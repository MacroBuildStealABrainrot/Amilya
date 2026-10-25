# Architecture

The browser and API share one origin. A compiled React/TypeScript interface is served by FastAPI; HTTP-only cookie sessions, stable per-session CSRF tokens and membership checks protect authenticated routes. PostgreSQL holds workspace identities, evidence provenance, fact revisions, document versions, audit events and commercial ledgers. SQLite supports local evaluation, not Cloud Run production storage.

## Clinical state

Clients own distinct wound references. Encounters own evidence and logical facts. Each review/edit creates an immutable fact revision; an edited value never inherits an old confirmation. AI findings begin unconfirmed, while structured manual entry is explicitly clinician-attested. Every evidence source remains independently usable, and unavailable providers cannot block manual entry or the exact-text editor.

Source quotations must match the admitted text. Local typed schemas reject invalid measurements, nonfinite numbers, unsupported booleans and unitless measurement promotion. Source admission excludes hypothetical/uncertain/historical claims from becoming current measured facts. Visible image suggestions cannot establish pain, odor, warmth, perfusion, infection, pressure stage or numeric measurements. Conflicting current values stay unresolved and excluded from the approved documentation projection.

Encounter revision compare-and-swap detects concurrent writers. Document save accepts and hashes the exact submitted string, including blank lines and indentation. Signing verifies the saved hash and current case revision, then appends a signature. Amendments are new immutable versions referring to the latest signed parent and an explicit reason. Evidence changes after saving require a new review/save before signing.

## Provider boundary

OpenRouter receives real multipart message arrays for images, task-specific prompts and a structured JSON response schema. A local validator enforces the output independently of the model. The model receives no executable tools, secrets, account administration or billing authority. All configured OpenRouter calls require zero-retention/no-training routing. Each speech provider has its own clinical-data approval check; fallbacks cannot bypass it.

Normalized prices and prompt hashes are versioned. A provider attempt reserves credits in a short transaction, calls the provider without a business write lock, then atomically settles usage and stores an encrypted 24-hour replay result. Concurrent duplicate requests cannot issue duplicate provider calls. Failed/unusable outputs waive user charges while retaining observed provider/normalized costs; unknown vendor charges remain null and require reconciliation. A timeout cannot be represented as a successful inference.

Routine review uses a lower-cost model. Material conflicts, missing vascular context for documented compression, or documented infection concerns trigger targeted escalation. Deep review can add an independent challenge, then reconcile interpretations against source facts. Repeated medications, history and other repeatable evidence do not alone trigger a contradiction. Generated documents undergo a separate source-faithfulness comparison before replacing a source-based draft. These checks support review and do not provide clinical validation.

Speech routes Cloudflare Whisper, then Groq Whisper, then optional Deepgram Medical, subject to configuration and privacy approvals. ffprobe validates actual container duration; client duration never determines billing. Audio remains a source with uncertain speaker attribution where it cannot be established. Raw audio and image binaries are not accumulated in PostgreSQL.

## Commercial state

The credit ledger separates monthly plan funds, top-ups and reservations. Integer microUSD avoids floating point balance drift. Database locks serialize reservations; settlement and webhook identities prevent duplicate charges and grants. Paid-cycle boundaries determine resets. Cancellation is terminal for that subscription ID; old snapshots cannot resurrect it. Explicit platform admins bypass subscription/credit limits but still produce cost records and remain subject to scope, privacy, validation and rate limits.

## Deployment boundary

Cloud Run handles requests and external-provider orchestration; it runs no model weights or GPU workloads. A private PostgreSQL VM supplies durable relational storage. The free-tier-oriented topology uses one eligible e2-micro, a total of 30 GB standard disks, no external VM IPv4, Private Google Access and Cloud Run Direct VPC egress. Secrets are mounted from Secret Manager with scoped runtime identities. All clinical-data and live-provider execution gates remain off for synthetic validation.

## Deliberate limits

This release is not an EHR, credential verification system or autonomous treatment service. MFA/enterprise SSO, organization-wide role administration, automated email delivery, finalized operator/legal identity, external clinical validation, regional privacy agreements and production disaster recovery operations require launch work appropriate to the actual organization. The existing implementation and its measured limits are documented separately from those approvals.
