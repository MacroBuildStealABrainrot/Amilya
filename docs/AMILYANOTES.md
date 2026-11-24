# Amilya Notes

Separate Android reporting companion, package `com.woundcopilot.android.notes`. It installs beside Amilya, with separate local storage and the existing live API. Signing in to the same account uses the same server workspace; this is not a separate clinical database.

Home has New report, Saved reports and End-of-shift summary. New reports use the existing community nursing format: concise clinician facts, no inferred diagnoses. Voice capture, typed notes, editing, saved versions, voice corrections and sharing reuse the existing workflow. The summary copies selected reports exactly, preserves warnings and uses the phone's local date. Account/PIN controls and recording recovery remain available.

If report writing stops, **Review my notes** lets the clinician review their typed words or a completed transcript and edit a draft on the phone. **Saved reports → Drafts on this device** can reopen available local work for the same account. An unfinished recording without a transcript stays in recording recovery. **Save report** saves the reviewed text to the workspace when connected.

Photo assessment, file upload, consultant, diagnostics, order creation, advanced format selection and voice navigation are not available in Notes. Route guards prevent opening those screens through restored navigation. Existing reports with other formats can be viewed unchanged; editing requires the full app. Bulk server deletion is unavailable in Notes because the same account can have work in the full app.

The Notes build is non-debug, screenshots are blocked, remote watch control is off, and private signing follows the existing installer workflow. Public CI uploads evidence only; installers are produced in the private builds repository.

## Verification

- CI assembles Notes, runs unit tests and lint, and checks its package, live address, reporting-only flag and screenshot setting.
- NotesPolicyTest exercises report/recovery navigation and rejects unrelated routes and report-generation formats.
- Existing shift-summary tests cover exact wording, dates, warnings and missing reports.
- Test only with made-up cases. This build does not change server permissions or clinical-data approval.
- Samsung sign-in, dictation, editing, saving, reopen, sharing and end-of-shift selection remain untested until run on the phone.
