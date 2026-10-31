# Render recording-to-report connection

The existing app recorded locally, but Render had no configured speech provider.
Add an opt-in OpenRouter Whisper Large V3 route using the key already connected.
There is no new account, credential, recurring bill, APK or recording deletion.
The original Cloudflare/Groq/Deepgram routes and their order remain unchanged.

`OPENROUTER_SPEECH_ENABLED` defaults false. With it enabled and a configured key,
the existing upload route uses `/audio/transcriptions` with JSON audio, an exact
reviewed model, verbose output and segment timestamps. Every call requires ZDR
and denies data collection. The app's normal consent, authentication, owner-only
allowance, expiry, media validation, ledger and replay rules still apply. The
existing one-day/$1 total key limit is not extended. No clinical-data setting or
production traffic changes.

Invalid response objects, HTTP 200 error envelopes, unexpected model identities,
invalid timestamps and oversized text fail without accepting a transcript.
Missing vendor cost stays unknown. Duration-based credit reservation uses locally
decoded duration and the highest reviewed endpoint rate: 1,850 microUSD/minute.
The actual supplied vendor cost is stored separately. The public endpoint catalog
on October 28 listed rates between $0.0000075 and $0.0000308333333333 per second.

An excerpt may be repeated through this service when no other verifier exists.
The response explicitly says that this is the same model, not a stronger or
independent medical check. Optional medical transcription remains disabled.

The fixed `audio-http-v4` startup test uses a locally generated artificial voice:
"Synthetic software test only. The dressing was changed. Pain score is two out
of ten." It is 5.312 seconds, mono AAC/M4A, generated with espeakng-loader 0.2.4
and ffmpeg. SHA-256:
`4d8d5e1eadac65c3660cb71c07c2f224c578994dbf67f5f348b446acef21246a`.
The server image needs no TTS dependency: only this fixed 27,138-byte recording is
included, as text-encoded fixture data, with a hash checked before use.

The private test creates a temporary fixture account in local SQLite, uses normal
native authentication and consented multipart upload, verifies a settled speech
operation on the exact provider/model, and generates a genuinely checked report
from that recording. Only a nonclinical once-only marker enters Render's durable
test DB. No patient recordings, photo data, owner credentials or acceptance are
used or generated in that database. Enable public speech only after this test
passes. This is server integration evidence, not physical Samsung validation.

Primary references reviewed October 28, 2026:

- https://openrouter.ai/docs/guides/overview/multimodal/stt
- https://openrouter.ai/api/v1/models/openai/whisper-large-v3/endpoints
- https://openrouter.ai/api/v1/models?output_modalities=transcription&zdr=true

The current public catalog included this model when filtered for ZDR endpoints.
Requests still enforce ZDR at runtime and fail closed if no eligible route exists.

## Verified live, October 28, 2026

Functional commit `cd9424969cab60056e5c8abfa0ac55e319c09b7f` passed CI run
`40779269994`: backend 934 passed / 1 skipped, Android build/unit/lint/flag checks,
and deployment safety checks. The Google Cloud job performed readiness checks
only; authentication, CLI setup and deployment steps stayed skipped.

- Automatic code deploy `dep-dbh7dbdg1s2s73fik98g` became live with public speech
  still off, preserving the previously enabled owner photo/text allowance.
- Private test deploy `dep-dbh7dnmhds7s73dtug2g` logged audio PASS and report PASS
  at 22:28:58 UTC and became live at 22:29:21 UTC. This exercised normal native
  login, consented multipart M4A upload, real OpenRouter transcription, extraction,
  and a genuinely checked report from the recording, in the disposable fixture DB.
- Only after those results, clear the once-only probe and set
  `OPENROUTER_SPEECH_ENABLED=true`. Deploy `dep-dbh7ejt9fdbs73865n90` logged owner
  eligibility READY at 22:30:10 UTC and became live at 22:30:30 UTC.
- After it became live, public config returned HTTP 200 with audio, photo,
  document and AI capabilities enabled. Signup remained closed, billing and
  clinical-data flags stayed off, and the same test cap/expiry remained in place.
  Health returned HTTP 200 / ok; an anonymous session request returned HTTP 401.

Keep the already supplied Test14 APK. No rebuilt APK was delivered, no installed
app or recording was deleted, no key was renewed or topped up, and no production
traffic moved. The allowance still ends October 28 at 20:50 Toronto time. Physical
Samsung behavior and a successful real-device wound assessment remain unverified.

A concurrent photo wording change at `d78defafe86534f293caf95535e38f5b6a737e70`
was discovered while saving this handoff. Its provider and prompt changes were
preserved together with the speech connection. The new fixed-input test version
`photo-note-v5` has its own once-only marker and reuses the normal native HTTP
photo/extraction/checked-report test after that prompt change. It does not reset
or delete earlier claims, renew the key or enable clinical data. A geometric
image must still yield a retake decision and no findings. This tests the updated
prompt's rejection of an unsuitable image, not a genuine wound or phone hardware.
