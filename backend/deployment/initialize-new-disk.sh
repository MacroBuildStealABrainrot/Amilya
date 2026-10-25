#!/bin/bash
# One-time operator action for the positively identified NEW wcp-data disk only.
set -euo pipefail
DISK=/dev/disk/by-id/google-wcp-data
test "$(readlink -f "$DISK")" = /dev/sdb
test "$(blockdev --getsize64 "$DISK")" = 21474836480
SIGNATURES=$(wipefs -n "$DISK")
test -z "$SIGNATURES"
test -z "$(lsblk -n -o MOUNTPOINT "$DISK")"
mkfs.ext4 -F -m 0 "$DISK"
mkdir -p /mnt/stateful_partition/wcp/data
mount -o nodev,nosuid "$DISK" /mnt/stateful_partition/wcp/data
df -h /mnt/stateful_partition/wcp/data
