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
