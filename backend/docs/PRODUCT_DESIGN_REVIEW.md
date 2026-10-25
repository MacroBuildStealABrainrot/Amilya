# WoundCoPilot interface redesign

Date: 5 September 2026. Scope: the existing full-stack application and its Google Cloud deployment.

## Design decision

The interface should make the current patient, unresolved findings and next documentation action easy to locate. The previous screen repeated headings, gave six source-entry tiles too much space, put confirmed observations before the review queue, and used a decorative wound illustration as persistent context. Its visual treatment did too little to distinguish primary work from supporting information.

Three directions were implemented as temporary CSS variants and rendered against the real demonstration encounter:

| Direction | What worked | Decision |
| --- | --- | --- |
| Paper record | Warm surface, serif patient name, light navigation, fewer containers | Calm, but the navigation and clinical work blended together too much. Rejected as the main direction. |
| Compact review rail | Narrow navigation, aligned field and value columns, more rows visible | Good scanning structure, but lost source context and made navigation labels too small. Kept the field/value alignment for wide screens. |
| Clinical workbench | Dark navigation, prominent patient identity, white working surface, actual source index | Selected and refined across light, dark, desktop, tablet and phone layouts. |

This comparison is an internal design assessment, not a clinician usability study or an objective certification of exceptional design.

## Research applied

[Linear's account of its interface redesign](https://linear.app/now/how-we-redesigned-the-linear-ui) describes aligning navigation, headers and panels, increasing useful density and evaluating hierarchy across different views. The application here uses those principles to give the patient header, task tabs and review area a consistent structure. It does not copy Linear's interface.

[Carbon's data-table guidance](https://carbondesignsystem.com/components/data-table/usage/) informed the aligned visit and plan comparisons and their nearby search/filter controls. These views preserve the actual record identifiers, prices and billing behavior.

[Nielsen Norman Group's visual-design principles](https://www.nngroup.com/articles/principles-visual-design/) informed the use of scale, contrast and grouping: patient identity is prominent, pending review carries the emphasis within the record, and provenance stays legible at a lower level of hierarchy.

## Implemented changes

- Self-hosted IBM Plex Sans, four subset WOFF2 weights, with its SIL Open Font License included. No external font service is needed at runtime. Numeric values align consistently; document text retains a distinct, readable serif treatment.
- Full patient identity, wound location, actual record reference, last-update date and status replace the repeated notebook title.
- Unconfirmed findings appear first. Wide screens align field labels, values, source details and review controls. Status remains explicit in text and symbols.
- Source entry becomes a compact toolbar. On phones it scrolls horizontally; the controls retain full accessible names and touch targets.
- Actual source excerpts, review progress and saved-document counts replace the decorative inspector content. Empty records correctly say “No findings.”
- The visit list supports combined status and case-insensitive reference/name search, sorted by last update. Opening a row retains its actual encounter ID.
- Plans appear in aligned comparison rows, with direct descriptions and clear monthly prices. Speculative promotional headings and internal cost-registry language were removed from customer-facing copy.
- Inputs, dialogs, review panels, account controls and both themes use the same typography, spacing and surface rules. Patient and source text remain selectable.
- Existing brief press feedback, tab transitions, modal entry, touch-aware task swiping and reduced-motion preferences remain in place. There is no looping decorative motion.

## Validation

- 39 frontend tests passed across nine files. Existing exact-text saving/signing, late-generation preservation, clinical correction, custom reports, photo review and transcript verification tests remain intact. Four new tests cover visit ordering/opening, combined filters, empty review status and pending-first presentation.
- ESLint, strict TypeScript and production bundling passed.
- Rendered browser review covered findings, evidence entry, documentation, visits, plans, settings, and the assessment dialog; responsive inspection included phone, tablet and desktop widths.
- At 390 × 844, measured visible controls on the findings view met 44 × 44 CSS pixels, and the document did not overflow horizontally. Source-toolbar overflow is deliberate and local to that toolbar.
- Fifteen representative text/background pairs were measured in each theme. The minimum sampled ratio was 5.22:1 in light and 6.88:1 in dark. This is a targeted text-contrast check, not a full WCAG conformance claim.
- Automated accessibility checks cover controls and landmarks in the encounter and assessment dialog. Physical touchscreen acceptance and clinician usability research are not claimed.
- Known private values were checked against the release tree; none were found in the initial scan. Deployment verification repeats asset and secret checks.

## Release boundary

This changes presentation, copy and local view filtering. The backend, clinical confirmation rules, saved-text integrity, provider configuration and account credit grant are preserved. AI remains enabled. No provider inference request or provider-credit purchase is required for this redesign. Clinical-data and live-payment gates remain separate from this interface release.

The deployment report supplied with the release records the exact source commit, Cloud Run revision, image digest and public asset verification.
