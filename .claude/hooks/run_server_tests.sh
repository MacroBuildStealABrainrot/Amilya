#!/usr/bin/env bash
# After a server code change, run the server tests. Silent when they pass; prints only failures.
input=$(cat)
file=$(printf '%s' "$input" | python3 -c 'import sys,json;print((json.load(sys.stdin).get("tool_input") or {}).get("file_path",""))' 2>/dev/null)
case "$file" in
  */backend/server/*.py|*/backend/tests/*.py) ;;
  *) exit 0 ;;
esac
root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
cd "$root/backend" || exit 0
python3 -c "import fastapi, sqlalchemy" 2>/dev/null || exit 0   # tools not installed here: skip quietly
out=$(timeout 240 python3 -m pytest -x -q 2>&1); code=$?
if [ $code -ne 0 ]; then
  printf 'Server tests FAILED after editing %s:\n%s\n' "$file" "$(printf '%s' "$out" | tail -25)" >&2
  exit 2
fi
exit 0
