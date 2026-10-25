# Amilya 1.8.5-test9: full problem review and test report

Date: 2026-10-24. Written in plain words. This is a separate candidate. The 1.8.5-test8 package, your installed app, production Cloud Run, the database and billing were **not touched**. Nothing was deployed.

## 1. Bottom line

- **First time the backend test suite actually ran: 676 of 676 tests pass** (654 old + 22 new). Earlier rounds could not install the server's packages. I built the exact pinned versions from their public source code.
- **4 problems fixed and proven on this computer** (server side): report retries that could never succeed, the unwanted word "reinforced", repeated text from the speech service, and confusing HTTP 405 errors. Plus **3 new automatic checks**: the app and server still match, and photos reach the AI without location or camera details.
- **3 problems fixed in the phone app, checked on this computer but NOT yet on a phone**: the PIN loop around the camera/photo picker, a photo that was taken but never sent after unlocking, and slow storage access (a likely part of the slow start).
- **The most important risk is unchanged: in the normal (debug/release) app, a recording is still lost if Android kills the app in the middle of a visit.** The crash-safe recorder exists only in the sandbox build and still needs your Samsung test before it can replace the normal recorder. See section 5, risk 1.
- **No Android build was possible here.** Google's download sites (Android SDK, Google Maven, Gradle) are blocked from this workspace. I got the real Android 36 API file from GitHub and type-checked the app code against it, which is stronger than earlier rounds but still not a build.

## 2. What actually ran on this computer

| Check | Result |
|---|---|
| Backend test suite (pytest, real FastAPI/SQLAlchemy, SQLite, all AI calls intercepted, no cost) | **676 passed, 0 failed** (`verification/backend-pytest-test9.log`) |
| Same suite on the old 1.8.5-test8 code, for comparison | 654 passed |
| New tests run against the OLD code (to prove they detect the old problems) | Report-retry: 4 fail; wording: fails; speech loops: fails; 405: fails. (`verification/test9-tests-against-old-code.log`) |
| Phone-app logic tests (Kotlin, without Android) | **25 + 39 + 109 = 173 pass** (was 167; 6 new PIN-loop tests) (`verification/kotlin-logic-tests-test9.log`) |
| Deliberate-mistake check of the new PIN-loop rule | Putting the old behaviour back makes the new test fail (caught) |
| Type-check of all app Kotlin code against the real Android 36 API | Same error list as test8 (only missing AndroidX/Compose libraries). `Api.kt` and the new `LockPolicy.kt` check fully clean (`verification/android-api-typecheck-test9.log`) |
| Stand-in compile of the sandbox recorder | 0 errors |
| Python static checks (no blocking program inside async server code) | 80 files, 0 syntax errors, no blocking calls |
| Android build (`gradlew assembleDebug/Release/Sandbox`), Android unit tests in Gradle, phone tests, Samsung, watch | **NOT RUN** (blocked here; see section 7) |

## 3. Every problem on your list

Status words: **Fixed + tested here** = changed and proven by automatic tests on this computer. **Fixed, phone test needed** = changed and checked by tests/type-check here, but Android behaviour only shows on the phone. **Earlier fix** = fixed in test2 to test8, re-checked this round. **Not fixed** = needs your decision or the phone. **Not a code problem** = explained.

### 1. Recording and microphone

| Problem | Status | What I found |
|---|---|---|
| Long recordings (25 min) failed | Partly fixed | The limits are fine for 25 min (60 min, 24 MB, 32 pieces of 2 min). A 25-minute report has more text, so a report step is more likely to time out; **a timed-out report could never be retried** (fixed, see 4.1). If the 25-minute failure was the app being killed, that is risk 1. |
| Stopped after about 5 minutes | Not fixed (cause not found) | Nothing in the code stops at 5 minutes. Likely causes: Samsung battery manager closing the app, another app taking the microphone (the recorder keeps a silent timer, risk 4), or a phone call. The Samsung guide (Part F) has a check. |
| Crash or process kill loses the recording | **Not fixed in debug/release** | Crash-safe, encrypted recorder exists in the sandbox build only (test5 to test8; 300 forced kills on a PC, no loss). Needs your Samsung test. |
| Microphone permission asked repeatedly | Not a code problem (most likely) | The app only asks when Android says permission is missing. If you chose **"Only this time"**, Android removes the permission when you leave the app. Choose **"While using the app"** (Settings > Apps > WoundCoPilot > Permissions > Microphone). |
| Recording not saved when offline | Earlier fix | Recording is saved on the phone first; upload waits. Needs phone test (Part F). |
| Extra "Generate Report" step | Earlier fix (confirmed by reading) | After Stop, upload and report start by themselves when the consent box is ticked and the recorder reported no error. |
| Voice commands need "Listen" | Not fixed (needs decision) | Always-on listening is a new feature: it competes with the recorder for the microphone, uses battery, and has privacy effects. Design decision needed. |
| Cutting audio could lose words | Not fixed (needs clinical approval) | Pieces are cut every 2 minutes with no overlap; a word at the cut can be misheard. A warning is shown. Fix (a few seconds of overlap) waits for your approval (review item 8). |

### 2. AI reports and transcription

| Problem | Status | What I found |
|---|---|---|
| Transcription OK but report failed | Earlier fix + **fixed (4.1)** | The server already falls back to a "source-only" draft. New: the AI report can now be retried with the same request. |
| HTTP 500/502, invalid responses, faithfulness-review failures | Partly | 502s are classified and never charged. A 500 needs the server log for its request ID; I cannot see production logs (and did not try). |
| Too slow or stuck "processing" | Partly fixed | Each report step is capped at 60 s. **A failed or timed-out step used to block every retry of the same request forever (fixed, 4.1).** A request stuck because the server itself restarted mid-call is risk 2 (billing approval). |
| Failed requests prevent retries or leave credits reserved | **Retries fixed + tested.** Holds: not fixed | Failed attempts are never charged (tested). A hold left by a server crash mid-call is risk 2. |
| Repeated text | **Fixed + tested (4.3)** | The speech service sometimes loops ("Thank you. Thank you. Thank you..."). |
| Wrong timestamps | Not found | Piece times are added up correctly in code. If you have an example, send it. |
| Inaccurate transcription, unnatural wording, unsupported findings | Earlier work | Source check + one repair + recheck pipeline; tests pass. Wording guide below. |
| "reinforced" keeps appearing | **Fixed + tested (4.2)** | Only a prompt asked the AI to avoid it; nothing enforced it. |
| Duplicate requests | Earlier fix | Request keys prevent paying twice; tests pass. |

### 3. Photo assessment

| Problem | Status | What I found |
|---|---|---|
| HTTP 502, invalid AI responses | Earlier fix | One automatic retry from the original photo; never invents an assessment; tests pass. |
| PIN loop when opening camera/photos | **Fixed, phone test needed (4.5)** | Two causes found. |
| Assessment failed after returning from camera/gallery | **Fixed, phone test needed (4.6)** | A photo that came back while the PIN screen showed was saved but never sent. |
| Photos/progress lost | Earlier fix | Photos are kept on the phone with a "Resume photo uploads" button. |
| Photo metadata, temporary storage, cleanup | **Checked + new test** | The server rebuilds every photo without location, camera, date or other hidden details before the AI sees it (new test proves it with a photo that has GPS). On the phone, photos stay in app-private storage until the existing clean-up. |

### 4. Login, PIN and performance

| Problem | Status | What I found |
|---|---|---|
| Repeated email/password login | Not reproduced | Phone sessions renew automatically. A new email/password login is needed only after a password change, a sign-out, or 90 days without using the app. If it happens more often, note the time and the message. |
| Repeated PIN | **Partly fixed (4.5)** | The camera/biometric loop is fixed. **Unchanged on purpose:** going to the home screen or another app still asks for the PIN on return (your privacy rule). A 1 to 2 minute grace period is possible if you want it (decision 3). |
| 10-second opening delay, freezing, slow screens | **Partly fixed, phone test needed (4.7)** | Every read of saved data reopened the Android key store. Now opened once. Real speed must be measured on the Samsung. |
| Screenshot blocking | Earlier | Debug builds allow screenshots. For a release test build: `gradlew assembleRelease -PwcpAllowScreenCapture=true` (never for clinical use). |
| Sign-out/clean-up during processing | Not fixed | Design delivered earlier (review item 4), awaiting approval. |

### 5. Backend, network and offline

| Problem | Status | What I found |
|---|---|---|
| HTTP 405 | **Cause found + fixed (4.4)** | A 405 means the app called a server address that the **deployed server does not have**. This is strong evidence the deployed server is older than the app. |
| "You do not have permission" after offline start | Earlier fix | Security token is fetched before the first save. New test locks the server wording the app depends on. |
| Deployment may not match source | **Not checked (on purpose)** | I did not contact production. Section 7 shows how to compare. |
| Interrupted uploads / failed AI recover | Partly fixed | Upload pieces resume; AI report retry fixed (4.1). |
| Blocking audio processing, duplicate checks, rate limits, resource use | Earlier fix | Static guard and tests pass. |
| Failed requests must not use credits | Tested | Every failed attempt in the new tests is settled with zero charge. |

### 6. Android, watch and build

| Problem | Status | What I found |
|---|---|---|
| Microphone service start crash | Earlier fix | Needs phone test. |
| Android Studio / Gradle problems | Not checked | Gradle could not run here. |
| Diagnostic logs | Earlier | Developer diagnostics exist. |
| Samsung install/runtime, watch | Not tested | Needs your phone and watch. |
| Old ZIPs / GitHub may be incomplete | Checked this package | All 494 files in test8 matched its checksum list; test9 has a new list. |

## 4. Changes made this round

**4.1 Report retry could never succeed (server, `providers.py`).**
Cause: when a report step failed (AI timeout 504, or 502/503), the server recorded the failure. Any later attempt with the same request key replayed that old failure without asking the AI again. The phone keeps the same key after a timeout, so "Try again" gave the same failure forever (or the source-only draft).
Fix: the same request key may now make a **new, uncharged attempt**, at most 3 extra times per report step. Earlier successful steps are reused, not paid again. Credit, access and routing errors are never retried.
Tests: `test_report_retry_after_failure.py` (new, 5), two old tests in `test_quick_report_repair.py` updated from "never retried" to "retried a limited number of times".
Cost note: an AI attempt that fails still costs us the provider's price (customer pays nothing). This matches your decision 5; the limit is 3.

**4.2 "reinforced" and stock openers (server, `documentation.py`, 2 call sites).**
Cause: only the AI instructions said to avoid them; nothing checked.
Fix: after the AI writes, and before the source check runs: "Education reinforced" becomes "Education provided", "Reinforced education" becomes "Provided education", "Reinforced the importance of" becomes "Discussed the importance of". "On assessment," / "During today's visit," at the start of a sentence are removed. **If you dictated the word, it stays exactly as you said it.** Other uses (for example "dressing reinforced") are never changed.
Tests: `test_avoided_wording.py` (new, 6).

**4.3 Repeated text from speech (server, `providers.py`).**
Fix: only when the same sentence appears **3 or more times in a row**, one copy is kept and a warning says to check that part against the recording. Two repeats, or similar but different sentences, are never touched.
Tests: `test_speech_loops.py` (new, 5).

**4.4 Confusing HTTP 405 (server, `main.py`).**
Cause: a save to an address the server does not have reached the web-page route, which only allows reading, so the answer was "405 Method Not Allowed".
Fix: now a clear 404: "This server does not support this action yet. The app may be newer than the server."
Also new: `test_android_routes.py` reads every server address in the phone app and checks the server has it with the same method (all match in this source). `test_android_contract.py` protects two server messages the app recognises.

**4.5 PIN loop with camera/photos (phone, `ExternalCapture.kt`, new `LockPolicy.kt`).**
Cause 1: the camera or picker had only 60 seconds before the app required the PIN on return; a careful wound photo can take longer. Now 3 minutes. A locked phone still locks the app at once.
Cause 2: if Android closed the app while the camera was open, a leftover "pending camera" note made the app lock **every time it resumed**, including right after a fingerprint unlock (the fingerprint window can pause and resume the app). That is a loop. Now the app locks once for that note, and a leftover note with no photo is forgotten, so it also stops blocking the next camera launch.
Tests: `LockPolicyTest` (new, 6). Phone test: Part F.

**4.6 Photo taken but never sent (phone, `ExternalCapture.kt`, `AppState.kt` one line).**
Cause: a photo that returned while the PIN screen showed was saved, but nothing sent it after unlocking.
Fix: after the PIN/fingerprint unlock, the photo is sent, only for the same clinician and the same visit. Phone test: Part F.

**4.7 Slow storage access (phone, `Api.kt`).**
Cause: every read or write of saved app data (dozens at start-up and on many screens) reopened the Android key store. This is slow on Samsung and runs on the screen thread.
Fix: the key's handle is kept for the life of the app. The key itself never leaves the key store. Bonus: a rare first-launch race that could make two different keys (and lose saved data) is closed.
Check: `Api.kt` type-checks cleanly against the real Android API. Speed must be measured on the phone (Part F).

**4.8 Version** 1.8.5-test9 (versionCode 29), phone and watch. Application IDs unchanged.

Changes 4.5 to 4.7 apply to **all** builds (debug, release and sandbox), not only the sandbox.

## 5. Remaining risks, most important first

1. **HIGH: recording lost if Android kills the app mid-visit (debug/release).** The sandbox crash-safe recorder is the fix; it needs your Samsung kill test (`SAMSUNG-TEST-GUIDE.md` Part E), then your decision to move it into the release app.
2. **HIGH (billing approval): a request held when the server itself crashes or restarts mid-call** stays "processing" and holds credits. Plan v3 (`STUCK-REQUEST-LEASE-PLAN.md`) is ready; billing code not changed.
3. **MEDIUM: the deployed server is probably older than the app** (405 evidence). Compare before more phone testing (section 7).
4. **MEDIUM: microphone taken over silently** (phone call or another app). No warning yet.
5. **MEDIUM: words at the 2-minute cuts.** Needs clinical approval.
6. **MEDIUM: notification permission never requested on Android 13+.** Recording still works; the Stop button in the notification shade may be hidden.
7. **LOW: more uncharged AI attempts.** Up to 3 extra per report step, paid by us.
8. All Android behaviour in this round is untested on a phone.

## 6. Security and privacy checks this round

- Photos to AI: no location, camera model or date (new test). No photo or audio is kept on the server (existing tests).
- App storage: still encrypted with the Android key store; the key never leaves it.
- The new "send photo after unlock" only works for the same signed-in clinician and the same visit.
- Lock rules: home screen and phone lock still lock the app at once. Only the camera/picker window is longer (3 minutes), and only for a camera or picker the app itself opened.
- Sandbox build still refuses the production address (existing test passes).
- No secrets, keys or patient text were added to logs.

## 7. What you need to run on your PC and phone

1. Build: `gradlew :app:testSandboxUnitTest :app:assembleDebug :app:assembleRelease :app:assembleSandbox :wear:assembleSandbox`.
2. Backend tests on your PC: `pip install -r backend\requirements.txt` then, in `backend`, `python -m pytest -q` (expect 676 passed).
3. Check the deployed server version **without changing it**: in Google Cloud Console > Cloud Run > the service > Revisions, compare the image date with this source. Or ask me for a read-only check you run yourself.
4. `SAMSUNG-TEST-GUIDE.md` Part F (new), then Part E (crash-safe recorder).

## 8. Decisions I need from you

1. After Part E passes: move the crash-safe recorder into the release app?
2. Approve building the stuck-request plan (billing).
3. Lock grace period: keep "PIN every time you leave the app", or allow 1 to 2 minutes?
4. 3-second overlap at the 2-minute cuts (clinical approval).
5. Microphone-taken-over warning and the notification permission prompt (both add something visible).
