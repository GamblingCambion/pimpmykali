package com.leviathan.nfctester.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.leviathan.nfctester.R
import com.leviathan.nfctester.emv.ApduExchange
import com.leviathan.nfctester.emv.DisplayField
import com.leviathan.nfctester.emv.EmulatorState
import com.leviathan.nfctester.emv.EmvProfile
import com.leviathan.nfctester.payloads.Payload
import com.leviathan.nfctester.payloads.Payloads
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Operator console: choose the display field and payload to inject, arm the card,
 * tap the target terminal, and read the live APDU log. Export writes a plain-text
 * report you can drop straight into engagement notes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var fieldSpinner: Spinner
    private lateinit var payloadSpinner: Spinner
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val payloads: List<Payload> = Payloads.all

    private val logListener: (ApduExchange) -> Unit = { ex ->
        runOnUiThread { appendExchange(ex) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fieldSpinner = findViewById(R.id.fieldSpinner)
        payloadSpinner = findViewById(R.id.payloadSpinner)
        statusView = findViewById(R.id.statusView)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)

        fieldSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            DisplayField.values().map { "${it.tag}  ${it.description}" },
        )
        payloadSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            payloads.map { "${it.category.label}: ${it.id}" },
        )

        findViewById<Button>(R.id.armButton).setOnClickListener { arm() }
        findViewById<Button>(R.id.disarmButton).setOnClickListener { disarm() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { clearLog() }
        findViewById<Button>(R.id.exportButton).setOnClickListener { exportLog() }

        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        EmulatorState.log.addListener(logListener)
        reloadLog()
    }

    override fun onPause() {
        super.onPause()
        EmulatorState.log.removeListener(logListener)
    }

    private fun selectedField(): DisplayField = DisplayField.values()[fieldSpinner.selectedItemPosition]
    private fun selectedPayload(): Payload = payloads[payloadSpinner.selectedItemPosition]

    private fun arm() {
        val field = selectedField()
        val payload = selectedPayload()
        val profile = EmvProfile(
            name = "${field.name.lowercase()} / ${payload.id}",
            targetField = field,
            payload = payload,
        )
        EmulatorState.arm(profile)
        renderStatus()
        Toast.makeText(this, "Armed. Tap the terminal.", Toast.LENGTH_SHORT).show()
    }

    private fun disarm() {
        EmulatorState.disarm()
        renderStatus()
    }

    private fun clearLog() {
        EmulatorState.log.clear()
        logView.text = ""
    }

    private fun renderStatus() {
        val p = EmulatorState.current()
        statusView.text = if (p == null) {
            getString(R.string.status_unarmed)
        } else {
            "ARMED\nField: ${p.targetField.tag} (${p.targetField.description})\n" +
                "Payload: ${p.payload.id}\n${p.payload.summary}\n" +
                "Injected value: ${p.payload.printable()}"
        }
    }

    private fun reloadLog() {
        logView.text = ""
        EmulatorState.log.snapshot().forEach { appendExchange(it) }
    }

    private fun appendExchange(ex: ApduExchange) {
        logView.append(ex.format())
        logView.append("\n\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun exportLog() {
        val p = EmulatorState.current()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val header = buildString {
            append("NFC EMV input-handling test log\n")
            append("Exported: ").append(Date()).append('\n')
            if (p != null) {
                append("Profile: ").append(p.name).append('\n')
                append("Target field: ").append(p.targetField.tag).append(" (").append(p.targetField.description).append(")\n")
                append("Payload: ").append(p.payload.id).append(" — ").append(p.payload.summary).append('\n')
                append("Injected value: ").append(p.payload.printable()).append('\n')
            }
        }
        val text = EmulatorState.log.export(header)
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "nfc-test-$stamp.txt")
        file.writeText(text)

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Export test log"))
    }
}
