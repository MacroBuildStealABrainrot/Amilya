# Narrow repair for live revision woundcopilot-00021-lrx

Prepared, not deployed. This directory is an isolated repair artifact; it does not replace the broader development branch runtime.

## Verified source

The owner supplied the provider file extracted from Cloud Build 5bc7affc-a02b-4936-a206-319a787dbbea, matched by image digest to the active revision. Its SHA256 is d2ec7ac83872485f3f1a9b24dd60fb4dd85d1d5ef0e345171375d96d8718ceb7. The original attachment is preserved. Patched providers.py SHA256 is 2af17315f9a5dadd05b73ff0b14de93360567de6ede02c18d016ddd03355c1fd. Applying providers.py.patch with GNU patch --binary reproduced that exact hash.

## Changes

- Increase extraction and document response capacity from the low-effort 1,800-token minimum to 8,000, scaling long text up to a bounded 16,000. Image analysis receives an 8,000-token minimum. Existing larger requested limits remain intact.
- Preserve the actual upstream transport status when HTTP 200 contains a provider error envelope, and expose that numeric status in the diagnostic response header. Error envelopes remain failures.
- Preserve existing routing, low reasoning settings, privacy controls, response validation and failed-request charge waivers. Longer responses can increase provider token expenditure; no price settings or balances are changed.

## Evidence and limits

OpenRouter's 02:50 report-associated request ended at exactly 1,800 completion tokens with finish reason length. The verified provider sets that cap and rejects truncated responses. The synthetic regression reproduces that rejection before the patch and successful complete output after it. This supports the truncation defect; an app request-ID-to-provider-generation mapping has not been recovered.

Photo root cause remains unconfirmed. The 03:12 HTTP 200 / stop / 125-token generation is not conclusively mapped to the photo request. Streaming is explicitly false in this exact live source; the earlier streaming theory is unsupported. Larger output capacity does not establish a photo fix.

## Validation

96 focused provider tests passed, including the new synthetic output-budget cases, rejection of still-truncated responses, waived charges and HTTP-200 error-envelope diagnostics. The original focused baseline had 5 failures and 86 passes; the new budget tests had 4 failures and 1 pass before repair.

A broader comparison uses an archived backend with matching core.py and kosmo.py, but not every archive file is proven identical to production. Both unmodified and repaired broader runs show failures; they must not be described as a clean production regression suite. Both runs stalled near 66% and were interrupted after approximately seven minutes. Neither completed; no full-suite success or complete failure-delta claim is made. The earlier development-branch CI results apply to that different candidate, not this narrow artifact.

## Release gate

The Dockerfile is a preparation template pinned to the deployed image digest. Before building, verify the parent actually contains /app/server/providers.py with the original SHA256 above. That path is inferred from the archive Dockerfile and has not been inspected in the live container. Do not build, deploy, or route production traffic without the owner's explicit approval and a reviewed verification/rollback plan. Do not deploy the whole broader development branch as this narrow repair.

The Work browser currently cannot reach Google Cloud Console, Cloud Shell or Cloud Storage. No production changes have been made. No new Android APK is needed for a server-only change; physical Samsung synthetic report/photo checks are required after an approved rollout. Existing APK signing incompatibility is a separate unresolved delivery gate.
