# Where users can still see "WoundCoPilot" (checked on test/isolated-safety, 1.8.5-test15p)

Nothing here was renamed yet. Internal code names (package names, storage keys) are not listed: users never see them.

## Phone app (Android)

| # | Where the user sees it | Wording now | File |
|---|---|---|---|
| 1 | App name under the icon, normal debug and release builds | WoundCoPilot | `android/app/build.gradle.kts` line 19 |
| 2 | App name, sandbox (computer test) build | WoundCoPilot SANDBOX | `android/app/src/sandbox/AndroidManifest.xml` |
| 3 | Unlock screen heading | Unlock WoundCoPilot | `AppLock.kt` 85 |
| 4 | Fingerprint / face unlock window title | Unlock WoundCoPilot | `AppLock.kt` 59 |
| 5 | Recording notification (pull-down shade) while recording | WoundCoPilot · 3:12 | `DeviceTools.kt` 341 (recorder in use), `SafeRecorderService.kt` 700 (crash-safe recorder, off) |
| 6 | Error when the microphone cannot start | Keep WoundCoPilot open and try again | `DeviceTools.kt` 170, `SafeRecorderService.kt` 340 |
| 7 | Error when recording cannot start | Keep WoundCoPilot open and allow microphone access | `DeviceTools.kt` 398, `SafeRecorderService.kt` 58 |
| 8 | Notification-permission tip on the recording screen | Settings > Apps > WoundCoPilot > Notifications (wrong: the Pilot app is called "Amilya Test 15" in Settings) | `RecordingSafety.kt` 67 |
| 9 | Account screen, credits line | Your account has unlimited WoundCoPilot credits / WoundCoPilot credits available in this workspace | `ui/AccountScreens.kt` 197-198 |
| 10 | Account screen, bottom line | WoundCoPilot for Android | `ui/AccountScreens.kt` 241 |
| 11 | Reminders about unfinished recordings (notification and screen) | WoundCoPilot is holding N recordings / Open WoundCoPilot to … / Nothing in WoundCoPilot was deleted | `UnresolvedRetention.kt` 35, 37, 58, 94 |
| 12 | Name of saved recording and photo files (seen when sharing or in Files) | WoundCoPilot-<date>-<id>.m4a / .jpg | `DeviceTools.kt` 604 |
| 13 | Label of a copied report (shown by some keyboards and clipboard managers) | WoundCoPilot report | `QuickReportScreen.kt` 203, `VoiceCommands.kt` 116 |
| 14 | Text sent with "Send to computer" (report transfer link message) | WoundCoPilot report transfer | `QuickIntegrationScreens.kt` 111 |
| 15 | Voice commands: optional lead-in phrase (no wake word) | Hey WoundCoPilot | `VoiceCommands.kt` 22, 60 |

The watch app has no "WoundCoPilot" wording. Notification channel names ("Recording", "Report processing", "Waiting recordings") are neutral.

## Server and web pages

| # | Where the user sees it | Wording now | File |
|---|---|---|---|
| 16 | Office computer "Receive a report" page: browser tab and page heading | Receive a report · WoundCoPilot / WoundCoPilot · Report transfer | `backend/server/transfer_receiver/index.html` 6, 22 |
| 17 | Web app browser tab title | WoundCoPilot · Wound care documentation | `backend/frontend/index.html` |
| 18 | Web app logo text and home button label | WoundCoPilot / WoundCoPilot home | `backend/frontend/src/App.tsx` 35, 39 |
| 19 | Server answer when the web app is missing | service: WoundCoPilot | `backend/server/main.py` 153 |
| 20 | Name of the owner account created by the setup script | WoundCoPilot Owner | `backend/server/identity.py` 298, 303 |
| 21 | AI instructions: "You are WoundCoPilot" (not shown to users, but can leak into AI wording) | You are WoundCoPilot | `backend/server/prompts/00_healthcare_boundary.system.md` 1 |
| 22 | Name sent to the AI service with each request (seen only in that service's dashboard) | WoundCoPilot | `backend/server/providers.py` 750 |

## Already "Amilya"
- Pilot app name under the icon: Amilya Test 15. Isolated test app: Amilya Isolated Test. Server-test app: Amilya Server Test.
- Watch app texts ("Unlock Amilya on your phone").
