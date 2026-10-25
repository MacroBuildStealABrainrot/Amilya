-- PROPOSED lease rules, written as plain SQL for PostgreSQL. TEST DATABASE ONLY. Not applied anywhere; not part of the app.
-- Table/column names mirror the idea of usage_operations + the account row; they are NOT the live schema.
-- Lock order everywhere: account row first, then the request row (so two sessions can never deadlock each other).
DROP TABLE IF EXISTS ops, account, tokens CASCADE;
CREATE TABLE account(id int PRIMARY KEY, available bigint NOT NULL CHECK (available >= 0), held bigint NOT NULL CHECK (held >= 0), charged bigint NOT NULL CHECK (charged >= 0));
CREATE TABLE ops(
  id text PRIMARY KEY, status text NOT NULL DEFAULT 'reserved' CHECK (status IN ('reserved','settled','released')),
  held bigint NOT NULL, lease_owner text, lease_token bigint NOT NULL DEFAULT 0, lease_expires_at timestamptz, last_heartbeat_at timestamptz,
  result_micros bigint, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  charged_micros bigint NOT NULL DEFAULT 0, settle_count int NOT NULL DEFAULT 0, release_count int NOT NULL DEFAULT 0);
CREATE TABLE tokens(worker int, op text, token bigint);          -- what each simulated worker believes it holds (stress test only)
-- Test knobs (seconds). Production values would be 120 s lease and 600 s quiet period.
CREATE OR REPLACE FUNCTION lease_secs() RETURNS double precision LANGUAGE sql STABLE AS $$ SELECT coalesce(nullif(current_setting('app.lease_seconds', true), ''), '120')::double precision $$;
CREATE OR REPLACE FUNCTION grace_secs() RETURNS double precision LANGUAGE sql STABLE AS $$ SELECT coalesce(nullif(current_setting('app.grace_seconds', true), ''), '600')::double precision $$;

CREATE OR REPLACE FUNCTION lease_reserve(p_op text, p_amount bigint) RETURNS text LANGUAGE plpgsql AS $$
DECLARE a account%ROWTYPE;
BEGIN
  SELECT * INTO a FROM account WHERE id = 1 FOR UPDATE;                       -- same idea as _lock_account
  IF EXISTS (SELECT 1 FROM ops WHERE id = p_op) THEN RETURN 'exists'; END IF; -- idempotent
  IF a.available < p_amount THEN RETURN 'insufficient'; END IF;
  UPDATE account SET available = available - p_amount, held = held + p_amount WHERE id = 1;
  INSERT INTO ops(id, held) VALUES (p_op, p_amount);
  RETURN 'reserved';
END $$;

-- CLAIM: one atomic conditional UPDATE. Returns the new ticket number, or NULL if somebody else holds a live lease.
CREATE OR REPLACE FUNCTION lease_claim(p_op text, p_worker text) RETURNS bigint LANGUAGE sql AS $$
  UPDATE ops SET lease_owner = p_worker, lease_token = lease_token + 1,
                 lease_expires_at = clock_timestamp() + make_interval(secs => lease_secs()), last_heartbeat_at = clock_timestamp()
   WHERE id = p_op AND status = 'reserved' AND result_micros IS NULL
     AND (lease_owner IS NULL OR lease_expires_at <= clock_timestamp())
  RETURNING lease_token $$;

CREATE OR REPLACE FUNCTION lease_heartbeat(p_op text, p_token bigint) RETURNS boolean LANGUAGE sql AS $$
  WITH u AS (UPDATE ops SET lease_expires_at = clock_timestamp() + make_interval(secs => lease_secs()), last_heartbeat_at = clock_timestamp()
              WHERE id = p_op AND status = 'reserved' AND lease_token = p_token AND lease_expires_at > clock_timestamp() RETURNING 1)
  SELECT EXISTS (SELECT 1 FROM u) $$;

-- STORE RESULT: the provider answered; remember the cost BEFORE settling. Only the current ticket holder may do this.
CREATE OR REPLACE FUNCTION lease_store_result(p_op text, p_token bigint, p_actual bigint) RETURNS boolean LANGUAGE sql AS $$
  WITH u AS (UPDATE ops SET result_micros = p_actual WHERE id = p_op AND status = 'reserved' AND lease_token = p_token AND result_micros IS NULL RETURNING 1)
  SELECT EXISTS (SELECT 1 FROM u) $$;

-- SETTLE: p_token = NULL is the "completer" path (any process may finish a stored result).
CREATE OR REPLACE FUNCTION lease_settle(p_op text, p_token bigint) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE o ops%ROWTYPE; charge bigint;
BEGIN
  PERFORM 1 FROM account WHERE id = 1 FOR UPDATE;
  SELECT * INTO o FROM ops WHERE id = p_op AND status = 'reserved' FOR UPDATE;
  IF NOT FOUND OR o.result_micros IS NULL OR (p_token IS NOT NULL AND o.lease_token <> p_token) THEN RETURN false; END IF;
  charge := least(o.result_micros, o.held);
  UPDATE ops SET status = 'settled', held = 0, charged_micros = charge, settle_count = settle_count + 1 WHERE id = p_op;
  UPDATE account SET held = held - o.held, available = available + (o.held - charge), charged = charged + charge WHERE id = 1;
  RETURN true;
END $$;

CREATE OR REPLACE FUNCTION lease_release_own(p_op text, p_token bigint) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE o ops%ROWTYPE;
BEGIN
  PERFORM 1 FROM account WHERE id = 1 FOR UPDATE;
  SELECT * INTO o FROM ops WHERE id = p_op AND status = 'reserved' AND result_micros IS NULL AND lease_token = p_token FOR UPDATE;
  IF NOT FOUND THEN RETURN false; END IF;
  UPDATE ops SET status = 'released', held = 0, release_count = release_count + 1 WHERE id = p_op;
  UPDATE account SET held = held - o.held, available = available + o.held WHERE id = 1;
  RETURN true;
END $$;

-- REAPER: gives credits back ONLY for silent requests with no stored result. Never charges on its own. Finishes stored results.
CREATE OR REPLACE FUNCTION lease_reap(OUT released int, OUT completed int) LANGUAGE plpgsql AS $$
DECLARE o ops%ROWTYPE;
BEGIN
  released := 0; completed := 0;
  PERFORM 1 FROM account WHERE id = 1 FOR UPDATE;
  FOR o IN SELECT * FROM ops WHERE status = 'reserved' AND result_micros IS NULL
              AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp())
              AND coalesce(last_heartbeat_at, created_at) <= clock_timestamp() - make_interval(secs => grace_secs())
              FOR UPDATE SKIP LOCKED LOOP
    UPDATE ops SET status = 'released', held = 0, lease_token = lease_token + 1, release_count = release_count + 1 WHERE id = o.id;
    UPDATE account SET held = held - o.held, available = available + o.held WHERE id = 1;
    released := released + 1;
  END LOOP;
  FOR o IN SELECT * FROM ops WHERE status = 'reserved' AND result_micros IS NOT NULL LOOP
    IF lease_settle(o.id, NULL) THEN completed := completed + 1; END IF;
  END LOOP;
END $$;

CREATE OR REPLACE FUNCTION lease_audit(p_start bigint) RETURNS text LANGUAGE plpgsql AS $$
DECLARE a account%ROWTYPE; open_held bigint; bad int; charged_sum bigint;
BEGIN
  SELECT * INTO a FROM account WHERE id = 1;
  SELECT coalesce(sum(held),0) INTO open_held FROM ops WHERE status = 'reserved';
  SELECT coalesce(sum(charged_micros),0) INTO charged_sum FROM ops;
  SELECT count(*) INTO bad FROM ops WHERE settle_count > 1 OR release_count > 1 OR (settle_count + release_count) > 1;
  IF a.available + a.held + a.charged <> p_start THEN RETURN format('credits not conserved: %s+%s+%s <> %s', a.available, a.held, a.charged, p_start); END IF;
  IF a.held <> open_held THEN RETURN format('account hold %s <> open holds %s', a.held, open_held); END IF;
  IF a.charged <> charged_sum THEN RETURN format('charged %s <> per-request charges %s', a.charged, charged_sum); END IF;
  IF bad > 0 THEN RETURN format('%s requests were settled or released more than once', bad); END IF;
  RETURN 'ok';
END $$;
