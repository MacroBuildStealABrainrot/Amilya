# Privacy Policy

Version and proposed effective date: 2026-09-05.

**Draft for professional legal review before commercial launch.** This notice describes the implemented application and proposed operations. Owner details, the deployed regions, retention schedule, subprocessors, contracts and incident process must be completed and checked before real patient information is enabled. This notice does not represent certification or a conclusion that a deployment complies with a particular law.

Operator: {{LEGAL_BUSINESS_NAME}}. Business address: {{LEGAL_BUSINESS_ADDRESS}}. Privacy contact: {{PRIVACY_EMAIL}}. You can also send an account or privacy request through Contact us; do not include patient information in that form.

## Information processed and purposes

We process account email, display name, password hashes, workspace membership, profile preferences, accepted legal versions and session/security records to provide and protect accounts. We process support and business inquiries to review and respond to those requests. Submitting an inquiry does not subscribe you to marketing, and the current application does not send automatic external inquiry emails.

Authorized healthcare users may enter client references, wound records, visit evidence, narratives, measurements, histories and test results. The application stores structured evidence, clinical facts and their revisions, clinician confirmations, reviews, document drafts, exact saved documents, signatures and audit metadata in its relational database. Use patient references that are appropriate for your organization's workflow and minimize identifying details.

Optional recordings, photographs and uploaded documents can be sent to the backend for processing. Raw uploads are not designed as a permanent media archive; server upload parsing and transcription conversion may create temporary files during processing. Derived transcripts, extracted findings, source quotations and clinical records can persist. Do not rely on this service as the sole custodian of an original recording or source document. The operational retention policy and backup lifecycle must be agreed with the healthcare organization.

## AI and service providers

If AI is enabled, relevant clinical inputs are sent to the configured provider for extraction, transcription, image interpretation, documentation or review. Potential routes include OpenRouter and its selected inference providers, Cloudflare, Groq and optionally Deepgram. The route and provider configuration determine which parties process an input. AI outputs can be inaccurate and remain subject to clinician review. We do not intentionally use clinical information to build an advertising profile or train our own general-purpose model. Provider data-use and retention terms must be separately verified; an application flag does not change a provider's contract.

The deployed infrastructure operator, database service and backup arrangements will be recorded in the organization's deployment/subprocessor schedule. Data may be processed outside Canada depending on deployment region and provider routing. Canadian data residency, zero retention and privacy-law compliance are not automatic guarantees of this application. Your organization must approve the actual purposes, vendor agreements and cross-border processing before enabling real patient data.

Stripe handles payment-card collection through its hosted Checkout and customer portal. We store Stripe identifiers, subscription state, payment/credit transactions and cost records, rather than complete card numbers or card security codes. Stripe handles its own payment processing under its applicable notice and terms.

## Browser storage, security and telemetry

The application uses an HttpOnly session cookie and a CSRF token to authenticate requests and protect account mutations. The PWA service worker may cache the application shell; protected API responses must not be put in that cache. Where enabled, browser recovery storage keeps unfinished work associated with the signed-in user and encounter. Recovery behavior and available encryption depend on browser capabilities. Device access controls, browser-profile separation and your organization's shared-device policy still matter; local recovery is not a substitute for a server save or organizational backup.

Access controls, revision history, signed-document integrity checks, rate limits and audit records support security. Cost diagnostics contain resource identifiers and usage/cost counters; they are not intended to contain raw patient narratives, prompts, media or document text. Authorized platform administrators can access operational diagnostics and the contact inbox. Production HTTPS, secret management, database access controls, monitoring and backup protections depend on the actual deployment and must be verified. No security measure eliminates all risk.

## Retention, access, correction and deletion

Clinical retention and deletion follow the healthcare organization's documented instructions and applicable obligations. Signing preserves a document version; clinical corrections use revisions or amendments so the historical record remains intelligible. Deleting an account does not automatically erase records an organization is required or authorized to retain. The current app does not provide an immediate self-service purge of all clinical records, backups or payment records. Contact the operator for account access/export/correction/deletion requests; it may need to verify identity and coordinate with the responsible healthcare organization.

Before commercial launch the owner must publish concrete retention periods for inactive accounts, support inquiries, clinical records, audit records, temporary media and backups, including how holds and backup expiry affect deletion. Until this schedule and the responsible privacy contact are approved, use synthetic data. Do not promise patients a deletion or retention outcome the deployed organization cannot fulfill.

## Responsibility, questions and changes

The healthcare organization is responsible for determining its authority to collect/use/disclose patient information, giving required notices, managing recording/photo consent, and approving this service's role. Acceptance of this website notice is not patient consent and does not replace a custodian/service-provider agreement. We will cooperate with lawful organizational instructions and applicable access, correction and incident obligations through the agreed process.

If a security incident occurs, the operator must assess it promptly and follow the applicable notification and recordkeeping requirements and customer agreements. The incident contact/process must be established before launch. Material changes to purposes, providers or processing require review and, where appropriate, updated notice or consent. We version this notice and record the version accepted during account creation. Contact {{PRIVACY_EMAIL}} or use Contact us for questions without patient details.
