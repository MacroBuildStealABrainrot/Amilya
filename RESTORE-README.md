# WoundCoPilot / Amilya source restore — 1.8.5-test1

This archive contains the full native Android phone and Wear OS projects, the FastAPI/React backend, migrations, prompts, documentation, test sources, synthetic test assets, the exact tested change patch, source hashes and matching signed test APKs. It is source needed to rebuild the application from a fresh machine; it is not a copy of a configured cloud account or database.

## Projects

- `android/`: Kotlin/Compose phone app, Wear OS companion, shared Wear protocol, Gradle wrapper, resources and Android tests.
- `backend/`: FastAPI service, React/TypeScript frontend, prompts, database migrations, deployment scripts and synthetic test suite.
- `release-artifacts/`: the phone/watch APKs compiled for 1.8.5-test1, delivery metadata and the pipeline-recovery report.
- `SOURCE-MANIFEST.json` and `SHA256SUMS.txt`: package inventory and file integrity checks.

## Restore the Android projects from source

Install Android Studio with Android SDK Platform 36 and Build Tools 36. Install JDK 21 (the project targets Java 17 bytecode). Gradle 9.5.0 downloads through the included wrapper on its first run. Open `android/` in Android Studio and allow Gradle sync, or from PowerShell run:

```powershell
cd android
.\gradlew.bat :app:assembleDebug :wear:assembleDebug
```

The phone debug APK appears at `android/app/build/outputs/apk/debug/app-debug.apk`; the watch debug APK appears at `android/wear/build/outputs/apk/debug/wear-debug.apk`. For JVM protocol tests run `./gradlew.bat :wear-contract:testDebugUnitTest`. Connected Android instrumentation tests need an authorized API 36 or newer Android phone/emulator; run `./gradlew.bat :app:connectedDebugAndroidTest`.

A debug build uses the normal development settings. Screen capture is permitted in the debug build. The signed 1.8.5-test1 APKs are included for convenience. A release can be compiled from source, but reproducing a release signature requires the original owner keystore and password. Those private signing materials are intentionally not included.

## Restore the backend/web app from source

Requires Python 3.11+, Node.js 22+, and network access to fetch dependencies. For a local synthetic development instance on Windows, run:

```powershell
cd backend
.\Start-Local.ps1
```

This creates a local virtual environment, generates a development-only secret and `.env`, builds the web frontend, runs database migrations and starts the service on `http://127.0.0.1:8766`. AI, clinical-data handling and billing stay disabled in this local setup. Runtime database files and `.env` are generated locally and ignored by Git.

Manual backend setup and test commands are in `backend/README.md`. The frontend source and `package-lock.json` are included; run `npm ci` then `npm run build` in `backend/frontend`. Provider tests use intercepted synthetic requests and do not require provider keys.

## Live service and credentials

Android production API calls target the existing WoundCoPilot Cloud Run URL encoded in the Android source. Rebuilding the app does not restore that cloud project, account, database, provider balance, API keys, domain, or deployment secrets. The photo assessment and source-only recovery in this source archive include backend changes that were tested locally but were not deployed to the hosted service. A future backend deployment must use operator-provided access and fresh environment configuration. Never place provider keys or database passwords in the Android app or frontend source.

## Privacy and omitted files

The archive deliberately excludes local `.env` files, Android `local.properties`, signing keystores/passwords, provider credentials, local databases and WAL files, runtime logs, virtual environments, `node_modules`, Gradle/Android build caches and machine-specific IDE state. Android audio test fixtures are synthetic and are included because reliability tests use them. Development demonstrations and tests should continue to use synthetic records only.

No production data or deployed cloud runtime is bundled. Hashes in `SHA256SUMS.txt` let you verify each included source file and APK after extraction.

## Controlled recovery tests

The archive includes `test-tools/controlled_pipeline_api.py`. Run it with the backend Python environment on port 18766, then use `adb reverse tcp:18766 tcp:18766` on an isolated emulator and the WearBridgeTest instrumentation class. This harness uses synthetic local responses. Do not point it at production data or use its test endpoints as a production service.
