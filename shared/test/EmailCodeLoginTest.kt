package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class EmailCodeLoginTest {
    private val initial = Instant.parse("2026-11-08T12:00:00Z")
    private val signedIn =
        """{"token":"test-session","user":{"id":7,"email":"demo@example.test","tos_accepted":true}}"""

    private fun envelope(data: String) = """{"success":true,"code":0,"data":$data}"""

    @Test
    fun sendOnlyEmailAndHonorTheServerResendInterval() = runTest {
        var clock = initial
        val paths = mutableListOf<String>()
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        paths += request.url.encodedPath
                        assertNull(request.headers[HttpHeaders.Authorization])
                        assertNull(request.headers["X-Turnstile-Token"])
                        if (request.url.encodedPath == "/api/public/auth-settings")
                            respond(envelope("""{"turnstile_enforced":false}"""))
                        else {
                            assertEquals("/api/auth/login/send-code", request.url.encodedPath)
                            val body =
                                Json.parseToJsonElement((request.body as TextContent).text)
                                    .jsonObject
                            assertEquals(setOf("email"), body.keys)
                            assertEquals("demo@example.test", body["email"]?.jsonPrimitive?.content)
                            respond(
                                envelope("""{"resend_after_seconds":31,"message":"accepted"}""")
                            )
                        }
                    }
                )
            ) {
                clock
            }
        assertEquals(31L, client.sendLoginCode(" demo@example.test ").resendAfterSeconds)
        assertFailsWith<AccountAccessException> { client.sendLoginCode("demo@example.test") }
        assertEquals(2, paths.size)
        clock = Instant.fromEpochSeconds(initial.epochSeconds + 32)
        assertEquals(31L, client.sendLoginCode("demo@example.test").resendAfterSeconds)
        assertEquals(4, paths.size)
        client.close()
    }

    @Test
    fun emailVerificationCanSignInDirectlyOrContinueWithTotp() = runTest {
        for (requiresTotp in listOf(false, true)) {
            val paths = mutableListOf<String>()
            val client =
                AccountClient(
                    HttpClient(
                        MockEngine { request ->
                            paths += request.url.encodedPath
                            val body =
                                Json.parseToJsonElement((request.body as TextContent).text)
                                    .jsonObject
                            assertNull(request.headers[HttpHeaders.Authorization])
                            when (request.url.encodedPath) {
                                "/api/auth/login/verify" -> {
                                    assertEquals(setOf("email", "verification_code"), body.keys)
                                    assertEquals(
                                        "AB12",
                                        body["verification_code"]?.jsonPrimitive?.content,
                                    )
                                    respond(
                                        envelope(
                                            if (requiresTotp)
                                                """{"requires_totp":true,"pending_token":"test-pending"}"""
                                            else signedIn
                                        )
                                    )
                                }
                                else -> {
                                    assertEquals("/api/auth/login/totp", request.url.encodedPath)
                                    assertEquals(setOf("pending_token", "totp_code"), body.keys)
                                    assertEquals(
                                        "test-pending",
                                        body["pending_token"]?.jsonPrimitive?.content,
                                    )
                                    assertEquals(
                                        "123456",
                                        body["totp_code"]?.jsonPrimitive?.content,
                                    )
                                    respond(envelope(signedIn))
                                }
                            }
                        }
                    )
                )
            val result = client.loginWithCode(" demo@example.test ", " AB12 ")
            val session =
                if (requiresTotp) {
                    val challenge = assertIs<AccountLoginResult.TotpRequired>(result)
                    client.verifyTotp(challenge.pendingToken, "123456")
                } else assertIs<AccountLoginResult.SignedIn>(result).session
            assertEquals("test-session", session.token)
            assertEquals(if (requiresTotp) 2 else 1, paths.size)
            client.close()
        }
    }

    @Test
    fun mandatoryOrUnknownHumanVerificationSettingsNeverSendMail() = runTest {
        for (settings in listOf("""{"turnstile_enforced":true}""", "{}")) {
            var calls = 0
            val client =
                AccountClient(
                    HttpClient(
                        MockEngine { request ->
                            calls++
                            assertEquals("/api/public/auth-settings", request.url.encodedPath)
                            respond(envelope(settings))
                        }
                    )
                )
            assertFailsWith<AccountAccessException> { client.sendLoginCode("demo@example.test") }
            assertEquals(1, calls)
            client.close()
        }
        for (data in listOf("null", "{}", """{"resend_after_seconds":-1}""")) {
            val client =
                AccountClient(
                    HttpClient(
                        MockEngine { request ->
                            respond(
                                envelope(
                                    if (request.url.encodedPath == "/api/public/auth-settings")
                                        """{"turnstile_enforced":false}"""
                                    else data
                                )
                            )
                        }
                    )
                )
            assertNull(client.sendLoginCode("demo@example.test").resendAfterSeconds)
            client.close()
        }
    }

    @Test
    fun invalidInputAndRejectedEmailCodesDoNotLeakCredentials() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized)) {
            var calls = 0
            val client =
                AccountClient(
                    HttpClient(
                        MockEngine {
                            calls++
                            respond("private-code private-token", status)
                        }
                    )
                )
            assertFailsWith<AccountAccessException> { client.sendLoginCode("invalid") }
            assertFailsWith<AccountAccessException> {
                client.loginWithCode("demo@example.test", " ")
            }
            assertFailsWith<AccountAccessException> {
                client.loginWithCode("demo@example.test", "1234567")
            }
            assertEquals(0, calls)
            val error =
                assertFailsWith<AccountAccessException> {
                    client.loginWithCode("demo@example.test", "123456")
                }
            assertTrue(error.message.orEmpty().contains("验证码"))
            assertFalse(error.message.orEmpty().contains("private"))
            client.close()
        }
    }

    @Test
    fun localPasswordCooldownDoesNotInventAnEmailEndpointRestriction() = runTest {
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/api/auth/login" ->
                                respond(
                                    """{"success":false,"message":"account temporarily locked due to too many failed attempts","data":{"type":"rate_limit_error"}}""",
                                    HttpStatusCode.TooManyRequests,
                                )
                            "/api/public/auth-settings" ->
                                respond(envelope("""{"turnstile_enforced":false}"""))
                            "/api/auth/login/send-code" ->
                                respond(envelope("""{"resend_after_seconds":20}"""))
                            else -> respond(envelope(signedIn))
                        }
                    }
                )
            ) {
                initial
            }
        assertFailsWith<AccountAccessException> {
            client.login("demo@example.test", "test-password")
        }
        // 只证明客户端不串用冷却；真实服务端仍可独立拒绝任何入口。
        assertEquals(20L, client.sendLoginCode("demo@example.test").resendAfterSeconds)
        assertIs<AccountLoginResult.SignedIn>(client.loginWithCode("demo@example.test", "123456"))
        client.close()
    }

    @Test
    fun simultaneousSendActionsDoNotGenerateDuplicateMailRequests() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sends = 0
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/api/public/auth-settings") {
                            started.complete(Unit)
                            release.await()
                            respond(envelope("""{"turnstile_enforced":false}"""))
                        } else {
                            sends++
                            respond(envelope("""{"resend_after_seconds":10}"""))
                        }
                    }
                )
            ) {
                initial
            }
        val first = async { client.sendLoginCode("demo@example.test") }
        started.await()
        assertFailsWith<AccountAccessException> { client.sendLoginCode("demo@example.test") }
        release.complete(Unit)
        assertEquals(10L, first.await().resendAfterSeconds)
        assertEquals(1, sends)
        client.close()
    }

    @Test
    fun unknownResendIntervalsStayAssociatedWithTheirEmail() = runTest {
        var sends = 0
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/api/public/auth-settings")
                            respond(envelope("""{"turnstile_enforced":false}"""))
                        else {
                            sends++
                            respond(envelope("{}"))
                        }
                    }
                )
            ) {
                initial
            }
        assertNull(client.sendLoginCode("first@example.test").resendAfterSeconds)
        assertTrue(client.isCodeResendUnknown(" FIRST@example.test "))
        assertFalse(client.isCodeResendUnknown("second@example.test"))
        assertNull(client.sendLoginCode("second@example.test").resendAfterSeconds)
        assertFailsWith<AccountAccessException> { client.sendLoginCode(" FIRST@example.test ") }
        assertEquals(2, sends)
        client.close()
    }
}
