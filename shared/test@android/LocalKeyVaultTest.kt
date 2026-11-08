package com.hanserwei.krill_monitor

import java.security.KeyPairGenerator
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LocalKeyVaultTest {
    private val pair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun decrypt(record: ByteArray, accountId: Long = 1, keyId: Long = 2): String {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.private, KeyVaultCodec.oaep)
        return KeyVaultCodec.decrypt(cipher, record, accountId, keyId)
    }

    @Test
    fun envelopeBindsOwnerAndKeyAndRejectsTampering() {
        val record = KeyVaultCodec.encrypt(pair.public, 1, 2, "test-密钥")
        assertEquals("test-密钥", decrypt(record))
        assertFails { decrypt(record, accountId = 3) }
        assertFails { decrypt(record, keyId = 3) }
        val tamperedCiphertext =
            record.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertFails { decrypt(tamperedCiphertext) }
        val tamperedWrappedKey =
            record.copyOf().apply { this[1] = (this[1].toInt() xor 1).toByte() }
        assertFails { decrypt(tamperedWrappedKey) }
    }

    @Test
    fun rejectsInvalidRecordsAndIdentifiers() {
        val record = KeyVaultCodec.encrypt(pair.public, 1, 2, "test")
        assertFails { decrypt(record.copyOf(20)) }
        assertFails { decrypt(ByteArray(70000)) }
        assertFails { decrypt(record.copyOf().apply { this[0] = 2 }) }
        assertFails { KeyVaultCodec.index(0, 2) }
        assertFails { KeyVaultCodec.index(1, -1) }
        assertFails { KeyVaultCodec.encrypt(pair.public, 1, 2, "") }
    }
}
