package com.leviathan.nfctester.payloads

/**
 * Library of test payloads for probing how a payment terminal handles the
 * human-readable fields it receives from a card.
 *
 * ## What this tests
 * A conformant terminal treats card-supplied display strings (Application Label,
 * Application Preferred Name, Cardholder Name) as untrusted: it length-caps them,
 * strips non-printable/control bytes, and never interprets them as commands. A
 * terminal that is "passing raw data back" will render or forward them unchanged.
 *
 * Each payload probes one hypothesis about *how* the terminal might fail to
 * sanitize. The operator arms a profile, taps the terminal, and records what the
 * screen/printer/logs actually do. The value of a run is the observation, not the
 * payload — so every payload carries a human-readable description of what a failure
 * would look like.
 *
 * ## What this is NOT
 * None of these carry cardholder data, PANs, or cryptographic material. They are
 * synthetic strings whose only purpose is to reveal unsanitized rendering.
 */
enum class PayloadCategory(val label: String) {
    BASELINE("Baseline / control"),
    LENGTH("Length & truncation"),
    CONTROL_CHARS("Control characters"),
    TERMINAL_ESCAPE("Terminal escape sequences"),
    FORMAT_STRING("Format specifiers"),
    ENCODING("Encoding edge cases"),
    DELIMITER("Delimiter / injection markers"),
}

/**
 * A single test payload.
 *
 * @param id       stable identifier used in logs and reports.
 * @param category grouping for the UI.
 * @param summary  one-line description of the hypothesis / expected failure signal.
 * @param bytes    the exact value bytes injected into the field under test.
 */
data class Payload(
    val id: String,
    val category: PayloadCategory,
    val summary: String,
    val bytes: ByteArray,
) {
    /** A printable rendering of the bytes for the operator's log (non-printables shown as \xNN). */
    fun printable(): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v in 0x20..0x7E) sb.append(v.toChar()) else sb.append("\\x%02X".format(v))
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = other is Payload && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

object Payloads {

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    /** The full catalogue, ordered for a sensible walk from benign to aggressive. */
    val all: List<Payload> = listOf(

        // --- Baseline: a clean, spec-conformant value. Run this first to confirm the
        //     terminal displays the field at all and to capture its "normal" rendering. ---
        Payload(
            id = "baseline.clean",
            category = PayloadCategory.BASELINE,
            summary = "Conformant short ASCII label. Establishes the normal display baseline.",
            bytes = ascii("TEST CARD"),
        ),

        // --- Length & truncation: does the terminal cap the field, or walk off the end
        //     of a fixed buffer? The spec caps most display tags well under 64 chars. ---
        Payload(
            id = "length.64",
            category = PayloadCategory.LENGTH,
            summary = "64 printable chars. Watch for truncation point, overflow, or UI layout break.",
            bytes = ascii("A".repeat(64)),
        ),
        Payload(
            id = "length.252",
            category = PayloadCategory.LENGTH,
            summary = "252 chars (near single-record max). Probes fixed-size display/print buffers.",
            bytes = ascii("B".repeat(252)),
        ),
        Payload(
            id = "length.marker",
            category = PayloadCategory.LENGTH,
            summary = "Numbered ruler. The last digits visible on screen reveal the exact truncation length.",
            bytes = ascii((1..40).joinToString("") { (it % 10).toString() }),
        ),

        // --- Control characters: non-printable bytes that a sanitizer should strip. If
        //     the screen jumps, blanks, or the printer misbehaves, they were passed raw. ---
        Payload(
            id = "control.nul",
            category = PayloadCategory.CONTROL_CHARS,
            summary = "Embedded NUL (0x00). C-string handling may truncate at the NUL or mis-measure length.",
            bytes = ascii("ABC") + byteArrayOf(0x00) + ascii("DEF"),
        ),
        Payload(
            id = "control.crlf",
            category = PayloadCategory.CONTROL_CHARS,
            summary = "CR/LF embedded. May inject new lines into the display or into receipt/log output.",
            bytes = ascii("LINE1") + byteArrayOf(0x0D, 0x0A) + ascii("LINE2"),
        ),
        Payload(
            id = "control.lowrange",
            category = PayloadCategory.CONTROL_CHARS,
            summary = "Bytes 0x01-0x1F in sequence. Reveals whether any control byte is acted on rather than stripped.",
            bytes = ByteArray(0x1F) { (it + 1).toByte() },
        ),
        Payload(
            id = "control.backspace_bell",
            category = PayloadCategory.CONTROL_CHARS,
            summary = "Backspace (0x08) and bell (0x07). Classic terminal-control bytes for dumb displays.",
            bytes = ascii("X") + byteArrayOf(0x08, 0x08) + ascii("Y") + byteArrayOf(0x07),
        ),

        // --- Terminal escape sequences: if the display is a VT/ANSI-style device or logs
        //     flow to one, raw escape sequences can move the cursor, clear the screen, or
        //     recolour text. This is the clearest "arbitrary text/error on screen" signal. ---
        Payload(
            id = "escape.ansi_clear",
            category = PayloadCategory.TERMINAL_ESCAPE,
            summary = "ESC[2J ESC[H (clear screen + home). If the display clears or garbles, escapes are interpreted.",
            bytes = byteArrayOf(0x1B) + ascii("[2J") + byteArrayOf(0x1B) + ascii("[H") + ascii("OWNED"),
        ),
        Payload(
            id = "escape.ansi_color",
            category = PayloadCategory.TERMINAL_ESCAPE,
            summary = "ESC[31m red SGR. Coloured text on screen/logs proves SGR sequences are interpreted.",
            bytes = byteArrayOf(0x1B) + ascii("[31mERROR") + byteArrayOf(0x1B) + ascii("[0m"),
        ),
        Payload(
            id = "escape.error_text",
            category = PayloadCategory.TERMINAL_ESCAPE,
            summary = "Plain crafted 'SYSTEM ERROR E-1337' string. If shown verbatim, the field is rendered unsanitized.",
            bytes = ascii("SYSTEM ERROR E-1337"),
        ),

        // --- Format specifiers: if the value is ever passed through a printf-style or
        //     templating formatter, these can crash, leak memory, or render oddly. ---
        Payload(
            id = "format.printf",
            category = PayloadCategory.FORMAT_STRING,
            summary = "%s %x %p %n sequence. A crash or garbage output indicates printf-style formatting.",
            bytes = ascii("%s%x%p%n%s%s"),
        ),
        Payload(
            id = "format.brace",
            category = PayloadCategory.FORMAT_STRING,
            summary = "Brace/dollar template markers {0} \${env}. Probes string-templating / interpolation layers.",
            bytes = ascii("{0}{{7*7}}\${PATH}"),
        ),

        // --- Encoding edge cases: the display tags have declared character sets; feeding
        //     invalid/mixed encodings tests the decoder, not just the renderer. ---
        Payload(
            id = "encoding.invalid_utf8",
            category = PayloadCategory.ENCODING,
            summary = "Invalid UTF-8 continuation bytes. May throw, show replacement glyphs, or expose a decoder crash.",
            bytes = ascii("OK") + byteArrayOf(0xC0.toByte(), 0xAF.toByte(), 0xE0.toByte(), 0x80.toByte(), 0x80.toByte()),
        ),
        Payload(
            id = "encoding.high_ascii",
            category = PayloadCategory.ENCODING,
            summary = "Bytes 0x80-0xFF. Tests code-page assumptions for the Application Label / Preferred Name tags.",
            bytes = ByteArray(16) { (0x80 + it).toByte() },
        ),
        Payload(
            id = "encoding.rtl_combining",
            category = PayloadCategory.ENCODING,
            summary = "RTL override + combining marks (UTF-8). Probes bidi/combining handling in the renderer.",
            bytes = utf8("A‮B́́́C"),
        ),

        // --- Delimiter / injection markers: tagged strings that make it obvious, in any
        //     downstream log/receipt/host message, where card-controlled data landed. ---
        Payload(
            id = "delimiter.sql_like",
            category = PayloadCategory.DELIMITER,
            summary = "Quote/semicolon/comment markers. Reveals card data reaching a log or query unescaped.",
            bytes = ascii("'\";--/*<CARD>*/"),
        ),
        Payload(
            id = "delimiter.tag_markers",
            category = PayloadCategory.DELIMITER,
            summary = "Angle-bracket + canary token. Grep downstream logs/receipts for the canary to trace data flow.",
            bytes = ascii("<nfc-canary-7Q>"),
        ),
    )

    val byId: Map<String, Payload> = all.associateBy { it.id }

    fun get(id: String): Payload =
        byId[id] ?: error("Unknown payload id: $id")
}
