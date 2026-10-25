# Plan: stuck AI requests and stuck credit holds (revision 3, built around your approved decision 5)

Status (1.8.5-test10): **BUILT FOR DEVELOPMENT, OFF BY DEFAULT** (`AI_LEASES_ENABLED=false`). See "Built in 1.8.5-test10" at the end. Live billing and production databases are unchanged.

Original status: **PLAN ONLY. Nothing here is applied.** The SQL rules are tested on a throw-away PostgreSQL 16 server with made-up numbers.
The application code is not written. `backend/server/billing.py` and `providers.py` are untouched. Needs your approval before any of it is built.

## Your decision, in plain words (approved)
If the AI provider answered but the result was lost before it was safely saved and made retrievable, **Amilya absorbs the provider cost**.
The customer is **not charged** for that lost attempt, and a retry is **never charged twice**. You also asked for retry limits, idempotency,
cost monitoring and protection against duplicate charges. Revision 3 adds those four (sections below). Approval covers this plan and its tests only;
**applying anything to the live backend or billing still needs your separate approval.**

## What changed from the first plan (test5/test6)
The first plan stored the provider's cost *before* charging, and a cleanup job later charged the stored amount. That would charge a
customer for an answer they never received, and a retry would charge again. It has been replaced. The old files are kept, labelled
superseded, in `verification/prototype/superseded-lease-v1/`.

## The new rule: charge and save in one step
* The customer is charged **only in the same database transaction that saves the result**, and only by the worker holding the newest ticket.
* Crash **before** that transaction: nothing saved, nothing charged. The held credits are given back later by the cleanup job.
* Crash **after** it: the saved result stays. The customer's retry is told "already saved", gets that result back, and makes **no new provider call and no new charge**.
* A slow or "zombie" worker holding an old ticket is refused: it saves nothing and charges nothing.
* The cleanup job **never charges**. It only gives credits back for requests that went silent.

## New in revision 3: the four protections you asked for
All four are in `verification/prototype/lease_schema.sql` and are tested (section "Test results").
1. **Retry limit.** Each request may start at most `max_attempts()` provider attempts (default **3**, adjustable per database). The fourth try is
   answered `attempts_exhausted`: nothing is reserved, no credits are held, nothing is charged, and no provider call happens. This caps how much we can absorb on one stuck request: at most 3 x one provider cost.
2. **Idempotency.** Every request stores a fingerprint (`request_hash`). The same key with the same request gets the same answer (`in_progress` while it runs, `saved` with the stored result afterwards). The same key with a **different** request is refused (`conflict`) and nothing changes.
3. **Duplicate-charge protection, enforced by the database itself, not only by the code.** One charge line per request (primary key). One saved result per request (primary key). A request can be marked settled once (a CHECK rule: `settle_count <= 1`). Even a buggy or repeated commit cannot charge twice.
4. **Cost monitoring.** Every provider attempt is recorded (`provider_attempts`, with its cost in millionths of a dollar). A view `absorbed_attempts` lists attempts that cost us money but never became a saved result. `lease_cost_report` totals attempts, absorbed attempts and cost, customer charged, and requests that ran out of retries. `lease_cost_alert(window, limit)` returns true when the absorbed cost in the chosen time window is above a limit. **The limit is a business number I have not set; see "Decisions".**

## Lease basics (unchanged idea)
A worker must "borrow" a request before calling the provider; the loan lasts 120 s and is renewed while the worker is alive. Each borrow
gets a higher ticket number. If the worker dies the loan runs out, and the retry (or cleanup) can take over. Use the database clock.

## Database changes (additive only)
* On `usage_operations`: `lease_owner`, `lease_token`, `lease_expires_at`, `last_heartbeat_at`, `attempts` (all nullable or defaulted). **No `result_micros` any more.**
* The saved result must be written in the same transaction as the charge. In the app that is the row that holds the finished AI result
  (the test uses a small `results` table with one row allowed per request). **Open question for the real code:** if the real result
  is stored somewhere that cannot join this transaction, the charge must still be made at the moment of saving; this has to be checked against the real tables before building.
* Rollback: stop using the columns (flag off); leave them in place.

## The rules (all tested; see `verification/prototype/lease_schema.sql`)
1. **Reserve**: first try, or a retry. Answers `reserved`, `saved` (use the stored result), `in_progress`, `insufficient`, `conflict` (same key, different request) or `attempts_exhausted` (retry limit reached). A previously released request may be reserved again.
2. **Claim**: one conditional UPDATE; exactly one winner; returns the new ticket.
3. **Heartbeat**: extends the loan only for the current ticket.
4. **Commit** = save result + charge, one transaction, current ticket only. Answers `committed`, `fenced`, `already_saved`, `lost`.
5. **Release by worker**: the provider failed; current ticket only.
6. **Cleanup job**: releases requests whose loan ran out and that were silent for the quiet period (10 minutes in production). Raises the ticket so a late worker is locked out. Never charges.
7. All rules take the account row first, then the request row, so two sessions cannot deadlock.

## Who pays for a lost attempt
Under your approval, **we** pay the provider and the customer pays nothing. In the latest stress run (seed 20261024): **122 provider calls, 60 saved results, 62 absorbed**
(98,009 millionths of a dollar of *made-up* unit costs; the customer was charged 167,105 for the 60 saved results). That mix is **deliberately extreme**
(many attempts were forced to fail); real cost depends on how often crashes happen. The retry limit caps one stuck request at 3 attempts, and the alert tells you when the total is unusual.

## Test results (PostgreSQL 16.15, separate sessions at the same time)
`sh verification/run_lease_pg_test.sh` starts a temporary server, runs `lease_pg_test.py`, and deletes the server: **62 of 62 checks pass** (`verification/lease_pg.log`). This is SQL-level only.
* 50 sessions racing for one request: exactly one winner. Heartbeat, takeover only after expiry, a stale ticket refused on save, renew and release.
* **Provider answered, server died before saving**: not charged, credits back, retry succeeds, charged once, 2 provider calls (1 absorbed).
* **Saved, reply lost**: retry gets "saved", same result, no new claim or charge; the cleanup job leaves it alone.
* Provider failure, late commit after release, not enough credit, charge capped at the hold. Commit racing the cleanup job on 40 expired requests: each ends exactly one way.
* Random stress: 120 retrying clients on 60 requests, 24 sessions, crashes, slow workers, lost replies: no deadlocks, credits conserved, at most one result and one charge per request, no charge without a result, customer paid exactly what the saved results say, **absorbed attempts = provider calls - saved results (62 = 62)**, every absorbed attempt has a cost on record and none was billed.
* Retry limit: attempts 1-3 allowed, 4th refused with nothing held or charged. Idempotency: same key same request, same key different request (`conflict`), many retries after saving = one charge. Duplicate protection: a second charge line, a second settle, a second result are each refused by the database. Cost alert fires above the limit, stays quiet below, and looks only at the chosen window.
* Deliberate-mistake checks (the tests must notice): no ticket check (6 fail), no "released" check (4), no retry limit (4), no idempotency conflict (1), charge line not written (8), double settle allowed (2), absorbed cost not counted (3). All caught.

## Still not done (so this is NOT ready for approval to change billing)
1. The real code in `billing.py`/`providers.py` behind a flag `AI_LEASES_ENABLED` (default off). Not written, on purpose.
2. A test against the real table definitions and migration.
3. HTTP-level tests with several FastAPI instances and a forced instance kill during a real provider call.
4. Cloud SQL / network behaviour and a report-only dry run of the cleanup job.
5. Confirming that the real result save can share the charge's transaction (see above).
6. Choosing the real alert limit and where the alert is sent; a dashboard or email for it is not built.

## Safe rollout (unchanged)
Test copy of the database first; code behind the flag, default off; sandbox deployment with the cleanup job in report-only mode for a week; production only with your written approval; rollback by switching the flag off (credits already released stay released).

## What this plan does not touch
Pricing, plans, top-ups, the ledger format, data-retention rules and production data.

## Decisions I need from you (for this plan)
1. The **absorbed-cost alert limit** (for example, per day) and who gets told.
2. Whether `max_attempts` should stay at 3.
3. Separate written approval before any of this is applied to the real backend or billing.


## Built in 1.8.5-test10 (development only, flag OFF by default)
* Approved by you on 2026-10-24 for an isolated development environment. Not deployed; production billing untouched.
* **No database change.** The lease lives in the existing `usage` field of `usage_operations`; release uses the existing `released` status.
* Code: `backend/server/billing.py` (lease start, heartbeat, stale check, release that never charges, report-only batch job, absorbed-cost report),
  `backend/server/providers.py` (lease + heartbeat around each AI call; a released hold cannot be charged or saved; a retry after a lost
  attempt makes a new attempt within the limit of 3 in total), `backend/scripts/release_stale_holds.py` (report-only unless `--apply`).
* Tests: `backend/tests/test_stuck_credit_recovery.py`, run on SQLite **and** a real PostgreSQL 16.15 server (16 passed, including 12
  workers racing to release one hold: exactly one wins; and settle-vs-release races: each ends exactly one way, credits conserved).
  Log: `verification/stuck-credit-sqlite-and-postgres-test10.log`. A deliberate mistake (every hold treated as stale) is caught.
* Simpler than the plan in one way: charging and saving the result were already one database transaction in the real code, so the
  "fencing" is the existing rule that a released hold can never be settled.
* Still open (your decisions): the absorbed-cost alert limit and who is told; a week of report-only running in a test deployment.
