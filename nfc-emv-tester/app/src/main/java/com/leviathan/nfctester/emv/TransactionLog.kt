package com.leviathan.nfctester.emv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One command/response exchange, captured for the operator's report. */
data class ApduExchange(
    val timestampMillis: Long,
    val commandHex: String,
    val responseHex: String,
    val note: String,
) {
    fun format(): String {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))
        return buildString {
            append("[").append(ts).append("] ").append(note).append('\n')
            append("  C-APDU: ").append(commandHex).append('\n')
            append("  R-APDU: ").append(responseHex)
        }
    }
}

/**
 * Append-only, in-memory log of a tap. Thread-safe because the HCE service runs on a
 * binder thread while the UI reads on the main thread. Observers are notified so the
 * UI can refresh live.
 */
class TransactionLog {

    private val exchanges = ArrayList<ApduExchange>()
    private val listeners = ArrayList<(ApduExchange) -> Unit>()

    @Synchronized
    fun record(command: ByteArray, response: ByteArray, note: String) {
        val ex = ApduExchange(
            timestampMillis = System.currentTimeMillis(),
            commandHex = Hex.encodeSpaced(command),
            responseHex = Hex.encodeSpaced(response),
            note = note,
        )
        exchanges.add(ex)
        listeners.toList().forEach { it(ex) }
    }

    @Synchronized
    fun snapshot(): List<ApduExchange> = ArrayList(exchanges)

    @Synchronized
    fun clear() = exchanges.clear()

    @Synchronized
    fun addListener(l: (ApduExchange) -> Unit) { listeners.add(l) }

    @Synchronized
    fun removeListener(l: (ApduExchange) -> Unit) { listeners.remove(l) }

    /** Render the whole session as plain text suitable for a report export. */
    @Synchronized
    fun export(header: String): String = buildString {
        append(header).append("\n\n")
        snapshot().forEach { append(it.format()).append("\n\n") }
    }
}
