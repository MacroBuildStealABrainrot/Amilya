# WoundCoPilot / Amilya source: 1.8.5-test10 CANDIDATE (NOT YET BUILT OR PHONE-TESTED; the sections below are the history, newest last)

This is a separate candidate. The original `WoundCoPilot-1.8.5-test1` archive was not modified.

**Verification status.** Not built and not run by the reviewer (no Android SDK, Gradle, network, or Python packages were available).
Checked offline only: patches apply and reverse cleanly, Python syntax compiles, Kotlin delimiter balance, and a static guard test for blocking audio calls.
It must pass `SAMSUNG-TEST-GUIDE.md` on your Samsung phone before it replaces anything.

## What changed from 1.8.5-test1 (all patches are in `source-changes/1.8.5-test2/`)

| Patch | Change |
|---|---|
| 0002 | After an offline start the app fetches its in-memory security token once, before the first save or upload |
| 0002b | If the server still rejects a save for a stale token, the app refreshes it and resends the identical request once (same Idempotency-Key). The error offers Try Again and says the recording is not affected |
| 0003 | The recording service goes foreground first and checks microphone permission before it starts, which prevents a start-up crash |
| 0007 | The server no longer freezes while it checks audio (ffprobe/ffmpeg run in a worker thread) |
| 0100 | `versionCode` 21 to 22 and `versionName` 1.8.5-test1 to 1.8.5-test2 (phone and watch). The application ID is unchanged |

Not changed: billing, the database, API contracts, screens, workflows, the release application ID, and recording, recovery or retention logic.
Not included from the review: items 1, 4, 5, 6 and 8 (designs only, awaiting approval).

## Apply or reverse the patches on a clean 1.8.5-test1 tree

```
patch -p1 < source-changes/1.8.5-test2/0002-offline-startup-auth.patch   # then 0002b, 0003, 0007, 0100
patch -R -p1 < <patch>                                                    # reverse in the opposite order
```

## Not included

The signed 1.8.5-test1 APKs are left out on purpose (they do not contain these changes). `release-artifacts/delivery.json` and the pipeline report still describe 1.8.5-test1.
`SHA256SUMS.txt` and `SOURCE-MANIFEST.json` were regenerated for the files in this archive.

## Revision 2 (test fix only)
- `source-changes/1.8.5-test2/0101-fix-csrf-integration-test.patch` corrects the Android integration test
  `aStaleTokenIsRefreshedAndTheRequestIsResentExactlyOnce`. The old version sent `POST /patients` twice with the same key and
  expected one record. That endpoint has no repeat protection, so the test was wrong. The new version sends it once and
  counts records with a unique marker: exactly one must exist after the app refreshes its token and resends.
- No app code changed. The version is still 1.8.5-test2 (versionCode 22).
- **Still UNVERIFIED:** no Android build, unit test, instrumentation test or phone run has happened. The build sandbox cannot reach the Android SDK or Gradle download sites.

## Revision 3 (1.8.5-test3): safe test-only build and test setup
- New `sandbox` build type (package `com.woundcopilot.android.sandbox`, name "WoundCoPilot SANDBOX"). It allows ONLY `http://127.0.0.1:18766/api/v1`. The live service address is refused in code. Debug and release builds are unchanged and still use the live service. The release application ID (`com.woundcopilot.android.release`) is unchanged.
- Phone tests now default to the sandbox build, so `connectedDebugAndroidTest` no longer exists by default.
- `android/tools/local_contract_server.py` now finds the `backend` folder of this project (no `woundcopilot-next` needed) and uses a fresh throw-away database each run.
- New `sandbox/` folder: Windows scripts for three separate test levels (local sign-in with AI off; pretend pipeline; real AI with fake speech only), plus `Go-Offline.ps1` / `Go-Online.ps1`.
- `RUN-ON-YOUR-PC.md` and `SAMSUNG-TEST-GUIDE.md` rewritten. In the sandbox, Airplane mode does not make the app offline (it talks over the USB link), so the guide cuts the USB link instead.
- Version 1.8.5-test3 (versionCode 23).
- **Still UNVERIFIED:** nothing was built or run. The Kotlin, Gradle and PowerShell changes were checked by reading only.

## Revision 4 (1.8.5-test4): full technical review
- Confirmed and fixed: the PDF reader in the backend ran a blocking program on the main server loop (`providers.py`, `analyze_media`). It now runs off the loop (`read_pdf_text` through `asyncio.to_thread`). The guard test now flags ANY direct `subprocess` call inside async code, and was shown to catch the old code.
- New `sandbox` watch build (`com.woundcopilot.android.sandbox`) so the sandbox phone app and a test watch app can pair. The watch app contains no network code.
- Version 1.8.5-test4 (versionCode 24).
- `verification/` holds real test logs and two re-runnable scripts. `REVIEW-AND-TEST-REPORT.md` lists every confirmed fix, remaining issue and what is still untested.
- **Still not built or phone-tested:** Android compile of the full app, instrumentation tests, Samsung run, backend test suite (packages unavailable here).

## Revision 5 (1.8.5-test5): crash-resistant recording prototype (sandbox only), lease plan, corrected PC instructions
- **Prototype (sandbox build only):** an experimental recorder that saves audio in small independent pieces so a killed app keeps the audio recorded so far. Debug and release builds, the release application ID, the interface and the workflows are unchanged. The original recorder is kept as the fallback. Details, test results and the full unverified list: `PROTOTYPE-RESULTS.md`.
- **Stuck AI requests / credit holds:** `STUCK-REQUEST-LEASE-PLAN.md` (plan only; no billing code changed) and (at the time) a SQLite simulation. Both were replaced in Revision 7; the old files are in `verification/prototype/superseded-lease-v1/`.
- **`RUN-ON-YOUR-PC.md` corrected:** the sandbox app is now installed (new Step 5, with a check that it is present) before any manual phone test; the automatic tests remove the app when they finish, so Step 5 is repeated after them. Watch ordering added.
- `SAMSUNG-TEST-GUIDE.md` Part E: hand tests for pieces, pause/resume, lock, watch, kill and recovery. New files: `CrashSafeRecordingTest` (phone test, never run) and `sandbox\Run-KillRecordingTest.ps1`.
- Version 1.8.5-test5 (versionCode 25).
- **Computer-only checks that ran:** 71 pure-logic tests pass (19 + 39 + 13 new), 300 real forced-kill trials with no loss, Kotlin parse check (0 syntax errors; not an Android build), Python static checks, lease simulation.
- **Still not built or phone-tested:** the Android app (including all new recorder code), every instrumentation test, the Samsung run, the backend test suite. Nothing deployed.

## Revision 6 (1.8.5-test6): fixes from your test5 review, encryption design, PostgreSQL lease test
- Fixed: the explicit `INSTANCE` field (a real compile error, reproduced); `LocalRetention` can no longer delete unfinished crash-resistant pieces or the recovery note of a waiting recording (existing 24 h / 7 d limits unchanged); recovery now detects and warns about missing, damaged, skipped and repeated audio; the phone test uses genuine encoded audio; the sandbox recorder stops at 59:50 so it cannot cross the backend's 3,600 s limit.
- New (sandbox only): per-recording key locked in the Android Keystore, every frame encrypted and signed, rebuild in memory with no decrypted temporary file (`AUDIO-ENCRYPTION-DESIGN.md`). The production recorder, upload path, application ID, interface and workflows are unchanged.
- Lease plan: SQL rules tested on a real PostgreSQL 16 server (`verification/run_lease_pg_test.sh`, 26 of 26). No billing change.
- Checks that ran on a PC: 109 logic tests, 5 of 5 deliberate bugs caught, 300 forced kills, 25 real-audio checks (ffmpeg + the backend's own audio functions), 19 + 26 lease checks, Kotlin parse check (not an Android build), Python static checks. Details and the full unverified list: `PROTOTYPE-RESULTS.md`.
- Version 1.8.5-test6 (versionCode 26).
- **Still not built or phone-tested:** the Android app, every instrumentation test, the Samsung run. Nothing deployed.

## Revision 7 (1.8.5-test7): your compile blocker and four decisions (sandbox only)
- **Fixed:** `ExposedBytes` re-declared `size()` of `ByteArrayOutputStream` (a real compile error, reproduced). Removed, with a regression check.
- **Decision 1:** if the key cannot be locked, recording does not start; the spare key copy is wiped.
- **Decision 2:** the finished recording stays encrypted; `recording-X.m4a` is a no-sound placeholder; playback and upload build an `.m4a` in memory (new `Mp4Aac`, `SealedRecording`, `SealedPlaceholder`). Sandbox only.
- **Decision 3:** new sandbox-only `RETAIN_UNRESOLVED`: nothing waiting for recovery, upload or a saved report is deleted by age; 2-day then daily reminders; storage limits (refuse under 200 MB free or 3 GB unresolved, stop under 40 MB); explicit "Discard this recording..." dialog.
- **Decision 4:** new lease rules: charge and saved result in one transaction, never for a lost attempt, never twice (`STUCK-REQUEST-LEASE-PLAN.md`; plan only, no billing code changed).
- Key audit, 59:50 findings, speech test: see `PROTOTYPE-RESULTS.md`.
- PC checks that ran: 148 logic tests, 7 deliberate mistakes caught, 300 forced kills, real full disk, 25 + 21 + 20 audio checks, 46 lease checks, stand-in compile and parse check (not Android builds).
- Version 1.8.5-test7 (versionCode 27). Application ID unchanged.
- **Still not built or phone-tested:** the Android app, every instrumentation test, the Samsung run. Nothing deployed; production, billing and your installed app untouched.

## Revision 8 (1.8.5-test8): your five approved decisions (sandbox only)
- **Decision 1 (drafts):** report drafts and other unfinished-work records never expire by age in the sandbox; reminders and storage limits cover them.
- **Decision 2 (over one hour):** heads-up at 54:00, automatic hand-over at 59:00 to a second recording with its own key; no lost sound (one repeated frame, about 43 ms); parts linked to the same visit; up to 4 parts. New file `VisitContinuation.kt`; small edits in `SafeRecorderService.kt`, `SealedAudio.kt`, `DeviceTools.kt` (a `notice` field) and `CaptureScreen.kt` (one line showing it).
- **Decision 3 (HTTP):** unchanged. Loopback HTTP for synthetic tests only; HTTPS still to be verified before clinical use.
- **Decision 4 (Speko):** one 7-second synthetic test, US$0.0098 total. New fixtures from the computer voice and the Speko voice.
- **Decision 5 (lost AI results):** lease rules v3 (retry limit, idempotency, charge ledger, cost monitoring and alert), 62 of 62 on PostgreSQL 16.15. Plan only; no billing code changed.
- PC checks that ran: 167 logic tests, 9 hand-over checks, 300 forced kills (75 across a hand-over), real full disk, 25 + 21 + 20 + 20 audio checks, 62 lease checks, 10 deliberate-mistake groups caught, stand-in compile and parse check (not Android builds). `PROTOTYPE-RESULTS.md` has the table and the full unverified list.
- Version 1.8.5-test8 (versionCode 28). Application ID unchanged.
- **Still not built or phone-tested:** the Android app, every instrumentation test, the Samsung run. Nothing deployed; production, billing and your installed app untouched.

## Revision 9 (1.8.5-test9): full problem review, first real backend test run
- Read `REVIEW-AND-TEST-REPORT-test9.md` first: every problem on your list, its status, cause, files, tests and remaining risks.
- **Backend test suite ran for the first time: 676 of 676 pass** (654 existing + 22 new). Logs: `verification/backend-pytest-test9.log`, `verification/test9-tests-against-old-code.log`.
- **Server fixes (tested):** report steps that failed or timed out can now be retried with the same request (bounded, never charged); AI-added "reinforced" and stock openers are replaced unless you dictated them; speech-service loops (the same sentence 3+ times in a row) are shortened with a warning; writes to an address the server does not have now give a clear 404 instead of HTTP 405. New checks: every app address exists on the server; photo location/camera details never reach the AI.
- **Phone fixes (type-checked against the real Android 36 API; not built, not phone-tested):** PIN loop around camera/photo picker; a photo that returned while locked is now sent after unlock; saved-data key opened once instead of on every read (speed). New `LockPolicy.kt` + 6 unit tests (173 Kotlin logic tests pass).
- Unchanged on purpose: home-screen lock rule, recorder in debug/release (crash-safe recorder still sandbox-only), billing, database, interface, nursing note format, application IDs.
- Patch against test8: `source-changes/1.8.5-test9/0800-test9-report-retry-wording-speech-loops-pin-loop.patch`.
- New Samsung rows: `SAMSUNG-TEST-GUIDE.md` Part F.
- Version 1.8.5-test9 (versionCode 29). **Still not built or phone-tested. Nothing deployed.**

## Revision 10 (1.8.5-test10): your approved decisions and corrections
- Read `REVIEW-AND-TEST-REPORT-test10.md` first, then `GITHUB-BUILD.md`, then `FINAL-SAMSUNG-CHECKLIST.md`.
- Decisions: crash-safe recorder prepared for release but OFF in debug/release; stuck-credit recovery built behind `AI_LEASES_ENABLED` (off, no schema change); PIN rule unchanged; 3-second overlap (sandbox) with a merge that never drops speech on a guess; microphone-interruption warning; notification permission.
- Corrections: deferred photo waits until the app is ready; report retries = 3 attempts in total; guide E14/E23 fixed; a multi-part visit makes ONE report from all parts; original transcript always kept and clinically meaningful repeats never shortened.
- Evidence: 709 backend tests (incl. real PostgreSQL), 187 Kotlin logic tests, zero type errors in 19 key files against the real Android 36 API.
- New: private GitHub Actions workflow for the real Android build + emulator tests (`.github/workflows/amilya-sandbox-ci.yml`).
- Patch against test9: `source-changes/1.8.5-test10/0900-test10-decisions-and-corrections.patch`.
- Version 1.8.5-test10 (versionCode 30). Application IDs unchanged. **Not built, not phone-tested, nothing deployed.**
