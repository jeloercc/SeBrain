package com.sebrain.core.vault

import java.security.SecureRandom

/**
 * Random 32-byte key for tests, in SQLCipher's raw key form `x'<64 hex chars>'`.
 * The key exists only as byte arrays, so it can be zeroed. It is never a String and never logged.
 */
class RawTestKey : AutoCloseable {
    private val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /** ASCII bytes of `x'<hex>'`. SQLCipher keeps this reference, so zero it only after close(). */
    val sqlcipherKey = ByteArray(2 * KEY_BYTES + 3).also { out ->
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        for (i in key.indices) {
            val b = key[i].toInt() and 0xff
            out[2 + 2 * i] = HEX[b ushr 4]
            out[3 + 2 * i] = HEX[b and 0x0f]
        }
        out[out.size - 1] = '\''.code.toByte()
    }

    val isZeroed: Boolean
        get() = key.all { it == 0.toByte() } && sqlcipherKey.all { it == 0.toByte() }

    override fun close() {
        key.fill(0)
        sqlcipherKey.fill(0)
    }

    override fun toString(): String = "RawTestKey(redacted)"

    private companion object {
        const val KEY_BYTES = 32
        val HEX = ByteArray(16) { "0123456789abcdef"[it].code.toByte() }
    }
}
