"""Reproduction of a noisy whole-visit recording on one fixed, made-up visit only (no patient data).

A real whole-visit home recording did not produce a report on the test server (2026-11-13) and the logs, by design,
hold no words. This runs an invented visit of the same shape (family chatter, speech-service filler such as repeated
"Thank you.", a short dictated summary at the end) through the unchanged checked report pipeline and prints every
step's outcome, so the cause can be seen and a fix can be proven. Every name and detail below is invented.
"""
import asyncio
import base64
import json
import time

VERSION = 'noisy-visit-v11'
BUDGET_SECONDS = 470   # the launcher stops this process at 540 s; unfinished runs are reported, never cut off silently

# Filler the speech service invents over silence. Shown as stored BEFORE the 2026-11-12 fix (variant "old").
FOREIGN = '감사합니다. 시청해주셔서 감사합니다. 다음 영상에서 만나요. 감사합니다. 구독과 좋아요 부탁드립니다. 감사합니다.'
PIECES = (
    "Morning.  Come on in, the door sticks.  Thank you.  How was he overnight?  He was up twice.  Once for the washroom and "
    "once because the cat jumped on the bed.  Thank you.  Thank you.  Walter, your nurse is here.  Okay.  I have not tidied "
    "up yet, sorry.  That is fine.  I put the kettle on if you want tea later.  Thank you.  Is your bottom sore right now, "
    "Walter?  A little.  So-so.  Thank you.  He did not want the pillow under him this morning but I told him Sam's rules.",
    "I am going to roll you toward the window, okay?  Okay.  One, two, three.  There we go.  Thank you.  Huh.  Thank you.  "
    "Mind your head on the rail.  Just a sec, I have to move the blanket.  Thank you.  When will the supplies come, do you "
    "know?  I ordered on Monday.  Last time the gauze came in the morning and the tape came in the afternoon in a second "
    "box.  They are paying the driver twice.  Thank you.  They never call first, they just show up.  Thank you.",
    "Do you have new supplies in your car?  Yes, I bring some, it is not a problem.  Okay.  Thank you.  sniff  Thank you.  "
    "I have to change the bedding after.  This sheet is terrible.  Did he have a bowel movement today?  Not today.  "
    "Yesterday evening, a normal one.  There you go, dear.  Thank you.  Did you hear back from the doctor about the swab?  "
    "Not yet.  I will call the office after lunch.  They are usually pretty good.",
    "Okay, the old dressing is off.  This one was soaked through.  It looks a bit better than Tuesday to me.  Yes, I think "
    "so too.  The skin around it is red but it has always been a bit red there since the rash in the spring.  It feels dry "
    "now.  Thank you.  What do you want here, the gauze?  Yes please.  Are you working this weekend?  Saturday, yes.  "
    "Thank you.  Thank you.",
    "Tomorrow I am giving Walter a shower, so if you are not here right after I will put a pad over it until you come.  "
    "Not the gauze, the abdominal pad.  Yes, the abdominal pad, sorry.  Thank you.  Are you okay, Walter?  Surviving.  "
    "That is what we do.  Thank you.  Yesterday I turned him every two hours.  And at bedtime I laid him flatter to take "
    "the pressure off.  Good, keep doing that.  Do you need a pen?  Yes, thank you.",
    "Did you see the snow they are calling for?  Thank you.  Ten centimetres by Friday.  My son said he will come and "
    "shovel but he said that last year too.  laughs  Thank you.  The plow guy wants forty dollars now.  Forty!  Thank you.  "
    "Walter used to do the whole driveway himself, did you know that?  And the neighbour's.  Thank you.  Okay, hold still "
    "for me, Walter.  I am just cleaning it now.  Is that cold?  A bit.  Sorry.  Thank you.  Thank you.  Almost done.",
    "The pharmacy called about his refill.  They said the feeding formula is on back order, maybe a week.  I have nine "
    "cans left.  Thank you.  That is enough for now but tell me if it gets to four.  Okay.  Thank you.  And the cushion "
    "for the chair, the gel one, it has gone flat on one side.  I turned it around.  Thank you.  How long does he sit in "
    "the chair?  About an hour after lunch, then back to bed.  An hour at a time is good.  Thank you.  Thank you.",
    "My sister phoned last night from Calgary.  Thank you.  She wants us to come at Easter but I said how, with the bed and "
    "the lift and everything.  Thank you.  She does not understand.  Nobody does until they do it.  Thank you.  Walter, "
    "lift your arm for me.  Thank you.  Good.  The radio said the bridge is closed again.  You came the long way?  I did, "
    "twenty minutes extra.  Thank you.  Thank you.  sigh  Thank you.  Okay.  Nearly there.",
    "Is he eating?  The tube feeds are all going in, no problems.  And he had a bit of applesauce by mouth, the speech "
    "lady said that was okay.  Thank you.  Drinking?  Sips.  Thank you.  His weight was the same at the clinic.  Okay.  "
    "Thank you.  The home support girl comes at four now instead of three, they changed it and nobody told me.  Thank you.  "
    "I found out when she did not show up.  Thank you.  I will mention it to the coordinator.  Would you?  Thank you.",
    "The cat is on the supplies again.  Shoo.  Thank you.  doorbell  That will be the parcel.  Just leave it on the step!  "
    "Thank you.  Thank you.  You know he was a mechanic for forty years.  Never sat down.  And now look.  Thank you.  It "
    "is hard.  It is.  You are doing a good job with him.  Thank you.  I try.  Thank you.  Okay Walter, we are going to "
    "roll you back now.  One, two, three.  Thank you.  Comfortable?  Yes.  Thank you.  Thank you.",
    "Any fever or chills, Walter?  No.  Any pain?  No pain.  Did you sleep?  He was up at ten to six for the washroom and "
    "again at half past six because his foot was sore, so I put a pillow under the foot and he went back to sleep.  Short "
    "of breath, any cough?  No.  You look cozy there.  laughs  You listen to Sam and you listen to me and we will get it "
    "better.  We see the grandchildren next week so that bottom has to heal.  Thank you.",
    "It still looks a bit sore, so I am going to keep the same dressing.  Thank you.  Do you have skin protectant?  What is "
    "that?  The skin prep wipes.  Oh, those.  I have a big box.  Thank you.  One time you used the spray and it stung him.  "
    "I remember, that was my mistake.  He was hopping after that.  Thank you.  Thank you.  Amen.  Thank you.",
    "Thank you.  Thank you.  Thank you.  Pass me the small scissors please.  Thank you.  Do you need an alcohol wipe?  I "
    "keep them here for the ports on his feeding tube.  Okay.  Why do you cut it like that?  Because I put two layers, so "
    "if the first one soaks it goes into the second.  Oh, okay.  Got it.  Thank you.  Thank you.",
    "We should get the occupational therapist to assess.  A what?  The occupational therapist.  To help me with what?  "
    "Positioning and transfers, and the right cushion.  I do not know if I need anybody.  Priya Nolan came once before and "
    "gave me ideas.  We kept his skin good for years, it was just this one day.  Okay.  Special care today.  If you are "
    "working Saturday we will see you.",
    "We are going to watch our granddaughter's recital on Saturday, so you have to come in the morning, we leave about "
    "two.  Okay.  Are you done?  All done.  Thank you.  I will see you tomorrow.  Call me after the shower.  I will.  "
    "Bye-bye.  Thank you.  Thank you.  I parked too close to your fence.  thud  snap  Thank you.",
    "Wound on the coccyx area, draining a large amount of thick drainage.  No redness to the periwound.  No warmth or "
    "increase in temperature.  No systemic signs of infection like fever or chills.  Client denies pain.",
)
REVIEW = 'Review the transcript against the recording, including words at segment boundaries, numbers, negation and laterality.'


def visit_context(variant):
    """variant "old": stored before the fix (foreign filler piece and its alarm). "new": stored after it."""
    from server import providers
    old = variant == 'old'
    text = '\n'.join(((FOREIGN,) if old else ()) + PIECES)
    warnings = [REVIEW, providers.BACKGROUND_SPEECH_WARNING if old else providers.FOREIGN_PIECE_DROPPED_WARNING]
    return {'photo_groups_clinician_assigned': [], 'case_facts': [], 'rejected_claims_do_not_reinstate': [],
            'untrusted_sources': [{'id': 'fictional-noisy-visit', 'kind': 'audio', 'text': text, 'warnings': warnings,
                                   'created_at': '2026-01-01T12:00:00Z', 'duration_seconds': 780}]}


def line(run, variant, stage, status, code, ms, effort, tokens, data):
    encoded = base64.b64encode(json.dumps(data, ensure_ascii=False)[:4500].encode('utf-8')).decode('ascii')
    print('AMILYA_NOISY_VISIT run=%d variant=%s stage=%s status=%s code=%s ms=%d effort=%s out_tokens=%d data_b64=%s' % (
        run, variant, stage, status, code, min(int(ms), 999999999), effort if effort in ('low', 'medium', 'high') else 'none',
        tokens if isinstance(tokens, int) and 0 <= tokens < 10 ** 9 else 0, encoded), flush=True)


async def one(run, variant, actor):
    from server import providers
    from render_synthetic_probe import error_code
    original = providers.routed

    async def traced(task, content, model=None, **kwargs):
        started = time.monotonic()
        try:
            result = await original(task, content, model, **kwargs)
        except Exception as exc:
            line(run, variant, task, 'FAIL', error_code(exc), (time.monotonic() - started) * 1000, None, 0, {})
            raise
        shown = ({'passed': result.get('passed'), 'discrepancies': result.get('discrepancies')} if task == 'quick_qa' else
                 {'status': result.get('status'), 'text': result.get('text'), 'ui_alerts': result.get('ui_alerts')})
        line(run, variant, task, 'OK', 'NONE', (time.monotonic() - started) * 1000, result.get('effort_level'),
             (result.get('usage') or {}).get('output_tokens'), shown)
        return result

    providers.routed = traced
    started = time.monotonic()
    try:
        result = await providers.quick_documentation(visit_context(variant), 'nursing', 'concise', user=actor,
                                                     encounter_id='fictional-noisy-%d-%s' % (run, variant))
        ok = bool(str(result.get('text') or '').strip())
        line(run, variant, 'final', 'PASS' if ok else 'FAIL', 'NONE' if ok else 'NO_NOTE', (time.monotonic() - started) * 1000, None, 0,
             {'status': result.get('status'), 'text': result.get('text'), 'ui_alerts': result.get('ui_alerts'),
              'qa': result.get('qa'), 'recording_unreliable': result.get('recording_unreliable')})
        return ok
    except Exception as exc:
        line(run, variant, 'final', 'FAIL', error_code(exc), (time.monotonic() - started) * 1000, None, 0, {})
        return False
    finally:
        providers.routed = original


def reproduce(actor, variants=('old', 'old', 'new')):
    started = time.monotonic()

    async def runs():
        passed = []
        for run, variant in enumerate(variants, 1):
            remaining = BUDGET_SECONDS - (time.monotonic() - started)
            if remaining < 30:
                line(run, variant, 'final', 'FAIL', 'NOT_RUN', 0, None, 0, {})
                passed.append(False)
                continue
            try:
                passed.append(await asyncio.wait_for(one(run, variant, actor), timeout=remaining))
            except asyncio.TimeoutError:
                line(run, variant, 'final', 'FAIL', 'PROBE_TIME_LIMIT', (time.monotonic() - started) * 1000, None, 0, {})
                passed.append(False)
        return passed

    return all(asyncio.run(runs()))
