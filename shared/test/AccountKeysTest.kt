package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class AccountKeysTest {
    private val session = AccountSession("account-jwt", null)
    private val item =
        """{"id":42,"name":"demo","token_prefix":"12345678901234567890","created_at":"2026-11-08T00:00:00Z","last_used_at":null,"revoked_at":null}"""

    private fun envelope(data: String) = """{"success":true,"code":0,"data":$data}"""

    @Test
    fun listUsesVerifiedOwnerAndUnpagedArray() = runTest {
        val seen = mutableListOf<String>()
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        seen += request.url.encodedPath
                        assertEquals(
                            "Bearer account-jwt",
                            request.headers[HttpHeaders.Authorization],
                        )
                        assertNull(request.headers[HttpHeaders.Cookie])
                        assertTrue(request.url.parameters.isEmpty())
                        when (request.url.encodedPath) {
                            "/api/auth/me" -> respond(envelope("""{"id":7,"tos_accepted":true}"""))
                            else -> respond(envelope("[$item]"))
                        }
                    }
                )
            )
        val result = client.listApiKeys(session)
        assertEquals(7L, result.accountId)
        assertEquals(42L, result.items.single().id)
        assertEquals("1234567890123456", result.items.single().prefix)
        assertFalse(result.items.single().revoked)
        assertEquals(listOf("/api/auth/me", "/api/tokens"), seen)
        client.close()
    }

    @Test
    fun mutationsUseExactMethodsPathsAndBodies() = runTest {
        val seen = mutableListOf<String>()
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        assertEquals(
                            "Bearer account-jwt",
                            request.headers[HttpHeaders.Authorization],
                        )
                        assertNull(request.headers[HttpHeaders.Cookie])
                        val path = request.url.encodedPath
                        seen += "${request.method.value} $path"
                        when (seen.size) {
                            1 -> {
                                assertEquals(
                                    JsonNull,
                                    Json.parseToJsonElement((request.body as TextContent).text)
                                        .jsonObject["name"],
                                )
                                respond(envelope("""{"item":$item,"token":"secret-value"}"""))
                            }
                            2 -> {
                                assertEquals(
                                    "renamed",
                                    Json.parseToJsonElement((request.body as TextContent).text)
                                        .jsonObject["name"]
                                        ?.jsonPrimitive
                                        ?.content,
                                )
                                respond(envelope(item))
                            }
                            3 -> {
                                assertFalse(request.body is TextContent)
                                respond(envelope("""{"item":$item,"token":"rotated-value"}"""))
                            }
                            4 -> respond("", HttpStatusCode.NoContent)
                            else -> respond(envelope("null"))
                        }
                    }
                )
            )
        val created = client.createApiKey(session, "  ")
        assertEquals("secret-value", created.secret)
        assertFalse(created.toString().contains(created.secret))
        assertEquals(42L, client.renameApiKey(session, 42, " renamed ").id)
        assertEquals("rotated-value", client.rotateApiKey(session, 42).secret)
        client.revokeApiKey(session, 42)
        client.destroyApiKey(session, 42)
        assertEquals(
            listOf(
                "POST /api/tokens",
                "PUT /api/tokens/42",
                "POST /api/tokens/42/rotate",
                "DELETE /api/tokens/42",
                "DELETE /api/tokens/42/destroy",
            ),
            seen,
        )
        client.close()
    }

    @Test
    fun invalidInputsNeverSendAndInvalidOwnerStopsList() = runTest {
        var calls = 0
        val client =
            AccountClient(
                HttpClient(
                    MockEngine {
                        calls++
                        respond(envelope("""{"id":0}"""))
                    }
                )
            )
        assertFailsWith<AccountSessionExpired> {
            client.listApiKeys(AccountSession("bad token", null))
        }
        assertFailsWith<AccountSessionExpired> {
            client.createApiKey(AccountSession("bad token", null), "")
        }
        assertFailsWith<AccountAccessException> { client.renameApiKey(session, 0, "") }
        assertFailsWith<AccountAccessException> { client.rotateApiKey(session, -1) }
        assertFailsWith<AccountAccessException> { client.revokeApiKey(session, Long.MIN_VALUE) }
        assertFailsWith<AccountAccessException> { client.destroyApiKey(session, 0) }
        assertEquals(0, calls)
        assertFailsWith<AccountAccessException> { client.listApiKeys(session) }
        assertEquals(1, calls)
        client.close()
    }

    @Test
    fun malformedSecretsAndResponsesDoNotLeakAndWarnOfUncertainOutcome() = runTest {
        var response = envelope("""{"item":$item,"token":null}""")
        val client =
            AccountClient(HttpClient(MockEngine { respond(response) }) { followRedirects = false })
        val missing = assertFailsWith<AccountAccessException> { client.createApiKey(session, "") }
        assertTrue(missing.message.orEmpty().contains("可能已生效"))
        response = envelope("""{"item":$item,"token":"private-secret"}""")
        val wrongId = assertFailsWith<AccountAccessException> { client.rotateApiKey(session, 43) }
        assertTrue(wrongId.message.orEmpty().contains("先刷新"))
        assertFalse(wrongId.message.orEmpty().contains("private-secret"))
        response = "private-secret"
        assertFalse(
            assertFailsWith<AccountAccessException> { client.createApiKey(session, "") }
                .message
                .orEmpty()
                .contains("private-secret")
        )
        response = "x".repeat(2 * 1024 * 1024 + 1)
        assertTrue(
            assertFailsWith<AccountAccessException> { client.createApiKey(session, "") }
                .message
                .orEmpty()
                .contains("可能已生效")
        )
        client.close()
    }

    @Test
    fun listRejectsPaginationAndTreatsMalformedRevocationAsRevoked() = runTest {
        var tokens = """{"items":[]}"""
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/api/auth/me")
                            respond(envelope("""{"id":1}"""))
                        else respond(envelope(tokens))
                    }
                )
            )
        assertFailsWith<AccountAccessException> { client.listApiKeys(session) }
        tokens = """[{"id":9223372036854775807,"revoked_at":"invalid"}]"""
        val key = client.listApiKeys(session).items.single()
        assertEquals(Long.MAX_VALUE, key.id)
        assertTrue(key.revoked)
        client.close()
    }

    @Test
    fun authRateLimitAndCancellationAreNotRetried() = runTest {
        for (status in
            listOf(
                HttpStatusCode.Unauthorized,
                HttpStatusCode.Forbidden,
                HttpStatusCode.TooManyRequests,
                HttpStatusCode.Found,
            )) {
            var calls = 0
            val client =
                AccountClient(
                    HttpClient(
                        MockEngine {
                            calls++
                            respond("""{"message":"private-secret"}""", status)
                        }
                    ) {
                        followRedirects = false
                    }
                )
            val error =
                if (status == HttpStatusCode.Unauthorized)
                    assertFailsWith<AccountSessionExpired> { client.createApiKey(session, "") }
                else assertFailsWith<AccountAccessException> { client.createApiKey(session, "") }
            assertFalse(error.message.orEmpty().contains("private-secret"))
            assertEquals(1, calls)
            client.close()
        }
        var calls = 0
        val client =
            AccountClient(
                HttpClient(
                    MockEngine {
                        calls++
                        throw CancellationException("cancelled")
                    }
                )
            )
        assertFailsWith<CancellationException> { client.rotateApiKey(session, 42) }
        assertEquals(1, calls)
        client.close()
    }

    @Test
    fun concurrentSecretMutationsDoNotQueue() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val client =
            AccountClient(
                HttpClient(
                    MockEngine {
                        calls++
                        entered.complete(Unit)
                        release.await()
                        respond(envelope("""{"item":$item,"token":"secret"}"""))
                    }
                )
            )
        val first = async { client.createApiKey(session, "one") }
        entered.await()
        assertFailsWith<AccountAccessException> { client.createApiKey(session, "two") }
        release.complete(Unit)
        first.await()
        assertEquals(1, calls)
        client.close()
    }
}
