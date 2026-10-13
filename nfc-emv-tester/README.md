# NFC EMV Input-Handling Tester

An Android **Host Card Emulation (HCE)** harness for assessing how a contactless
payment terminal handles the data a card sends it. It emulates a contactless EMV
card and injects crafted values into the human-readable fields a terminal may
render on screen, so you can determine whether the device **sanitizes card-supplied
input** or **passes raw data through** to its display, logs, receipts, or host
messages.

> **Phase 1 (this release): display-field injection.** Phase 2 (structural / APDU
> fuzzing) is scaffolded but not yet enabled — see the roadmap below.

---

## Authorized use only

This is a security-assessment tool for use **only against devices you are
explicitly authorized to test** — your own equipment, lab units, or terminals
covered by a signed engagement with permission to perform NFC/contactless testing.
Confirm the target and the rules of engagement before you tap anything. Do not use
it against third-party or production payment devices without written authorization.

The tool is deliberately built so it **cannot complete a transaction** (see below).

---

## What it tests, and why it's safe by construction

A contactless EMV transaction is an APDU conversation. Early in that conversation —
before any cryptographic authentication — the card returns several **human-readable
string fields** that terminals commonly display or print:

| Tag    | Field                        | Where it appears              |
|--------|------------------------------|-------------------------------|
| `50`   | Application Label            | PPSE directory entry, ADF FCI |
| `9F12` | Application Preferred Name   | ADF FCI                       |
| `5F20` | Cardholder Name              | READ RECORD response          |

A terminal that sanitizes input will length-cap these, strip non-printable/control
bytes, and treat them as opaque text. A terminal that is "passing raw data back"
will render them unchanged — which is where you see truncation bugs, control-byte
effects, terminal-escape interpretation, format-string handling, or card-controlled
text appearing on screen / in logs / on receipts.

**Safe by construction:** the emulated card carries **no PAN, no track-2 data, no
expiry, and no issuer keys**, and it **refuses `GENERATE AC`** (returns `6985`). It
can only exercise the terminal's pre-authentication *parsing and display* path. It
cannot produce a valid cryptogram and cannot complete a payment, regardless of which
AID it advertises.

### Realistic expectations

The most common outcome against a hardened terminal is a clean generic decline
("card read error", "use another card") — which is itself a useful result: the
device bailed safely. The findings worth reporting are things like:

- card-controlled text rendered verbatim on the display,
- truncation that reveals a fixed buffer size,
- a hang, reset, or kernel error,
- the injected canary turning up unescaped in a log, receipt, or host message.

EMVCo L1/L2 kernels are generally well-hardened; the long tail of value is in OEM
terminal UIs, unattended/kiosk readers, and downstream logging/receipt pipelines.

---

## How it works

```
terminal                              emulated card (this app)
--------                              ------------------------
SELECT PPSE (2PAY.SYS.DDF01)   --->   FCI: our AID + Application Label (50)
SELECT AID                     --->   FCI: Application Label (50) + Preferred Name (9F12)
GET PROCESSING OPTIONS         --->   AIP + AFL (points at SFI 1, record 1)
READ RECORD (SFI 1, rec 1)     --->   record: Cardholder Name (5F20)
GENERATE AC                    --->   6985  (refused — no transaction can complete)
```

Each experiment isolates **one** display field: the chosen payload goes into the
target tag, every other display field carries a benign baseline value. So when the
terminal misbehaves, the result points at a specific field.

### Payload categories

| Category               | What it probes                                                        |
|------------------------|-----------------------------------------------------------------------|
| Baseline / control     | A clean conformant value — captures the terminal's normal rendering.  |
| Length & truncation    | Over-length / numbered-ruler strings — truncation point, buffer size. |
| Control characters      | `0x00`, CR/LF, `0x01`–`0x1F`, backspace/bell — stripped or acted on?  |
| Terminal escape         | ANSI/VT escape sequences — cursor/clear/colour = interpreted raw.     |
| Format specifiers       | `%s %x %n`, `{0}`, `${}` — printf/templating crashes or leaks.        |
| Encoding edge cases     | Invalid UTF-8, high-ASCII, RTL/combining marks — decoder behaviour.   |
| Delimiter / injection   | Quote/semicolon/angle-bracket canaries — unescaped data downstream.   |

See [`payloads/Payloads.kt`](app/src/main/java/com/leviathan/nfctester/payloads/Payloads.kt)
for the full catalogue; each payload documents the hypothesis it tests.

---

## Requirements

- An Android phone with NFC that supports HCE (Android 5.0 / API 21+; virtually all
  modern devices).
- Android Studio (recommended) or a configured command-line Android SDK.
- The device held to the target reader's contactless field when armed.

Note on AID routing: the test AIDs are registered under HCE category `other` (see
[`res/xml/apduservice.xml`](app/src/main/res/xml/apduservice.xml)), which lets the
OS route them to this app without it being the default wallet. On some
devices/readers, scheme AIDs (`A000000003...`) may be claimed by a secure element or
require the app to be the selected contactless-payment app — if a tap doesn't reach
the app, see Troubleshooting.

---

## Build & run

Open the project in Android Studio and run the `app` configuration onto a physical
device (HCE does not work on the emulator), **or** from the command line:

```bash
# from the project root
./gradlew :app:installDebug      # build + install onto a connected device
```

> The Gradle wrapper JAR is not committed. In Android Studio the wrapper is restored
> automatically on first sync. For the CLI, run `gradle wrapper` once (with a local
> Gradle 8.7) to generate `gradle/wrapper/gradle-wrapper.jar`.

### Run the logic tests

The `emv/` and `payloads/` packages are framework-free and unit-tested on the JVM:

```bash
./gradlew :app:test
```

These validate the APDU routing, BER-TLV construction, payload placement, and the
`GENERATE AC` refusal.

---

## Using it in the field

1. Launch the app. Choose the **display field** and the **payload** to inject.
2. Tap **Arm**. The status panel shows the exact bytes that will be served.
3. Hold the phone to the target reader.
4. Watch the reader's screen/printer **and** the app's live **APDU log**, which
   records every command/response exchange with a human-readable note.
5. Repeat across payloads and fields. Use **Export** to save a plain-text log for
   your engagement notes.

Work from benign to aggressive: run `baseline.clean` first to confirm the field is
displayed at all and to capture the normal rendering, then escalate.

---

## Troubleshooting

- **Tap never reaches the app.** The reader may route scheme AIDs to a secure
  element, or require a default payment app. Try: disable other wallet apps; set
  this app as the default contactless payment app; or add the target's specific AID
  to `apduservice.xml`. Confirm with a reader you control first.
- **Terminal shows a generic decline immediately.** Expected and fine — note it.
  The display fields are populated during selection/read, before the decline.
- **Large payloads rejected.** Some readers cap record/APDU sizes; a rejection of an
  over-length field is itself a (positive) sanitization result.

---

## Roadmap

- **Phase 1 — display-field injection (done).** Inject crafted values into tags
  `50` / `9F12` / `5F20`; observe terminal rendering and downstream handling.
- **Phase 2 — structural / APDU fuzzing (scaffolded).** The TLV encoder already has
  a `LengthMode` hook for emitting deliberately malformed lengths. Phase 2 extends
  this to fuzz the whole selection flow: wrong declared lengths, truncated TLVs,
  unexpected status words, malformed FCI/GPO templates — testing the terminal's
  *parser*, not just its renderer.

---

## Project layout

```
app/src/main/java/com/leviathan/nfctester/
  emv/
    Hex.kt            byte/hex helpers
    Tlv.kt            BER-TLV encoder (+ LengthMode hook for Phase 2)
    Apdu.kt           command-APDU parser, status words, INS selectors
    EmvProfile.kt     display-field model + one-variable experiment definition
    CardEmulator.kt   the emulated card: command APDU -> response APDU
    TransactionLog.kt append-only, observable APDU log + text export
    EmulatorState.kt  UI <-> HCE service handoff
  payloads/
    Payloads.kt       the test payload catalogue
  ui/
    MainActivity.kt   operator console
  HceService.kt       HostApduService entry point
app/src/test/java/...  JVM unit tests for the emulator logic
```
