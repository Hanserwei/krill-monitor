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

fun initializeMonitorKeyStore(context: Context) {
    monitorApplicationContext = context.applicationContext
}

actual fun createMonitorKeyStore(): MonitorKeyStore =
    AndroidMonitorKeyStore(monitorApplicationContext)

private class AndroidMonitorKeyStore(private val context: Context?) : MonitorKeyStore {
    override val isSecure = true

    override fun load(): String? {
        val preferences =
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return null
        return try {
            val stored = preferences.getString(PREF_KEY, null) ?: return null
            val payload = Base64.decode(stored, Base64.DEFAULT)
            require(payload.size > GCM_IV_LENGTH + GCM_TAG_LENGTH_BITS / 8)
            val secret =
                keyStore().getKey(KEY_ALIAS, null) as? SecretKey
                    ?: error("Keystore key unavailable")
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
            throw IllegalStateException("无法读取已保存的 Key")
        }
    }

    override fun save(key: String): Boolean =
        try {
            val preferences = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (preferences == null) false
            else {
                val secret =
                    keyStore().getKey(KEY_ALIAS, null) as? SecretKey
                        ?: KeyGenerator.getInstance(
                                KeyProperties.KEY_ALGORITHM_AES,
                                ANDROID_KEYSTORE_PROVIDER,
                            )
                            .apply {
                                init(
                                    KeyGenParameterSpec.Builder(
                                            KEY_ALIAS,
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
                val ciphertext = cipher.doFinal(key.toByteArray(StandardCharsets.UTF_8))
                val stored = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
                preferences.edit().putString(PREF_KEY, stored).commit()
            }
        } catch (_: Exception) {
            false
        }

    override fun clear(): Boolean =
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
