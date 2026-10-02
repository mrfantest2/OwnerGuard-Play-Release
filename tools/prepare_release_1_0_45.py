#!/usr/bin/env python3
"""Reconstruct OwnerGuard 1.0.45 from the canonical 1.0.44 release lineage."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
PATCH = ROOT / "patches" / "release_1_0_45_face_enrollment.patch"

def run(*args):
    subprocess.run(list(args), cwd=ROOT, check=True)

def main() -> int:
    run(sys.executable, str(TOOLS / "prepare_release_1_0_44.py"))
    if not PATCH.is_file():
        raise SystemExit("required 1.0.45 face enrollment patch is missing")
    run("git", "apply", "--whitespace=nowarn", str(PATCH))
    run(sys.executable, str(TOOLS / "release_contract_1_0_45.py"))
    print("OwnerGuard 1.0.45 deterministic preparation: PASS")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
