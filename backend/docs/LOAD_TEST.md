# Reproducible synthetic concurrency probe

Run from the repository root with the project Python environment:

```powershell
& '..\.venv\Scripts\python.exe' scripts/load_probe.py --output work/load-probe.json
```

The default probe runs 1, 10, 50 and 100 concurrent virtual users. Each user has a separate synthetic workspace, patient and wound in a temporary SQLite database. Each level starts a new database using WAL and a 30-second busy timeout. No existing records, configuration file, provider account or billing balance is used.

Each virtual user creates an encounter, adds a clinician-attested measurement, reads the case, obtains a mocked AI review, generates a deterministic note, saves its exact text, signs the returned text hash and case revision, then reads the case again. The probe verifies exact draft-to-signed text and signed status. Eight HTTP handler requests constitute each completed workflow. AI review is explicitly replaced with a synthetic response and a configurable 20 ms delay; all other inference functions reject unexpected calls. Async outbound HTTP is prohibited. The result checks that no usage operations or credit charges occurred.

The JSON report contains request p50, p95 and maximum latency, elapsed time, throughput, error status counts, completed workflows, signed document counts, mocked provider calls, and charged micro-units. A nonzero exit code identifies HTTP or invariant failures. Error logs exclude clinical payloads. The script prints one result per completed concurrency level so progress is visible.

For repeated workflows or a different simulated provider delay:

```powershell
& '..\.venv\Scripts\python.exe' scripts/load_probe.py --levels 1,10,50,100 --iterations 3 --mock-delay-ms 100 --output work/load-probe-repeat.json
```

These results describe local application handlers and SQLite write contention on the machine recorded in the report. They include FastAPI TestClient overhead and use authentication dependency overrides; password hashing, real session middleware, TCP, TLS, reverse proxies, browser rendering, file transfer, genuine model latency, billing reservation contention and PostgreSQL behavior are outside this probe. Each user submits sequential requests in a simultaneous closed-loop batch. The results establish neither production user capacity nor clinical model quality. Use an isolated staging PostgreSQL deployment and actual authorized provider budgets for a separate production capacity assessment.

The isolated databases are removed after the run; the explicit JSON output is retained. Only synthetic source text is generated. Unit and integration tests in `tests/test_providers.py` separately exercise the real billing ledger and encrypted inference replay using mocked HTTP responses.

## Recorded local run

The run at **2026-09-05 05:43:33 UTC** used Python 3.11.9 on Windows with 12 logical CPUs, one workflow per user, and a 20 ms mocked provider delay. The retained result is `work/load-probe.json`.

| Concurrent users | Completed workflows | HTTP requests | Errors | Request p50 | Request p95 | Total elapsed |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 8 | 0 | 9.58 ms | 41.93 ms | 0.136 s |
| 10 | 10 | 80 | 0 | 7.74 ms | 228.51 ms | 0.781 s |
| 50 | 50 | 400 | 0 | 241.07 ms | 834.32 ms | 3.538 s |
| 100 | 100 | 800 | 0 | 569.06 ms | 1,521.07 ms | 6.608 s |

All 161 workflows ended with a verified signed document. All levels reported zero live provider calls, zero usage operations and zero charges. Request latency rose with concurrent SQLite access; the single 100-user request maximum was 6,364.48 ms. This observed batch result has the scope limitations described above and is not a production service-level commitment.
