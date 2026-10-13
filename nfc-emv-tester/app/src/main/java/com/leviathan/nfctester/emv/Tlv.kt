package com.leviathan.nfctester.emv

/**
 * Minimal BER-TLV encoder for building EMV card responses.
 *
 * EMV responses are nested BER-TLV structures. A terminal parses these and, for a
 * handful of tags, renders the value on screen. This encoder builds *well-formed*
 * TLVs for Phase 1 (we want the terminal to parse the structure successfully and
 * reach the display step — the payload under test lives in the *value*, not the
 * framing).
 *
 * The [LengthMode] hook is where Phase 2 (structural/APDU fuzzing) plugs in: by
 * emitting deliberately wrong declared lengths we can test the parser itself rather
 * than the renderer. Phase 1 only ever uses [LengthMode.CORRECT].
 */
object Tlv {

    enum class LengthMode {
        /** Declared length matches the real value length (conformant). */
        CORRECT,

        /** Declared length one greater than the real value (Phase 2 parser test). */
        OVER_BY_ONE,

        /** Declared length one less than the real value (Phase 2 parser test). */
        UNDER_BY_ONE,
    }

    /**
     * Encode a single TLV.
     *
     * @param tag   the tag, as a hex string (1 or 2 bytes), e.g. "50" or "9F12".
     * @param value the raw value bytes (this is where a test payload goes).
     * @param lengthMode how to encode the length field. Defaults to [LengthMode.CORRECT].
     */
    fun encode(
        tag: String,
        value: ByteArray,
        lengthMode: LengthMode = LengthMode.CORRECT,
    ): ByteArray {
        val tagBytes = Hex.decode(tag)
        val declaredLen = when (lengthMode) {
            LengthMode.CORRECT -> value.size
            LengthMode.OVER_BY_ONE -> value.size + 1
            LengthMode.UNDER_BY_ONE -> (value.size - 1).coerceAtLeast(0)
        }
        return concat(tagBytes, encodeLength(declaredLen), value)
    }

    /** Convenience overload for a hex-string value. */
    fun encode(tag: String, valueHex: String): ByteArray =
        encode(tag, Hex.decode(valueHex), LengthMode.CORRECT)

    /** Wrap one or more already-encoded TLVs inside a constructed (template) tag. */
    fun wrap(tag: String, vararg children: ByteArray): ByteArray =
        encode(tag, concat(*children), LengthMode.CORRECT)

    /**
     * Encode a BER length field.
     *  - 0x00..0x7F           -> single byte
     *  - 0x80..0xFF           -> 0x81 followed by one byte
     *  - 0x0100..0xFFFF       -> 0x82 followed by two bytes
     */
    fun encodeLength(len: Int): ByteArray {
        require(len in 0..0xFFFF) { "Unsupported TLV length: $len" }
        return when {
            len <= 0x7F -> byteArrayOf(len.toByte())
            len <= 0xFF -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len ushr 8).toByte(), len.toByte())
        }
    }
}
