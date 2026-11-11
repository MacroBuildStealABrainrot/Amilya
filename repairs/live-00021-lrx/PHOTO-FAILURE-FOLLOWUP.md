# Photo provider failure follow-up — 27 October 2026

Status: diagnostic/error-handling correction, not a confirmed end-to-end photo fix. Not deployed.

Hazim reported recording works but Android photo assessment fails. Screenshot: HTTP 502, provider HTTP 200, request bf565b68-83af-466a-b931-fdca28cd45f6.

OpenRouter's signed-in UI showed a 09:51 AM GPT-5.6 Luna / Azure upstream request, status 200, one attempt, 1.2 seconds, generation gen-1793109074-zFK8dUxiwzVSyqjSus0M. The generation lookup reported no generation found. This is consistent with the screenshot timing but cannot be joined conclusively to its request UUID without the application log. The original error body is unavailable. Do not claim an overload, content-policy failure, or image-format failure has been established.

Cloud Run console and standalone Cloud Shell both returned Site Unavailable in this session. No local gcloud credentials or CLI were available. PostHog's connected default project has no ingested events. The repair health endpoint returned 200, version 6.0.0, clinical_data_enabled false; health is not a successful photo assessment.

## Correction
OpenRouter documents typed errors at error.metadata.error_type and, for partial completions, choices[].error. Existing code discarded that category and only inspected top-level errors. The new parser preserves only a closed vocabulary of documented technical categories, rejects both error shapes, and never saves partial findings. Unknown category labels become unknown. Provider messages, raw/native metadata and generated content are neither returned nor logged.

A null error field no longer falsely rejects an otherwise valid completion. Error envelopes are not automatically retried. The existing APK receives an application-owned explanation and a safe X-WCP-Provider-Error header; HTTP200 error envelopes are marked non-retryable. Failed operations stay charge-waived, with unknown vendor cost kept NULL.

The canonical root repair file now includes the previously tested date exclusion, report floors and validation diagnostics already present in Amilya/repairs/live-00021-lrx/providers.py, avoiding two different repair files under similar paths. Both repair copies are identical.

## Validation
159 focused synthetic tests pass: 91 existing provider tests and 68 new source/live-patch error-envelope cases. No external AI calls or real wound images were used. These cases demonstrate correct classification, rejection and privacy behavior; they do not prove the provider-side cause is fixed.

## Deployment limits
No normal traffic change, test deployment, clinical-data activation, billing change, database deletion, signing change, APK rebuild or data deletion was performed by this follow-up.

Use the existing immutable live image digest in the repair Dockerfile for any later zero-traffic deployment. Test environment changes must be explicit on the tagged revision, followed by restoration and verification of the service template. A live test using synthetic data and the safe category is still required before a root-cause repair can be selected. The current APK cannot receive a server-side code correction until the tagged server is updated.

Reference: https://openrouter.ai/docs/api_reference/errors-and-debugging

