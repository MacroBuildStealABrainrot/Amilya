# WoundCoPilot / Amilya — pipeline recovery, 1.8.5-test1

## Result and scope

Incremental recovery fixes are implemented in the existing Android/backend projects. Signed test APKs use version code 21 and the existing release certificate. No production deployment was performed. The Android source-only recovery path can use the already-existing manual quick-report endpoint on the hosted backend. The photo validation/recovery changes require a backend rollout before they affect hosted assessments.

The submitted clinical transcript was not copied into tests, source, logs or this report. All test sources, microphone recordings, and images were synthetic. The attachment's analysis was treated as hypotheses. Cloud Logging returned no records matching the two supplied request IDs within the 30-day lookup, so their original provider responses and precise causes remain unverified.

## Verified findings

1. Original written/audio sources already enter the documentation context even when fact extraction is unavailable. A missing confirmed fact is not automatically the reason a report cannot be generated.
2. The photo schema does not require location, wound measurements or diagnosis. Relaxing those nonexistent requirements would not fix the reported failure.
3. A malformed individual photo candidate previously rejected the entire response, including other independently valid observations.
4. Quick reports previously propagated provider/checking failures after existing bounded repair instead of using the existing source-only formatter.
5. Failure-category metadata was dropped for schema errors/timeouts/connections/processing failures. A new replay test reproduced an unnecessary provider call after a successful schema recovery. Keeping these closed categories fixes that replay defect.

## Implemented behavior

### Reports

Successful AI generation and independent checking retain the existing path. For a downstream generation/checking error (402/429/502/503/504), the backend returns a source-only draft from the existing formatter. It never promotes the rejected AI prose. The result explicitly says `qa.passed=false`, `source_only_unchecked`, and requires review. Warnings are displayed outside the clinical note. This fallback can retain conversational noise and cannot substitute for a successful AI-generated clinical report.

The Android app also handles these errors from an older hosted backend by calling its manual quick-report endpoint with `use_ai=false`. It uses a distinct stable mutation identity and the acknowledged sources. It does not upload or transcribe the recording again. If the fallback also fails, the existing retained-recording Retry path remains available. Authorization and concurrent-source errors are not converted to successful drafts.

### Photos

An invalid candidate is excluded while independently schema-valid visual observations remain available, with a review warning. Unsupported nonvisual fields remain excluded. An unusable envelope, quality schema or all-invalid candidate set triggers one strict response retry from the original image. The retry uses the existing router, schema validation, metering and idempotency. A successful recovery replays without another provider request. A second invalid response remains an error; no assessment is fabricated. Provider-credit failures do not trigger schema recovery. Insufficient-quality images still require retaking.

### Errors and privacy

The audio extraction warning now uses the existing typed error feedback. Billing preserves only closed technical failure categories, not arbitrary provider messages. No raw provider response, prompt, transcript, image or report content was added to diagnostics. No new persistent clinical-data store, migration, model default, recording screen or cleanup policy was introduced.

## Tests actually executed

- Provider/billing/quick-workflow/photo-quality/photo-recovery/report-repair regression run: **240 passed**.
- Documentation layers/contract/refinement and developer diagnostics: **127 passed**.
- Recovery regression and additional edges: **13 passed**; 10 overlap the first run, giving **370 distinct backend tests** in total.
- Android API 36 emulator: **3 passed**, using the real microphone service and a synthetic loopback backend:
  - Normal Stop -> report succeeds.
  - AI report failure -> automatic source-only draft. Recording still exists; two report calls produce one result, and audio completion occurs once.
  - AI and manual report failures -> retained audio -> Retry succeeds without another transcription/upload part.
- Phone debug/test APK and signed phone/watch release builds succeeded.
- APK signature, existing certificate, version code/name, non-debuggable release status and known-secret scans passed.
- Test capture build flag was checked: `ALLOW_SCREEN_CAPTURE=true`.
- No physical phone, real wound image, paid live-provider call, production rollout or live clinical-quality benchmark was executed. A successful synthetic schema test does not prove the provider can assess a real image accurately.

The pytest runs show one dependency deprecation warning about Starlette/httpx; no failing tests remained in these runs. Earlier intentional failures and their corrected reruns are retained in the local pass directory.

## Changed files

Backend: `server/providers.py`, `server/quick_workflows.py`, `server/clinical.py`, `server/billing.py`, `tests/test_pipeline_recovery.py`, `tests/test_photo_recovery.py`, `tests/test_quick_workflows.py`.

Android: `app/src/main/java/com/woundcopilot/android/AppState.kt`, `app/src/androidTest/java/com/woundcopilot/android/WearBridgeTest.kt`, and phone/watch `build.gradle.kts` version metadata.

The included patch compares these files with the delivered 1.8.4-test1 full-source restore archive. Source hashes and APK hashes are included. Older development work was preserved.

## Phone verification

Use fictional information only with this screen-capture-enabled test build.

1. Install the phone APK over the existing signed test app.
2. Add a short fictional written note; check normal report creation.
3. Record a fictional short visit and Stop; no second Generate action should be needed.
4. If AI checking fails, verify that the editor opens a clearly warned source-only draft. Review against the original; do not treat it as checked AI output.
5. If both requests fail, verify the recording remains accessible and Retry works.
6. Once the backend changes are rolled out to a test environment, test photo assessment and inspect the accepted observations and exclusion/recovery warnings.

## Limits and next step

This pass removes specific recovery dead ends; it does not prove or fix the original live-provider cause. The highest-value next step is an isolated backend rollout with synthetic end-to-end requests and request diagnostics, followed by clinician testing. Routine clinical accuracy and live response latency must be evaluated separately. No faster model was selected and no speed-improvement claim is made from build/test completion.
