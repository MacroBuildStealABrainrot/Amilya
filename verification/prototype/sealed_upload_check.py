"""
Phase 2 PC check. Examines the .m4a files that Mp4Aac built IN MEMORY (see SealedUploadPipeline.kt) with real audio tools and
with the backend's OWN audio functions. What it proves: the hand-written MP4 writer produces files that ffprobe/ffmpeg read,
that decode to exactly the same sound as the original AAC frames, that the backend accepts (duration, format, 130 s per part),
with the same bytes every time. What it does NOT prove: that Android's own MediaPlayer/MediaExtractor accept the file (only a
phone can), or anything about speech.
"""
import ast, hashlib, json, math, shutil, struct, subprocess, sys, tempfile
from pathlib import Path
import numpy as np

OUT = Path(sys.argv[1]); BACKEND = Path(sys.argv[2]); RATE = 24000
results = []
def check(name, ok, extra=""):
    results.append(bool(ok)); print(("PASS  " if ok else "FAIL  ") + name + (f"   [{extra}]" if extra else ""))

class HTTPException(Exception):
    def __init__(self, status, detail=""): super().__init__(f"{status}: {detail}"); self.status = status
def backend_function(path, name, **env):
    src = path.read_text(encoding="utf-8")
    node = next(n for n in ast.parse(src).body if isinstance(n, ast.FunctionDef) and n.name == name)
    ns = dict(HTTPException=HTTPException, shutil=shutil, subprocess=subprocess, tempfile=tempfile, Path=Path, json=json, math=math, **env)
    exec(ast.get_source_segment(src, node), ns); return ns[name]
class _S: max_audio_seconds = 3600
audio_duration = backend_function(BACKEND/"providers.py", "audio_duration", settings=_S())
checked_media = backend_function(BACKEND/"clinical.py", "checked_media")

def run(*c): return subprocess.run(c, check=True, capture_output=True)
def decode(path, extra=()):
    return np.frombuffer(run("ffmpeg","-nostdin","-loglevel","error",*extra,"-i",str(path),"-f","s16le","-ac","1","-ar",str(RATE),"-").stdout, dtype="<i2")
def probe(path):
    return json.loads(run("ffprobe","-v","error","-show_entries","stream=codec_name,profile,sample_rate,channels,duration,nb_frames:format=duration,format_name","-of","json",str(path)).stdout)
def info(name): return dict(l.split("=",1) for l in (OUT/f"{name}.txt").read_text().splitlines() if "=" in l)
def boxes(data, start=0, end=None, depth=0, path=""):
    end = len(data) if end is None else end; pos = start; seen = []
    while pos < end:
        size, kind = struct.unpack(">I4s", data[pos:pos+8]); kind = kind.decode("latin1")
        if size < 8 or pos + size > end: return None
        seen.append((path + "/" + kind, pos, size))
        if kind in ("moov","trak","mdia","minf","stbl","dinf") and depth < 6:
            inner = boxes(data, pos+8, pos+size, depth+1, path+"/"+kind)
            if inner is None: return None
            seen += inner
        pos += size
    return seen if pos == end else None
def tone_second(pcm, start):
    n0 = int(start*RATE + RATE*0.2); n1 = int(start*RATE + RATE*0.7); seg = pcm[n0:n1].astype(float); best, bp = -1, -1
    for k in range(40):
        hz = 300 + 40*k; c = 2*math.cos(2*math.pi*hz/RATE); s1 = s2 = 0.0
        for x in seg: s0 = x + c*s1 - s2; s2 = s1; s1 = s0
        pw = s1*s1 + s2*s2 - c*s1*s2
        if pw > bp: bp, best = pw, k
    return best

# 1. one-minute visit, whole file
name = "one-minute"; meta = info(name); frames = int(meta["frames"]); whole = OUT/f"{name}.whole.m4a"; blob = whole.read_bytes()
check("whole file is well-formed MP4 (every box fits exactly inside its parent)", boxes(blob) is not None)
tree = [b[0] for b in boxes(blob)]
check("has the boxes a player needs (ftyp, moov/trak/mdia/minf/stbl: stsd stts stsc stsz stco, mdat)",
      all(x in tree for x in ["/ftyp","/moov","/moov/trak/mdia/minf/stbl/stsd","/moov/trak/mdia/minf/stbl/stts","/moov/trak/mdia/minf/stbl/stsc","/moov/trak/mdia/minf/stbl/stsz","/moov/trak/mdia/minf/stbl/stco","/mdat"]))
p = probe(whole)["streams"][0]
check("ffprobe: AAC-LC, 24000 Hz, mono, correct frame count", (p["codec_name"],p["profile"],int(p["sample_rate"]),p["channels"],int(p["nb_frames"])) == ("aac","LC",RATE,1,frames), f"{p}")
orig = decode(Path(sys.argv[3]))
mine = decode(whole)
check("decoded sound equals the original recording sample for sample", np.array_equal(mine, orig), f"{len(mine)} vs {len(orig)} samples")
adts_pcm = decode(OUT/f"{name}.adts")
check("decoded sound equals the same frames as a plain ADTS stream", np.array_equal(mine, adts_pcm))
d = audio_duration(blob); check("backend audio_duration agrees with the frame count", abs(d - frames*1024/RATE) < 0.001, f"{d:.4f}s")
checked_media(blob, "audio/mp4", "audio"); check("backend checked_media accepts it as audio/mp4", True)
if "tone" in Path(sys.argv[3]).name: check("tones at seconds 3, 17, 31, 44, 55 are right", all(tone_second(mine, s) == s % 40 for s in (3,17,31,44,55)))
else: print("      (tone check skipped: this fixture is speech)")
# the same file made by ffmpeg, for comparison of structure
ref = OUT/"ffmpeg-reference.m4a"; run("ffmpeg","-nostdin","-loglevel","error","-y","-i",sys.argv[3],"-c","copy",str(ref))
ref_tree = {b[0] for b in boxes(ref.read_bytes())}
check("every kind of box in the file made by ffmpeg's muxer that a player needs is also present in ours",
      {"/ftyp","/moov","/mdat"}.issubset(set(tree)) and {b for b in ref_tree if b.endswith(("stsd","stts","stsc","stsz","stco"))}.issubset(set(tree)))

# 2. ten-minute visit cut into parts
name = "ten-minutes"; meta = info(name); frames = int(meta["frames"]); parts = int(meta["parts"])
files = sorted(OUT.glob(f"{name}.part-*.m4a")); check("part count matches", len(files) == parts == len(files), f"{parts} parts")
durs = []; all_ok = True; sizes = []
for f in files:
    b = f.read_bytes(); sizes.append(len(b)); dd = audio_duration(b); durs.append(dd)
    try: checked_media(b, "audio/mp4", "audio")
    except Exception: all_ok = False
    if dd > 130 or len(b) > 4*1024*1024: all_ok = False
check("every part passes the backend's checks (format, <= 130 s, <= 4 MB)", all_ok, f"longest {max(durs):.2f}s, biggest {max(sizes)/1024:.0f} KiB")
total = sum(durs); check("parts add up to the whole visit (within 1 ms per part)", abs(total - frames*1024/RATE) < 0.001*parts, f"{total:.3f}s vs {frames*1024/RATE:.3f}s")
check("server's total-length rule (sum of parts <= 3602 s, at most 32 parts) is met", total <= 3602 and parts <= 32)
# Each part is decoded on its own, so the first ~0.05 s after a cut differs (the decoder has no memory of the earlier part). The
# app's existing splitter behaves the same way. Away from the cuts the only difference is the decoder's random "noise fill"
# generator, which restarts in each part: up to about 150 out of 32767 (-46 dB), the same figure as in the gap test.
pieces = [decode(f) for f in files]; joined = np.concatenate(pieces); whole_pcm = decode(OUT/f"{name}.whole.m4a")
cuts = np.cumsum([len(x) for x in pieces])[:-1]
same_len = len(joined) == len(whole_pcm)
diff = np.abs(joined.astype(int) - whole_pcm.astype(int)) if same_len else np.array([1 << 30])
mask = np.ones(len(diff), bool)
for c in cuts: mask[c:c + 2200] = False
check("parts played one after another are the same length as the whole visit", same_len, f"{len(joined)} samples")
worst = int(diff[mask].max()) if same_len else -1
check("away from the cuts the sound matches the whole visit to within 200/32767 (-44 dB; decoder noise-fill only)", same_len and worst < 200, f"largest difference {worst}/32767")
# 3. same bytes every time (so a retried upload is recognised)
check("part 0 is byte-identical when built again after re-opening the pieces", (OUT/f"{name}.again-part-00.m4a").read_bytes() == files[0].read_bytes())
check("last part is byte-identical when built again", (OUT/f"{name}.again-part-last.m4a").read_bytes() == files[-1].read_bytes())
check("no clock values inside the file (nothing varies between two builds)", hashlib.sha256(files[0].read_bytes()).hexdigest() == hashlib.sha256((OUT/f"{name}.again-part-00.m4a").read_bytes()).hexdigest())

# 4. a missing piece still produces a playable file with a warning
name = "missing-piece"; meta = info(name)
check("missing piece: a warning is raised and a file is still produced", "missing" in meta["warning"] and (OUT/f"{name}.whole.m4a").exists(), meta["warning"][:90])
b = (OUT/f"{name}.whole.m4a").read_bytes(); checked_media(b, "audio/mp4", "audio")
check("missing piece: file plays and its length is the surviving frames", abs(audio_duration(b) - int(meta["frames"])*1024/RATE) < 0.001)
# 5. a killed recording
name = "killed"; meta = info(name); b = (OUT/f"{name}.whole.m4a").read_bytes()
check("killed mid-record: playable, no false warning", meta["warning"] == "" and abs(audio_duration(b) - int(meta["frames"])*1024/RATE) < 0.001, f"{meta['frames']} frames")

print(f"\n{sum(results)} of {len(results)} checks passed"); sys.exit(0 if all(results) else 1)
