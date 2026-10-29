# Renewed synthetic test access, October 28, 2026

Hazim approved renewing test access through November 4 while keeping the same
US$1 overall total, and running the high-versus-medium photo comparison without
changing the app's default photo setting. The original one-day key has expired.
OpenRouter's UI says key expiration cannot be edited; replacement is necessary.
The existing key shows 35.448594% of $1 used ($0.35448594). A replacement key is
limited to $0.64 total with no reset, so combined maximum is $0.99448594, below
the original $1 approval. Existing production/default keys are not used or changed.
No top-up or subscription is purchased.

The renewed hard app boundary is Unix 1793843400, November 4 at 20:50 Toronto
(November 5 at 01:50 UTC). The actual activated deadline must be earlier than
the replacement key's verified expiry. Activation accepts at most seven days
within this hard boundary; the provider metadata guard now rejects key limits
greater than $0.64, any reset, management credentials, exhaustion or invalid
expiry. Owner binding, current legal acceptance, closed signup, clinical-data
and billing exclusions remain unchanged. No new owner account or acceptance is
created. No patient-data permission or production traffic change is included.

The prepared once-only `photo-cost-v8` compares identical generated wound/shapes
inputs with current high and candidate medium reasoning, normal native HTTP,
privacy enforcement, qualitative validation and actual provider cost metadata.
No public photo reasoning setting changes. Because a concurrent approved backup
model feature was added, the comparison also requires every recorded attempt to
use the intended Sol model; any other model invalidates a same-model comparison.
Missing cost stays unknown, and field coverage is not clinical equivalence.

A concurrent provider diagnostic accepted any label-shaped upstream name. Change
that log field to a fixed list of known providers so an invented label cannot
leak clinical words. Preserve the backup feature and all other current changes.
Regression tests cover this exclusion, renewed multi-day eligibility, the smaller
remaining budget, alternate-model detection and all previous time/privacy gates.

The secret replacement key must stay only in provider/Render secure storage.
It must never be committed, printed, placed in a report or retained as an artifact.
The original recorder, Test14 APK and installed recordings are untouched.

Provider UI confirmed exactly one replacement key, $0.64 TOTAL, no reset, expiry
November 4, 2026 at 20:25 EST. The server deadline is conservatively November 4
at 20:20 EST (Unix 1793841600), before that expiry and the approved hard boundary.
A secret-only temporary transfer was mode 0600, never committed or displayed,
and removed immediately after Render secure storage accepted it. No secret is
retained in a report, screenshot, project file or clipboard. The provider's email
verification completed; visible key creation and masked list state confirmed it.

Local checks passed before incorporating the concurrent HTTP 429 test correction.
Preserve that correction: HTTP 429 can use the existing bounded same-model retry,
so it is excluded from the single-request assertion. Existing rate-limit tests
cover this behavior; no retry behavior is changed.
