#!/usr/bin/env python3
"""Reconstruct and execute the reviewed OwnerGuard automatic Cloud sync patch."""
from pathlib import Path
import base64
import zlib

ROOT = Path(__file__).resolve().parents[1]
PAYLOAD = ROOT / "tools" / "payloads"
encoded = "".join(
    (PAYLOAD / f"cloud_sync_{index:02d}.b64").read_text(encoding="ascii").strip()
    for index in range(4)
)
compressed_prefix = base64.b64decode(encoded, validate=True)
decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
source_prefix = decoder.decompress(compressed_prefix).decode("utf-8")
source_tail = (PAYLOAD / "cloud_sync_tail.pyfrag").read_text(encoding="utf-8")
source = source_prefix + source_tail
if len(source_prefix) != 61961:
    raise SystemExit(f"Automatic Cloud sync payload prefix mismatch: {len(source_prefix)}")

# The forced-update patch also augments OwnerGuardApp.onActivityResumed. Replace
# that method structurally instead of depending on its older exact body.
old_line = 'app = replace_once(app, old_resume, new_resume, "OwnerGuardApp foreground reconciliation")'
new_line = 'app = replace_method(app, "    @Override public synchronized void onActivityResumed(Activity activity) {", new_resume)'
if old_line not in source:
    raise SystemExit("Automatic Cloud sync foreground transform normalization anchor is missing")
source = source.replace(old_line, new_line, 1)

for marker in (
    "class CloudBackupEngine",
    "class CloudAuthActivity",
    "private void showVaultTab(){",
    "Applied separate Cloud authentication",
    "replace_method(app, \"    @Override public synchronized void onActivityResumed",
):
    if marker not in source:
        raise SystemExit("Automatic Cloud sync payload is incomplete: " + marker)
exec(compile(source, __file__ + ":payload", "exec"), {"__name__": "__main__", "__file__": __file__})
