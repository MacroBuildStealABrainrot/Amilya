# Final Samsung checklist: Amilya 1.8.5-test10 SANDBOX

Fake data only: made-up voice, made-up "patients", no real clinical information. The sandbox app (`com.woundcopilot.android.sandbox`, named "WoundCoPilot SANDBOX") installs **beside** your real app and can only talk to the test server on your PC. Your installed release app, Cloud Run, clinical data and billing are not touched by anything here.

## 0. Before you start

| # | Do | Pass if |
|---|---|---|
| 0.1 | Build first: either the GitHub workflow (`GITHUB-BUILD.md`) or on your PC (`RUN-ON-YOUR-PC.md`) | All three jobs are green (backend, Android build, emulator phone tests), or you have a list of what failed. **Do not start the phone test if the Android build failed.** |
| 0.2 | In the build evidence, open `build-flags.log` | Ends with `ALL BUILD-FLAG CHECKS PASSED` (release and debug: crash-resistant recorder OFF; sandbox: ON) |
| 0.3 | Install: `adb install -r app-sandbox.apk`; check `adb shell pm list packages com.woundcopilot.android` | You see `.sandbox` **and** your existing release app, unchanged |
| 0.4 | Phone settings: plugged in for long tests; Do Not Disturb off; a second phone available to call this one (for section E) | |
| 0.5 | Start the PC server for the section (Level 1: `sandbox\Run-Level1-LocalAuth.ps1`; Level 3 real AI with fake voice: `sandbox\Run-Level3-RealAI-SyntheticOnly.ps1`) | Phone shows "Phone linked" message on the PC |

For every row write down: time, pass/fail, and the exact words of any message. Screenshots of fake data are fine.

## A. Safety of the test setup

| # | Do | Pass if |
|---|---|---|
| A1 | Stop the PC server, Wi-Fi on, open the sandbox app, try to sign in | It cannot connect (it never reaches the live service) |
| A2 | Open your **release** app | It opens normally with your usual data; version unchanged |

## B. A recording is never lost (most important)

| # | Do | Pass if |
|---|---|---|
| B1 | Record 12 min, Stop | Saved, plays, no "may be missing" warning (guide E1) |
| B2 | Record 10 min; at ~7 min run `sandbox\Run-KillRecordingTest.ps1`; reopen | Listed as interrupted, plays, length within ~10 s of the kill, uploads (E6, E9) |
| B3 | Record 5 min, lock the phone, kill as in B2, unlock, reopen | Same as B2 (E22) |
| B4 | Settings > Battery > restrict the sandbox app; record 15 min, screen off | Either keeps recording, or everything up to the stop is listed on reopening |
| B5 | Record 2 min, Stop, **restart the phone**, play it | Plays (E15) |
| B6 | Go offline (`sandbox\Go-Offline.ps1`), record 3 min, Stop | Saved on the phone; clear "needs a connection" message; nothing deleted |
| B7 | Go online (`sandbox\Go-Online.ps1`), tap Try again | Upload and report complete; the recording stays listed until the report is saved |
| B8 | Fill storage below 200 MB, tap Record | Refused with a clear space message; earlier recordings kept (E20) |

## C. Long visit: 61 to 90 minutes, ONE report (new)

| # | Do | Pass if |
|---|---|---|
| C1 | Record a 90-minute fake visit (music or a counting voice is fine; phone plugged in) | At 54:00 a calm heads-up; at 59:00 it **continues by itself** as part 2 (no stop, no button). It does **not** stop at 59:50 |
| C2 | Stop at ~90:00 | The screen says the visit was recorded in 2 parts |
| C3 | Let it upload (Level 3) | Progress shows "Part 1 of 2", then "Part 2 of 2" |
| C4 | Read the report | **One** report containing things said in the first hour **and** in the last 30 minutes, in order |
| C5 | Play the end of part 1 and start of part 2 | No gap and no missing words (a ~0.04 s repeat is normal) |
| C6 | Kill the app during part 2 (B2 method), reopen, upload | Part 1 complete; part 2 rebuilt; still one report with both |

## D. 3-second overlap at the 2-minute cuts (new)

Read this fake script with a stopwatch so the bold lines are spoken **across** 2:00, 4:00 and 6:00 (start each about 3 seconds before the minute mark):

* 1:57: "**Give metformin five hundred milligrams twice daily with meals.**"
* 3:57: "**Client denies chest pain. No, no, no fever today.**"
* 5:57: "**Wound measures two point five by three centimetres, left heel.**"

| # | Do | Pass if |
|---|---|---|
| D1 | Record 7 minutes with that script, upload (Level 3), open the transcript | Each bold line is present **in full**. Doses, "denies", the number of "no", "two point five", "left" are all correct |
| D2 | Read the review notes on the transcript | At each join either "...shown once" or "...both were kept". If both were kept, a phrase may appear twice. **A missing word is a FAIL; a doubled word with a note is a pass** |
| D3 | Look at the report | Same facts as the transcript; nothing from the bold lines is lost |

## E. Microphone taken by a call (new)

| # | Do | Pass if |
|---|---|---|
| E1 | Record; at ~1 min call this phone from another phone; answer, talk 30 s, hang up; keep recording to 3 min | During the call the screen says the microphone is being used by a call. After it: "The microphone was taken ... once (first at 1:0x, about 30 s ...)" |
| E2 | Let the report finish | The same message is shown again with the report |

## F. Notification permission (Android 13+, new)

| # | Do | Pass if |
|---|---|---|
| F1 | Fresh install of the sandbox app; first recording | Android asks for microphone **and** notifications together |
| F2 | Deny notifications; record | Recording still works; the screen says Stop is on this screen |
| F3 | Next recording | Not asked about notifications again |

## G. PIN, camera and photos

| # | Do | Pass if |
|---|---|---|
| G1 | Photo > Take photograph, stay ~2 min, take it | No PIN; assessment starts (guide F2) |
| G2 | Same but more than 3 min | PIN once; after unlock the assessment starts by itself (F3) |
| G3 | Take a photo while a report is still being prepared | The photo is sent automatically as soon as the report finishes (new) |
| G4 | Camera open, run `adb shell am kill com.woundcopilot.android.sandbox`, Back, unlock with fingerprint | One unlock only; camera can be opened again (F4) |
| G5 | Press Home in a visit, come back after 5 s | PIN asked (your rule, unchanged) |

## H. Reports

| # | Do | Pass if |
|---|---|---|
| H1 | Start a report, go offline during "Preparing and checking", go online, Try again | A checked AI report appears (guide F7) |
| H2 | Dictate "Education given on leg elevation." | The report does not add "reinforced". Dictate "I reinforced education": the word is kept (F8) |
| H3 | Dictate "Thank you. Thank you. Thank you. Give five milligrams. Give five milligrams. Give five milligrams." | Transcript keeps **all three** dose sentences (with a review note); the "Thank you" repeats may be shortened |

## I. Watch

| # | Do | Pass if |
|---|---|---|
| I1 | Start, pause, resume, stop from the watch (guide E5) | Phone follows each command once; file saved |

## J. Existing work survives the update (new)

| # | Do | Pass if |
|---|---|---|
| J1 | With the **previous** sandbox build (test8 or test9) installed, record 2 min and do NOT upload. Install test10 over it (`adb install -r`) | The recording is still listed, plays, uploads, and gives a report |
| J2 | Leave a report draft unfinished in the previous sandbox build, update to test10 | Draft still there |

## K. Speed

| # | Do | Pass if |
|---|---|---|
| K1 | Cold start (swipe the app away, open) 3 times; time to the PIN screen | Write the times; compare with test8/test9 (guide F1) |

## Decide

* **All pass:** send me the results. Only then, and only with your written approval, the next step is a release build with the crash-resistant recorder switched on (`-PwcpCrashSafeRecording=true`), tested again before it replaces your app.
* **Any fail:** keep using your current app. Send the row number, the exact message, a fake-data screenshot and `adb logcat -d`. Remove the test app with `adb uninstall com.woundcopilot.android.sandbox`.
