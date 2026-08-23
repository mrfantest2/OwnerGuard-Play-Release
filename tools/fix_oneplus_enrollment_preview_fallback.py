#!/usr/bin/env python3
"""Use the already-validated live preview when an OEM still JPEG cannot be face-detected."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "EnrollmentActivity.java"

text = PATH.read_text(encoding="utf-8")

field_anchor = "    private LiveFace lastLiveFace;"
field = "    private volatile Bitmap pendingEnrollmentPreview;"
if field not in text:
    if field_anchor not in text:
        raise SystemExit("EnrollmentActivity live-face field anchor missing")
    text = text.replace(field_anchor, field_anchor + "\n" + field, 1)

capture_anchor = '        autoBadge.setText("● Capturing");'
capture_block = '''        autoBadge.setText("● Capturing");
        Bitmap oldPreviewFallback = pendingEnrollmentPreview;
        pendingEnrollmentPreview = null;
        if (oldPreviewFallback != null && !oldPreviewFallback.isRecycled()) oldPreviewFallback.recycle();
        try {
            if (preview != null && preview.isAvailable()) pendingEnrollmentPreview = preview.getBitmap(360, 480);
        } catch (Throwable ignored) {
            pendingEnrollmentPreview = null;
        }'''
if "preview.getBitmap(360, 480)" not in text[text.find("private void captureAngle()"):text.find("private void handleCapturedImage")]:
    if capture_anchor not in text:
        raise SystemExit("EnrollmentActivity capture-state anchor missing")
    text = text.replace(capture_anchor, capture_block, 1)

old_result = '''            FaceSimilarity.EnrollmentResult result = FaceSimilarity.enrollGuided(this, bitmap, pose);
            bitmap.recycle();'''
new_result = '''            FaceSimilarity.EnrollmentResult stillResult = FaceSimilarity.enrollGuided(this, bitmap, pose);
            bitmap.recycle();
            Bitmap previewFallback = pendingEnrollmentPreview;
            pendingEnrollmentPreview = null;
            FaceSimilarity.EnrollmentResult resolvedResult = stillResult;
            if (!stillResult.accepted && previewFallback != null && !previewFallback.isRecycled()) {
                resolvedResult = FaceSimilarity.enrollGuided(this, previewFallback, pose);
            }
            if (previewFallback != null && !previewFallback.isRecycled()) previewFallback.recycle();
            final FaceSimilarity.EnrollmentResult result = resolvedResult;'''
if "if (!stillResult.accepted" not in text:
    if old_result not in text:
        raise SystemExit("EnrollmentActivity still-enrollment result anchor missing")
    text = text.replace(old_result, new_result, 1)

catch_anchor = '''        } catch (Exception e) {
            runOnUiThread(() -> {'''
catch_block = '''        } catch (Exception e) {
            Bitmap previewFallback = pendingEnrollmentPreview;
            pendingEnrollmentPreview = null;
            if (previewFallback != null && !previewFallback.isRecycled()) previewFallback.recycle();
            runOnUiThread(() -> {'''
handle_start = text.find("    private void handleCapturedImage(ImageReader r)")
handle_end = text.find("    private Bitmap rotate(", handle_start)
handle = text[handle_start:handle_end]
if "Bitmap previewFallback = pendingEnrollmentPreview;" not in handle[handle.find("catch (Exception e)"):]:
    idx = text.find(catch_anchor, handle_start, handle_end)
    if idx < 0:
        raise SystemExit("EnrollmentActivity captured-image catch anchor missing")
    text = text[:idx] + text[idx:].replace(catch_anchor, catch_block, 1)

close_anchor = '''        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        reader = null;'''
close_block = '''        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        reader = null;
        Bitmap previewFallback = pendingEnrollmentPreview;
        pendingEnrollmentPreview = null;
        if (previewFallback != null && !previewFallback.isRecycled()) previewFallback.recycle();'''
close_start = text.find("    private void closeCamera()")
if close_start < 0:
    raise SystemExit("EnrollmentActivity closeCamera anchor missing")
close_tail = text[close_start:]
if "Bitmap previewFallback = pendingEnrollmentPreview;" not in close_tail:
    if close_anchor not in text:
        raise SystemExit("EnrollmentActivity reader-close anchor missing")
    text = text.replace(close_anchor, close_block, 1)

required = (
    "pendingEnrollmentPreview",
    "preview.getBitmap(360, 480)",
    "if (!stillResult.accepted",
    "FaceSimilarity.enrollGuided(this, previewFallback, pose)",
    "previewFallback.recycle()",
)
for token in required:
    if token not in text:
        raise SystemExit("OnePlus preview fallback invariant missing: " + token)

PATH.write_text(text, encoding="utf-8")
print("Applied OnePlus/OEM enrollment fallback from still JPEG to validated live preview")
