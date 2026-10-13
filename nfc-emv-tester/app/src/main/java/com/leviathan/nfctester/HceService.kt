package com.leviathan.nfctester

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.leviathan.nfctester.emv.CardEmulator
import com.leviathan.nfctester.emv.EmulatorState
import com.leviathan.nfctester.emv.Hex
import com.leviathan.nfctester.emv.Sw

/**
 * Host Card Emulation entry point.
 *
 * Android binds this service and calls [processCommandApdu] for every command the
 * terminal sends once it has routed one of our registered AIDs (see
 * res/xml/apduservice.xml) to us. We hand the bytes to a [CardEmulator] built from
 * the currently-armed profile and log the exchange.
 */
class HceService : HostApduService() {

    private var emulator: CardEmulator? = null
    private var armedNote: String = "unarmed"

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val command = commandApdu ?: return Sw.WRONG_LENGTH

        // Build (or rebuild) the emulator from whatever profile is armed right now.
        val profile = EmulatorState.current()
        if (profile == null) {
            // Respond cleanly but make it obvious nothing is armed.
            EmulatorState.log.record(command, Sw.FILE_NOT_FOUND, "UNARMED — no profile selected")
            return Sw.FILE_NOT_FOUND
        }
        if (emulator == null) {
            emulator = CardEmulator(profile)
            armedNote = "armed: ${profile.name} [field=${profile.targetField.tag} payload=${profile.payload.id}]"
        }

        val response = emulator!!.process(command)
        EmulatorState.log.record(command, response, describe(command, armedNote))
        return response
    }

    override fun onDeactivated(reason: Int) {
        val why = when (reason) {
            DEACTIVATION_LINK_LOSS -> "link loss"
            DEACTIVATION_DESELECTED -> "deselected"
            else -> "reason=$reason"
        }
        EmulatorState.log.record(ByteArray(0), ByteArray(0), "— field deactivated ($why) —")
        // Reset so the next tap re-reads the armed profile.
        emulator = null
    }

    /** Short human note identifying the command for the log. */
    private fun describe(command: ByteArray, note: String): String {
        val hex = Hex.encode(command).uppercase()
        val kind = when {
            hex.startsWith("00A40400") && hex.contains("325041592E5359532E4444463031") -> "SELECT PPSE"
            hex.startsWith("00A40400") -> "SELECT AID"
            hex.startsWith("80A8") -> "GET PROCESSING OPTIONS"
            hex.startsWith("00B2") -> "READ RECORD"
            hex.startsWith("80AE") -> "GENERATE AC (refused)"
            else -> "command"
        }
        return "$kind — $note"
    }
}
