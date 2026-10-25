#!/bin/bash
# Reinstalled at every COS boot; private recovery key is never installed on the VM.
set -euo pipefail
test -s /mnt/stateful_partition/wcp/backup-public.crt
cat > /etc/systemd/system/wcp-backup.service <<'UNIT'
[Unit]
Description=Encrypted bounded WoundCoPilot backup to private Cloud Storage
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
ExecStart=/bin/bash /mnt/stateful_partition/wcp/backup-to-gcs.sh
TimeoutStartSec=600
Nice=15
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/run/wcp /mnt/stateful_partition/wcp/data/.backup-staging
UNIT
mkdir -p /mnt/stateful_partition/wcp/data/.backup-staging
chmod 700 /mnt/stateful_partition/wcp/data/.backup-staging
cat > /etc/systemd/system/wcp-backup.timer <<'UNIT'
[Unit]
Description=Daily encrypted WoundCoPilot backup, capped without automatic deletion

[Timer]
OnCalendar=*-*-* 08:00:00 UTC
RandomizedDelaySec=15min
Persistent=true

[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now wcp-backup.timer
