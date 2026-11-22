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
