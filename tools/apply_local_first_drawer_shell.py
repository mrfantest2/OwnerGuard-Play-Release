#!/usr/bin/env python3
"""Finalize the native drawer for local-first use and Android 1.0.32."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHELL = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "OwnerGuardDrawerShell.java"

text = SHELL.read_text(encoding="utf-8")

old_nav = '''        section(nav, "OVERVIEW");
        item(nav, "dashboard", "⌂", "Dashboard");

        section(nav, "VAULT");'''
new_nav = '''        section(nav, "OVERVIEW");
        item(nav, "dashboard", "⌂", "Dashboard");

        section(nav, "DEVICE");
        item(nav, "protection", "◈", "Protection");
        item(nav, "setup", "⚙", "Local setup");

        section(nav, "CLOUD VAULT");'''
if old_nav not in text:
    raise SystemExit("Drawer navigation anchor missing")
text = text.replace(old_nav, new_nav, 1)

old_cloud = '        cloudLine.setText(signedIn ? "●  Cloud account ready" : "○  Cloud sign-in required");\n'
new_cloud = '        cloudLine.setText(signedIn ? "●  Cloud vault connected" : "○  Cloud vault optional — set up later");\n'
if old_cloud not in text:
    raise SystemExit("Drawer Cloud status anchor missing")
text = text.replace(old_cloud, new_cloud, 1)

text = text.replace('return info.versionName == null ? "1.0.31" : info.versionName;',
                    'return info.versionName == null ? "1.0.32" : info.versionName;')
text = text.replace('return "1.0.31";', 'return "1.0.32";')

required = [
    'item(nav, "protection", "◈", "Protection")',
    'item(nav, "setup", "⚙", "Local setup")',
    'section(nav, "CLOUD VAULT")',
    'Cloud vault optional — set up later',
    'return "1.0.32";',
    'WindowInsets.Type.statusBars()',
    'WindowInsets.Type.displayCutout()',
]
for token in required:
    if token not in text:
        raise SystemExit(f"Local-first drawer output incomplete: {token}")

SHELL.write_text(text, encoding="utf-8")
print("Applied local-first drawer destinations and safe-area release labels")
