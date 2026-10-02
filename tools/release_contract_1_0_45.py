#!/usr/bin/env python3
"""Release contract for the OwnerGuard 1.0.45 face-enrollment hotfix."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"

def require(path: Path, needle: str) -> None:
    text = path.read_text(encoding="utf-8")
    if needle not in text:
        raise SystemExit(f"missing required 1.0.45 marker in {path}: {needle}")

def forbid(path: Path, needle: str) -> None:
    text = path.read_text(encoding="utf-8")
    if needle in text:
        raise SystemExit(f"forbidden legacy marker remains in {path}: {needle}")

def main() -> int:
    build = ROOT / "app" / "build.gradle"
    require(build, "versionCode 10045")
    require(build, "versionName '1.0.45'")
    require(build, "com.google.mlkit:face-detection:16.1.7")

    for name in ("EnrollmentActivity.java", "FaceSimilarity.java", "SelfieVerificationActivity.java"):
        path = JAVA / name
        forbid(path, "android.media.FaceDetector")
        require(path, "FaceDetection.getClient")

    enrollment = JAVA / "EnrollmentActivity.java"
    require(enrollment, "pendingEnrollmentPreview")
    require(enrollment, "Only one face should be visible during enrollment")
    require(enrollment, "Samsung devices may already apply JPEG_ORIENTATION")

    face = JAVA / "FaceSimilarity.java"
    require(face, "Tasks.await")
    require(face, "new int[]{0, 90, 270, 180}")

    cloud = JAVA / "CloudSyncStatusView.java"
    require(cloud, '@SuppressLint("AppCompatCustomView")')
    print("OwnerGuard 1.0.45 release contract: PASS")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
