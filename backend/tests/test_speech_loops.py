"""Speech-service loops ("Thank you. Thank you. Thank you.") without ever losing clinical words.

Rules (1.8.5-test10): only a run of 3+ identical, short, non-clinical sentences is shortened in the working
transcript; a repeated sentence with a number, dose, negation, frequency or side is kept in full and flagged;
the original transcript is always kept with the source.
"""
import asyncio

from server import providers
from server.providers import carries_clinical_meaning, collapse_speech_loops
from test_providers import gateway  # noqa: F401  (fixture)


def test_a_run_of_three_or_more_identical_filler_sentences_keeps_one_copy():
    text, segments, changed, flagged = collapse_speech_loops('Wound cleansed with NS. Thank you. Thank you. Thank you. Thank you. Dressed with Mepilex.', [])
    assert changed and not flagged and text == 'Wound cleansed with NS. Thank you. Dressed with Mepilex.'


def test_genuine_repetition_of_two_and_different_wording_is_untouched():
    for text in ['No pain. No pain.', 'Pain 2 out of 10. Pain 3 out of 10. Pain 4 out of 10.', 'Left heel. Right heel. Left heel.']:
        assert collapse_speech_loops(text, []) == (text, [], False, False)


def test_repeated_doses_numbers_negation_and_sides_are_never_shortened():
    for sentence in ['Give 5 mg.', 'Give five milligrams.', 'No.', 'Not today.', 'Denies pain.', 'Left side.',
                     'Twice daily.', 'Hold the metformin.', "Don't remove it.", 'Two tablets.', '10 percent.']:
        text = ' '.join([sentence] * 4)
        result, _, changed, flagged = collapse_speech_loops(text, [])
        assert result == text and not changed and flagged, sentence


def test_long_sentences_are_never_shortened():
    sentence = 'The client and her daughter asked about the next home visit time.'
    text = ' '.join([sentence] * 3)
    assert collapse_speech_loops(text, [])[0] == text


def test_clinical_meaning_detector():
    assert carries_clinical_meaning('Apply 2 cm of gel')
    assert carries_clinical_meaning('no')
    assert carries_clinical_meaning('right foot')
    assert not carries_clinical_meaning('Thank you')
    assert not carries_clinical_meaning('Okay')


def test_repeated_segments_are_collapsed_with_the_text():
    segments = [{'text': 'Dressing changed.', 'start': 0, 'end': 2}] + [{'text': ' Thank you.', 'start': i, 'end': i + 1} for i in range(2, 7)]
    _, kept, changed, _ = collapse_speech_loops('Dressing changed. Thank you.', segments)
    assert changed and [s['text'] for s in kept] == ['Dressing changed.', ' Thank you.'] and kept[1]['start'] == 2


def test_line_breaks_between_parts_are_preserved():
    text = 'Part one ends here.\nPart two starts. Okay. Okay. Okay.'
    assert collapse_speech_loops(text, [])[0] == 'Part one ends here.\nPart two starts. Okay.'


def test_transcription_keeps_the_original_and_warns(gateway, monkeypatch):
    gateway.config.groq_api_key = 'synthetic-groq'
    monkeypatch.setattr(providers, 'audio_duration', lambda _: 15)
    original = 'Wound measured 2 by 3 cm. Thank you. Thank you. Thank you.'
    gateway.http.responses = [{'text': original, 'segments': []}]
    result = asyncio.run(providers.transcribe(b'synthetic', 'audio/wav', 'synthetic.wav', user=gateway.user, encounter_id='loops'))
    assert result['text'] == 'Wound measured 2 by 3 cm. Thank you.'
    assert result['original_text'] == original
    assert any('original transcript is kept' in warning for warning in result['warnings'])


def test_transcription_flags_but_keeps_a_repeated_dose(gateway, monkeypatch):
    gateway.config.groq_api_key = 'synthetic-groq'
    monkeypatch.setattr(providers, 'audio_duration', lambda _: 15)
    original = 'Give 5 mg. Give 5 mg. Give 5 mg.'
    gateway.http.responses = [{'text': original, 'segments': []}]
    result = asyncio.run(providers.transcribe(b'synthetic', 'audio/wav', 'synthetic.wav', user=gateway.user, encounter_id='loops-dose'))
    assert result['text'] == original and 'original_text' not in result
    assert any('was NOT shortened' in warning for warning in result['warnings'])
