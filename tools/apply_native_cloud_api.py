#!/usr/bin/env python3
"""Install the authenticated metadata API used by the native Android Cloud workspace."""
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "deploy" / "cloud_api" / "native_workspace.php"
TARGET = ROOT / ".deploy" / "ownerguard_cloud" / "api" / "native_workspace.php"

if not SOURCE.is_file():
    raise SystemExit("Native Cloud API source is missing")
TARGET.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(SOURCE, TARGET)
text = TARGET.read_text(encoding="utf-8")
required = [
    "og_api_user()",
    "native_user_status_toggle",
    "native_incident_metadata_view",
    "Administrator access required",
    "Unknown native workspace section",
]
missing = [token for token in required if token not in text]
if missing:
    raise SystemExit("Native Cloud API is incomplete: " + ", ".join(missing))
print("Installed OwnerGuard native Cloud workspace API")
