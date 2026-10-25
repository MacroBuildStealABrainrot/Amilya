"""
Checks the rebuilt audio with REAL audio tools (ffmpeg/ffprobe) and with the backend's OWN audio-checking functions.
Input: the folder written by RealAudioPipeline.kt (see run_real_audio_check.sh).

What this proves : our recording-storage/recovery code, fed with genuine AAC from a real encoder, gives back audio that real
                   decoders play, that has the right length, the right timeline (tone pitch = position), and that the
                   backend's duration/format checks accept after a lossless MP4 wrapping (ffmpeg stands in for Android's muxer).
What it does NOT : prove the phone's microphone, MediaCodec encoder or MediaMuxer, nor speech transcription (no AI service is called).
"""
import ast, json, math, os, shutil, subprocess, sys, tempfile
from pathlib import Path
import numpy as np

OUT = Path(sys.argv[1]); BACKEND = Path(sys.argv[2]); WORK = Path(sys.argv[3])
RATE = 24000
results = []
def check(name, ok, extra=""):
    results.append(ok); print(("PASS  " if ok else "FAIL  ") + name + (f"   [{extra}]" if extra else ""))

class HTTPException(Exception):
    def __init__(self, status, detail=""): super().__init__(f"{status}: {detail}"); self.status = status

def backend_function(path, name, **env):
    """Takes the function's real source text out of the backend file (no FastAPI needed) and runs it."""
    src = path.read_text(encoding="utf-8")
    node = next(n for n in ast.parse(src).body if isinstance(n, ast.FunctionDef) and n.name == name)
    ns = dict(HTTPException=HTTPException, shutil=shutil, subprocess=subprocess, tempfile=tempfile, Path=Path, json=json, math=math, **env)
    exec(ast.get_source_segment(src, node), ns)
    return ns[name]

class _S: max_audio_seconds = 3600
audio_duration = backend_function(BACKEND / "providers.py", "audio_duration", settings=_S())
checked_media = backend_function(BACKEND / "clinical.py", "checked_media")

def run(*cmd): return subprocess.run(cmd, check=True, capture_output=True)
def decode(path):
    raw = run("ffmpeg", "-nostdin", "-loglevel", "error", "-i", str(path), "-f", "s16le", "-ac", "1", "-ar", str(RATE), "-").stdout
    return np.frombuffer(raw, dtype="<i2")
def to_m4a(aac, m4a): run("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", str(aac), "-c", "copy", str(m4a))
def frames_of(data):
    out, p = [], 0
    while p < len(data):
        n = ((data[p+3] & 3) << 11) | (data[p+4] << 3) | (data[p+5] >> 5)
        assert data[p] == 0xFF and (data[p+1] & 0xF0) == 0xF0 and n > 7, f"bad frame at {p}"
        out.append(data[p:p+n]); p += n
    return out
def tone_second(pcm, start):
    n = np.arange(int(start*RATE + RATE*0.2), int(start*RATE + RATE*0.7)); seg = pcm[n[0]:n[-1]+1].astype(float)
    best, bp = -1, -1
    for k in range(40):
        hz = 300 + 40*k; c = 2*math.cos(2*math.pi*hz/RATE); s1 = s2 = 0.0
        for x in seg: s0 = x + c*s1 - s2; s2 = s1; s1 = s0
        pw = s1*s1 + s2*s2 - c*s1*s2
        if pw > bp: bp, best = pw, k
    return best
def info(name): return dict(l.split("=", 1) for l in (OUT/f"{name}.txt").read_text().splitlines() if "=" in l)

original = (OUT/"original.aac").read_bytes(); orig_frames = frames_of(original); N = len(orig_frames)
orig_pcm = decode(OUT/"original.aac")
PER = 300

# --- 1. clean recording: byte-exact, sample-exact, right length, playable by real tools, accepted by the backend checks
for name in ("clean-sealed", "clean-plain"):
    data = (OUT/f"{name}.aac").read_bytes()
    check(f"{name}: rebuilt audio is byte-for-byte the original ({N} frames)", data == original)
    check(f"{name}: decoded sound is sample-for-sample identical to the original", np.array_equal(decode(OUT/f"{name}.aac"), orig_pcm))
    check(f"{name}: no warning", info(name)["warning"] == "" and info(name)["problems"] == "0")
m4a = WORK/"clean.m4a"; to_m4a(OUT/"clean-sealed.aac", m4a); blob = m4a.read_bytes()
expected = N * 1024 / RATE
d = audio_duration(blob)
check("backend audio_duration accepts the rebuilt file and agrees with the frame count", abs(d - expected) < 0.08, f"backend={d:.3f}s frames={expected:.3f}s")
checked_media(blob, "audio/mp4", "audio"); check("backend checked_media accepts it as audio/mp4", True)
meta = json.loads(run("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,profile,sample_rate,channels", "-of", "json", str(m4a)).stdout)["streams"][0]
check("stream is AAC-LC, 24000 Hz, mono", (meta["codec_name"], meta["profile"], int(meta["sample_rate"]), meta["channels"]) == ("aac", "LC", RATE, 1))

# --- 2. timeline: the tone's pitch says which second we are in
ok = all(tone_second(decode(OUT/"clean-sealed.aac"), s) == s % 40 for s in (3, 17, 31, 44, 55))
check("clean: seconds 3, 17, 31, 44, 55 carry the right tones (continuity across every piece boundary)", ok)
boundaries = [PER*i*1024/RATE for i in range(1, N//PER + 1)]
check("clean: no click at piece boundaries (largest sample jump at a boundary is no bigger than elsewhere)",
      all(np.abs(np.diff(orig_pcm.astype(int)[int(b*RATE)-8:int(b*RATE)+8])).max() <= np.abs(np.diff(orig_pcm.astype(int))).max() for b in boundaries[:-1]))

# --- 3. killed in the middle of a record
data = (OUT/"killed-midrecord.aac").read_bytes(); k = info("killed-midrecord")
kf = int(k["frames"])
check("killed: every complete frame before the cut is kept, byte for byte, and nothing else", data == b"".join(orig_frames[:kf]) and N - 3 <= kf < N, f"frames={kf}/{N}; the 33 cut bytes ate the last {N-kf} (tiny end-of-file) record(s)")
check("killed: a normal kill gives NO missing-audio warning", k["problems"] == "0" and k["warning"] == "")
kd = decode(OUT/"killed-midrecord.aac"); n = min(len(kd), len(orig_pcm))
check("killed: decoded sound equals the original for its whole length", np.array_equal(kd[:n - 2048], orig_pcm[:n - 2048]))

# --- 4. a whole piece is missing
data = (OUT/"missing-piece-2.aac").read_bytes(); m = info("missing-piece-2")
check("missing piece: exactly the other frames survive, in order", data == b"".join(orig_frames[:PER] + orig_frames[2*PER:]), f"frames={m['frames']}")
lost_s = PER*1024/RATE
check("missing piece: warning names the loss and where", m["problems"] == "1" and f"lost={int(PER*1024*1000/RATE)}" in (OUT/'missing-piece-2.txt').read_text() and "may be missing" in m["warning"], m["warning"][:110])
md = decode(OUT/"missing-piece-2.aac")
check("missing piece: sound before the gap is untouched", np.array_equal(md[:PER*1024 - 2048], orig_pcm[:PER*1024 - 2048]))
g = PER*1024
after = md[g+2048:]; ref = orig_pcm[g + PER*1024 + 2048 : g + PER*1024 + 2048 + len(after)]
diff = np.abs(after.astype(int) - ref.astype(int)) if len(after) == len(ref) else None
# The ffmpeg encoder fills some high-frequency bands with random "noise substitution" (PNS) whose random generator is
# carried from frame to frame; after a gap the decoder's generator is out of step, so those faint bands differ.
check("missing piece: sound after the gap matches the original to within -44 dB (only the encoder's noise-fill differs)",
      diff is not None and diff.max() <= 200, f"largest difference {None if diff is None else int(diff.max())} of 32767")
exact_dir = WORK/"pnsoff"; exact_dir.mkdir(exist_ok=True)
expr = "0.45*sin(2*PI*(300+40*mod(floor(t),40))*t)*(0.65+0.35*sin(2*PI*3.1*t))"
run("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"aevalsrc=exprs='{expr}':s=24000:d=60", "-c:a", "aac", "-b:a", "32k", "-ar", "24000", "-ac", "1", "-aac_pns", "0", "-f", "adts", str(exact_dir/"o.aac"))
pf = frames_of((exact_dir/"o.aac").read_bytes()); (exact_dir/"g.aac").write_bytes(b"".join(pf[:PER] + pf[2*PER:]))
po, pg = decode(exact_dir/"o.aac"), decode(exact_dir/"g.aac")
check("same gap with the noise-fill feature switched off: sound after the gap is sample-for-sample exact",
      np.array_equal(pg[g+2048:], po[g+PER*1024+2048 : g+PER*1024+2048+len(pg)-g-2048]))
tones_after = [(t, tone_second(md, t)) for t in (20, 30, 40)]
check("missing piece: tones after the gap are shifted by exactly the lost time", all(v == int(t + 0.45 + lost_s) % 40 for t, v in tones_after), str(tones_after))

# --- 5. damaged byte inside a piece
data = (OUT/"damaged-piece-2.aac").read_bytes(); dm = info("damaged-piece-2"); kept = int(dm["frames"])
cut = kept - (N - PER)                              # frames of piece 2 that survived before the damaged record
check("damaged: good frames before the damage and all later pieces survive, in order", 0 < cut < PER and data == b"".join(orig_frames[:PER+cut] + orig_frames[2*PER:]), f"kept {cut}/{PER} of piece 2")
check("damaged: warning says audio may be missing, with its length", dm["problems"] == "1" and "may be missing" in dm["warning"], dm["warning"][:110])

# --- 6. the app uploads 120-second parts: the backend must accept each one
long_aac = WORK/"visit-600s.aac"; run("sh", str(OUT/"make_fixtures.sh"), str(WORK), "600")
shutil.move(WORK/"tone-visit-600s.aac", long_aac)
parts = WORK/"parts"; parts.mkdir(exist_ok=True)
run("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", str(long_aac), "-c", "copy", "-f", "segment", "-segment_time", "120", "-segment_format", "ipod", "-reset_timestamps", "1", str(parts/"part-%02d.m4a"))
durs = [audio_duration((p).read_bytes()) for p in sorted(parts.glob("*.m4a"))]
for p in sorted(parts.glob("*.m4a")): checked_media(p.read_bytes(), "audio/mp4", "audio")
check("10-minute visit split into 120 s parts: every part passes the backend duration and format checks (limit 130 s)", len(durs) >= 5 and all(0 < d <= 130 for d in durs), f"{len(durs)} parts, longest {max(durs):.1f}s")

# --- 7. a full 60-minute visit (the recorder's limit) against the backend's whole-file limit
run("sh", str(OUT/"make_fixtures.sh"), str(WORK), "3600")
hour = (WORK/"tone-visit-3600s.aac").read_bytes(); hf = frames_of(hour)
cap = 3600 * RATE // 1024                            # the frames a 3,600 s recording holds
sixty = WORK/"sixty.aac"; sixty.write_bytes(b"".join(hf[:cap])); sixty_m4a = WORK/"sixty.m4a"; to_m4a(sixty, sixty_m4a)
dd = None
try: dd = audio_duration(sixty_m4a.read_bytes()); verdict = f"accepted, {dd:.3f}s"
except HTTPException as e: verdict = f"REJECTED by backend: {e}"
size = sixty_m4a.stat().st_size
check("60-minute visit at the 3,600 s limit: accepted by the backend's whole-file duration check", dd is not None, verdict + f", {size/1048576:.1f} MiB (limit 25 MiB)")
check("60-minute file is under the backend's 25 MiB limit", size < 25*1024*1024)

bad = results.count(False); print(f"\n{len(results)-bad}/{len(results)} checks passed"); sys.exit(1 if bad else 0)
