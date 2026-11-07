# Amilya hourly Sentry monitor

`ci/sentry_monitor.py`, run by `.github/workflows/amilya-sentry-monitor.yml`. **Not scheduled yet**: the hourly
schedule stays commented out until the owner approves it.

## What it does

- Reads the `amilya-android` and `amilya-backend` Sentry projects with the existing **read-only** `SENTRY_AUTH_TOKEN`.
- Looks at issues seen in the **last 60 minutes**.
- Remembers, in the body of the "Amilya hourly monitor" issue, which issue IDs and counts it has already reported.
- Comments only when a problem is **new**, **reopened** (regressed, or was resolved or ignored), or **getting worse**.
  Getting worse means 5 or more new events and at least 50% more than last time, a louder level, or Sentry marking it
  escalating.
- Each line gives the Sentry short ID with its link, the workflow category, severity, test server or production,
  project, safe technical tags, events in the last hour and in total, first and last seen, and status.
- If Sentry cannot be read (no token, HTTP error, timeout), it posts **"Monitoring failure ... not a report of zero
  errors"** once. It does not repeat the same failure every hour, and it says when reading works again.
- A run with nothing to report only updates the "Last checked" line in the issue body, so ChatGPT can always see when
  the last check happened.

## Categories

Recording · Multipart handover · Transcription · Image upload · Report generation · Report saving · Ask Consultant ·
Backend exception · Clinical accuracy check · AI fallback · App crash or freeze · Monitoring self-test · Other.

Environment: `isolated-test` is shown as **Test server**, `production` as **Production**, and anything else as
**Unknown**.

## Privacy

Only these are read and written: short ID, link, times, counts, level, status, and the Amilya category tags
(environment, component, stage, code, http_status, route shape, kind, flow_type, retries, recording_exists, storage,
release, defects). Tag values must be short technical words. Titles must match Amilya's fixed
`Amilya <component>: <stage> <code>` shape, or they are withheld. Event messages, breadcrumbs, request data,
transcripts, notes, photos and user details are never copied, even when Sentry returns them (this is tested).

## Investigation of the two open issues (2026-11-07)

Both come from the same moment, **2026-11-06 21:04–21:05 UTC on the test server**. That was the owner's real noisy
home-visit recording, when the phone showed "The sources added so far do not contain enough assessment or treatment
information".

**AMILYA-BACKEND-2, `source_faithfulness_initial report_check_failed` (1 defect)**
- Server log order: the test server had just started (21:04:43, after the free plan's sleep). Then draft at 21:04:56,
  the check found 1 problem at 21:05:02, one repair at 21:05:07, and the recheck at 21:05:11.
- The visit ended with "not enough information" (no note). So the check **worked as designed**: it caught an
  unsupported or garbled statement in a draft written from a recording that was mostly TV, phone calls and filler.
  It is not an evaluation bug. The recording itself was unreliable.
- The exact defect wording is not recorded anywhere safe to read, because it would quote clinical text.
- Since then (test branch, not production): noisy-recording rules, a recording-quality alert, and a true message when
  no note can be written. A made-up noisy visit now produces a correct note with a quality alert, and a noise-only
  recording produces no note and the true reason. **Not yet re-tested with a real noisy recording on the phone.**
- Classification: an **initial** check failure followed by a repair is normal and expected. It only matters if it is
  frequent, or if it ends in no note. The monitor files it under "Clinical accuracy check" at Medium severity.

**AMILYA-ANDROID-3, `generation_request failed` (retries 0, recording_exists true, storage app_private)**
- The phone reports that one `/quick-report` request failed. The recording **was preserved** (`recording_exists
  true`, private app storage). The app's one automatic retry (on 502, 503 or 504) evidently worked, because the owner
  then saw the server's "not enough information" answer rather than "The report was NOT generated".
- Most likely cause: the **free test server waking from sleep**. It started 13 seconds before the draft began, so the
  first request probably hit the start-up and got a 502 or 504 or timed out. **Not proven.**
- What is missing to prove it: the Android event has no **HTTP status** or **failure category** (for example
  `REPORT_GENERATION_TIMEOUT` vs `BACKEND_FAILED`), because the phone records them locally but does not send them in
  the event. Recommended next step (needs approval, because it touches the Android app): send `http_status` and the
  existing failure category as two extra safe tags.
- Not fixed and not re-tested. A paid always-on test server, or a "wake the server" call before generating, would
  remove cold starts. Both need owner approval.
