# Photo cost comparison: prepared, not yet run live

Hazim approved comparing a cheaper photo setting before changing the app. The
candidate keeps the reviewed OpenAI GPT-6.1 Sol model, exact same image inputs,
prompt, strict qualitative validator and 12,000-token completion ceiling. It
varies only reasoning effort: current high versus candidate medium. A smaller
reasoning budget may reduce generated tokens; it is not a cheaper per-token rate
and lower cost or equivalent quality must not be assumed in advance.

The opt-in `photo-cost-v8` uses exactly the existing fixed shapes and the entirely
generated fictional wound. It runs four normal authenticated native multipart
photo uploads in disposable SQLite, with the real provider gateway, billing
meter and unchanged privacy enforcement. Only reasoning configuration is varied
inside this private child; provider responses and authentication are not mocked
in a live run. The public app's model and effort settings are never changed.

Shapes must request retake and yield no findings. The synthetic wound must have
unconfirmed qualitative tissue, edges and periwound descriptions, no percentage
text/keys and no unsupported field names. These are contract and coverage gates,
not proof of clinical accuracy or equivalent clinical judgment. Two images per
profile are an initial comparison, not a clinical validation study.

Outputs contain fixed labels and bounded numeric cost, latency, input/output and
cached-token counters only. No image, clinical wording, owner credentials or key
can be forwarded by the launcher. Cost is the sum of actual provider-reported
cost for every image operation in that upload, including recovery attempts.
Missing cost remains UNKNOWN; normalized app credits are never substituted for
an invoice. Cached tokens are recorded because cache reuse can affect costs.
No saving claim or automatic switch may be made from unknown costs or failed
coverage. No public setting is selected automatically even if all gates pass.

The existing original hard expiry and US$1 total cap remain unchanged. There must
be at least five minutes left before any key check/claim for this comparison,
and each upload checks remaining time again. A new separate durable claim blocks
repeat runs without resetting previous claims. No real patient input, production
traffic, billing enablement or clinical-data enablement is involved.

At preparation time the original one-day approval ends October 28, 2026 at
20:50 Toronto (Unix 1793235000), before the stored key expires. There is no time
to complete CI and run this comparison inside that window. No comparison opt-in,
key renewal, cap increase, expiry extension or paid comparison has been made.
Further live tests need renewed time authorization, while retaining the same
total spending cap, and a valid bounded key. Do not claim measured savings yet.

Local checks: 59 passed, including synthetic HTTP, effort isolation/restoration,
identical inputs, real-cost-versus-unknown handling, redacted numeric-only logs,
missing field coverage, percentage rejection, and expiry blocking. All provider
responses in these unit/integration tests are intercepted. CI results follow.

## CI and current permission state

Commit `d1591dcda9adec7cf0f39b0c94dd887e2aceab19` passed full CI
`40796674025`: 984 backend tests passed / 1 skipped, Android build/unit/lint/flag
checks passed, and deployment safety tests passed. Google Cloud authentication,
CLI setup and deployment stayed skipped. No live comparison has been executed.
The opt-in has not been set and no comparison claim has been reset or consumed.

After the original approval expired, the public config returned HTTP 200 with
photo/audio and synthetic testing disabled, clinical data still disallowed,
expiry 1793235000 and the original US$1 cap unchanged. This is the intended time
pause, not evidence that a photo request failed. Renewed time authorization and
a valid bounded key are required before further paid tests. Prepared outputs
are not measured savings and must not be presented as such.
