# Qualitative photo assessment

Hazim explicitly requested no AI-generated tissue percentages: an estimate may
be wrong. New photo interpretations describe supported visible tissue in words.
The request and recovery instructions prohibit percentages. The photo-specific
response schema exposes a required text value and no numeric tissue fields.
Server validation rejects numeric tissue keys and percentage language (symbols,
full-width or Arabic percent signs, percent and per cent) before storing findings.
No percentage is silently converted into a different clinical description.

Independently valid visual observations survive an invalid candidate. If all
photo candidates fail, the existing single bounded schema recovery can request a
qualitative answer; a second invalid answer remains an error. No validation is
relaxed. Clinician-entered tissue values and source-quoted extraction keep their
existing typed percentage fields. Existing saved assessments are not rewritten.
No Android replacement, recording deletion, production deploy, billing change,
clinical-data enable, provider cap increase or expiry extension is included.

Regression coverage checks the outbound schema, qualitative output, prohibited
numeric keys, percentage text across all photo fields, preservation of independent
observations, bounded retry, repeated rejection, and clinician-entered values.

The private once-only `photo-words-v7` probe checks exactly two fixed synthetic
inputs through normal native authentication and multipart upload: unrelated shapes
must require retake and the generated fictional wound must yield a qualitative
tissue finding with no percentage keys or percentage language. The existing
US$1 total cap and original one-day expiry are checked before the durable claim.
No prior claim is reset. No real patient image is sent. Disable the probe after
its single run; do not repeat it by deleting its marker.

## Automated validation, October 28, 2026

Functional commit `f88a871ca8b74aeda94e9232a7ded99101eefec5` passed CI
`40788762835`: 975 backend tests passed / 1 skipped, Android build/unit/lint and
flag checks passed, and deployment safety checks passed. Google Cloud auth, CLI
and deploy steps remained skipped. Local focused checks passed 258 tests, then
62 tests covering the final photo-only probe addition.

Automatic Render deploy `dep-dbh8hecs728c73cq9ar0` became live at 23:44:53 UTC.
The explicitly selected `photo-words-v7` test uses separate deployment
`dep-dbh8hnst50us738jg5bg`; live results and cleanup follow after verification.

## Live synthetic verification and cleanup

On `f88a871ca8b74aeda94e9232a7ded99101eefec5`, the isolated native HTTP probe
logged photo PASS and wound PASS at 23:46:24 UTC. This requires an unrelated
image to be rejected and a generated fictional wound to produce a nonempty
qualitative tissue finding with text only, no numeric tissue keys or percentage
language, and no clinician confirmation. Deploy `dep-dbh8hnst50us738jg5bg` became
live at 23:46:48 UTC. This proves the current qualitative contract with fixed
synthetic inputs; it is not a clinical accuracy or new Samsung hardware claim.

The probe opt-in was cleared after that successful single run. A concurrent
Claude-authored prompt-only change `3025f6d279977ed48f4f20b6b61629210b92b5a0`
arrived before cleanup. It is a direct descendant of the qualitative change and
was preserved. It changes tissue terminology and removes a copyable example;
it leaves the no-percentage instructions, photo schema and rejection guard
unchanged. The live inference above tested the prior prompt. Cleanup deployment
`dep-dbh8j0tg1s2s73dhb6eg` uses this descendant, logged owner READY at 23:48:01 UTC
and became live at 23:48:17 UTC, with the probe disabled. No prior marker was reset.

After cleanup, public health/config returned HTTP 200, photo and audio remained
enabled for the sole eligible test owner, signup stayed closed and clinical data
and billing stayed off. The original $1 total cap and expiry 1793235000 (October
28 at 20:50 Toronto) remain unchanged. No renewed key or time extension, replaced
APK, recording deletion, original production traffic change or patient-data
enablement is included. Existing saved findings are not silently rewritten.

The preserved descendant also passed full CI run `40789636498`, including the
backend, Android and deployment safety jobs. Google Cloud authentication, CLI
and deployment steps remained skipped. This is automated coverage of the latest
prompt alongside the qualitative guard; no second paid photo run was made.
