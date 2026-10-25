# Timestamped audio-window verification

This flow verifies a selected excerpt **longer than zero and no more than 30 seconds** from existing audio evidence. It does not retranscribe a whole visit, replace an original transcript, confirm model findings, or represent a second medical expert assessment. Live provider execution remains subject to the existing execution flag, privacy approvals, rate limits, credit reservation and usage settlement.

## Source and clinician controls

The clinician selects an audio evidence record, reselects its original file, identifies a start/end time, describes the uncertain term and provides consent to process the excerpt. The server requires the uploaded file's SHA-256 hash to match the hash saved with that evidence. Files are limited to 25 MB, and the selected window must be finite, positive, no more than 30 seconds and within the locally verified recording duration.

The server uses ffprobe and ffmpeg to decode only the selected window into 16 kHz mono PCM WAV. Decoder commands use fixed temporary file paths, a file/pipe protocol allowlist, no shell, explicit stream selection, bounded execution time and bounded output size. The decoded clip's actual duration is checked again. Original media and derivative clips are not retained after the request.

Only the clipped audio is sent to the speech provider. The original transcript, uncertainty description and context are not sent to the verifier, avoiding priming it with the prior answer. Returned segment offsets are translated back to positions in the original recording. A local comparator reports textual, number-token and negation-token differences; it makes no medical judgment. Where original segment timing is unavailable, the UI receives an explicit indication that the original text is wider context rather than an aligned transcript window.

A verification candidate is a separate immutable `audio_verification` evidence record with **no clinical facts**. The original transcript remains intact. Explicit clinician acceptance, including an optional corrected excerpt, creates another immutable transcript evidence record linked to the original source, candidate and timestamps. Locally extracted findings remain **unconfirmed** and require their own clinician review. Acceptance is idempotent; different text cannot silently reuse a completed acceptance. Case revision checks reject stale proposals and acceptance requests.

## Configured verifier routes

The default `stronger` route prefers Groq **`whisper-large-v3`** when configured. Groq's official documentation lists this full model and a **$0.111 per audio hour** price, with a **10-second minimum billed duration**. The gateway applies this price to the selected clip, including that provider minimum. It never charges this operation as a whole-recording transcription. Public documentation does not establish the app's account access, privacy eligibility or clinical accuracy. Sources checked on 2026-09-05: [Groq speech-to-text documentation](https://console.groq.com/docs/speech-to-text) and [Whisper Large v3 model documentation](https://console.groq.com/docs/model/whisper-large-v3).

If the full-model route is unavailable and Cloudflare speech is configured and approved, the default route may use the configured Turbo service. Its response is explicitly labelled **repeat transcription fallback**; it is not presented as a stronger or independent medical verifier.

The `medical` route is selected only by an explicit request and uses enabled, configured Deepgram medical transcription. It does not run automatically because a full-model result remains uncertain. Every route retains clinician comparison and acceptance, and the medical service label does not establish medical correctness.

## API contract

`POST /api/v1/encounters/{encounter_id}/audio-verifications` uses multipart fields:

- `file`: original audio, matching the selected evidence hash.
- `source_evidence_id`: audio evidence from the same encounter and workspace.
- `start_seconds`, `end_seconds`: selected original recording positions.
- `uncertain_term`: 1–200 characters; `context`: optional, up to 2,000 characters.
- `consent`: `true`; `verifier`: `stronger` by default, or explicitly `medical`.
- A required `Idempotency-Key` header, plus the normal session and CSRF verification.

The response includes `verification_id`, source identity, `original_text`, `original_text_scope`, `candidate_text`, `window`, `segments`, `comparison`, `verifier`, `status`, `case_revision` and `usage`. A proposal initially has `status: pending_clinician_review`.

`GET /api/v1/audio-verifications/{verification_id}` retrieves the scoped proposal and its current acceptance status. `POST /api/v1/audio-verifications/{verification_id}/accept` accepts `{expected_case_revision, accepted_text}` with a maximum 8,000-character excerpt. The response includes the new evidence, unconfirmed findings and case revision. Neither endpoint changes the original transcript.

## Verification scope

`tests/test_audio_verification.py` covers source identity, consent and CSRF, tenant/encounter isolation, required idempotency, malformed and excessive windows, upload limits, provider gates, stale writes, separate immutable acceptance, source preservation and decoder time/format bounds. It includes a local synthetic WAV decode using ffmpeg/ffprobe when those binaries are available. `tests/test_providers.py` separately exercises the real billing ledger against mocked HTTP for full-model selection and pricing, short-clip billing, the provider minimum, labelled fallback, explicitly requested medical transcription, unusable-output charge waiver and execution-pause enforcement.

All model outputs in automated tests are synthetic mocks. No live inference or real patient recording was used to establish these software checks. Clinical speech accuracy, speaker identification and medical correctness remain unvalidated.
