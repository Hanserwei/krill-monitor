package com.hanserwei.krill_monitor

import androidx.compose.runtime.Composable

private object DisabledKeyVault : LocalKeyVault {
    override val enabled = false
    override val unavailableReason = "网页版暂不支持本地保存 Key"

    override fun hasKey(accountId: Long, keyId: Long) = false

    override fun save(accountId: Long, keyId: Long, secret: String) {
        throw KeyVaultException(unavailableReason)
    }

    override suspend fun reveal(accountId: Long, keyId: Long): String? = null

    override suspend fun copy(accountId: Long, keyId: Long) = false

    override fun remove(accountId: Long, keyId: Long) = Unit

    override fun cancelAuthentication() = Unit
}

@Composable internal actual fun rememberLocalKeyVault(): LocalKeyVault = DisabledKeyVault

@Composable internal actual fun ProtectKeyDisplay(onBackground: () -> Unit) = Unit
