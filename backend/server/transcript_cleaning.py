"""Cleaned transcription: a free, local step between the raw transcript and the report.

The raw transcript is never changed or deleted (it stays saved as the source). Only the copy sent to the report
writer drops things that are clearly not speech from the visit: sound-effect tags such as *gunshot* or [Music],
music-note lines, and the same stock sentence repeated three or more times in a row (a known speech-to-text
glitch on silence or TV audio), stock phrases it invents ("Thanks for watching!") and bare sound labels between pauses
("thud", "sounds of water"). A real clinical sentence is never removed.
"""
import re

_SOUNDS = (r'music|musical|applause|laughter|laughing|laughs|cheering|gunshots?|gunfire|explosions?|sirens?|beeping|beeps?|bleep|'
           r'silence|static|noise|background noise|background music|tv|television|sound effects?|door (?:opens|closes|slams)|'
           r'phone ringing|ringing|knocking|barking|crowd|inaudible|unintelligible|indistinct|crosstalk|speaking [a-z ]+|sighs?|coughs?|coughing')
_TAG = re.compile(rf'(?:\*|\[|\()\s*(?:{_SOUNDS})[^\]\)\*\n]{{0,40}}(?:\*|\]|\))', re.I)
_NOTES = re.compile(r'^[\s♪♫🎵🎶#]*[♪♫🎵🎶][^\n]*$', re.M)
_STOCK = re.compile(r'(?i)\b(?:thanks? (?:you )?for watching[.!]?|please (?:like and )?subscribe[^.!?]{0,40}[.!?]?|subtitles? by [^.!?]{0,40}[.!?]?)')
_BARE = (r'thud|snap|click|clicks|beep|beeps|sigh|sighs|snort|knock(?: knock)?|doorbell|phone ringing|gunshot|laughs|laughter|'
         r'crying|sniffles|rubber band|music|applause|static|sounds? of (?:a |the )?[a-z]+(?: [a-z]+)?|you')
_BARE_SOUND = re.compile(rf'(?im)(?:^|(?<=\s\s)|(?<=[.!?]\s))(?:{_BARE})[.!]?(?=\s\s|\s*$)')
# "Thank you." standing alone: at the start, after a sentence end, or after a pause.
_THANKS = re.compile(r'(?i)(?:^|(?<=[.!?]\s)|(?<=\s\s))thank you\.(?=\s|$)')


def clean_transcript(text):
    if not isinstance(text, str) or not text.strip():
        return text
    out = _NOTES.sub('', text)
    out = _TAG.sub(' ', out)
    out = out.replace('♪', ' ')
    # Stock phrases the speech service invents over TV or silence (owner's real visit, 2026-11-06).
    out = _STOCK.sub(' ', out)
    # A bare sound label said by nobody, between pauses: "  thud  ", "  sounds of water  ", "  doorbell  ".
    out = _BARE_SOUND.sub('  ', out)
    # Many bare "Thank you." is the classic glitch: then every bare "Thank you." is filler, not speech.
    if len(_THANKS.findall(out)) >= 3:
        out = _THANKS.sub(' ', out)
        out = _BARE_SOUND.sub('  ', out)
    # Same sentence three or more times in a row is a recognition glitch, keep one copy.
    out = re.sub(r'(?is)(\b[^.!?\n]{2,60}[.!?])(?:\s+\1){2,}', r'\1', out)
    out = re.sub(r'[ \t]{2,}', ' ', out)
    out = re.sub(r' +\n', '\n', out)
    out = re.sub(r'\n{3,}', '\n\n', out)
    out = out.strip()
    return text if out == text.strip() else out


def clean_context_for_report(context):
    """Copy of the report context whose spoken sources are cleaned. The original context is untouched."""
    sources = []
    for source in context.get('untrusted_sources', []):
        if source.get('kind') == 'audio' and isinstance(source.get('text'), str):
            source = {**source, 'text': clean_transcript(source['text'])}
        sources.append(source)
    return {**context, 'untrusted_sources': sources}
