"""Create a file-backed ALSA capture device and check the headless runner's synthetic audio source."""
import array
import math
import os
from pathlib import Path
import sys

RATE = 48000
work_dir = Path(os.environ["RUNNER_TEMP"])


def prepare():
    tone = work_dir / "notes-mic-440.raw"
    one_second = array.array("h", (round(18000 * math.sin(2 * math.pi * 440 * i / RATE)) for i in range(RATE)))
    with tone.open("wb") as stream:
        for _ in range(600):
            stream.write(one_second.tobytes())
    (work_dir / "notes.asoundrc").write_text(f"""</usr/share/alsa/alsa.conf>
pcm.notes_source {{
    type file
    slave.pcm "null"
    file "/dev/null"
    infile "{tone}"
    format "raw"
}}
pcm.notes_in {{
    type plug
    slave {{ pcm "notes_source" format S16_LE channels 1 rate 48000 }}
}}
pcm.!default {{
    type asym
    playback.pcm "null"
    capture.pcm "notes_in"
}}
""")


def verify(filename):
    values = array.array("h")
    values.frombytes(Path(filename).read_bytes())
    crossings = sum(a < 0 <= b for a, b in zip(values, values[1:]))
    if len(values) != 2 * RATE or not 870 <= crossings <= 890:
        raise SystemExit(f"Virtual microphone preflight failed: {len(values)} PCM frames, {crossings} crossings")
    print(f"Host virtual microphone verified: {len(values)} PCM frames, {crossings} tone crossings over two seconds.")


if __name__ == "__main__":
    if sys.argv[1] == "prepare":
        prepare()
    else:
        verify(sys.argv[2])
