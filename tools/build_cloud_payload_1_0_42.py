#!/usr/bin/env python3
"""Build and validate OwnerGuard Cloud 1.3.27.5 for Android 1.0.42."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
subprocess.run([sys.executable, str(ROOT / "tools" / "build_cloud_payload_1_0_41.py")], check=True)
subprocess.run([sys.executable, str(ROOT / "tools" / "apply_cloud_mobile_users_layout.py")], check=True)

out = ROOT / ".deploy" / "ownerguard_cloud"
ui = (out / "includes" / "ui.php").read_text(encoding="utf-8")
css = (out / "assets" / "ui.css").read_text(encoding="utf-8")
core = (out / "includes" / "core.php").read_text(encoding="utf-8")
version = (out / "VERSION").read_text(encoding="utf-8")

checks = {
    "includes/ui.php": (ui, (
        "users-shell",
        "users-table",
        "user-action-stack",
        'data-label="Actions"',
        "drawer132",
        "Reset recovery code",
        "Open vault",
    )),
    "assets/ui.css": (css, (
        ".user-action-stack",
        ".users-table td::before",
        "min-height:48px",
        "@media(max-width:760px)",
    )),
    "includes/core.php": (core, ("const OG_VERSION = '1.3.27.5';",)),
    "VERSION": (version, ("OwnerGuard Cloud 1.3.27.5",)),
}
for name, (text, tokens) in checks.items():
    for token in tokens:
        if token not in text:
            raise SystemExit(f"Cloud 1.3.27.5 invariant missing in {name}: {token}")

php_files = sorted(out.rglob("*.php"))
for file in php_files:
    subprocess.run(["php", "-l", str(file)], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
print(f"Prepared OwnerGuard Cloud 1.3.27.5; {len(php_files)} PHP files linted")
