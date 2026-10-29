# Measured photo reasoning comparison — 29 October 2026

## Result

The once-only `photo-cost-v8` completed on the dedicated Render test server.
All four normal authenticated photo uploads passed their contract/coverage checks.
The same approved OpenAI GPT-6.1 Sol model was used for every metered attempt;
the lower-effort candidate was not credited with a backup model's output.

| Reasoning | Artificial image | Gate | Fields | Actual USD | Server upload seconds | Input tokens | Output tokens | Cached tokens |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| high | shapes | PASS | 0 | $0.012356 | 15.816 | 4651 | 73 | 0 |
| high | wound | PASS | 4 | $0.010252 | 15.478 | 6399 | 489 | 4431 |
| medium | shapes | PASS | 0 | $0.011916 | 8.331 | 4651 | 29 | 0 |
| medium | wound | PASS | 4 | $0.008072 | 9.816 | 6399 | 271 | 4431 |

Total actual reported provider cost across all four uploads: **US$0.042596**.
For this single artificial wound, medium cost US$0.002180 less than high
(21.264% less) and completed in 9.816 versus 15.478 seconds.
These are measurements for these particular inputs, not an average or a price
guarantee for future photos. Cost includes every recorded image operation for
the upload, including any recovery; it is provider-reported money, not app credits.

Both wound runs returned all four allowed qualitative observation sections,
including tissue, edges and periwound. The checks reject percentages, unknown
finding types, empty descriptions and confirmed-by-AI findings. Both shapes
runs returned no findings and requested a retake. This is schema/coverage evidence,
not an independent clinical accuracy review, equivalence study or production certification.
No real patient photo was used. Both wound runs had the same 4,431 cached input
tokens; both shapes runs had zero cached input tokens. Server-side upload timings
exclude phone camera use, mobile network delay and UI time.

## Provenance and checks

Tested source: `6a6e06bd011ab7d3d684df2b10ca09dfd2ae2e70`.
Full CI: `40803425378`; 1,006 backend tests passed / 1 skipped, Android
build/unit/lint/flag checks passed, deployment safety passed, deployment guard
passed. Google authentication remained skipped; no Google Cloud deployment occurred.
The local available subset passed 592 tests / 1 skipped; 111 focused comparison,
activation, startup and fallback checks also passed. Local source had first
needed refreshing to include the concurrent fallback test change; the refreshed
checks passed.

Render deployment `dep-dbhalspiimcc73e9d2jg` became live at
2026-10-29T02:13:08.377552Z. Its process instance was
`srv-dbgh73o8v5cc73ccufk0-kmkbq`. The launcher emitted the four fixed-label,
numeric-only records at 2026-10-29T02:12:42.604Z, after the child completed.
The child varied only image reasoning effort, using identical image bytes,
prompt, strict qualitative schema and 12,000-token completion ceiling. It used
the real provider gateway, ZDR requirement and billing meter with a disposable
SQLite account; no provider output or authentication was mocked in the live run.
No clinical descriptions, images, credentials or provider body were exported in
these comparison records.

## Test access and cleanup state

Test access had already been renewed with Hazim's approval through November 4,
2026 at 20:20 America/Toronto (Unix 1793841600), before the replacement key's
expiry. The existing overall US$1 spending ceiling was not raised. The expired
original key's recorded US$0.35448594 usage plus the replacement key's US$0.64
total limit cannot exceed US$0.99448594. No credits or subscription were purchased.
The same existing Test14 APK and recorder remain usable; no app reinstall or
recording deletion is part of this comparison.

After completion, browser-observed public config confirmed photo, audio and
document access enabled, expiry 1793841600, clinical data disabled, patient data
disallowed, signup closed and billing disabled. The health page reported
`status=ok` and clinical data disabled.

The durable v8 claim remains in place and must never be reset or deleted.
It prevents this completed comparison from running paid requests again on a
restart, even while the v8 opt-in is retained. An attempted opt-in cleanup was
stopped *before mutation* because the active branch had advanced to
`7def9660639bc9782d7a5391f82579dd6b19c90f`, a separate change to nursing-note
model routing. Updating Render env variables would also deploy that new head.
This audit must not cause an unrelated report-model deployment or overwrite
parallel work. Clearing the already-claimed opt-in can be combined with the next
separately validated test deployment; it is not permission to repeat this test.

This audit is stored on its own branch to avoid superseding the active branch's
in-progress deployment guard. The comparison did not change the public app's
photo model or reasoning setting. No production traffic, clinical-data permission,
billing setting, database deletion, APK signing or Samsung certification changed.
A wider assessment of artificial photos and independent review of the descriptions
is needed before recommending a switch based on quality.
