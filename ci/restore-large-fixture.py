#!/usr/bin/env python3
"""Restore the large synthetic Android test fixture from Git-stored binary parts.

The original file is a generated synthetic fixture (not patient data). GitHub's
connector transferred it in verified chunks. This script recreates exactly the
original M4A before Android compilation or instrumentation tests.
"""
from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "ci" / "large-fixtures"
DEST = ROOT / "android" / "app" / "src" / "androidTest" / "assets" / "speech-1500.m4a"
EXPECTED_SHA256 = "709b5bd48b2a1fffc9b527c5ae7f4d9f992e629c90183726385d7836cf4ab091"
PARTS = [SOURCE / f"speech-1500.m4a.part{i:02d}" for i in range(8)]

def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(block)
    return sha.hexdigest()

def main():
    if DEST.exists() and digest(DEST) == EXPECTED_SHA256:
        print("Synthetic fixture already restored and verified.")
        return
    missing = [p.name for p in PARTS if not p.is_file()]
    if missing:
        raise RuntimeError("Missing synthetic audio fixture parts: " + ", ".join(missing))
    DEST.parent.mkdir(parents=True, exist_ok=True)
    temporary = DEST.with_name(DEST.name + ".partial")
    try:
        with temporary.open("wb") as output:
            for part in PARTS:
                with part.open("rb") as source:
                    for block in iter(lambda: source.read(1024 * 1024), b""):
                        output.write(block)
        if digest(temporary) != EXPECTED_SHA256:
            raise RuntimeError("Restored audio checksum mismatch; refusing to use fixture.")
        temporary.replace(DEST)
    finally:
        temporary.unlink(missing_ok=True)
    print("Restored and verified synthetic 1500-second audio fixture.")

if __name__ == "__main__":
    main()
