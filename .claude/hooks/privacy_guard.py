#!/usr/bin/env python3
"""Blocks a file save if it contains keys or patient-identifier-like data. Plain checks only; stays quiet when clean."""
import json, re, sys

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

inp = data.get("tool_input", {}) or {}
path = inp.get("file_path", "") or ""
text = inp.get("content") or inp.get("new_string") or ""

# Test fixtures and this guard itself legitimately hold fake patterns.
if "/tests/" in path or "/fixtures/" in path or path.endswith("privacy_guard.py") or path.endswith(".md"):
    sys.exit(0)

checks = [
    (r"sk-ant-[A-Za-z0-9_\-]{20,}", "an Anthropic key"),
    (r"sk-or-[A-Za-z0-9_\-]{20,}", "an OpenRouter key"),
    (r"AKIA[0-9A-Z]{16}", "an AWS key"),
    (r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", "a private key"),
    (r"\bghp_[A-Za-z0-9]{30,}", "a GitHub token"),
    (r"\b\d{4}[- ]\d{3}[- ]\d{3}[- ]?[A-Z]{2}\b", "something shaped like an Ontario health card number"),
    (r"\b\d{3}[- ]\d{3}[- ]\d{3}\b", "something shaped like a Canadian SIN"),
]
hits = [label for pat, label in checks if re.search(pat, text)]
if hits:
    print("Blocked: this change contains " + ", ".join(hits) +
          ". Amilya must never hold keys or patient identifiers. Remove it, or use a clearly fake value in a tests folder.",
          file=sys.stderr)
    sys.exit(2)
sys.exit(0)
