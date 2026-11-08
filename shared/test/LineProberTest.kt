package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class LineProberTest {
    @Test
    fun probesAreAnonymousAndOnlyReachTheFixedLines() = runTest {
        val hosts = mutableSetOf<String>()
        val prober =
            LineProber(
                HttpClient(
                    MockEngine { request ->
                        hosts += request.url.host
                        assertEquals(HttpMethod.Get, request.method)
                        assertEquals("/v1/models", request.url.encodedPath)
                        assertNull(request.headers[HttpHeaders.Authorization])
                        assertNull(request.headers[HttpHeaders.Cookie])
                        respond(
                            "",
                            when (request.url.host) {
                                "api.kbsfdxc.net" -> HttpStatusCode.Unauthorized
                                "api-slb.kbsfdxc.net" -> HttpStatusCode.OK
                                else -> HttpStatusCode.ServiceUnavailable
                            },
                        )
                    }
                ) {
                    followRedirects = false
                }
            )
        val lines = prober.probeLines()
        assertEquals(RelayLine.entries.map { it.origin.removePrefix("https://") }.toSet(), hosts)
        assertEquals(2, lines.count { it.reachable })
        assertTrue(lines.all { (it.latencyMillis ?: -1) >= 0 })
        assertEquals(503, lines.last().status)
        prober.close()
    }

    @Test
    fun networkFailuresRemainLocalToTheirLineAndNeverExposeRawErrors() = runTest {
        val prober =
            LineProber(
                HttpClient(
                    MockEngine { request ->
                        if (request.url.host == "api.kbsfdxc.net")
                            throw IllegalStateException("private network detail")
                        respond("", HttpStatusCode.Unauthorized)
                    }
                )
            )
        val lines = prober.probeLines()
        assertNull(lines.first().status)
        assertFalse(lines.first().reachable)
        assertEquals("线路探测失败", lines.first().message)
        assertTrue(lines.drop(1).all { it.reachable })
        prober.close()
    }

    @Test
    fun cancellationIsNotConvertedIntoALineFailure() = runTest {
        val prober = LineProber(HttpClient(MockEngine { throw CancellationException("cancelled") }))
        assertFailsWith<CancellationException> { prober.probeLines() }
        prober.close()
    }
}
