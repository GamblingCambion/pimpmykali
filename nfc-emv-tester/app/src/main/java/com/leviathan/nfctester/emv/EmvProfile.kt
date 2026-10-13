package com.leviathan.nfctester.emv

import com.leviathan.nfctester.payloads.Payload
import com.leviathan.nfctester.payloads.Payloads

/**
 * The human-readable EMV tags a terminal may render on screen, and therefore the
 * fields worth probing for unsanitized display handling.
 *
 *  - [APPLICATION_LABEL]  tag 50   — appears in the PPSE directory entry and the ADF FCI.
 *  - [PREFERRED_NAME]     tag 9F12 — shown in preference to the label when present.
 *  - [CARDHOLDER_NAME]    tag 5F20 — returned in a READ RECORD; shown/printed by many terminals.
 */
enum class DisplayField(val tag: String, val description: String) {
    APPLICATION_LABEL("50", "Application Label (PPSE entry + ADF FCI)"),
    PREFERRED_NAME("9F12", "Application Preferred Name (ADF FCI)"),
    CARDHOLDER_NAME("5F20", "Cardholder Name (READ RECORD)"),
}

/**
 * A single experiment: inject [payload] into exactly one [targetField]; every other
 * display field carries the benign baseline value. Isolating one variable means an
 * observed failure on the terminal points at a specific tag.
 *
 * [aid] is the application identifier the emulated card advertises. It only steers
 * which kernel / UI path the terminal exercises — it carries no cardholder data and
 * no keys, so the transaction cannot complete regardless of AID.
 */
data class EmvProfile(
    val name: String,
    val targetField: DisplayField,
    val payload: Payload,
    val aid: ByteArray = DEFAULT_AID,
) {
    /** Value to place in [field]: the payload for the target field, baseline otherwise. */
    fun valueFor(field: DisplayField): ByteArray =
        if (field == targetField) payload.bytes else BASELINE.bytes

    override fun equals(other: Any?): Boolean =
        other is EmvProfile && name == other.name &&
            targetField == other.targetField && payload == other.payload &&
            aid.contentEquals(other.aid)

    override fun hashCode(): Int =
        name.hashCode() * 31 + targetField.hashCode() * 17 + payload.hashCode() + aid.contentHashCode()

    companion object {
        /** A14-byte Visa-family AID is widely recognised; swap per target as needed. */
        val DEFAULT_AID: ByteArray = Hex.decode("A0000000031010")

        /** Benign value used for every field that is not under test. */
        val BASELINE: Payload = Payloads.get("baseline.clean")

        /**
         * Build the default experiment matrix: every payload against every display
         * field. The operator typically walks these one at a time.
         */
        fun matrix(aid: ByteArray = DEFAULT_AID): List<EmvProfile> =
            DisplayField.values().flatMap { field ->
                Payloads.all.map { p ->
                    EmvProfile(
                        name = "${field.name.lowercase()} / ${p.id}",
                        targetField = field,
                        payload = p,
                        aid = aid,
                    )
                }
            }
    }
}
