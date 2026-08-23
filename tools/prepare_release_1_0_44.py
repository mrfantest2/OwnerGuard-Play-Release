#!/usr/bin/env python3
"""Reconstruct the canonical OwnerGuard Android lineage through Play release 1.0.44."""
from __future__ import annotations

from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"

STACK = (
    "apply_samsung_compat.py",
    "apply_cloud_client_compat.py",
    "apply_forced_update_gate.py",
    "apply_android_drawer_ui.py",
    "apply_local_first_drawer_shell.py",
    "apply_debug_recording_drawer_fix.py",
    "apply_mandatory_requirements_gate.py",
    "apply_cloud_console_crash_fix.py",
    "apply_native_cloud_workspace.py",
    "apply_vertical_cloud_control_center.py",
    "apply_credential_event_evidence.py",
    "apply_mandatory_owner_face_enrollment.py",
    "apply_release_1_0_39.py",
    "apply_cloud_automatic_sync.py",
    "apply_release_1_0_40.py",
    "apply_simple_automatic_vault.py",
    "apply_release_1_0_41.py",
    "apply_recovery_export_cellular_upload.py",
    "apply_release_1_0_42.py",
    "apply_upload_pipeline_repair.py",
    "apply_release_1_0_43.py",
    "apply_release_1_0_44.py",
    "fix_android16_lint.py",
    "fix_play_local_only_face_enrollment.py",
    "fix_local_auth_compile_surface.py",
)


def main() -> int:
    for name in STACK:
        script = TOOLS / name
        if not script.is_file():
            raise SystemExit(f"required release transformation missing: tools/{name}")
        print(f"==> {name}")
        subprocess.run([sys.executable, str(script)], cwd=ROOT, check=True)

    subprocess.run([sys.executable, str(TOOLS / "release_contract_1_0_44.py")], cwd=ROOT, check=True)
    print("OwnerGuard 1.0.44 deterministic preparation: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
