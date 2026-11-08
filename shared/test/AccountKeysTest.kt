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
    fun channelRequestsUseExactPathsAndBodies() = runTest {
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
                        assertTrue(request.url.parameters.isEmpty())
                        seen += "${request.method.value} ${request.url.encodedPath}"
                        when (seen.size) {
                            1 ->
                                respond(
                                    envelope(
                                        """[{"id":1,"name":"one","default_routed":true,"billing_multiplier":"1.5"},{"id":"2","name":"two","default_routed":false,"billing_multiplier":null}]"""
                                    )
                                )
                            2 -> respond(envelope("""{"channel_ids":[1,"2",1]}"""))
                            3 -> {
                                assertEquals(
                                    Json.parseToJsonElement("""{"provider_ids":[1,99]}"""),
                                    Json.parseToJsonElement((request.body as TextContent).text),
                                )
                                respond("", HttpStatusCode.NoContent)
                            }
                            else -> {
                                assertEquals(
                                    Json.parseToJsonElement("""{"provider_ids":[]}"""),
                                    Json.parseToJsonElement((request.body as TextContent).text),
                                )
                                respond(envelope("null"))
                            }
                        }
                    }
                )
            )
        assertEquals(
            listOf(ApiKeyChannel(1, "one", true, 1.5), ApiKeyChannel(2, "two", false, null)),
            client.listApiKeyChannels(session),
        )
        assertEquals(setOf(1L, 2L), client.getApiKeyChannels(session, 42))
        client.setApiKeyChannels(session, 42, linkedSetOf(1L, 99L))
        client.setApiKeyChannels(session, 42, emptySet())
        assertEquals(
            listOf(
                "GET /api/my/channels",
                "GET /api/tokens/42/channels",
                "PUT /api/tokens/42/channels",
                "PUT /api/tokens/42/channels",
            ),
            seen,
        )
        client.close()
    }

    @Test
    fun channelResponsesAndInputsAreValidated() = runTest {
        var response = ""
        var calls = 0
        val client =
            AccountClient(
                HttpClient(
                    MockEngine {
                        calls++
                        respond(envelope(response))
                    }
                )
            )
        assertFailsWith<AccountSessionExpired> {
            client.listApiKeyChannels(AccountSession("bad token", null))
        }
        assertFailsWith<AccountAccessException> { client.getApiKeyChannels(session, 0) }
        assertFailsWith<AccountAccessException> {
            client.setApiKeyChannels(session, -1, emptySet())
        }
        assertFailsWith<AccountAccessException> { client.setApiKeyChannels(session, 1, setOf(0)) }
        assertEquals(0, calls)
        for (data in
            listOf(
                "null",
                "{}",
                "[null]",
                """[{"id":0,"name":"x","default_routed":true}]""",
                """[{"id":1,"name":3,"default_routed":true}]""",
                """[{"id":1,"name":"x"}]""",
                """[{"id":1,"name":"x","default_routed":"true"}]""",
                """[{"id":1,"name":"x","default_routed":true},{"id":1,"name":"y","default_routed":false}]""",
            )) {
            response = data
            assertFailsWith<AccountAccessException> { client.listApiKeyChannels(session) }
        }
        response = """[{"id":1,"name":"x","default_routed":false,"billing_multiplier":"NaN"}]"""
        assertNull(client.listApiKeyChannels(session).single().billingMultiplier)
        for (data in
            listOf(
                "null",
                "{}",
                """{"channel_ids":null}""",
                """{"channel_ids":[-1]}""",
                """{"channel_ids":[0]}""",
                """{"channel_ids":[1.5]}""",
                """{"channel_ids":[null]}""",
            )) {
            response = data
            assertFailsWith<AccountAccessException> { client.getApiKeyChannels(session, 1) }
        }
        response = """{"channel_ids":[]}"""
        assertEquals(emptySet(), client.getApiKeyChannels(session, 1))
        client.close()
    }

    @Test
    fun channelSaveFailuresAreSafeAndNotRetried() = runTest {
        for (status in
            listOf(
                HttpStatusCode.Unauthorized,
                HttpStatusCode.TooManyRequests,
                HttpStatusCode.InternalServerError,
            )) {
            var calls = 0
            val client =
                AccountClient(
                    HttpClient(
                        MockEngine {
                            calls++
                            respond("""{"message":"private-secret"}""", status)
                        }
                    )
                )
            val error =
                if (status == HttpStatusCode.Unauthorized)
                    assertFailsWith<AccountSessionExpired> {
                        client.setApiKeyChannels(session, 1, setOf(2))
                    }
                else
                    assertFailsWith<AccountAccessException> {
                        client.setApiKeyChannels(session, 1, setOf(2))
                    }
            assertFalse(error.message.orEmpty().contains("private-secret"))
            if (status != HttpStatusCode.Unauthorized)
                assertTrue(error.message.orEmpty().contains("重新读取配置"))
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
        assertFailsWith<CancellationException> { client.setApiKeyChannels(session, 1, emptySet()) }
        assertEquals(1, calls)
        client.close()
        val offline =
            AccountClient(HttpClient(MockEngine { throw IllegalStateException("private-secret") }))
        val error =
            assertFailsWith<AccountAccessException> {
                offline.setApiKeyChannels(session, 1, emptySet())
            }
        assertTrue(error.message.orEmpty().contains("渠道保存结果未确认"))
        assertFalse(error.message.orEmpty().contains("private-secret"))
        offline.close()
    }

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
