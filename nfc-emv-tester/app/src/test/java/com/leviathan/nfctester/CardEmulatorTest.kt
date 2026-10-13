package com.leviathan.nfctester

import com.leviathan.nfctester.emv.CardEmulator
import com.leviathan.nfctester.emv.DisplayField
import com.leviathan.nfctester.emv.EmvProfile
import com.leviathan.nfctester.emv.Hex
import com.leviathan.nfctester.emv.Ins
import com.leviathan.nfctester.emv.Tlv
import com.leviathan.nfctester.payloads.Payloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the emulator's EMV response construction. These need no Android
 * runtime — the emv/ and payloads/ packages are deliberately framework-free — so they
 * run under `./gradlew :app:test`.
 */
class CardEmulatorTest {

    private val aid = EmvProfile.DEFAULT_AID

    private fun profile(field: DisplayField, payloadId: String) =
        EmvProfile("t", field, Payloads.get(payloadId), aid)

    private fun selectPpse() =
        Hex.decode("00A40400") + byteArrayOf(Ins.PPSE_NAME.size.toByte()) + Ins.PPSE_NAME + byteArrayOf(0x00)

    private fun selectAid() =
        Hex.decode("00A40400") + byteArrayOf(aid.size.toByte()) + aid + byteArrayOf(0x00)

    private fun gpo() = Hex.decode("80A80000028300" + "00")

    private fun readRecord() = Hex.decode("00B2010C00") // record 1, SFI 1 (P2 = (1<<3)|4 = 0x0C)

    private fun endsWith9000(r: ByteArray) =
        r.size >= 2 && r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()

    @Test
    fun ppse_is_well_formed_and_carries_label_payload() {
        val emu = CardEmulator(profile(DisplayField.APPLICATION_LABEL, "escape.error_text"))
        val r = emu.process(selectPpse())
        assertTrue("PPSE must succeed with 9000", endsWith9000(r))
        val hex = Hex.encode(r)
        assertTrue("PPSE FCI must be a 6F template", hex.startsWith("6F"))
        assertTrue("PPSE must advertise our AID", hex.contains(Hex.encode(aid)))
        // The crafted label must appear verbatim in the response bytes.
        assertTrue("Injected label payload must be present", hex.contains(Hex.encode(Payloads.get("escape.error_text").bytes)))
    }

    @Test
    fun aid_fci_carries_preferred_name_payload() {
        val emu = CardEmulator(profile(DisplayField.PREFERRED_NAME, "control.crlf"))
        emu.process(selectPpse())
        val r = emu.process(selectAid())
        assertTrue(endsWith9000(r))
        val hex = Hex.encode(r)
        assertTrue("ADF FCI must be a 6F template", hex.startsWith("6F"))
        assertTrue("9F12 tag must be present", hex.contains("9F12"))
        assertTrue("Injected preferred-name payload must be present", hex.contains(Hex.encode(Payloads.get("control.crlf").bytes)))
    }

    @Test
    fun read_record_carries_cardholder_name_payload() {
        val emu = CardEmulator(profile(DisplayField.CARDHOLDER_NAME, "format.printf"))
        emu.process(selectPpse())
        emu.process(selectAid())
        emu.process(gpo())
        val r = emu.process(readRecord())
        assertTrue(endsWith9000(r))
        val hex = Hex.encode(r)
        assertTrue("Record must be a 70 template", hex.startsWith("70"))
        assertTrue("5F20 tag must be present", hex.contains("5F20"))
        assertTrue("Injected cardholder-name payload must be present", hex.contains(Hex.encode(Payloads.get("format.printf").bytes)))
    }

    @Test
    fun generate_ac_is_always_refused() {
        val emu = CardEmulator(profile(DisplayField.CARDHOLDER_NAME, "baseline.clean"))
        val r = emu.process(Hex.decode("80AE80000400000000" + "00"))
        assertEquals("GENERATE AC must return 6985 so no transaction can complete", "6985", Hex.encode(r))
    }

    @Test
    fun unknown_aid_is_rejected() {
        val emu = CardEmulator(profile(DisplayField.APPLICATION_LABEL, "baseline.clean"))
        val select = Hex.decode("00A4040007A0000000999999" + "00")
        val r = emu.process(select)
        assertEquals("Unknown AID selects nothing", "6A82", Hex.encode(r))
    }

    @Test
    fun non_target_fields_use_baseline_only() {
        // Target the cardholder name; the PPSE label must therefore be the baseline value.
        val emu = CardEmulator(profile(DisplayField.CARDHOLDER_NAME, "escape.error_text"))
        val r = emu.process(selectPpse())
        val hex = Hex.encode(r)
        assertTrue("Baseline label expected in PPSE", hex.contains(Hex.encode(EmvProfile.BASELINE.bytes)))
        assertTrue(
            "Cardholder payload must NOT leak into the PPSE label",
            !hex.contains(Hex.encode(Payloads.get("escape.error_text").bytes)),
        )
    }

    @Test
    fun tlv_length_encoding_boundaries() {
        assertEquals("7F", Hex.encode(Tlv.encodeLength(0x7F)))
        assertEquals("8180", Hex.encode(Tlv.encodeLength(0x80)))
        assertEquals("81FF", Hex.encode(Tlv.encodeLength(0xFF)))
        assertEquals("820100", Hex.encode(Tlv.encodeLength(0x100)))
    }
}
