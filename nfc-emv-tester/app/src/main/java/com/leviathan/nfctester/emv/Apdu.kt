package com.leviathan.nfctester.emv

/**
 * Parsed view of a command APDU sent by the terminal (ISO 7816-4).
 *
 * Case 1:  CLA INS P1 P2
 * Case 2:  CLA INS P1 P2 Le
 * Case 3:  CLA INS P1 P2 Lc <data>
 * Case 4:  CLA INS P1 P2 Lc <data> Le
 *
 * HCE only deals with short-form APDUs in practice, so this parser handles the
 * single-byte Lc/Le forms, which is all an EMV contactless terminal emits.
 */
data class CommandApdu(
    val cla: Int,
    val ins: Int,
    val p1: Int,
    val p2: Int,
    val data: ByteArray,
    val le: Int?,
    val raw: ByteArray,
) {
    companion object {
        fun parse(bytes: ByteArray): CommandApdu? {
            if (bytes.size < 4) return null
            val cla = bytes[0].toInt() and 0xFF
            val ins = bytes[1].toInt() and 0xFF
            val p1 = bytes[2].toInt() and 0xFF
            val p2 = bytes[3].toInt() and 0xFF

            // Case 1: header only.
            if (bytes.size == 4) {
                return CommandApdu(cla, ins, p1, p2, ByteArray(0), null, bytes)
            }
            // Case 2: header + Le.
            if (bytes.size == 5) {
                val le = bytes[4].toInt() and 0xFF
                return CommandApdu(cla, ins, p1, p2, ByteArray(0), le, bytes)
            }
            // Cases 3 & 4: header + Lc + data [+ Le].
            val lc = bytes[4].toInt() and 0xFF
            val dataStart = 5
            if (dataStart + lc > bytes.size) return null // truncated
            val data = bytes.copyOfRange(dataStart, dataStart + lc)
            val le = if (dataStart + lc < bytes.size) bytes[dataStart + lc].toInt() and 0xFF else null
            return CommandApdu(cla, ins, p1, p2, data, le, bytes)
        }
    }

    /** Short-integer identity (CLA<<8 | INS) handy for routing on command class. */
    val insId: Int get() = (cla shl 8) or ins

    override fun equals(other: Any?): Boolean =
        other is CommandApdu && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()
}

/** Common ISO 7816 / EMV status words, as 2-byte responses. */
object Sw {
    val OK = byteArrayOf(0x90.toByte(), 0x00)                       // 9000 success
    val WRONG_LENGTH = byteArrayOf(0x67, 0x00)                      // 6700
    val CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69, 0x85.toByte()) // 6985
    val FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())           // 6A82
    val RECORD_NOT_FOUND = byteArrayOf(0x6A, 0x83.toByte())         // 6A83
    val FUNC_NOT_SUPPORTED = byteArrayOf(0x6A, 0x81.toByte())       // 6A81
    val INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)                 // 6D00
    val CLA_NOT_SUPPORTED = byteArrayOf(0x6E, 0x00)                 // 6E00

    /** Append a status word to a data payload to form a full response APDU. */
    fun with(data: ByteArray, sw: ByteArray): ByteArray = concat(data, sw)
}

/** INS bytes / selectors used by the EMV contactless flow. */
object Ins {
    const val SELECT = 0xA4
    const val READ_RECORD = 0xB2
    const val GET_PROCESSING_OPTIONS_INS = 0xA8 // with CLA 0x80
    const val GENERATE_AC_INS = 0xAE            // with CLA 0x80
    const val GET_DATA_INS = 0xCA               // with CLA 0x80

    /** PPSE directory name the terminal selects first on a contactless tap. */
    val PPSE_NAME: ByteArray = "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)
}
