# Identified live source and unresolved retrieval

Evidence supplied by the owner through Cloud Shell screenshots:

- Project: woundcopilot-20260905; service woundcopilot; region us-central1.
- Revision receiving 100% traffic: woundcopilot-00021-lrx.
- Image: us-central1-docker.pkg.dev/woundcopilot-20260905/wcp/app@sha256:1b05b36af5db9f1a53426101faef035d6940f6dabc6897301e4dd7c67fee39e4
- Matching global Cloud Build: 5bc7affc-a02b-4936-a206-319a787dbbea, created 2026-09-09T00:09:03.638831907Z.
- Build source: gs://woundcopilot-20260905-builds/source/1788912541.484414-a7ed5a890e42494eb01d254038d3aa7d.tgz, object generation 1788912543361284.

Exact SHA-256 fingerprints read by the owner from that source archive:

| File | SHA-256 |
| --- | --- |
| server/core.py | b7d99af302928559a7a0eff4d1254a52650bdd2e27c4dfeb796ed101e22e5bef |
| server/kosmo.py | 3405631677a75d6df395d2a91bd27c80c86358f6d1a43a2f8673c87b63bbe3ca |
| server/providers.py | d2ec7ac83872485f3f1a9b24dd60fb4dd85d1d5ef0e345171375d96d8718ceb7 |

The owner extracted providers.py into a uniquely named Python file in Cloud Shell home. Phone download/attachment did not succeed.

## Independent checks completed

GitHub main and all currently available branch-tip provider variants were compared. None matched the live provider fingerprint. Main kosmo.py matched. Main core.py did not match, including LF/CRLF-normalized checks.

The previously shared AI-Maintenance-Ready and test1 FULL-SOURCE-RESTORE archives were retrieved privately and inspected without executing their code. Both contain exact matches for live core.py and kosmo.py. Their providers.py has SHA-256 670d70a101992bd47c83f332deef3176558d32c6612d144c46f4926a4d4416f1 and is not the live provider file.

Reversing the included test1 pipeline-recovery patch on a scratch copy produced provider hash 2821e35e787962a29c43a134d116837bcfc2f5165af8726748362fa8283767e6 (LF normalization af6addb002e654950d3c70663a9e900567e9a7125d910f0ca3d09930a016722c), also not a match. This reconstruction must not be treated as deployed code.

Direct browser attempts to the service Console, standalone shell.cloud.google.com and the identified storage.cloud.google.com source object all returned Site Unavailable / Unable to access this site. The private source object was not downloaded in this Work session. No exposed source URL, access credential or public sharing was created.

The exact live providers.py is still unavailable here. Do not deploy the broad development candidate as a presumed minimal patch against an unverified baseline. Do not treat OpenRouter's streaming metadata as proof of source-level stream configuration, or the 03:12 Terra generation as proven to be the photo request.

## Boundary

No production deployment, billing change, privacy change, source deletion, recording deletion or live paid AI request occurred. A working authorized Cloud execution connection or the matching source file remains necessary for the live-specific repair. Existing development tests/builds remain documented in VALIDATION-test12.md; this investigation made no runtime change and required no repeat of those tests.
