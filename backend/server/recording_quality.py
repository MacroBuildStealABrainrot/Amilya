"""Is a recording reliable enough to chart from? (owner principle 2026-11-07)

The goal is not a report from every recording. The goal is a report that accurately reflects the encounter, and a
clear alert when the recording is not reliable enough. This applies to every role and every document format.

Two signals, either is enough:
- free and fixed: how much of a spoken source was made-up filler or loops from the speech service, or flagged as
  background speech;
- the report AI's own judgement: it adds an alert with field "recording_quality" when much of the recording was
  background (TV, other conversations) or unclear.
Nothing here ever changes the clinical text; it only adds one plain alert, or a plain message when no note is written.
"""
from __future__ import annotations

import re

from .transcript_cleaning import clean_transcript

UNRELIABLE_ALERT = ('Recording quality: much of this recording was background noise, other conversations or unclear speech. '
                    'Only clearly heard details are in this note; check it against the visit before signing.')
UNRELIABLE_NO_NOTE = ('This recording was mostly background noise, other conversations or unclear speech, so too little was clear '
                      'to write an accurate note. Record a short summary of what you assessed and did, or type the key findings.')
_WORD = re.compile(r"[A-Za-z0-9']+")
_LOOP_OR_BACKGROUND = re.compile(r'repeated by mistake|background audio|background noise|another language', re.I)


def _words(text: str) -> int:
    return len(_WORD.findall(text or ''))


def free_signals(context: dict) -> list[str]:
    """Reasons a spoken source looks unreliable, from fixed rules only (no AI, no cost). Never patient words."""
    reasons = []
    for source in (context or {}).get('untrusted_sources', []):
        if not isinstance(source, dict) or source.get('kind') != 'audio':
            continue
        raw = str(source.get('text') or '')
        raw_words = _words(raw)
        if raw_words >= 40:
            share = 1 - _words(clean_transcript(raw) or '') / raw_words
            if share >= 0.25:
                reasons.append('mostly_filler')
        if any(isinstance(w, str) and _LOOP_OR_BACKGROUND.search(w) for w in source.get('warnings') or []):
            reasons.append('speech_service_warning')
        seconds = source.get('duration_seconds')
        if isinstance(seconds, (int, float)) and seconds >= 180 and raw_words / (seconds / 60) < 25:
            reasons.append('mostly_silence')
    return list(dict.fromkeys(reasons))


def apply(result: dict, context: dict) -> dict:
    """Adds the one plain recording-quality alert first, and marks the result so a no-note answer explains why."""
    if not isinstance(result, dict):
        return result
    alerts = [a for a in result.get('ui_alerts') or [] if isinstance(a, dict)]
    model_flag = any(a.get('field') == 'recording_quality' or str(a.get('message', '')).startswith('Recording quality') for a in alerts)
    reasons = free_signals(context) + (['report_ai'] if model_flag else [])
    alerts = [a for a in alerts if a.get('field') != 'recording_quality' and not str(a.get('message', '')).startswith('Recording quality')]
    if reasons:
        result['recording_unreliable'] = reasons
        if str(result.get('text') or '').strip():
            alerts = [{'field': 'clarify', 'message': UNRELIABLE_ALERT}] + alerts
    result['ui_alerts'] = alerts
    return result
