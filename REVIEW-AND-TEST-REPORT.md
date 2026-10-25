# Amilya 1.8.5-test4 technical review and test report (test5 additions are in PROTOTYPE-RESULTS.md and STUCK-REQUEST-LEASE-PLAN.md)

Written in plain words. Date: 2026-10-24. Nothing was deployed. Billing, data-retention rules, production and your installed app were not touched.

## 1. Bottom line

- The candidate is **better, but still not proven**. I could not compile the full Android app, run the phone tests, run the backend test suite, or try it on the Samsung. My workspace cannot reach the Android SDK or the Python package sites.
- I **did run for real**: 58 pure-logic tests (all pass), a Kotlin syntax check of all 71 Kotlin files (0 syntax errors), a Python check of all 75 Python files (0 errors), and a behavior check of the new backend fix.
- I found and fixed **1 new confirmed bug** and added **1 test-only build** (watch). I found **2 serious problems that I did not change because they need your approval**, plus others listed in section 4.
- The biggest risk to your goal ("never lose audio on a long visit") is that an **unfinished recording cannot be played back if the app process dies mid-visit**. See R1.

## 2. Actual test results

| Check | How | Result |
|---|---|---|
| CSRF bootstrap rules, CSRF recovery and retry rules, error message, sandbox "never production" address guard (3 test files) | Compiled with the Kotlin compiler bundled in Gradle 8.14.3 and run with JUnit. Logic copied from the real source files | **19 of 19 pass** |
| Watch/phone protocol tests (`wear-contract`) | Same method | **39 of 39 pass** |
| Kotlin syntax, all 71 files | Same compiler. Self-tested: it does report a deliberately broken file | **0 syntax errors.** Many "missing Android library" messages are expected and ignored |
| Python syntax, all 75 files | Python parser | **0 errors** |
| "No server code freezes other requests" guard | Reads the code structure | **Old code flagged (PDF reader in `analyze_media`), new code clean** |
| New PDF reader wrapper | Real function with a stand-in PDF worker | **Pass**: the server loop kept running while the worker slept (50 ticks) |
| Full Android build (debug, release, sandbox, watch) | Not possible here | **NOT RUN** |
| Android unit tests (the other test files) and phone instrumentation tests | Not possible here | **NOT RUN** |
| Backend test suite (pytest) | FastAPI, SQLAlchemy and pytest cannot be installed here | **NOT RUN** (an earlier independent review of yours reported 124 passing targeted tests on test2; I could not repeat them) |
| Frontend tests | npm packages cannot be installed here | **NOT RUN** |
| Samsung run, Android 16 behavior, watch pairing, real AI | Needs your phone and PC | **NOT RUN** |

Logs are in `verification/`. Two scripts there can be re-run on your PC.

## 3. Confirmed fixes

This round (test4):
1. **Backend PDF reading froze the server.** In `backend/server/providers.py`, `analyze_media` ran a program (the PDF reader) directly on the main server loop, for up to 15 seconds. During that time every other user's request, including audio uploads, waited. Now it runs off the loop (`read_pdf_text` with `asyncio.to_thread`). Same kind of problem as review item 7.
2. **The guard test is stronger.** It now catches any direct `subprocess` call inside async server code, not only the two audio helpers. It was proven to catch the old bug.
3. **Watch test build.** New `sandbox` watch build (`com.woundcopilot.android.sandbox`) so the sandbox phone app and a test watch app can talk to each other. Before, only debug-with-debug or release-with-release could pair, so the sandbox phone had no matching watch. The watch app has no internet code, so it cannot reach production.

Already done in earlier candidates (re-checked by reading this round, not by running them on a phone):
- Offline start: the security token is now fetched before the first write, and a rejected token is refreshed and the same request is resent once with the same repeat-protection key (so nothing runs twice). The rules are covered by the 19 passing tests. The real network behavior is untested.
- Recording service start: the "foreground" notification is now raised first, with a permission check before it. Untested on a phone.
- Backend audio checks moved off the main loop (audio length probe and clip cutting). Covered now by the stronger guard.
- Sandbox build cannot reach production (see section 6).
- Integration test corrected (no longer assumes `/patients` can be repeated).

## 4. Remaining issues (not changed), most important first

**R1. HIGH. A recording is lost if the app process dies mid-visit.**
The phone's recorder writes the file's "table of contents" only when you press Stop. If the app crashes, is killed by the battery manager, or is terminated any other way during a 40-minute visit, the file cannot be played, and the app says "cannot be uploaded as playable audio". The raw sound is probably still inside the file but this app cannot read it.
*Safe fix (needs your approval and a Samsung trial):* record in rolling pieces (a new finished file every few minutes using the recorder's "next output file" feature), or record in a crash-safe format (AAC stream without a table of contents). Both change how recordings are stored, listed and uploaded, so I did not do it blind. Plan: add it behind a switch that is ON only in the sandbox build first, test a deliberate "kill the app mid-recording" on the Samsung, then decide.

**R2. HIGH. A stuck AI request can block a recording from ever finishing (billing-related, needs approval).**
If the server is restarted or crashes while waiting for the AI (for example during a Cloud Run restart), the saved "money held for this request" never gets released. Any retry of the same part then gets "already processing or awaiting reconciliation" (409) forever. The same stuck request also stops that visit from being closed or deleted. Nothing in the maintenance job cleans these up. Your recording stays safe on the phone, but the report cannot be finished from that recording without a manual fix.
*Safe fix (needs your approval because it touches billing):* a cleaner that only acts on requests older than about 10 minutes that have no saved result, releases the held amount (never charges), and lets the app start a fresh attempt. It would never settle or charge on its own. (This is review item 1.)

**R3. MEDIUM. Words at the 2-minute cuts can be wrong.** The app cuts long audio into 120-second pieces with no overlap, so a dose, number or "no" at a cut can be misheard. Planned fix: a few seconds of overlap plus a flag on boundary words. Needs your clinical approval. (Review item 8.)

**R4. MEDIUM. No warning if the microphone is taken over.** On Android 10 and later, a phone call or another app can silence a recording without the recorder reporting an error. The app would keep a "recording" timer on silence. A small warning when Android silences the app would fix this. It adds an on-screen message, so I need your OK.

**R5. MEDIUM. Notifications are never requested on Android 13 and later.** If notifications are off, the recording notification (with its Stop button) is hidden from the shade. Recording still works. A one-time permission prompt would fix it; it is a visible change, so I need your OK.

**R6. Pending design items awaiting your approval:** sign-out without losing work (4), expiry of temporary clinical data (5, a retention rule), trusted-proxy and login rate limiting (6). Designs were delivered earlier.

**R7. LOW. Database connections are held while waiting for the AI.** The audio-part endpoint keeps a database connection open during the AI call. Today it fits: the pool allows 10 connections and Cloud Run is set to 8 requests at a time. If someone raises that setting, this will start failing. No change needed now.

**R8. LOW. Cloud Run is set to a maximum of 1 instance and may scale to zero.** A cold start can add delay at the start of a visit, and one instance is one point of failure. I did not change deployment settings.

## 5. Review notes by area

- **Recording loss:** besides R1, the recorder stops cleanly on limits (24 MB, 60 minutes at 32 kbps leaves room), writes status every second, and the original file is kept until the report is saved. Deleting the original happens only after a confirmed save, on explicit discard, or by the 24 hour / 7 day cleanup you already have. I did not change that rule.
- **Duplicate AI charges:** the reserve, settle and release steps are repeat-safe (same key gives the same answer; a released request cannot be charged; a settled request cannot change). Each audio part uses a fixed key, so retries do not pay twice. I found no double-charge path by reading the code. The related risk is R2 (money held, not double-charged).
- **Incomplete reports:** if the AI stops early (output cut off) the backend rejects it instead of saving a partial report. Transcript assembly refuses to continue if any audio piece is missing, so a report is never made from a partial recording.
- **Security and privacy:** no passwords, keys or patient text in app logs or server logs (only request IDs, routes and status). Backups and device transfer are blocked. The file-sharing area covers only exports and photos, not recordings. Voice commands use only the phone's on-device recognizer. The app has a single network exit point, and it is guarded.
- **Performance:** nothing alarming by reading. The recording timer redraws 10 times per second and the app writes a small status file each second, which is fine. I did not measure speed on a phone.
- **Android 16 compatibility:** back handling uses the modern system (works with the new back gesture). Foreground service types and permissions are declared for Android 14 and later, including the 6-hour limit handler for the processing service. I could not run it on Android 16.
- **Gradle:** the Android Gradle plugin 9.3.2 and Gradle 9.5.0 match each other, and the 1.8.5-test1 source was built with them before. My additions (sandbox build types, watch sandbox, default test build) use standard settings, but I could not run Gradle, so they are **unverified**.
- **Phone and watch together:** the watch commands (start, stop, cancel, retry, open) use a replay ledger and clear error codes. The protocol tests pass (39). The watch can only start recording while the phone app is open and unlocked (unchanged). Release phone with release watch and debug with debug still pair as before. Sandbox needs sandbox on both.

## 6. Sandbox cannot reach production

- Only one place in the whole app opens network connections (`Api.kt`, one `openConnection` line). There is no web view and no other web client.
- In the sandbox build the only allowed address is `http://127.0.0.1:18766/api/v1` (or `localhost`). The live address is refused in code, and a unit test checks it (passes).
- The watch app has no internet permission and no web code.
- Phone tests now use the sandbox build by default, so a normal debug app cannot be tested by accident.

## 7. What remains untested (please run on your PC)

1. `gradlew :app:testSandboxUnitTest :app:assembleDebug :app:assembleRelease :app:assembleSandbox :wear:assembleSandbox`
2. Level 1 and Level 2 phone tests, then the Samsung test guide (Part A, then Part B with real AI and fake speech only).
3. A deliberate "kill the app during a recording" test on the Samsung, to see R1 for yourself (it is the first thing I would try).
4. Anything involving Android 16 behavior.
