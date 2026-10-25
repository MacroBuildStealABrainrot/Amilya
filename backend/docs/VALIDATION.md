# Release verification record

Final engineering verification for WoundCoPilot 6.0.0 on 2026-09-05. The public synthetic pilot is deployed at https://woundcopilot-1039151887397.us-central1.run.app. Live AI, medical transcription and billing remain paused at the owner's request. Real clinical data is not approved. Automated fixture results, actual browser observations and cloud checks are distinguished below.

## Final results

| Check | Observed result | Scope |
| --- | --- | --- |
| Complete backend suite | **245 passed in 76.14 seconds** | Final combined run includes custom reports, 27 short-audio tests, 14 photo-metadata tests and the corrected fallback replay. One Starlette TestClient/httpx deprecation warning; no failures. |
| Provider suite | **76 passed in 36.87 seconds** | Mocked HTTP with real isolated ledgers; includes replay without another request or charge. Included in the complete backend count. |
| Deployment suite | **14 passed in 0.03 seconds** | Plan and backup-capacity rules; no live cloud mutations from these tests. |
| Frontend | **30 passed across 7 files** | ESLint, strict TypeScript, Vite build and dependency tree passed. The final CSS contrast correction was rebuilt and verified in the actual browser. |
| Python dependencies | **43 audited dependencies, 0 reported vulnerabilities** | Saved advisory snapshot; no dependencies changed after this scan. |
| npm dependencies | **0 reported vulnerabilities**, including development dependencies | Final audit of the installed lockfile; no new dependency was added for the final workflows. |
| Prompt registry | **16 of 16 SHA-256 hashes verified**, version `2026-09-05.2` | UTF-8, LF-normalized content; no unlisted prompt file. |
| Public deployed API | **45 checks passed in 3.64 seconds** | Actual HTTPS requests without Google IAM authentication, PostgreSQL, two isolated synthetic accounts. Includes registration, protected cookies, CSRF/origin rejection, non-admin denial, measurement-only drafting, custom layout persistence, exact Unicode/whitespace save, replay, signing, amendments, zero-credit access, paused-provider response, logout and cross-tenant 404s. No inference or payment transaction. |
| Database | **Alembic `0003`, TLS 1.3, negative authentication checks passed** | Runtime role is non-superuser; 16 attempted immutable-table UPDATE/DELETE statements were denied. |
| Restart and restore | **Passed** | Real VM reboot preserved signed text/hash/amendment relationship. Encrypted off-VM backup was downloaded, authenticated-decrypted and restored to a fresh database: revision 0003, 26 tables, signed documents and all 12 audit events/head verified. Modified ciphertext failed decryption. |

Advisory scans, source tests and synthetic cloud workflows do not establish the absence of vulnerabilities or clinical effectiveness. The public API workflow ran on revision `woundcopilot-00002-ttc`; the final subsequent image changes the dark-mode canvas and desktop legal-link target widths. Final-revision public readiness, asset and signed-record persistence checks are recorded in `CLOUD_EXECUTION.md` and the machine-readable validation artifacts.

## Actual browser review

The rendered final frontend was inspected at 1440×900, 1280×800, 820×1180, 430×932 and 390×844. The inspected encounter screen had no horizontal document overflow. All measured visible button/input/select targets were at least 44×44 after increasing the desktop Privacy/Terms widths. A dark-mode rendering defect was caught: the canvas had a literal light colour while text used dark-theme tokens. The canvas now uses the theme variable; the actual browser resolves dark background RGB(23,29,25) and foreground RGB(228,237,230).

The custom report browser workflow preserved an exact 94-character draft containing blank paragraphs, two leading spaces and a trailing newline. Saving retained exact text; signing made the text and custom formatting fields read-only. Earlier authenticated local browser verification independently checked exact saved/signature hashes and the audit chain in the database. Mobile Clients navigation, account transition, pricing, custom report controls, source review and the anonymous deployed demo were inspected. Static contrast calculation covers 21 colour pairs; automated accessibility checks exclude contrast. This is not a complete WCAG conformance audit or a hardware microphone/background acceptance run.

Final frontend assets: `index-BBmdVr8s.js` 316.40 kB / **97.61 kB gzip**, and `index-q_TRhmtu.css` 34.73 kB / **7.48 kB gzip**. Build sizes are measured; no Lighthouse score, population field-performance result or guaranteed response-time commitment is asserted.

## Live provider and commercial checks

Before the owner paused execution, one synthetic 15.022-second Cloudflare Whisper Large-v3-Turbo recording completed in 2,030 ms of provider time (2,578 ms for the complete check). The returned transcript preserved the synthetic left-heel measurements, negation and pain score. Its normalized cost was 128 microUSD, or **0.128 credits**; the administrator was charged zero credits and actual vendor invoice cost was not returned. This is one smoke test, not a representative transcription or clinical benchmark.

All four required model IDs were present in the public OpenRouter registry: Muse Spark 1.3, GPT-5.6 Luna, GPT-5.6 Sol and Claude Fable 5.1. OpenRouter authentication succeeded but inference was blocked by its account-confirmation requirement and the account had zero credits. No successful text/vision quality benchmark or measured full-encounter AI cost is claimed. The owner explicitly requested configured placeholders with live processing paused. Stronger clip-verifier, medical fallback and model-routing behavior are fixture-tested; live comparative quality remains unmeasured.

All five supplied Stripe **test** prices were active and matched the expected CAD amounts/cadence: Essential24.99, Plus34.99, Pro49.99, Power75.00 monthly, and top-up8.99 once. No real purchase, provider top-up or live billing activation was performed. Business/contact requests are stored for owner review; no outbound email delivery is configured.

## Source and safety coverage

The clinical corpus contains **18 focused extraction scenarios** covering explicit millimetre conversion, missing or zero depth, absent units, negative dimensions, local laterality, pain versus exudate, conditional instructions, negation, caregiver attribution, bilateral sites, decimal dimensions and planned versus assessed care. These are synthetic source-handling examples rather than a representative population of wound diagnoses.

The integration suite covers **12 evidence combinations**: typed, manual, measurement, photo, document and audio individually; typed plus photo; photo plus measurement; document plus manual; audio plus document; audio plus photo plus manual; and all six together. The tests verify independent entry, preserved evidence, no required source sequence and no substitution of missing evidence with a default negative finding.

Additional tested controls include:

- Typed numeric dimensions and strict boolean presence, bounded pain and tissue values, rejection of malformed structures, contextual negation and uncertainty checks, exact source quotations, and verified unit conversion.
- Immutable fact revisions, explicit clinician approval and rejection, retained provenance, unidentified transcript speakers labelled **Speaker not verified**, separate wound identities, preserved repeatable observations and surfaced scalar conflicts.
- Approved-fact-only documentation, exact text and paragraph preservation, SHA-256 content binding, stale case detection, atomic signing, idempotent retries and explicit amendments that preserve signed versions.
- Tenant isolation, session and CSRF controls, safe audit metadata, and a contextual assistant that preserves confirmation states and cannot modify facts or documents directly.
- Actual multimodal image payloads, image metadata removal, photo finding limits, provider privacy and ZDR gates, unsupported parameter omission, exact quotation admission, bounded failure handling and source injection checks.
- An isolated real billing ledger with mocked HTTP: reservations before network work, concurrent replay protection, encrypted result storage, atomic settlement with replay persistence, charge waiver for unusable responses, and rejection of missing or malformed token usage.
- Locally verified audio duration despite false client duration, three configured speech fallbacks, independent documentation QA, and targeted review followed by reconciliation.
- Original-file-bound, timestamped audio verification limited to a 30-second decoded clip; configured full Whisper v3 selection, explicit medical-transcription selection and clearly labelled repeat fallback; separate candidate and clinician-accepted evidence with no silent transcript replacement or automatic clinical confirmation.
- Client-reported photo capture checks stored as nonclinical metadata. Automatic metrics require bounded numeric values; manual review preserves missing measurements and does not fabricate zero values.

Details and explicit clinical limits are recorded in [CLINICAL_VALIDATION.md](CLINICAL_VALIDATION.md). Exact quotation and schema validation cannot establish that an arbitrary free-text model interpretation is clinically correct. The tests do not constitute clinical validation, diagnostic accuracy evaluation, treatment-effect evidence, regulatory clearance or authorization to process real patient data. Live AI execution remains disabled by default, and no paid inference was used for these results.

## Synthetic concurrency results

The retained report `work/load-probe.json` was generated at **2026-09-05 05:43:33 UTC** on Windows with Python 3.11.9 and 12 logical CPUs. Each virtual user had a separate synthetic workspace and completed an eight-request encounter, manual measurement, mocked review, deterministic draft, exact save, sign and final-read workflow.

| Concurrent users | Completed workflows | HTTP requests | Errors | Request p50 | Request p95 | Total elapsed |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 8 | 0 | 9.58 ms | 41.93 ms | 0.136 s |
| 10 | 10 | 80 | 0 | 7.74 ms | 228.51 ms | 0.781 s |
| 50 | 50 | 400 | 0 | 241.07 ms | 834.32 ms | 3.538 s |
| 100 | 100 | 800 | 0 | 569.06 ms | 1,521.07 ms | 6.608 s |

All **161 workflows** ended with verified signed documents. All levels recorded **zero live provider calls, zero usage operations and zero charges**. The maximum single request at the 100-user level was **6,364.48 ms**.

The probe used temporary SQLite databases in WAL mode, a 30-second busy timeout, FastAPI TestClient, authentication dependency overrides and a 20 ms mocked AI review. It includes in-process client overhead. It does not measure real authentication, network transport, browser rendering, uploads, provider latency, billing contention, cloud infrastructure or PostgreSQL capacity. These local batches establish no production throughput or response-time commitment. Reproduction instructions and additional limits are in [LOAD_TEST.md](LOAD_TEST.md).

## Reproduction

From the repository root with the project Python environment:

```powershell
& '..\.venv\Scripts\python.exe' -m pytest -q --tb=short
& '..\.venv\Scripts\python.exe' -m pytest deployment/test_plan.py -q --tb=short
& '..\.venv\Scripts\python.exe' scripts/load_probe.py --output work/load-probe.json
```

The concurrency probe is a retained earlier run, not an additional run in this final verification. Its measured limits remain distinct from the actual HTTPS/PostgreSQL smoke checks above.
