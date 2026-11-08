package com.hanserwei.krill_monitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "krill_monitor_key_v1"
private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val PREFS_NAME = "krill_monitor_secret"
private const val PREF_KEY = "encrypted_key"
private const val GCM_IV_LENGTH = 12
private const val GCM_TAG_LENGTH_BITS = 128
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"

private var monitorApplicationContext: Context? = null

fun initializeSessionStore(context: Context) {
    monitorApplicationContext = context.applicationContext
}

actual fun createSessionStore(): SessionStore = AndroidSessionStore(monitorApplicationContext)

private class AndroidSessionStore(private val context: Context?) : SessionStore {
    // 保持账号别名和密文记录不变，升级无需重新登录。
    private val alias = "krill_monitor_session_v1"
    private val preferenceKey = "account_session"
    override val isSecure = true

    override fun load(): String? {
        val preferences =
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return null
        return try {
            val stored = preferences.getString(preferenceKey, null) ?: return null
            val payload = Base64.decode(stored, Base64.DEFAULT)
            require(payload.size > GCM_IV_LENGTH + GCM_TAG_LENGTH_BITS / 8)
            val secret =
                keyStore().getKey(alias, null) as? SecretKey ?: error("Keystore key unavailable")
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secret,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.copyOfRange(0, GCM_IV_LENGTH)),
            )
            String(
                cipher.doFinal(payload.copyOfRange(GCM_IV_LENGTH, payload.size)),
                StandardCharsets.UTF_8,
            )
        } catch (_: Exception) {
            // 失效密钥或损坏密文不能继续复用，清理后让用户重新输入。
            clear()
            throw IllegalStateException("无法读取已保存的账号会话")
        }
    }

    override fun save(token: String): Boolean =
        try {
            val preferences = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (preferences == null) false
            else {
                val secret =
                    keyStore().getKey(alias, null) as? SecretKey
                        ?: KeyGenerator.getInstance(
                                KeyProperties.KEY_ALGORITHM_AES,
                                ANDROID_KEYSTORE_PROVIDER,
                            )
                            .apply {
                                init(
                                    KeyGenParameterSpec.Builder(
                                            alias,
                                            KeyProperties.PURPOSE_ENCRYPT or
                                                KeyProperties.PURPOSE_DECRYPT,
                                        )
                                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                        .setEncryptionPaddings(
                                            KeyProperties.ENCRYPTION_PADDING_NONE
                                        )
                                        .build()
                                )
                            }
                            .generateKey()
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                // AndroidKeyStore 自行生成随机 IV，不能传入调用方生成的 IV。
                cipher.init(Cipher.ENCRYPT_MODE, secret)
                val ciphertext = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
                val stored = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
                preferences.edit().putString(preferenceKey, stored).commit()
            }
        } catch (_: Exception) {
            false
        }

    override fun clear(): Boolean =
        try {
            val preferences = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (preferences == null) false
            else {
                val cleared = preferences.edit().remove(preferenceKey).commit()
                keyStore().deleteEntry(alias)
                cleared
            }
        } catch (_: Exception) {
            false
        }

    override fun clearLegacyKey(): Boolean =
        try {
            val preferences = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (preferences == null) false
            else {
                val cleared = preferences.edit().remove(PREF_KEY).commit()
                keyStore().deleteEntry(KEY_ALIAS)
                cleared
            }
        } catch (_: Exception) {
            false
        }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
}
