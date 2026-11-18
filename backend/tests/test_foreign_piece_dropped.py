"""A recording piece that is entirely another language (made-up filler over silence) is left out, so it does not make the
whole visit look unreliable. Made-up text only, no patient information. (owner request 2026-11-12)"""
from server import providers, recording_quality

KOREAN = '감사합니다. 시청해주셔서 감사합니다. 다음 영상에서 만나요.'
ENGLISH = 'Coccyx wound with granulation tissue and thick drainage. No redness or warmth.'


def test_all_foreign_piece_is_dropped_and_original_kept():
    out = providers.flag_background_speech({'text': KOREAN, 'segments': [{'text': KOREAN}], 'warnings': []})
    assert out['text'] == '' and out['segments'] == [] and out['original_text'] == KOREAN
    assert out['warnings'] == [providers.FOREIGN_PIECE_DROPPED_WARNING]


def test_dropped_piece_warning_does_not_trigger_the_unreliable_gate():
    out = providers.flag_background_speech({'text': KOREAN, 'warnings': []})
    ctx = {'untrusted_sources': [{'kind': 'audio', 'text': ENGLISH, 'warnings': out['warnings'], 'duration_seconds': 60}]}
    assert recording_quality.free_signals(ctx) == []


def test_mixed_piece_still_raises_the_background_warning():
    out = providers.flag_background_speech({'text': ENGLISH + ' ' + KOREAN * 2, 'warnings': []})
    assert out['text'].startswith('Coccyx') and providers.BACKGROUND_SPEECH_WARNING in out['warnings']
    ctx = {'untrusted_sources': [{'kind': 'audio', 'text': out['text'], 'warnings': out['warnings']}]}
    assert recording_quality.free_signals(ctx) == ['speech_service_warning']


def test_english_piece_is_untouched():
    out = providers.flag_background_speech({'text': ENGLISH, 'warnings': []})
    assert out['text'] == ENGLISH and 'background_suspected' not in out
