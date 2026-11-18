"""Make a recording easier to hear before speech-to-text, and drop words the speech service guessed during silence.

1. prepare(): a speech-band, loudness-levelled copy (mono, 16 kHz, lossless FLAC). Rumble and hiss outside the voice range
   are cut and quiet speech is lifted to a steady level. The timing is unchanged (nothing is cut out), so every timestamp
   still points to the same moment in the original recording. If anything fails, the original is used unchanged.
   (Gain is capped so silence stays silent.)
2. drop_guessed(): Whisper reports, for every stretch of text, how likely it was silence (no_speech_prob) and how unsure it
   was (avg_logprob). A stretch that was probably silence AND very unsure is the classic made-up line ("Thank you.").
   It is left out of the working transcript; the original transcript is always kept, and the clinician is told.
   A stretch with a number, negation or side is never dropped.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import tempfile
from pathlib import Path

# Gentle levelling with a gain cap: lifts quiet speech without turning silence into hiss the speech service then
# fills with made-up words (seen with full loudness normalisation on 2026-11-07).
SPEECH_FILTER = 'highpass=f=80,lowpass=f=7800,dynaudnorm=f=250:g=15:p=0.9:m=6'
MAX_PREPARED_BYTES = 24 * 1024 * 1024
_PROTECTED = re.compile(r'\d|\b(no|not|denies|denied|without|left|right|bilateral|mg|ml|cm)\b', re.I)


def prepare(data: bytes, mime: str) -> tuple[bytes, str]:
    executable = shutil.which('ffmpeg')
    if not executable or not data:
        return data, mime
    try:
        with tempfile.TemporaryDirectory(prefix='wcp-speech-') as folder:
            source, target = Path(folder) / 'input.media', Path(folder) / 'speech.flac'
            source.write_bytes(data)
            subprocess.run([executable, '-v', 'error', '-nostdin', '-protocol_whitelist', 'file,pipe', '-i', str(source), '-vn',
                            '-af', SPEECH_FILTER, '-ac', '1', '-ar', '16000', '-c:a', 'flac', str(target)],
                           check=True, capture_output=True, timeout=120, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            prepared = target.read_bytes()
        if not prepared or len(prepared) > MAX_PREPARED_BYTES:
            return data, mime
        return prepared, 'audio/flac'
    except Exception:
        return data, mime


def drop_guessed(text: str, segments: list) -> tuple[str, list, int]:
    """Returns (working text, kept segments, how many stretches were dropped)."""
    if not isinstance(text, str) or not segments:
        return text, segments, 0
    kept, dropped = [], 0
    out = text
    for segment in segments:
        words = str(segment.get('text') or '').strip() if isinstance(segment, dict) else ''
        silence, unsure = segment.get('no_speech_prob') if isinstance(segment, dict) else None, segment.get('avg_logprob') if isinstance(segment, dict) else None
        guessed = (isinstance(silence, (int, float)) and isinstance(unsure, (int, float)) and silence > 0.6 and unsure < -1.0
                   and words and not _PROTECTED.search(words) and len(words.split()) <= 12)
        if guessed and words in out:
            out = out.replace(words, ' ', 1)
            dropped += 1
            continue
        kept.append(segment)
    if dropped:
        out = re.sub(r'[ \t]{2,}', ' ', out).strip()
    return out, kept, dropped
