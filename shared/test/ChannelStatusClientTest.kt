package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class ChannelStatusClientTest {
    private val start = Instant.parse("2026-11-08T00:00:00Z")

    private fun payload(channels: String, perf: String = "[]") =
        """{"success":true,"code":0,"data":{"channels":$channels,"perf":$perf}}"""

    private fun channel(key: String, status: String = "1", fixed: String = "null") =
        """{"channel_key":"$key","provider":"OpenAI","model_name":"model","channel":"official","current_status":$status,"fixed_status":$fixed,"history":[{"ts":"2026-11-08 00:01:00","s":2},{"ts":"2026-11-08T01:00:00+02:00","s":0},{"ts":"bad","s":1},{"ts":"2026-11-08T00:00:00Z","s":9}]}"""

    @Test
    fun anonymousQueryParsingAndUtc() = runTest {
        val channels =
            "[${channel("a", "0")},${channel("b", "1", "2")},${channel("c", "2", "\"bad\"")},${channel("d", "1")},${channel("e", "2")}]"
        val client =
            ChannelStatusClient(
                HttpClient(
                    MockEngine { request ->
                        assertEquals(HttpMethod.Get, request.method)
                        assertEquals("/api/public/channel-status", request.url.encodedPath)
                        assertEquals("hours=24", request.url.encodedQuery)
                        assertNull(request.headers[HttpHeaders.Authorization])
                        assertNull(request.headers[HttpHeaders.Cookie])
                        respond(
                            payload(
                                channels,
                                """[{"channel_key":"a","throughput_p50_tps":null,"throughput_avg_tps":12.5,"cache_rate":1.1,"avg_image_time_ms":-1}]""",
                            )
                        )
                    }
                ) {
                    followRedirects = false
                },
                "https://example.test",
                { start },
            )
        val snapshot = client.load()
        assertEquals(listOf(0, 2, null, 1, 2), snapshot.channels.map { it.status })
        val a = snapshot.channels.first()
        assertEquals(12.5, a.throughputTps)
        assertNull(a.cacheRate)
        assertNull(a.imageTimeMillis)
        assertEquals(Instant.parse("2026-11-07T23:00:00Z"), a.history.first().at)
        assertEquals(listOf(0, null, 2), a.history.map { it.status })
        assertNull(snapshot.channels[1].throughputTps)
        client.close()
    }

    @Test
    fun cacheRefreshRateLimitAndStale() = runTest {
        var time = start
        var calls = 0
        val client =
            ChannelStatusClient(
                HttpClient(
                    MockEngine {
                        calls++
                        if (calls == 2)
                            respond(
                                "",
                                HttpStatusCode.TooManyRequests,
                                headersOf(HttpHeaders.RetryAfter, "120"),
                            )
                        else respond(payload("[${channel("a")} ]"))
                    }
                ),
                "https://example.test",
                { time },
            )
        val initial = client.load()
        assertEquals(initial, client.load())
        assertEquals(1, calls)
        val stale = client.load(refresh = true)
        assertTrue(stale.stale)
        assertNotNull(stale.warning)
        assertEquals(initial.fetchedAt, stale.fetchedAt)
        client.load(refresh = true)
        assertEquals(2, calls)
        time += 121.seconds
        assertFalse(client.load().stale)
        assertEquals(3, calls)
        client.close()
    }

    @Test
    fun rejectsMalformedDuplicateRedirectAndOversize() = runTest {
        var body = payload("[${channel("a")},${channel("a")}]")
        var status = HttpStatusCode.OK
        val client =
            ChannelStatusClient(
                HttpClient(MockEngine { respond(body, status) }) { followRedirects = false },
                "https://example.test",
                { start },
            )
        assertFailsWith<AccountAccessException> { client.load() }
        body = payload("{}")
        assertFailsWith<AccountAccessException> { client.load() }
        body = "x".repeat(8 * 1024 * 1024 + 1)
        assertFailsWith<AccountAccessException> { client.load() }
        status = HttpStatusCode.Found
        assertFailsWith<AccountAccessException> { client.load() }
        client.close()
    }

    @Test
    fun cancellationPropagatesAndEmptyIsExplicit() = runTest {
        var cancel = true
        val client =
            ChannelStatusClient(
                HttpClient(
                    MockEngine {
                        if (cancel) throw CancellationException("cancel")
                        respond(payload("[]"))
                    }
                ),
                "https://example.test",
                { start },
            )
        assertFailsWith<CancellationException> { client.load() }
        cancel = false
        assertNotNull(client.load().warning)
        client.close()
    }
}
