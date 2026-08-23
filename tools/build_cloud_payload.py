#!/usr/bin/env python3
"""Reconstruct, harden, and validate OwnerGuard Cloud 1.3.27.3."""
from pathlib import Path
import base64
import hashlib
import shutil
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE_B64 = ROOT / "deploy" / "cloud_source.tar.gz.b64"
DEPLOY = ROOT / ".deploy"
OUT = DEPLOY / "ownerguard_cloud"
ARCHIVE = DEPLOY / "ownerguard_cloud_source.tar.gz"
EXPECTED_ARCHIVE_SHA256 = "e9cb2c84f7aeea7d9807b0c39a57193fbf2a32d5c121a37e26c54f302595c30d"

# Validate pinned baseline bytes before applying reviewed release overlays.
EXPECTED_FILES = {
    "includes/core.php": "785b531c819290dbd98a84a3caa8ef0587ada12b5a0a5e8b8d49c18dfaa6b4ea",
    ".htaccess": "045be4b91734b61a65248ac06e548cf2ef1d8d8163dcb2e7cf2e75276cfe2d9f",
    "releases/.htaccess": "a8e63f631abc7ed32508f69aa07ac52c684044298b8227ba27e9e2887f95f325",
    "api/ping.php": "00de61ca4d340706d10b1211cb5472cf3d16ce50121760990a803e5fac70d210",
    "releases/release.json": "78e56aa026db2b38a1d65a437982438ce6a295122ca739062ce54127c4b516fe",
    "VERSION": "34937cbb13045100807d40f7685cdbc7c654d889fb29b8d5506a4d052e5a6e8e",
}

if DEPLOY.exists():
    shutil.rmtree(DEPLOY)
OUT.mkdir(parents=True)

raw = base64.b64decode(SOURCE_B64.read_text(encoding="ascii").strip(), validate=True)
actual = hashlib.sha256(raw).hexdigest()
if actual != EXPECTED_ARCHIVE_SHA256:
    raise SystemExit(f"Cloud source checksum mismatch: {actual}")
ARCHIVE.write_bytes(raw)

with tarfile.open(ARCHIVE, "r:gz") as package:
    for member in package.getmembers():
        target = (OUT / member.name).resolve()
        if OUT.resolve() not in target.parents and target != OUT.resolve():
            raise SystemExit(f"Unsafe cloud source member: {member.name}")
    package.extractall(OUT)
ARCHIVE.unlink()

subprocess.run([sys.executable, str(ROOT / "tools" / "apply_cloud_openssl_hotfix.py")], check=True)
subprocess.run([sys.executable, str(ROOT / "tools" / "apply_cloud_dual_backend_hotfix.py")], check=True)
subprocess.run([sys.executable, str(ROOT / "tools" / "apply_cloud_release_access.py")], check=True)
subprocess.run([sys.executable, str(ROOT / "tools" / "apply_cloud_tabbed_ui.py")], check=True)
subprocess.run([sys.executable, str(ROOT / "tools" / "apply_native_cloud_api.py")], check=True)

required = [
    "app.php", "includes/core.php", "includes/ui.php", "assets/ui.css", "assets/ui.js",
    "index.php", "dashboard.php", "incidents.php", "owners.php", "devices.php",
    "incident.php", "decrypt.php", "settings.php", "users.php", "audit.php",
    "system.php", "updates.php", "setup.php", "login.php",
    "api/ping.php", "api/mobile_signup.php", "api/mobile_login.php",
    "api/mobile_me.php", "api/upload.php", "api/escrow_public.php",
    "api/update_manifest.php", "api/native_workspace.php", "mobile_api.php", "tools/healthcheck.php",
]
missing = [name for name in required if not (OUT / name).is_file()]
if missing:
    raise SystemExit("Cloud source is incomplete: " + ", ".join(missing))

for relative, expected in EXPECTED_FILES.items():
    digest = hashlib.sha256((OUT / relative).read_bytes()).hexdigest()
    if digest != expected:
        raise SystemExit(f"Critical file checksum mismatch: {relative}: {digest}")

# Apply versioned API semantics only after the pinned baseline and prior overlays validate.
subprocess.run(
    [sys.executable, str(ROOT / "tools" / "apply_cloud_automatic_sync_api.py")],
    check=True,
)

app = (OUT / "app.php").read_text(encoding="utf-8")
core = (OUT / "includes" / "core.php").read_text(encoding="utf-8")
version = (OUT / "VERSION").read_text(encoding="utf-8")
ui = (OUT / "includes" / "ui.php").read_text(encoding="utf-8")
css = (OUT / "assets" / "ui.css").read_text(encoding="utf-8")
js = (OUT / "assets" / "ui.js").read_text(encoding="utf-8")
native_api = (OUT / "api" / "native_workspace.php").read_text(encoding="utf-8")
markers = {
    "app.php": [
        "require_once __DIR__ . '/includes/ui.php';",
        "if ($route === 'index' || $route === 'dashboard') { og_render_dashboard_page(); }",
        "if ($route === 'incidents') { og_render_incidents_page(); }",
        "if ($route === 'owners') { og_render_owners_page(); }",
        "if ($route === 'devices') { og_render_devices_page(); }",
        "if ($route === 'incident') { og_render_incident_page(); }",
        "if ($route === 'decrypt') {",
        "if ($route === 'users') { og_render_users_page(); }",
        "if ($route === 'audit') { og_render_audit_page(); }",
        "if ($route === 'system') { og_render_system_page(); }",
        "if ($route === 'updates') { og_render_updates_page(); }",
        "A password of at least 10 characters is required",
        "automatic Wi-Fi backup policy",
        "HTTP_X_OWNERGUARD_MIGRATION",
        "object_migrated",
        "previous ciphertext archived",
        "already_current",
    ],
    "includes/ui.php": [
        "function og_layout_drawer",
        "function og_ui_header_actions",
        "data-page-search",
        "Mandatory APK",
        "Signed in as",
        "function og_render_dashboard_page():never",
        "function og_render_incidents_page():never",
        "function og_render_owners_page():never",
        "function og_render_devices_page():never",
        "function og_render_incident_page():never",
        "function og_render_users_page():never",
        "function og_render_audit_page():never",
        "function og_render_system_page():never",
        "function og_render_updates_page():never",
        "Vault metadata is available without recovery",
        "Recovery remains an explicit, audited action",
        "Recover media",
    ],
    "assets/ui.css": [
        ".app-drawer", ".drawer-link", ".drawer-collapsed", ".drawer-open",
        ".header-actions", ".header-action", "width:min(82vw,320px)", ".table-shell"
    ],
    "assets/ui.js": [
        "ownerguard.drawer.collapsed", "data-drawer-toggle", "data-page-search",
        "input[name=\"q\"]", "Escape", "matchMedia"
    ],
    "api/native_workspace.php": [
        "og_api_user()",
        "native_user_status_toggle",
        "native_incident_metadata_view",
        "Administrator access required",
        "Unknown native workspace section",
    ],
}
for source, tokens in markers.items():
    text = {
        "app.php": app,
        "includes/ui.php": ui,
        "assets/ui.css": css,
        "assets/ui.js": js,
        "api/native_workspace.php": native_api,
    }[source]
    for token in tokens:
        if token not in text:
            raise SystemExit(f"Generated {source} is incomplete: {token}")

if "const OG_VERSION = '1.3.27.3';" not in core or "OwnerGuard Cloud 1.3.27.3" not in version:
    raise SystemExit("Cloud 1.3.27.3 version promotion is incomplete")
for obsolete in ("top-tabs", "section-tabs", "top-tab", "section-tab"):
    if obsolete in ui or obsolete in css:
        raise SystemExit(f"Obsolete tab navigation marker returned: {obsolete}")
for obsolete in (
    "Display name and a password of at least 10 characters are required",
    "$display=trim((string)($in['display_name']??''))",
):
    if obsolete in app:
        raise SystemExit(f"Obsolete mobile signup requirement returned: {obsolete}")

php_files = sorted(OUT.rglob("*.php"))
for file in php_files:
    subprocess.run(
        ["php", "-l", str(file)],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )

for name in [
    "runtime.json", "bootstrap-secrets.json", "ownerguard.sqlite",
    "admin_escrow_private.pem", "admin_escrow_public.pem", "openssl-ownerguard.cnf",
]:
    if (OUT / "data" / name).exists():
        raise SystemExit(f"Source artifact must not contain runtime secret or generated file: {name}")
if list((OUT / "data").glob(".og-escrow-*")) if (OUT / "data").exists() else []:
    raise SystemExit("Source artifact contains temporary escrow material")

print(
    f"Prepared {OUT}; baseline source SHA-256 {actual}; "
    f"OwnerGuard Cloud 1.3.27.3 automatic signup and migration-safe upload API; "
    f"{len(php_files)} PHP files linted"
)
