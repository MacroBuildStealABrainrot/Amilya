"""Joining transcripts of audio pieces that overlap by a few seconds (sandbox build, 1.8.5-test10).

Each 2-minute piece after the first also contains the last ~3 seconds of the piece before it, so a word cut at the
boundary is heard whole at least once. The same few seconds are therefore transcribed twice. This module removes
the second copy ONLY when it is certain:

* the words are an exact match (after lower-casing and removing punctuation) between the END of the previous piece
  and the START of the next piece, longest match first;
* the matched words lie inside the overlap window of the next piece (by timestamps when the speech service gave
  them, otherwise by a conservative word limit);
* nothing is ever reworded, and every join is reported in plain words.

If the two copies do not match exactly (for example a dose heard as "5" in one and "15" in the other), BOTH are kept
and the join is flagged for review. Speech is never dropped on a guess. The full, unmerged text of every piece is
kept by the caller.
"""
from __future__ import annotations

import re

_WORD = re.compile(r'\S+')
WORDS_PER_SECOND_LIMIT = 4.0   # fast speech is about 3 words per second; the limit only bounds what may be matched
MAX_OVERLAP_SECONDS = 5.0


def _norm(token: str) -> str:
    token = token.casefold()
    token = re.sub(r"[^\w.%'-]+", '', token)
    return token.strip(".'-")


def _tokens(text: str):
    return [(m.start(), m.end(), _norm(m.group())) for m in _WORD.finditer(text or '')]


def _window_word_limit(overlap_seconds: float, segments) -> int:
    """How many leading words of the next piece may belong to the overlap."""
    if segments:
        words = 0
        for segment in segments:
            if not isinstance(segment, dict):
                continue
            start = segment.get('start')
            if not isinstance(start, (int, float)) or isinstance(start, bool):
                break
            if start >= overlap_seconds + 0.5:
                break
            words += len(_WORD.findall(str(segment.get('text') or '')))
        return words
    return int(overlap_seconds * WORDS_PER_SECOND_LIMIT) + 2


def merge_pair(previous: str, current: str, overlap_seconds: float, current_segments=None):
    """Returns (current_without_duplicate, removed_words_text, status) with status 'merged', 'unmatched' or 'none'."""
    if overlap_seconds <= 0:
        return current, '', 'none'
    before = [t for t in _tokens(previous) if t[2]]
    after = [t for t in _tokens(current) if t[2]]
    limit = min(_window_word_limit(min(overlap_seconds, MAX_OVERLAP_SECONDS), current_segments), len(before), len(after))
    for k in range(limit, 0, -1):
        if [t[2] for t in before[-k:]] == [t[2] for t in after[:k]]:
            cut = after[k - 1][1]
            removed = current[after[0][0]:cut]
            rest = current[cut:].lstrip(' ,;:.')
            return rest, removed, 'merged'
    return current, '', 'unmatched'


def _clock(seconds: float) -> str:
    seconds = max(0, int(round(seconds)))
    return f'{seconds // 60}:{seconds % 60:02d}'


def merge_parts(parts):
    """parts: dicts with 'text', 'seconds', 'overlap_seconds' (overlap with the piece before), optional 'segments'.

    Returns (text, notes, joins) where notes are plain-words review messages and joins describe every boundary.
    Without any overlap the result is exactly the old behaviour: the texts joined by line breaks.
    """
    texts, notes, joins = [], [], []
    position = 0.0
    for index, part in enumerate(parts):
        text = part.get('text') or ''
        overlap = float(part.get('overlap_seconds') or 0)
        if index and overlap > 0:
            boundary = position
            kept, removed, status = merge_pair(texts[-1] if texts else '', text, overlap, part.get('segments'))
            joins.append({'index': index, 'at_seconds': round(boundary, 1), 'status': status, 'removed': removed})
            if status == 'merged':
                notes.append(f'At about {_clock(boundary)} the overlapping pieces both contained "{removed}"; it is shown once.')
                text = kept
            elif text.strip():
                notes.append(f'At about {_clock(boundary)} the overlapping pieces did not match word for word, so both were kept. '
                             'Words around this point may appear twice; check numbers, doses and "no/not" against the recording.')
        texts.append(text)
        position += float(part.get('seconds') or 0) - (overlap if index else 0)
    return '\n'.join(texts), notes, joins
