package com.sebrain.core.vault

/** Encodes a DEK as SQLCipher's raw key form `x'<hex>'`, as ASCII bytes and never as a String. */
internal object SqlcipherRawKey {
    private val HEX = ByteArray(16) { "0123456789abcdef"[it].code.toByte() }

    fun encode(dek: ByteArray): ByteArray {
        val out = ByteArray(2 * dek.size + 3)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        for (i in dek.indices) {
            val b = dek[i].toInt() and 0xff
            out[2 + 2 * i] = HEX[b ushr 4]
            out[3 + 2 * i] = HEX[b and 0x0f]
        }
        out[out.size - 1] = '\''.code.toByte()
        return out
    }
}
