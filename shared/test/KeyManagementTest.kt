package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

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
                            request.method == HttpMethod.Post -> {
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
}
