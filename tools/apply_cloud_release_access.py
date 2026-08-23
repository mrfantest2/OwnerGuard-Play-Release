#!/usr/bin/env python3
"""Allow only the signed OwnerGuard updater and its manifest from Cloud releases/."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RELEASES = ROOT / ".deploy" / "ownerguard_cloud" / "releases"
HTACCESS = RELEASES / ".htaccess"

CONTENT = """Options -Indexes
<IfModule mod_authz_core.c>
  <FilesMatch "^(?!release\\.json$|OwnerGuard-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk$).+">
    Require all denied
  </FilesMatch>
</IfModule>
<IfModule !mod_authz_core.c>
  <FilesMatch "^(?!release\\.json$|OwnerGuard-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk$).+">
    Order Allow,Deny
    Deny from all
  </FilesMatch>
</IfModule>
"""

if not RELEASES.is_dir():
    raise SystemExit("OwnerGuard Cloud releases directory does not exist; reconstruct the cloud payload first")

HTACCESS.write_text(CONTENT, encoding="utf-8")
print("Applied OwnerGuard Cloud release download allow-list")
