#!/bin/sh
# REAL full-disk test: mounts a 1 MB RAM disk (needs root, Linux), runs the logic tests with the disk-full test switched on, unmounts.
# Usage: sh run_storage_full_check.sh /path/to/gradle-home
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"; M="$(mktemp -d /var/tmp/wcp-full-XXXXXX)"
cleanup() { umount "$M" 2>/dev/null || true; rmdir "$M" 2>/dev/null || true; }
trap cleanup EXIT
mount -t tmpfs -o size=1m tmpfs "$M"
FULL_DIR="$M" sh "$HERE/run_kotlin_logic_tests.sh" "$1" 2>&1 | tee /tmp/wcp-full-all.log | grep -E "disk full|recovered|^OK|Failures|Tests run|FAIL|Exception" || true
