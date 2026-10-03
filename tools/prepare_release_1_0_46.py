#!/usr/bin/env python3
"""Reconstruct OwnerGuard 1.0.46 production from the validated 1.0.45 hotfix lineage."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"

def run(*args):
    subprocess.run(list(args), cwd=ROOT, check=True)

def main() -> int:
    run(sys.executable, str(TOOLS / "prepare_release_1_0_45.py"))
    build = ROOT / "app" / "build.gradle"
    text = build.read_text(encoding="utf-8")
    text = text.replace("versionCode 10045", "versionCode 10046", 1)
    text = text.replace("versionName '1.0.45'", "versionName '1.0.46'", 1)
    build.write_text(text, encoding="utf-8")
    run(sys.executable, str(TOOLS / "release_contract_1_0_46.py"))
    print("OwnerGuard 1.0.46 deterministic preparation: PASS")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
