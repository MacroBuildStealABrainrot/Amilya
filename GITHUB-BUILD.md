# Build and test Amilya on GitHub (private), step by step

Why: the review workspace cannot download the Android SDK, so it cannot build the app. GitHub's build machines can. The workflow
`.github/workflows/amilya-sandbox-ci.yml` builds every app variant, runs all automatic tests, and runs the sandbox app's phone
tests on an Android emulator. It uses **no secrets, no signing keys, no AI keys**, deploys nothing, and never contacts your Cloud Run
service, database or billing. Only fake data is used.

## One-time setup (about 10 minutes)

1. Use your existing **private** repository: `https://github.com/MacroBuildStealABrainrot/Amilya` (do not create a new repository).
2. Upload this whole folder (the one that contains `.github`, `android`, `backend`, ...):
   * easiest: GitHub Desktop > File > Add local repository > choose this folder > "Publish repository" (keep "Private" ticked); or
   * command line, in this folder:
     ```
     git init -b main
     git add .
     git commit -m "Amilya 1.8.5-test10 candidate"
     git remote add origin https://github.com/MacroBuildStealABrainrot/Amilya.git
     git push -u origin main
     ```
   The folder contains no passwords or keys (checked: `.env.example` and `sandbox/level3.env.example` hold placeholders only).
   Do **not** add your real `level3.env`, any keystore (`.jks`/`.keystore`) or any real recording.
3. The push starts the workflow. You can also start it by hand: **Actions** tab > `amilya-sandbox-ci` > **Run workflow**.

Alternatively: connect GitHub in claude.ai settings (Connectors) and tell me the private repository name. I can then push the
package and read the results myself. I would still not deploy anything.

## What to look at (about 40 to 70 minutes after the push)

Actions tab > the latest run. Three jobs:

| Job | Proves | Must be green before the Samsung test? |
|---|---|---|
| Backend tests (SQLite + PostgreSQL) | 709 server tests, including stuck-credit recovery on real PostgreSQL | Yes |
| Android build + unit tests + build-flag checks | The real app builds (sandbox, debug, release, watch); unit tests pass; release and debug keep the original recorder | **Yes** |
| Sandbox app on an Android emulator | Crash-resistant recorder, existing-work compatibility, sign-in/offline token, recording start, offline-to-report pipeline | Yes, groups `recorder`, `level1`, `level2` (see `summary.txt`). The group `all-other-tests` is information only |

Downloads at the bottom of the run page ("Artifacts"):

* `amilya-apks`: `app-sandbox.apk` (the one to install for the Samsung test), plus debug, unsigned release and watch apps, with `SHA256SUMS.txt`.
* `amilya-build-evidence`: `gradle-build.log`, `build-flags.log`, unit-test and lint reports.
* `amilya-backend-evidence`: `backend-pytest.log`.
* `amilya-phone-test-evidence-api35`: `summary.txt`, one report folder per test group, `logcat.txt`.

If something fails, send me the failing job's log (or the artifacts). Build problems that appear only on a real build machine
(Gradle plugin, dependency or Compose compile errors) are exactly what this step is for.

## Notes

* The release app built here is **unsigned** and is not meant to be installed. Your signing key stays on your PC.
* The emulator is not a Samsung: it has no Samsung battery manager, no real microphone sound and no watch. The Samsung
  checklist (`FINAL-SAMSUNG-CHECKLIST.md`) is still required.
* GitHub's free plan includes build minutes for private repositories; one full run uses roughly 60 to 120 minutes of them.

## Test10 CI safety clarification
The `ci/run-phone-tests.sh` in this upload-ready archive fails the required emulator job if a test server does not start or if required instrumentation classes run zero tests. A green result requires real executed tests, not merely a completed Gradle command.
