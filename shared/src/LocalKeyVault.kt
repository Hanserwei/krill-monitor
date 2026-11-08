package com.hanserwei.krill_monitor

import androidx.compose.runtime.Composable

class KeyVaultException(message: String) : Exception(message)

interface LocalKeyVault {
    val enabled: Boolean
    val unavailableReason: String?

    fun hasKey(accountId: Long, keyId: Long): Boolean

    fun save(accountId: Long, keyId: Long, secret: String)

    suspend fun reveal(accountId: Long, keyId: Long): String?

    suspend fun copy(accountId: Long, keyId: Long): Boolean

    fun remove(accountId: Long, keyId: Long)

    fun cancelAuthentication()
}

@Composable internal expect fun rememberLocalKeyVault(): LocalKeyVault

@Composable internal expect fun ProtectKeyDisplay(onBackground: () -> Unit)
