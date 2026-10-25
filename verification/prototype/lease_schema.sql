-- PROPOSED lease rules v2 (decision 4), plain SQL for PostgreSQL. TEST DATABASE ONLY. Not applied anywhere; not part of the app.
-- Table/column names mirror the idea of usage_operations + the account row + the saved result row; they are NOT the live schema.
--
-- The customer is charged ONLY in the same database transaction that saves the result, and only by the worker that still holds
-- the current ticket. So:   * a crash before that transaction commits  -> nothing saved, nothing charged, the hold is released later;
--                           * a crash after it commits (response lost)  -> the saved result is replayed on retry, nothing charged again;
--                           * a slow or "zombie" worker with an old ticket -> refused, saves nothing, charges nothing.
-- Lock order everywhere: account row first, then the request row (so two sessions can never deadlock each other).
DROP TABLE IF EXISTS provider_attempts, charges, results, ops, account, tokens CASCADE;
CREATE TABLE account(id int PRIMARY KEY, available bigint NOT NULL CHECK (available >= 0), held bigint NOT NULL CHECK (held >= 0), charged bigint NOT NULL CHECK (charged >= 0));
CREATE TABLE ops(
  id text PRIMARY KEY, status text NOT NULL DEFAULT 'reserved' CHECK (status IN ('reserved','settled','released')),
  held bigint NOT NULL, lease_owner text, lease_token bigint NOT NULL DEFAULT 0, lease_expires_at timestamptz, last_heartbeat_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  charged_micros bigint NOT NULL DEFAULT 0, settle_count int NOT NULL DEFAULT 0, release_count int NOT NULL DEFAULT 0, attempts int NOT NULL DEFAULT 1,
  request_hash text,                                   -- idempotency: what the client asked for under this key
  CONSTRAINT one_settle_at_most CHECK (settle_count <= 1),                      -- the database itself refuses a second charge
  CONSTRAINT released_not_more_than_tried CHECK (release_count <= attempts));
-- stands in for the saved result (the Evidence row in the real app). At most one per request.
CREATE TABLE results(op text PRIMARY KEY REFERENCES ops(id), token bigint NOT NULL, body text NOT NULL, saved_at timestamptz NOT NULL DEFAULT clock_timestamp());
-- ONE charge line per request, ever (primary key). A bug that tried to charge twice would fail here, loudly, and roll back.
CREATE TABLE charges(op text PRIMARY KEY REFERENCES ops(id), amount bigint NOT NULL CHECK (amount >= 0), at timestamptz NOT NULL DEFAULT clock_timestamp());
-- Cost monitoring: every call made to the (paid) AI provider, with what it cost us. Written by the worker right after the provider answers,
-- BEFORE the commit, so a lost attempt still shows up as money we spent.
CREATE TABLE provider_attempts(op text NOT NULL, token bigint NOT NULL, cost_micros bigint NOT NULL DEFAULT 0, at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE tokens(worker int, op text, token bigint);          -- what each simulated worker believes it holds (stress test only)
-- Test knobs (seconds). Production values would be 120 s lease and 600 s quiet period.
CREATE OR REPLACE FUNCTION lease_secs() RETURNS double precision LANGUAGE sql STABLE AS $$ SELECT coalesce(nullif(current_setting('app.lease_seconds', true), ''), '120')::double precision $$;
CREATE OR REPLACE FUNCTION grace_secs() RETURNS double precision LANGUAGE sql STABLE AS $$ SELECT coalesce(nullif(current_setting('app.grace_seconds', true), ''), '600')::double precision $$;
-- Retry limit: how many times one request may be attempted (first try included) before the app stops spending money on it.
CREATE OR REPLACE FUNCTION max_attempts() RETURNS int LANGUAGE sql STABLE AS $$ SELECT coalesce(nullif(current_setting('app.max_attempts', true), ''), '3')::int $$;

-- RESERVE (first try or retry). p_hash = a fingerprint of the request (same idempotency key + different request = conflict).
-- Returns: reserved | saved (a result already exists: replay it, do not call the provider) | in_progress | insufficient |
--          conflict (key reused for a different request) | attempts_exhausted (retry limit reached: no more paid attempts)
CREATE OR REPLACE FUNCTION lease_reserve(p_op text, p_amount bigint, p_hash text DEFAULT NULL) RETURNS text LANGUAGE plpgsql AS $$
DECLARE a account%ROWTYPE; o ops%ROWTYPE;
BEGIN
  SELECT * INTO a FROM account WHERE id = 1 FOR UPDATE;
  SELECT * INTO o FROM ops WHERE id = p_op FOR UPDATE;
  IF FOUND THEN
    IF p_hash IS NOT NULL AND o.request_hash IS NOT NULL AND o.request_hash <> p_hash THEN RETURN 'conflict'; END IF;
    IF o.status = 'settled' THEN RETURN 'saved'; END IF;
    IF o.status = 'reserved' THEN RETURN 'in_progress'; END IF;
    -- released earlier (an attempt was lost): a retry opens a fresh attempt on the same request, up to the limit
    IF o.attempts >= max_attempts() THEN RETURN 'attempts_exhausted'; END IF;
    IF a.available < p_amount THEN RETURN 'insufficient'; END IF;
    UPDATE account SET available = available - p_amount, held = held + p_amount WHERE id = 1;
    UPDATE ops SET status = 'reserved', held = p_amount, lease_owner = NULL, lease_expires_at = NULL, last_heartbeat_at = NULL,
                   attempts = attempts + 1, created_at = clock_timestamp(), request_hash = coalesce(request_hash, p_hash) WHERE id = p_op;
    RETURN 'reserved';
  END IF;
  IF a.available < p_amount THEN RETURN 'insufficient'; END IF;
  UPDATE account SET available = available - p_amount, held = held + p_amount WHERE id = 1;
  INSERT INTO ops(id, held, request_hash) VALUES (p_op, p_amount, p_hash);
  RETURN 'reserved';
END $$;

-- CLAIM: one atomic conditional UPDATE. Returns the new ticket number, or NULL if somebody else holds a live lease.
CREATE OR REPLACE FUNCTION lease_claim(p_op text, p_worker text) RETURNS bigint LANGUAGE sql AS $$
  UPDATE ops SET lease_owner = p_worker, lease_token = lease_token + 1,
                 lease_expires_at = clock_timestamp() + make_interval(secs => lease_secs()), last_heartbeat_at = clock_timestamp()
   WHERE id = p_op AND status = 'reserved' AND (lease_owner IS NULL OR lease_expires_at <= clock_timestamp())
  RETURNING lease_token $$;

CREATE OR REPLACE FUNCTION lease_heartbeat(p_op text, p_token bigint) RETURNS boolean LANGUAGE sql AS $$
  WITH u AS (UPDATE ops SET lease_expires_at = clock_timestamp() + make_interval(secs => lease_secs()), last_heartbeat_at = clock_timestamp()
              WHERE id = p_op AND status = 'reserved' AND lease_token = p_token AND lease_expires_at > clock_timestamp() RETURNING 1)
  SELECT EXISTS (SELECT 1 FROM u) $$;

-- cost monitoring: "the worker called the paid provider and it cost us p_cost" (worker calls this right after the provider answers)
CREATE OR REPLACE FUNCTION lease_note_provider_call(p_op text, p_token bigint, p_cost bigint DEFAULT 0) RETURNS void LANGUAGE sql AS $$ INSERT INTO provider_attempts(op, token, cost_micros) VALUES (p_op, p_token, p_cost) $$;

-- COMMIT: save the result AND charge, in one transaction, only for the current ticket.
-- Returns: committed | fenced (old ticket: nothing saved, nothing charged) | already_saved | lost (released meanwhile: nothing saved, nothing charged) | missing
CREATE OR REPLACE FUNCTION lease_commit(p_op text, p_token bigint, p_actual bigint, p_body text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE o ops%ROWTYPE; charge bigint;
BEGIN
  PERFORM 1 FROM account WHERE id = 1 FOR UPDATE;
  SELECT * INTO o FROM ops WHERE id = p_op FOR UPDATE;
  IF NOT FOUND THEN RETURN 'missing'; END IF;
  IF o.status = 'settled' THEN RETURN 'already_saved'; END IF;
  IF o.status = 'released' THEN RETURN 'lost'; END IF;
  IF o.lease_token <> p_token THEN RETURN 'fenced'; END IF;
  INSERT INTO results(op, token, body) VALUES (p_op, p_token, p_body);          -- primary key: a second result for one request is impossible
  charge := least(p_actual, o.held);
  INSERT INTO charges(op, amount) VALUES (p_op, charge);                          -- primary key: a second charge line for one request is impossible
  UPDATE ops SET status = 'settled', held = 0, charged_micros = charge, settle_count = settle_count + 1 WHERE id = p_op;
  UPDATE account SET held = held - o.held, available = available + (o.held - charge), charged = charged + charge WHERE id = 1;
  RETURN 'committed';
END $$;

-- RELEASE BY WORKER: the provider failed and nothing was saved. Current ticket only.
CREATE OR REPLACE FUNCTION lease_release_own(p_op text, p_token bigint) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE o ops%ROWTYPE;
BEGIN
  PERFORM 1 FROM account WHERE id = 1 FOR UPDATE;
  SELECT * INTO o FROM ops WHERE id = p_op AND status = 'reserved' AND lease_token = p_token FOR UPDATE;
  IF NOT FOUND THEN RETURN false; END IF;
  UPDATE ops SET status = 'released', held = 0, release_count = release_count + 1 WHERE id = p_op;
  UPDATE account SET held = held - o.held, available = available + o.held WHERE id = 1;
  RETURN true;
END $$;

-- REAPER: gives credits back for requests that went silent. NEVER charges. A request that has a saved result is 'settled', never
-- 'reserved', so there is nothing here that could be charged by mistake.
CREATE OR REPLACE FUNCTION lease_reap(OUT released int) LANGUAGE plpgsql AS $$
DECLARE o ops%ROWTYPE;
BEGIN
  released := 0;
  PERFORM 1 FROM account WHERE id = 1 FOR UPDATE;
  FOR o IN SELECT * FROM ops WHERE status = 'reserved'
              AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp())
              AND coalesce(last_heartbeat_at, created_at) <= clock_timestamp() - make_interval(secs => grace_secs())
              FOR UPDATE SKIP LOCKED LOOP
    UPDATE ops SET status = 'released', held = 0, lease_token = lease_token + 1, release_count = release_count + 1 WHERE id = o.id;
    UPDATE account SET held = held - o.held, available = available + o.held WHERE id = 1;
    released := released + 1;
  END LOOP;
END $$;

CREATE OR REPLACE FUNCTION lease_audit(p_start bigint) RETURNS text LANGUAGE plpgsql AS $$
DECLARE a account%ROWTYPE; open_held bigint; bad int; charged_sum bigint;
BEGIN
  SELECT * INTO a FROM account WHERE id = 1;
  SELECT coalesce(sum(held),0) INTO open_held FROM ops WHERE status = 'reserved';
  SELECT coalesce(sum(charged_micros),0) INTO charged_sum FROM ops;
  IF a.available + a.held + a.charged <> p_start THEN RETURN format('credits not conserved: %s+%s+%s <> %s', a.available, a.held, a.charged, p_start); END IF;
  IF a.held <> open_held THEN RETURN format('account hold %s <> open holds %s', a.held, open_held); END IF;
  IF a.charged <> charged_sum THEN RETURN format('charged %s <> per-request charges %s', a.charged, charged_sum); END IF;
  SELECT count(*) INTO bad FROM ops WHERE settle_count > 1; IF bad > 0 THEN RETURN format('%s requests were charged more than once', bad); END IF;
  SELECT count(*) INTO bad FROM ops o WHERE (o.status = 'settled') <> EXISTS (SELECT 1 FROM results r WHERE r.op = o.id); IF bad > 0 THEN RETURN format('%s requests where "charged" and "result saved" disagree', bad); END IF;
  SELECT count(*) INTO bad FROM ops WHERE status <> 'settled' AND charged_micros <> 0; IF bad > 0 THEN RETURN format('%s requests charged without a saved result', bad); END IF;
  SELECT count(*) INTO bad FROM ops WHERE status <> 'reserved' AND held <> 0; IF bad > 0 THEN RETURN format('%s finished requests still hold credits', bad); END IF;
  SELECT count(*) INTO bad FROM ops o WHERE (o.status = 'settled') <> EXISTS (SELECT 1 FROM charges c WHERE c.op = o.id); IF bad > 0 THEN RETURN format('%s requests where "charged" and the charge line disagree', bad); END IF;
  IF (SELECT coalesce(sum(amount),0) FROM charges) <> a.charged THEN RETURN 'charge lines do not add up to the account total'; END IF;
  SELECT count(*) INTO bad FROM ops WHERE release_count > attempts; IF bad > 0 THEN RETURN format('%s requests released more often than attempted', bad); END IF;
  RETURN 'ok';
END $$;

-- Read the saved result of a request (used on retry: replay it; do not call the provider again).
CREATE OR REPLACE FUNCTION lease_saved(p_op text) RETURNS text LANGUAGE sql STABLE AS $$ SELECT body FROM results WHERE op = p_op $$;

-- COST MONITORING. A provider attempt is "absorbed" (we paid, the customer did not) when it is not the attempt whose result was saved.
CREATE OR REPLACE VIEW absorbed_attempts AS
  SELECT p.op, p.token, p.cost_micros, p.at FROM provider_attempts p LEFT JOIN results r ON r.op = p.op AND r.token = p.token WHERE r.op IS NULL;

-- Text report for a dashboard or a log line.
CREATE OR REPLACE FUNCTION lease_cost_report(p_since timestamptz DEFAULT '-infinity') RETURNS text LANGUAGE sql STABLE AS $$
  SELECT format('attempts=%s absorbed_attempts=%s absorbed_micros=%s customer_charged_micros=%s requests_out_of_retries=%s',
    (SELECT count(*) FROM provider_attempts WHERE at >= p_since),
    (SELECT count(*) FROM absorbed_attempts WHERE at >= p_since),
    (SELECT coalesce(sum(cost_micros),0) FROM absorbed_attempts WHERE at >= p_since),
    (SELECT coalesce(sum(amount),0) FROM charges WHERE at >= p_since),
    (SELECT count(*) FROM ops WHERE status = 'released' AND attempts >= max_attempts())) $$;

-- True when the cost we absorbed since p_since is above p_limit_micros: the signal to alert a person (and, if wanted, pause new work).
CREATE OR REPLACE FUNCTION lease_cost_alert(p_since timestamptz, p_limit_micros bigint) RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT coalesce(sum(cost_micros),0) > p_limit_micros FROM absorbed_attempts WHERE at >= p_since $$;
