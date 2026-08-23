#!/usr/bin/env python3
"""Build and validate OwnerGuard Cloud 1.3.27.4 for Android 1.0.41."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
subprocess.run([sys.executable, str(ROOT / "tools" / "build_cloud_payload.py")], check=True)
subprocess.run([sys.executable, str(ROOT / "tools" / "apply_cloud_recovery_login.py")], check=True)

out = ROOT / ".deploy" / "ownerguard_cloud"
app = (out / "app.php").read_text(encoding="utf-8")
core = (out / "includes" / "core.php").read_text(encoding="utf-8")
ui = (out / "includes" / "ui.php").read_text(encoding="utf-8")
version = (out / "VERSION").read_text(encoding="utf-8")

checks = {
    "app.php": (app, (
        "if ($route === 'api_signup')",
        "'recovery_code'=>$recovery",
        "Invalid username or recovery code",
        "admin_recovery_code_bootstrap",
        "recovery_code_migration",
        "name=\"recovery_code\"",
    )),
    "includes/core.php": (core, (
        "const OG_VERSION = '1.3.27.4';",
        "recovery_code_hash",
        "function og_recovery_code()",
        "function og_verify_recovery_code",
        "function og_set_recovery_code",
    )),
    "includes/ui.php": (ui, (
        "Reset recovery code",
        "Open vault",
        "Recovery codes are hashed and cannot be revealed",
        "function og_render_owners_page():never",
    )),
    "VERSION": (version, ("OwnerGuard Cloud 1.3.27.4",)),
}
for name, (text, tokens) in checks.items():
    for token in tokens:
        if token not in text:
            raise SystemExit(f"Cloud 1.3.27.4 invariant missing in {name}: {token}")

for forbidden in (
    "A password of at least 10 characters is required",
    "Invalid username or password",
    "<label>Password<input",
):
    if forbidden in app:
        raise SystemExit("Password login returned after recovery-code migration: " + forbidden)

php_files = sorted(out.rglob("*.php"))
for file in php_files:
    subprocess.run(["php", "-l", str(file)], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

print(f"Prepared OwnerGuard Cloud 1.3.27.4; {len(php_files)} PHP files linted")
