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

A temporary 409, 502, 503 or 504 no longer refunds a completed checkpoint while
the report remains recoverable. Only a durable terminal failure triggers the
whole-report app-credit refund. An internal `TerminalReportError` distinguishes
that outcome from a status code or a refused later request. The legacy phone
signal is attached only after the refund transaction succeeds; if accounting
is unavailable, no ended signal is sent. Measured provider costs are preserved.

The quick-report HTTP route also preserves temporary errors instead of turning
them into a completed source-only response. Otherwise the phone could forget a
still-recoverable request and its already-paid checkpoint. Confirmed terminal
failures retain the existing clearly labelled unchecked source-only fallback.
Manual drafting remains available. This changes when a backup draft is offered,
not the clinical prompts, models, evidence or checking rules.

No database schema, phone code, request-key rule, workflow or server setting changes.
Photo uploads, transcription and extraction recovery are outside this fix.

## Fake-data proof

Six regression cases failed against the unchanged base. Those six pass after the
fix. The initial expanded suite had 21 cases using the real provider/accounting functions
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

## Follow-up review proof

Against unchanged `97a69d0`, both new retryable-503 regression cases failed:
the successful draft's app charge had been refunded. One uses a fake connection
error after the cancelled check; the other uses real reserve/release transitions
to force the explicit retryable-503 race, rather than a busy 409. With the fix,
the draft checkpoint stays charged once and replayed, with zero refunds. A later
successful check completes the same request without regenerating that draft.

A separate HTTP regression failed when the old route catch was restored (it
returned 200 source-only instead of 503). With the fix, the actual quick-report
route returns 503, retains the checkpoint, and later returns a checked report
for the same request. A terminal-refund test also verifies that refund failure
does not emit the ended signal and that app-credit reversal preserves the fake
provider's measured cost. Existing timeout tests now require no refund before
the third, terminal failure and require a refund after that failure.

The independent-thread contention test now runs for both SQLite and PostgreSQL.
PostgreSQL is the existing throwaway CI service, restricted to loopback port
55432, database `wcp_test`, with a unique temporary schema. No Render or live
database is contacted. CI must fail, not skip, if this service is unavailable.
Expected closed fake counts are one completed report response, three AI calls
(one cancelled and two successful stages), two successful stage charges, zero
refunds, one retry-child reservation, one busy loser and zero remaining holds.
These are gateway response counts, not saved clinical-document records. The
PostgreSQL result must be read from the actual GitHub job before claiming it ran.

Build 206 / `41362104150` used `.github/workflows/amilya-sandbox-ci.yml`
(`amilya-sandbox-ci`), not either older proof workflow. It uploaded exactly
`amilya-build-evidence` and `amilya-backend-evidence`. The build evidence archive
was independently inspected: 80 entries, no APK, AAB, keystore or JKS. The
follow-up uses the same unchanged workflow; its actual results and uploads must
be checked separately. Nothing is deleted.

These tests simulate cancellation directly; they do not disconnect a real phone
or test an actual network gateway. Samsung recovery is not tested. The observed
Samsung request has not been conclusively linked to cancellation. No production
or test-server deployment, merge, paid AI call or installer is part of this change.
