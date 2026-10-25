# Encrypted backup and recovery operations

The synthetic pilot has a verified database backup outside the VM and retained data disk. A daily systemd job uses the existing PostgreSQL container to make a custom-format, gzip-compressed export, encrypts it with OpenSSL CMS AES-256-GCM using a separate RSA-3072 public key with OAEP/SHA-256, and uploads only ciphertext. OpenSSL supports recipient-certificate encryption and private-key decryption through its [CMS command](https://docs.openssl.org/3.5/man1/openssl-cms/). This does not sign the backup as an identified publisher; the authenticated encryption detects ciphertext modification, while scoped Cloud IAM controls object creation.

## Actual storage and schedule

- Bucket: `gs://woundcopilot-20260905-db-backups`, Standard storage in `us-central1`.
- Uniform bucket-level access and public access prevention are enabled. Object versioning and soft-delete retention are disabled. There is no lifecycle deletion rule.
- `wcp-db` has Object Creator and Object Viewer on this bucket only. It can list/read its backups and create new objects; no overwrite/delete role is granted.
- `wcp-backup.timer` runs daily at 08:00 UTC plus a random delay up to 15 minutes. COS startup reinstalls the timer after PostgreSQL is ready. First service execution returned success.
- The job refuses an incomplete listing, 30 or more objects, or a projected total at or above 100,000,000 bytes. It uses a single-process lock and `ifGenerationMatch=0` to prevent overwrites. It never deletes an existing cloud object.
- The staging write has a file-size limit, and a dump at or above 95 MB is refused. Only two fixed temporary staging files are used, on the already allocated encrypted data disk. Normal success/failure removes them; a killed process can leave a file until the next run or operator cleanup.

**At the cap, backups stop.** Existing backups remain recoverable, but the recovery point ages until an operator responds. The pilot does not send an external alert; review `systemctl status wcp-backup.service` and its journal, and assign monitoring/retention ownership before clinical use. Retention decisions and cloud-object deletion require a separate owner decision. The caps bound this bucket's storage; they cannot establish that the account-wide Free Tier is unused.

## Recovery materials — paths only

These files are outside the application/release, under the task's restricted local directory:

```text
C:\Users\stoph\Documents\Codex\2026-09-05\you\work\private\cloud-db\backup-private.pem
C:\Users\stoph\Documents\Codex\2026-09-05\you\work\private\cloud-db\backup-key-password.txt
C:\Users\stoph\Documents\Codex\2026-09-05\you\work\private\cloud-db\backup-public.crt
```

Copy the encrypted private key and its password file together to secure owner-controlled recovery storage, preferably separate from the application host and this computer. Keep the certificate with them. The private key and password were never uploaded to the VM, application, Secret Manager or Cloud Storage. Losing these recovery materials makes the encrypted backup unrecoverable. Do not rotate or discard the old key while backups encrypted for it are retained. This backup key is separate from the database TLS CA key.

The backup contains database schema/data, including sensitive application records. It excludes PostgreSQL role passwords/privileges, application environment secrets, TLS private keys, VM configuration and container images. Retain protected application configuration and reviewed deployment sources as separate recovery inputs. Restore uses `--no-owner --no-privileges`; recreate the owner/runtime roles and reapply append-only grants before permitting runtime access.

## Verified restore and repeatable procedure

The first object is `woundcopilot-20260905T063426Z-636daed9-b746-4e46-b810-0083c362dd78.dump.cms`, 76,906 bytes. Its SHA256 is `cf5d8e3f5976375c90c04d76b5b8cc382edb908a86174aa4df7bdb02f67eea7c`. Downloaded bytes matched that hash. Local authenticated decryption succeeded; a modified ciphertext was rejected. A fresh isolated PostgreSQL restore verified revision `0003`, 26 public tables, exact signed/amended document hashes, the original signature, amendment relationship and all 12 audit events through the stored workspace head. The test database and VM plaintext staging dump were removed afterwards.

1. Use an authorized operator identity to download a selected encrypted object into a restricted local directory. Verify its object metadata and recorded digest where available.
2. Decrypt locally with OpenSSL. Supply recovery secrets through files, never inline values. Use a private output directory; OpenSSL may write partial plaintext before reporting an authentication failure, so only use the output after a zero exit status and remove failed output.

   ```powershell
   openssl cms -decrypt -binary -inform DER -in selected.dump.cms -recip backup-public.crt -inkey backup-private.pem -passin file:backup-key-password.txt -out recovered.dump
   ```

3. Restore into a newly created isolated database with the reviewed PostgreSQL image using `pg_restore --exit-on-error`. Never restore over the active database during a drill. `deployment/restore_off_vm.py` is the executed COS-native probe; its fixed target must not already exist and its input is restricted to the dedicated private staging directory.
4. Verify migration revision, required records, exact text/signature hashes, amendment relationships and audit-chain integrity. Restore checks must be adapted to actual approved recovery fixtures; the included identifiers refer only to synthetic test data.
5. Reapply runtime role permissions using `deployment/harden_runtime.py`, verify TLS and negative access, then test application workflows against the restored target before any separately authorized cutover. Preserve the original database until recovery is accepted.

The first restore proves mechanics and preservation of the tested synthetic workflow. A daily export gives an approximate one-day recovery point when the job is healthy; downtime, size caps and operator response can make it older. There is no point-in-time recovery or automatic failover. Define recovery objectives, key custody, retention, cap alerts and a sustainable capacity plan before patient records are admitted.
