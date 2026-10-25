# WoundCoPilot for Android

A native Kotlin / Jetpack Compose client for the existing WoundCoPilot API. The production backend and web frontend are separate and are not rewritten by this Android project.

## Interface 1.3

The encounter has three persistent destinations: **Capture, Review, Documents**. Swipe between them or use the custom dock. The patient and wound remain visible above every clinical screen.

Record, Photo, Write note and Measure are available directly from Capture. The next-action panel follows pending findings and documentation state. Less common sources live under More ways to add evidence. Supporting source text, history, template settings, optional observation details and capture limitations expand on request.

`ui/InstrumentUI.kt` contains the original vector icon set, layered panels, capture controls, recorder button and navigation dock. Press states use restrained scale/depth feedback, and haptics use Android's system-respecting feedback events. Animation uses Compose's duration scaling. Actual recorded amplitude drives the waveform; no fake processing progress or AI outcome is shown.

See [the interface specification](INTERFACE.md) for the screen flow, interaction rules and implementation references. Wide screens replace the phone dock with sidebar navigation and keep source context beside review and document work.

## Run

Open this folder in Android Studio, sync Gradle and run the `app` configuration. Minimum Android 9 / API 28. This build uses the installed stable AGP 9.3.2, Compose BOM 2026.02.01 and Gradle 9.5.0. `local.properties` points at the local SDK and should not be committed or copied to another computer.

On this computer, `Open-AndroidStudio.ps1` reuses a separate Studio profile because the existing user profile has an inaccessible `.port` startup file. Existing Studio settings are preserved.

Use **Explore a sample encounter** to exercise the app without an account or provider credits. Sign in with an existing WoundCoPilot account for the connected workspace. The API origin is fixed to the deployed WoundCoPilot Cloud Run service; provider credentials never belong in the Android project.

## Included

- Native account creation/sign-in, policy acceptance, account profile, support and service/credit status.
- Authorized administration overview, cost/provider status and contact inbox status management.
- Patient/wound selection and encounter creation with recovery of acknowledged intermediate records.
- Independent manual, measurement, typed, transcript, result, photograph, audio and document evidence workflows.
- Microphone foreground recording, pause/resume, native playback, private originals, recovery and retry.
- System camera/document picker, original JPEG/PNG/WebP inspection, explicit photo limitations and source-linked evidence history.
- Short-window audio verification with original-file hash checking and explicit reviewed-transcript acceptance.
- Finding confirmation/correction/rejection; deterministic case checks and server-assisted review/questions.
- Existing nursing, SOAP, SBAR, handoff, referral and custom document templates.
- Exact-text draft editing/recovery, server save, explicit signing, amendments and version history.
- Native text/PDF sharing with version/status context, dark/light themes and adaptive tablet context panel.

## Existing API boundaries

The current service processes uploads synchronously and does not retain raw media or expose background job/resumable-upload endpoints. Originals are local to the capturing device. Failed or ambiguous operations must remain retryable with the same submission identifier; saving locally is distinct from saving to the server.

Kosmo is the single routing identity. Model selection is server-side. The API returns the selected model and effort metadata; the client renders those actual values. Luna/Terra/Sol are not separate feature brands.

The live service currently reports synthetic-data-only operation, configured AI/transcription and disabled checkout. Provider credit exhaustion is a real runtime failure, not permission to fabricate a successful AI result. No native purchases are implemented against the disabled checkout.

## Storage and security

Session cookies, document drafts and form recovery data use AES-GCM with an Android Keystore key in no-backup storage. Raw captures are in private application storage and excluded from backup. Signed documents remain authoritative on the backend. Release builds block screenshots; debug builds permit synthetic screenshot QA. Release networking requires HTTPS to the fixed service origin. Debug localhost access exists solely for isolated contract tests.

## Verification

The 1.3.0 delivery includes explicit task status while generating, saving, reviewing and uploading. The document action is labelled **Report with advice**.

Build: `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`.

UI tests use only synthetic sample records. LocalStorageAndExportTest verifies encryption integrity, UTF-8 byte fidelity and multi-page PDF rendering. BackendContractTest uses the unchanged backend with a separate SQLite database and all AI providers disabled.

For the HTTP test, use the sandbox scripts described at the end of this file (the backend is the `backend` folder of this project; no `woundcopilot-next` folder is needed). Do not run these tests on an installation containing a signed-in real user.

The 1.3.0 APK is a non-debug release signed with an owner-controlled persistent key. Its package is com.woundcopilot.android.release, so it installs alongside older debug builds without deleting their local work. Provider authorities follow the build application ID. Store distribution still requires its distribution setup and review. Emulator testing does not establish performance or camera/audio behavior on every physical handset.

## Release build

Run `./gradlew.bat :app:assembleRelease :app:lintRelease`, then sign the unsigned APK using `tools/sign_release.py` with the private owner key and WCP_SIGN_PASSWORD. No key or password is included in source. The release blocks debug HTTP origins and screenshots.

For release instrumentation, build `:app:assembleReleaseAndroidTest -PwcpTestBuildType=release` and sign that test APK with the same key. `connectedDelivery=true` explicitly opts into a new synthetic account on the deployed API; leave it unset in ordinary local testing. The local HTTP test is skipped in release builds.


## Wear OS companion (1.8.0-test1)

The existing phone recorder now has a Wear OS remote companion in `wear`, with a small `wear-contract` protocol module. See [WEAR_OS.md](WEAR_OS.md) for architecture, authentication limits, matching APK installation, diagnostics, privacy and Galaxy Watch testing. The watch uses the phone microphone; starting from a locked/backgrounded phone is intentionally not enabled. No clinical prompts or backend processing pipeline were changed by this integration.


## Safe test-only build (1.8.5-test3)

Use the `sandbox` build type for all phone tests: `:app:assembleSandbox`, `:app:connectedSandboxAndroidTest`. Package `com.woundcopilot.android.sandbox`, named "WoundCoPilot SANDBOX". It can reach only the test server on this PC (`127.0.0.1:18766` through `adb reverse`); the live service address is refused in code (`isAllowedApiOrigin`, covered by `ApiOriginGuardTest`). The old note about a `woundcopilot-next` folder no longer applies: the backend is the `backend` folder of this project. See `RUN-ON-YOUR-PC.md` and the `sandbox` folder at the project root.
