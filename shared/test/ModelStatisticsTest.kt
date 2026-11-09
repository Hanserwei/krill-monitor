package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class ModelStatisticsTest {
    private val initial = Instant.parse("2026-11-08T20:00:00Z")
    private fun envelope(data: String) = """{"success":true,"data":$data}"""

    @Test
    fun rangeParsingCacheRefreshAndIsolation() = runTest {
        var time = initial
        var meCalls = 0
        val bodies = mutableListOf<JsonObject>()
        val client = AccountClient(HttpClient(MockEngine { request ->
            assertTrue(request.headers[HttpHeaders.Authorization] in listOf("Bearer token-a", "Bearer token-b"))
            if (request.url.encodedPath == "/api/auth/me") {
                meCalls++
                respond(envelope("""{"id":1}"""))
            } else {
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/api/request-logs/stats", request.url.encodedPath)
                bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                respond(envelope("""{"total_requests":2,"failed_requests":-1,"input_tokens":10,"output_tokens":3,"cache_read_input_tokens":4,"cache_creation_input_tokens":"bad","cache_rate_read_tokens":4,"cache_rate_eligible_tokens":14,"reasoning_tokens":1,"total_tokens":13,"total_cost_usd":"NaN","overall_tps":5,"unknown":123}"""))
            }
        }), now = { time })
        val a = AccountSession("token-a", null)
        val first = client.loadModelStatistics(a, " model/a ", 7, initial)
        assertEquals(" model/a ", bodies.single()["model"]?.jsonPrimitive?.content)
        assertEquals("2026-11-03T00:00:00", bodies.single()["start_time"]?.jsonPrimitive?.content)
        assertEquals("2026-11-09T23:59:59", bodies.single()["end_time"]?.jsonPrimitive?.content)
        assertEquals(10L, first.statistics.inputTokens)
        assertEquals(3L, first.statistics.outputTokens)
        assertEquals(4L, first.statistics.cachedInputTokens)
        assertEquals(1L, first.statistics.reasoningTokens)
        assertEquals(4L, first.statistics.cacheRateReadTokens)
        assertEquals(14L, first.statistics.cacheRateEligibleTokens)
        assertNull(first.statistics.failedRequests)
        assertNull(first.statistics.cacheWriteTokens)
        assertNull(first.statistics.costUsd)
        time = Instant.fromEpochSeconds(initial.epochSeconds + 299)
        assertEquals(first, client.loadModelStatistics(a, " model/a ", 7, initial))
        assertEquals(2, meCalls)
        assertEquals(1, bodies.size)
        client.loadModelStatistics(a, " model/a ", 7, initial, refresh = true)
        assertEquals(2, bodies.size)
        time = Instant.fromEpochSeconds(time.epochSeconds + 1)
        val refreshedRange = time
        client.loadModelStatistics(a, " model/a ", 7, refreshedRange)
        assertEquals(3, bodies.size)
        client.loadModelStatistics(a, " model/a ", 7, refreshedRange)
        assertEquals(3, bodies.size)
        time = Instant.fromEpochSeconds(time.epochSeconds + 300)
        client.loadModelStatistics(a, " model/a ", 7, initial)
        client.loadModelStatistics(a, "other", 7, initial)
        client.loadModelStatistics(a, " model/a ", 30, initial)
        assertEquals("2026-10-11T00:00:00", bodies.last()["start_time"]?.jsonPrimitive?.content)
        client.loadModelStatistics(a, " model/a ", 7, Instant.fromEpochSeconds(initial.epochSeconds + 86400))
        client.loadModelStatistics(AccountSession("token-b", null), " model/a ", 7, initial)
        client.loadModelStatistics(a, " model/a ", 7, initial)
        assertEquals(9, bodies.size)
        val before = meCalls
        assertFailsWith<AccountAccessException> { client.loadModelStatistics(a, "  ", 7, initial) }
        assertFailsWith<AccountAccessException> { client.loadModelStatistics(a, "a\nb", 7, initial) }
        assertFailsWith<AccountAccessException> { client.loadModelStatistics(a, "a".repeat(513), 7, initial) }
        assertFailsWith<AccountAccessException> { client.loadModelStatistics(a, "a", 8, initial) }
        assertEquals(before, meCalls)
        client.close()
    }

    @Test
    fun failureFallbackRateLimitCancellationAndUnauthorized() = runTest {
        var time = initial
        var status = HttpStatusCode.OK
        var cancel = false
        var calls = 0
        var count = 1
        val client = AccountClient(HttpClient(MockEngine { request ->
            calls++
            if (cancel) throw CancellationException("cancel")
            if (request.url.encodedPath == "/api/auth/me") {
                if (status == HttpStatusCode.Unauthorized) respond("", status)
                else respond(envelope("""{"id":1}"""))
            } else if (status != HttpStatusCode.OK) {
                respond("{}", status, headersOf(HttpHeaders.RetryAfter, "60"))
            } else respond(envelope("""{"input_tokens":$count}"""))
        }), now = { time })
        val session = AccountSession("token", null)
        status = HttpStatusCode.InternalServerError
        assertFailsWith<AccountAccessException> { client.loadModelStatistics(session, "m", 7, initial) }
        status = HttpStatusCode.OK
        val first = client.loadModelStatistics(session, "m", 7, initial)
        status = HttpStatusCode.InternalServerError
        val stale = client.loadModelStatistics(session, "m", 7, initial, true)
        assertTrue(stale.stale)
        assertNotNull(stale.warning)
        assertEquals(first.statistics, stale.statistics)
        status = HttpStatusCode.OK
        count = 2
        val recovered = client.loadModelStatistics(session, "m", 7, initial)
        assertFalse(recovered.stale)
        assertEquals(2L, recovered.statistics.inputTokens)
        status = HttpStatusCode.TooManyRequests
        assertTrue(client.loadModelStatistics(session, "m", 7, initial, true).stale)
        val before = calls
        assertTrue(client.loadModelStatistics(session, "m", 7, initial, true).stale)
        assertEquals(before, calls)
        time = Instant.fromEpochSeconds(time.epochSeconds + 61)
        cancel = true
        assertFailsWith<CancellationException> { client.loadModelStatistics(session, "m", 7, initial, true) }
        cancel = false
        status = HttpStatusCode.Unauthorized
        assertFailsWith<AccountSessionExpired> { client.loadModelStatistics(session, "m", 7, initial) }
        status = HttpStatusCode.InternalServerError
        assertFailsWith<AccountAccessException> { client.loadModelStatistics(session, "m", 7, initial) }
        client.close()
    }

    @Test
    fun lateResponseCannotRestoreClearedAccountCache() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var block = true
        var statsCalls = 0
        val client = AccountClient(HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/auth/me") respond(envelope("""{"id":1}"""))
            else {
                statsCalls++
                if (block) {
                    block = false
                    started.complete(Unit)
                    release.await()
                }
                respond(envelope("""{"input_tokens":1}"""))
            }
        }), now = { initial })
        val a = AccountSession("token-a", null)
        val pending = async {
            assertFailsWith<AccountSessionExpired> { client.loadModelStatistics(a, "m", 7, initial) }
        }
        started.await()
        assertFailsWith<AccountAccessException> { client.loadModelStatistics(a, "m", 7, initial) }
        client.loadModelStatistics(AccountSession("token-b", null), "m", 7, initial)
        release.complete(Unit)
        pending.await()
        client.loadModelStatistics(a, "m", 7, initial)
        assertEquals(3, statsCalls)
        client.close()
    }
}
