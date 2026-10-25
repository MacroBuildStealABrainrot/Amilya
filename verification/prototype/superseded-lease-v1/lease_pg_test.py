"""
Tests the proposed lease rules (lease_schema.sql) on a REAL PostgreSQL server, using the standard `psql` program as the client
(no Python database package is installed in the build workspace). Many separate psql programs run at the same time, so the
database's own row locks and transaction rules decide every race, exactly as they would between two server instances.

SAFETY: connects only to the throw-away server whose socket folder you pass in. Creates its own tables with made-up numbers.
It never reads the application's code or database, and changes no billing code.
What it does NOT test: the SQLAlchemy code (not written yet), the real table definitions, Cloud SQL / a network connection.

Usage: python3 lease_pg_test.py <socket-dir> <port> [database]
"""
import os, random, subprocess, sys, tempfile, threading, time
from concurrent.futures import ThreadPoolExecutor

SOCK, PORT = sys.argv[1], sys.argv[2]; DB = sys.argv[3] if len(sys.argv) > 3 else "leasetest"
HERE = os.path.dirname(os.path.abspath(__file__)); START = 1_000_000
results = []

def psql(sql, db=DB, settings=None, check=True):
    env = dict(os.environ, PGOPTIONS=" ".join(f"-c app.{k}={v}" for k, v in (settings or {}).items()))
    p = subprocess.run(["psql", "-X", "-q", "-A", "-t", "-h", SOCK, "-p", PORT, "-U", "postgres", "-d", db, "-v", "ON_ERROR_STOP=1", "-c", sql],
                       capture_output=True, text=True, env=env)
    if check and p.returncode: raise RuntimeError(p.stderr.strip())
    return p.stdout.strip(), p.stderr.strip(), p.returncode

def check(name, ok, extra=""):
    results.append(ok); print(("PASS  " if ok else "FAIL  ") + name + (f"   [{extra}]" if extra else ""))

def fresh(lease="2", grace="3", balance=START):
    subprocess.run(["psql", "-X", "-q", "-h", SOCK, "-p", PORT, "-U", "postgres", "-d", DB, "-v", "ON_ERROR_STOP=1", "-f", os.path.join(HERE, "lease_schema.sql")],
                   check=True, capture_output=True)
    psql(f"INSERT INTO account VALUES (1, {balance}, 0, 0)")
    return {"lease_seconds": lease, "grace_seconds": grace}

def audit(label): 
    out = psql(f"SELECT lease_audit({START})")[0]
    check(f"{label}: credits conserved, holds match, no request settled/released twice", out == "ok", out)

# create the database
subprocess.run(["psql", "-X", "-q", "-h", SOCK, "-p", PORT, "-U", "postgres", "-d", "postgres", "-c", f"DROP DATABASE IF EXISTS {DB}"], check=True, capture_output=True)
subprocess.run(["psql", "-X", "-q", "-h", SOCK, "-p", PORT, "-U", "postgres", "-d", "postgres", "-c", f"CREATE DATABASE {DB}"], check=True, capture_output=True)
version = psql("SHOW server_version")[0]
print(f"PostgreSQL {version}  (throw-away server, test database '{DB}')\n")

# 1. 50 separate sessions race to claim one request
S = fresh(); psql("SELECT lease_reserve('op1', 1000)")
go = time.time() + 1.5
def racer(i):
    time.sleep(max(0, go - time.time()))
    return psql(f"SELECT lease_claim('op1', 'w{i}')", settings=S)[0]
with ThreadPoolExecutor(50) as ex: got = list(ex.map(racer, range(50)))
winners = [g for g in got if g]
check("50 simultaneous sessions claim one request: exactly one winner, ticket 1", winners == ["1"], f"{len(winners)} winner(s)")
audit("race")

# 2. crash, takeover only after expiry, late ('zombie') worker locked out  (real clock, 2 s lease)
S = fresh(lease="2", grace="3"); psql("SELECT lease_reserve('op2', 2000)")
a = psql("SELECT lease_claim('op2','A')", settings=S)[0]
check("worker A gets ticket 1", a == "1")
check("worker B is refused while A's lease is alive", psql("SELECT lease_claim('op2','B')", settings=S)[0] == "")
check("A can renew its lease while alive", psql(f"SELECT lease_heartbeat('op2', {a})", settings=S)[0] == "t")
time.sleep(2.3)                                                       # A dies: lease runs out
b = psql("SELECT lease_claim('op2','B')", settings=S)[0]
check("after A's lease runs out, B takes over with ticket 2", b == "2")
check("zombie A cannot store a result", psql(f"SELECT lease_store_result('op2', {a}, 1500)")[0] == "f")
check("zombie A cannot release", psql(f"SELECT lease_release_own('op2', {a})")[0] == "f")
check("zombie A cannot renew", psql(f"SELECT lease_heartbeat('op2', {a})")[0] == "f")
check("B stores its result and settles", psql(f"SELECT lease_store_result('op2', {b}, 1500)")[0] == "t" and psql(f"SELECT lease_settle('op2', {b})")[0] == "t")
check("a repeated settle changes nothing", psql(f"SELECT lease_settle('op2', {b})")[0] == "f" and psql("SELECT lease_settle('op2', NULL)")[0] == "f")
row = psql("SELECT available, held, charged FROM account")[0]
check("charged exactly the stored cost; the rest of the hold went back", row == f"{START-1500}|0|1500", row)
audit("takeover")

# 3. reaper rules
S = fresh(lease="1", grace="3")
for op, amt in (("silent", 3000), ("answered", 4000), ("busy", 5000), ("never-claimed", 600)): psql(f"SELECT lease_reserve('{op}', {amt})")
t_s = psql("SELECT lease_claim('silent','W1')", settings=S)[0]; t_a = psql("SELECT lease_claim('answered','W2')", settings=S)[0]; psql("SELECT lease_claim('busy','W3')", settings=S)
psql(f"SELECT lease_store_result('answered', {t_a}, 3500)")           # answered, then its worker died before settling
r = psql("SELECT * FROM lease_reap()", settings=S)[0]
check("reaper too early: releases nothing silent (it only finishes the stored result)", r.split("|")[0] == "0", r)
st = dict(l.split("|") for l in psql("SELECT id, status FROM ops ORDER BY id")[0].splitlines())
check("a stored result is settled (never released) even before the quiet period ends", st["answered"] == "settled")
for _ in range(7):                                                    # keep 'busy' alive with heartbeats while the quiet period passes
    time.sleep(0.6); psql("SELECT lease_heartbeat('busy', 1)", settings=S)
r = psql("SELECT * FROM lease_reap()", settings=S)[0]
st = dict(l.split("|") for l in psql("SELECT id, status FROM ops ORDER BY id")[0].splitlines())
check("reaper releases the silent request and the never-claimed one", st["silent"] == "released" and st["never-claimed"] == "released" and r.split("|")[0] == "2", r)
check("reaper leaves a heartbeating request alone", st["busy"] == "reserved")
check("the only charge is the stored provider cost (3500)", psql("SELECT charged FROM account")[0] == "3500")
check("a late worker cannot store a result for a released request", psql(f"SELECT lease_store_result('silent', {t_s}, 10)")[0] == "f")
audit("reaper")

# 4. result stored at the same instant the reaper runs: exactly one wins, money stays consistent (repeat many times)
bad = 0
for round_ in range(25):
    S = fresh(lease="1", grace="0.2"); psql("SELECT lease_reserve('x', 1000)"); tok = psql("SELECT lease_claim('x','W')", settings=S)[0]
    time.sleep(1.3); go = time.time() + 0.8
    def storer():
        time.sleep(max(0, go - time.time())); return psql(f"SELECT lease_store_result('x', {tok}, 700)", settings=S)[0]
    def reaper():
        time.sleep(max(0, go - time.time())); return psql("SELECT * FROM lease_reap()", settings=S)[0]
    with ThreadPoolExecutor(2) as ex: f1 = ex.submit(storer); f2 = ex.submit(reaper); stored, reaped = f1.result(), f2.result()
    st = psql("SELECT status, settle_count, release_count FROM ops WHERE id='x'")[0]
    verdict = psql(f"SELECT lease_audit({START})")[0]
    # either the answer was stored (then it is settled at 700 by the reaper's completer or a later pass) or the request was released: never both
    psql("SELECT * FROM lease_reap()", settings=S)
    final = psql("SELECT status, charged_micros FROM ops WHERE id='x'")[0]
    ok = verdict == "ok" and (final in ("settled|700", "released|0"))
    if stored == "t": ok = ok and final == "settled|700"
    if not ok: bad += 1; print("   odd round:", stored, reaped, st, final, verdict)
check("25 races of 'store result' against 'reaper': never both, never lost, money consistent", bad == 0, f"{bad} bad rounds")

# 5. heavy random stress: 8 sessions running server-side loops at once, random deaths, random reaper runs
S = fresh(lease="0.4", grace="1.0")
psql("TRUNCATE tokens")
WORKERS, STEPS = 8, 1200
LOOP = """
DO $body$
DECLARE i int; op text; k double precision; t bigint; w int := %d; r record;
BEGIN
  FOR i IN 1..%d LOOP
    k := random();
    IF k < 0.22 THEN
      PERFORM lease_reserve('s' || w || '_' || i, 100 + floor(random() * 2900)::bigint);
    ELSIF k < 0.50 THEN
      SELECT id INTO op FROM ops WHERE status = 'reserved' ORDER BY random() LIMIT 1;
      IF op IS NOT NULL THEN t := lease_claim(op, 'w' || w); IF t IS NOT NULL THEN INSERT INTO tokens VALUES (w, op, t); END IF; END IF;
    ELSIF k < 0.63 THEN
      SELECT * INTO r FROM tokens WHERE worker = w ORDER BY random() LIMIT 1;
      IF FOUND THEN PERFORM lease_store_result(r.op, r.token, 50 + floor(random() * 3000)::bigint); END IF;
    ELSIF k < 0.75 THEN
      SELECT * INTO r FROM tokens WHERE worker = w ORDER BY random() LIMIT 1;
      IF FOUND THEN PERFORM lease_settle(r.op, r.token); END IF;
    ELSIF k < 0.80 THEN
      SELECT * INTO r FROM tokens WHERE worker = w ORDER BY random() LIMIT 1;
      IF FOUND THEN PERFORM lease_release_own(r.op, r.token); END IF;
    ELSIF k < 0.85 THEN
      SELECT * INTO r FROM tokens WHERE worker = w ORDER BY random() LIMIT 1;
      IF FOUND THEN PERFORM lease_heartbeat(r.op, r.token); END IF;
    ELSIF k < 0.93 THEN
      PERFORM * FROM lease_reap();
    ELSIF k < 0.97 THEN
      DELETE FROM tokens WHERE ctid IN (SELECT ctid FROM tokens WHERE worker = w LIMIT 1);   -- this worker 'dies' holding a lease
    ELSE
      PERFORM pg_sleep(random() * 0.25);
    END IF;
    COMMIT;
  END LOOP;
END $body$;
"""
def worker(w):
    return psql(LOOP % (w, STEPS), settings=S, check=False)
t0 = time.time()
with ThreadPoolExecutor(WORKERS) as ex: outs = list(ex.map(worker, range(WORKERS)))
errors = [o[1] for o in outs if o[2] != 0 or o[1]]
check(f"stress: {WORKERS} sessions x {STEPS} random steps ran with no database errors (no deadlocks, no rule violations)", not errors, (errors[0][:140] if errors else f"{time.time()-t0:.0f}s"))
created = int(psql("SELECT count(*) FROM ops")[0])
audit("stress (after the storm)")
stats = psql("SELECT status || '=' || count(*) FROM ops GROUP BY status ORDER BY status")[0].replace("\n", ", ")
takeovers = psql("SELECT count(*) FROM ops WHERE lease_token > 1")[0]
print(f"      stress mix: {stats}; requests taken over by a second worker or the reaper: {takeovers}")
check("stress really exercised takeovers, settles and releases", int(takeovers) > 5 and "settled" in stats and "released" in stats)
time.sleep(1.3); psql("SELECT * FROM lease_reap()", settings=S); psql("SELECT * FROM lease_reap()", settings=S)
left = psql("SELECT count(*) FROM ops WHERE status='reserved'")[0]; held = psql("SELECT held FROM account")[0]
check(f"after every lease and quiet period has run out, nothing is stuck (stuck={left}, held={held}; {created} requests created)", left == "0" and held == "0")
audit("stress (after clean-up)")
bad = results.count(False); print(f"\n{len(results)-bad}/{len(results)} checks passed"); sys.exit(1 if bad else 0)
