#!/usr/bin/env python3
"""Execute the reviewed OwnerGuard Cloud 1.3.27.5 Users-layout transformation."""
from pathlib import Path
import base64
import gzip
import hashlib

ROOT = Path(__file__).resolve().parents[1]
payload = ROOT / "tools" / "payloads" / "cloud_mobile_users_layout_1_3_27_5.b64"
source = gzip.decompress(base64.b64decode(payload.read_text(encoding="ascii"), validate=True))
expected = "5435e22e64a15ce028b7a752244c7fbefdca7ecd4781050ee8a177c23205f1f3"
if hashlib.sha256(source).hexdigest() != expected:
    raise SystemExit("OwnerGuard Cloud 1.3.27.5 payload checksum mismatch")
exec(compile(source, str(payload), "exec"), {
    "__name__": "__main__",
    "__file__": str(ROOT / "tools" / "apply_cloud_mobile_users_layout.py"),
})
