package com.example.couplewall.core

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM: output is iv(12) || ciphertext || tag(16). Tampered or foreign data fails authentication. */
object Crypto {
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun seal(key: ByteArray, plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    fun open(key: ByteArray, data: ByteArray): ByteArray? {
        if (data.size < IV_LEN + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, data.copyOfRange(0, IV_LEN)))
            cipher.doFinal(data, IV_LEN, data.size - IV_LEN)
        } catch (e: GeneralSecurityException) {
            null
        }
    }
}
