#!/bin/bash
# Reinstalled by the VM startup script because COS /etc configuration is ephemeral.
set -euo pipefail
cat > /etc/systemd/system/wcp-maintenance.service <<'UNIT'
[Unit]
Description=Expire transient WoundCoPilot replay, session and rate records
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
ExecStart=/bin/bash /mnt/stateful_partition/wcp/maintenance.sh
TimeoutStartSec=45
Nice=10
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
UNIT
cat > /etc/systemd/system/wcp-maintenance.timer <<'UNIT'
[Unit]
Description=Hourly transient WoundCoPilot record cleanup

[Timer]
OnBootSec=10min
OnUnitActiveSec=1h
RandomizedDelaySec=5min
Persistent=true

[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now wcp-maintenance.timer
