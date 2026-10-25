# Test the Amilya 1.8.5-test8 candidate on your Windows PC and Samsung phone

Plain steps. Nothing here changes your installed app, your billing, or the live service. Use made-up (fake) data only.

**Status: this candidate has NOT been built or tested on Android by me.** I had no Android tools or internet access to them. Every Android result comes from you running these steps.

**Order that matters (corrected in this version):** build (Step 1) -> automatic phone tests (Steps 3 and 4) -> **install the sandbox app on the phone (Step 5)** -> only then any manual phone testing (Steps 6 to 9). The automatic tests install their own copy of the app and **remove it again when they finish**, so a hand test straight after them would find no app on the phone. Always do Step 5 first.

## What is new in this version (in plain words)

- There is now a separate **sandbox** test app. It is named **WoundCoPilot SANDBOX** and its package is `com.woundcopilot.android.sandbox`.
  - It can talk **only** to a test server on your own PC. The live service address is refused in the app's code, so a recording can never go to the live service by accident.
  - It installs **next to** your other apps. It does not replace your release app, and it does not touch your normal debug app. Your release app id (`com.woundcopilot.android.release`) is unchanged.
- Your normal debug and release builds still work as before and still use the live service. **Do not use the normal debug app for these tests.**
- The test server setup is fixed. The old note about a `woundcopilot-next` folder was wrong for this project. The server code is in the `backend` folder of this ZIP, and the scripts now find it by themselves. You do not need `woundcopilot-next`.
- Tests are split into three levels, so you always know what a pass really proves.

## The three levels

| Level | What runs on your PC | AI used? | Costs money? | What it can prove | What it cannot prove |
|---|---|---|---|---|---|
| 1 Local sign-in | The real server code, empty throw-away database, AI **off** | No | No | Sign-in, security token (CSRF) fix, saving | Transcription, reports |
| 2 Pretend pipeline | A pretend server that gives fixed fake answers (and can fail on purpose) | No | No | The app's retry, upload and "recording kept" handling in automatic tests | That the real server or AI works. It cannot be used for signing in by hand |
| 3 Real AI, fake data | The real server, with **your** AI keys, fake speech only | Yes | Maybe small charges | Real transcription and report writing | Anything about real patients (never use them) |

Level 3 sends fake speech to outside AI companies, using keys that you put in a file on your PC. Never type those keys into a chat, an email or the app.

## Before you start (one time)

1. Install **Android Studio** (it includes the Android SDK). Open it once, then in *Settings > Languages & Frameworks > Android SDK* install **Android 16 (API 36)** and Build-Tools 36.
2. Install **Python 3.11 or newer** from python.org. Tick **Add python.exe to PATH**.
3. Install ffmpeg: open PowerShell and run `winget install Gyan.FFmpeg`, then close and reopen PowerShell. (Needed for audio in Level 3.)
4. Make `adb` work in PowerShell: add `C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools` to your PATH. Test with `adb version`.
5. Unzip the candidate ZIP into a **new empty folder**, for example `C:\amilya-test3`. Do not unzip over any older folder.
6. Samsung phone: *Settings > About phone > Software information*, tap **Build number** 7 times. Then *Settings > Developer options*: turn on **USB debugging**. If *Auto Blocker* is on (Settings > Security and privacy), turn it off while installing, and back on afterward. Plug in by USB and tap **Allow** on the phone. Check: `adb devices` shows the phone as `device`.

## Step 1: Build and check (no phone needed)

Open PowerShell in `C:\amilya-test3\android`. If Gradle complains about Java, run this first (adjust the path if Android Studio is elsewhere):

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

Run each line and save everything it prints into a text file (add `*>&1 | Tee-Object step1.txt`):

```
.\gradlew.bat :app:testSandboxUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:assembleSandbox
```

- `assembleRelease` makes an **unsigned** app. That is fine. It only proves it compiles. Do not install it. If it asks for signing keys, stop and tell me; never send me keys.
- The unit test run includes `ApiOriginGuardTest`. It proves in code that the sandbox build refuses the live service.
- Test APK you will install: `app\build\outputs\apk\sandbox\app-sandbox.apk`.

## Step 2: Set up the test server (one time)

Open PowerShell in `C:\amilya-test3\sandbox`. If scripts are blocked, run once: `Set-ExecutionPolicy -Scope Process Bypass`.

```
.\Setup-Sandbox.ps1
```

## Step 3: Level 1 tests (real server code, AI off)

1. In the sandbox folder, start the server and **leave that window open**:
   ```
   .\Run-Level1-LocalAuth.ps1
   ```
   It links the phone by USB (`adb reverse`) and starts the fake-data server on port 18766.
2. Open a **second** PowerShell in `C:\amilya-test3\android`, then:
   ```
   .\gradlew.bat :app:connectedSandboxAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.woundcopilot.android.BackendContractTest,com.woundcopilot.android.CsrfAfterOfflineStartTest,com.woundcopilot.android.RecordingServiceStartTest"
   ```
   The phone runs the tests on the **sandbox** app only. Your release app is not touched. (Phone tests are set up to use the sandbox build by default, so the normal debug app cannot be tested by accident.)
3. Save the report folder: `android\app\build\reports\androidTests\connected`.
4. Press Ctrl+C in the server window when done.

## Step 4: Level 2 tests (pretend server, automatic only)

1. Start the pretend server (leave the window open): `.\Run-Level2-SimulatedPipeline.ps1` (in `sandbox`).
2. In the second window:
   ```
   .\gradlew.bat :app:connectedSandboxAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.woundcopilot.android.ReliabilityPassTest,com.woundcopilot.android.RecordingWorkflowTest,com.woundcopilot.android.PhotoRecoveryTest"
   ```
3. Save the report. Stop the server with Ctrl+C.

Note: these three test files were written to use the pretend server. If one of them fails to start because it needs something else, send me the report and I will sort it out. I have not been able to run them.

## Step 5: Install the sandbox app on the Samsung (do this BEFORE any hand test)

In PowerShell, in the `C:\amilya-test3\android` folder, with the phone plugged in and `adb devices` showing it as `device`:

```
adb install -r app\build\outputs\apk\sandbox\app-sandbox.apk
adb shell pm list packages com.woundcopilot.android.sandbox
```

The second line must print `package:com.woundcopilot.android.sandbox`. If it prints nothing, the app is not installed: stop and fix that first (re-run the first line; if it fails, send me the message). You will see an app called **WoundCoPilot SANDBOX**. Your release app is untouched. Repeat this step any time you ran Step 3 or 4 again, because they remove the app when they finish.

Then start the matching test server for the test you are about to do (Level 1: `.\Run-Level1-LocalAuth.ps1`; Level 3: Step 7), **before** opening the app.

To remove the sandbox app later: `adb uninstall com.woundcopilot.android.sandbox`.

## Step 6: Samsung hand test, Level 1 first (still no AI)

Needs Step 5 done. Follow part A of `SAMSUNG-TEST-GUIDE.md`. It proves the offline-start sign-in fix on a real phone without using AI.

## Step 7: Samsung hand test, Level 3 (real transcription and report)

Needs Step 5 done. Only do this if you are happy to send **fake speech** to your AI providers.

1. In `sandbox`, copy `level3.env.example` to `level3.env`. Open it in Notepad and type your own keys. Save. Do not share this file.
2. Create one local test account that has unlimited credits **on your PC only** (billing stays off): `.\Level3-CreateTestOwner.ps1`. Use an email like `tester@example.invalid` and a made-up password of 16 or more characters.
3. Start the server: `.\Run-Level3-RealAI-SyntheticOnly.ps1`. Type `SYNTHETIC ONLY` when asked. Leave the window open.
4. Follow part B of `SAMSUNG-TEST-GUIDE.md`.

## Optional: phone and Galaxy Watch together (sandbox)

Order: (1) phone sandbox app installed (Step 5) -> (2) watch sandbox app installed (below) -> (3) open the phone app and sign in -> (4) test the watch. Installing the watch app first, or testing the watch before the phone app is installed, gives a false failure.

The watch and the phone only talk to each other when both apps have the **same package name and signature**. The sandbox phone app is `com.woundcopilot.android.sandbox`, so it needs the matching **sandbox watch app**. A normal debug watch app will not connect to it.

1. Build the watch test app: `.\gradlew.bat :wear:assembleSandbox` (in the `android` folder). It is at `wear\build\outputs\apk\sandbox\wear-sandbox.apk`.
2. Turn on developer options and **ADB/Wi-Fi debugging** on the watch, connect it with `adb connect <watch-ip>:<port>`, then `adb -s <watch-ip>:<port> install -r wear\build\outputs\apk\sandbox\wear-sandbox.apk`.
3. The watch app has no internet code of its own. It only talks to the phone app.
4. The watch can start recording only while the phone app is open and unlocked. This is existing behavior and was not changed.

> 1.8.5-test10: the crash-resistant recorder files now live in `app/src/main` (switched ON only in the sandbox build). Easiest full run: `GITHUB-BUILD.md`.

## Step 8: Crash-resistant, encrypted recording prototype (sandbox only; the finished recording stays encrypted, the .m4a is a no-sound placeholder)

The sandbox app records with an **experimental crash-resistant recorder**. It saves audio as small independent pieces, each frame **encrypted** with a key locked in the Android Keystore (design: `AUDIO-ENCRYPTION-DESIGN.md`), so if Android kills the app the audio saved so far can be rebuilt. It also checks the pieces for missing or damaged audio and warns the clinician. **Debug and release builds still use the original recorder.** The original recorder is the fallback and was not replaced.

Needs Step 5 done. Visits over one hour continue automatically into a second recording (heads-up at 54:00, hand-over at 59:00); see rows E24 to E29 of the guide. Then follow **part E of `SAMSUNG-TEST-GUIDE.md`** (recording 30+ minutes, pause/resume, locking the phone, the watch, the kill test, the encryption and warning checks).

Automatic phone test for the prototype (needs the phone plugged in; it installs and then removes the app, so redo Step 5 afterwards). It builds a left-behind session from **genuine encoded audio** (`app\src\androidTestSandbox\assets\fixtures`), rebuilds it with the phone's own tools, decodes it with the phone's own decoder and checks the length and the tone at several moments:

```
.\gradlew.bat :app:connectedSandboxAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.woundcopilot.android.CrashSafeRecordingTest,com.woundcopilot.android.ExistingWorkCompatibilityTest"
```

Kill test helper (installs nothing; run after Step 5 while the app is recording a made-up voice): `.\sandbox\Run-KillRecordingTest.ps1`. It force-stops the app mid-recording, reopens it, and tells you what to write down.

## Extra checks I ran without Android (you can re-run them)

`verification\` holds the logs of what I could really run in my workspace, plus two scripts you can run yourself:

- `sh verification/run_real_audio_check.sh <gradle-folder>` (needs Java, ffmpeg and Python with numpy): passes genuine encoder audio through the recorder's storage and recovery code and checks it with real audio tools and the backend's own duration/format functions.
- `sh verification/run_lease_pg_test.sh` (needs the PostgreSQL 16 server programs and a Linux shell with root) or, with Docker, the command written inside that script: tests the stuck-request lease rules on a real PostgreSQL server.
- `sh verification/run_kill_experiment.sh <gradle-folder> 300`: real forced kills (also rebuilds the in-memory .m4a after each encrypted kill).
- `sh verification/run_storage_full_check.sh <gradle-folder>` (Linux, root): fills a real 1 MB RAM disk during a recording.
- `sh verification/run_sealed_upload_check.sh <gradle-folder>` (set `FIXTURE=<aac>` to use the speech fixture): in-memory .m4a and upload parts checked with ffprobe/ffmpeg and the backend's functions.
- `sh verification/run_handoff_check.sh <gradle-folder>` (needs Java, ffmpeg, Python with numpy): the over-one-hour hand-over with real AAC; checks no sound is lost and every part is accepted by the backend's functions (9 checks).
- `sh verification/run_standin_compile.sh <gradle-folder>` and `sh verification/run_kotlin_syntax_check.sh <gradle-folder>`: NOT Android builds.
- `python verification\python_static_checks.py` (needs only Python): checks every Python file and that no server code freezes other requests.
- `sh verification/run_kotlin_logic_tests.sh <gradle-folder>`: runs the pure-logic tests (167 including the hand-over and draft-retention tests, the recording-piece, encryption, key-handling, in-memory rebuild, retention and real-audio-fixture tests) (needs a Gradle install and a shell such as Git Bash). Optional.

These do **not** replace the real Android build and phone tests in Steps 1 to 8.

## What to send me

- `step1.txt` (build and unit test output)
- The report folders (Level 1, Level 2 and the crash-resistant recording test), or a screenshot of the pass/fail summary
- The pass/fail table from the Samsung guide, with failing row numbers
- For a failure: the phone's `adb logcat -d` output with no patient data

Never send me keys, passwords, or real patient information.

## Rollback

Nothing here changed anything outside the folder you unzipped. To undo everything: `adb uninstall com.woundcopilot.android.sandbox`, close the server windows, and delete the folder `C:\amilya-test3`. Your release app, your normal debug app, the live service and billing were never touched.
