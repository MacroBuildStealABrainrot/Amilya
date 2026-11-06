"""Permanent evaluation visits for Amilya. EVERY name, number, date and detail here is invented.

Each case:
  id, area, profession, template          who is charting and which format
  text                                    what the clinician "said" (a made-up transcript)
  facts / note                            optional photo findings and the clinician's own words sent with a photo
  expected                                the key facts a correct note must keep, in plain words (for reviewers and the marker)
  must        (critical)                  patterns that MUST appear: losing one is a serious accuracy failure
  must_not    (critical)                  patterns that must NOT appear: an invented, wrong or uncorrected detail
  clarify                                 True when a "please confirm" note is expected (an unclear term)
  max_words                               optional: a short visit must stay short (a writing-style check, not accuracy)
  planted                                 deliberately wrong reports: the checks MUST flag every one of them
  pattern                                 which earlier failure pattern this case guards against
"""

CASES = [
    # ---------------- Community nursing and wound care ----------------
    {'id': 'oncology_bleeding_lesion', 'area': 'Nursing / oncology', 'profession': 'RPN', 'template': 'nursing',
     'pattern': 'Lost diagnosis and unclear term (the "cell cancer" report)',
     'text': "Okay so this client has lymphoma, she's immune compromised. She also has cell cancer, one lesion on the left thigh. "
             "It's bleeding today, she says it hurts, pain about 6 out of 10. It's getting bigger and there's more drainage than last week, "
             "serous, moderate amount. She cannot change her own dressing so we're seeing her daily. I cleansed with saline and applied Mepilex. "
             "High risk for infection. She has surgery scheduled November 6th.",
     'expected': ['lymphoma', 'immunocompromised', 'cancerous lesion, exact type to be confirmed', 'left thigh', 'bleeding today', 'pain 6/10',
                  'lesion larger', 'more serous drainage, moderate', 'seen daily because she cannot change her own dressing', 'saline, Mepilex',
                  'high risk for infection', 'surgery November 6'],
     'must': [r'cancer', r'lymphoma', r'immuno-?\s?compromised|immune[- ]compromised', r'left thigh', r'bleed', r'6\s*(/|out of)\s*10',
              r'increas\w*.{0,40}size|size.{0,40}increas|bigger|larger|enlarg', r'drainage', r'daily',
              r'(unable|cannot|can.t).{0,30}(change|manage).{0,20}dressing', r'Mepilex', r'November 6', r'saline|NS\b'],
     'must_not': [r'squamous|basal|melanoma', r'\bcell cancer\b', r'physician (was )?(notified|informed)', r'no signs of infection'],
     'clarify': True,
     'planted': [
        {'mistake': 'Drops the bleeding and the growth of the lesion',
         'text': 'Client has lymphoma and is immunocompromised. Cancerous lesion to the left thigh with moderate serous drainage; pain 6/10. Unable to change her own dressing, so seen daily. Cleansed with saline, Mepilex applied. Surgery scheduled for November 6.'},
        {'mistake': 'Guesses the cancer type and invents a physician notification',
         'text': 'Client has lymphoma and squamous cell carcinoma of the left thigh, bleeding and larger, more serous drainage; pain 6/10. Unable to change her own dressing, so seen daily. Cleansed with saline, Mepilex applied. Physician notified. Surgery November 6.'}]},
    {'id': 'routine_catheter', 'area': 'Nursing', 'profession': 'RPN', 'template': 'nursing', 'pattern': 'Short visit must stay short',
     'text': 'Seen for blocked suprapubic catheter. Changed it, 16 French, clear yellow urine back right away. No other concerns, thanks.',
     'expected': ['blocked suprapubic catheter', 'changed, 16 Fr', 'clear urine return', 'no other concerns'],
     'must': [r'suprapubic', r'16\s*(Fr|French)', r'clear'], 'must_not': [r'no signs of infection', r'tolerated', r'bladder (scan|spasm)'], 'max_words': 45,
     'planted': [{'mistake': 'Wrong catheter size', 'text': 'Seen for blocked suprapubic catheter. Catheter changed using 18 Fr catheter with clear urine return. No other concerns.'}]},
    {'id': 'long_wound_soap_with_chatter', 'area': 'Wound care', 'profession': 'RN', 'template': 'soap',
     'pattern': 'Background chatter, a spoken correction of the side',
     'text': "Hi how was the hockey game last night, oh nice. Okay, client seen for the left, sorry, correction, right heel pressure injury. "
             "She says pain is 3 out of 10 with dressing changes, no fever or chills. Diabetes, on metformin. Wound measures 3.2 by 2.1 cm, "
             "depth 0.4 cm. Wound bed 70 percent granulation 30 percent slough, edges rolled, periwound macerated. Moderate serous drainage, no odour. "
             "Cleansed with saline, Inadine applied, covered with Mepilex Border, heel offloaded with a pillow. Plan dressing every 3 days and "
             "she'll see the wound clinic next Tuesday. My daughter also plays hockey actually.",
     'expected': ['RIGHT heel pressure injury (left was corrected)', 'pain 3/10 with dressing changes', 'denies fever, chills', 'diabetes on metformin',
                  '3.2 x 2.1 x 0.4 cm', '70% granulation, 30% slough', 'rolled edges', 'macerated periwound', 'moderate serous, no odour',
                  'saline, Inadine, Mepilex Border, offloaded', 'every 3 days', 'wound clinic next Tuesday', 'no hockey'],
     'must': [r'right heel', r'3\s*(/|out of)\s*10', r'3\.2\s*(x|by)\s*2\.1', r'0\.4', r'granulation', r'slough', r'macerat', r'Inadine',
              r'Mepilex', r'offload', r'(every|q)\s*3\s*days|q3', r'diabet', r'metformin', r'(denies|no) fever'],
     'must_not': [r'hockey', r'left heel', r'osteomyelitis is', r'cellulitis is present'],
     'planted': [{'mistake': 'Keeps the side the clinician corrected', 'text': 'S: Pain 3/10 with dressing changes. Denies fever or chills. Diabetes on metformin. O: Left heel pressure injury 3.2 x 2.1 x 0.4 cm, 70% granulation, 30% slough, rolled edges, macerated periwound, moderate serous drainage, no odour. Cleansed with saline, Inadine, Mepilex Border, heel offloaded. P: Dressing every 3 days; wound clinic next Tuesday.'},
                 {'mistake': 'Includes the small talk', 'text': 'S: Client chatted about hockey. Pain 3/10 with dressing changes. Denies fever or chills. Diabetes on metformin. O: Right heel pressure injury 3.2 x 2.1 x 0.4 cm, 70% granulation, 30% slough, rolled edges, macerated periwound, moderate serous drainage, no odour. Cleansed with saline, Inadine, Mepilex Border, offloaded. P: Every 3 days; wound clinic next Tuesday.'}]},
    {'id': 'misheard_products', 'area': 'Wound care', 'profession': 'RPN', 'template': 'nursing',
     'pattern': 'Incorrect dressing names from speech recognition',
     'text': 'Left lateral ankle venous ulcer. Cleansed with normal sailing and pure cleanse, then Biatine alginate, ABD pad and Coban two lite. '
             'Moderate serous drainage, periwound dry and flaky. Client tolerating compression, no pain today.',
     'expected': ['left lateral ankle venous ulcer', 'normal saline (not "sailing")', 'PureCleanse', 'Biatain Alginate (not "Biatine")', 'ABD pad',
                  'Coban 2 Lite', 'moderate serous drainage', 'dry flaky periwound', 'tolerating compression', 'no pain today'],
     'must': [r'left lateral ankle', r'saline', r'PureCleanse|Pure ?Cleanse', r'Biatain', r'ABD', r'Coban\s*(2|two)', r'serous', r'(dry|flak)', r'compression',
              r'(no|denies) pain'],
     'must_not': [r'sailing', r'Biatine', r'infection'],
     'planted': [{'mistake': 'Keeps the misheard product name', 'text': 'Left lateral ankle venous ulcer cleansed with normal saline and PureCleanse, then Biatine alginate, ABD pad and Coban 2 Lite. Moderate serous drainage, periwound dry and flaky. Tolerating compression, no pain today.'}]},
    {'id': 'photo_plus_clinician_note', 'area': 'Wound photo + clinician words', 'profession': 'RPN', 'template': 'nursing',
     'pattern': 'Photo combined with the clinician\'s typed or spoken note', 'text': '',
     'facts': [
        {'name': 'wound_tissue', 'value': {'text': 'Wound bed with dark brown-black dry eschar at the distal toe tip.'}, 'confirmation_state': 'unconfirmed', 'source_ref': 'photo-1'},
        {'name': 'periwound', 'value': {'text': 'Surrounding skin with localized redness and mild swelling.'}, 'confirmation_state': 'unconfirmed', 'source_ref': 'photo-1'}],
     'note': 'Left second toe. Present about 3 weeks. Client reports throbbing pain 5 out of 10. Toe feels warm on assessment. Known diabetes. Dressed with Inadine and dry gauze.',
     'expected': ['left second toe (from the clinician)', 'about 3 weeks', 'dry dark eschar at tip (photo)', 'redness and mild swelling (photo)',
                  'throbbing pain 5/10 (client)', 'warm on assessment (clinician)', 'diabetes', 'Inadine and dry gauze'],
     'must': [r'left (2nd|second) toe', r'eschar', r'(red|erythema)', r'(swell|edema)', r'3 weeks', r'5\s*(/|out of)\s*10', r'warm', r'diabet', r'Inadine'],
     'must_not': [r'For clinician review', r'osteomyelitis', r'\bright\b', r'no odou?r', r'cellulitis (is )?(present|confirmed)']},
    {'id': 'photo_without_note', 'area': 'Wound photo only', 'profession': 'RN', 'template': 'nursing', 'pattern': 'Nothing invented from a photo alone',
     'text': '', 'note': '',
     'facts': [
        {'name': 'wound_tissue', 'value': {'text': 'Moist pink-red granulation tissue centrally with adherent yellow slough.'}, 'confirmation_state': 'unconfirmed', 'source_ref': 'photo-1'},
        {'name': 'wound_edges', 'value': {'text': 'Irregular edges.'}, 'confirmation_state': 'unconfirmed', 'source_ref': 'photo-1'}],
     'expected': ['granulation with slough', 'irregular edges', 'no side, size, pain, odour or drainage amount invented'],
     'must': [r'granulation', r'slough', r'irregular'], 'must_not': [r'\bleft\b|\bright\b', r'\d+\s*(cm|mm)', r'no odou?r', r'pain'],
     'planted': [{'mistake': 'Invents a side and a size', 'text': 'Right heel wound 2 x 3 cm with moist granulation centrally and adherent yellow slough. Irregular edges.'}]},
    {'id': 'physician_update_toe', 'area': 'Letter to physician', 'profession': 'RPN', 'template': 'physician_update', 'pattern': 'Letter keeps the request',
     'text': "Letter to the doctor. Left third toe has dry black eschar at the tip, mild swelling and redness, no drainage. "
             "Compression makes her foot throb when tightened. Previous ABI normal. Please assess circulation and whether daily wound care is needed.",
     'expected': ['left 3rd toe dry black eschar at tip', 'mild swelling and redness', 'no drainage', 'throbbing with compression', 'previous ABI normal',
                  'request: assess circulation and whether daily wound care'],
     'must': [r'left (3rd|third) toe', r'eschar', r'(swell|edema)', r'(red|erythema)', r'compression', r'(ABI|ABPI)', r'(circulation|vascular|perfusion)', r'daily'],
     'must_not': [r'gangrene (is )?(present|confirmed)', r'antibiotic']},
    {'id': 'venous_ulcer_soap_np', 'area': 'Wound care', 'profession': 'Nurse Practitioner', 'template': 'soap', 'pattern': 'NP voice, plan exactly as stated',
     'text': 'Right medial gaiter venous ulcer, 4 by 3 cm, shallow, granulating, moderate serous exudate, hemosiderin staining around. ABPI 0.95 last month. '
             'No signs of infection. Continue two layer compression, Mepilex Ag change twice weekly, elevate legs, review in two weeks.',
     'expected': ['right medial gaiter venous ulcer 4 x 3 cm', 'shallow, granulating', 'moderate serous', 'hemosiderin staining', 'ABPI 0.95',
                  'no signs of infection (stated)', 'two-layer compression', 'Mepilex Ag twice weekly', 'elevation', 'review in 2 weeks'],
     'must': [r'right medial', r'4\s*(x|by)\s*3', r'granulat', r'serous', r'hemosiderin', r'0\.95', r'(two|2)[- ]layer', r'Mepilex Ag', r'twice weekly|2x weekly',
              r'elevat', r'(two|2) weeks'],
     'must_not': [r'antibiotic', r'debride']},

    # ---------------- Medicine ----------------
    {'id': 'medicine_heart_failure', 'area': 'Internal medicine', 'profession': 'MD', 'template': 'soap', 'pattern': 'Doctor note, dose change exact',
     'text': "Seventy eight year old man with heart failure, ejection fraction 30 percent. Three kilo weight gain this week, more short of breath "
             "climbing stairs, orthopnea two pillows, no chest pain. BP 142 over 86, heart rate 96, sats 93 percent room air. Crackles at both bases, "
             "pitting edema to the knees. I'm increasing furosemide from 40 mg to 80 mg daily, checking electrolytes and creatinine in 3 days, "
             "and see him back in one week.",
     'expected': ['HF, EF 30%', '3 kg gain', 'DOE, 2-pillow orthopnea', 'no chest pain', 'BP 142/86, HR 96, SpO2 93% RA', 'bibasilar crackles',
                  'pitting edema to knees', 'furosemide 40 -> 80 mg daily', 'lytes and creatinine in 3 days', 'follow up 1 week'],
     'must': [r'heart failure', r'30\s*(%|percent)', r'3\s*(kg|kilo)', r'142\s*/\s*86', r'96', r'93\s*(%|percent)', r'crackles', r'edema',
              r'furosemide.{0,40}80\s*mg|80\s*mg.{0,40}furosemide', r'40\s*mg', r'electrolytes|creatinine', r'(one|1) week'],
     'must_not': [r'chest pain (is )?present', r'spironolactone', r'BNP'],
     'planted': [{'mistake': 'Wrong new dose', 'text': 'S: 78-year-old man with HF, EF 30%, 3 kg weight gain, dyspnea on stairs, 2-pillow orthopnea, no chest pain. O: BP 142/86, HR 96, SpO2 93% RA, bibasilar crackles, pitting edema to knees. A: Volume overload. P: Increase furosemide from 40 mg to 120 mg daily, electrolytes and creatinine in 3 days, review in 1 week.'}]},
    {'id': 'copd_exacerbation_np', 'area': 'Primary care', 'profession': 'Nurse Practitioner', 'template': 'nursing', 'pattern': 'Vitals and plan exact',
     'text': 'COPD, increased sputum, now green, more short of breath for 3 days. Sats 89 percent at rest, usual is 92. Resp rate 24, temp 37.9. '
             'Prednisone 40 mg daily for 5 days and doxycycline started, puffers reviewed, will call tomorrow, go to ED if worse.',
     'expected': ['COPD', 'green sputum, more SOB x 3 days', 'SpO2 89% (baseline 92%)', 'RR 24, T 37.9', 'prednisone 40 mg x 5 days', 'doxycycline',
                  'inhaler review', 'call tomorrow', 'ED if worse'],
     'must': [r'COPD', r'green', r'(3|three) days', r'89\s*(%|percent)', r'92', r'24', r'37\.9', r'prednisone.{0,20}40\s*mg', r'(5|five) days', r'doxycycline',
              r'(inhaler|puffer)', r'(ED|emergency)'],
     'must_not': [r'doxycycline\s*\d+\s*mg', r'oxygen (was )?(started|applied)']},
    {'id': 'hypoglycemia_home', 'area': 'Home care / diabetes', 'profession': 'RN', 'template': 'nursing', 'pattern': 'Intervention, response and recheck time',
     'text': 'Found client sweaty and shaky, glucose 2.9. Gave 175 mL juice. Recheck at 15 minutes 5.8, alert and talking normally. '
             'On gliclazide. Had skipped lunch. Informed family doctor by fax.',
     'expected': ['diaphoretic, shaky', 'BG 2.9', '175 mL juice', 'recheck 15 min 5.8', 'alert, normal speech', 'gliclazide', 'skipped lunch',
                  'family doctor informed by fax'],
     'must': [r'2\.9', r'175\s*mL', r'15 min', r'5\.8', r'alert', r'gliclazide', r'(skipped|missed) lunch', r'(fax|family (doctor|physician))'],
     'must_not': [r'glucagon', r'dextrose', r'EMS'],
     'planted': [{'mistake': 'Invents a response and drops the recheck value', 'text': 'Client diaphoretic and shaky, glucose 2.9. 175 mL juice given with good effect. On gliclazide, skipped lunch. Family doctor informed by fax.'}]},
    {'id': 'new_af_md', 'area': 'Cardiology / medicine', 'profession': 'MD', 'template': 'soap', 'pattern': 'Doctor assessment allowed, no invented doses',
     'text': 'Palpitations since this morning. ECG shows atrial fibrillation, rate 128. BP 118 over 72. No chest pain, no syncope. CHADS-65 positive, age 72 with hypertension. '
             'Start apixaban 5 mg twice daily and metoprolol 25 mg twice daily, echo ordered, review in clinic Monday.',
     'expected': ['palpitations since morning', 'AF rate 128', 'BP 118/72', 'no chest pain, no syncope', 'CHADS-65 positive (72, HTN)',
                  'apixaban 5 mg BID', 'metoprolol 25 mg BID', 'echo ordered', 'review Monday'],
     'must': [r'atrial fibrillation|\bAF\b', r'128', r'118\s*/\s*72', r'(no|denies).{0,20}chest pain', r'syncope', r'CHADS', r'apixaban.{0,15}5\s*mg', r'metoprolol.{0,15}25\s*mg',
              r'echo', r'Monday'],
     'must_not': [r'warfarin', r'heparin', r'cardioversion']},
    {'id': 'np_uti_short', 'area': 'Primary care', 'profession': 'Nurse Practitioner', 'template': 'nursing', 'pattern': 'Short visit, no invented dose',
     'text': 'Burning on urination 2 days, frequency, no fever, no flank pain. Urine dip positive nitrites and leukocytes. Nitrofurantoin started per guideline, culture sent.',
     'expected': ['dysuria 2 days', 'frequency', 'no fever, no flank pain', 'dip nitrite+ leuk+', 'nitrofurantoin started', 'culture sent'],
     'must': [r'(2|two) days', r'frequency', r'nitrite', r'leukocyte', r'nitrofurantoin', r'culture', r'(no|denies).{0,30}fever'],
     'must_not': [r'\d+\s*mg', r'pyelonephritis (is )?(present|confirmed)'], 'max_words': 70},

    # ---------------- Surgery ----------------
    {'id': 'surgery_post_op', 'area': 'General surgery', 'profession': 'Surgeon', 'template': 'nursing', 'pattern': 'No invented exam findings',
     'text': "Post-op day 2 laparoscopic cholecystectomy. Port sites clean and dry, no erythema. JP drain 50 mL serosanguinous over 24 hours. "
             "Tolerating clear fluids, passing flatus. Pain 4 out of 10 on oral analgesia. Plan remove drain tomorrow if under 30 mL, advance diet.",
     'expected': ['POD 2 lap chole', 'port sites clean, dry, no erythema', 'JP 50 mL serosanguinous / 24 h', 'clear fluids, flatus', 'pain 4/10 on oral analgesia',
                  'remove drain tomorrow if < 30 mL', 'advance diet'],
     'must': [r'(post-?op|postoperative).{0,15}(day )?2|POD ?2', r'cholecystectomy', r'50\s*mL', r'serosanguin', r'flatus', r'4\s*(/|out of)\s*10',
              r'30\s*mL', r'drain'], 'must_not': [r'bowel sounds', r'abdomen (is )?soft']},
    {'id': 'hip_fracture_pod3', 'area': 'Orthopedics', 'profession': 'RPN', 'template': 'nursing', 'pattern': 'Measurements and staples kept',
     'text': 'Day 3 after left hip hemiarthroplasty for fracture. Incision 14 cm, staples intact, small serous ooze on dressing, no redness. '
             'Mobilizing with walker to bathroom with one assist. Pain 5 out of 10 before hydromorphone, 2 out of 10 after. On enoxaparin.',
     'expected': ['POD 3 left hip hemiarthroplasty for fracture', '14 cm incision, staples intact', 'small serous ooze', 'no redness',
                  'walker, one assist', 'pain 5/10 -> 2/10 after hydromorphone', 'enoxaparin'],
     'must': [r'left hip', r'hemiarthroplasty', r'fractur', r'14\s*cm', r'staples', r'serous', r'walker', r'(one|1)[- ]assist|assist of (one|1)',
              r'5\s*(/|out of)\s*10', r'2\s*(/|out of)\s*10', r'hydromorphone', r'enoxaparin'],
     'must_not': [r'right hip', r'dehiscen', r'hydromorphone\s*\d']},
    {'id': 'dehiscence_concern', 'area': 'Surgery / community', 'profession': 'RN', 'template': 'physician_update', 'pattern': 'Worsening change emphasized, request kept',
     'text': 'Abdominal incision, the lower 3 cm has opened since Monday, yellow slough in the base, more drainage, now soaking two gauze a day, '
             'skin around is red about 2 cm. Temp 37.8. Client says it is more painful. Please review today, possible dehiscence.',
     'expected': ['lower 3 cm of abdominal incision opened since Monday', 'yellow slough', 'drainage increased, 2 gauze/day', 'erythema ~2 cm', 'T 37.8',
                  'more pain (client)', 'request same-day review, possible dehiscence'],
     'must': [r'3\s*cm', r'(open|separat|dehisc)', r'slough', r'(increas|more).{0,20}drainage|drainage.{0,30}increas', r'(two|2) gauze', r'2\s*cm',
              r'37\.8', r'(pain)', r'(today|same[- ]day)'],
     'must_not': [r'antibiotic', r'(infection|dehiscence) (is )?confirmed'],
     'planted': [{'mistake': 'Loses the worsening and the urgency', 'text': 'Abdominal incision with an open lower area and yellow slough. Skin around red about 2 cm. Temp 37.8. Please review at your convenience.'}]},
    {'id': 'ostomy_check', 'area': 'Ostomy', 'profession': 'RPN', 'template': 'nursing', 'pattern': 'Device terms kept',
     'text': 'Colostomy check. Stoma red, moist, budded about 1 cm. Peristomal skin with a 1 cm area of redness at 6 o clock under the flange. '
             'Changed to a convex 2 piece system cut to 35 mm, applied barrier ring. Output soft brown.',
     'expected': ['colostomy', 'stoma red, moist, ~1 cm budded', 'peristomal redness 1 cm at 6 o\'clock', 'convex 2-piece cut to 35 mm', 'barrier ring', 'soft brown output'],
     'must': [r'colostomy', r'(red|pink).{0,15}moist|moist.{0,15}(red|pink)', r'1\s*cm', r'6 o.?clock', r'convex', r'35\s*mm', r'barrier ring', r'soft'],
     'must_not': [r'ileostomy', r'fungal|candida']},

    # ---------------- Emergency ----------------
    {'id': 'emergency_hypoxia_transfer', 'area': 'Emergency / community', 'profession': 'RN', 'template': 'nursing', 'pattern': 'Actual outcome, caregiver attribution',
     'text': "Wife said he's been more tired and confused since yesterday. On arrival sats 86 percent on room air, resp rate 28, heart rate 118, "
             "temp 38.4. Recently discharged after aspiration pneumonia. I called 911, EMS came and took him to the emergency department.",
     'expected': ['wife reports more tired and confused since yesterday', 'SpO2 86% RA, RR 28, HR 118, T 38.4', 'recent aspiration pneumonia',
                  'EMS activated, transported to ED'],
     'must': [r'86\s*(%|percent)', r'28', r'118', r'38\.4', r'(wife|spouse).{0,30}(report|state|said)', r'aspiration pneumonia', r'transport'],
     'must_not': [r'client is (lethargic|confused)', r'lungs clear', r'ER recommended'],
     'planted': [{'mistake': 'Turns the wife\'s words into an exam finding and records only a recommendation', 'text': 'Client lethargic and confused. SpO2 86% RA, RR 28, HR 118, T 38.4. Recent aspiration pneumonia. ER recommended.'}]},
    {'id': 'chest_pain_ems', 'area': 'Emergency', 'profession': 'RPN', 'template': 'sbar', 'pattern': 'SBAR with exact vitals and actions',
     'text': 'Client with crushing chest pain started 20 minutes ago, radiating to left arm, sweaty. BP 156 over 94, heart rate 104. History of MI and stents. '
             'Gave nitro spray once as per his prescription, called 911, EMS took over at 10:42.',
     'expected': ['crushing chest pain 20 min, radiating left arm, diaphoretic', 'BP 156/94, HR 104', 'history MI, stents', 'one nitro spray (own prescription)',
                  '911 called, EMS took over 10:42'],
     'must': [r'chest pain', r'20 min', r'left arm', r'(sweat|diaphores)', r'156\s*/\s*94', r'104', r'(MI|myocardial)', r'stent', r'nitro', r'(911|EMS)', r'10:42'],
     'must_not': [r'aspirin', r'ECG (shows|showed)', r'pain (resolved|relieved)']},
    {'id': 'fall_on_anticoagulant', 'area': 'Emergency / falls', 'profession': 'RN', 'template': 'nursing', 'pattern': 'Risk factor kept, refusal documented as said',
     'text': 'Daughter found her on the floor this morning, she hit her head on the dresser. Small 2 cm bump on the right forehead. On apixaban. '
             'Alert and answering questions. I advised ED for head scan, client refused, daughter will stay with her, I reviewed signs to call 911.',
     'expected': ['found on floor by daughter', 'hit head on dresser', '2 cm bump right forehead', 'on apixaban', 'alert, answering questions',
                  'advised ED for scan, client REFUSED', 'daughter staying', 'reviewed when to call 911'],
     'must': [r'daughter', r'(floor|fall)', r'head', r'2\s*cm', r'right forehead', r'apixaban', r'alert', r'(ED|emergency)', r'refus', r'911'],
     'must_not': [r'transported', r'oriented (x|×)\s*3', r'CT (shows|showed|was)']},
    {'id': 'sepsis_concern_refusal', 'area': 'Emergency / palliative boundary', 'profession': 'RN', 'template': 'nursing', 'pattern': 'Correction of a vital sign',
     'text': 'Temp 39.1, heart rate 122, BP 96 over 58, sorry I mean 92 over 58. Shivering. Leg redness spreading past the line I drew yesterday. '
             'On call physician paged at 15:10, advised ED now. Client agreed, son driving her.',
     'expected': ['T 39.1, HR 122, BP 92/58 (96 was corrected)', 'rigors', 'leg redness spreading past yesterday\'s line', 'on-call physician paged 15:10, advised ED',
                  'client agreed, son driving'],
     'must': [r'39\.1', r'122', r'92\s*/\s*58', r'(shiver|rigor)', r'spread', r'15:10', r'(ED|emergency)', r'son'],
     'must_not': [r'96\s*/\s*58', r'EMS', r'antibiotic']},

    # ---------------- Palliative care ----------------
    {'id': 'palliative_symptom', 'area': 'Palliative care', 'profession': 'RPN', 'template': 'nursing', 'pattern': 'No invented response or scores',
     'text': "Palliative client with metastatic lung cancer. Daughter reports more breathless overnight. Pain 8 out of 10 in the chest. "
             "Gave hydromorphone 1 mg subcut at 14:00 as per order. Will reassess in 30 minutes. Goals of care comfort at home.",
     'expected': ['metastatic lung cancer, palliative', 'daughter reports more breathless overnight', 'chest pain 8/10', 'hydromorphone 1 mg subcut 14:00 per order',
                  'reassess in 30 min', 'goal: comfort at home', 'no response invented'],
     'must': [r'metasta', r'daughter.{0,30}(report|state)', r'8\s*(/|out of)\s*10', r'hydromorphone.{0,20}1\s*mg', r'(subcut|SC|subcutaneous)', r'14:00|1400',
              r'30 minutes', r'comfort'],
     'must_not': [r'(pain|symptoms?) (improved|relieved|decreased)|good effect|effective', r'PPS\s*\d', r'ESAS'],
     'planted': [{'mistake': 'Invents the response to the medication', 'text': 'Palliative client with metastatic lung cancer. Daughter reports more breathlessness overnight. Chest pain 8/10. Hydromorphone 1 mg subcut given at 14:00 per order with good effect, pain now 3/10. Goals of care comfort at home.'}]},
    {'id': 'terminal_secretions', 'area': 'Palliative / end of life', 'profession': 'RN', 'template': 'nursing', 'pattern': 'Response documented when given',
     'text': 'Actively dying, not responding, noisy breathing from secretions. Repositioned on side, gave glycopyrrolate 0.4 mg subcut at 09:15 as ordered. '
             'At 09:45 breathing quieter. Wife at bedside, wants him to stay home, aware of who to call overnight.',
     'expected': ['actively dying, unresponsive', 'noisy secretions', 'repositioned on side', 'glycopyrrolate 0.4 mg subcut 09:15 as ordered',
                  '09:45 quieter breathing', 'wife at bedside, wants home death', 'knows who to call overnight'],
     'must': [r'(actively dying|end of life)', r'secretion', r'reposition', r'glycopyrrolate.{0,20}0\.4\s*mg', r'09:15|0915', r'09:45|0945', r'quieter', r'wife',
              r'home', r'overnight'],
     'must_not': [r'oxygen', r'suction(ed|ing) (performed|done)', r'hospital transfer']},
    {'id': 'delirium_caregiver', 'area': 'Palliative / delirium', 'profession': 'RPN', 'template': 'nursing', 'pattern': 'Caregiver report vs observed finding',
     'text': 'Husband says she has been seeing people in the room and pulling at her blanket since last night. When I arrived she was drowsy but woke to voice, '
             'not sure of the day. No bowel movement for 4 days. Urine dark, small amounts. Palliative physician called, will visit at 4 pm.',
     'expected': ['husband reports visual hallucinations and picking at blanket since last night (caregiver report)', 'drowsy, rouses to voice, disoriented to day (observed)',
                  'no BM x 4 days', 'dark, small urine', 'palliative physician called, visiting 4 pm'],
     'must': [r'husband.{0,30}(report|state|says)', r'(seeing people|hallucinat)', r'blanket', r'drows', r'voice', r'day', r'(4|four) days', r'dark',
              r'palliative (physician|doctor)', r'4\s*(pm|PM|p\.m\.)|16:00'],
     'must_not': [r'client (is )?hallucinating', r'urinary retention (is )?(present|confirmed)', r'haloperidol']},
    {'id': 'malignant_wound_comfort', 'area': 'Palliative wound', 'profession': 'RPN', 'template': 'nursing', 'pattern': 'Comfort goals, odour control as stated',
     'text': 'Fungating breast wound, strong odour, bleeds on touch, heavy drainage. Goal is comfort not healing. Metronidazole gel applied, '
             'non-adherent contact layer, absorbent pad. Client says the smell bothers her the most. Charcoal dressing ordered.',
     'expected': ['fungating breast wound', 'strong odour', 'contact bleeding', 'heavy drainage', 'goal comfort not healing', 'metronidazole gel',
                  'non-adherent contact layer, absorbent pad', 'odour bothers client most', 'charcoal dressing ordered'],
     'must': [r'fungating', r'breast', r'odou?r', r'bleed', r'heavy', r'comfort', r'metronidazole', r'(non-?adherent|contact layer)', r'charcoal'],
     'must_not': [r'debride', r'heal(ing)? expected']},

    # ---------------- Specialist consultations ----------------
    {'id': 'dermatology_drug_rash', 'area': 'Dermatology', 'profession': 'Dermatologist', 'template': 'soap', 'pattern': 'Specialist voice, no invented doses',
     'text': "Rash started 5 days after starting amoxicillin. Itchy, red, blotchy on trunk and arms. No blistering, no mouth or eye involvement, "
             "no fever. Stop amoxicillin, cetirizine for itch, review in 48 hours or sooner if blistering or mucosal symptoms.",
     'expected': ['rash 5 days after amoxicillin', 'pruritic, erythematous, blotchy, trunk and arms', 'no blistering, no mucosal involvement, no fever',
                  'stop amoxicillin', 'cetirizine', 'review 48 h or sooner if blistering/mucosal'],
     'must': [r'amoxicillin', r'(5|five) days', r'trunk', r'arms', r'(no|without|denies).{0,40}blister', r'(mouth|mucosal|oral)', r'cetirizine', r'48 hours'],
     'must_not': [r'\d+\s*mg', r'DRESS (is|confirmed)', r'Stevens']},
    {'id': 'urology_spc_hematuria', 'area': 'Urology', 'profession': 'Urologist', 'template': 'soap', 'pattern': 'Specialist plan exact',
     'text': 'Suprapubic catheter changed in clinic, 16 French, passed smoothly. Since then pink urine for 2 days, no clots, draining well. Hemoglobin stable at 128. '
             'Increase fluids, no further exchange this month, come back if dark red urine, clots or not draining.',
     'expected': ['SPC changed in clinic, 16 Fr, smooth', 'pink urine 2 days, no clots, draining', 'Hb 128 stable', 'increase fluids',
                  'no further exchange this month', 'return if dark red, clots or not draining'],
     'must': [r'suprapubic', r'16\s*(Fr|French)', r'pink', r'(2|two) days', r'(no|without) clots', r'128', r'fluid', r'clots'],
     'must_not': [r'irrigat\w* (performed|started)', r'cystoscopy (shows|showed)']},
    {'id': 'psychiatry_followup', 'area': 'Psychiatry', 'profession': 'Psychiatrist', 'template': 'soap', 'pattern': 'Safety statements as said',
     'text': 'Follow up depression. Sleep improved, mood 6 out of 10 from 3. Denies suicidal thoughts today, has a safety plan. Sertraline 100 mg, no side effects. '
             'Continue, therapy weekly, follow up 4 weeks.',
     'expected': ['depression follow-up', 'sleep improved', 'mood 6/10 (was 3/10)', 'denies SI today, safety plan', 'sertraline 100 mg, no side effects',
                  'continue, weekly therapy, follow up 4 weeks'],
     'must': [r'depress', r'sleep', r'6\s*(/|out of)\s*10', r'3', r'(denies|no).{0,20}suicid', r'safety plan', r'sertraline.{0,15}100\s*mg', r'(4|four) weeks'],
     'must_not': [r'increase.{0,20}sertraline', r'homicid']},

    # ---------------- Long conversations and background speech ----------------
    {'id': 'long_multi_problem_visit', 'area': 'Home care, long visit', 'profession': 'RPN', 'template': 'nursing', 'pattern': 'Long conversation, every problem kept',
     'text': "Hi there, come in, sorry about the dog. So how have you been since Tuesday? Not great, my legs are more swollen. Okay. Let me look. "
             "Both lower legs pitting edema to mid shin, worse on the left. Weight today 92.4 kilos, last week 89.8. "
             "Are you short of breath? A bit when I climb the stairs, not at rest. Any chest pain? No. "
             "Your sugar this morning? It was 11.2. Did you take your insulin? I forgot last night. Okay. "
             "And the sore on your sacrum? Let me check. Stage 2 pressure injury, 1.5 by 1 cm, pink base, no slough. Cleansed with saline, foam dressing. "
             "Your daughter said you've been sleeping in the chair. Yes it's easier to breathe. Okay. "
             "Oh is that the TV? The game is on. Anyway. I'll fax your family doctor about the weight gain and edema today. "
             "Reminder to take insulin as prescribed, and keep your legs up. See you Friday.",
     'expected': ['more leg swelling since Tuesday (client)', 'bilateral pitting edema to mid-shin, worse left', 'weight 92.4 kg (89.8 last week)',
                  'SOB on stairs, not at rest; no chest pain', 'BG 11.2, missed insulin last night', 'sacral stage 2, 1.5 x 1 cm, pink base, no slough',
                  'saline, foam', 'daughter reports sleeping in chair (easier to breathe)', 'fax to family doctor re weight/edema', 'education: insulin, elevate legs',
                  'next visit Friday', 'no dog/TV/game'],
     'must': [r'pitting', r'mid[- ]?shin', r'left', r'92\.4', r'89\.8', r'stairs', r'(no|denies).{0,20}chest pain', r'11\.2', r'insulin', r'(stage 2|stage II)',
              r'1\.5\s*(x|by)\s*1', r'sacr', r'foam', r'daughter', r'chair', r'fax', r'Friday'],
     'must_not': [r'\bdog\b', r'\bTV\b|television', r'\bgame\b', r'orthopnea (is )?confirmed', r'lungs'],
     'planted': [{'mistake': 'Loses the weight gain and the missed insulin', 'text': 'Bilateral pitting edema to mid-shin, worse on the left. Short of breath on stairs, no chest pain. Sacral stage 2 pressure injury 1.5 x 1 cm, pink base, cleansed with saline, foam applied. Family doctor to be faxed. Next visit Friday.'}]},
    {'id': 'background_speech_repetition', 'area': 'Nursing, noisy visit', 'profession': 'RN', 'template': 'nursing', 'pattern': 'Repeated sections and other voices',
     'text': "PICC dressing change. Okay the PICC, right arm, dressing changed. Mom can you turn that down? Right arm PICC site clean, no redness, no swelling. "
             "External length 2 cm, same as last week. External length 2 cm. Flushed with 10 mL saline, good blood return. "
             "Somebody in the background: did you call grandma back? Yes later. Antibiotic infusion continues daily. Site clean, no redness.",
     'expected': ['right arm PICC dressing changed', 'site clean, no redness or swelling', 'external length 2 cm, unchanged', 'flushed 10 mL saline, blood return',
                  'daily antibiotic infusion continues', 'each fact once; no family conversation'],
     'must': [r'PICC', r'right arm', r'(no|without) redness', r'swelling', r'2\s*cm', r'10\s*mL', r'blood return', r'daily'],
     'must_not': [r'grandma', r'turn (that|it) down', r'(no redness).*(no redness)']},
]


def review_markdown() -> str:
    """The case list in plain words, for an independent reviewer (no code needed)."""
    lines = ['# Amilya permanent evaluation visits (all details invented)', '',
             'Every case below is made up. "Expected" is what a correct note must keep. "Must not" lists mistakes the note must not contain. '
             '"Planted wrong reports" are deliberately bad notes that the checks must catch.', '']
    for n, case in enumerate(CASES, 1):
        lines += [f'## {n}. {case["id"]} — {case["area"]} ({case["profession"]}, {case["template"]})', '',
                  f'**Guards against:** {case.get("pattern", "")}', '']
        if case.get('text'):
            lines += ['**What the clinician said:**', '', '> ' + case['text'], '']
        if case.get('facts'):
            lines += ['**Photo findings:** ' + ' '.join(f['value']['text'] for f in case['facts']), '']
        if case.get('note'):
            lines += ['**Clinician\'s note with the photo:** ' + case['note'], '']
        lines += ['**Expected in the note:**', ''] + [f'- {item}' for item in case['expected']] + ['']
        if case.get('clarify'):
            lines += ['**Expected alert:** a short "please confirm" note for the unclear term.', '']
        if case.get('max_words'):
            lines += [f'**Length:** at most about {case["max_words"]} words.', '']
        for planted in case.get('planted', []):
            lines += [f'**Planted wrong report ({planted["mistake"]}):** {planted["text"]}', '']
    return '\n'.join(lines)
