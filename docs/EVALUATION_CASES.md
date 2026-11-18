# Amilya permanent evaluation visits (all details invented)

Every case below is made up. "Expected" is what a correct note must keep. "Must not" lists mistakes the note must not contain. "Planted wrong reports" are deliberately bad notes that the checks must catch.

## 1. oncology_bleeding_lesion — Nursing / oncology (RPN, nursing)

**Guards against:** Lost diagnosis and unclear term (the "cell cancer" report)

**What the clinician said:**

> Okay so this client has lymphoma, she's immune compromised. She also has cell cancer, one lesion on the left thigh. It's bleeding today, she says it hurts, pain about 6 out of 10. It's getting bigger and there's more drainage than last week, serous, moderate amount. She cannot change her own dressing so we're seeing her daily. I cleansed with saline and applied Mepilex. High risk for infection. She has surgery scheduled November 6th.

**Expected in the note:**

- lymphoma
- immunocompromised
- cancerous lesion, exact type to be confirmed
- left thigh
- bleeding today
- pain 6/10
- lesion larger
- more serous drainage, moderate
- seen daily because she cannot change her own dressing
- saline, Mepilex
- high risk for infection
- surgery November 6

**Expected alert:** a short "please confirm" note for the unclear term.

**Planted wrong report (Drops the bleeding and the growth of the lesion):** Client has lymphoma and is immunocompromised. Cancerous lesion to the left thigh with moderate serous drainage; pain 6/10. Unable to change her own dressing, so seen daily. Cleansed with saline, Mepilex applied. Surgery scheduled for November 6.

**Planted wrong report (Guesses the cancer type and invents a physician notification):** Client has lymphoma and squamous cell carcinoma of the left thigh, bleeding and larger, more serous drainage; pain 6/10. Unable to change her own dressing, so seen daily. Cleansed with saline, Mepilex applied. Physician notified. Surgery November 6.

## 2. routine_catheter — Nursing (RPN, nursing)

**Guards against:** Short visit must stay short

**What the clinician said:**

> Seen for blocked suprapubic catheter. Changed it, 16 French, clear yellow urine back right away. No other concerns, thanks.

**Expected in the note:**

- blocked suprapubic catheter
- changed, 16 Fr
- clear urine return
- no other concerns

**Length:** at most about 45 words.

**Planted wrong report (Wrong catheter size):** Seen for blocked suprapubic catheter. Catheter changed using 18 Fr catheter with clear urine return. No other concerns.

## 3. long_wound_soap_with_chatter — Wound care (RN, soap)

**Guards against:** Background chatter, a spoken correction of the side

**What the clinician said:**

> Hi how was the hockey game last night, oh nice. Okay, client seen for the left, sorry, correction, right heel pressure injury. She says pain is 3 out of 10 with dressing changes, no fever or chills. Diabetes, on metformin. Wound measures 3.2 by 2.1 cm, depth 0.4 cm. Wound bed 70 percent granulation 30 percent slough, edges rolled, periwound macerated. Moderate serous drainage, no odour. Cleansed with saline, Inadine applied, covered with Mepilex Border, heel offloaded with a pillow. Plan dressing every 3 days and she'll see the wound clinic next Tuesday. My daughter also plays hockey actually.

**Expected in the note:**

- RIGHT heel pressure injury (left was corrected)
- pain 3/10 with dressing changes
- denies fever, chills
- diabetes on metformin
- 3.2 x 2.1 x 0.4 cm
- 70% granulation, 30% slough
- rolled edges
- macerated periwound
- moderate serous, no odour
- saline, Inadine, Mepilex Border, offloaded
- every 3 days
- wound clinic next Tuesday
- no hockey

**Planted wrong report (Keeps the side the clinician corrected):** S: Pain 3/10 with dressing changes. Denies fever or chills. Diabetes on metformin. O: Left heel pressure injury 3.2 x 2.1 x 0.4 cm, 70% granulation, 30% slough, rolled edges, macerated periwound, moderate serous drainage, no odour. Cleansed with saline, Inadine, Mepilex Border, heel offloaded. P: Dressing every 3 days; wound clinic next Tuesday.

**Planted wrong report (Includes the small talk):** S: Client chatted about hockey. Pain 3/10 with dressing changes. Denies fever or chills. Diabetes on metformin. O: Right heel pressure injury 3.2 x 2.1 x 0.4 cm, 70% granulation, 30% slough, rolled edges, macerated periwound, moderate serous drainage, no odour. Cleansed with saline, Inadine, Mepilex Border, offloaded. P: Every 3 days; wound clinic next Tuesday.

## 4. misheard_products — Wound care (RPN, nursing)

**Guards against:** Incorrect dressing names from speech recognition

**What the clinician said:**

> Left lateral ankle venous ulcer. Cleansed with normal sailing and pure cleanse, then Biatine alginate, ABD pad and Coban two lite. Moderate serous drainage, periwound dry and flaky. Client tolerating compression, no pain today.

**Expected in the note:**

- left lateral ankle venous ulcer
- normal saline (not "sailing")
- PureCleanse
- Biatain Alginate (not "Biatine")
- ABD pad
- Coban 2 Lite
- moderate serous drainage
- dry flaky periwound
- tolerating compression
- no pain today

**Planted wrong report (Keeps the misheard product name):** Left lateral ankle venous ulcer cleansed with normal saline and PureCleanse, then Biatine alginate, ABD pad and Coban 2 Lite. Moderate serous drainage, periwound dry and flaky. Tolerating compression, no pain today.

## 5. photo_plus_clinician_note — Wound photo + clinician words (RPN, nursing)

**Guards against:** Photo combined with the clinician's typed or spoken note

**Photo findings:** Wound bed with dark brown-black dry eschar at the distal toe tip. Surrounding skin with localized redness and mild swelling.

**Clinician's note with the photo:** Left second toe. Present about 3 weeks. Client reports throbbing pain 5 out of 10. Toe feels warm on assessment. Known diabetes. Dressed with Inadine and dry gauze.

**Expected in the note:**

- left second toe (from the clinician)
- about 3 weeks
- dry dark eschar at tip (photo)
- redness and mild swelling (photo)
- throbbing pain 5/10 (client)
- warm on assessment (clinician)
- diabetes
- Inadine and dry gauze

## 6. photo_without_note — Wound photo only (RN, nursing)

**Guards against:** Nothing invented from a photo alone

**Photo findings:** Moist pink-red granulation tissue centrally with adherent yellow slough. Irregular edges.

**Expected in the note:**

- granulation with slough
- irregular edges
- no side, size, pain, odour or drainage amount invented

**Planted wrong report (Invents a side and a size):** Right heel wound 2 x 3 cm with moist granulation centrally and adherent yellow slough. Irregular edges.

## 7. physician_update_toe — Letter to physician (RPN, physician_update)

**Guards against:** Letter keeps the request

**What the clinician said:**

> Letter to the doctor. Left third toe has dry black eschar at the tip, mild swelling and redness, no drainage. Compression makes her foot throb when tightened. Previous ABI normal. Please assess circulation and whether daily wound care is needed.

**Expected in the note:**

- left 3rd toe dry black eschar at tip
- mild swelling and redness
- no drainage
- throbbing with compression
- previous ABI normal
- request: assess circulation and whether daily wound care

## 8. venous_ulcer_soap_np — Wound care (Nurse Practitioner, soap)

**Guards against:** NP voice, plan exactly as stated

**What the clinician said:**

> Right medial gaiter venous ulcer, 4 by 3 cm, shallow, granulating, moderate serous exudate, hemosiderin staining around. ABPI 0.95 last month. No signs of infection. Continue two layer compression, Mepilex Ag change twice weekly, elevate legs, review in two weeks.

**Expected in the note:**

- right medial gaiter venous ulcer 4 x 3 cm
- shallow, granulating
- moderate serous
- hemosiderin staining
- ABPI 0.95
- no signs of infection (stated)
- two-layer compression
- Mepilex Ag twice weekly
- elevation
- review in 2 weeks

## 9. medicine_heart_failure — Internal medicine (MD, soap)

**Guards against:** Doctor note, dose change exact

**What the clinician said:**

> Seventy eight year old man with heart failure, ejection fraction 30 percent. Three kilo weight gain this week, more short of breath climbing stairs, orthopnea two pillows, no chest pain. BP 142 over 86, heart rate 96, sats 93 percent room air. Crackles at both bases, pitting edema to the knees. I'm increasing furosemide from 40 mg to 80 mg daily, checking electrolytes and creatinine in 3 days, and see him back in one week.

**Expected in the note:**

- HF, EF 30%
- 3 kg gain
- DOE, 2-pillow orthopnea
- no chest pain
- BP 142/86, HR 96, SpO2 93% RA
- bibasilar crackles
- pitting edema to knees
- furosemide 40 -> 80 mg daily
- lytes and creatinine in 3 days
- follow up 1 week

**Planted wrong report (Wrong new dose):** S: 78-year-old man with HF, EF 30%, 3 kg weight gain, dyspnea on stairs, 2-pillow orthopnea, no chest pain. O: BP 142/86, HR 96, SpO2 93% RA, bibasilar crackles, pitting edema to knees. A: Volume overload. P: Increase furosemide from 40 mg to 120 mg daily, electrolytes and creatinine in 3 days, review in 1 week.

## 10. copd_exacerbation_np — Primary care (Nurse Practitioner, nursing)

**Guards against:** Vitals and plan exact

**What the clinician said:**

> COPD, increased sputum, now green, more short of breath for 3 days. Sats 89 percent at rest, usual is 92. Resp rate 24, temp 37.9. Prednisone 40 mg daily for 5 days and doxycycline started, puffers reviewed, will call tomorrow, go to ED if worse.

**Expected in the note:**

- COPD
- green sputum, more SOB x 3 days
- SpO2 89% (baseline 92%)
- RR 24, T 37.9
- prednisone 40 mg x 5 days
- doxycycline
- inhaler review
- call tomorrow
- ED if worse

## 11. hypoglycemia_home — Home care / diabetes (RN, nursing)

**Guards against:** Intervention, response and recheck time

**What the clinician said:**

> Found client sweaty and shaky, glucose 2.9. Gave 175 mL juice. Recheck at 15 minutes 5.8, alert and talking normally. On gliclazide. Had skipped lunch. Informed family doctor by fax.

**Expected in the note:**

- diaphoretic, shaky
- BG 2.9
- 175 mL juice
- recheck 15 min 5.8
- alert, normal speech
- gliclazide
- skipped lunch
- family doctor informed by fax

**Planted wrong report (Invents a response and drops the recheck value):** Client diaphoretic and shaky, glucose 2.9. 175 mL juice given with good effect. On gliclazide, skipped lunch. Family doctor informed by fax.

## 12. new_af_md — Cardiology / medicine (MD, soap)

**Guards against:** Doctor assessment allowed, no invented doses

**What the clinician said:**

> Palpitations since this morning. ECG shows atrial fibrillation, rate 128. BP 118 over 72. No chest pain, no syncope. CHADS-65 positive, age 72 with hypertension. Start apixaban 5 mg twice daily and metoprolol 25 mg twice daily, echo ordered, review in clinic Monday.

**Expected in the note:**

- palpitations since morning
- AF rate 128
- BP 118/72
- no chest pain, no syncope
- CHADS-65 positive (72, HTN)
- apixaban 5 mg BID
- metoprolol 25 mg BID
- echo ordered
- review Monday

## 13. np_uti_short — Primary care (Nurse Practitioner, nursing)

**Guards against:** Short visit, no invented dose

**What the clinician said:**

> Burning on urination 2 days, frequency, no fever, no flank pain. Urine dip positive nitrites and leukocytes. Nitrofurantoin started per guideline, culture sent.

**Expected in the note:**

- dysuria 2 days
- frequency
- no fever, no flank pain
- dip nitrite+ leuk+
- nitrofurantoin started
- culture sent

**Length:** at most about 70 words.

## 14. surgery_post_op — General surgery (Surgeon, nursing)

**Guards against:** No invented exam findings

**What the clinician said:**

> Post-op day 2 laparoscopic cholecystectomy. Port sites clean and dry, no erythema. JP drain 50 mL serosanguinous over 24 hours. Tolerating clear fluids, passing flatus. Pain 4 out of 10 on oral analgesia. Plan remove drain tomorrow if under 30 mL, advance diet.

**Expected in the note:**

- POD 2 lap chole
- port sites clean, dry, no erythema
- JP 50 mL serosanguinous / 24 h
- clear fluids, flatus
- pain 4/10 on oral analgesia
- remove drain tomorrow if < 30 mL
- advance diet

## 15. hip_fracture_pod3 — Orthopedics (RPN, nursing)

**Guards against:** Measurements and staples kept

**What the clinician said:**

> Day 3 after left hip hemiarthroplasty for fracture. Incision 14 cm, staples intact, small serous ooze on dressing, no redness. Mobilizing with walker to bathroom with one assist. Pain 5 out of 10 before hydromorphone, 2 out of 10 after. On enoxaparin.

**Expected in the note:**

- POD 3 left hip hemiarthroplasty for fracture
- 14 cm incision, staples intact
- small serous ooze
- no redness
- walker, one assist
- pain 5/10 -> 2/10 after hydromorphone
- enoxaparin

## 16. dehiscence_concern — Surgery / community (RN, physician_update)

**Guards against:** Worsening change emphasized, request kept

**What the clinician said:**

> Abdominal incision, the lower 3 cm has opened since Monday, yellow slough in the base, more drainage, now soaking two gauze a day, skin around is red about 2 cm. Temp 37.8. Client says it is more painful. Please review today, possible dehiscence.

**Expected in the note:**

- lower 3 cm of abdominal incision opened since Monday
- yellow slough
- drainage increased, 2 gauze/day
- erythema ~2 cm
- T 37.8
- more pain (client)
- request same-day review, possible dehiscence

**Planted wrong report (Loses the worsening and the urgency):** Abdominal incision with an open lower area and yellow slough. Skin around red about 2 cm. Temp 37.8. Please review at your convenience.

## 17. ostomy_check — Ostomy (RPN, nursing)

**Guards against:** Device terms kept

**What the clinician said:**

> Colostomy check. Stoma red, moist, budded about 1 cm. Peristomal skin with a 1 cm area of redness at 6 o clock under the flange. Changed to a convex 2 piece system cut to 35 mm, applied barrier ring. Output soft brown.

**Expected in the note:**

- colostomy
- stoma red, moist, ~1 cm budded
- peristomal redness 1 cm at 6 o'clock
- convex 2-piece cut to 35 mm
- barrier ring
- soft brown output

## 18. emergency_hypoxia_transfer — Emergency / community (RN, nursing)

**Guards against:** Actual outcome, caregiver attribution

**What the clinician said:**

> Wife said he's been more tired and confused since yesterday. On arrival sats 86 percent on room air, resp rate 28, heart rate 118, temp 38.4. Recently discharged after aspiration pneumonia. I called 911, EMS came and took him to the emergency department.

**Expected in the note:**

- wife reports more tired and confused since yesterday
- SpO2 86% RA, RR 28, HR 118, T 38.4
- recent aspiration pneumonia
- EMS activated, transported to ED

**Planted wrong report (Turns the wife's words into an exam finding and records only a recommendation):** Client lethargic and confused. SpO2 86% RA, RR 28, HR 118, T 38.4. Recent aspiration pneumonia. ER recommended.

## 19. chest_pain_ems — Emergency (RPN, sbar)

**Guards against:** SBAR with exact vitals and actions

**What the clinician said:**

> Client with crushing chest pain started 20 minutes ago, radiating to left arm, sweaty. BP 156 over 94, heart rate 104. History of MI and stents. Gave nitro spray once as per his prescription, called 911, EMS took over at 10:42.

**Expected in the note:**

- crushing chest pain 20 min, radiating left arm, diaphoretic
- BP 156/94, HR 104
- history MI, stents
- one nitro spray (own prescription)
- 911 called, EMS took over 10:42

## 20. fall_on_anticoagulant — Emergency / falls (RN, nursing)

**Guards against:** Risk factor kept, refusal documented as said

**What the clinician said:**

> Daughter found her on the floor this morning, she hit her head on the dresser. Small 2 cm bump on the right forehead. On apixaban. Alert and answering questions. I advised ED for head scan, client refused, daughter will stay with her, I reviewed signs to call 911.

**Expected in the note:**

- found on floor by daughter
- hit head on dresser
- 2 cm bump right forehead
- on apixaban
- alert, answering questions
- advised ED for scan, client REFUSED
- daughter staying
- reviewed when to call 911

## 21. sepsis_concern_refusal — Emergency / palliative boundary (RN, nursing)

**Guards against:** Correction of a vital sign

**What the clinician said:**

> Temp 39.1, heart rate 122, BP 96 over 58, sorry I mean 92 over 58. Shivering. Leg redness spreading past the line I drew yesterday. On call physician paged at 15:10, advised ED now. Client agreed, son driving her.

**Expected in the note:**

- T 39.1, HR 122, BP 92/58 (96 was corrected)
- rigors
- leg redness spreading past yesterday's line
- on-call physician paged 15:10, advised ED
- client agreed, son driving

## 22. palliative_symptom — Palliative care (RPN, nursing)

**Guards against:** No invented response or scores

**What the clinician said:**

> Palliative client with metastatic lung cancer. Daughter reports more breathless overnight. Pain 8 out of 10 in the chest. Gave hydromorphone 1 mg subcut at 14:00 as per order. Will reassess in 30 minutes. Goals of care comfort at home.

**Expected in the note:**

- metastatic lung cancer, palliative
- daughter reports more breathless overnight
- chest pain 8/10
- hydromorphone 1 mg subcut 14:00 per order
- reassess in 30 min
- goal: comfort at home
- no response invented

**Planted wrong report (Invents the response to the medication):** Palliative client with metastatic lung cancer. Daughter reports more breathlessness overnight. Chest pain 8/10. Hydromorphone 1 mg subcut given at 14:00 per order with good effect, pain now 3/10. Goals of care comfort at home.

## 23. terminal_secretions — Palliative / end of life (RN, nursing)

**Guards against:** Response documented when given

**What the clinician said:**

> Actively dying, not responding, noisy breathing from secretions. Repositioned on side, gave glycopyrrolate 0.4 mg subcut at 09:15 as ordered. At 09:45 breathing quieter. Wife at bedside, wants him to stay home, aware of who to call overnight.

**Expected in the note:**

- actively dying, unresponsive
- noisy secretions
- repositioned on side
- glycopyrrolate 0.4 mg subcut 09:15 as ordered
- 09:45 quieter breathing
- wife at bedside, wants home death
- knows who to call overnight

## 24. delirium_caregiver — Palliative / delirium (RPN, nursing)

**Guards against:** Caregiver report vs observed finding

**What the clinician said:**

> Husband says she has been seeing people in the room and pulling at her blanket since last night. When I arrived she was drowsy but woke to voice, not sure of the day. No bowel movement for 4 days. Urine dark, small amounts. Palliative physician called, will visit at 4 pm.

**Expected in the note:**

- husband reports visual hallucinations and picking at blanket since last night (caregiver report)
- drowsy, rouses to voice, disoriented to day (observed)
- no BM x 4 days
- dark, small urine
- palliative physician called, visiting 4 pm

## 25. malignant_wound_comfort — Palliative wound (RPN, nursing)

**Guards against:** Comfort goals, odour control as stated

**What the clinician said:**

> Fungating breast wound, strong odour, bleeds on touch, heavy drainage. Goal is comfort not healing. Metronidazole gel applied, non-adherent contact layer, absorbent pad. Client says the smell bothers her the most. Charcoal dressing ordered.

**Expected in the note:**

- fungating breast wound
- strong odour
- contact bleeding
- heavy drainage
- goal comfort not healing
- metronidazole gel
- non-adherent contact layer, absorbent pad
- odour bothers client most
- charcoal dressing ordered

## 26. dermatology_drug_rash — Dermatology (Dermatologist, soap)

**Guards against:** Specialist voice, no invented doses

**What the clinician said:**

> Rash started 5 days after starting amoxicillin. Itchy, red, blotchy on trunk and arms. No blistering, no mouth or eye involvement, no fever. Stop amoxicillin, cetirizine for itch, review in 48 hours or sooner if blistering or mucosal symptoms.

**Expected in the note:**

- rash 5 days after amoxicillin
- pruritic, erythematous, blotchy, trunk and arms
- no blistering, no mucosal involvement, no fever
- stop amoxicillin
- cetirizine
- review 48 h or sooner if blistering/mucosal

## 27. urology_spc_hematuria — Urology (Urologist, soap)

**Guards against:** Specialist plan exact

**What the clinician said:**

> Suprapubic catheter changed in clinic, 16 French, passed smoothly. Since then pink urine for 2 days, no clots, draining well. Hemoglobin stable at 128. Increase fluids, no further exchange this month, come back if dark red urine, clots or not draining.

**Expected in the note:**

- SPC changed in clinic, 16 Fr, smooth
- pink urine 2 days, no clots, draining
- Hb 128 stable
- increase fluids
- no further exchange this month
- return if dark red, clots or not draining

## 28. psychiatry_followup — Psychiatry (Psychiatrist, soap)

**Guards against:** Safety statements as said

**What the clinician said:**

> Follow up depression. Sleep improved, mood 6 out of 10 from 3. Denies suicidal thoughts today, has a safety plan. Sertraline 100 mg, no side effects. Continue, therapy weekly, follow up 4 weeks.

**Expected in the note:**

- depression follow-up
- sleep improved
- mood 6/10 (was 3/10)
- denies SI today, safety plan
- sertraline 100 mg, no side effects
- continue, weekly therapy, follow up 4 weeks

## 29. long_multi_problem_visit — Home care, long visit (RPN, nursing)

**Guards against:** Long conversation, every problem kept

**What the clinician said:**

> Hi there, come in, sorry about the dog. So how have you been since Tuesday? Not great, my legs are more swollen. Okay. Let me look. Both lower legs pitting edema to mid shin, worse on the left. Weight today 92.4 kilos, last week 89.8. Are you short of breath? A bit when I climb the stairs, not at rest. Any chest pain? No. Your sugar this morning? It was 11.2. Did you take your insulin? I forgot last night. Okay. And the sore on your sacrum? Let me check. Stage 2 pressure injury, 1.5 by 1 cm, pink base, no slough. Cleansed with saline, foam dressing. Your daughter said you've been sleeping in the chair. Yes it's easier to breathe. Okay. Oh is that the TV? The game is on. Anyway. I'll fax your family doctor about the weight gain and edema today. Reminder to take insulin as prescribed, and keep your legs up. See you Friday.

**Expected in the note:**

- more leg swelling since Tuesday (client)
- bilateral pitting edema to mid-shin, worse left
- weight 92.4 kg (89.8 last week)
- SOB on stairs, not at rest; no chest pain
- BG 11.2, missed insulin last night
- sacral stage 2, 1.5 x 1 cm, pink base, no slough
- saline, foam
- daughter reports sleeping in chair (easier to breathe)
- fax to family doctor re weight/edema
- education: insulin, elevate legs
- next visit Friday
- no dog/TV/game

**Planted wrong report (Loses the weight gain and the missed insulin):** Bilateral pitting edema to mid-shin, worse on the left. Short of breath on stairs, no chest pain. Sacral stage 2 pressure injury 1.5 x 1 cm, pink base, cleansed with saline, foam applied. Family doctor to be faxed. Next visit Friday.

## 30. background_speech_repetition — Nursing, noisy visit (RN, nursing)

**Guards against:** Repeated sections and other voices

**What the clinician said:**

> PICC dressing change. Okay the PICC, right arm, dressing changed. Mom can you turn that down? Right arm PICC site clean, no redness, no swelling. External length 2 cm, same as last week. External length 2 cm. Flushed with 10 mL saline, good blood return. Somebody in the background: did you call grandma back? Yes later. Antibiotic infusion continues daily. Site clean, no redness.

**Expected in the note:**

- right arm PICC dressing changed
- site clean, no redness or swelling
- external length 2 cm, unchanged
- flushed 10 mL saline, blood return
- daily antibiotic infusion continues
- each fact once; no family conversation

