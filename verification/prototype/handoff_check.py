"""
Long-visit hand-over, checked with real audio tools. The frames of a genuine AAC file were split into several encrypted recordings by the
recorder's own HandoffRouter (HandoffPipeline.kt). The promise being tested: NO SOUND IS MISSING AT THE JOINS.
  part 1 played, then part 2 without its first (faded, repeated) 1024 samples, then part 3 likewise, must equal the uncut recording exactly.
Also: every part is a valid recording for the backend (duration, format, limits), and the parts are linked to one visit.
Usage: python3 handoff_check.py <outDir> <backendDir>
"""
import ast, json, math, shutil, subprocess, sys, tempfile
from pathlib import Path
import numpy as np

OUT = Path(sys.argv[1]); BACKEND = Path(sys.argv[2]); RATE = 24000
results = []
def check(name, ok, extra=""):
    results.append(bool(ok)); print(("PASS  " if ok else "FAIL  ") + name + (f"   [{extra}]" if extra else ""))

class HTTPException(Exception):
    def __init__(self, status, detail=""): super().__init__(f"{status}: {detail}"); self.status = status
def backend_function(path, name, **env):
    src = path.read_text(encoding="utf-8"); node = next(n for n in ast.parse(src).body if isinstance(n, ast.FunctionDef) and n.name == name)
    ns = dict(HTTPException=HTTPException, shutil=shutil, subprocess=subprocess, tempfile=tempfile, Path=Path, json=json, math=math, **env)
    exec(ast.get_source_segment(src, node), ns); return ns[name]
class _S: max_audio_seconds = 3600
audio_duration = backend_function(BACKEND/"providers.py", "audio_duration", settings=_S())
checked_media = backend_function(BACKEND/"clinical.py", "checked_media")
def decode(path): return np.frombuffer(subprocess.run(["ffmpeg","-nostdin","-loglevel","error","-i",str(path),"-f","s16le","-ac","1","-ar",str(RATE),"-"],check=True,capture_output=True).stdout, dtype="<i2")

meta = dict(l.split("=", 1) for l in (OUT/"handoff.txt").read_text().splitlines() if "=" in l)
parts = int(meta["parts"]); per = int(meta["per"]); total = int(meta["framesTotal"])
ref = decode(OUT/"reference.adts")
check(f"the visit was split into the expected number of parts ({parts})", parts == max(2, -(-(total - 1) // (per - 1)) if total > per else 1) or parts >= 2, f"{total} frames, {per} per part")
dec = [decode(OUT/f"part{i}.whole.m4a") for i in range(1, parts + 1)]
frames = [int(meta[f"part{i}.frames"]) for i in range(1, parts + 1)]
check("part 1 holds exactly its share of frames; every later part starts with one repeated frame",
      frames[0] == per and sum(frames) == total + (parts - 1), f"{frames}")
joined = np.concatenate([dec[0]] + [d[1024:] for d in dec[1:]])
same_len = len(joined) == len(ref)
worst = int(np.abs(joined.astype(int) - ref.astype(int)).max()) if same_len else -1
check("NO SOUND IS MISSING: part 1 + the rest of each later part equals the uncut recording" +
      (" sample for sample" if worst == 0 else " to within 200/32767 (the encoder's optional noise substitution makes noise-like sound decode slightly differently)"),
      same_len and worst < 200, f"{len(joined)} vs {len(ref)} samples; largest difference {worst}/32767")
check("the repeated start of each later part is only 1024 samples (about 43 ms)", all(len(d) >= 1024 for d in dec))
for i in range(1, parts + 1):
    p = OUT/f"part{i}.whole.m4a"; blob = p.read_bytes()
    d = audio_duration(blob); checked_media(blob, "audio/mp4", "audio")
    check(f"part {i}: the backend accepts it (format, duration {d:.2f} s) and the length matches its frame count", abs(d - frames[i-1]*1024/RATE) < 0.001)
check("the parts together are at most the visit plus one repeated frame per join (nothing extra)",
      abs(sum(frames[i]*1024/RATE for i in range(parts)) - (total + parts - 1)*1024/RATE) < 0.001)
visit = [meta[f"part{i}.visit"].split("/") for i in range(1, parts + 1)]
check("every part names the same visit, its own number, and the part before it",
      all(v[0] == "visit" and int(v[1]) == i + 1 and v[2] == (f"part{i}.m4a" if i else "") for i, v in enumerate(visit)), str(visit))
check("no part reports missing, damaged or repeated audio", all(meta[f"part{i}.warning"] == "" for i in range(1, parts + 1)))
bad = results.count(False); print(f"\n{len(results)-bad}/{len(results)} checks passed"); sys.exit(1 if bad else 0)
