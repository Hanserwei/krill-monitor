package com.hanserwei.krill_monitor

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

internal object KeyVaultCodec {
    private const val MAX_SECRET_BYTES = 65536
    private const val WRAPPED_SIZE = 256
    private const val IV_SIZE = 12
    private const val HEADER_SIZE = 1 + WRAPPED_SIZE + IV_SIZE
    val oaep =
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    fun index(accountId: Long, keyId: Long): String {
        require(accountId > 0 && keyId > 0)
        return "$accountId:$keyId"
    }

    private fun aad(accountId: Long, keyId: Long): ByteArray {
        index(accountId, keyId)
        return ByteBuffer.allocate(16).putLong(accountId).putLong(keyId).array()
    }

    fun encrypt(publicKey: PublicKey, accountId: Long, keyId: Long, secret: String): ByteArray {
        val bytes = secret.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_SECRET_BYTES)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            aes.updateAAD(aad(accountId, keyId))
            val encrypted = aes.doFinal(bytes)
            val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            rsa.init(Cipher.ENCRYPT_MODE, publicKey, oaep)
            val wrapped = rsa.doFinal(key)
            require(wrapped.size == WRAPPED_SIZE && aes.iv.size == IV_SIZE)
            return byteArrayOf(1) + wrapped + aes.iv + encrypted
        } finally {
            key.fill(0)
            bytes.fill(0)
        }
    }

    fun validate(record: ByteArray) {
        require(record.size in (HEADER_SIZE + 16)..(HEADER_SIZE + 16 + MAX_SECRET_BYTES))
        require(record[0] == 1.toByte())
    }

    fun decrypt(cipher: Cipher, record: ByteArray, accountId: Long, keyId: Long): String {
        validate(record)
        val key = cipher.doFinal(record, 1, WRAPPED_SIZE)
        try {
            require(key.size == 32)
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, record, 1 + WRAPPED_SIZE, IV_SIZE),
            )
            aes.updateAAD(aad(accountId, keyId))
            val plaintext = aes.doFinal(record, HEADER_SIZE, record.size - HEADER_SIZE)
            try {
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext))
                    .toString()
            } finally {
                plaintext.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }
}
