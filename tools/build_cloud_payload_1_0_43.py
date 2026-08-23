#!/usr/bin/env python3
"""Build and validate OwnerGuard Cloud 1.3.27.7 with Administrator media gallery."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
subprocess.run([sys.executable, str(ROOT / "tools" / "build_cloud_payload_1_0_42.py")], check=True)
subprocess.run([sys.executable, str(ROOT / "tools" / "apply_cloud_upload_pipeline_repair.py")], check=True)
subprocess.run([sys.executable, str(ROOT / "tools" / "apply_cloud_admin_gallery.py")], check=True)

out = ROOT / ".deploy" / "ownerguard_cloud"
checks = {
    ".htaccess": (out / ".htaccess", ("E=HTTP_AUTHORIZATION:%1", "SetEnvIfNoCase Authorization")),
    "includes/core.php": (out / "includes" / "core.php", (
        "const OG_VERSION = '1.3.27.7';",
        "REDIRECT_HTTP_AUTHORIZATION",
        "getallheaders",
        "Invalid or disabled API session",
    )),
    "VERSION": (out / "VERSION", ("OwnerGuard Cloud 1.3.27.7",)),
}
for label, (path, tokens) in checks.items():
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            raise SystemExit(f"Cloud 1.3.27.6 invariant missing in {label}: {token}")

for file in sorted(out.rglob("*.php")):
    subprocess.run(["php", "-l", str(file)], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
print("Prepared OwnerGuard Cloud 1.3.27.7 with robust bearer forwarding")
