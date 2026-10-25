"""
Tests the lease rules v2 (lease_schema.sql) on a REAL PostgreSQL server, using the standard `psql` program as the client.
Many separate psql programs run at once, so the database's own row locks decide every race, as they would between two servers.

What these rules promise (your decision 4): the customer is charged ONLY when the result is saved, in the same database
transaction, and never twice. If the AI provider answered but the server died before saving, nothing is charged for that lost
attempt; a retry makes a fresh attempt and is charged once. If the result WAS saved but the reply never reached the app, the retry
gets the saved result back with no new provider call and no new charge.

SAFETY: connects only to the throw-away server whose socket folder you pass in; made-up numbers; never touches the app's code or
database or billing. NOT tested: the SQLAlchemy code (not written), the real tables, Cloud SQL / network connections.
Usage: python3 lease_pg_test.py <socket-dir> <port> [database]
"""
import os, random, subprocess, sys, threading, time
from collections import Counter
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
    results.append(bool(ok)); print(("PASS  " if ok else "FAIL  ") + name + (f"   [{extra}]" if extra else ""))

def fresh(lease="1", grace="2", balance=START):
    subprocess.run(["psql", "-X", "-q", "-h", SOCK, "-p", PORT, "-U", "postgres", "-d", DB, "-v", "ON_ERROR_STOP=1", "-f", os.path.join(HERE, "lease_schema.sql")],
                   check=True, capture_output=True)
    psql(f"INSERT INTO account VALUES (1, {balance}, 0, 0)")
    return {"lease_seconds": lease, "grace_seconds": grace}

def audit(label):
    out = psql(f"SELECT lease_audit({START})")[0]
    check(f"{label}: credits conserved, nothing charged twice, nothing charged without a saved result", out == "ok", out)

def acct(): return psql("SELECT available||'|'||held||'|'||charged FROM account")[0]
def count(table, op): return int(psql(f"SELECT count(*) FROM {table} WHERE op = '{op}'")[0])

for d in ("postgres",):
    pass
subprocess.run(["psql", "-X", "-q", "-h", SOCK, "-p", PORT, "-U", "postgres", "-d", "postgres", "-c", f"DROP DATABASE IF EXISTS {DB}"], check=True, capture_output=True)
subprocess.run(["psql", "-X", "-q", "-h", SOCK, "-p", PORT, "-U", "postgres", "-d", "postgres", "-c", f"CREATE DATABASE {DB}"], check=True, capture_output=True)
print(f"PostgreSQL {psql('SHOW server_version')[0]}  (throw-away server, test database '{DB}')\n")

# ---- 1. 50 sessions race to claim one request
S = fresh(); psql("SELECT lease_reserve('op1', 1000)")
go = time.time() + 1.5
def racer(i):
    time.sleep(max(0, go - time.time())); return psql(f"SELECT lease_claim('op1', 'w{i}')", settings=S)[0]
with ThreadPoolExecutor(50) as ex: got = list(ex.map(racer, range(50)))
check("50 simultaneous sessions claim one request: exactly one winner, ticket 1", [g for g in got if g] == ["1"])
audit("race")

# ---- 2. heartbeat, takeover only after expiry, zombie locked out
S = fresh(); psql("SELECT lease_reserve('op2', 2000)")
a = psql("SELECT lease_claim('op2','A')", settings=S)[0]
check("worker A gets ticket 1; B is refused while A's lease is alive", a == "1" and psql("SELECT lease_claim('op2','B')", settings=S)[0] == "")
time.sleep(0.7); check("A renews its lease in time", psql(f"SELECT lease_heartbeat('op2', {a})", settings=S)[0] == "t")
time.sleep(0.7); check("... so B is still refused (the renewal moved the deadline)", psql("SELECT lease_claim('op2','B')", settings=S)[0] == "")
time.sleep(1.3)
b = psql("SELECT lease_claim('op2','B')", settings=S)[0]
check("after A goes silent past the lease, B takes over with ticket 2", b == "2")
psql("SELECT lease_note_provider_call('op2', 2)")
check("zombie A cannot save a result or charge (fenced)", psql(f"SELECT lease_commit('op2', {a}, 1500, 'zombie')")[0] == "fenced" and count("results", "op2") == 0 and acct() == f"{START-2000}|2000|0", acct())
check("zombie A cannot renew or release", psql(f"SELECT lease_heartbeat('op2', {a})", settings=S)[0] == "f" and psql(f"SELECT lease_release_own('op2', {a})")[0] == "f")
check("B commits: result saved and charged exactly once", psql(f"SELECT lease_commit('op2', {b}, 1500, 'actual=1500')")[0] == "committed" and acct() == f"{START-1500}|0|1500", acct())
check("a repeated commit (even from the zombie) changes nothing", psql(f"SELECT lease_commit('op2', {b}, 1500, 'x')")[0] == "already_saved" and psql(f"SELECT lease_commit('op2', {a}, 1500, 'y')")[0] == "already_saved" and acct() == f"{START-1500}|0|1500" and psql("SELECT lease_saved('op2')")[0] == "actual=1500")
audit("takeover")

# ---- 3. provider ANSWERED but the server died before saving: no charge; retry charged once
S = fresh(); psql("SELECT lease_reserve('lost', 3000)")
t1 = psql("SELECT lease_claim('lost','W1')", settings=S)[0]; psql(f"SELECT lease_note_provider_call('lost', {t1})")   # provider answered (we paid it) ... then W1 dies
check("while the dead worker's lease is alive the retry is told 'in progress'", psql("SELECT lease_reserve('lost', 3000)")[0] == "in_progress")
time.sleep(1.2); check("lease ran out but the quiet period has not: reaper leaves it alone", psql("SELECT released FROM lease_reap()", settings=S)[0] == "0")
time.sleep(1.2)
check("after the quiet period the reaper releases it", psql("SELECT released FROM lease_reap()", settings=S)[0] == "1")
check("the customer was NOT charged for the lost attempt: all credits back, no result", acct() == f"{START}|0|0" and count("results", "lost") == 0, acct())
check("a second reaper pass does nothing", psql("SELECT released FROM lease_reap()", settings=S)[0] == "0" and acct() == f"{START}|0|0")
check("the retry reserves again on the same request", psql("SELECT lease_reserve('lost', 3000)")[0] == "reserved")
t2 = psql("SELECT lease_claim('lost','W2')", settings=S)[0]; psql(f"SELECT lease_note_provider_call('lost', {t2})")
check("the retry gets a newer ticket than the dead attempt", int(t2) > int(t1), f"{t1} -> {t2}")
check("the dead attempt, if it wakes up late, is refused", psql(f"SELECT lease_commit('lost', {t1}, 2500, 'late')")[0] == "fenced")
check("the retry commits: charged ONCE, one saved result", psql(f"SELECT lease_commit('lost', {t2}, 2500, 'actual=2500')")[0] == "committed" and acct() == f"{START-2500}|0|2500" and count("results", "lost") == 1, acct())
check("provider was called twice for this visit (one call absorbed by us, never billed to the customer)", count("provider_attempts", "lost") == 2)
audit("answered-then-crashed")

# ---- 4. result SAVED but the reply never reached the app: retry replays it, no new call, no new charge
S = fresh(); psql("SELECT lease_reserve('replay', 4000)")
t = psql("SELECT lease_claim('replay','W1')", settings=S)[0]; psql(f"SELECT lease_note_provider_call('replay', {t})")
check("commit succeeds (then the response is lost on the way back)", psql(f"SELECT lease_commit('replay', {t}, 3600, 'actual=3600')")[0] == "committed")
before = acct()
check("retry: 'saved' - told to use the stored result, not to call the provider", psql("SELECT lease_reserve('replay', 4000)")[0] == "saved")
check("retry cannot claim (nothing left to do)", psql("SELECT lease_claim('replay','W2')", settings=S)[0] == "")
check("the stored result comes back unchanged", psql("SELECT lease_saved('replay')")[0] == "actual=3600")
time.sleep(2.3); check("the reaper leaves a finished request alone", psql("SELECT released FROM lease_reap()", settings=S)[0] == "0" and acct() == before == f"{START-3600}|0|3600", acct())
check("only one provider call, one charge", count("provider_attempts", "replay") == 1)
audit("saved-then-reply-lost")

# ---- 5. provider failed (worker says so), late commits, shortfalls, over-estimates
S = fresh(balance=5000)
psql("SELECT lease_reserve('fail', 3000)"); tf = psql("SELECT lease_claim('fail','W')", settings=S)[0]
check("provider failure: worker releases its own hold, credits return", psql(f"SELECT lease_release_own('fail', {tf})")[0] == "t" and psql("SELECT available||'|'||held FROM account")[0] == "5000|0")
check("a late commit after release saves nothing and charges nothing", psql(f"SELECT lease_commit('fail', {tf}, 100, 'x')")[0] == "lost" and count("results", "fail") == 0 and acct() == "5000|0|0")
check("not enough credit: refused, nothing changes", psql("SELECT lease_reserve('big', 9000)")[0] == "insufficient" and acct() == "5000|0|0")
psql("SELECT lease_reserve('cap', 2000)"); tc = psql("SELECT lease_claim('cap','W')", settings=S)[0]
check("if the real cost is above the hold, the customer pays at most the hold", psql(f"SELECT lease_commit('cap', {tc}, 99999, 'actual=99999')")[0] == "committed" and acct() == "3000|0|2000")
check("a released request that cannot be re-reserved for lack of credit says so", (psql("SELECT lease_reserve('fail', 9000)")[0] == "insufficient"))
psql("SELECT lease_reserve('x1', 3000)"); check("... and credits held by one request are not available to another", psql("SELECT lease_reserve('x2', 1)")[0] == "insufficient")
psql("SELECT lease_audit(5000)")
check("audit after shortfalls", psql("SELECT lease_audit(5000)")[0] == "ok")

# ---- 6. commit and reaper racing on the same expired request (40 rounds, both ways round)
S = fresh(lease="0.3", grace="0.3"); N = 40
for i in range(N): psql(f"SELECT lease_reserve('r{i}', 1000)"); psql(f"SELECT lease_claim('r{i}','A')", settings=S)
time.sleep(0.8)                                                           # all leases expired and quiet period over
go = time.time() + 1.5; out = {}
def commiter(i):
    time.sleep(max(0, go - time.time())); out[i] = psql(f"SELECT lease_commit('r{i}', 1, 600, 'actual=600')")[0]
def reaper():
    time.sleep(max(0, go - time.time())); return psql("SELECT released FROM lease_reap()", settings=S)[0]
with ThreadPoolExecutor(N + 1) as ex:
    rf = ex.submit(reaper); [ex.submit(commiter, i) for i in range(N)]
released_by_reaper = int(rf.result()); time.sleep(0.5)
committed = sum(1 for v in out.values() if v == "committed"); lost = sum(1 for v in out.values() if v == "lost")
check(f"commit-vs-reaper race: every request ends exactly one way (committed {committed}, released-first {lost})", committed + lost == N and committed == int(psql("SELECT count(*) FROM results")[0]))
check("... never both charged and released, never an unpaid result", psql(f"SELECT lease_audit({START})")[0] == "ok", psql(f"SELECT lease_audit({START})")[0])
check("... and the reaper's count matches what the commits lost", released_by_reaper == lost, f"{released_by_reaper} vs {lost}")

# ---- 7. random multi-session stress with a provider-call counter
SEED = int(os.environ.get("SEED", "20261024")); rng0 = random.Random(SEED)
S = fresh(lease="0.4", grace="0.6"); S["max_attempts"] = "60"; OPS = 60; CLIENTS_PER_OP = 2; HOLD = 5000
tally = Counter(); tl = threading.Lock(); stop = threading.Event()
def bump(k):
    with tl: tally[k] += 1
def client(op, seed):
    rng = random.Random(seed)
    for _ in range(40):
        r = psql(f"SELECT lease_reserve('{op}', {HOLD}, 'h-{op}')", settings=S)[0]
        if r == "saved": bump("replay"); return
        if r == "insufficient": bump("insufficient"); return
        tok = psql(f"SELECT lease_claim('{op}', 'c{seed}')", settings=S)[0]
        if not tok: time.sleep(rng.uniform(0.1, 0.4)); continue
        cost = rng.randint(300, 3000); psql(f"SELECT lease_note_provider_call('{op}', {tok}, {cost})"); bump("provider_call")
        x = rng.random(); actual = rng.randint(800, HOLD)
        if x < 0.15: bump("crash_after_provider"); time.sleep(rng.uniform(0.0, 0.2)); continue          # worker dies; the app retries
        if x < 0.25: bump("provider_failed"); psql(f"SELECT lease_release_own('{op}', {tok})"); continue
        if x < 0.40: time.sleep(0.4 * 1.6)                                                              # so slow its lease runs out
        res = psql(f"SELECT lease_commit('{op}', {tok}, {actual}, 'actual={actual}')")[0]; bump("commit_" + res)
        if x >= 0.40 and x < 0.55: bump("reply_lost"); continue                                         # saved, but the app never heard
        if res in ("committed", "already_saved"): return
def reaper_loop():
    while not stop.is_set():
        n = int(psql("SELECT released FROM lease_reap()", settings=S)[0]); 
        if n: bump("reaped"); tally["reaped_requests"] += n
        time.sleep(0.25)
rt = threading.Thread(target=reaper_loop); rt.start(); t0 = time.time()
jobs = [(f"s{i}", rng0.randrange(10**9)) for i in range(OPS) for _ in range(CLIENTS_PER_OP)]
with ThreadPoolExecutor(24) as ex: futs = [ex.submit(client, op, sd) for op, sd in jobs]; errs = [str(f.exception()) for f in futs if f.exception()]
stop.set(); rt.join()
check(f"stress: {len(jobs)} client retries loops on {OPS} requests ran with no database errors/deadlocks ({time.time()-t0:.0f}s)", not errs, errs[0][:140] if errs else "")
time.sleep(1.6); psql("SELECT * FROM lease_reap()", settings=S); psql("SELECT * FROM lease_reap()", settings=S)
audit("stress")
st = psql("SELECT status||'='||count(*) FROM ops GROUP BY status ORDER BY 1")[0].replace("\n", ", ")
calls = int(psql("SELECT count(*) FROM provider_attempts")[0]); saved = int(psql("SELECT count(*) FROM results")[0])
check("stress: nothing left holding credits", psql("SELECT held FROM account")[0] == "0" and psql("SELECT count(*) FROM ops WHERE status='reserved'")[0] == "0")
check("stress: the customer paid exactly what the saved results say, and nothing else",
      psql("SELECT charged FROM account")[0] == psql("SELECT coalesce(sum(split_part(body,'=',2)::bigint),0) FROM results")[0])
check("stress: at most one saved result and one charge per request", psql("SELECT count(*) FROM (SELECT op FROM results GROUP BY op HAVING count(*)>1) q")[0] == "0" and psql("SELECT count(*) FROM ops WHERE settle_count>1")[0] == "0")
check("stress: no provider call was ever made on a ticket newer than the one that saved the result",
      psql("SELECT count(*) FROM provider_attempts p JOIN results r ON r.op=p.op WHERE p.token > r.token")[0] == "0")
check("stress really exercised the hard cases", tally["crash_after_provider"] > 3 and tally["commit_fenced"] + tally["commit_lost"] > 0 and tally["replay"] > 0 and tally["reply_lost"] > 0 and tally["reaped_requests"] > 0, dict(tally))
absorbed_n = int(psql("SELECT count(*) FROM absorbed_attempts")[0])
check("stress: cost monitoring agrees: absorbed attempts = provider calls - saved results", absorbed_n == calls - saved, f"{absorbed_n} vs {calls-saved}")
check("stress: every absorbed attempt has a cost on record and none of them was billed to the customer",
      psql("SELECT count(*) FROM absorbed_attempts WHERE cost_micros <= 0")[0] == "0" and psql("SELECT charged FROM account")[0] == psql("SELECT coalesce(sum(amount),0) FROM charges")[0])
print("      report:", psql("SELECT lease_cost_report()")[0])
print(f"      requests: {st}; provider calls {calls}, saved results {saved} -> {calls-saved} provider call(s) absorbed by us and never billed (seed {SEED})")
print(f"      events: {dict(tally)}")

# ---- 8. retry limit, idempotency, duplicate-charge protection, cost monitoring (decision 5)
S = fresh(lease="0.3", grace="0.3"); S["max_attempts"] = "3"
def lose_one(op, amt=1000, cost=700):                                  # a worker claims, the provider answers (it costs us), the worker dies
    r = psql(f"SELECT lease_reserve('{op}', {amt}, 'h-{op}')", settings=S)[0]
    if r != "reserved": return r
    t = psql(f"SELECT lease_claim('{op}','W')", settings=S)[0]; psql(f"SELECT lease_note_provider_call('{op}', {t}, {cost})")
    time.sleep(0.8); psql("SELECT released FROM lease_reap()", settings=S); return "lost"
check("retry limit: attempts 1 and 2 are allowed", lose_one("lim") == "lost" and lose_one("lim") == "lost")
check("... attempt 3 is allowed and is the last", lose_one("lim") == "lost")
check("... attempt 4 is refused: 'attempts_exhausted', nothing reserved, nothing charged", psql("SELECT lease_reserve('lim', 1000, 'h-lim')", settings=S)[0] == "attempts_exhausted" and acct() == f"{START}|0|0", acct())
check("cost monitoring: three lost attempts = 3 x 700 absorbed, customer charged 0", psql("SELECT lease_cost_report()")[0].startswith("attempts=3 absorbed_attempts=3 absorbed_micros=2100 customer_charged_micros=0 requests_out_of_retries=1"), psql("SELECT lease_cost_report()")[0])
check("cost alert fires above the limit and stays quiet below it", psql("SELECT lease_cost_alert('-infinity', 2000)")[0] == "t" and psql("SELECT lease_cost_alert('-infinity', 2100)")[0] == "f")
check("cost alert looks only at the chosen window", psql("SELECT lease_cost_alert(clock_timestamp() + interval '1 hour', 0)")[0] == "f")
check("idempotency: same key, same request while in progress -> 'in_progress', no second hold",
      psql("SELECT lease_reserve('idem', 500, 'A')", settings=S)[0] == "reserved" and psql("SELECT lease_reserve('idem', 500, 'A')", settings=S)[0] == "in_progress" and psql("SELECT held FROM account")[0] == "500")
check("idempotency: same key with a DIFFERENT request -> 'conflict', nothing changes", psql("SELECT lease_reserve('idem', 500, 'B')", settings=S)[0] == "conflict" and psql("SELECT held FROM account")[0] == "500")
ti = psql("SELECT lease_claim('idem','W')", settings=S)[0]
check("idempotency: after the result is saved, any number of retries get the saved result and one charge",
      psql(f"SELECT lease_commit('idem', {ti}, 450, 'actual=450')")[0] == "committed" and all(psql("SELECT lease_reserve('idem', 500, 'A')", settings=S)[0] == "saved" for _ in range(5)) and psql("SELECT charged FROM account")[0] == "450")
def refused(sql):
    r = subprocess.run(["psql","-X","-q","-A","-t","-h",SOCK,"-p",PORT,"-U","postgres","-d",DB,"-v","ON_ERROR_STOP=1","-c",sql], capture_output=True, text=True); return r.returncode != 0
check("duplicate-charge protection: a second charge line for the same request is refused by the database", refused("INSERT INTO charges(op, amount) VALUES ('idem', 450)"))
check("duplicate-charge protection: marking a request settled twice is refused by the database", refused("UPDATE ops SET settle_count = settle_count + 1 WHERE id = 'idem'"))
check("duplicate-charge protection: a second saved result for the same request is refused", refused("INSERT INTO results(op, token, body) VALUES ('idem', 99, 'actual=1')"))
check("duplicate-charge protection: a repeated commit with any ticket charges nothing more",
      psql(f"SELECT lease_commit('idem', {ti}, 450, 'x')")[0] == "already_saved" and psql("SELECT lease_commit('idem', 99, 450, 'x')")[0] == "already_saved" and psql("SELECT charged FROM account")[0] == "450")
check("audit after limits, conflicts and refused duplicates", psql(f"SELECT lease_audit({START})")[0] == "ok", psql(f"SELECT lease_audit({START})")[0])

bad = results.count(False); print(f"\n{len(results)-bad}/{len(results)} checks passed"); sys.exit(1 if bad else 0)
