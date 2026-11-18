# End-of-shift summary (offline, no AI)

Approved base: `e4a6bcf47e30b78ab5132c9558329f58bb35d731` on `test/isolated-safety`.
Work branch: `feature/end-of-shift-summary`. Do not merge without Hazim's approval.

## Screen

Saved work → End-of-shift summary. Today's finished reports are ticked by default.
Untick any visit to leave it out. The screen shows the combined text and a Copy button.
There is no send, email, upload, AI request or billing action. Send all is unchanged.
The clipboard item is marked sensitive to suppress previews on supported Android versions.

“Finished” means the latest saved report is signed using the existing Review and sign action.
Saved draft-only visits and visits with no report appear under No finished report.
An older signed version is not substituted for a newer saved draft.
The visit's creation time, interpreted in the phone's current time zone, determines its day.
Old records without creation time use the first local remembered start or their existing timestamp.
Reports are ordered by visit start then visit ID. Each has its own heading/reference, separator,
saved warnings and safety alerts. Report text is appended without trimming or rewriting.
The summary does not claim to have run an AI check. Existing source-only/unchecked notices remain.

## Local data and limits

Existing save, sign, open and refresh responses populate an owner-scoped encrypted local copy.
The summary itself adds no network requests. Its screen does not use runTask or diagnostics.
Only locally available visits can be enumerated after an offline restart. Visits known from the
normal work list but whose reports have not been loaded are explicitly listed as unavailable;
they are not called report-free. Open these visits normally while online to make a local copy.
No report can be fetched from the server by this feature, and it cannot discover unknown work
created on another device. Unsaved editor text is never treated as a finished report.

The latest report only is cached for each visit, with that report's own doc-notice/doc-alerts.
If older warning history cannot be confirmed, a permanent warning appears within that section.
Existing cached warnings are retained if an immutable saved report's separate warning record
cannot be read. A changed visit revision requires reloading its case before inclusion.
Copies are in the existing encrypted, non-backed-up vault. Yesterday's copies are removed when
this owner's summary is opened; the existing seven-day draft cleanup is a fallback when unused.
Deleting/discarding a visit also removes its summary copy. Binding a local visit to its server ID
removes the local placeholder. No cloud/server retention, request journal or billing rule changes.

## Verification

Pure helper tests cover 0, 1 and 3 visits, exact whitespace/Unicode, local-date boundaries and
daylight saving, ordering, tick choices, no-report/draft-only lists, latest versions, unavailable
reports, source-only and omission warnings, similar wording in different visits and safe errors.
Cache tests cover simulated reopen, account separation, revision changes, cleanup/deletion,
local-to-remote binding, large text and corrupted data. They use fake data and in-memory storage.

These tests do not press screen buttons, exercise the actual Android Keystore, prove clipboard
behaviour, or measure scrolling with large documents. Real screen compilation and all existing
tests must pass in public CI before delivery. This branch is outside automatic push triggers,
so manually run the existing amilya-sandbox-ci workflow with this branch selected.
No workflow change or private installer is needed.

Samsung: **not tested**. Airplane-mode restart, midnight/time-zone changes, checkboxes, warning
placement, Copy and large-document performance must be tested on the phone after a later,
separately approved private installer. No live server, secrets, signing or settings changes.
