"""
Stand-alone simulation of the proposed "expiring lease" for AI requests and credit holds.

SAFETY: uses a throw-away in-memory/temporary SQLite database with made-up numbers.
It imports nothing from backend/, touches no live billing code, and never connects to any real database.

What it checks (see STUCK-REQUEST-LEASE-PLAN.md):
  1. Only one worker can hold the lease for a request at a time.
  2. A worker that died is replaced only after its lease has expired.
  3. A late "zombie" worker cannot settle or release after someone else took over (fencing token).
  4. The cleanup job only gives credits back when no provider result was ever stored. It never charges.
  5. A stored result is always settled exactly once, by whoever finishes first.
  6. Credits are conserved: available + held + charged == starting total, at every step.
This is a model of the database rules in SQLite. It is NOT a test of the real PostgreSQL billing code.
"""
import os, random, sqlite3, tempfile, threading, sys

START = 1_000_000
LEASE_SECONDS = 120
REAPER_GRACE = 600  # only requests silent this long (no result) are given back

SCHEMA = """
CREATE TABLE account(id INTEGER PRIMARY KEY, available INTEGER, held INTEGER, charged INTEGER);
CREATE TABLE ops(id TEXT PRIMARY KEY, status TEXT, held INTEGER, lease_owner TEXT, lease_token INTEGER DEFAULT 0,
                 lease_expires REAL, last_beat REAL, result_micros INTEGER, created REAL);
"""

def connect(path):
    c = sqlite3.connect(path, timeout=30, isolation_level=None)
    c.execute("PRAGMA busy_timeout=30000")
    return c

def begin(c): c.execute("BEGIN IMMEDIATE")  # SQLite's version of the account row lock

def reserve(c, op, amount, now):
    begin(c)
    try:
        if c.execute("SELECT 1 FROM ops WHERE id=?", (op,)).fetchone():
            c.execute("COMMIT"); return "exists"
        avail, = c.execute("SELECT available FROM account WHERE id=1").fetchone()
        if avail < amount: c.execute("ROLLBACK"); return "insufficient"
        c.execute("UPDATE account SET available=available-?, held=held+? WHERE id=1", (amount, amount))
        c.execute("INSERT INTO ops(id,status,held,created) VALUES(?, 'reserved', ?, ?)", (op, amount, now))
        c.execute("COMMIT"); return "reserved"
    except Exception:
        c.execute("ROLLBACK"); raise

def claim(c, op, worker, now):
    """One atomic conditional UPDATE. Returns the new fencing token or None."""
    begin(c)
    cur = c.execute("""UPDATE ops SET lease_owner=?, lease_token=lease_token+1, lease_expires=?, last_beat=?
                       WHERE id=? AND status='reserved' AND result_micros IS NULL
                         AND (lease_owner IS NULL OR lease_expires<=?)""", (worker, now + LEASE_SECONDS, now, op, now))
    token = None
    if cur.rowcount == 1:
        token, = c.execute("SELECT lease_token FROM ops WHERE id=?", (op,)).fetchone()
    c.execute("COMMIT"); return token

def heartbeat(c, op, token, now):
    begin(c)
    cur = c.execute("UPDATE ops SET lease_expires=?, last_beat=? WHERE id=? AND status='reserved' AND lease_token=? AND lease_expires>?",
                    (now + LEASE_SECONDS, now, op, token, now))
    c.execute("COMMIT"); return cur.rowcount == 1

def store_result(c, op, token, actual):
    """Provider answered. Record the cost BEFORE settling so a crash can never lose the fact that work was done."""
    begin(c)
    cur = c.execute("UPDATE ops SET result_micros=? WHERE id=? AND status='reserved' AND lease_token=? AND result_micros IS NULL", (actual, op, token))
    c.execute("COMMIT"); return cur.rowcount == 1

def settle(c, op, token=None):
    """Charge the stored result. token=None is the completer path (any process may finish a stored result)."""
    begin(c)
    try:
        row = c.execute("SELECT held, result_micros, lease_token FROM ops WHERE id=? AND status='reserved'", (op,)).fetchone()
        if not row or row[1] is None or (token is not None and row[2] != token):
            c.execute("ROLLBACK"); return False
        held, actual, _ = row
        charge = min(actual, held)
        c.execute("UPDATE ops SET status='settled', held=0 WHERE id=? AND status='reserved'", (op,))
        c.execute("UPDATE account SET held=held-?, available=available+?, charged=charged+? WHERE id=1", (held, held - charge, charge))
        c.execute("COMMIT"); return True
    except Exception:
        c.execute("ROLLBACK"); raise

def release_own(c, op, token):
    """Worker saw the provider fail with no result."""
    begin(c)
    row = c.execute("SELECT held FROM ops WHERE id=? AND status='reserved' AND result_micros IS NULL AND lease_token=?", (op, token)).fetchone()
    if not row: c.execute("ROLLBACK"); return False
    c.execute("UPDATE ops SET status='released', held=0 WHERE id=?", (op,))
    c.execute("UPDATE account SET held=held-?, available=available+? WHERE id=1", (row[0], row[0]))
    c.execute("COMMIT"); return True

def reaper(c, now):
    """Gives credits back ONLY for silent requests with no stored result. Never charges. Returns (released, completed)."""
    begin(c)
    rows = c.execute("""SELECT id, held FROM ops WHERE status='reserved' AND result_micros IS NULL
                        AND (lease_expires IS NULL OR lease_expires<=?) AND COALESCE(last_beat, created)<=?""",
                     (now, now - REAPER_GRACE)).fetchall()
    released = 0
    for op, held in rows:
        cur = c.execute("UPDATE ops SET status='released', held=0, lease_token=lease_token+1 WHERE id=? AND status='reserved' AND result_micros IS NULL", (op,))
        if cur.rowcount == 1:
            c.execute("UPDATE account SET held=held-?, available=available+? WHERE id=1", (held, held)); released += 1
    c.execute("COMMIT")
    done = 0
    for op, in c.execute("SELECT id FROM ops WHERE status='reserved' AND result_micros IS NOT NULL").fetchall():
        done += settle(c, op)  # finish what the provider already answered
    return released, done

def invariant(c, label):
    a, h, ch = c.execute("SELECT available, held, charged FROM account WHERE id=1").fetchone()
    sheld, = c.execute("SELECT COALESCE(SUM(held),0) FROM ops WHERE status='reserved'").fetchone()
    assert a + h + ch == START, f"{label}: credits not conserved {a}+{h}+{ch}"
    assert h == sheld, f"{label}: account hold {h} != sum of open holds {sheld}"
    assert a >= 0 and h >= 0, f"{label}: negative balance"

def fresh():
    path = tempfile.mktemp(suffix=".sqlite", prefix="lease_sim_")
    c = connect(path); c.executescript(SCHEMA)
    c.execute("INSERT INTO account VALUES(1, ?, 0, 0)", (START,)); return path, c

RESULTS = []
def check(name, ok):
    RESULTS.append((name, ok)); print(("PASS  " if ok else "FAIL  ") + name)

def main():
    # 1. 50 threads race to claim the same request
    path, c = fresh(); reserve(c, "op1", 1000, 0.0)
    wins = []; barrier = threading.Barrier(50)
    def racer(i):
        cc = connect(path); barrier.wait(); t = claim(cc, "op1", f"w{i}", 1.0)
        if t: wins.append((i, t))
    ts = [threading.Thread(target=racer, args=(i,)) for i in range(50)]
    [t.start() for t in ts]; [t.join() for t in ts]
    check("50 simultaneous claims: exactly one winner", len(wins) == 1 and wins[0][1] == 1)
    invariant(c, "race")

    # 2 + 3. crash, takeover only after expiry, zombie fenced out
    token_a = claim(c, "op2", "A", 0.0)  # op2 does not exist yet
    check("claim of an unknown request is refused", token_a is None)
    reserve(c, "op2", 2000, 0.0); a = claim(c, "op2", "A", 10.0)
    check("worker A gets token 1", a == 1)
    check("worker B refused while A's lease is alive", claim(c, "op2", "B", 10.0 + LEASE_SECONDS - 1) is None)
    b = claim(c, "op2", "B", 10.0 + LEASE_SECONDS + 1)
    check("worker B takes over after expiry with token 2", b == 2)
    check("zombie A cannot store a result", store_result(c, "op2", a, 1500) is False)
    check("zombie A cannot release", release_own(c, "op2", a) is False)
    check("zombie A cannot heartbeat", heartbeat(c, "op2", a, 10.0 + LEASE_SECONDS + 2) is False)
    check("worker B stores result and settles", store_result(c, "op2", b, 1500) and settle(c, "op2", b))
    check("a second settle changes nothing", settle(c, "op2") is False and settle(c, "op2", b) is False)
    a_, h_, ch_ = c.execute("SELECT available, held, charged FROM account").fetchone()
    check("charged exactly the stored amount, difference returned", ch_ == 1500 and h_ == 1000 and a_ == START - 1000 - 1500)
    invariant(c, "takeover")

    # 4. reaper: releases silent requests, never charges, leaves stored results to the completer
    path, c = fresh(); reserve(c, "silent", 3000, 0.0); reserve(c, "answered", 4000, 0.0); reserve(c, "busy", 5000, 0.0)
    s = claim(c, "silent", "W1", 1.0); r = claim(c, "answered", "W2", 1.0); claim(c, "busy", "W3", 1.0)
    store_result(c, "answered", r, 3500)     # provider answered, worker then died before settling
    before_charged = c.execute("SELECT charged FROM account").fetchone()[0]
    rel, done = reaper(c, 1.0 + REAPER_GRACE - 5)   # too early: nothing silent long enough
    check("reaper does nothing before the grace period (except finishing stored results)", rel == 0)
    c.execute("UPDATE ops SET lease_expires=?, last_beat=? WHERE id='busy'", (1.0 + 10 * REAPER_GRACE, 1.0 + 10 * REAPER_GRACE))
    rel, done = reaper(c, 1.0 + REAPER_GRACE + 5)
    st = dict(c.execute("SELECT id, status FROM ops").fetchall())
    check("reaper releases the silent request", st["silent"] == "released" and rel == 1)
    check("reaper never releases a request whose answer was stored; it settles it", st["answered"] == "settled")
    check("reaper leaves a live (heartbeating) request alone", st["busy"] == "reserved")
    ch = c.execute("SELECT charged FROM account").fetchone()[0]
    check("the only charge is the stored provider cost (3500)", ch - before_charged == 3500)
    check("late worker W1 cannot settle a released request", store_result(c, "silent", s, 10) is False)
    invariant(c, "reaper")

    # 5. randomized stress with a fake clock: random worker deaths, random reaper runs, random zombies
    rng = random.Random(20261024)
    path, c = fresh(); now = 0.0; live = {}  # op -> (worker, token)
    ops = []; settled_twice = 0
    for step in range(6000):
        now += rng.random() * 40
        k = rng.random()
        if k < 0.25:
            op = f"s{step}"; 
            if reserve(c, op, rng.randint(100, 3000), now) == "reserved": ops.append(op)
        elif k < 0.55 and ops:
            op = rng.choice(ops); t = claim(c, op, f"w{step}", now)
            if t: live[op] = (f"w{step}", t)
        elif k < 0.70 and live:
            op = rng.choice(list(live)); w, t = live[op]
            if rng.random() < 0.7: store_result(c, op, t, rng.randint(50, 3000))
        elif k < 0.82 and live:
            op = rng.choice(list(live)); w, t = live[op]
            if settle(c, op, t): live.pop(op, None)
        elif k < 0.88 and live:
            op = rng.choice(list(live)); w, t = live[op]
            release_own(c, op, t)
        elif k < 0.95:
            reaper(c, now)
        else:  # a worker silently dies: forget it (its lease just runs out)
            if live: live.pop(rng.choice(list(live)))
        invariant(c, f"stress step {step}")
    # drain: let every lease expire, run the reaper repeatedly
    for _ in range(3):
        now += 10 * REAPER_GRACE; reaper(c, now)
    invariant(c, "after drain")
    left, = c.execute("SELECT COUNT(*) FROM ops WHERE status='reserved'").fetchone()
    held, = c.execute("SELECT held FROM account").fetchone()
    check(f"stress: 6000 random steps, credits conserved at every step", True)
    check(f"after everything expires no request is stuck (stuck={left}, held={held})", left == 0 and held == 0)
    total = c.execute("SELECT COUNT(*) FROM ops").fetchone()[0]
    print(f"      ({total} requests created in the stress run)")
    bad = [n for n, ok in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(bad)}/{len(RESULTS)} checks passed")
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
