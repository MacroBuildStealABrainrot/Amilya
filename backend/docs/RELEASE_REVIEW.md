# Frontend release review — 2026-09-05

Final frontend validation of WoundCoPilot 6.0.0 after closing explicit custom-report, photo-quality and short-audio-verification workflow requirements. Source changes were authorized after the initial independent review. No dependencies were added. Browser, cloud, provider and payment validation belong to the integration review and are not represented as checks performed here.

## Repeated validation

| Check | Actual result |
| --- | --- |
| `npm run lint` | Passed, exit 0. |
| `npm run test` | 30 tests passed across 7 files; no failures. The custom-workflow suite was repeated after the final formatting-normalization adjustment (7/7 passed). |
| `npm run build` | Strict TypeScript and Vite 7.3.6 production build passed. |
| `npm audit --json` | 0 reported vulnerabilities, including development dependencies; 297 dependencies in audit metadata. This is the current advisory result, not a guarantee that vulnerabilities cannot exist. |
| `npm ls --all --json` | Exit 0, no dependency-tree problems. |
| Prompt manifest | All 16 files in `server/prompts/manifest.json`, version `2026-09-05.2`, match SHA-256 over UTF-8 content normalized to LF. Every current source file already uses LF. No unlisted prompt files were present. |

Final production assets are `index-BBmdVr8s.js` (316.40 kB; 97.61 kB gzip) and `index-q_TRhmtu.css` (34.73 kB; 7.48 kB gzip).

The tests also cover custom-format versioning/normalization, photo-review fallback, capture-quality signals, one-hour/size guards, actual signal sampling, paid-candidate reopening, late audio acceptance, and acknowledged-recording expiry. They exercise encounter isolation, fact correction, confirmed-only drafting, actual Return-key paragraph preservation through save/sign, document navigation, a no-audio workflow and amendment, demo-to-account transition, late-generation preservation, encrypted recovery ownership/concurrency/expiry/incomplete chunks, and selected accessible controls/landmarks. The DOM accessibility runner excludes contrast. The separate 21-pair static contrast calculation and its boundaries are documented in `DESIGN.md`; it is not a browser rendering or full conformance result.

## Interface and documentation reconciliation

The principal advertised workflow has implementation behind it: independent evidence entry; Confirm/Edit/Reject with revisions; deterministic review and confirmed-finding drafting; exact textarea save; saved-version signing and explicit amendments; Clients; pricing and billing adapters; contact storage; legal acceptance; settings; and an administrator inbox/cost view. Custom report name/instructions persist with immutable versions; Physician / NP update is named explicitly. Photo quality signals and clinician obstruction/limitation review are recorded as nonclinical metadata. Timestamped audio verification keeps original/candidate text separate, offers source playback during comparison, preserves the original transcript, and resumes saved proposals without another provider call. Demo fixtures are labeled synthetic and isolated in memory. Personal-file upload and microphone capture are explicitly disabled in the public demo. No TODO, “coming soon” control, fabricated clinical outcome or compliance certification was identified in the reviewed interface.

Current service activation remains a release condition: optional AI/media processing and contextual assistance require enabled server capabilities. The UI consumes the server's reason, so “configured but paused” must remain accurate in `/config`. Manual entry, writing, saving and signing remain usable. Real clinical data and live commercial billing are not represented here as activated or approved.

These limitations should remain explicit when describing the release:

- **Recovery is narrower than a complete offline application.** Authenticated document drafts and recording chunks have encrypted device recovery. Unsaved evidence-form text, selected photo/document files and arbitrary API mutations are not a durable offline queue. API responses are excluded from the service-worker cache. Cold-start account access still needs a valid server session and available encounter data; shell installation alone does not guarantee offline clinical access.
- **Device encryption has a defined boundary.** A non-exportable browser CryptoKey is stored in IndexedDB. Account scoping prevents normal application cross-account recovery; this does not provide an operating-system key vault or protection from compromised same-origin code/browser profiles. Acknowledged recordings are linked to the exact uploaded source and remain encrypted for excerpt verification; they are not shown as pending transcription. Draft expiry is seven days and recording expiry 24 hours, enforced when that account signs in or reviews recovery, rather than a continuously running background cleanup service.
- **Audio behavior still needs real-device acceptance.** MediaRecorder, consent, pause/resume, duration/size guards, encrypted chunks and retry paths exist. Browser/OS background suspension, microphone interruptions, long sessions and installed-mode behavior were not tested by this frontend review. The waveform now displays measured WebAudio signal amplitude and falls back to a plain indicator if unavailable. The recorder allows 60 minutes with requested 32 kbps encoding, subject to the earlier 25 MB size boundary.
- **History is encounter-oriented.** Clients exposes prior visits and each encounter exposes document/fact revisions. The design's reference to longitudinal history should not be interpreted as implemented wound-trend charts, serial-image comparison or a dedicated cross-visit wound timeline. Document export here is exact-text Copy; there is no advertised PDF export action.
- **Minor usability follow-ups are not release claims.** Saving a profile does not immediately refresh the enclosing session's displayed name; it refreshes on the next session load. The billing portal button reports server availability on invocation, while checkout has a capability notice. “Clients & wounds” groups visits by client rather than exposing a separate wound-management screen.

No additional critical frontend blocker was identified in this final static pass. This finding supports the already gated synthetic release; it does not replace integration/browser testing, clinical validation, legal review or activation decisions.

## Integrated visual correction

The release browser review found and corrected a dark-mode canvas defect that the static colour-pair audit could not detect. `:root` now resolves its background through `--canvas`; actual dark rendering and contrast were inspected. Desktop Privacy and Terms controls now also meet the measured 44-pixel width. The CSS-only correction passed the strict production build and real-browser recheck; no application logic or dependency changed. See VALIDATION.md for the complete integration record.
