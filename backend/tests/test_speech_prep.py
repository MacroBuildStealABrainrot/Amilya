"""Audio clean-up before speech-to-text and the made-up-line filter (all audio and text invented)."""
import shutil
import subprocess

import pytest

from server.speech_prep import drop_guessed, prepare


def seg(text, silence, unsure, start=0.0):
    return {'text': text, 'start': start, 'end': start + 2, 'no_speech_prob': silence, 'avg_logprob': unsure}


def test_made_up_lines_in_silence_are_dropped_and_clinical_lines_never_are():
    segments = [seg(' Thank you.', 0.9, -1.4), seg(' Any pain today? No.', 0.1, -0.3, 2), seg(' Thanks for watching!', 0.8, -1.6, 4),
                seg(' No fever.', 0.9, -1.5, 6), seg(' BP 118 over 72.', 0.7, -1.2, 8)]
    text = ''.join(s['text'] for s in segments)
    out, kept, dropped = drop_guessed(text, segments)
    assert dropped == 2
    assert 'Thank you' not in out and 'Thanks for watching' not in out
    for clinical in ('Any pain today? No.', 'No fever.', 'BP 118 over 72.'):
        assert clinical in out   # a negation or a number is never dropped, however unsure the service was
    assert len(kept) == 3


def test_confident_speech_and_missing_scores_are_untouched():
    segments = [{'text': ' Thank you.', 'start': 0, 'end': 1}, seg(' Thank you.', 0.2, -0.2, 1)]
    assert drop_guessed(' Thank you. Thank you.', segments) == (' Thank you. Thank you.', segments, 0)


@pytest.mark.skipif(not shutil.which('ffmpeg'), reason='ffmpeg not installed')
def test_prepared_audio_keeps_the_same_length(tmp_path):
    source = tmp_path / 'tone.wav'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=5', '-ar', '44100', str(source)], check=True)
    data, mime = prepare(source.read_bytes(), 'audio/wav')
    assert mime == 'audio/flac' and data[:4] == b'fLaC'
    out = tmp_path / 'out.flac'; out.write_bytes(data)
    seconds = float(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', str(out)],
                                   capture_output=True, text=True, check=True).stdout)
    assert abs(seconds - 5) < 0.1   # nothing cut out: every timestamp still points to the same moment


def test_unreadable_audio_falls_back_to_the_original():
    assert prepare(b'not audio', 'audio/mp4') == (b'not audio', 'audio/mp4')
