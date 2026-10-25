# Samsung hand test: Amilya 1.8.5-test10 SANDBOX (fake data only)

**Use `FINAL-SAMSUNG-CHECKLIST.md` for the actual test run.** This guide keeps the detailed rows it refers to.

**Status: not yet run by anyone.** Use a made-up account and fake (synthetic) speech only. Never real patients.
**Install the sandbox app first (`RUN-ON-YOUR-PC.md` Step 5) and confirm it appears on the phone before any row below.** Install steps and server steps are in `RUN-ON-YOUR-PC.md`. This guide is the phone part.

**How "offline" works in this test.** The sandbox app talks to your PC through the USB cable. Airplane mode does **not** cut that link, so do not use it here. Instead:
- Go offline: run `sandbox\Go-Offline.ps1` (or `adb reverse --remove tcp:18766`).
- Go back online: run `sandbox\Go-Online.ps1` (or `adb reverse tcp:18766 tcp:18766`).

This proves how the app behaves when the server cannot be reached. It does not test Wi-Fi or mobile data themselves. That would need a separate test server on the internet, which needs your approval first.

**Preservation check** (before and after every stage). The sandbox app's package is `com.woundcopilot.android.sandbox`:

```
adb shell run-as com.woundcopilot.android.sandbox ls -l files/recordings
adb shell run-as com.woundcopilot.android.sandbox sha256sum files/recordings/<file>
adb logcat -b crash -d
```

Prepare a fake 30 to 45 minute visit (text-to-speech or you reading a script), played from a second device. Plant these every few minutes: "no known drug allergies", "warfarin 5 mg daily", "wound 3 by 2 centimetres", "not 15, 50". Words right at the 2:00, 4:00 and 6:00 marks may be wrong (known weakness, review item 8). Write those down but do not count them as failures.

## Part A: Level 1 server (real server code, AI OFF)

Server: `sandbox\Run-Level1-LocalAuth.ps1` running. Phone: sandbox app installed.

| # | Do | Pass if |
|---|---|---|
| A1 | Open WoundCoPilot SANDBOX. Create a fake account (`a1@example.invalid`, made-up password). Set a PIN | Sign-up works |
| A2 | Force stop the app (Settings > Apps > WoundCoPilot SANDBOX > Force stop). Run `Go-Offline.ps1`. Open the app, enter the PIN | App opens. No crash |
| A3 | Still offline: start a 1-minute recording, then Stop | Message says it could not connect **and** that the recording is saved on the device. A **Try Again** button shows. **No** message about permission or request verification |
| A4 | Run the preservation check. Write down the file name, size and sha256 | File exists. `logcat -b crash` is empty |
| A5 | Run `Go-Online.ps1`. Wait 10 seconds. Tap **Try Again** | The server answers. Because AI is off in Level 1, you should get a clear "AI is paused / not configured" style message. You must **not** get "You do not have permission" or "request verification". That proves the security-token fix worked after an offline start |
| A6 | Run the preservation check again | Same file, size and **same sha256** as A4 |

## Part B: Level 3 server (real AI, fake speech)

Server: `sandbox\Run-Level3-RealAI-SyntheticOnly.ps1` running. Phone: sign in with the test owner account you made with `Level3-CreateTestOwner.ps1`. Accept the terms when asked.

| # | Do | Pass if |
|---|---|---|
| B1 | Force stop the app. Run `Go-Offline.ps1`. Open the app, enter the PIN | App opens, no crash |
| B2 | Document mode, give consent, start recording, play the 30 to 45 minute fake visit | Timer runs, notification shows |
| B3 | During recording: lock the screen 2 minutes, then use another app 2 minutes | Timer and notification continue |
| B4 | Tap Stop while still offline | "Could not connect" and "recording saved on device". Try Again shows. No permission or verification message |
| B5 | Preservation check. Write down name, size, sha256 | File exists, size matches the visit length, crash log empty |
| B6 | Run `Go-Online.ps1`, wait 10 seconds, tap **Try Again** | Progress moves through the audio parts |
| B7 | Wait for the transcript and the report | A report appears with the planted doses, allergy and measurement (apart from the known break items) |
| B8 | Save the report. Reopen it from the saved list | Saved text matches what was shown |
| B9 | Preservation check | Same file, size and **same sha256** as B5 |

## Part C: Failure checks (recording must survive each one), Level 3 server

| # | Do | Pass if |
|---|---|---|
| C1 | New 20-minute fake recording. Tap Stop. While it shows progress, run `Go-Offline.ps1` | Error with Try Again and "recording is saved on this device". sha256 unchanged |
| C2 | Run `Go-Online.ps1`. Tap Try Again | Finishes and the report appears. Recording unchanged |
| C3 | New recording, Stop, then swipe the app away during transcription. Reopen, enter PIN | The interrupted recording is listed as recoverable. Resume completes the report. sha256 unchanged |
| C4 | Optional: record 3 minutes, restart the phone from the power menu, reopen | Recording is listed and plays, length within a few seconds |
| C5 | Settings > Apps > WoundCoPilot SANDBOX > Permissions: remove Microphone. Tap Record | Clear message, no crash, no stuck notification. Allow it again and record 10 seconds: works |
| C6 | Stop the server window (Ctrl+C) while processing, then start it again and tap Try Again | Same as C1/C2: recording kept, report completes |
| C7 | End of day: `adb logcat -b crash -d` | No crash entries for the app |

## Part E: Crash-resistant, encrypted recording prototype (sandbox only, experimental)

The sandbox app records in small independent pieces (about 5 minutes each), every sound frame encrypted with a key locked in the Android Keystore. Debug and release apps still use the original recorder. **Nothing in this part has been run by anyone yet.** Use a made-up voice only. The raw pieces live in `files/recordings/safe-*` (`adb shell run-as com.woundcopilot.android.sandbox ls -l files/recordings/safe-*`).

| # | Do | Pass if |
|---|---|---|
| E1 | Record 12 minutes of a made-up script (the 5-minute pieces will have rotated twice). Stop. | One file is saved, with **no** "may be missing" warning. In `safe-*` you see about 3 `seg-0000N.aac` pieces. Listen at the 5:00 and 10:00 marks: no gap, no repeated words, no click |
| E2 | Record 3 minutes. Pause for 60 seconds. Resume, record 3 minutes. Stop | Saved length is about 6:00, **not** 7:00 (paused time not recorded). The words just before pause and just after resume both present. No warning |
| E3 | Pause and resume 5 times quickly over 4 minutes | No crash; the notification Pause/Resume button works each time; audio plays through |
| E4 | Record 10 minutes. Lock the phone for 5 of them (screen off, in your pocket). Unlock and Stop | Whole 10 minutes saved; the red microphone indicator showed while locked; no gap at lock or unlock moments; no warning |
| E5 | With the watch app installed after the phone app (see the watch order in RUN-ON-YOUR-PC): start, pause, resume and stop from the watch | Phone follows each command once. Watch and phone show about the same time. File is saved after the watch Stop. (The phone app must be open and unlocked to start from the watch: existing behavior) |
| E6 | Record 10 minutes. At about 7 minutes, run `sandbox\Run-KillRecordingTest.ps1` and press Enter. Reopen the app | The recording is listed as saved with the notice "Recording was interrupted". Length is within about 10 seconds of the kill moment. **No** "may be missing" warning (a plain kill is not a gap). It plays. You can upload it |
| E7 | Same as E6 but kill within the first 60 seconds | A short recording is rescued, or a clear "no audio saved" message. No crash, no stuck notification |
| E8 | Start a recording, then Settings > Battery: restrict WoundCoPilot SANDBOX. Keep recording 15 minutes with the screen off | Note whether Android stops it. If it does, the audio up to that moment must be listed on reopening. (This checks the real "Android kills the app" case, which a script cannot copy exactly) |
| E9 | After E6 recovery, upload the recovered file with the Level 3 server | Transcription works on the rebuilt file. Only after the upload succeeds does the `safe-*` folder disappear |
| E10 | **Protected audio:** after E1, run `cmd /c "adb exec-out run-as com.woundcopilot.android.sandbox cat files/recordings/safe-<name>/seg-00001.aac > piece1.aac"` (plain PowerShell would damage the binary file) and try to play `piece1.aac` on the PC (VLC or ffplay) | It does **not** play (it is encrypted). `session.properties` in the same folder holds only a long locked `keyBlob`, no readable key |
| E11 | **Missing-audio warning:** with the app closed, delete one middle piece (`adb shell run-as com.woundcopilot.android.sandbox rm files/recordings/safe-<name>/seg-00002.aac`) of an interrupted recording (kill it as in E6 first), then reopen the app | The recording is listed with a clear warning such as "Part of this recording may be missing: about 5 min 00 s of audio is missing around 5:00. Listen to the whole recording...". The rest still plays in order |
| E12 | **Clean-up never deletes unfinished pieces:** kill a recording (E6) and do **not** reopen the app. Next day, then a week later, reopen it | The interrupted recording is still there and still rebuilds. (Phone clock change is not needed; the automatic test `theCleanUpNeverDeletesUnfinishedPiecesEvenWhenVeryOld` also covers it) |
| E13 | **Key still works later:** record 2 minutes, kill it (E6) but reopen only after (a) restarting the phone, (b) changing the screen lock (PIN to pattern and back) | It still rebuilds. If it does not, write down exactly what you did: this is the main risk of encryption (see `AUDIO-ENCRYPTION-DESIGN.md`) |
| E14 | *(Replaced in 1.8.5-test10; it contradicted E23.)* Normal long visits do **not** stop at 59:50: they continue into part 2 at 59:00 (E23). The 59:50 stop happens **only** if the hand-over to part 2 cannot start (E28) or after the last allowed part (about 3 h 56 min) | See E23 and E28 |

| E15 | **Key survives restart:** record 2 minutes, Stop, restart the phone, open the app, play the recording | Plays normally with sound; no "cannot be opened" message. (If it fails, note exactly what the screen says) |
| E16 | **Encrypted playback:** after E1, tap Play on the recording, seek forward and back | Sound plays, length shown is correct (within 1 second), seek works. `adb shell run-as com.woundcopilot.android.sandbox ls -l files/recordings` shows the `.m4a` is **under 4 KB** and no other large audio file exists besides the `safe-*` folder |
| E17 | **Upload from memory:** upload the E1 recording with the Level 3 server | Progress moves through parts; transcript and report appear; no new large file appears in `files/` or `cache/` during the upload |
| E18 | **Discard dialog:** under a pending recording tap "Discard this recording...", then Cancel | Nothing is deleted. Tap it again and confirm: the recording and its `safe-*` folder disappear and it leaves the list |
| E19 | **Waiting recordings are never aged out:** leave an unsent recording for 8 days (or set the phone date forward 8 days **on a test phone only**) and reopen | The recording is still there and still plays/uploads; a reminder notice appeared after day 2 (no patient details) |
| E20 | **Storage full:** fill the phone until under 200 MB free (copy big files), tap Record | At under 200 MB the app refuses with a clear space message; when it fills mid-visit the recording stops and saves with a clear message; earlier audio is kept |
| E21 | **Key unavailable:** (only if you can) clear the app's Keystore key or use a test build that fails locking | The app says it cannot record securely and does **not** record |
| E22 | **After a kill with the screen locked:** record 5 minutes, lock the phone, run `sandbox\Run-KillRecordingTest.ps1`, unlock, reopen | Recording listed as interrupted, rebuilds and plays |
| E23 | **Long visit:** record 61 minutes (fake voice or music; keep the phone plugged in) | At **54:00** a calm message says the visit will continue in a second recording. At about **59:00** the recording continues by itself (no stop, no button); the screen keeps showing the recording and a notice that it continues as recording 2. The visit then ends at your Stop |
| E24 | **Two recordings, one visit:** after E23 open the recordings list | Two recordings exist; their notes name the same visit, "part 1" and "part 2", each pointing to the other. Both play. `files/recordings` shows two small `.m4a` placeholders (under 4 KB) and two `safe-*` folders |
| E25 | **No missing sound:** play the end of part 1 and the start of part 2 (use a counting voice or a steady tone) | Continuous. At most a faint ~0.04 s repeat at the join. A gap, click or missing word is a failure. Write down the time |
| E26 | **One report from all parts (1.8.5-test10):** after E23, let the app upload by itself (or tap Create report on either part) with the Level 3 server | Progress shows "Part 1 of 2" then "Part 2 of 2". ONE report appears and contains content from **both** parts in order. Pass/fail |
| E27 | **Kill across the join:** during part 2 run `sandbox\Run-KillRecordingTest.ps1`, reopen | Part 1 is complete and playable; part 2 is listed as interrupted, rebuilds and plays |
| E28 | **Hand-over cannot start (only if you can):** use a build that fails the Keystore at the join | Part 1 keeps recording instead of stopping at once; the app stops cleanly at about 59:50 with a message; nothing is lost |
| E29 | **Draft kept:** leave an unfinished report draft for 8 days (or set the date forward 8 days **on a test phone only**) and reopen | The draft is still there. A reminder appeared after day 2 and was not repeated more than once a day |

Note: there is no short-cut test for the hand-over on the phone yet. A test-only setting (`handOffFramesForTests`) exists in the code but no instrumentation test uses it, so E23 needs the full 59 minutes.

Record in your results: kill time, recovered length, any audible gap/click/repeat (note the time), and the exact wording of any warning. A **gap at a 5-minute boundary**, a **missing last-minutes block**, or a **recording that cannot be rebuilt** is a failure of the prototype.

## Part F: New in 1.8.5-test9 (PIN loop, photo after unlock, speed, report retry)

Note (test10): a report step is now tried at most **3 times in total** (including the first).

These changes are in the normal app code, so they apply to the sandbox build you are testing here and, later, to debug and release.

| # | Do | Pass if |
|---|---|---|
| F1 | Before installing test9, open the test8 sandbox app from a closed state 3 times; time from tap to the PIN screen. Then install test9 and repeat | test9 is the same or faster. Write down both times |
| F2 | Open a visit > Photo > Take photograph. Stay in the camera about 2 minutes (frame it slowly), then take the photo | Back in the app with **no PIN request**; the photo assessment starts |
| F3 | Same as F2 but stay in the camera more than 3 minutes | The app asks for the PIN **once**. After the PIN (or fingerprint), the photo assessment **starts by itself** |
| F4 | Open the camera from the app. While in the camera, run `adb shell am kill com.woundcopilot.android.sandbox`, then press Back to leave the camera. Unlock with the **fingerprint** | Exactly one unlock. No repeated fingerprint or PIN prompts. Then "Take photograph" opens the camera again (not "Finish or cancel the current system selection") |
| F5 | Repeat F4 with the PIN instead of the fingerprint | Same as F4 |
| F6 | Press Home while a visit is open, come back after 5 seconds | The PIN is asked (this is unchanged on purpose) |
| F7 | Level 3 server: start a report, then run `sandbox\Go-Offline.ps1` during "Preparing and checking your report". Wait for the error. Run `sandbox\Go-Online.ps1`, tap **Try again** | A checked AI report appears (not only the source-only draft). The recording is still listed until the report is saved |
| F8 | Dictate (fake speech): "Education given on leg elevation." Create a nursing note | The note does not say "reinforced". Then dictate "I reinforced education on leg elevation": the word "reinforced" is kept |
| F9 | Record 6 minutes with the screen off and the phone on the table | The recording keeps going the full 6 minutes. If it stops, note the minute and copy Developer diagnostics |

## Part D: Safety proof for the sandbox

| # | Do | Pass if |
|---|---|---|
| D1 | Turn Wi-Fi **on**. Stop the PC server. Open the sandbox app and try to sign in | It says it cannot connect. (A normal debug app would reach the live service. The sandbox must not.) |

## Decide

- **All pass:** send me the results. Then I can prepare a release build for you to consider installing over your release app, only after your approval.
- **Any fail:** do not install anything over your release app. Send me the row number, a screenshot (fake data only) and `adb logcat -d` output. Remove the test app: `adb uninstall com.woundcopilot.android.sandbox`.

When finished, delete the fake test accounts with the app's Delete work option, and delete `sandbox\work` to remove the fake test databases.
