# Amilya Pilot test12 — validation and release limits

Runtime/APK source: `04cb191fca646c822171a5437fe2a687a5afef89`.
Development branch: `fix/server-diagnosis-20261025`.
Draft review: https://github.com/MacroBuildStealABrainrot/Amilya/pull/1
Successful CI: https://github.com/MacroBuildStealABrainrot/Amilya/actions/runs/40289058142

## Test results

| Check | Result |
| --- | --- |
| Local backend suite | 752 passed, 1 PostgreSQL-only skip, 1 dependency deprecation warning |
| Focused output-limit/provider/photo/routing/diagnostic suite | 171 passed |
| CI backend with SQLite and PostgreSQL | 760 passed, 1 SQLite-specific skip, 1 dependency warning |
| Android Sandbox app unit tests | 151 passed, 0 failures, 0 skips |
| Wear contract unit tests | 39 passed, 0 failures, 0 skips |
| App Sandbox/Pilot/debug/release builds | Passed |
| Sandbox physical-device instrumentation compilation | Passed; not executed on a device |
| Wear Sandbox/release builds | Passed |
| Sandbox/Pilot lint | Passed; 0 errors, 14 warnings per variant |
| Built package and original-recorder flags | Passed |
| Real isolated storage-full regression | Passed |
| Emulator | None used |
| Live synthetic text/photo/report acceptance | Not run; production unchanged |
| Clinical/privacy acceptance | Not approved by these tests |

The existing [26-item reconstructed checklist](CHECKLIST-26-STATUS-test11.md) retains **16 completed, 9 partially completed and 1 unverified/deferred**. The new tests strengthen output-limit, diagnostic and routing coverage; they do not close physical-device or clinical/privacy items. The original verbatim checklist is still unavailable.

## APK identity and installation limitation

- Filename: `Amilya-Pilot-1.8.5-test12.apk`
- Application ID: `com.woundcopilot.android.pilot`
- Version code: 32; version name: `1.8.5-test12-pilot`
- Size: 32,073,559 bytes
- SHA-256: `48eb3bc4875055f998bf60c2733b7002df634a86139a850e46da7bcd254bb09d`
- Original recorder enabled; crash-safe recorder remains disabled in Pilot.
- Existing production HTTPS server URL retained. The APK does not deploy the backend fix.

Public v2 signer-certificate fingerprints were read from both APKs:

| Build | SHA-256 of signer certificate |
| --- | --- |
| Previously supplied test11 | `3755c21d718013d178737b36cc23170faa6dbba68ff3331fccb38f3c788a111e` |
| New CI test12 | `35b496d500b4a51db52ab8b66e96b39c4377d6cd3eb1ae382d87e30ae43bf562` |

These certificates differ. CI inherits the ephemeral debug signing configuration and has no configured key rotation. **This APK is not an in-place upgrade for the supplied test11 Pilot.** Do not uninstall test11, clear app data or delete recordings to get past an installation rejection. Stable signing with the existing key, or an explicitly reviewed separate-package/data-preservation plan, is required. The installed phone package/certificate was not inspected. A first install of the Pilot package is distinct from updating an existing Pilot.

Android's update requirements: https://developer.android.com/google/play/app-updates

## Samsung acceptance checklist

After a compatible installation and approved server change, use synthetic data only:

1. Confirm the installed version and that existing recordings and saved work remain accessible.
2. Paste a made-up nurse/client conversation. Confirm extraction completes, the report is a generated draft rather than an unchanged transcript, and checking finishes with its actual status.
3. Test a synthetic/non-patient image. Confirm supported processing or a clear image-quality message, with a request reference on failure.
4. Record a short made-up visit, then test screen lock and an incoming-call interruption. Verify saved audio, transcription and recovery without deleting originals.
5. Verify Deep Review's independent challenger and requested/reported model metadata; check source fidelity and medication corrections manually.
6. Verify retry behavior and safe recovery after a network interruption. Review warnings before using any draft.

## Required before production

- Working authorized Cloud Run/Cloud Logging access to identify the deployed revision and unresolved photo/report-check failures.
- Explicit owner approval for the exact backend revision, configuration and migration/deployment plan. No production deployment has occurred.
- Stable Pilot signing before updating an installed Pilot while retaining its data.
- Synthetic live acceptance and physical Samsung validation.
- Separate clinical/privacy approval before identifiable patient data or clinical use.

Higher output ceilings increase the worst-case token reservation/cost; no price registry, live balance or entitlement was changed. Provider account availability under the retained privacy filters still requires approved live synthetic acceptance.

The following documentation-only commit also corrects one code comment from “source” to “input” when describing OpenRouter's prompt-token count. Runtime behavior and APK source remain those tested above.
