# Amilya 1.8.5-test10: your approved decisions and corrections

Date: 2026-10-24. Separate candidate; test8 and test9 packages, your installed app, Cloud Run, clinical data and live billing were **not touched**. Nothing was deployed.

## 1. Bottom line

* All five decisions and all five corrections are done in the source, with automatic tests.
* **Backend: 709 tests pass** (1 skipped by design), including the stuck-credit recovery on a **real PostgreSQL 16.15 server**.
* **Phone app logic: 187 Kotlin tests pass** on a PC (was 173).
* **Android build: still not possible in this workspace** (Google's download sites are blocked; no GitHub account is linked to this session, so I could not start GitHub Actions). New this round:
  * a complete **private GitHub Actions workflow** that builds every app variant, runs all unit tests, checks the build flags inside the built apps, and runs the sandbox app's phone tests on an Android emulator with throw-away test servers (`GITHUB-BUILD.md`);
  * a stronger local check: **every non-screen Kotlin file (state, recorder, upload, lock, network: 19 files, including all of this round's logic) now type-checks with zero errors against the real Android 36 API.** Screen files still cannot be checked without the Compose libraries.
* **Most important still-open risk:** nothing here has run on an Android device yet. Run the GitHub build first, then `FINAL-SAMSUNG-CHECKLIST.md`.

## 2. Your decisions

| # | Decision | What was done | Tested here | Needs phone/build |
|---|---|---|---|---|
| 1 | Prepare crash-safe recorder for release, not active | The 8 recorder files moved from the sandbox-only folder into the main app. Switched **off** in debug and release by default, both in code (`CRASH_SAFE_RECORDING`) and in the manifest (`android:enabled=false`). One build property (`-PwcpCrashSafeRecording=true`) would turn it on later; it must not be used without your approval. New guard tests check the flags in each build, and the GitHub job checks them inside the built apps. New phone test `ExistingWorkCompatibilityTest`: work left by the original recorder stays listed, playable, uploadable the old way, never mistaken for encrypted or multi-part, never cleaned up early | 117 recorder logic tests; zero type errors against real Android | Build-flag check, emulator compatibility test, checklist J |
| 2 | Stuck-credit recovery, development only | Built behind `AI_LEASES_ENABLED` (default **off**), **no database change**. A dead request's hold is given back in full, never charged; a late worker cannot charge or save; a retry works within the 3-attempt limit; a batch job reports before it acts (`--apply` to release); an absorbed-cost report | 16 tests on SQLite + PostgreSQL, including 12 parallel workers (exactly one release) and 20 settle-vs-release races (each ends exactly one way, credits conserved); a deliberate mistake is caught | A week of report-only running in a test deployment (your decision) |
| 3 | Keep immediate PIN on leaving the app | Unchanged. Trusted camera/picker window (3 min) kept | Lock tests | Checklist G5 |
| 4 | 3-second overlap (sandbox) | Each 2-minute upload piece after the first starts 3 s early (max 123 s, under the server's 130 s). The server removes the second copy **only** on an exact word-for-word match inside the overlap window; otherwise it keeps **both** and adds a review note naming the time. Nothing is reworded. The full unmerged text is kept with the transcript | 13 tests: doses ("metformin 500" kept once), negation ("denies" never lost), differing numbers (both kept), genuine repeats ("no no no" keeps 3), words outside the window never removed, timestamps and total length correct | Checklist D |
| 5 | Microphone warning + notification permission | Recorders ask Android once a second whether this recording is silenced (Android 10+). On screen: "The microphone is being used by a phone call or another app..." and, after, a summary kept with the recording and shown again with the report. Microphone and (once, Android 13+) notifications are asked together; refusing notifications never blocks recording, and the screen then says where Stop is | 4 tests (timing, counting, permission rules) | Checklist E and F |

## 3. Your corrections

| Finding | Cause | Fix | Test |
|---|---|---|---|
| Deferred photo cleared before the "loading" check | The reference was set to null first, then the function returned when busy | The photo now **waits** while the app is locked, busy, importing, or on another visit, and is sent when the current task ends or after unlock. Dropped only if another clinician signs in | 2 new tests (`LockPolicyTest`) |
| Retries were 3 extra (4 total) | Wrong constant meaning | Now **3 attempts in total, including the first** (`MAX_REPORT_STAGE_ATTEMPTS = 3`). Transcription and photo limits unchanged | Test asserts exactly 3 paid attempts, then none |
| Guide E14 vs E23 | E14 was written for test7 | E14 replaced: long visits continue at 59:00; the 59:50 stop happens only if the hand-over cannot start (E28) or after the last part. E26 is now pass/fail | Guide and checklist |
| 90-minute visit must give one report | Only the last part was uploaded automatically; the report was made before the other parts | The app finds every part of the visit (from any part, in order), transcribes **all** of them before the single report, labels them "Part 1 of 2..." for the AI, and keeps them together even if uploaded out of order. A missing part stops with a clear message (nothing deleted) instead of making an incomplete report | 6 ordering tests; server test: two parts give one report containing both |
| Preserve the original transcript when cleaning repeats | test9 kept only a warning | The original is always kept (`original_transcript`) with the source and the assembled transcript. **A repeated sentence with a number, dose, unit, negation, frequency or side is never shortened**, only flagged. Only short, non-clinical repeats ("Thank you." x3) are shortened in the working text | 9 tests (11 kinds of clinical sentences kept) |

## 4. Files changed (main ones)

* Server: `providers.py` (retry limit, repeat rules, leases), `billing.py` (stuck-credit recovery), `audio_parts.py` and new `audio_overlap.py` (overlap, visit parts, preserved transcript), `quick_workflows.py` (part labels and order), `clinical.py` (preserved transcript), `core.py` (flags, default off), new `scripts/release_stale_holds.py`.
* Phone: `AppState.kt` (all parts before one report; deferred photo; warnings shown with the report), `ExternalCapture.kt`, `LockPolicy.kt`, new `RecordingSafety.kt`, `DeviceTools.kt` (microphone warning), `CaptureScreen.kt` (permissions), `AudioChunks.kt` (visit fields, overlap), the 8 recorder files moved to `main`, `SealedRecording.kt` (overlap), `VisitContinuation.kt` (part order, plain messages), `AndroidManifest.xml`, `build.gradle.kts` (flags), version 1.8.5-test10 (versionCode 30). Application IDs unchanged.
* Tests: 2 new and 3 updated server test files, 4 new Kotlin unit-test files (plus new tests in 3 existing ones), 1 new phone test.
* Build: `.github/workflows/amilya-sandbox-ci.yml`, `ci/run-phone-tests.sh`, `ci/verify-build-flags.sh`, root `.gitignore` (keeps keys out).
* Full diff against test9: `source-changes/1.8.5-test10/0900-*.patch` (applies and reverses cleanly).

## 5. Evidence on this computer (`verification/`)

| Log | Result |
|---|---|
| `backend-pytest-test10.log` | 709 passed, 1 skipped |
| `stuck-credit-sqlite-and-postgres-test10.log` | 16 passed on SQLite + PostgreSQL 16.15 |
| `test10-tests-against-test9-code.log` | the new tests fail on test9 code (they detect the changes) |
| `kotlin-logic-tests-test10.log` | 31 + 39 + 117 = 187 passed |
| `android-api-typecheck-test10.log` | 19 key files: 0 errors against the real Android 36 API |
| `standin-compile-test10.log` | 0 errors |
| `python-static-checks-test10.log` | 85 files, 0 syntax errors, no blocking calls in async code |
| Both throw-away test servers (Level 1, Level 2) | Started with this code; health and 404 checks answered |

## 6. Remaining risks

1. **No Android build or device run yet.** Compose screen code (`CaptureScreen.kt` permission flow) is checked by reading only. The GitHub build is the next step.
2. Overlap merging depends on the speech service writing the same words twice; when it does not, words near a cut may appear twice (flagged, never lost).
3. Microphone-silence detection depends on Android reporting it; Samsung behaviour must be confirmed (checklist E).
4. Leases are off. Turning them on anywhere real needs your decision on the alert limit and a report-only trial.
5. The crash-safe recorder in release stays off until the Samsung test, compatibility test and your written approval.
