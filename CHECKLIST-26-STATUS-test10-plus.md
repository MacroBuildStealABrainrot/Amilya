# Amilya 26-item checklist and technical findings

**Scope:** Candidate branch `test/gpt6-sonnet55-routing`, source commit `af8756179aff7615a262f6a11249aabb9ae197ba`. Production `main` was not changed. No production deployment, live billing change, or patient-data enablement was made.

**Checklist source note:** The repository and connected GitHub Issues API do not contain the original verbatim 26-line checklist. These concise labels reconstruct the numbered topics from the existing review notes and commit history; they are not quotations.

**Status key:** Done = implementation and relevant automated checks verified; Partial = implementation verified but device or approval validation remains; Unverified = external/device validation remains; Deferred = intentionally held for approval.

| # | Checklist item (condensed) | Status | Evidence / remaining work |
|---:|---|---|---|
| 1 | Preserve top-up balance across billing-cycle settlement | Done | Backend regression coverage; 712 tests passed in run #38. |
| 2 | Release AI credit holds when provider work is cancelled | Done | Cancellation-path regression coverage; backend suite passed. |
| 3 | Recheck stale credit leases under the account lock | Done | Source fix and backend regression coverage. |
| 4 | Keep AI lease renewal alive through transient database errors | Done | Retry behavior and backend regression coverage. |
| 5 | Close database sessions before awaiting provider calls | Done | Provider flow reviewed; session scope ends before provider work. |
| 6 | Reject malformed non-ASCII Stripe signatures cleanly | Done | Backend regression expects HTTP 400. |
| 7 | Preserve available balance during AI settlement/top-up interactions | Done | Billing-cycle/top-up regression coverage. |
| 8 | Prevent duplicate/orphan recovery racing a long-visit rebuild | Done | Recovery race fix and backend regression coverage. |
| 9 | Honor configured text-model provider routes | Done | Routing implementation and backend tests. |
| 10 | Honor configured vision and challenger routes | Done | Provider selection and backend tests. |
| 11 | Preserve prompt-injection defenses while allowing clinical nonadherence notes | Done | Filter adjustment and regression coverage. |
| 12 | Validate recovery metadata and bound recorder finalization | Done | Guard and bounded flush verified by the recorder instrumentation group. |
| 13 | Remove the public sample-workspace entry point | Done | Public sample entry removed; demo fixtures remain test-only. |
| 14 | Preserve existing PIN/account-lock behavior | Unverified | No PIN policy change was introduced. Verify lock-screen, biometric, and relock behavior on Samsung hardware. |
| 15 | Release microphone/encoder resources after recorder startup failure | Done | Startup/resource regression coverage passed in the recorder and Level 1 groups. |
| 16 | Clarify repeated photo failure and avoid futile retry loops | Done | Backend tests passed. |
| 17 | Replay photo fallback before retry exhaustion and honor retry delay | Done | Backend retry/fallback regression coverage passed. |
| 18 | Defer photo processing until the app is unlocked and ready | Done | Android photo-recovery group passed. |
| 19 | Warn when calls or microphone interruptions affect a recording | Partial | Warning path and synthetic tests are present; confirm real call/audio-focus interruption behavior on Samsung devices. |
| 20 | Show a useful empty Saved Work state and hide irrelevant bulk delete | Done | Compose instrumentation passed. |
| 21 | Avoid patient names in Saved Work and recent-work previews | Done | Generic labels and privacy regression instrumentation passed. |
| 22 | Sort Home recent work by update time and show only the newest item | Done | Candidate source implements newest-first, single-item preview. |
| 23 | Remove unused Saved Work search | Done | Search UI and state removed. |
| 24 | Show accurate unfinished-recording counts and separate recovered reports | Done | Recovery summary/report regressions passed in emulator suite. |
| 25 | Provide a global way to finish or discard unfinished recordings | Done | Recovery list supports owner-scoped resume/discard, explicit delete confirmation, and generic status-only previews; Android regression groups passed. |
| 26 | Add optional Clinical Assist | Deferred | Requires clinical/privacy approval and a separate product decision before implementation or any identifiable-data use. |

## Smaller technical findings

| Finding | Status | Evidence / remaining work |
|---|---|---|
| Stale credit hold release must recheck lease under account lock | Done | Backend regression coverage; run #38 passed. |
| Lease renewal must survive temporary database errors | Done | Backend regression coverage; run #38 passed. |
| Provider DB session must not remain open over an await | Done | Reviewed provider flow and passing backend suite. |
| Cancellation must release an AI credit hold | Done | Backend regression coverage; run #38 passed. |
| Stripe parsing must reject malformed Unicode safely | Done | Backend regression coverage; run #38 passed. |
| Photo retries must respect retry delay and retain fallback replay | Done | Backend regression coverage; run #38 passed. |
| Recovery metadata must be guarded and encoder flush bounded | Done | Recorder instrumentation passed. |
| Recorder startup failure must release resources | Done | Recorder and service-start instrumentation passed. |
| Recovery discard must be owner-scoped and confined to local audio | Done | Recorder recovery-action instrumentation passed. |
| Recovery UI must not render patient names or report text | Done | Compose instrumentation passed. |
| Pilot APK must retain the original recorder | Done | Run #38 checked Pilot package `com.woundcopilot.android.pilot` and confirmed `SafeRecorderService` is disabled. |
| Sandbox recorder must recheck microphone permission before AudioRecord creation | Done | Permission guard added; Android lint/build job passed. |
| Cross-install storage upgrade verification | Unverified | This check requires separate prepare, app-update, verify, and cleanup installs; it is intentionally excluded from the in-process emulator sweep. |

## Automated verification

| Check | Result |
|---|---|
| GitHub Actions run #38 | **Success** — [view run](https://github.com/MacroBuildStealABrainrot/Amilya/actions/runs/40237832317), candidate commit `af8756179aff7615a262f6a11249aabb9ae197ba). |
| Backend tests | **712 passed, 1 skipped**, 1 upstream Starlette/httpx deprecation warning; static checks parsed 85 Python files with no syntax errors or blocking calls. |
| Android build and unit tests | **Passed** for all configured variants. |
| Android lint | **0 errors, 14 warnings**. |
| Build flags | **Passed** — release/debug/Pilot keep the original recorder; sandbox alone enables SafeRecorderService. |
| Emulator instrumentation | **All required groups passed:** recorder, Level 1, Level 2, and Wear Bridge. Broad sweep also exited 0. The evidence contains 205 XML test cases, 47 skipped, and 0 failures across 31 classes. |
| Emulator storage | Runner cleanup freed space; emulator booted and completed the test step. |
| Pilot APK | SHA-256 `edd024f1bf11bba281be0505b79ea4af01783b4dc52c251db7a5db42625dbe67`; package `com.woundcopilot.android.pilot`; artifact from run #38. |

Earlier runs #31 and #32 failed before instrumentation because the hosted runner lacked the emulator's required disk space. Run #33 reached tests and exposed stale test expectations; run #34 verified the required groups but was superseded before its backend job finished. Those findings were corrected or separated as manual validation, and run #38 completed successfully.

## Device and approval follow-up

- Samsung device testing remains required for real audio focus, calls/microphone interruptions, long recording, notification behavior, crash/restart recovery, lock-screen behavior, and install/update compatibility.
- Clinical and privacy approval is required before enabling identifiable patient data or implementing Clinical Assist.
- The Pilot APK targets the existing Cloud Run API and keeps the original recorder. Use synthetic data only until those approvals are complete.
- CI artifacts are candidate outputs, not a production release.
