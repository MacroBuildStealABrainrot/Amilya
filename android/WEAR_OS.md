# Amilya / WoundCoPilot Wear OS companion

Version 1.8.2-test1, protocol 1. This is an extension of the existing phone app. Phase 1 uses the **phone microphone**. Direct watch audio, tiles and wake-word listening are not implemented.

## Simplified test setup (1.8.2)

Extract the matching phone/watch delivery bundle. With the phone and watch authorized in ADB, run `Install-Amilya-Watch.ps1`; it finds one device of each type, verifies bundled APK checksums, updates both apps without uninstalling, and opens them. `-CheckOnly` validates without changing devices. For first-time watch wireless debugging, supply `-PairAddress IP:PAIR_PORT -WatchAddress IP:CONNECT_PORT`; ADB asks for the code interactively. This helper does not replace normal Samsung/Google watch-phone pairing. It needs Android platform-tools, but not a project build or Android Studio setup.

Sign in on the phone only. The watch discovers/reconnects automatically while its app is open, checking again every 15 seconds when unsynchronized. A healthy connection is not polled every 15 seconds. No watch account, PIN, API key or background microphone permission was introduced. Pausing the watch screen stops the reconnection loop.

When phone setup is needed, use **Open phone**. The owner-bound navigation shortcut reaches Document after normal unlock; it never records on its own. Once ready, **Record visit** confirms the clearly displayed recording/processing consent and starts in one tap. Stop remains one tap. Discard keeps an explicit confirmation tied to the same recording. Reconnect never replays Start or Stop.

The disconnected watch screen distinguishes a reachable phone missing the matching app from an unavailable connection, and provides expandable setup help. Rotary/crown scrolling is supported; the content remains scrollable at larger text sizes. The device still needs a visible, unlocked phone app to START recording; this release does not claim locked-pocket start.

For eventual client distribution without ADB/developer options, use a Wear OS Play testing/distribution track. Neither this repository change nor the installer publishes an app or creates a Play listing.

## Architecture and source of truth

- `app`: existing AppState, RecordingService, RecordingRecovery, ProcessingService, API, report editor, vault and diagnostics.
- `wear`: native Wear Compose Material 3 companion, capability discovery, command transport and operational-state display.
- `wear-contract`: small UI-independent binary protocol, validation and watch mirror. No clinical domain or provider code is moved here.
- The phone owns all clinical and recording state. The watch cannot create clinical reports, upload audio or contact AI providers.
- Google Play services Data Layer enforces matching package names and signing certificates. Both release APKs are `com.woundcopilot.android.release`; both debug APKs are `com.woundcopilot.android`. Install matching build variants.

Phone START/STOP and watch START/STOP control the same RecordingService. Watch START calls existing `startQuick` when a new Document encounter is needed, prepares the existing recovery context and starts the existing service. The existing AppState collector observes completion, uploads, transcribes and generates the selected report. Existing checkpoints publish real operational updates even when the Activity has been disposed.

## Protocol

Capabilities: `amilya_phone_remote` and `amilya_watch_remote`. Exactly one reachable capable phone is required on the watch; multiple candidates produce an explicit error rather than choosing arbitrarily. The phone validates that the sender advertises the companion capability. Commands arrive only through WearableListenerService, not exported command intents.

Paths:

- `/amilya/v1/command`: MessageClient transient command.
- `/amilya/v1/response`: acknowledgement with current authoritative state and matching command ID.
- `/amilya/v1/status`: DataClient latest operational snapshot, overwritten on workflow changes.

Commands: STATUS, START, STOP, CANCEL, RETRY, OPEN_PHONE. Payloads are bounded to 2 KiB and contain a protocol version, UUID command ID, operation nonce, opaque job ID, timestamp and explicit recording-consent boolean. Fields use closed enums and UUID validation. Unknown/trailing fields and oversized packets are rejected. No arbitrary clinical string can be encoded in the accepted contract.

A new phone-process epoch and monotonic sequence distinguish snapshots. Fresh status acknowledgement is required after watch resume/reconnect. Cached snapshots cannot enable controls. Commands expire after 30 seconds (with 5 seconds future-clock tolerance); keep both devices on automatic time. A mutex serializes execution. The phone reserves command IDs in the existing encrypted vault before effects and rotates the operation nonce. Duplicate/replayed commands are rejected and resynchronized; they are never queued for later automatic execution. The bounded ledger retains at most 128 technical records and drops entries older than one day when updated.

## Operational states and progress

The watch displays actual recording/paused state, local elapsed duration, saved/recoverable state, combined upload/transcription, transcription completion, generation, report readiness, retryable failure and cancellation. It does not pretend that transport delivery means the microphone started.

The current synchronous backend combines upload and transcription in one HTTP operation. Accordingly the watch says **Uploading / transcribing**, not separate invented progress percentages or independently measured server-stage notifications. TRANSCRIBED checkpoints show transcription completion; REPORT_READY means a generated draft is ready for clinician review, not that it has been signed or clinically approved.

Timer samples come from RecordingService. Between transitions the watch advances its display locally using a monotonic clock; there is no once-per-second Data Layer poll. A disconnected display is labelled last-known and freezes until refreshed. Amplitude updates do not trigger disk reads or radio publication.

## Authentication, consent and background restrictions

START requires an existing signed-in phone session, completed app setup/legal acceptance, an unlocked app, an unlocked interactive device, a visible phone Activity and phone microphone permission. The watch asks the clinician to confirm recording/processing consent for the visit. It does not grant OS permissions. If prerequisites fail, the watch explains which phone action is needed.

**Starting with the phone locked in a pocket is not enabled by this release.** The current app locks when backgrounded. This integration does not weaken that policy or exploit a background microphone exemption. STOP is allowed for the same already-authorized active session even if the app subsequently locks, matching the existing recording notification's stop control; STOP never unlocks the app. CANCEL and RETRY require normal unlock. CANCEL requires watch confirmation, marks the existing capture before stopping to prevent an auto-processing race, and uses RecordingService.clearPending after finalization. If cancellation is interrupted, retained recovery data is left for phone review rather than deleting it blindly.

OPEN_PHONE uses the platform RemoteActivityHelper and a narrowly scoped VIEW link, `woundcopilot://wear/open`. The link itself cannot record, unlock or select arbitrary clinical data. Only the authenticated Data Layer command writes an owner-bound, two-minute pending result target to the encrypted vault. The existing PIN gate is respected; after unlock the existing report editor/recovery action opens the matching ready job. A cold process reads existing checkpoints without constructing a second AppState. If the platform cannot launch the phone, the watch asks the user to open it manually.

## Privacy, storage and cleanup

Watch storage contains **only** one operational snapshot: phase, opaque job/command identifiers, process epoch, command nonce, timer sample, sequence, saved flag, normalized error and timestamps. The nonce is a short-lived command freshness token, not an account session cookie. No name, report, wound data, transcript, audio, phone number, password, API key or authentication cookie is sent to the watch.

The snapshot is stored in private SharedPreferences with backup disabled. On app launch, snapshots older than 24 hours are removed. Data Layer keeps the latest metadata item until overwritten by subsequent state/session updates; it is not a clinical history. These technical fields are not presented as an encrypted clinical-data vault. The watch owns no clinical media and cannot delay media cleanup.

Phone audio location, encryption claims, temporary retention, transcription retries, idempotency and report storage remain the existing implementation. No retention period was extended. Pending open-result metadata uses the existing encrypted vault and is removed when consumed, expired or mismatched. Failed/queued audio is not deleted by watch disconnect, retry or status sync. Wear-specific phone metadata also participates in the existing seven-day recovery-metadata expiry, so abandoned cancellation markers and unused command ledgers do not persist indefinitely.

## Offline and restart behavior

- Watch disconnected: phone recording and processing continue independently. Watch controls are disabled and last-known status is labelled. Reconnect requests authoritative state; no stale command is resent.
- Phone Internet unavailable: recording stays local. Existing failure/recovery checkpoints preserve audio. The watch reports saved/waiting for connection when the phone detects lost connectivity. Use Retry after connection returns and normal phone unlock requirements are met. This pass does not introduce an automatic background upload scheduler; the existing app's retry policy remains in control.
- Phone process restart: never silently restarts the microphone. Existing recoverable recordings/jobs remain owned by the phone. The cold receiver exposes only their technical state; processing resumes through existing phone recovery/Retry. An interrupted, unfinalized recording is not labelled safely saved.
- Watch restart: restores at most the recent labelled snapshot, then performs a fresh status handshake. Controls remain disabled until it succeeds.
- Cleanup removes a completed job: the watch cannot recreate it. Open the phone's normal history if the result is no longer in temporary recording recovery.

## Diagnostics

Reuses Developer Diagnostics and its encrypted bounded outbox. Adds `flow_type=WEAR_OS`, derived backend `origin=WEAR_OS`, normalized `wear_error` and UUID `client_request_id`. Events include watch_connected/disconnected, start_requested/confirmed, stop_requested/confirmed, cancel_requested, open_phone, status_sync and command_failed. Requested/confirmed timestamps allow command execution delay to be inspected. No cross-device latency is claimed from unsynchronized clocks.

Backend ingestion classifies these as `wear_control` while retaining `source=device_reported`, so they cannot be mistaken for provider-verified events. Raw error text and arbitrary additional fields are rejected. Administrator authorization remains required for diagnostics. Deploy the updated backend schema allowlists before expecting new Wear events to upload; older servers reject unknown event names. No new database migration is required by this pass; the earlier diagnostics migration remains a prerequisite where not already applied.

## Permissions and feature flag

Phone adds ACCESS_NETWORK_STATE to distinguish queued offline status. Existing RECORD_AUDIO and microphone/data-sync foreground-service permissions are reused. No raw Bluetooth, broad storage, health-sensor or new location permission is requested. Watch does not request RECORD_AUDIO. Dependencies may contribute normal network/service permissions; inspect the delivery's merged permission manifest.

`-PwcpWearRemoteControl=false` disables phone remote control and checkpoint publishing at build time; default is true. There is no experimental direct-audio path to accidentally enable. `-PwcpAllowScreenCapture=true` remains the explicit test-only phone capture option. Production default remains false.

## Build and signing

Open this existing Android project in Android Studio and sync. Create/select an Android App run configuration with module `wear`, then select the watch device. Use `app` for the phone.

```powershell
.\gradlew.bat :wear-contract:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :wear:assembleDebug :app:lintDebug :wear:lintDebug
.\gradlew.bat :app:assembleRelease :wear:assembleRelease :app:lintRelease :wear:lintRelease -PwcpAllowScreenCapture=true
```

Release artifacts must both be signed with the existing owner-controlled signing key using the existing safe signing process. Debug and release variants cannot communicate with one another. Do not put a signing password or provider key in Gradle/source. Phone test APK is an update to the existing release package; do not uninstall it to upgrade.

## Install on Amir's Samsung Galaxy Watch

1. Pair the watch and Android phone normally through Samsung's companion setup. Confirm the watch runs **Wear OS**; a Tizen watch cannot run this module.
2. Install the matching phone APK, sign in, complete PIN setup, and allow microphone permission from the normal Document screen.
3. On the watch, open Settings → About watch → Software/Software information and tap Software version five times to enable Developer options. Menu names vary by firmware.
4. Put the computer and watch on the same Wi-Fi network. Enable ADB debugging and Wireless debugging in the watch's Developer options. Choose Pair new device and keep its pairing code visible.
5. In Android Studio choose Pair Devices Using Wi-Fi, or run `adb pair WATCH_IP:PAIRING_PORT` and enter the displayed code. Then run `adb connect WATCH_IP:DEBUG_PORT`. The debugging port can differ from the pairing port. Use the values displayed on the watch; do not copy example ports.
6. Confirm the watch appears in `adb devices`. Select the `wear` run configuration and watch device, or install the supplied signed watch APK with `adb -s WATCH_IP:DEBUG_PORT install -r PATH_TO_WATCH_APK`.
7. Launch Amilya on both devices. Confirm Phone connected on the watch. If disconnected, check that both APKs use the same variant/signing key, both devices have Google Play services, and exactly one capable phone is reachable.
8. Keep the phone app open and unlocked for initial testing. Read the consent statement and tap Record visit on the watch for a **fictional** visit, verify the phone's actual recording indicator and the watch timer, then STOP.
9. Verify saved audio, combined upload/transcription, report generation and REPORT READY. Tap OPEN ON PHONE; verify the correct report and normal PIN protection if relocked.
10. Disable wireless debugging when finished testing.

Samsung's setup instructions: https://developer.samsung.com/health/sensor/guide/connect-watch.html . Android wireless debugging: https://developer.android.com/training/wearables/get-started/debug-wifi . Emulator pairing requires Android Studio's Wear OS pairing assistant and the appropriate phone companion app: https://developer.android.com/training/wearables/get-started/connect-phone .

## Physical-device acceptance checklist

| Test | Expected |
|---|---|
| Phone open/unlocked, watch START | One real phone recording, confirmation only after recorder start |
| Watch STOP | Same recording finalizes, saved source survives processing |
| Start watch, stop phone | Watch follows the same session |
| Start phone, stop watch | Same session stops; no second recorder |
| START while phone locked/backgrounded | Clear unlock/open-phone message; no microphone bypass |
| Lock phone after recording started, then watch STOP | Existing recording stops without unlocking clinical screens |
| Disconnect watch while recording | Phone keeps recording; watch status becomes unavailable/last-known |
| Reconnect watch | Fresh status; no replayed Start or Stop |
| Disable phone Internet during recording/processing | Audio preserved; retry uses existing recording |
| Restore Internet and use Retry | Existing pipeline resumes; no duplicate visible report |
| Force report failure, then Retry | Saved source remains; valid transcription reused |
| Double Start/Stop, Stop versus Cancel | One session; no duplicate processing or accidental completed-report deletion |
| Close/reopen phone and watch | No silent microphone restart; pending work discoverable |
| Report finishes while Activity/watch closed | Checkpoint sync exposes ready result on reconnect |
| OPEN ON PHONE while PIN locked | Correct result opens only after normal unlock |
| Large font/round watch/rotary input | All controls reachable; verify on target Galaxy Watch firmware |

## Phase 2

Direct watch recording is deliberately not implemented or advertised as a capability. A later version can add an explicit protocol capability and channel/asset transfer contract, independent watch microphone permission, private durable media storage, integrity checks, acknowledgement and retry. Accepted audio must then enter the existing phone upload/transcription pipeline. This release provides no guarantee of watch-side clinical audio recovery because no such capture path exists.
