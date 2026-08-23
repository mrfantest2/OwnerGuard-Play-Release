#!/usr/bin/env python3
"""Execute the reviewed OwnerGuard 1.0.43 Android upload-pipeline repair."""
from pathlib import Path
import base64
import gzip
import hashlib

ROOT = Path(__file__).resolve().parents[1]
payload = ROOT / "tools" / "payloads" / "upload_pipeline_repair_1_0_43.b64"
source = gzip.decompress(base64.b64decode(payload.read_text(encoding="ascii"), validate=True))
expected = "ed159534a931a0ad01bc50612258598580b8fda8d062b2df642097fdabcc6f81"
if hashlib.sha256(source).hexdigest() != expected:
    raise SystemExit("OwnerGuard 1.0.43 upload repair payload checksum mismatch")
exec(compile(source, str(payload), "exec"), {
    "__name__": "__main__",
    "__file__": str(ROOT / "tools" / "apply_upload_pipeline_repair.py"),
})
