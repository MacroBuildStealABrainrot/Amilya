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
The image needs no TTS dependency: only this fixed 27,138-byte recording is
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
