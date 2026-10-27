# Narrow repair for live revision woundcopilot-00021-lrx

Prepared, not deployed. This directory is an isolated repair artifact; it does not replace the broader development branch runtime.

## Verified source

The owner supplied the provider file extracted from Cloud Build 5bc7affc-a02b-4936-a206-319a787dbbea, matched by image digest to the active revision. Its SHA256 is d2ec7ac83872485f3f1a9b24dd60fb4dd85d1d5ef0e345171375d96d8718ceb7. The original attachment is preserved. Patched providers.py SHA256 is e2d181215e86ac7b842cddbecf1d083105bf593e45a173a10743958fee20bbc8. Applying providers.py.patch with GNU patch --binary reproduced that exact hash.

## Changes

- Increase extraction and document response capacity from the low-effort 1,800-token minimum to 8,000, scaling long text up to a bounded 16,000. Image analysis receives an 8,000-token minimum. Existing larger requested limits remain intact.
- Preserve the actual upstream transport status when HTTP 200 contains a provider error envelope, and expose that numeric status in the diagnostic response header. Error envelopes remain failures.
- Preserve existing routing, low reasoning settings, privacy controls, response validation and failed-request charge waivers. Longer responses can increase provider token expenditure; no price settings or balances are changed.

## Evidence and limits

OpenRouter's 02:50 report-associated request ended at exactly 1,800 completion tokens with finish reason length. The verified provider sets that cap and rejects truncated responses. The synthetic regression reproduces that rejection before the patch and successful complete output after it. This supports the truncation defect; an app request-ID-to-provider-generation mapping has not been recovered.

New owner-supplied database evidence at 2026-10-25 21:30:35 UTC identifies task=image, OpenRouter model openai/gpt-5.6-luna, failure_reason=upstream_error, upstream_status=502 and one failed attempt with error_code=502 and normalized_micros=0. This matches the upload request log timestamp. The provider error body and exact infrastructure cause remain unavailable. The 03:12 HTTP 200 / stop / 125-token generation is not conclusively mapped to the photo request. Streaming is explicitly false in this exact live source; the earlier streaming theory is unsupported. Larger output capacity does not establish a photo fix.

## Photo gateway recovery

Image analysis now retries once after an explicit 502/503/504 JSON error without generation ID, choices, usage or quota indicators. It preserves the same model, payload and privacy policy, honors short Retry-After hints, and does not shorten long hints. HTTP-200 error envelopes, ambiguous network failures, non-JSON errors and permanent failures are not automatically retried. The entire image network phase is bounded by the lesser of 60 seconds and the configured provider timeout. The second failure stops and retains failed-request charge waivers. A gateway may have performed work without reporting it; the retry can incur additional upstream cost, even when the failed attempt has no reported usage.

153 focused tests passed across provider, photo-quality, output-budget and new gateway regression cases (14 new cases). A wider selection gave 154 passed / 14 failed; all 14 failures were reproduced before this photo change and concern newer client header expectations absent from the isolated live source. They remain an integration limitation, not a clean full-suite pass.

## Validation

96 focused provider tests passed, including the new synthetic output-budget cases, rejection of still-truncated responses, waived charges and HTTP-200 error-envelope diagnostics. The original focused baseline had 5 failures and 86 passes; the new budget tests had 4 failures and 1 pass before repair.

A broader comparison uses an archived backend with matching core.py and kosmo.py, but not every archive file is proven identical to production. Both unmodified and repaired broader runs show failures; they must not be described as a clean production regression suite. Both runs stalled near 66% and were interrupted after approximately seven minutes. Neither completed; no full-suite success or complete failure-delta claim is made. The earlier development-branch CI results apply to that different candidate, not this narrow artifact.

## Release gate

The Dockerfile is a preparation template pinned to the deployed image digest. Before building, verify the parent actually contains /app/server/providers.py with the original SHA256 above. That path is inferred from the archive Dockerfile and has not been inspected in the live container. Do not build, deploy, or route production traffic without the owner's explicit approval and a reviewed verification/rollback plan. Do not deploy the whole broader development branch as this narrow repair.

The Work browser currently cannot reach Google Cloud Console, Cloud Shell or Cloud Storage. No production changes were made by this repair workflow. Owner screenshots show a separately created revision 00022-yer tagged srv1 at 0% normal traffic, with normal traffic on 00021-lrx. That different candidate returns Invalid host header on its tag URL; it is not verified as this artifact. Production rollout remains blocked on parent-image verification, exact candidate comparison, synthetic end-to-end checks, and owner approval. No new Android APK is needed for a server-only change; physical Samsung synthetic report/photo checks are required after an approved rollout. Existing APK signing incompatibility is a separate unresolved delivery gate.


## Independent review follow-up - October 27

Photo validation remains unresolved. Matching OpenRouter generation gen-1793082491-M0TkxoAR9bb5EYhK4FbF returned HTTP 200, stop/completed, native 2323 input and 216 output tokens. Completion text was not retained. Extra value keys are a hypothesis only.

Added categorical validation warning and X-WCP-Validation response header. Only fixed schema field/type labels are emitted; arbitrary extra key names, values, messages and context are suppressed. Unknown keys appear as x (even identifier-looking names). Ledger retains existing allowed failure_reason invalid_response; the category is available in logs/header, not ledger, avoiding changes to billing sanitization. Clinical validation is unchanged.

Remaining report stages quick_draft, quick_refine, quick_qa, quick_repair and improve_report now have minimum output budget 8000. Focused synthetic suite: 166 passed. This is not a full-suite claim or live photo success.

BLOCKING ROLLOUT: test PUBLIC_BASE_URL and TRUSTED_HOSTS updates also changed the service template. Future deployments inherit these values. Restore intended production environment overrides with a no-traffic revision before promotion; if old revision had no override, remove the new override rather than setting empty. Compare env existence and valueFrom versus literal value safely, without dumping secret contents. Keep tagged test revision on its explicit configuration. No restoration or traffic change has been executed by this follow-up.

Do not reuse source-rebuilt 00022-yer or srv2-sentry-cloudshell.sh. Build from immutable verified original digest with the new providers.py, verify hash and compile, then deploy diagnostic candidate at zero traffic. Use one nonpatient test, read category, and implement only evidence-supported correction. Do not strip arbitrary unknown keys to hide unsupported findings without a specific reviewed contract.

Cloud SQL free-trial-first-project is not the app database; inspect state and contents before requesting explicit deletion approval. No deletion authorized. Health-only rollback does not detect clinical feature failures: gate rollout on separate synthetic transcription, extraction, report and photo checks, and monitor feature errors as well as health. No pricing claim is verified here.

## Confirmed photo rejection and targeted correction
Diagnostic revision log at 2026-10-27T07:17:08.794339Z: VALUE:date_from_datetime_parsing:observed_on. This proves an unparseable observed_on value caused this attempt to fail; it does not prove all historical photo failures have the same cause. Photo schema now omits observed_on, and photo acceptance excludes that unsupported metadata with one fixed warning before typed validation. Valid dates are excluded too: image pixels cannot establish observation date. Other unknown fields, invalid clinical values and all document/text date checks remain strict. 174 focused tests passed. Requires a new zero-traffic deployment and reproduction before claiming live success.
