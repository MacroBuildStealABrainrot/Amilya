#!/bin/sh
# Rebuilds the genuine-AAC test fixtures with ffmpeg. Made-up sound only (a tone that changes every second plus a gentle
# speech-like wobble), so every second of a recording has a different, recognisable pitch. No real voice, no patient data.
# Output: AAC-LC, 24 kHz, mono, about 32 kbit/s, ADTS framing: the same shape the phone's encoder produces.
set -e
OUT="${1:?output folder}"; mkdir -p "$OUT"
EXPR='0.45*sin(2*PI*(300+40*mod(floor(t),40))*t)*(0.65+0.35*sin(2*PI*3.1*t))'
ffmpeg -nostdin -loglevel error -y -f lavfi -i "aevalsrc=exprs='${EXPR}':s=24000:d=${2:-60}" -c:a aac -b:a 32k -ar 24000 -ac 1 -f adts "$OUT/tone-visit-${2:-60}s.aac"
# Synthetic SPEECH (computer-made voice reading made-up text, 4 times over; no real person, no patient data). Needs an ffmpeg built with the flite filter.
# -aac_pns 0: the phone's own encoder does not use noise substitution, so this matches it better (see PROTOTYPE-RESULTS.md).
SP="$(dirname "$0")/../android/app/src/androidTestSandbox/assets/fixtures/speech-flite-synthetic.txt"
if [ -f "$SP" ] && ffmpeg -hide_banner -filters 2>/dev/null | grep -q flite; then
  T="$(mktemp -d)"; for i in 1 2 3 4; do cat "$SP"; done > "$T/t.txt"
  ffmpeg -nostdin -loglevel error -y -f lavfi -i "flite=textfile=$T/t.txt:voice=slt" -ar 24000 -ac 1 "$T/s.wav"
  ffmpeg -nostdin -loglevel error -y -i "$T/s.wav" -c:a aac -aac_pns 0 -b:a 32k -ar 24000 -ac 1 -f adts "$OUT/speech-flite-synthetic.aac"
fi
