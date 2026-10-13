package com.leviathan.nfctester.emv

/**
 * The emulated contactless card: a stateless function from a terminal command APDU
 * (plus the armed [EmvProfile]) to a response APDU.
 *
 * It implements just enough of the EMV contactless selection flow to reach the point
 * where a terminal renders card-supplied display fields:
 *
 *   terminal                         emulated card
 *   --------                         -------------
 *   SELECT PPSE (2PAY.SYS.DDF01) -->  FCI listing our AID + Application Label
 *   SELECT AID                   -->  FCI with Application Label + Preferred Name
 *   GET PROCESSING OPTIONS       -->  AIP + AFL pointing at one record
 *   READ RECORD (SFI 1, rec 1)   -->  record carrying Cardholder Name
 *   GENERATE AC                  -->  6985 (refused — see note)
 *
 * ### Safe by construction
 * The record contains **no PAN, no track-2 data, and no expiry**, and the card holds
 * **no issuer keys**. GENERATE AC is always refused, so no valid cryptogram can be
 * produced and no transaction can complete. The emulator only ever exercises the
 * terminal's pre-authentication parsing and display path — which is exactly the
 * behaviour under test.
 */
class CardEmulator(private val profile: EmvProfile) {

    /** SFI and record number we advertise in the AFL and answer on READ RECORD. */
    private val sfi = 1
    private val recordNumber = 1

    /** Process one command APDU and return the full response (data + status word). */
    fun process(commandBytes: ByteArray): ByteArray {
        val cmd = CommandApdu.parse(commandBytes) ?: return Sw.WRONG_LENGTH

        return when {
            cmd.ins == Ins.SELECT && cmd.p1 == 0x04 -> handleSelect(cmd)
            cmd.cla == 0x80 && cmd.ins == Ins.GET_PROCESSING_OPTIONS_INS -> handleGpo()
            cmd.ins == Ins.READ_RECORD -> handleReadRecord(cmd)
            cmd.cla == 0x80 && cmd.ins == Ins.GENERATE_AC_INS -> Sw.CONDITIONS_NOT_SATISFIED
            cmd.cla == 0x80 && cmd.ins == Ins.GET_DATA_INS -> Sw.CONDITIONS_NOT_SATISFIED
            else -> Sw.INS_NOT_SUPPORTED
        }
    }

    // SELECT by name: either the PPSE directory or our payment AID.
    private fun handleSelect(cmd: CommandApdu): ByteArray = when {
        cmd.data.contentEquals(Ins.PPSE_NAME) -> Sw.with(buildPpseFci(), Sw.OK)
        cmd.data.contentEquals(profile.aid) -> Sw.with(buildAdfFci(), Sw.OK)
        else -> Sw.FILE_NOT_FOUND
    }

    // READ RECORD: answer only the record we advertised in the AFL.
    private fun handleReadRecord(cmd: CommandApdu): ByteArray {
        val requestedSfi = (cmd.p2.toInt() ushr 3) and 0x1F
        val requestedRecord = cmd.p1
        return if (requestedSfi == sfi && requestedRecord == recordNumber) {
            Sw.with(buildRecord(), Sw.OK)
        } else {
            Sw.RECORD_NOT_FOUND
        }
    }

    // --- Response templates -------------------------------------------------

    /**
     * PPSE FCI (answer to SELECT 2PAY.SYS.DDF01).
     *
     *   6F  FCI template
     *     84  DF name = 2PAY.SYS.DDF01
     *     A5  FCI proprietary template
     *       BF0C  FCI issuer discretionary data
     *         61  directory entry
     *           4F  ADF name (our AID)
     *           50  Application Label   <-- display field under test
     *           87  priority indicator
     */
    private fun buildPpseFci(): ByteArray {
        val label = profile.valueFor(DisplayField.APPLICATION_LABEL)
        val directoryEntry = Tlv.wrap(
            "61",
            Tlv.encode("4F", profile.aid),
            Tlv.encode("50", label),
            Tlv.encode("87", byteArrayOf(0x01)),
        )
        val proprietary = Tlv.wrap("A5", Tlv.wrap("BF0C", directoryEntry))
        return Tlv.wrap(
            "6F",
            Tlv.encode("84", Ins.PPSE_NAME),
            proprietary,
        )
    }

    /**
     * ADF FCI (answer to SELECT <AID>).
     *
     *   6F
     *     84  DF name (our AID)
     *     A5
     *       50    Application Label          <-- display field under test
     *       9F12  Application Preferred Name  <-- display field under test
     *       5F2D  language preference = "en"
     *       9F11  issuer code table index = 1
     *       87    priority indicator
     *
     * No PDOL is advertised, so the terminal issues a GET PROCESSING OPTIONS with an
     * empty command template — keeping the flow minimal.
     */
    private fun buildAdfFci(): ByteArray {
        val label = profile.valueFor(DisplayField.APPLICATION_LABEL)
        val preferred = profile.valueFor(DisplayField.PREFERRED_NAME)
        val proprietary = Tlv.wrap(
            "A5",
            Tlv.encode("50", label),
            Tlv.encode("9F12", preferred),
            Tlv.encode("5F2D", "en".toByteArray(Charsets.US_ASCII)),
            Tlv.encode("9F11", byteArrayOf(0x01)),
            Tlv.encode("87", byteArrayOf(0x01)),
        )
        return Tlv.wrap("6F", Tlv.encode("84", profile.aid), proprietary)
    }

    /**
     * GPO response (format 2, template 77).
     *
     *   77
     *     82  AIP  = 0000 (no offline auth, no CVM required for our purposes)
     *     94  AFL  = SFI 1, records 1..1, 0 offline records
     */
    private fun handleGpo(): ByteArray {
        val aip = byteArrayOf(0x00, 0x00)
        // AFL entry: byte0 = SFI in the high 5 bits (SFI << 3), low 3 bits RFU/zero;
        // then first record, last record, and #records for offline auth (none).
        val afl = byteArrayOf((sfi shl 3).toByte(), recordNumber.toByte(), recordNumber.toByte(), 0x00)
        val body = concat(Tlv.encode("82", aip), Tlv.encode("94", afl))
        return Sw.with(Tlv.wrap("77", body), Sw.OK)
    }

    /**
     * READ RECORD response.
     *
     *   70
     *     5F20  Cardholder Name  <-- display field under test
     *
     * Deliberately minimal: no 57 (track-2 equivalent), no 5A (PAN), no 5F24 (expiry).
     */
    private fun buildRecord(): ByteArray {
        val cardholder = profile.valueFor(DisplayField.CARDHOLDER_NAME)
        return Tlv.wrap("70", Tlv.encode("5F20", cardholder))
    }
}
