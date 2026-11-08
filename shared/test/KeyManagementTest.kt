package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class KeyManagementTest {
    private val session = AccountSession("jwt", null)
    private val item =
        """{"id":42,"name":"demo","token_prefix":"prefix","created_at":null,"last_used_at":null,"revoked_at":null}"""

    private fun envelope(data: String) = """{"success":true,"code":0,"data":$data}"""

    private suspend fun awaitIdle(state: KeyManagementState) {
        while (state.busy) yield()
    }

    private class Vault : LocalKeyVault {
        override val enabled = true
        override val unavailableReason: String? = null
        val values = mutableMapOf<Pair<Long, Long>, String>()
        var failSave = false
        var failLookup = false
        var copies = 0
        var reveals = 0
        var cancellations = 0
        var revealResult: String? = null
        var revealGate: CompletableDeferred<Unit>? = null

        override fun hasKey(accountId: Long, keyId: Long): Boolean {
            if (failLookup) throw KeyVaultException("failed")
            return values.containsKey(accountId to keyId)
        }

        override fun save(accountId: Long, keyId: Long, secret: String) {
            if (failSave) throw KeyVaultException("failed")
            values[accountId to keyId] = secret
        }

        override suspend fun reveal(accountId: Long, keyId: Long): String? {
            reveals++
            revealGate?.let { withContext(NonCancellable) { it.await() } }
            return revealResult
        }

        override suspend fun copy(accountId: Long, keyId: Long): Boolean {
            copies++
            return true
        }

        override fun remove(accountId: Long, keyId: Long) {
            values.remove(accountId to keyId)
        }

        override fun cancelAuthentication() {
            cancellations++
        }
    }

    @Test
    fun createSavesAndRetryDoesNotCreateAgain() = runTest {
        var posts = 0
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath == "/api/auth/me" ->
                                respond(envelope("""{"id":7}"""))
                            request.method == HttpMethod.Post &&
                                request.url.encodedPath == "/api/tokens" -> {
                                posts++
                                respond(envelope("""{"item":$item,"token":"secret"}"""))
                            }
                            else -> respond(envelope("[]"))
                        }
                    }
                )
            )
        val vault = Vault()
        val state = KeyManagementState(client, session, vault, this, {}, {})
        state.load()
        awaitIdle(state)
        state.create("demo")
        awaitIdle(state)
        assertEquals("secret", vault.values[7L to 42L])
        assertNull(state.revealed)
        vault.failSave = true
        state.create("again")
        awaitIdle(state)
        assertNotNull(state.pendingSave)
        assertTrue(state.error.orEmpty().contains("官网已生成"))
        state.create("blocked")
        runCurrent()
        assertEquals(2, posts)
        state.leavePage()
        vault.failSave = false
        state.retrySave()
        assertNull(state.pendingSave)
        assertEquals(2, posts)
        state.dispose()
        client.close()
    }

    @Test
    fun failedRotationSaveBlocksOldCopyUntilRetry() = runTest {
        var rotations = 0
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath == "/api/auth/me" ->
                                respond(envelope("""{"id":7}"""))
                            request.method == HttpMethod.Post -> {
                                rotations++
                                respond(envelope("""{"item":$item,"token":"new-secret"}"""))
                            }
                            else -> respond(envelope("[$item]"))
                        }
                    }
                )
            )
        val vault =
            Vault().apply {
                values[7L to 42L] = "old-secret"
                failSave = true
            }
        val state = KeyManagementState(client, session, vault, this, {}, {})
        state.load()
        awaitIdle(state)
        val key = state.data!!.items.single()
        state.rotate(key)
        awaitIdle(state)
        assertNotNull(state.pendingSave)
        assertEquals("old-secret", vault.values[7L to 42L])
        state.reveal(key)
        state.copy(key)
        runCurrent()
        assertEquals(0, vault.reveals)
        assertEquals(0, vault.copies)
        assertNull(state.revealed)
        vault.failSave = false
        state.retrySave()
        assertEquals("new-secret", vault.values[7L to 42L])
        assertNull(state.pendingSave)
        vault.revealResult = vault.values[7L to 42L]
        state.reveal(key)
        runCurrent()
        assertEquals("new-secret", state.revealed?.secret)
        state.copy(key)
        runCurrent()
        assertEquals(1, vault.copies)
        assertEquals(1, rotations)
        state.dispose()
        client.close()
    }

    @Test
    fun authenticationCancellationAndCopiesAreIndependent() = runTest {
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/api/auth/me")
                            respond(envelope("""{"id":7}"""))
                        else respond(envelope("[$item]"))
                    }
                )
            )
        val vault = Vault().apply { values[7L to 42L] = "secret" }
        val state = KeyManagementState(client, session, vault, this, {}, {})
        state.load()
        awaitIdle(state)
        val key = state.data!!.items.single()
        vault.failLookup = true
        assertNull(state.hasKey(key))
        assertEquals("secret", vault.values[7L to 42L])
        vault.failLookup = false
        state.reveal(key)
        runCurrent()
        assertNull(state.revealed)
        vault.revealResult = "secret"
        state.reveal(key)
        runCurrent()
        assertNotNull(state.revealed)
        state.copy(key)
        runCurrent()
        state.copy(key)
        runCurrent()
        assertEquals(2, vault.copies)
        state.closeSecret()
        state.copy(key)
        state.leavePage()
        awaitIdle(state)
        assertEquals(2, vault.copies)
        val gate = CompletableDeferred<Unit>()
        vault.revealGate = gate
        state.reveal(key)
        runCurrent()
        state.leavePage()
        gate.complete(Unit)
        runCurrent()
        assertNull(state.revealed)
        assertTrue(vault.cancellations > 0)
        state.dispose()
        client.close()
    }

    @Test
    fun loadIsCachedAndRejectsChangedOwner() = runTest {
        var calls = 0
        var owner = 7
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        calls++
                        if (request.url.encodedPath == "/api/auth/me")
                            respond(envelope("""{"id":$owner}"""))
                        else respond(envelope("[$item]"))
                    }
                )
            )
        val vault =
            Vault().apply {
                values[7L to 42L] = "secret"
                revealResult = "secret"
            }
        val state = KeyManagementState(client, session, vault, this, {}, {})
        state.load()
        awaitIdle(state)
        state.leavePage()
        state.load()
        runCurrent()
        assertEquals(2, calls)
        val key = state.data!!.items.single()
        state.reveal(key)
        runCurrent()
        assertNotNull(state.revealed)
        owner = 8
        state.load(force = true)
        awaitIdle(state)
        assertNull(state.data)
        assertNull(state.revealed)
        state.reveal(key)
        state.copy(key)
        runCurrent()
        assertEquals(1, vault.reveals)
        assertEquals(0, vault.copies)
        state.load(force = true)
        runCurrent()
        assertEquals(4, calls)
        assertNotNull(state.error)
        state.dispose()
        client.close()
    }

    @Test
    fun unauthorizedClearsSecretsAndNotifies() = runTest {
        var unauthorized = false
        var expired = 0
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        when {
                            unauthorized -> respond("", HttpStatusCode.Unauthorized)
                            request.url.encodedPath == "/api/auth/me" ->
                                respond(envelope("""{"id":7}"""))
                            else -> respond(envelope("[$item]"))
                        }
                    }
                )
            )
        val vault =
            Vault().apply {
                values[7L to 42L] = "secret"
                revealResult = "secret"
            }
        val state = KeyManagementState(client, session, vault, this, {}, { expired++ })
        state.load()
        awaitIdle(state)
        state.reveal(state.data!!.items.single())
        runCurrent()
        unauthorized = true
        state.load(force = true)
        awaitIdle(state)
        assertNull(state.data)
        assertNull(state.revealed)
        assertNull(state.pendingSave)
        assertEquals(1, expired)
        assertEquals("secret", vault.values[7L to 42L])
        state.dispose()
        client.close()
    }

    @Test
    fun createSavesBeforeChannelLoadAndRetriesWithoutCreatingAgain() = runTest {
        val vault = Vault().apply { failSave = true }
        var posts = 0
        var channelRequests = 0
        var failChannels = true
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        when {
                            request.url.encodedPath == "/api/auth/me" ->
                                respond(envelope("""{"id":7}"""))
                            request.url.encodedPath == "/api/my/channels" -> {
                                channelRequests++
                                assertEquals("secret", vault.values[7L to 42L])
                                if (failChannels) respond("", HttpStatusCode.InternalServerError)
                                else
                                    respond(
                                        envelope("""[{"id":1,"name":"默认","default_routed":true}]""")
                                    )
                            }
                            request.method == HttpMethod.Post -> {
                                posts++
                                respond(envelope("""{"item":$item,"token":"secret"}"""))
                            }
                            else -> respond(envelope("[]"))
                        }
                    }
                )
            )
        val state = KeyManagementState(client, session, vault, this, {}, {})
        state.load()
        awaitIdle(state)
        state.create("demo")
        awaitIdle(state)
        assertNotNull(state.pendingSave)
        assertEquals(0, channelRequests)
        vault.failSave = false
        state.retrySave()
        awaitIdle(state)
        assertNotNull(state.channelError)
        assertNull(state.channels)
        assertEquals("secret", vault.values[7L to 42L])
        failChannels = false
        state.reloadChannels()
        awaitIdle(state)
        assertEquals(setOf(1L), state.channelSelection)
        assertEquals(1, posts)
        state.dispose()
        client.close()
    }

    @Test
    fun existingChannelsPreserveUnknownAndLoadedConflicts() = runTest {
        var selected = "[1,2,99]"
        val catalog =
            """[{"id":1,"name":"国产模型官渠","default_routed":true},{"id":2,"name":"阿里云直连","default_routed":false},{"id":3,"name":"Max 官渠","default_routed":false},{"id":4,"name":"Kiro 渠道","default_routed":false}]"""
        val submitted = mutableListOf<Set<Long>>()
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/api/auth/me" -> respond(envelope("""{"id":7}"""))
                            "/api/my/channels" -> respond(envelope(catalog))
                            "/api/tokens/42/channels" ->
                                if (request.method == HttpMethod.Put) {
                                    val body =
                                        Json.parseToJsonElement((request.body as TextContent).text)
                                            .jsonObject
                                    submitted +=
                                        body
                                            .getValue("provider_ids")
                                            .jsonArray
                                            .map { it.jsonPrimitive.long }
                                            .toSet()
                                    respond("", HttpStatusCode.NoContent)
                                } else respond(envelope("""{"channel_ids":$selected}"""))
                            else -> respond(envelope("[$item]"))
                        }
                    }
                )
            )
        val state = KeyManagementState(client, session, Vault(), this, {}, {})
        state.load()
        awaitIdle(state)
        val key = state.data!!.items.single()
        state.openChannels(key)
        awaitIdle(state)
        assertEquals(setOf(1L, 2L, 99L), state.channelSelection)
        state.toggleChannel(state.channels!![2], true)
        state.toggleChannel(state.channels!![3], true)
        assertEquals(setOf(1L, 2L, 3L, 99L), state.channelSelection)
        assertTrue(state.channelError.orEmpty().contains("Kiro 渠道"))
        state.defaultChannels()
        assertEquals(setOf(1L, 99L), state.channelSelection)
        state.saveChannels()
        awaitIdle(state)
        assertEquals(setOf(1L, 99L), submitted.single())
        selected = "[]"
        state.openChannels(key)
        awaitIdle(state)
        assertEquals(setOf(1L), state.channelSelection)
        state.toggleChannel(state.channels!!.first(), false)
        state.saveChannels()
        awaitIdle(state)
        assertEquals(emptySet(), submitted.last())
        state.dispose()
        client.close()
    }

    @Test
    fun createdKeyIsAuthenticatedAndShownAfterSavingOrSkippingChannels() = runTest {
        for (save in listOf(true, false)) {
            var creates = 0
            var channelSaves = 0
            val vault = Vault().apply { revealResult = "secret" }
            val client =
                AccountClient(
                    HttpClient(
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/api/auth/me" -> respond(envelope("""{"id":7}"""))
                                "/api/my/channels" ->
                                    respond(
                                        envelope("""[{"id":1,"name":"默认","default_routed":true}]""")
                                    )
                                "/api/tokens/42/channels" ->
                                    if (request.method == HttpMethod.Put) {
                                        channelSaves++
                                        respond("", HttpStatusCode.NoContent)
                                    } else respond(envelope("""{"channel_ids":[1]}"""))
                                "/api/tokens" ->
                                    if (request.method == HttpMethod.Post) {
                                        creates++
                                        respond(envelope("""{"item":$item,"token":"secret"}"""))
                                    } else respond(envelope("[]"))
                                else -> error("Unexpected endpoint")
                            }
                        }
                    )
                )
            val state = KeyManagementState(client, session, vault, this, {}, {})
            state.load()
            awaitIdle(state)
            state.create("demo")
            awaitIdle(state)
            assertEquals("secret", vault.values[7L to 42L])
            assertTrue(state.newKeyConfiguration)
            assertNull(state.revealed)
            assertEquals(0, vault.reveals)
            if (save) state.saveChannels() else state.finishChannels()
            awaitIdle(state)
            assertNull(state.channelItem)
            assertFalse(state.newKeyConfiguration)
            assertEquals(1, vault.reveals)
            assertEquals("secret", state.revealed?.secret)
            assertEquals(if (save) 1 else 0, channelSaves)
            assertEquals(1, creates)
            val key = state.data!!.items.single()
            state.closeSecret()
            state.openChannels(key)
            awaitIdle(state)
            if (save) state.saveChannels() else state.finishChannels()
            awaitIdle(state)
            assertNull(state.revealed)
            assertEquals(1, vault.reveals)
            state.dispose()
            client.close()
        }
    }

    @Test
    fun createdKeyChannelFailureKeepsContinuationAndAuthenticationCancellationKeepsItHidden() =
        runTest {
            var failSave = true
            var creates = 0
            val vault = Vault()
            val client =
                AccountClient(
                    HttpClient(
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/api/auth/me" -> respond(envelope("""{"id":7}"""))
                                "/api/my/channels" -> respond(envelope("[]"))
                                "/api/tokens/42/channels" ->
                                    if (failSave) respond("", HttpStatusCode.InternalServerError)
                                    else respond("", HttpStatusCode.NoContent)
                                "/api/tokens" ->
                                    if (request.method == HttpMethod.Post) {
                                        creates++
                                        respond(envelope("""{"item":$item,"token":"secret"}"""))
                                    } else respond(envelope("[]"))
                                else -> error("Unexpected endpoint")
                            }
                        }
                    )
                )
            val state = KeyManagementState(client, session, vault, this, {}, {})
            state.load()
            awaitIdle(state)
            state.create("demo")
            awaitIdle(state)
            state.saveChannels()
            awaitIdle(state)
            assertTrue(state.newKeyConfiguration)
            assertNotNull(state.channelItem)
            assertNotNull(state.channelError)
            assertEquals(0, vault.reveals)
            failSave = false
            state.saveChannels()
            awaitIdle(state)
            assertEquals(1, vault.reveals)
            assertNull(state.revealed)
            assertEquals("secret", vault.values[7L to 42L])
            assertEquals(1, creates)
            state.openChannels(state.data!!.items.single(), created = true)
            awaitIdle(state)
            state.finishChannels()
            state.leavePage()
            awaitIdle(state)
            assertEquals(1, vault.reveals)
            assertNull(state.revealed)
            val gate = CompletableDeferred<Unit>()
            vault.revealGate = gate
            vault.revealResult = "secret"
            state.openChannels(state.data!!.items.single(), created = true)
            awaitIdle(state)
            state.finishChannels()
            runCurrent()
            state.leavePage()
            gate.complete(Unit)
            awaitIdle(state)
            assertNull(state.revealed)
            assertTrue(vault.cancellations > 0)
            state.dispose()
            client.close()
        }

    @Test
    fun leavingDuringChannelLoadDiscardsLateResultAndUnauthorizedClearsEditor() = runTest {
        val gate = CompletableDeferred<Unit>()
        var delay = true
        var unauthorized = false
        var expired = 0
        val client =
            AccountClient(
                HttpClient(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/api/auth/me" -> respond(envelope("""{"id":7}"""))
                            "/api/my/channels" -> {
                                if (delay) withContext(NonCancellable) { gate.await() }
                                if (unauthorized) respond("", HttpStatusCode.Unauthorized)
                                else respond(envelope("[]"))
                            }
                            "/api/tokens/42/channels" -> respond(envelope("""{"channel_ids":[]}"""))
                            else -> respond(envelope("[$item]"))
                        }
                    }
                )
            )
        val state = KeyManagementState(client, session, Vault(), this, {}, { expired++ })
        state.load()
        awaitIdle(state)
        val key = state.data!!.items.single()
        state.openChannels(key)
        runCurrent()
        state.leavePage()
        gate.complete(Unit)
        awaitIdle(state)
        assertNull(state.channelItem)
        assertNull(state.channels)
        delay = false
        unauthorized = true
        state.openChannels(key)
        awaitIdle(state)
        assertNull(state.channelItem)
        assertNull(state.data)
        assertEquals(1, expired)
        state.dispose()
        client.close()
    }
}
