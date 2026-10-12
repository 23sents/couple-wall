package com.example.couplewall.core

/** URL-safe Base64 without padding (own implementation: java.util.Base64 needs API 26, android.util is not unit-testable). */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val LOOKUP = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                .append(ALPHABET[(n shr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63]).append(ALPHABET[(n shr 6) and 63])
            }
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray? {
        if (text.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (ch in text) {
            val v = if (ch.code < 128) LOOKUP[ch.code] else -1
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
