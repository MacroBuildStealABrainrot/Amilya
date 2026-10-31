# Amilya Render Test14 — isolated Android connection candidate

This build connects only to `https://amilya-repair-test.onrender.com/api/v1`.
Package: `com.woundcopilot.android.rendertest`. Launcher label: `Amilya Render Test`.
It installs separately from the original app and Repair Test13. It does not
upgrade, move, delete, migrate or share their recordings, sessions or app data.
It retains the original recorder and disables the crash-safe recorder and Wear
remote control for this candidate. No provider key or app credential is bundled.

The API origin guard permits the exact Render URL for this build only. It rejects
Cloud Run, the repair tag, local HTTP, other hosts, path variants and conflicting
build flags. The existing builds retain their original allowed origins.

CI builds and tests this variant, runs lint, checks the built manifest package
and disabled recorder service, and verifies the test APK signature. This is a
CI debug-signed test build. Production signing continuity and Samsung hardware
validation remain open; do not uninstall a recording-bearing app to install it.

## Server and account gates

Render provider integration checks already passed for nonclinical geometric
photo handling, fictional text extraction through Sol, and report generation
with QA. Those checks did not certify a real wound assessment or a phone flow.

Public AI, signup, billing and clinical-data processing are still disabled.
No Render app account, legal acceptance or transcription provider was created.
An APK configured for Render does not itself activate those services. It is not
a production-final release and cannot be described as a verified photo fix.

Before a complete authenticated app test: prepare an isolated account, obtain
the owner's actual policy acceptance without forging it, configure bounded
synthetic-only provider access, and test upload, extraction and report routes.
Use no patient records or photographs. Preserve the existing Cloud Run service
and all existing mobile recordings throughout.

## Verified build and file

- Source commit: `b5c33932c062e9cad75f7ecb88eab2aea92492c8`.
- Full CI run: [40618434623](https://github.com/MacroBuildStealABrainrot/Amilya/actions/runs/40618434623).
  Android compilation, unit tests, lint, package/recorder checks and APK signature
  verification passed. Backend: 874 passed, 1 skipped. PostgreSQL integration
  and repair deployment safety checks passed. Google Cloud deploy steps skipped.
- Artifact: `Amilya-Render-Test14.apk`, 32057199 bytes.
- APK SHA-256: `a37a8cb36047a1062e8446b25ba84b5b1d4c0a7ed009bf0773d605dccbe5fd82`.
- APK Signature Scheme v2 verified by Android SDK apksigner in CI.
- Test signer certificate SHA-256: `afc89c35b9e8839a98e077f2f9dd65c77d25856faaa0cf02fed993f457a16455`.
- The downloaded ZIP hash matched GitHub's artifact digest; APK bytes matched
  the CI checksum file. A separate local APK/DEX inspection confirmed its label,
  package, exact API URL, Render flag true, repair/sandbox flags false, original
  recorder enabled through the existing code path, SafeRecorderService disabled,
  and Wear remote control disabled.
- A persistent download was saved for the owner. No phone installation was
  performed; no existing app, session or recording was modified.

This APK is prepared for connection review. The authenticated photo workflow
is still gated by test-account setup, actual owner policy acceptance and
synthetic-only provider activation. It is not a certified Samsung photo fix.
