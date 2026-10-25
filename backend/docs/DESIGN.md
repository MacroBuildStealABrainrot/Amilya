# WoundCoPilot 6 â€” Clinical notebook

The clinical task is the visual centre. Patient identity stays above the work. Optional evidence contributes to reviewed facts; it never becomes a mandatory wizard step. The product uses a synthetic interactive encounter before sign-in, not an unrelated marketing page.

## Direction selection

Three concepts were evaluated in the audit: a Clinical notebook with a central editable record and evidence inspector; a Visit capture workspace centred on a source stream; and a Longitudinal wound desk centred on repeated visits. The notebook was selected for balanced typed/manual/audio/photo use, a durable document editor, calmer long sessions and simpler mobile tasks. Longitudinal history remains available through Clients and encounter History. Capture remains an independent source.

## Composition

Desktop uses subdued left navigation, a persistent patient/encounter heading, Findings/Evidence/Case review/Document tabs, and a contextual evidence inspector. The inspector is informational and never contains unique required actions. At tablet widths navigation moves to the bottom; narrower task views expose the same evidence through the Evidence view. Mobile has Home, Clients, Encounter and More, with one full-width task at a time. The desktop sidebar is absent. All common buttons and input controls have a minimum 44px target. The document footer remains reachable and accounts for the mobile keyboard and safe area.

## Tokens and components

Warm neutral canvas `#f7f7f3`; white working surface; subdued neutral navigation `#eeeee7`; pine action colour `#204c41`. Semantic warning, error, and confirmation treatments include words/icons as well as colour. Core radius is 8px; larger 12px radius is reserved for modal layers. Native system typography supports the working application; the document uses a restrained serif face to distinguish document text from controls. No external fonts, glow, gradients, decorative blobs, sparkle marks, or fabricated social proof.

The compact evidence launcher represents six independently actionable inputs, not a generic marketing feature grid. Source badges identify actual fact provenance. Borders separate repeated records or task boundaries; spacing does most grouping. Source inspectors and audit history are expandable. The recording signal uses measured WebAudio time-domain samples while recording, closes its AudioContext on pause/stop/unmount, and updates less frequently for reduced-motion preferences. A plain recording indicator is used when WebAudio is unavailable.

## Interaction invariants

- Each rendered encounter is keyed by workspace/user and encounter. Evidence forms remount cleanly for each encounter. Requests capture an immutable encounter ID; stale responses cannot replace another active record.
- Demo records are held in a separate in-memory repository and labeled synthetic. Real personal media cannot be submitted through the public demo. Demo photo/transcript/document examples are explicit fixtures, not claimed production inference.
- All facts can be reviewed, corrected or rejected, with structured measurements retained as numeric fields and revision history accessible.
- The document editor is a controlled textarea. It preserves paragraph boundaries and exact whitespace. Generation offers a candidate rather than replacing intervening edits. Saved and signed versions are distinct. Amendments require a reason and preserve the signed version.
- Dirty document text belongs to its encounter and survives task navigation. Authenticated device copies are AES-GCM encrypted and scoped by user and encounter. IndexedDB writes wait for transaction completion. Recovery failures retain pending data.
- Disabled provider actions show a specific service reason. Manual entry, writing, save and sign remain available independently of optional assisted processing.
- Native dialog semantics provide focus containment and Escape dismissal. Form labels are associated with their controls. Errors are announced as alerts; status messages use live regions. File inputs remain keyboard accessible. Ctrl/Cmd+S saves the working note; Alt+D opens Document.

## Verification

Frontend tests exercise cross-encounter state isolation, revision correction, confirmed-only drafting, actual Return-key paragraph preservation, navigation recovery, no-audio document signing, encrypted account isolation, concurrent device draft writes, and automated accessibility checks. Automated contrast checks are excluded in the DOM-only runner because it has no layout canvas; visual contrast, resizing, tablet/mobile composition and keyboard overlap require browser QA. Root browser validation targets 1440Ã—900, 1280Ã—800, 820Ã—1180, 430Ã—932 and 390Ã—844.

## Static contrast verification, 2026-09-05

An independent numeric pass calculated sRGB relative luminance and `(lighter + 0.05) / (darker + 0.05)` for 21 explicit CSS foreground/background pairs. This is a static color calculation, not a DOM screenshot result or a claim of full accessibility conformance. Normal text was checked against 4.5:1; form boundaries and focus outlines against 3:1. Disabled-control opacity and image content are outside this selected-pair assessment.

The initial pass found deficient light captions (3.18:1), muted navigation text (4.41:1), pending text (4.03:1), open-status text (3.74:1), and hardcoded light backgrounds that made dark demo/initials/system-active-navigation text 1.22â€“1.86:1. These were corrected using darker light-theme metadata, semantic warning tokens, theme-aware surfaces, and a dedicated form-border token. The transcript source `unattributed_transcript` is displayed as **Speaker not verified**.

| Checked pair after correction | Light ratio | Dark ratio |
| --- | ---: | ---: |
| Body / canvas | 13.31:1 | 14.32:1 |
| Muted text / canvas | 5.16:1 | 7.95:1 |
| Caption / canvas | 4.98:1 | 5.86:1 |
| Muted navigation/demo text | 4.75:1 | 8.14:1 |
| Caption / navigation | 4.59:1 | â€” |
| Primary button label | 9.00:1 | 10.97:1 |
| Pending label / canvas | 4.89:1 | 9.30:1 |
| Open-status label | 4.53:1 | 6.17:1 |
| Initials and active navigation | â€” | 7.28:1 |
| Form boundary / adjacent canvas or surface | 3.14:1 | 4.68:1 |
| Form boundary / white interior | 3.37:1 | â€” |
| Focus outline / canvas | 3.74:1 | 4.26:1 |

All 21 selected post-correction pairs meet their stated thresholds. Browser QA is still required for actual compositing, focus visibility, text enlargement, imagery, and states outside the selected pairs. Final source validation remained clean: ESLint, strict TypeScript/Vite build, and 18 component/recovery/accessibility tests passed at that checkpoint. The explicit workflow additions below have their own subsequent validation.

## Explicit workflow additions

Custom report formatting supports a report name and bounded formatting instructions. Clinical content must still come through confirmed findings. The name and instructions are stored with saved and signed versions, restored through history/amendments, and cleared when selecting a standard template. Physician / NP update is explicitly named.

Photo capture computes resolution, luminance, local-edge variance and clipped-highlight signals. Wording stays conservative: signals cannot certify sharpness, detect every reflection, establish clinical suitability or infer wound findings. Clinicians explicitly record unobstructed/partial/uncertain visibility and may record limitations. When pixel inspection is unavailable, the persisted review is labeled manual; no numeric measurements are fabricated. Crop and orientation preserve source color.

Audio supports up to 60 minutes subject to the earlier 25 MB size limit, using requested 32 kbps encoding. Device encoding can differ. Acknowledged audio stays encrypted under the same 24-hour recovery policy solely to support excerpt verification, linked to the exact server-acknowledged source evidence ID; it is not shown as a pending transcription. Users may discard it explicitly.

Audio evidence exposes a bounded 30-second verification flow. It uses an encrypted retained original or asks for the original file; the server verifies the source hash. The clinician listens to the selected window, compares original and candidate text, and explicitly accepts or corrects a separate transcript source. Existing transcript text is never silently replaced, and new clinical candidates still require confirmation. Idempotency survives a failed retry.

Home and Clients lists are tagged with their account owner. An authentication transition renders a loading state until that owner's lists arrive, so synthetic demo visits cannot appear under an authenticated workspace label.

Final validation after these additions: 30 tests across seven files passed, including source-candidate comparison/resume, late acceptance, custom-format immutable history, manual photo-review fallback and acknowledged-recording expiry. The custom-workflow suite passed again after format metadata normalization. ESLint, strict TypeScript/Vite and the full npm audit pass; the audit reports zero vulnerabilities. Verification proposals can be reopened from Evidence without repeating a provider call, and source listening remains available beside the candidate. Optional medical transcription is disabled unless the server advertises that capability.

## Final browser correction

Actual light/dark browser inspection found a hard-coded light canvas that the static token calculation did not catch. The root background now uses the theme canvas variable. Final public rendering was inspected at1440,1280,820,430 and390pixels, with no measured horizontal overflow or visible button/input/select target below44×44 on the encounter screen. Desktop inline legal controls received a44pixel minimum width. These are observed screen checks, not a full accessibility certification.
