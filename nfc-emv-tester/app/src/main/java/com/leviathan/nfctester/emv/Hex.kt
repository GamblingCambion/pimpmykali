package com.leviathan.nfctester.emv

/**
 * Hex / byte helpers used throughout the emulator.
 *
 * Everything in the APDU layer is bytes; we keep a small, dependency-free set of
 * conversions here so the rest of the code can read naturally.
 */
object Hex {

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    /** Encode bytes as an uppercase, space-free hex string, e.g. [0x90, 0x00] -> "9000". */
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    /** Encode bytes as uppercase hex with a space between each octet, e.g. "90 00". */
    fun encodeSpaced(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * Decode a hex string into bytes. Whitespace is ignored so callers may paste
     * spaced dumps. Throws [IllegalArgumentException] on odd length or non-hex input.
     */
    fun decode(hex: String): ByteArray {
        val clean = hex.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "Hex string must have an even number of nibbles: '$hex'" }
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            val hi = Character.digit(clean[i], 16)
            val lo = Character.digit(clean[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "Illegal hex character in '$hex'" }
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    /** True if [a] and [b] have identical contents. */
    fun eq(a: ByteArray, b: ByteArray): Boolean = a.contentEquals(b)
}

/** Concatenate several byte arrays into one. */
fun concat(vararg parts: ByteArray): ByteArray {
    val total = parts.sumOf { it.size }
    val out = ByteArray(total)
    var offset = 0
    for (p in parts) {
        System.arraycopy(p, 0, out, offset, p.size)
        offset += p.size
    }
    return out
}
