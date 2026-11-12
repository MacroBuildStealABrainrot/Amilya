"""Made-up-audio test of Deepgram keyterm prompting for nova-3-medical.

Everything here is invented practice text. No patient information. The Deepgram key is read from the
environment (DEEPGRAM_TEST_KEY) and is never printed or saved. Run only from a test branch.
"""
import json, os, re, sys, urllib.parse, urllib.request
from pathlib import Path

HERE = Path(__file__).parent
STT_URL = 'https://api.deepgram.com/v1/listen'
TTS_URL = 'https://api.deepgram.com/v1/speak'
# Same settings Amilya uses today (backend/server/providers.py, speech_attempt), so the only difference is the list.
BASE_PARAMS = {'model': 'nova-3-medical', 'smart_format': 'true', 'utterances': 'true', 'mip_opt_out': 'true', 'diarize': 'true'}


def load_terms():
    terms = [t.strip() for t in (HERE / 'terms.txt').read_text().splitlines() if t.strip()]
    return terms


def norm(text):
    return re.sub(r'[^a-z0-9]', '', text.lower())


def words(text):
    return re.findall(r"[a-z0-9']+", text.lower().replace('-', ' '))


def term_found(term, transcript):
    return norm(term) in norm(transcript)


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def word_error_rate(truth, heard):
    t, h = words(truth), words(heard)
    return edit_distance(t, h) / max(1, len(t))


def request(url, key, params, body=None, content_type=None):
    query = urllib.parse.urlencode(params, doseq=True)
    req = urllib.request.Request(url + '?' + query, data=body, method='POST')
    req.add_header('Authorization', 'Token ' + key)
    req.add_header('Content-Type', content_type or 'application/json')
    with urllib.request.urlopen(req, timeout=120) as response:
        return response.read()


def speak(key, text, voice):
    return request(TTS_URL, key, {'model': voice, 'encoding': 'linear16', 'container': 'wav'},
                   json.dumps({'text': text}).encode())


def transcribe(key, audio, keyterms=None):
    params = dict(BASE_PARAMS)
    if keyterms: params['keyterm'] = keyterms  # repeated once per term, as Deepgram documents
    raw = json.loads(request(STT_URL, key, params, audio, 'audio/wav'))
    return raw['results']['channels'][0]['alternatives'][0]['transcript']


def main():
    key = os.environ.get('DEEPGRAM_TEST_KEY', '')
    if not key:
        sys.exit('DEEPGRAM_TEST_KEY is not set')
    terms = load_terms()
    scripts = json.loads((HERE / 'scripts.json').read_text())
    results = []
    for item in scripts:
        audio = speak(key, item['text'], item['voice'])
        without = transcribe(key, audio)
        with_list = transcribe(key, audio, terms)
        results.append({'id': item['id'], 'truth': item['text'], 'without': without, 'with': with_list})
    out = HERE / 'out'
    out.mkdir(exist_ok=True)
    (out / 'results.json').write_text(json.dumps(results, indent=1))
    (out / 'report.md').write_text(report(results, terms))
    print((out / 'report.md').read_text())


def report(results, terms):
    lines = ['# Deepgram nova-3-medical: word list test (made-up audio only)', '']
    tot = {'list_before': 0, 'list_after': 0, 'list_n': 0, 'other_before': 0, 'other_after': 0, 'other_n': 0}
    wer_before = wer_after = 0.0
    for r in results:
        lines += [f"## Recording {r['id']}", '', '| Word | Without list | With list |', '|---|---|---|']
        for term in terms:
            if not term_found(term, r['truth']): continue
            b, a = term_found(term, r['without']), term_found(term, r['with'])
            tot['list_n'] += 1; tot['list_before'] += b; tot['list_after'] += a
            lines.append(f"| {term} | {'right' if b else 'WRONG'} | {'right' if a else 'WRONG'} |")
        for term in OTHER_WORDS:
            if not term_found(term, r['truth']): continue
            b, a = term_found(term, r['without']), term_found(term, r['with'])
            tot['other_n'] += 1; tot['other_before'] += b; tot['other_after'] += a
            lines.append(f"| {term} (not on the list) | {'right' if b else 'WRONG'} | {'right' if a else 'WRONG'} |")
        wb, wa = word_error_rate(r['truth'], r['without']), word_error_rate(r['truth'], r['with'])
        wer_before += wb; wer_after += wa
        lines += ['', f"Word error rate (lower is better): without {wb:.1%}, with {wa:.1%}", '']
    n = max(1, len(results))
    lines += ['## Summary', '',
              f"- Words on the list: right without list {tot['list_before']}/{tot['list_n']}, right with list {tot['list_after']}/{tot['list_n']}",
              f"- Medical words NOT on the list (check for harm): right without {tot['other_before']}/{tot['other_n']}, with {tot['other_after']}/{tot['other_n']}",
              f"- Average word error rate: without {wer_before / n:.1%}, with {wer_after / n:.1%}", '']
    return '\n'.join(lines)


OTHER_WORDS = ['granulation', 'erythema', 'callus', 'macerated', 'pitting edema', 'Tegaderm', 'Allevyn', 'Mepilex', 'Kaltostat', 'debridement',
               'drainage', 'periwound', 'compression', 'offloading']

if __name__ == '__main__':
    main()
