package com.leviathan.nfctester.emv

/**
 * Process-wide handoff between the UI and the [com.leviathan.nfctester.HceService].
 *
 * Android starts the HCE service on a binder thread the moment the terminal's RF
 * field selects our AID — there is no opportunity to pass arguments in. So the UI
 * "arms" a profile here; the service reads the currently-armed profile when a tap
 * arrives. A single shared [TransactionLog] lets the UI render exchanges live.
 */
object EmulatorState {

    /** The log for the current/most-recent tap. */
    val log = TransactionLog()

    @Volatile
    private var armedProfile: EmvProfile? = null

    /** Arm an experiment. Until this is called the service responds but logs "unarmed". */
    @Synchronized
    fun arm(profile: EmvProfile) {
        armedProfile = profile
    }

    @Synchronized
    fun disarm() {
        armedProfile = null
    }

    @Synchronized
    fun current(): EmvProfile? = armedProfile
}
