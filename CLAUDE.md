# Amilya – house rules for Claude

Owner: Hazim (RPN). He is not a programmer. Use plain everyday words, no jargon. Claude does the development, testing, repairs and installer prep itself. Only involve him for sign-ins, approvals that are truly needed, or physical Samsung phone testing.

## Never break these
- The app must never collect, store or send patient-identifying information (name, date of birth, health card number, address). Sessions are just "New case".
- Synthetic test data only. No real patient data anywhere in this repository.
- No keys, passwords, tokens or signing files in the repository.
- AI models: Claude Haiku and Sonnet only unless Hazim says otherwise in writing. Check his latest instruction before changing any model.
- Reports must reflect the encounter accurately. The AI's own inferred judgments ("cannot be excluded") stay in Ask Consultant only, never in saved charts/reports unless Hazim adopts them.
- Do not claim compliance (PHIPA/HIPAA) in the app or marketing.

## How we work
- Since Nov 19, 2026: changes go on the live app; no more test-server testing. Reaching the live Google Cloud server still needs Hazim's sign-in.
- Do not change billing, account settings or the release application ID without his approval.
- Never lose a recording or report before saving/processing is confirmed.
- Samsung phone behaviour is "not tested" until Hazim has run it on his phone. Keep phone results separate from computer results.
- Every change report names the branch, commit and build number, and gives evidence (test output), not reassurance.
- Report only failures, meaningful changes and decisions he must make.
- When he asks for a change, give Claude's own opinion. Recommend improvements proactively.

## Layout
- `android/` Kotlin app (Gradle). `backend/` Python FastAPI server (tests in `backend/tests`, run `python -m pytest -q` from `backend/`).
- `ci/` monitoring and repair scripts. `.github/workflows/` builds. `private-installer/` installer is built in a separate private repository.

## Amilya Voice follow-up

For Voice transcription recovery, start with [PR 31](https://github.com/MacroBuildStealABrainrot/Amilya/pull/31). Check the current PR, release and live-service state before making changes. This is the separate Android `lite` build, package `com.woundcopilot.android.lite`. Its main flow is Record → Stop → editable transcript → Copy or Share. Preserve all recognized voices. Numbered speaker labels are optional. Keep clinical reports and review comments out of this flow.

The 1.9.5 app could display an HTTP 409 with the phrase “already processing or awaiting reconciliation” and a direction to refresh a case. Voice has no case screen. This response is ambiguous. Inspect the original job and section. Preserve its audio, ownership, visit, UUID and completed sections across retries and in-place updates. Use saved evidence or bounded status checks. Never clear a reservation or start a replacement job just to clear the warning.

- Android entry points: `LiteVoice.kt`, `VoiceTranscriptionRecovery.kt`, `AudioChunks.kt` and `LocalRetention.kt` in `android/app/src/main/java/com/woundcopilot/android/`.
- Server entry points: `backend/server/audio_parts.py` and `backend/server/providers.py`. Check which revision serves traffic before relying on the new section-status route or typed response header. Existing ambiguous upstream cancellations need authorized reconciliation.
- Reproduce with synthetic audio. Run `:app:testLiteUnitTest`, the Voice emulator tests and `pytest -q tests/test_audio_parts.py tests/test_transcription_recovery.py` from `backend/`. Verify delayed completion, a persistent pending job with playback, a 1.9.5 in-place upgrade, and locked-screen recording. [Android build checks](https://github.com/MacroBuildStealABrainrot/Amilya/actions/runs/46054108643) · [Emulator tests](https://github.com/MacroBuildStealABrainrot/Amilya/actions/runs/46054108727).
- Deliver the private signed Voice APK. Report source SHA, build number, tests and whether the live backend and physical Samsung were verified. Follow [the live rollout procedure](PRODUCTION-REPAIR-READINESS.md) for any production change. Use existing authorized access for routine work and involve the owner only when access, rollout approval, or a physical device check requires it.
