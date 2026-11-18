# Amilya test11 development status — 25 October 2026

Branch: `fix/server-diagnosis-20261025`; based on latest development branch `test/gpt6-sonnet55-routing` at `68c9d4601cd08736fe9f2db025b8dcd2669d5b8e`. Original source and checklist retained.

## Incident and validation boundary

The root cause of HTTP 502 request `2a9bc999-5629-43c8-9bca-b5e273febe67` remains **unconfirmed**. Google Cloud Console returned Site Unavailable, including after reload. No production logs, deployed environment, provider credits or secrets were inspected. Confirmed source defects are not proof of that specific incident. See [routing audit and read-only logging query](backend/docs/AI-ROUTING-AUDIT-2026-10-25.md).

No deployment, billing/subscription balance change, identifiable-data enablement, emulator execution, original-source deletion or recording deletion occurred. Installing the Pilot APK does not apply backend fixes to Cloud Run.

## Checklist status

**Source limitation:** the repository labels its available 26-item list as a reconstruction from earlier reviews and commits. The original verbatim checklist was not recovered. The statuses below apply to that reconstruction.

16 completed, 9 partially completed, 1 unverified/deferred. Completed means accessible implementation and applicable automated/source checks; it does not mean deployed, clinically approved or verified on Samsung. Earlier fixes remain attributed to the prior candidate. This batch changes routing, diagnostics, safe error handling, deadlines and CI.

| # | Item | Status | Evidence / remaining work |
|---:|---|---|---|
| 1 | Preserve top-up balance across billing-cycle settlement | Completed | Existing billing regressions passed; no live balances changed. |
| 2 | Release AI credit holds when provider work is cancelled | Completed | Cancellation settlement regressions passed; no customer charge for failed work. |
| 3 | Recheck stale credit leases under the account lock | Completed | Stale hold is rechecked under the account lock; backend regressions passed. |
| 4 | Keep AI lease renewal alive through transient database errors | Completed | Transient heartbeat-failure tests passed. Lease flag remains off by default. |
| 5 | Close database sessions before awaiting provider calls | Completed | Provider transaction-scope and concurrent-writer regressions passed. |
| 6 | Reject malformed non-ASCII Stripe signatures cleanly | Completed | Malformed non-ASCII signature regression passed. |
| 7 | Preserve available balance during AI settlement/top-up interactions | Completed | Settlement/top-up concurrency and cycle regressions passed. |
| 8 | Prevent duplicate/orphan recovery racing a long-visit rebuild | Completed | Existing recovery race protections reviewed; backend regressions passed. |
| 9 | Honor configured text-model provider routes | Completed | Fixed configuration bypass; synthetic routing and wire-request tests passed. Live environment unverified. |
| 10 | Honor configured vision and challenger routes | Completed | Vision route retained; independent challenger now enforced for every Deep Review. Live provider test outstanding. |
| 11 | Preserve prompt-injection defenses while allowing clinical nonadherence notes | Completed | Prompt-injection and nonadherence-note regressions passed. |
| 12 | Validate recovery metadata and bound recorder finalization | Partially completed | Metadata/finalization guards compile. Real interruption and recovery behavior requires Samsung. |
| 13 | Remove the public sample-workspace entry point | Completed | Existing public sample entry removal retained; synthetic fixtures remain in tests. |
| 14 | Preserve existing PIN/account-lock behavior | Partially completed | PIN policy logic is retained. Physical lock-screen and external-photo return require Samsung. |
| 15 | Release microphone/encoder resources after recorder startup failure | Partially completed | Startup cleanup compiles. Microphone failure/recovery requires physical device testing. |
| 16 | Clarify repeated photo failure and avoid futile retry loops | Completed | Photo error/retry regressions passed; source-preservation behavior retained. |
| 17 | Replay photo fallback before retry exhaustion and honor retry delay | Completed | Fallback replay, bounded retry and retry-delay regressions passed. |
| 18 | Defer photo processing until the app is unlocked and ready | Partially completed | Deferred-photo lock/busy/ownership guards retained; lifecycle execution requires Samsung. |
| 19 | Warn when calls or microphone interruptions affect a recording | Partially completed | Interruption warning implementation retained; calls and audio focus require Samsung. |
| 20 | Show a useful empty Saved Work state and hide irrelevant bulk delete | Partially completed | Empty-state implementation retained; visual/device interaction unverified. |
| 21 | Avoid patient names in Saved Work and recent-work previews | Partially completed | Type/date labels retained; confirm no identifying previews on Samsung. |
| 22 | Sort Home recent work by update time and show only the newest item | Completed | Existing newest-first, single-item preview retained; source review completed. |
| 23 | Remove unused Saved Work search | Completed | Existing unused Saved Work search removal retained. |
| 24 | Show accurate unfinished-recording counts and separate recovered reports | Partially completed | Recovery count/report separation retained; verify against actual device recordings. |
| 25 | Provide a global way to finish or discard unfinished recordings | Partially completed | Owner-scoped recovery list and explicit discard confirmation retained. Do not delete existing recordings during validation. |
| 26 | Add optional Clinical Assist | Unverified / deferred | Deferred. Optional Clinical Assist requires product, clinical and privacy approval. |

## Smaller technical findings

| Finding | Status / evidence |
|---|---|
| Stale hold release under account lock | Completed; backend concurrency regressions passed. |
| Lease renewal through transient database failures | Completed; backend regressions passed; feature flag remains off by default. |
| Database session closed before provider await | Completed; transaction-scope regressions passed. |
| Cancellation releases AI hold | Completed; cancellation/settlement regressions passed. |
| Malformed Unicode Stripe signature | Completed; clean HTTP 400 regression passed. |
| Photo fallback replay and retry delay | Completed; photo regressions passed. |
| Recovery metadata and bounded encoder flush | Partially completed; compiled, physical interruption/recovery validation outstanding. |
| Recorder startup cleanup | Partially completed; compiled, physical microphone failure validation outstanding. |
| Owner-scoped discard confined to local audio | Partially completed; instrumentation compiled, physical execution outstanding. |
| Recovery UI omits names/report text | Partially completed; instrumentation compiled, physical execution outstanding. |
| Pilot retains original recorder | Completed; built package and disabled experimental recorder asserted in CI. |
| Mic permission recheck before AudioRecord construction | Completed for source/lint checks; physical permission-race validation outstanding. |
| Storage-full regression skipped without fixture; legacy helper hid failures | CI now supplies a separate 1 MB RAM disk; helper exit propagation verified with success and failure checks. |

## Reproducible verification

Tested code / APK source: [`a939c7fcf64658e9de96cf2635a99a77dd0eda00`](https://github.com/MacroBuildStealABrainrot/Amilya/commit/a939c7fcf64658e9de96cf2635a99a77dd0eda00).
[Successful CI run 40238619591](https://github.com/MacroBuildStealABrainrot/Amilya/actions/runs/40238619591).

| Check | Result |
|---|---|
| Full local backend | 731 passed; 1 PostgreSQL-only test skipped; 1 deprecation warning. |
| Focused routing/photo/diagnostics | 209 passed. |
| CI backend with SQLite and PostgreSQL | 739 passed; 1 SQLite-only instance of the PostgreSQL concurrency case skipped; PostgreSQL variant passed; 1 Starlette/httpx deprecation warning. |
| App JVM tests | 150 passed; zero failures/skips, including the real storage-full case on an isolated 1 MB RAM disk. |
| Wear-contract JVM tests | 39 passed; zero failures/skips. |
| Android compilation | Sandbox, Pilot, Debug, Release, Sandbox instrumentation, Wear Sandbox and Wear Release passed. |
| Lint | Sandbox and Pilot passed with 14 nonblocking warnings each: dependency currency, target API, one unused resource and Kotlin extension suggestions. |
| Built APK flags | Expected package IDs passed; experimental recorder disabled in Pilot, Debug and Release, enabled only in Sandbox. |
| Python static checks | Passed; no blocking subprocess calls found inside async functions. |
| Legacy storage helper | Synthetic checks preserve exit 0 and exit 7; mounting mocked for those shell checks. |
| Physical Samsung / live provider | Not executed; instrumentation compiled only. |

The earlier CI attempt exposed a nonexistent `:app:testPilotUnitTest` task. The workflow now uses `:app:test` to run the configured app JVM suite, which resolves to Sandbox in this project. Pilot compilation and lint are independently required. No emulator was used. Final required checks have no failures.

Pilot: `Amilya-Pilot-1.8.5-test11.apk`, version code 31, package `com.woundcopilot.android.pilot`; artifact SHA-256 verified against CI:

`999928d7d9b8ac8a85c0b3e654b99052dcee80ee72ecc528b9a8a1ed30e83767`

Implementation: [routing/diagnostics](https://github.com/MacroBuildStealABrainrot/Amilya/commit/fc07decd3052608e3df477b4c9adf8f631608de3), [CI task correction](https://github.com/MacroBuildStealABrainrot/Amilya/commit/f7ec57ca81a0010528beb2c5a863114369646b60), [storage-full coverage and failure propagation](https://github.com/MacroBuildStealABrainrot/Amilya/commit/a939c7fcf64658e9de96cf2635a99a77dd0eda00).


## Samsung testing (synthetic data only)

1. Confirm install/update compatibility without uninstalling or clearing existing app data. Package is `com.woundcopilot.android.pilot`; original recorder remains enabled. A signing mismatch needs a reviewed signing plan, not deletion of recordings.
2. Record synthetic 1-minute and 30-minute visits; stop, transcribe, generate, save and reopen reports. Compare duration and content with the synthetic script.
3. Test backgrounding, screen lock, calls and microphone interruption; verify warning, retained audio and recovery.
4. Test offline recording and reconnect/retry without duplicate work.
5. Test camera/gallery return through PIN unlock and assess a non-patient test image.
6. Check unfinished counts, ownership and saved-work preview privacy using synthetic work. Keep original recordings intact.
7. After an approved backend rollout, run synthetic live photo/Deep Review smoke tests and correlate requested/reported models with request IDs.

## Remaining access and approval gates

- Working read-only Google Cloud access is required to establish the actual 502 cause and deployed routing configuration.
- Production deployment requires explicit approval, a reviewed rollout/rollback plan and synthetic live-provider tests.
- Real clinical use, identifiable patient data and Clinical Assist require clinical/privacy approval.
- Stable Pilot signing, experimental recorder activation and any billing-normalization/cache-write accounting change require separate review.
- Account model availability, provider credits, ZDR-eligible live endpoints and unrecognized model snapshot aliases remain unverified.
