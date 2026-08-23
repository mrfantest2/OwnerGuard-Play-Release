#!/usr/bin/env python3
"""Execute the reviewed OwnerGuard 1.0.42 Android transformation."""
from pathlib import Path
import base64
import gzip
import hashlib

ROOT = Path(__file__).resolve().parents[1]
payload = ROOT / "tools" / "payloads" / "recovery_export_cellular_upload_1_0_42.b64"
source = gzip.decompress(base64.b64decode(payload.read_text(encoding="ascii"), validate=True))
expected = "fdac556164fe20c029f04ecb7dca9723a351cf10c79edc6ce128151eec67977c"
if hashlib.sha256(source).hexdigest() != expected:
    raise SystemExit("OwnerGuard 1.0.42 Android payload checksum mismatch")
exec(compile(source, str(payload), "exec"), {
    "__name__": "__main__",
    "__file__": str(ROOT / "tools" / "apply_recovery_export_cellular_upload.py"),
})
