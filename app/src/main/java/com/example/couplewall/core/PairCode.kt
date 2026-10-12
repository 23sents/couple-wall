package com.example.couplewall.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/**
 * The shared secret of a couple: 20 Crockford-base32 characters (100 bits). From it we derive the relay topic
 * (so strangers cannot guess where to listen) and the AES key (so the relay only ever sees ciphertext).
 */
object PairCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 20

    fun generate(random: Random = SecureRandom()): String =
        buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** "abcde-fghij…" typed by a human -> canonical 20 characters, or null if it is not a valid code. */
    fun normalize(input: String): String? {
        val sb = StringBuilder()
        for (raw in input.uppercase()) {
            if (!raw.isLetterOrDigit()) continue
            sb.append(
                when (raw) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> raw
                }
            )
        }
        val s = sb.toString()
        return if (s.length == LENGTH && s.all { ALPHABET.indexOf(it) >= 0 }) s else null
    }

    fun format(code: String): String = code.chunked(5).joinToString("-")

    fun topic(code: String): String = "cw" + hex(sha256("couple-wall/topic/$code")).take(40)

    fun key(code: String): ByteArray = sha256("couple-wall/key/$code")

    /** Proof of knowing the code, sent to the relay; the relay stores only its hash. Independent of topic and key. */
    fun authToken(code: String): String = hex(sha256("couple-wall/auth/$code"))

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
