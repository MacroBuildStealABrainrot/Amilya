# Security implementation and residual work

## Implemented controls

- Argon2 password hashes; random session cookies are stored only as hashes in the database. HTTP-only SameSite Strict cookies, configurable Secure flag, expiration, revocation and membership enforcement.
- Per-session CSRF with Origin binding for authenticated mutations; current legal-version acceptance required before clinical operations. No administrator flag in signup/profile schemas and no email-derived promotion.
- Workspace predicates on clinical, document, commerce and administrative access. Case and fact revisions prevent stale writer overwrites. Signatures bind exact saved content and case revision.
- Append-only clinical versions, separate signatures and a serialized workspace audit hash chain. Version 2 hashes bind event ID and timestamp as well as user, sequence, action, resource, metadata and previous hash. Older version 1 events remain explicitly versioned for verification.
- Shared-database rate windows, request/upload/text limits, strict evidence schemas, photo decoding limits and metadata stripping. PDFs are extracted in a separate process with a wall timeout and Linux memory/CPU limits; active PDF content is not executed.
- Provider scope prompts, local input/output checks, explicit PHI approvals and zero-retention routing. Providers receive no account/billing authority. No source content or arbitrary provider payloads is accepted into usage metadata.
- Credit reservations before network calls; idempotent settlement/grants; row locks for overspend races; Stripe signature, livemode, API version, price and replay validation. Failed model output does not consume user credits.
- Same-origin CSP, no framing, no MIME sniffing, no-store API responses, production HTTPS/HSTS and explicit hosts. Logs include route templates/status/latency, not request bodies, query strings, tokens or clinical content.
- AES-GCM device recovery with per-owner keys, associated-data binding, completed IndexedDB transactions, ordered writes and expiry. Server provider replay results use authenticated encryption with a separate derived key and 24-hour validity.
- Secret/build ignore files exclude environment credentials, databases, recordings, caches and local private directories. The container runs as a non-root user. PostgreSQL remains on private IP with TLS verification and a distinct non-superuser runtime role.

## Verification and limits

Regression tests exercise cross-workspace identifiers, missing membership, session expiry/revocation, missing/wrong CSRF, malicious origins, legal re-consent, privilege injection, audit timestamp tampering, stale revisions, wrong hashes, failed signatures, webhook races, provider malformed output and concurrent credit spending. See VALIDATION.md for final counts and dependency scan results.

Browser recovery encryption protects stored bytes and account separation in the app; it does not protect against an attacker executing JavaScript in the same origin or an unlocked compromised device. Raw source content is intentionally excluded from logs, but authorized clinical database records necessarily contain the information entered for that workspace.

Production activation requires a real operator to approve identity management, provider agreements, regional processing, retention, breach procedures, backup schedules, restoration, access reviews and incident response. MFA/SSO and out-of-band account recovery are not implemented in this release. Do not market those controls as present. Account recovery can be handled through the operator's verified support process; never create an email-matching privilege exception.

The free-tier topology is suitable for bounded synthetic evaluation and early operational validation. It has one database instance and no high availability. A shared free allowance and a low Cloud Run instance cap do not guarantee zero bills or availability under abuse. Follow the deployment report before widening access or admitting real clinical data.
