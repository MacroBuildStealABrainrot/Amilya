#!/bin/sh
# REAL full-disk test: mounts an isolated 1 MB RAM disk (needs root, Linux), runs the pure-logic tests, then unmounts.
# Usage: sh run_storage_full_check.sh /path/to/gradle-home
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
M="$(mktemp -d /var/tmp/wcp-full-XXXXXX)"
LOG="$(mktemp /tmp/wcp-full-XXXXXX.log)"
cleanup() { umount "$M" 2>/dev/null || true; rmdir "$M" 2>/dev/null || true; }
trap cleanup EXIT
mount -t tmpfs -o size=1m tmpfs "$M"
status=0
FULL_DIR="$M" sh "$HERE/run_kotlin_logic_tests.sh" "$1" >"$LOG" 2>&1 || status=$?
grep -E "disk full|recovered|^OK|Failures|Tests run|FAIL|Exception" "$LOG" || true
printf 'Full test log: %s\n' "$LOG"
exit "$status"
