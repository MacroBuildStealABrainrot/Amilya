# Amilya routing and incident audit — 25 October 2026

## Incident evidence boundary

Photo Assessment returned HTTP 502 for request `2a9bc999-5629-43c8-9bca-b5e273febe67`, approximately 17:30–17:32 America/Toronto (21:30–21:32 UTC).
The task browser returned **Site Unavailable** for Google Cloud Console, including after one reload. No production log, revision, environment value, provider credit balance, or API key was read. The incident's root cause is **unconfirmed**. Source defects below must not be represented as the cause of that specific request.

Production diagnosis remains read-only. Query the named Cloud Run service with:

```text
resource.type="cloud_run_revision"
resource.labels.service_name="woundcopilot"
resource.labels.location="us-central1"
timestamp>="2026-10-25T21:25:00Z"
timestamp<="2026-10-25T21:40:00Z"
"2a9bc999-5629-43c8-9bca-b5e273febe67"
```

Project: `woundcopilot-20260905`. If no match, search the same time window for HTTP 502 and correlate its trace/revision. An app request ID is not necessarily a Cloud trace ID. Existing application diagnostic records may have finer failure categories than historical stdout. Do not export source bodies, patient identifiers, credentials, or environment secrets.

## Public documentation verification

Public documentation was reviewed on 25 October 2026. This confirms published identifiers and features, **not access for this particular API key, a ZDR-compatible endpoint at request time, or a successful live inference**.

| Role | Candidate default / OpenRouter ID | Direct vendor ID |
|---|---|---|
| Extraction | `openai/gpt-6-luna` | `gpt-6-luna` |
| Documentation, routine review, vision | `openai/gpt-6.1-sol` | `gpt-6.1-sol` |
| High-effort review and reconciliation | `openai/gpt-6-astra` | `gpt-6-astra` |
| Independent challenger | `anthropic/claude-sonnet-5.5` | `claude-sonnet-5-5` |

GPT-6.1 Sol and Sonnet 5.5 accept image inputs. The differing Sonnet punctuation above is intentional: the gateway and direct vendor IDs differ. Do not substitute one for the other.

Sources:
- https://developers.openai.com/api/docs/models/gpt-6.1-sol
- https://developers.openai.com/api/docs/guides/latest-model
- https://platform.claude.com/docs/en/models/sonnet-5-5/overview
- https://openrouter.ai/openai/gpt-6.1-sol
- https://openrouter.ai/anthropic/claude-sonnet-5.5
- https://openrouter.ai/docs/guides/routing/provider-selection

The candidate sends JSON-schema response format; it omits temperature, top-p and top-k. It uses supported low/medium/high effort, not `none` for Sol/Astra. Images stay in a multimodal array after metadata stripping. OpenRouter requests retain `zdr=true`, `data_collection=deny`, and `require_parameters=true`. A missing compatible endpoint fails closed; these controls are never relaxed as a fallback.

## Source corrections

- Configured text-model settings were bypassed by fixed router IDs. The candidate preserves automatic effort selection and uses configured task models, escalating high-effort work to the configured frontier model.
- Deep Review previously skipped independent review when no narrow trigger matched. Every Deep Review now obtains a challenger from a different model developer, with source evidence rather than the first model's conclusions. Misconfiguration is rejected before paid work.
- Unexpected provider-reported model identities are rejected without accepting clinical findings or charging the user. Approved reported identities and confirmed/unreported/mismatch status are recorded separately from requested models. Unrecognized model strings are not persisted. Snapshot aliases must be explicitly reviewed before acceptance; they are not guessed.
- All structured AI stages have an absolute deadline, including retries and backoff. Timeout settlement still waives the customer charge; unknown vendor cost remains unknown.
- Invalid configuration messages now map to safe application error codes rather than a dictionary lookup exception.
- Allowlisted structured stdout records correlate provider attempts and validation outcomes by application request ID, even when the diagnostic database is unavailable. No bodies, image data, keys, patient names, raw provider errors, or arbitrary model strings are emitted.
- CI no longer runs an emulator. It builds the APKs, compiles instrumentation, runs all configured app JVM tests plus wear-contract tests, and treats Sandbox/Pilot lint as a required check. A separate 1 MB RAM disk activates the real storage-full regression without filling the runner filesystem. The legacy standalone storage helper now preserves failure exit codes; synthetic shell checks verified success and failure propagation.

## Fallback and approval limits

Text/vision model fallback is not configured. OpenRouter may select an eligible host for the **same model** under the retained privacy requirements. One bounded short 429 retry remains. Photo schema recovery retries the original image once with stricter instructions; it is not an independent second model. Speech fallback remains the existing Cloudflare/Groq/optional Deepgram policy. No provider credentials were available locally, so live provider integration is unverified.

Published cache-read prices differ from the historical billing-normalization registry. That registry is an application billing policy, not live provider pricing; it has not been silently changed. Review normalization and cache-write accounting before approving a billing-policy change. Live balances and subscriptions were not touched.

No backend deployment was performed. Installing a new Pilot APK cannot apply server-side fixes to the existing Cloud Run service. Production approval, a reviewed rollout/rollback plan, synthetic live-provider tests, and clinical/privacy approval remain separate requirements. Clinical Assist stays deferred. Physical Samsung validation is required for long recordings, interruptions, PIN/photo-return behavior, notification controls, recovery, and installation/signature compatibility.
