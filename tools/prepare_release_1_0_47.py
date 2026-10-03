#!/usr/bin/env python3
"""Reconstruct OwnerGuard 1.0.47 production from validated 1.0.46 production source."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"

def run(*args):
    subprocess.run(list(args), cwd=ROOT, check=True)

def main() -> int:
    run(sys.executable, str(TOOLS / "prepare_release_1_0_46.py"))
    build = ROOT / "app" / "build.gradle"
    text = build.read_text(encoding="utf-8")
    before = text
    text = text.replace("versionCode 10046", "versionCode 10047", 1)
    text = text.replace("versionName '1.0.46'", "versionName '1.0.47'", 1)
    if text == before:
        raise SystemExit("1.0.47 version bump did not modify build.gradle")
    if "versionCode 10047" not in text or "versionName '1.0.47'" not in text:
        raise SystemExit("1.0.47 version bump validation failed")
    build.write_text(text, encoding="utf-8")
    print("OwnerGuard 1.0.47 version-only production preparation: PASS")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
