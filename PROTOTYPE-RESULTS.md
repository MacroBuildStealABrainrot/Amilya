# Verification report: 1.8.5-test8 (sandbox only)

**Read this first.** Everything below ran on a PC. **The Android app has still never been compiled or run**: this workspace has no Android SDK, Gradle download or Maven access. A "stand-in compile" (stubbed Android classes) type-checks much of the sandbox code, but it is **not** an Android build and I do not call it one. Nothing was deployed, no billing or production code was changed, your installed app was not touched, the release application ID is unchanged, and all test data is synthetic.

## Your five approved decisions: what was done and what proves it
| # | Approved decision | What was built | Proof (PC only) | Not proven |
|---|---|---|---|---|
| 1 | Drafts and unfinished work are never deleted by age; keep until saved, discarded or an approved policy; storage limits and reminders | Report drafts and other unfinished-work records (`draft-...` and the like) now never expire by age while the sandbox flag `RETAIN_UNRESOLVED` is on. Reminders (after 2 days, then at most daily) now also cover drafts. Storage limits unchanged: refuse to start under 200 MB free or 3 GB unresolved (warn at 1 GB); stop and save under 40 MB. | 6 new logic tests; aging everything 400 years deletes no unresolved item; a mutation that makes drafts expire again is caught. | Notification and screens on a phone. Records of other kinds (`source-`, `local-bind-`, `diagnostic-`, `wear-`) and photos not tied to a pending record still expire (24 h for those files). |
| 2 | Recording longer than one hour: automatic continuation into a second recording, sandbox only, same visit, no missing audio, advance warning, careful test | Heads-up at 54:00; hand-over at 59:00 of sound to a new recording with its own new key; microphone and encoder keep running; parts linked by visit id, part number, previous and next names; up to 4 parts. If the next part cannot start, the current one keeps recording and retries. Details in `AUDIO-ENCRYPTION-DESIGN.md`. | Real AAC: part 1 + part 2 minus the repeated 1024 samples equals the uncut recording **exactly** (speech); every part accepted by the backend's own checks (**9 of 9**); **75 real SIGKILL trials across a hand-over, 0 failures**; 13 logic tests with real encrypted parts; 5 deliberate mistakes in the hand-over all caught. | The recorder service wiring on a real phone, Keystore for part 2, the on-screen notice, the real 54:00 and 59:00 timing (a test-only shortcut exists for the phone), and whether the report step merges parts. |
| 3 | Plain HTTP over an isolated USB connection is acceptable for synthetic testing only | Nothing changed: the sandbox talks to the PC over loopback HTTP (USB). The test server is never exposed. | -- | **HTTPS is not verified and must be before any clinical use.** |
| 4 | One 10-second synthetic Speko transcription test, cost confirmed in advance, at most CAD 1 | Done once. Made-up sentence, computer-generated voice (7.1 s). Total cost **US$0.0098 (about CAD 0.014)**. Transcript returned: "This is a synthetic test. The dressing was changed on the left forearm. The wound measures 4 cm by 2 cm." The speech-to-text service picked Azure MAI-Transcribe-2. | `speko-transcription-test.log`. Only synthetic audio left this workspace. | The service can only fetch audio from a web link, so the transcript is of the **original clip, not of the .m4a the app rebuilds**. The rebuilt file was checked separately (probe, decode, backend checks) but **was not transcribed**. |
| 5 | Lost provider results: Amilya absorbs the cost; customer not charged; retry limits, idempotency, cost monitoring, duplicate-charge protection | SQL rules (plan only, not applied): retry limit (default 3), idempotency key with request fingerprint, charge ledger with database rules against double charges, per-attempt cost records, report and alert views. See `STUCK-REQUEST-LEASE-PLAN.md`. | Real PostgreSQL 16.15: **62 of 62 checks**, including 120 retrying clients: absorbed attempts = provider calls - saved results (62 = 62), nobody charged twice. 8 deliberate mistakes caught. | Application code is not written; real tables, HTTP tests, Cloud SQL not done. Nothing applied to billing. |

## What ran (PC only; re-run for this candidate)
| Check | Result | Log |
|---|---|---|
| Pure-logic Kotlin tests (19 + 39 + 109) | **167 pass** | `kotlin-logic-tests.log` |
| Hand-over with real AAC (speech and tones) | **9 of 9** | `handoff-check.log` |
| Real forced kills (SIGKILL), 300 trials: half on encrypted pieces, 75 across a hand-over | **0 failures**, 0 false "missing audio" warnings | `kill-experiment.log` |
| Real full disk (1 MB RAM disk fills during a recording) | all 7,286 frames written were recovered; no false warning | `storage-full-check.log` |
| Genuine encoder audio through storage/recovery and the backend's own checks | **25 of 25** | `real-audio-check.log` |
| In-memory `.m4a` writer: tone fixture / computer speech / the Speko voice clip | **21 of 21 / 20 of 20 / 20 of 20** | `sealed-upload-*.log` |
| Lease rules on PostgreSQL 16.15 | **62 of 62** | `lease_pg.log` |
| Compiler rejects the old `size()` redeclaration; fix accepted | pass | `exposed-bytes-check.log` |
| Deliberate-mistake checks (test8: 5 Kotlin, 5 SQL, plus those carried over) | all caught | `mutation-checks.log` |
| Stand-in compile of sandbox files and real extracts of main files | clean. **Not an Android build** | `standin-compile.log` |
| Kotlin parse check of 94 files | 0 syntax errors. **Not an Android build** | `kotlin-syntax-check.log` |
| Python static checks (75 files) | pass | `python-static-checks.log` |

## Findings worth knowing
* The join between two hand-over parts repeats one frame (about 43 ms, a faded 1024-sample block). That is the price of "nothing missing".
* ffmpeg's optional noise substitution makes parts decode with slightly different hiss than the whole visit (up to 144/32767 on tones). With it off the difference is 0. Does not affect listening.
* Compile-blocker fix from test7 (`ExposedBytes` hiding `size()`) is still in place and still guarded.

## Not verified (complete list)
1. The Android app has **never been compiled**. Not covered even by the stand-in compile: `CaptureScreen.kt` (including the new notice line), the bodies of the `DeviceTools.kt` hooks, most of `Api.kt`, the `RecordingRecovery.kt` edit, the manifest, and every instrumentation test.
2. No instrumentation test has run; no Samsung run.
3. The hand-over wiring in the real recorder service (foreground service, notifications, UI notice) has never run on a phone.
4. AudioRecord + MediaCodec on your Samsung; microphone sharing, foreground-service rules, watch start while locked.
5. Android Keystore: key creation (including the second key at hand-over), restart, lock-screen change, updates, restore to another phone.
6. Android's `MediaPlayer`/`MediaMetadataRetriever` accepting the in-memory MP4.
7. Rebuild time and memory for a one-hour visit on a phone.
8. Transcription of the **rebuilt** recording by the real AI services (only the original clip was transcribed).
9. Whether the report step merges several recordings of one visit (all parts share the same encounter and owner context; behaviour unverified).
10. HTTPS: the sandbox uses loopback HTTP. **Must be verified before any clinical use.**
11. Lease rules: application code, real tables, HTTP-level and Cloud SQL tests.
12. Copies made inside `Api.execute` and the platform's crypto classes cannot be wiped (best effort).
13. A recovery pass could re-consolidate a part that is already stopped (harmless, but a warning-text difference is possible).
14. The first block of part 2 is a faded repeat (about 43 ms).

## Rollback
* Sandbox only: `adb uninstall com.woundcopilot.android.sandbox`. Your installed app is untouched.
* Source: `git apply -R --binary source-changes/1.8.5-test8/0700-*.patch` returns the tree to test7 exactly (checked).
* All new behaviour is behind sandbox-only flags; debug and release have `RETAIN_UNRESOLVED` and `CRASH_SAFE_RECORDING` off.

## Decisions I need from you
1. **HTTPS:** approve a staging build that proves HTTPS end to end (the sandbox stays on loopback HTTP).
2. **Hand-over fallback:** if the next part cannot start, the current one keeps recording until 59:50 and then stops with a message. OK?
3. **Multiple parts in the report:** should the report step automatically use all parts of a visit? (Not built.)
4. **Absorbed-cost alert limit** for lost AI attempts (the amount per day that should raise an alert), and whether the retry limit of 3 is right.
5. **Separate approval** before any lease rule is applied to the real backend or billing.
