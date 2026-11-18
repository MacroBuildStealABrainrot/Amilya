# Amilya 26-item checklist and technical findings

**Scope:** candidate branch `test/gpt6-sonnet55-routing`. No production deployment, live billing change, or patient-data enablement was made.

**Checklist source note:** the original verbatim 26-line checklist was not present in the repository or accessible review backup. These concise labels reconstruct the numbered topics from the available review notes and commit history; they are not presented as exact quotations.

**Status key:** Done = implementation and relevant automated checks are verified; Partial = implementation is present but automated or device verification remains; Unverified = external/device validation remains; Deferred = intentionally held for approval.

| # | Checklist item (condensed) | Status | Evidence / remaining work |
|---:|---|---|---|
| 1 | Preserve top-up balance across billing-cycle settlement | Done | Regression coverage; backend suite passed. |
| 2 | Release AI credit holds when provider work is cancelled | Done | Cancellation-path fix and regression test; backend suite passed. |
| 3 | Recheck stale credit leases under the account lock | Done | Source fix and backend regression coverage. |
| 4 | Keep AI lease renewal alive through transient database errors | Done | Retry behavior and backend regression coverage. |
| 5 | Close database sessions before awaiting provider calls | Done | Reviewed provider flow; session scope ends before provider work. |
| 6 | Reject malformed non-ASCII Stripe signatures cleanly | Done | Backend regression expects HTTP 400. |
| 7 | Preserve available balance during AI settlement/top-up interactions | Done | Billing-cycle/top-up regression coverage; backend suite passed. |
| 8 | Prevent duplicate/orphan recovery racing a long-visit rebuild | Done | Recovery race fix and tests. |
| 9 | Honor configured text-model provider routes | Done | Route changes and backend tests. |
| 10 | Honor configured vision and challenger routes | Done | Provider selection and backend tests. |
| 11 | Preserve prompt-injection defenses while allowing clinical nonadherence notes | Done | Filter adjustment and regression coverage. |
| 12 | Validate recovery metadata and bound recorder finalization | Partial | Guards and bounded flush are implemented; final emulator groups could not boot yet. |
| 13 | Remove the public sample-workspace entry point | Done | Public sample entry removed; synthetic fixtures remain internal to tests. |
| 14 | Preserve existing PIN/account-lock behavior | Unverified | No PIN policy change was introduced; physical Samsung lock-screen behavior needs device testing. |
| 15 | Release microphone/encoder resources after recorder startup failure | Partial | Cleanup is implemented; required emulator regression group has not run successfully yet. |
| 16 | Clarify repeated photo failure and avoid futile retry loops | Done | Backend tests passed. |
| 17 | Replay photo fallback before retry exhaustion and honor retry delay | Done | Backend tests passed after correcting an earlier expected-response regression. |
| 18 | Defer photo processing until the app is unlocked and ready | Partial | Deferred-processing path is implemented; Android instrumentation still needs a successful run. |
| 19 | Warn when calls or microphone interruptions affect a recording | Partial | Warning path is implemented; verify call/audio interruptions on Samsung hardware. |
| 20 | Show a useful empty Saved Work state and hide irrelevant bulk delete | Partial | UI update is implemented; required Compose instrumentation has not run successfully yet. |
| 21 | Avoid patient names in Saved Work and recent-work previews | Partial | Generic type/date labels are implemented; Android UI regression is pending successful emulator execution. |
| 22 | Sort Home recent work by update time and show only the newest item | Done | Candidate source implements newest-first, single-item preview. |
| 23 | Remove unused Saved Work search | Done | Search UI/state removed. |
| 24 | Show accurate unfinished-recording counts and separate recovered reports | Partial | Recovery summary/report separation is implemented; required Android instrumentation remains pending. |
| 25 | Provide a global way to finish or discard unfinished recordings | Partial | New Recovery & older work list, owner-scoped resume/discard, explicit permanent-delete confirmation, and synthetic tests are committed; phone tests have not executed because the hosted emulator cannot allocate its data partition. |
| 26 | Add optional Clinical Assist | Deferred | Requires clinical/privacy approval and a separate product decision before implementation or identifiable-data use. |

## Smaller technical findings

| Finding | Status | Evidence / remaining work |
|---|---|---|
| Stale credit hold release must recheck lease under account lock | Done | Backend regression coverage. |
| Lease renewal must survive temporary database errors | Done | Backend regression coverage. |
| Provider DB session must not remain open over an await | Done | Reviewed source scope. |
| Cancellation must release an AI credit hold | Done | Backend regression coverage. |
| Stripe parsing must reject malformed Unicode safely | Done | Backend regression coverage. |
| Photo retries must respect retry delay and retain fallback replay | Done | Backend suite passed. |
| Recovery metadata must be guarded and encoder flush bounded | Partial | Implemented; final emulator test group is pending. |
| Recorder startup failure must release resources | Partial | Implemented; final emulator test group is pending. |
| Recovery discard must be owner-scoped and confined to local audio | Partial | Instrumentation regression added; pending emulator execution. |
| Recovery UI must not render patient names or report text | Partial | Synthetic Compose regression added; pending emulator execution. |
| Pilot APK must retain the original recorder | Done | Built APK checks confirmed package `com.woundcopilot.android.pilot` and disabled experimental SafeRecorderService. |
| Sandbox recorder must recheck mic permission immediately before AudioRecord creation | Partial | Lint finding identified; explicit permission guard added in the current candidate batch, pending CI lint rerun. |

## CI evidence and unresolved validation

| Run | Result |
|---|---|
| #31, run ID `40229713890` | Backend passed: 712 passed, 1 skipped, 1 warning. Android build/unit/flags passed. Emulator failed before tests: runner had 6.8 GB free but the emulator requested 7.37 GB. |
| #32, run ID `40231742357` | Backend passed: 712 passed, 1 skipped, 1 warning. Android build/unit/lint/build flags passed, including the Pilot assertion. Emulator again failed before tests: even with a 2 GB userdata setting, the image requested 7.37 GB while 6.82 GB was available. No phone tests ran. |
| #33 | Workflow now removes unused .NET/CodeQL runner installations before Android SDK setup. It also adds a pre-construction microphone permission check for sandbox AudioRecord. CI rerun is pending. |

## Approval and device validation

- Samsung device testing remains required for real audio focus, calls/microphone interruptions, long recording, notification behavior, crash/restart recovery, and install/update compatibility.
- Clinical and privacy approval is still required before enabling identifiable patient data or using Clinical Assist.
- Pilot targets the existing Cloud Run API and keeps the original recorder. Use synthetic data only until approvals are complete.
- CI artifacts are candidate build outputs, not a production release.
