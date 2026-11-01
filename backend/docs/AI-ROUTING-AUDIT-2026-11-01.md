# AI routing audit — 2026-11-01

## Outcome

The backend is ready for a controlled model refresh without changing the Android client or the installed app's data handling.

- Aurora: Luna for extraction, Terra for custom/full drafts, owner-approved Haiku for standard notes, and Sol for independent report QA.
- Nebula: keep owner-approved Claude Sonnet 5.5 for wound-photo assessment.
- Galaxia: keep Sol for review, Claude Sonnet 5.5 for an independent challenge, and Astra only for high-risk escalation.
- Kosmo: keep deterministic application routing. It is not a generative model.

This is a code and configuration update, not proof of clinical performance. Production routing must remain gated until the synthetic suite and a clinician-reviewed de-identified comparison set pass.

## Changes from the October 25 audit

1. Added `openai/gpt-6.1-terra` to the approved price and model-identity registry.
2. Split report drafting from report QA. Terra may draft a custom/full report, but Sol independently checks it.
3. Preserved the owner's October 28 blind-review choices: Haiku for standard note types and Claude Sonnet 5.5 for wound photos.
4. Removed the stale, unassigned Muse model from the executable allowlist.
5. Pinned OpenRouter requests to first-party provider slugs by default:
   - OpenAI-family models: `openai`
   - Anthropic-family models: `anthropic`
   - unlisted model families: fail closed until an approved provider order is configured
6. Disabled provider fallback outside the configured order.
7. Updated `.env.example`, model prices, exact snapshot aliases, tests, and the model registry.

## Why Terra is limited to drafting

OpenRouter lists GPT-6.1 Terra at $1/M input and $5/M output, with JSON-schema structured outputs and a 1,050,000-token context window. It is materially cheaper than Sol at $2/M input and $10/M output.

Those are vendor/catalog facts, not wound-care evidence. No independent wound-specific clinical validation was found for the exact Terra version. For that reason:

- Terra does not replace the photo model.
- Terra does not approve its own report.
- Terra does not replace Sol for review.
- Terra does not replace Astra for high-risk escalation.

## Provider and privacy boundary

OpenRouter's current default router can choose among multiple hosts based on price, speed, throughput, and reliability. ZDR and `data_collection: deny` remain mandatory, but they do not by themselves decide which legal entity hosts the model.

The request policy now uses `provider.order` and `allow_fallbacks: false` so a clinical request cannot silently move to Azure, Bedrock, Vertex, or another host that has not been reviewed. Adding any provider requires a separate review of contract terms, retention, subprocessors, location, breach handling, and the Ontario custodian's obligations.

This improves technical control but does not by itself establish PHIPA compliance. Operational approval, agreements, privacy assessment, access controls, incident handling, and clinician oversight are still required.

## Evidence separation

### Vendor and catalog claims

- Terra model, pricing, release date, context, parameters and providers:
  https://openrouter.ai/openai/gpt-6.1-terra-20261026
- Sol model, pricing, context, parameters and providers:
  https://openrouter.ai/openai/gpt-6.1-sol
- Luna model, pricing, context and parameters:
  https://openrouter.ai/openai/gpt-6-luna
- Astra catalog:
  https://openrouter.ai/openai/gpt-6-astra
- OpenRouter provider routing behavior:
  https://openrouter.ai/blog/announcements/how-we-rebuilt-provider-routing/
  https://openrouter.ai/docs/features/provider-routing

### Independent or local evidence

- General third-party benchmarks on OpenRouter are not clinical wound-care validation.
- No independent evidence located in this review establishes safe autonomous wound diagnosis for these exact versions.
- The Haiku note and Claude Sonnet photo assignments are local owner preference from the October 28 blind review, not a clinical effectiveness study.
- The application must continue to label AI output as draft/support, reject unsupported quantitative photo findings, and require nurse review.

## Release checks

Before enabling this branch on a test service:

1. Run the complete backend suite with no real provider keys.
2. Confirm report-draft requests select Terra and report-QA requests select Sol.
3. Confirm every OpenAI/Anthropic request contains ZDR, data-collection denial, parameter enforcement, an explicit provider order, and disabled outside fallback.
4. Run de-identified side-by-side reports reviewed by a qualified nurse.
5. Verify that failures are visible and do not silently retry through an unapproved provider.
6. Do not use identifiable patient information until privacy approval and provider agreements are complete.

