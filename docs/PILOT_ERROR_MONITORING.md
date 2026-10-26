# Amilya Pilot diagnostic monitoring (review required)

This branch introduces opt-in technical failure signals to PostHog. **It does not deploy production changes or enable monitoring by default.**

## Implemented
- Android Pilot-only diagnostic sender with strict stage/outcome/code allowlists.
- Android previous-process crash flag (reported on next launch), camera/picker launch failures, recording multipart upload failures, and failed transitions to later audio parts.
- Backend request failure events, AI extraction errors, source-only report recovery, and transcription provider fallback.
- Unit tests for disabled-by-default and safe outbound backend payload properties.

## Configuration needed before a monitored Pilot APK is built
Provide these values to the Pilot build environment **as secrets, never in git**:
- `AMILYA_POSTHOG_HOST`: the HTTPS ingestion host shown by the connected PostHog project.
- `AMILYA_POSTHOG_PROJECT_TOKEN`: the project's ingestion token.

For a backend monitoring pilot, provide the same env variables only to a **non-production server**. Do not alter the production Cloud Run service without owner approval.

## Privacy and verification gates
- No event may include patient identity, visit data, text, photographs, audio, transcripts, raw stack traces, HTTP URLs or request bodies, or upstream messages.
- This telemetry uses randomized per-event distinct IDs and disables geo-IP enrichment. Review provider defaults, regional data residency, retention, mobile network logs, and potential IP processing before any clinical use.
- Do not turn on session replay, autocapture, raw logs, user identification, or network payload capture.
- Verify Android unit/build tests and backend tests in GitHub Actions. Samsung physical-device tests are needed for crash recovery and recording handover behavior; emulator runs are optional.
- Verify PostHog actually receives an intentionally triggered failure in a synthetic, non-clinical Pilot encounter.
- Firebase Crashlytics is not installed or configured by this branch.
- Monitoring is not yet wired to **every** recording, transcription, report and photo failure path. Additional source-level coverage must be completed and tested before claiming full coverage.
