# Cancelled report recovery

Branch: `chatgpt/report-cancel-retry`, based on isolated-test source
`ad2d166f113076c9d64cf764b263d4915fc1aed1`.

## Change

A report step explicitly released with `provider_cancelled` and zero app charge
may resume under a deterministic child operation of the same parent request.
Released operations are never reopened. The existing account lock and unique
operation identity allow only one worker to reserve a child; a late original
worker cannot settle or save against that child. Successful encrypted checkpoints
are replayed. Running, unknown and storage-failed reservations stay blocked.

The existing limit remains three attempts per report step, including cancelled
attempts and ordinary failed attempts together. Once cancellations exhaust it,
the report wrapper refunds tracked app charges before returning a terminal error.
The existing HTTP route may instead return its clearly marked unchecked,
source-only fallback. No clinical check or fallback warning is removed.

A 409 from an overlapping retry no longer refunds a completed checkpoint while
another worker may still be using it. It does not assert that nothing was charged.
No database schema, phone code, request-key rule, workflow or server setting changes.
Photo uploads, transcription and extraction recovery are outside this fix.

## Fake-data proof

Six regression cases failed against the unchanged base. Those six pass after the
fix. The expanded suite has 21 cases using the real provider/accounting functions
with an intercepted provider and a temporary SQLite database. It covers first-
and second-step cancellation, same-key completion/replay, overlapping retries,
late-worker fencing, independent worker-thread contention, combined timeout/cancellation limits, exactly one terminal
refund, all five report-stage types and protection of unrelated operation states.

A cancelled first or second step followed by success makes three fake provider
calls in total and stores two provider-stage results. Those are not three finished
reports. The completed response then replays with no additional call, charge or
refund. App balances and held credits are checked separately. Provider charges
for a cancelled real request can remain unknown; an app-credit refund is not a
claim that the provider refunded its fee.

These tests simulate cancellation directly; they do not disconnect a real phone
or test an actual network gateway. Samsung recovery is not tested. The observed
Samsung request has not been conclusively linked to cancellation. No production
or test-server deployment, merge, paid AI call or installer is part of this change.
