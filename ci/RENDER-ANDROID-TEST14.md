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
