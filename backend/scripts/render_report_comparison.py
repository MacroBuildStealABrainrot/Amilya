"""Blind report comparison on fixed, made-up visits only (no patient data).

Each fictional visit is written by the current report model and by Claude Haiku 5.5
through the unchanged checked report pipeline. Variants are shuffled to A/B so the
owner can judge without knowing which model wrote which report. Only these fixed
fictional inputs, their generated text and closed technical fields are printed.
"""
import asyncio
import base64
import json
import random
import time

CURRENT = 'openai/gpt-6.1-sol'
CANDIDATE = 'anthropic/claude-haiku-5.5'
CASES = (
    ('nursing', 'Fictional training visit, no real patient. Home care wound visit. Right lateral malleolus wound. '
     'Old foam dressing removed with moderate serous drainage on the dressing. Wound bed mostly granulation with a '
     'small area of adherent slough at 12 o clock. Edges attached. Periwound mildly macerated. Measured 3.1 by 2.4 by '
     '0.3 cm. Cleansed with normal saline, skin barrier to periwound, silver foam applied, compression wrap reapplied. '
     'Patient says pain 3 out of 10 during the change, wears the wrap most days, asked about swimming.'),
    ('nursing', 'Fictional training visit, no real patient. Medical ward evening assessment. 78 year old admitted with '
     'pneumonia, day 2 of IV antibiotics. Alert and oriented, says breathing is a bit easier than yesterday. '
     'Temperature 37.9, heart rate 98, BP 128/76, respiratory rate 22, oxygen saturation 93 percent on 2 litres nasal '
     'prongs. Crackles right base. Ate half of supper. Voided twice. Mobilised to chair with one assist. Family visited.'),
    ('soap', 'Fictional training visit, no real patient. Family practice follow up for type 2 diabetes. Patient reports '
     'taking metformin most days but missing evening doses. No hypoglycaemia. Some tingling in both feet at night. '
     'Last A1C 8.4 percent three months ago, today 7.9 percent. BP 142/88. Weight down 2 kg. Foot exam: monofilament '
     'reduced at both great toes, no ulcers, pulses palpable. Plan discussed: continue metformin with a phone reminder, '
     'start ACE inhibitor, referral to foot care and diabetes education, repeat labs in three months.'),
    ('nursing', 'Fictional training visit, no real patient. Emergency triage. 34 year old with left forearm laceration '
     'from a kitchen knife about one hour ago. Bleeding controlled with pressure dressing. Laceration about 4 cm, edges '
     'clean. Can move all fingers, sensation intact, cap refill under 2 seconds. Last tetanus unknown. Pain 6 out of 10. '
     'Vitals stable. No other injuries. Waiting for physician assessment for repair.'),
)


async def one(case_id, template, text, model, actor):
    from server import providers
    context = {'case_facts': [], 'untrusted_sources': [{'id': 'fictional-source', 'kind': 'typed', 'text': text}],
               'rejected_claims_do_not_reinstate': []}
    started = time.monotonic()
    try:
        result = await providers.quick_documentation(context, template, 'concise', user=actor,
                                                     encounter_id='fictional-compare-%d-%s' % (case_id, model.split('/')[0]))
        return {'ok': bool(result.get('text', '').strip()), 'qa': (result.get('qa') or {}).get('passed') is True,
                'text': result.get('text', ''), 'ms': int((time.monotonic() - started) * 1000)}
    except Exception:
        return {'ok': False, 'qa': False, 'text': '', 'ms': int((time.monotonic() - started) * 1000)}


def compare(actor):
    from server import providers
    original = providers.settings.model_documentation
    if CANDIDATE not in providers.MODEL_PRICES or CURRENT not in providers.MODEL_PRICES:
        print('AMILYA_REPORT_COMPARE status=BLOCKED', flush=True)
        return False

    async def run_model(model):
        providers.settings.model_documentation = model
        return await asyncio.gather(*(one(i, template, text, model, actor) for i, (template, text) in enumerate(CASES, 1)))

    try:
        # Models run one after another so the setting is never shared mid-request.
        current = asyncio.run(run_model(CURRENT))
        candidate = asyncio.run(run_model(CANDIDATE))
    finally:
        providers.settings.model_documentation = original
    rng = random.SystemRandom()
    for case_id, (first, second) in enumerate(zip(current, candidate), 1):
        pair = [('current', first), ('haiku', second)]
        rng.shuffle(pair)
        for variant, (name, result) in zip('AB', pair):
            encoded = base64.b64encode(result['text'][:6000].encode('utf-8')).decode('ascii')
            print('AMILYA_REPORT_COMPARE case=%d variant=%s status=%s qa=%s latency_ms=%d text_b64=%s' % (
                case_id, variant, 'PASS' if result['ok'] else 'FAIL', 'PASS' if result['qa'] else 'FAIL',
                min(result['ms'], 999999999), encoded), flush=True)
        print('AMILYA_REPORT_COMPARE_KEY case=%d A=%s B=%s' % (case_id, pair[0][0], pair[1][0]), flush=True)
    return all(r['ok'] for r in current + candidate)
