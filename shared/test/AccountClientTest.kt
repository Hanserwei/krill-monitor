package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class AccountClientTest {
    private val initial = Instant.parse("2026-11-08T20:00:00Z") // 北京时间已经是 11 月 9 日。
    private val user = """{"id":7,"email":"demo@example.test","tos_accepted":true}"""
    private val subscription =
        """{"subscriptions":[
        {"status":"active","plan":{"name":"Week","billing_type":"usd_weekly","duration_days":30},"quota":{"daily_limit_usd":"20","used_usd":"3","remaining_usd":"17","window_reset_at":"2026-11-12T00:00:00+08:00"},"total_limit_usd":"80","total_used_usd":"8","total_remaining_usd":"72","subscription_end_at":"2026-12-01T00:00:00+08:00"},
        {"status":"active","plan":{"name":"Daily","billing_type":"usd_daily"},"total_limit_usd":"100","total_used_usd":"25","total_remaining_usd":"75"},
        {"status":"active","plan":{"name":"Month","billing_type":"usd_monthly"},"quota":{"daily_limit_usd":"90","used_usd":"10","remaining_usd":"80"}},
        {"status":"active","plan":{"name":"Credits","billing_type":"credits","total_credits":"1000"},"quota":{"limit_credits":"1000","remaining_credits":"900"}},
        {"status":"active","plan":{"name":"Count","billing_type":"request_count","rate_limit_5h":30,"rate_limit_weekly":100,"rate_limit_monthly":300}}
    ],"request_count_quota":{"used_5h":2,"limit_5h":30,"used_weekly":5,"limit_weekly":100,"used_monthly":8,"limit_monthly":300}}"""
    private val stats =
        """{"total_requests":9,"failed_requests":1,"input_tokens":100,"output_tokens":20,"cache_read_input_tokens":30,"cache_creation_input_tokens":10,"reasoning_tokens":4,"total_tokens":120,"total_cost_usd":"1.25","overall_tps":15.5,"trend":[{"bucket_start":"2026-11-08T16:00:00Z","request_count":4,"total_cost_usd":"0.50","total_tokens":50},{"bucket_start":"2026-11-08T17:00:00Z","request_count":5,"total_cost_usd":"0.75","total_tokens":70}]}"""
    private val models =
        """{"items":[{"model":"demo-model","request_count":9,"total_cost_usd":"1.25","total_tokens":120}]}"""

    private fun envelope(data: String) = """{"success":true,"code":0,"data":$data}"""

    @Test
    fun passwordLoginAndTotpUseDifferentCredentials() = runTest {
        val seen = mutableListOf<String>()
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        assertNull(request.headers[HttpHeaders.Authorization])
                        val body =
                            Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        seen += request.url.encodedPath
                        when (request.url.encodedPath) {
                            "/api/auth/login" -> {
                                assertEquals(
                                    "demo@example.test",
                                    body["email"]?.jsonPrimitive?.content,
                                )
                                assertEquals(
                                    "fake-password",
                                    body["password"]?.jsonPrimitive?.content,
                                )
                                respond(
                                    envelope(
                                        """{"requires_totp":true,"pending_token":"fake-pending"}"""
                                    )
                                )
                            }
                            else -> {
                                assertEquals(
                                    "fake-pending",
                                    body["pending_token"]?.jsonPrimitive?.content,
                                )
                                assertEquals("123456", body["totp_code"]?.jsonPrimitive?.content)
                                respond(envelope("""{"token":"fake-session","user":$user}"""))
                            }
                        }
                    }
                ) {
                    followRedirects = false
                }
            )
        val challenge =
            assertIs<AccountLoginResult.TotpRequired>(
                client.login(" demo@example.test ", "fake-password")
            )
        val session = client.verifyTotp(challenge.pendingToken, "123456")
        assertEquals("fake-session", session.token)
        assertEquals("demo@example.test", session.email)
        assertFalse(session.toString().contains("fake-session"))
        assertEquals(listOf("/api/auth/login", "/api/auth/login/totp"), seen)
        client.close()
    }

    @Test
    fun loginRejectsInvalidInputsAndMissingTokenWithoutLeaking() = runTest {
        var calls = 0
        var data = "{}"
        val client =
            AccountClient(
                HttpClient(
                    MockEngine {
                        calls++
                        respond(envelope(data))
                    }
                )
            )
        assertFailsWith<AccountAccessException> { client.login("invalid", "private-password") }
        assertFailsWith<AccountAccessException> { client.login("a@b.test", "") }
        assertFailsWith<AccountAccessException> { client.verifyTotp("pending", "bad") }
        assertEquals(0, calls)
        val missing =
            assertFailsWith<AccountAccessException> { client.login("a@b.test", "private-password") }
        assertFalse(missing.message.orEmpty().contains("private-password"))
        data = """{"token":"session-token","user":{"id":1,"tos_accepted":false}}"""
        assertTrue(
            assertFailsWith<AccountAccessException> { client.login("a@b.test", "private-password") }
                .message
                .orEmpty()
                .contains("服务条款")
        )
        client.close()
    }

    @Test
    fun dashboardUsesBeijingDatesWalletAndPlanSpecificUnits() = runTest {
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        assertEquals(
                            "Bearer account-jwt",
                            request.headers[HttpHeaders.Authorization],
                        )
                        assertEquals("www.krill-code.com", request.url.host)
                        val data =
                            when (request.url.encodedPath) {
                                "/api/auth/me" -> user
                                "/api/subscription" -> subscription
                                "/api/credits" ->
                                    """{"balance_usd":"42.50","welfare_balance_usd":"3.00"}"""
                                else -> {
                                    val range =
                                        Json.parseToJsonElement((request.body as TextContent).text)
                                            .jsonObject
                                    assertEquals(
                                        "2026-11-03T00:00:00",
                                        range["start_time"]?.jsonPrimitive?.content,
                                    )
                                    assertEquals(
                                        "2026-11-09T23:59:59",
                                        range["end_time"]?.jsonPrimitive?.content,
                                    )
                                    if (request.url.encodedPath.endsWith("model-stats")) models
                                    else stats
                                }
                            }
                        respond(envelope(data))
                    }
                ) {
                    followRedirects = false
                },
                now = { initial },
            )
        val dashboard = client.load(AccountSession("account-jwt", null))
        assertEquals(42.5, dashboard.balanceUsd)
        assertEquals(3.0, dashboard.welfareUsd)
        assertEquals(5, dashboard.subscriptions.size)
        val week = dashboard.subscriptions.first()
        assertEquals("本周额度", week.quotas.first().label)
        assertEquals(17.0, week.quotas.first().remaining)
        assertEquals(72.0, week.quotas.last().remaining)
        assertEquals("credits", dashboard.subscriptions[3].quotas.single().unit)
        assertEquals("次", dashboard.subscriptions[4].quotas.first().unit)
        assertEquals(1.25, dashboard.statistics?.costUsd)
        assertEquals(10, dashboard.statistics?.cacheWriteTokens)
        val usage = assertNotNull(dashboard.usage)
        assertEquals("UTC+8", usage.timezone)
        assertEquals(7, usage.daily.size)
        assertEquals("2026-11-09", usage.daily.last().date)
        assertEquals(1.25, usage.daily.last().costUsd)
        assertEquals(9, usage.daily.last().requests)
        assertNull(usage.daily.last().inputTokens)
        assertEquals(120, usage.models.single().totalTokens)
        assertNull(usage.models.single().inputTokens)
        assertTrue(dashboard.warnings.isEmpty())
        client.close()
    }

    @Test
    fun cacheIsIsolatedAndUnauthorizedAlwaysClearsTheSession() = runTest {
        var clock = initial
        var calls = 0
        var unauthorized = false
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        calls++
                        if (unauthorized) respond("private-jwt", HttpStatusCode.Unauthorized)
                        else
                            respond(
                                envelope(
                                    when (request.url.encodedPath) {
                                        "/api/auth/me" -> user
                                        "/api/subscription" -> """{"subscriptions":[]}"""
                                        "/api/credits" ->
                                            """{"balance_usd":2,"welfare_balance_usd":0}"""
                                        "/api/request-logs/model-stats" -> models
                                        else -> stats
                                    }
                                )
                            )
                    }
                )
            ) {
                clock
            }
        client.load(AccountSession("token-a", null))
        val before = calls
        assertTrue(client.load(AccountSession("token-a", null)).usage!!.fromCache)
        assertEquals(before + 1, calls) // 命中缓存仍校验 me。
        assertFalse(client.load(AccountSession("token-b", null)).usage!!.fromCache)
        clock = Instant.fromEpochSeconds(initial.epochSeconds + 301)
        assertFalse(client.load(AccountSession("token-b", null)).usage!!.fromCache)
        unauthorized = true
        assertFailsWith<AccountSessionExpired> { client.load(AccountSession("token-b", null)) }
        unauthorized = false
        assertFalse(client.load(AccountSession("token-b", null)).usage!!.fromCache)
        client.close()
    }

    @Test
    fun errorsAndOptionalFieldsDoNotExposeCredentialsOrInventZeros() = runTest {
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/api/auth/me" -> respond(envelope(user))
                            "/api/subscription" ->
                                respond("private-token", HttpStatusCode.InternalServerError)
                            "/api/credits" -> respond(envelope("{}"))
                            "/api/request-logs/model-stats" -> respond(envelope("{}"))
                            else ->
                                respond(
                                    envelope(
                                        """{"trend":[{"bucket_start":"2026-11-09T00:00:00","request_count":1}]}"""
                                    )
                                )
                        }
                    }
                )
            ) {
                initial
            }
        val result = client.load(AccountSession("private-token", null))
        assertNull(result.balanceUsd)
        assertNull(result.usage?.daily?.last()?.costUsd)
        assertTrue(result.warnings.any { it.contains("HTTP 500") })
        assertFalse(result.warnings.joinToString().contains("private-token"))
        assertTrue(result.usage!!.warnings.isNotEmpty())
        client.close()
    }

    @Test
    fun rateLimitAndCancellationAreNotRetriedOrHidden() = runTest {
        var calls = 0
        val client =
            AccountClient(
                HttpClient(
                    MockEngine {
                        calls++
                        respond(
                            "password token",
                            HttpStatusCode.TooManyRequests,
                            headersOf(HttpHeaders.RetryAfter, "60"),
                        )
                    }
                )
            ) {
                initial
            }
        assertFailsWith<AccountAccessException> { client.login("a@b.test", "password") }
        assertFailsWith<AccountAccessException> { client.login("a@b.test", "password") }
        assertEquals(1, calls)
        client.close()
        val cancelled =
            AccountClient(HttpClient(MockEngine { throw CancellationException("cancelled") }))
        assertFailsWith<CancellationException> { cancelled.login("a@b.test", "password") }
        cancelled.close()
        val range = accountRange(Instant.parse("2026-12-31T16:01:00Z"), 30)
        assertEquals("2026-12-03T00:00:00", range["start_time"]?.jsonPrimitive?.content)
        assertEquals("2027-01-01T23:59:59", range["end_time"]?.jsonPrimitive?.content)
    }

    @Test
    fun bindingErrorsRedirectsAndOversizedResponsesAreSafe() = runTest {
        for (mode in listOf("binding", "redirect", "oversized")) {
            var calls = 0
            val client =
                AccountClient(
                    HttpClient(
                        MockEngine {
                            calls++
                            when (mode) {
                                "binding" ->
                                    respond(
                                        """{"success":false,"data":{"type":"email_binding_required"},"message":"private-password private-token"}""",
                                        HttpStatusCode.Forbidden,
                                    )
                                "redirect" ->
                                    respond(
                                        "",
                                        HttpStatusCode.Found,
                                        headersOf(
                                            HttpHeaders.Location,
                                            "https://untrusted.example/",
                                        ),
                                    )
                                else -> respond("x".repeat(2 * 1024 * 1024 + 1))
                            }
                        }
                    ) {
                        followRedirects = false
                    }
                )
            val error =
                assertFailsWith<AccountAccessException> {
                    client.login("demo@example.test", "private-password")
                }
            assertEquals(1, calls)
            assertFalse(error.message.orEmpty().contains("private"))
            if (mode == "binding") assertTrue(error.message.orEmpty().contains("绑定邮箱"))
            if (mode == "redirect") assertTrue(error.message.orEmpty().contains("HTTP 302"))
            if (mode == "oversized") assertTrue(error.message.orEmpty().contains("过大"))
            client.close()
        }
    }

    @Test
    fun lockedAccountsAndUnspecifiedRateLimitsKeepAccurateSafeMessages() = runTest {
        for ((serverMessage, retryAfter) in
            listOf(
                "account temporarily locked due to too many failed attempts" to null,
                "account temporarily locked due to too many failed attempts" to "120",
                "rate limit exceeded" to null,
                "rate limit exceeded" to "120",
                "rate limit exceeded" to "invalid",
            )) {
            var calls = 0
            val response = buildJsonObject {
                put("success", false)
                put("code", 429)
                put("message", "$serverMessage private-password demo@example.test")
                putJsonObject("data") { put("type", "rate_limit_error") }
            }
                .toString()
            val client =
                AccountClient(
                    HttpClient(
                        MockEngine {
                            calls++
                            respond(
                                response,
                                HttpStatusCode.TooManyRequests,
                                if (retryAfter == null) headersOf()
                                else headersOf(HttpHeaders.RetryAfter, retryAfter),
                            )
                        }
                    )
                ) {
                    initial
                }
            val first =
                assertFailsWith<AccountAccessException> {
                    client.login("demo@example.test", "private-password")
                }
            val second =
                assertFailsWith<AccountAccessException> {
                    client.login("demo@example.test", "private-password")
                }
            assertEquals(1, calls)
            assertEquals(first.message, second.message)
            assertFalse(first.message.orEmpty().contains("private-password"))
            assertFalse(first.message.orEmpty().contains("demo@example.test"))
            assertEquals(serverMessage.contains("locked"), first.message.orEmpty().contains("锁定"))
            if (retryAfter == "120") assertTrue(first.message.orEmpty().contains("服务端建议"))
            else {
                assertTrue(first.message.orEmpty().contains("未提供"))
                assertFalse(first.message.orEmpty().contains("60 秒"))
            }
            client.close()
        }
    }
}
