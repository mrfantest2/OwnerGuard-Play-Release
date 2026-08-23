#!/usr/bin/env python3
"""Install the OwnerGuard Cloud drawer-only workspace and dedicated routes."""
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / ".deploy" / "ownerguard_cloud"
OVERLAY = ROOT / "deploy" / "cloud_ui"
APP = OUT / "app.php"

if not APP.is_file():
    raise SystemExit("Reconstruct OwnerGuard Cloud before applying the drawer workspace")


def replace_between(text: str, start: str, end: str, replacement: str) -> str:
    a = text.find(start)
    b = text.find(end, a)
    if a < 0 or b < 0:
        raise SystemExit(f"OwnerGuard UI patch marker missing: {start}")
    return text[:a] + replacement.rstrip() + "\n" + text[b:]


for name in ("layout.inc", "vault.inc", "incident.inc", "settings.inc", "ui.css", "ui.js"):
    if not (OVERLAY / name).is_file():
        raise SystemExit(f"OwnerGuard drawer overlay is incomplete: {name}")

(OUT / "includes").mkdir(parents=True, exist_ok=True)
(OUT / "assets").mkdir(parents=True, exist_ok=True)
ui = "<?php\ndeclare(strict_types=1);\n\n" + "\n".join(
    (OVERLAY / name).read_text(encoding="utf-8").rstrip()
    for name in ("layout.inc", "vault.inc", "incident.inc", "settings.inc")
) + "\n"
(OUT / "includes" / "ui.php").write_text(ui, encoding="utf-8")
shutil.copy2(OVERLAY / "ui.css", OUT / "assets" / "ui.css")
shutil.copy2(OVERLAY / "ui.js", OUT / "assets" / "ui.js")

wrappers = {
    "dashboard.php": "dashboard",
    "incidents.php": "incidents",
    "owners.php": "owners",
    "devices.php": "devices",
    "settings.php": "settings",
    "users.php": "users",
    "audit.php": "audit",
    "system.php": "system",
    "updates.php": "updates",
}
for filename, route in wrappers.items():
    (OUT / filename).write_text(
        "<?php $_GET['route']='" + route + "'; require __DIR__ . '/app.php';\n",
        encoding="utf-8",
    )

text = APP.read_text(encoding="utf-8")
include = "require_once __DIR__ . '/includes/ui.php';"
if include not in text:
    text = text.replace(
        "require_once __DIR__ . '/includes/core.php';",
        "require_once __DIR__ . '/includes/core.php';\n" + include,
        1,
    )
text = replace_between(
    text,
    "function og_layout(",
    "function og_error_card",
    "function og_layout(string $title,string $body,?array $user=null):never { og_layout_drawer($title,$body,$user); }\n",
)
text = replace_between(
    text,
    "if ($route === 'index') {",
    "if ($route === 'incident') {",
    "if ($route === 'index' || $route === 'dashboard') { og_render_dashboard_page(); }\n"
    "if ($route === 'incidents') { og_render_incidents_page(); }\n"
    "if ($route === 'owners') { og_render_owners_page(); }\n"
    "if ($route === 'devices') { og_render_devices_page(); }\n",
)
text = replace_between(
    text,
    "if ($route === 'incident') {",
    "if ($route === 'decrypt') {",
    "if ($route === 'incident') { og_render_incident_page(); }\n",
)
text = replace_between(
    text,
    "if ($route === 'users') {",
    "if ($route === 'healthcheck') {",
    "if ($route === 'settings') { header('Location: users.php'); exit; }\n"
    "if ($route === 'users') { og_render_users_page(); }\n"
    "if ($route === 'audit') { og_render_audit_page(); }\n"
    "if ($route === 'system') { og_render_system_page(); }\n"
    "if ($route === 'updates') { og_render_updates_page(); }\n",
)
APP.write_text(text, encoding="utf-8")

changelog = OUT / "CHANGELOG.txt"
if changelog.is_file():
    note = (
        "\nDrawer-only workspace revision\n"
        "- Kept one global hamburger drawer as the only primary navigation system.\n"
        "- Added dedicated Dashboard, Incidents, Vault Owners, Devices, Users, Audit, System, and Update Management routes.\n"
        "- Added compact fixed headers with route-specific Search, Refresh, Back, and Download APK actions.\n"
        "- Added persistent desktop collapse state and an 82vw, 320px-capped accessible mobile drawer.\n"
        "- Kept administrator metadata browsing recovery-free while plaintext media recovery remains explicit and audited.\n"
        "- Preserved runtime data, escrow material, and the mandatory Android 1.0.30 updater.\n"
    )
    current = changelog.read_text(encoding="utf-8")
    if "compact fixed headers with route-specific" not in current:
        changelog.write_text(current.rstrip() + note, encoding="utf-8")

print("Applied OwnerGuard Cloud drawer-only workspace with compact page actions")
