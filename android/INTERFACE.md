# WoundCoPilot Android · Interface 1.1

The interface starts with the clinician's next action. Capture, Review and Documents stay in the same order; a contextual action changes as the encounter gains sources, unconfirmed findings and documents.

## Navigation and task flow

| Screen | Primary action | Details available on demand |
| --- | --- | --- |
| Encounters | Find or open an encounter; create one from the dock | Account and workspace settings |
| Capture | Record, Photo, Write note, Measure | Additional source types, source contents, encounter history |
| Record a visit | Consent, then the record control | Audio import; playback and recovery when an original exists |
| Photo | Take or choose a photo, then check visibility | Optional capture limitations |
| Written note | Enter the note and add it | A counter appears near the input limit |
| Measurements | Length, width and optional depth | Only guidance that affects interpretation remains visible |
| Review | Confirm, edit or reject a finding | Original quote, source attribution and revision history |
| Case review | Ask Kosmo to review the encounter | Deterministic checks, questions and returned gaps/conflicts |
| Documents | Open a saved version or create a document | Previous working-text recovery |
| Document editor | Write/refine, save the exact text, then explicitly sign | Template configuration, amendment details and export |

The patient and wound remain in the header throughout clinical work. Horizontal dragging moves between the three main sections; the dock provides an equally available tap target. Focused entry screens sit outside that pager, preserving their form interactions. Wide layouts add persistent source context.

The next-action panel follows actual encounter state. It does not predict user behavior or move navigation destinations. Kosmo remains the single AI identity and calls the existing backend. The app shows selected effort only when the backend provides it.

## Visual and interaction system

Graphite, mint and warm white form the main palette. Light appearance uses warm paper and deep green. Serif section titles establish hierarchy; findings and documents use the same readable sans serif typography as the input controls. Coral is reserved for the prominent recorder control.

`ui/InstrumentUI.kt` provides original vector icons, capture tiles, the recorder control, layered panels, disclosures and the navigation dock. Panel depth comes from tonal layers, gradients, subtle edge highlights and shadows. Clinical text stays opaque and legible. Background blur, sensor-driven parallax and grain overlays are not part of this implementation.

Press feedback uses short scale/depth changes. Navigation follows the user's drag and settles into place. Disclosures expand and rotate their chevrons. Haptics use Android feedback events and respect the device's haptic preference. Compose animation follows the system duration scale. The recording waveform is driven by recorded amplitude; there is no simulated AI progress.

Controls expose roles and labels. Main icon targets are at least 48 dp, primary buttons at least 52 dp, and dock destinations at least 60 dp. Disclosures announce whether they are expanded. Findings retain explicit unconfirmed/rejected states and a labelled confirmation mark. Source details remain available after the visual simplification.

## Implementation references

The main-section drag and settling behavior uses [Android's Compose pager APIs](https://developer.android.com/develop/ui/compose/layouts/pager). Custom press states use [Compose interaction handling](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions). Tactile feedback follows [Android's haptic feedback guidance](https://developer.android.com/develop/ui/views/haptics/haptic-feedback).

The requested design references informed the exploration. The shipped choices prioritize recognizable actions, persistent patient context, progressive disclosure and purposeful motion. No trend article establishes that this is the world's best interface; usability with clinicians and physical-device evaluation remain the appropriate way to assess it.

## Verification boundaries

The app retains the existing account, capture, source, correction, review, exact-save, signing, amendment and sharing functionality. Native instrumentation uses synthetic fixtures and an isolated instance of the unchanged backend with providers disabled. It does not validate funded AI inference or physical microphone, camera and haptic behavior. Distribution signing is separate from the installable developer build.
