@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class MonitorClientTest {
    private val initial = Instant.parse("2026-11-08T12:00:00Z")
    private val subscription =
        """{"data":{"plan":{"title":"Pro"},"hard_limit_usd":"50","access_until":0}}"""
    private val bill = """{"data":{"total_usage":"1250"}}"""
    private val costs =
        """{"data":[{"start_time":1794096000,"results":[{"amount":{"value":"1.25","currency":"usd"}}]}],"has_more":false}"""
    private val usage =
        """{"data":[{"start_time":1794096000,"results":[{"model":"example","cost_usd":"1.20","num_model_requests":2,"input_tokens":10,"input_cached_tokens":3,"output_tokens":5}]}],"has_more":false}"""

    @Test
    fun accountUsageAndAnonymousProbes() = runTest {
        val seen = mutableListOf<String>()
        val http =
            HttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPath
                    assertEquals("application/json", request.headers[HttpHeaders.Accept])
                    val authorization = request.headers[HttpHeaders.Authorization]
                    seen += path
                    when (path) {
                        "/v1/models" -> {
                            assertNull(authorization)
                            respond("", HttpStatusCode.Unauthorized)
                        }
                        "/v1/user-auth-credential/whoami" -> {
                            assertEquals("Bearer test-key", authorization)
                            respond("""{"email":"person@example.com"}""")
                        }
                        "/v1/dashboard/billing/subscription" -> respond(subscription)
                        "/v1/dashboard/billing/usage" -> {
                            assertEquals("2026-11-01", request.url.parameters["start_date"])
                            assertEquals("2026-11-09", request.url.parameters["end_date"])
                            respond(bill)
                        }
                        "/v1/organization/costs",
                        "/v1/organization/usage/completions" -> {
                            assertEquals("1794182400", request.url.parameters["end_time"])
                            assertEquals("1793577600", request.url.parameters["start_time"])
                            assertEquals("1d", request.url.parameters["bucket_width"])
                            assertEquals("7", request.url.parameters["limit"])
                            assertEquals(
                                if (path.endsWith("completions")) "model" else null,
                                request.url.parameters["group_by"],
                            )
                            respond(if (path.endsWith("costs")) costs else usage)
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            ) {
                followRedirects = false
            }
        val client = MonitorClient(http) { initial }
        val snapshot = client.load("test-key", RelayLine.Overseas)
        val account = assertNotNull(snapshot.account)
        assertEquals(50.0, account.limitUsd)
        assertEquals(12.5, account.usedUsd)
        assertEquals(37.5, account.remainingUsd)
        assertNull(account.expiresAt)
        assertEquals("Pro", account.plan)
        val report = assertNotNull(snapshot.usage)
        assertEquals(7, report.daily.size)
        assertEquals(1.25, report.daily.single { it.date == "2026-11-08" }.costUsd)
        assertEquals(1.20, report.models.single().costUsd)
        assertEquals(3, seen.count { it == "/v1/models" })
        assertTrue(snapshot.lines.all { it.reachable && it.status == 401 })
        client.close()
    }

    @Test
    fun fallbackAndMissingFieldsStayUnknown() = runTest {
        val paths = mutableListOf<String>()
        val http =
            HttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPath
                    paths += path
                    when (path) {
                        "/v1/models" -> respond("", HttpStatusCode.OK)
                        "/v1/user-auth-credential/whoami" -> respond("{}")
                        "/v1/dashboard/billing/subscription" -> respond("", HttpStatusCode.NotFound)
                        "/dashboard/billing/subscription" ->
                            respond("""{"hard_limit_usd":"100000000","access_until":0}""")
                        "/dashboard/billing/usage" -> respond("{}")
                        "/v1/organization/costs" -> respond("", HttpStatusCode.NotFound)
                        "/v1/organization/usage/completions" ->
                            respond(
                                """{"data":[{"start_time":1794096000,"results":[{"model":"m","input_tokens":4}]}]}"""
                            )
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            ) {
                followRedirects = false
            }
        val client = MonitorClient(http) { initial }
        val snapshot = client.load("k", RelayLine.China)
        assertTrue(assertNotNull(snapshot.account).unlimited)
        assertNull(snapshot.account.usedUsd)
        assertNull(snapshot.account.remainingUsd)
        assertTrue(paths.contains("/dashboard/billing/usage"))
        val row = assertNotNull(snapshot.usage).daily.single { it.date == "2026-11-08" }
        assertNull(row.costUsd)
        assertNull(row.requests)
        assertEquals(4L, row.inputTokens)
        assertTrue(snapshot.usage.warnings.isNotEmpty())
        assertTrue(client.load("k", RelayLine.China).usage!!.fromCache)
        assertEquals(2, paths.count { it.startsWith("/v1/organization/") })
        client.close()
    }

    @Test
    fun cacheIsolationRateLimitAndUnauthorized() = runTest {
        var clock = initial
        var mode = "ok"
        var orgCalls = 0
        val http =
            HttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        path == "/v1/models" -> respond("", HttpStatusCode.Unauthorized)
                        path == "/v1/user-auth-credential/whoami" && mode == "unauthorized" ->
                            respond("secret response", HttpStatusCode.Unauthorized)
                        path == "/v1/user-auth-credential/whoami" -> respond("{}")
                        path.endsWith("subscription") -> respond(subscription)
                        path.endsWith("billing/usage") -> respond(bill)
                        path.startsWith("/v1/organization/") -> {
                            orgCalls++
                            if (mode == "limited")
                                respond(
                                    "secret response",
                                    HttpStatusCode.TooManyRequests,
                                    headersOf(HttpHeaders.RetryAfter, "3600"),
                                )
                            else respond(if (path.endsWith("costs")) costs else usage)
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            ) {
                followRedirects = false
            }
        val client = MonitorClient(http) { clock }
        client.load("key-a", RelayLine.Overseas)
        assertTrue(client.load("key-a", RelayLine.China).usage!!.fromCache)
        assertEquals(2, orgCalls)
        client.load("key-b", RelayLine.Overseas)
        assertEquals(4, orgCalls)
        clock = Instant.fromEpochSeconds(initial.epochSeconds + 1801)
        mode = "limited"
        val stale = assertNotNull(client.load("key-a", RelayLine.Overseas).usage)
        assertTrue(stale.fromCache && stale.stale)
        assertTrue(stale.warnings.any { it.contains("受限") })
        val count = orgCalls
        client.load("key-a", RelayLine.Overseas)
        assertEquals(count, orgCalls)
        clock = Instant.fromEpochSeconds(initial.epochSeconds + 5402)
        mode = "unauthorized"
        val error = assertFailsWith<MonitorException> { client.load("key-a", RelayLine.Overseas) }
        assertFalse(error.message.orEmpty().contains("key-a"))
        assertFalse(error.message.orEmpty().contains("secret response"))
        client.close()
    }

    @Test
    fun paginationAndRedirectAreNotFollowed() = runTest {
        var pages = 0
        val http =
            HttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        path == "/v1/models" ->
                            respond(
                                "",
                                HttpStatusCode.Found,
                                headersOf(HttpHeaders.Location, "https://other.example/"),
                            )
                        path == "/v1/user-auth-credential/whoami" -> respond("{}")
                        path.endsWith("subscription") -> respond(subscription)
                        path.endsWith("billing/usage") -> respond(bill)
                        path.endsWith("costs") -> respond("""{"data":[],"has_more":false}""")
                        path.endsWith("completions") -> {
                            pages++
                            if (pages > 1) assertNotNull(request.url.parameters["page"])
                            respond("""{"data":[],"has_more":true,"next_page":"next$pages"}""")
                        }
                        else -> error("Unexpected URL")
                    }
                }
            ) {
                followRedirects = false
            }
        val client = MonitorClient(http) { initial }
        val snapshot = client.load("k", RelayLine.Cdn, 30)
        assertEquals(3, pages)
        assertTrue(snapshot.usage!!.warnings.any { it.contains("不完整") })
        assertTrue(snapshot.lines.all { !it.reachable && it.status == 302 })
        client.close()
    }

    @Test
    fun invalidInputAndNonFiniteMoney() = runTest {
        val http =
            HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/v1/models" -> respond("", HttpStatusCode.OK)
                        "/v1/user-auth-credential/whoami" -> respond("{}")
                        "/v1/dashboard/billing/subscription" ->
                            respond("""{"hard_limit_usd":"NaN","access_until":0}""")
                        "/v1/dashboard/billing/usage" -> respond("""{"total_usage":-1}""")
                        else -> respond("""{"data":[]}""")
                    }
                }
            ) {
                followRedirects = false
            }
        val client = MonitorClient(http) { initial }
        assertFailsWith<MonitorException> { client.load("bad key", RelayLine.Overseas) }
        assertFailsWith<MonitorException> { client.load("x", RelayLine.Overseas, 32) }
        val account = assertNotNull(client.load("x", RelayLine.Overseas).account)
        assertNull(account.limitUsd)
        assertNull(account.usedUsd)
        assertNull(account.remainingUsd)
        client.close()
    }

    @Test
    fun malformedBillingFallsBackAndUnlimitedHasNoRemaining() = runTest {
        val http =
            HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/v1/models" -> respond("", HttpStatusCode.Unauthorized)
                        "/v1/user-auth-credential/whoami" -> respond("{}")
                        "/v1/dashboard/billing/subscription" -> respond("not json")
                        "/dashboard/billing/subscription" ->
                            respond(
                                """{"hard_limit_usd":100000000,"plan":"Unlimited","access_until":1794096000000}"""
                            )
                        "/dashboard/billing/usage" -> respond("{}")
                        "/v1/dashboard/billing/usage" -> respond("""{"total_usage":100}""")
                        else -> respond("""{"data":[]}""")
                    }
                }
            ) {
                followRedirects = false
            }
        val client = MonitorClient(http) { initial }
        val account = assertNotNull(client.load("key", RelayLine.Overseas).account)
        assertEquals("Unlimited", account.plan)
        assertEquals(1.0, account.usedUsd)
        assertEquals(Instant.parse("2026-11-08T00:00:00Z"), account.expiresAt)
        assertNull(account.remainingUsd)
        client.close()
    }

    @Test
    fun emptyDaysFallbackAndLongCountsArePreserved() = runTest {
        val http =
            HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/v1/models" -> respond("", HttpStatusCode.OK)
                        "/v1/user-auth-credential/whoami" -> respond("{}")
                        "/v1/dashboard/billing/subscription" -> respond(subscription)
                        "/v1/dashboard/billing/usage" -> respond(bill)
                        "/v1/organization/costs" ->
                            respond(
                                """{"data":[{"start_time":1794096000,"results":[{"amount":{"value":"invalid","currency":"usd"}}]}]}"""
                            )
                        "/v1/organization/usage/completions" ->
                            respond(
                                """{"data":[{"start_time":1794096000,"result":[{"cost_usd":2,"num_model_requests":9007199254740993,"input_tokens":1,"input_cached_tokens":0,"output_tokens":1},{"model":"a","cost_usd":2,"num_model_requests":3,"input_tokens":1,"input_cached_tokens":0,"output_tokens":1}]},{"start_time":1,"results":[{"model":"outside","cost_usd":999}]}]}"""
                            )
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            ) {
                followRedirects = false
            }
        val client = MonitorClient(http) { initial }
        val report = assertNotNull(client.load("key", RelayLine.Overseas).usage)
        val today = report.daily.single { it.date == "2026-11-08" }
        assertEquals(4.0, today.costUsd)
        assertEquals(9007199254740996L, today.requests)
        assertEquals(0.0, report.daily.first().costUsd)
        assertEquals(0L, report.daily.first().requests)
        assertEquals("未命名模型", report.models.first().model)
        assertEquals(2, report.models.size)
        assertTrue(report.warnings.any { it.contains("费用使用") })
        client.close()
    }

    @Test
    fun repeatedCursorAndUnavailableSourcesAreReported() = runTest {
        var pages = 0
        var unavailable = false
        val http =
            HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/v1/models" -> respond("", HttpStatusCode.OK)
                        "/v1/user-auth-credential/whoami" -> respond("{}")
                        "/v1/dashboard/billing/subscription" -> respond(subscription)
                        "/v1/dashboard/billing/usage" -> respond(bill)
                        "/v1/organization/costs" ->
                            if (unavailable) respond("", HttpStatusCode.ServiceUnavailable)
                            else respond("""{"data":[]}""")
                        "/v1/organization/usage/completions" -> {
                            if (unavailable) respond("", HttpStatusCode.ServiceUnavailable)
                            else {
                                pages++
                                respond("""{"data":[],"has_more":true,"next_page":"same"}""")
                            }
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            ) {
                followRedirects = false
            }
        val client = MonitorClient(http) { initial }
        val first = client.load("first", RelayLine.Overseas)
        assertEquals(2, pages)
        assertTrue(first.usage!!.warnings.any { it.contains("不完整") })
        assertNull(first.usage.daily.first().requests)
        unavailable = true
        val second = client.load("second", RelayLine.Overseas)
        assertNull(second.usage)
        assertTrue(second.warnings.isNotEmpty())
        client.close()
    }

    @Test
    fun oversizedBodyAndHugeRetryAfterStayUnavailable() = runTest {
        var requests = 0
        val oversized = " ".repeat(2 * 1024 * 1024 + 1)
        val http =
            HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/v1/models" -> respond("", HttpStatusCode.OK)
                        "/v1/user-auth-credential/whoami" -> respond(oversized)
                        "/v1/dashboard/billing/subscription" -> respond(subscription)
                        "/v1/dashboard/billing/usage" -> respond(bill)
                        "/v1/organization/costs",
                        "/v1/organization/usage/completions" -> {
                            requests++
                            respond(
                                "",
                                HttpStatusCode.TooManyRequests,
                                headersOf(HttpHeaders.RetryAfter, "999999999999999999999999"),
                            )
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            ) {
                followRedirects = false
            }
        val client = MonitorClient(http) { initial }
        val snapshot = client.load("key", RelayLine.Overseas)
        assertNull(snapshot.account?.email)
        assertNull(snapshot.usage)
        assertTrue(snapshot.warnings.any { it.contains("受限") })
        val count = requests
        val again = client.load("key", RelayLine.Overseas)
        assertEquals(count, requests)
        assertNull(again.usage)
        assertTrue(again.warnings.any { it.contains("受限") })
        client.close()
    }
}
