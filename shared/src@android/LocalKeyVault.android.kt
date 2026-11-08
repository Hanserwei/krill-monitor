package com.hanserwei.krill_monitor

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.lang.ref.WeakReference
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

private const val VAULT_ALIAS = "krill_monitor_local_key_vault_v1"
private const val VAULT_PREFS = "krill_monitor_key_vault"

private fun Context.activity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return current as? Activity
}

@Composable
internal actual fun rememberLocalKeyVault(): LocalKeyVault {
    val context = LocalContext.current
    val activity = context.activity()
    val owner = LocalLifecycleOwner.current
    val vault =
        remember(context.applicationContext, activity) {
            AndroidLocalKeyVault(context.applicationContext, activity)
        }
    DisposableEffect(vault, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) vault.cancelAuthentication()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            vault.cancelAuthentication()
        }
    }
    return vault
}

@Composable
internal actual fun ProtectKeyDisplay(onBackground: () -> Unit) {
    val activity = LocalContext.current.activity()
    val owner = LocalLifecycleOwner.current
    val callback = rememberUpdatedState(onBackground)
    DisposableEffect(activity, owner) {
        val window = activity?.window
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) callback.value()
        }
        owner.lifecycle.addObserver(observer)
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) callback.value()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private class AndroidLocalKeyVault(context: Context, activity: Activity?) : LocalKeyVault {
    private val app = context.applicationContext
    private val activityRef = WeakReference(activity)
    private val prefs = app.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    private class Request(
        val signal: CancellationSignal,
        val continuation: CancellableContinuation<Cipher?>,
    )

    private var pending: Request? = null
    override val enabled = true
    override val unavailableReason: String?
        get() =
            when {
                Build.VERSION.SDK_INT < 30 -> "本地 Key 库需要 Android 11 或更高版本"
                activityRef.get()?.let { !it.isFinishing && !it.isDestroyed } != true ->
                    "当前页面不可用，请重新打开应用"
                !(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                    .isDeviceSecure -> "请先设置设备锁屏密码"
                else -> null
            }

    private fun index(accountId: Long, keyId: Long): String =
        try {
            KeyVaultCodec.index(accountId, keyId)
        } catch (_: Exception) {
            throw KeyVaultException("Key 标识无效")
        }

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun hasKey(accountId: Long, keyId: Long): Boolean =
        try {
            prefs.contains(index(accountId, keyId))
        } catch (e: KeyVaultException) {
            throw e
        } catch (_: Exception) {
            throw KeyVaultException("无法读取本地 Key 库")
        }

    override fun save(accountId: Long, keyId: Long, secret: String) {
        val id = index(accountId, keyId)
        unavailableReason?.let { throw KeyVaultException(it) }
        try {
            val keyStore = store()
            if (!keyStore.containsAlias(VAULT_ALIAS)) {
                if (prefs.all.isNotEmpty()) throw KeyVaultException("本地 Key 的安全密钥不可用，原有数据已保留")
                val generator =
                    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
                generator.initialize(
                    KeyGenParameterSpec.Builder(VAULT_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(
                            0,
                            KeyProperties.AUTH_BIOMETRIC_STRONG or
                                KeyProperties.AUTH_DEVICE_CREDENTIAL,
                        )
                        .setInvalidatedByBiometricEnrollment(false)
                        .build()
                )
                generator.generateKeyPair()
                keyStore.load(null)
            }
            val privateKey =
                keyStore.getKey(VAULT_ALIAS, null)
                    ?: throw KeyVaultException("本地 Key 的安全密钥不可用，原有数据已保留")
            Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
                .init(Cipher.DECRYPT_MODE, privateKey, KeyVaultCodec.oaep)
            val publicKey =
                keyStore.getCertificate(VAULT_ALIAS)?.publicKey
                    ?: throw KeyVaultException("本地 Key 的安全密钥不可用")
            val record = KeyVaultCodec.encrypt(publicKey, accountId, keyId, secret)
            val old = prefs.getString(id, null)
            if (
                !prefs.edit().putString(id, Base64.encodeToString(record, Base64.NO_WRAP)).commit()
            ) {
                val restore = prefs.edit()
                if (old == null) restore.remove(id) else restore.putString(id, old)
                restore.commit()
                throw KeyVaultException("本地 Key 保存失败，请重试")
            }
        } catch (e: KeyVaultException) {
            throw e
        } catch (_: Exception) {
            throw KeyVaultException("本地 Key 保存失败，请检查设备安全设置后重试")
        }
    }

    override fun remove(accountId: Long, keyId: Long) {
        val id = index(accountId, keyId)
        cancelAuthentication()
        try {
            if (!prefs.edit().remove(id).commit()) throw KeyVaultException("删除本地 Key 失败")
        } catch (e: KeyVaultException) {
            throw e
        } catch (_: Exception) {
            throw KeyVaultException("删除本地 Key 失败")
        }
    }

    override suspend fun reveal(accountId: Long, keyId: Long): String? = decrypt(accountId, keyId)

    override suspend fun copy(accountId: Long, keyId: Long): Boolean {
        val secret = decrypt(accountId, keyId) ?: return false
        currentCoroutineContext().ensureActive()
        try {
            val clip = ClipData.newPlainText("Key", secret)
            if (Build.VERSION.SDK_INT >= 33) {
                clip.description.extras =
                    PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
            }
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                clip
            )
            return true
        } catch (_: Exception) {
            throw KeyVaultException("无法复制本地 Key")
        }
    }

    private suspend fun decrypt(accountId: Long, keyId: Long): String? {
        val id = index(accountId, keyId)
        unavailableReason?.let { throw KeyVaultException(it) }
        try {
            val encoded = prefs.getString(id, null) ?: return null
            require(encoded.length <= 88000)
            val record = Base64.decode(encoded, Base64.NO_WRAP)
            KeyVaultCodec.validate(record)
            val privateKey =
                store().getKey(VAULT_ALIAS, null) ?: throw KeyVaultException("本地 Key 的安全密钥不可用")
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            cipher.init(Cipher.DECRYPT_MODE, privateKey, KeyVaultCodec.oaep)
            val authenticated = authenticate(cipher) ?: return null
            currentCoroutineContext().ensureActive()
            return KeyVaultCodec.decrypt(authenticated, record, accountId, keyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KeyVaultException) {
            throw e
        } catch (_: Exception) {
            throw KeyVaultException("无法安全读取本地 Key，数据已保留")
        }
    }

    private suspend fun authenticate(cipher: Cipher): Cipher? =
        suspendCancellableCoroutine { continuation ->
            val activity = activityRef.get()
            if (
                Build.VERSION.SDK_INT < 30 ||
                    activity == null ||
                    activity.isFinishing ||
                    activity.isDestroyed
            ) {
                continuation.resumeWithException(KeyVaultException("当前页面不可用，无法验证身份"))
                return@suspendCancellableCoroutine
            }
            val request = Request(CancellationSignal(), continuation)
            synchronized(lock) {
                if (pending != null) {
                    continuation.resumeWithException(KeyVaultException("已有身份验证正在进行"))
                    return@suspendCancellableCoroutine
                }
                pending = request
            }
            continuation.invokeOnCancellation {
                synchronized(lock) { if (pending === request) pending = null }
                request.signal.cancel()
            }
            fun complete(value: Cipher?, error: String? = null) {
                synchronized(lock) {
                    if (pending !== request || !continuation.isActive) return
                    pending = null
                    if (error == null) continuation.resume(value)
                    else continuation.resumeWithException(KeyVaultException(error))
                }
            }
            try {
                val prompt =
                    BiometricPrompt.Builder(activity)
                        .setTitle("验证身份以访问本地 Key")
                        .setAllowedAuthenticators(
                            android.hardware.biometrics.BiometricManager.Authenticators
                                .BIOMETRIC_STRONG or
                                android.hardware.biometrics.BiometricManager.Authenticators
                                    .DEVICE_CREDENTIAL
                        )
                        .build()
                prompt.authenticate(
                    BiometricPrompt.CryptoObject(cipher),
                    request.signal,
                    activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult
                        ) {
                            val resultCipher = result.cryptoObject?.cipher
                            if (resultCipher == null) complete(null, "身份验证未绑定安全密钥")
                            else complete(resultCipher)
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            if (
                                errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
                                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT
                            )
                                complete(null)
                            else complete(null, "身份验证失败，请检查设备安全设置")
                        }
                    },
                )
            } catch (_: Exception) {
                complete(null, "无法启动身份验证")
            }
        }

    override fun cancelAuthentication() {
        val request = synchronized(lock) { pending.also { pending = null } } ?: return
        request.signal.cancel()
        if (request.continuation.isActive) request.continuation.resume(null)
    }
}
