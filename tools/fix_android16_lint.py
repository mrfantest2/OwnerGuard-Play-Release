#!/usr/bin/env python3
"""Apply Android 16 lint/runtime compatibility fixes after OwnerGuard 1.0.44 reconstruction."""

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"

BACK_ACTIVITIES = (
    "CloudAuthActivity.java",
    "CloudConsoleActivity.java",
    "EnrollmentActivity.java",
    "MainActivity.java",
    "NativeCloudActivity.java",
)

BACK_BRIDGE = (
    "if (android.os.Build.VERSION.SDK_INT >= 33) { "
    "getOnBackInvokedDispatcher().registerOnBackInvokedCallback("
    "android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed); }"
)
BACK_SUPPRESS = '@android.annotation.SuppressLint("GestureBackNavigation")'


def patch_back_navigation(path: Path) -> None:
    if not path.is_file():
        raise SystemExit(f"Android 16 back-navigation source missing: {path.name}")

    text = path.read_text(encoding="utf-8")

    if BACK_BRIDGE not in text:
        match = re.search(r"(?m)^(?P<indent>[ \t]*)super\.onCreate\([^)]+\);[ \t]*$", text)
        if not match:
            raise SystemExit(f"Android 16 onCreate anchor missing in {path.name}")
        indent = match.group("indent")
        replacement = match.group(0) + "\n" + indent + BACK_BRIDGE
        text = text[:match.start()] + replacement + text[match.end():]

    if BACK_SUPPRESS not in text:
        pattern = re.compile(
            r"(?m)^(?P<indent>[ \t]*)@Override[ \t]+public[ \t]+void[ \t]+onBackPressed\(\)[ \t]*\{"
        )
        match = pattern.search(text)
        if not match:
            raise SystemExit(f"Android 16 onBackPressed anchor missing in {path.name}")
        indent = match.group("indent")
        replacement = (
            indent + BACK_SUPPRESS + "\n" +
            indent + "@Override public void onBackPressed() {"
        )
        text = text[:match.start()] + replacement + text[match.end():]

    path.write_text(text, encoding="utf-8")


def patch_pro_backup_flags(path: Path) -> None:
    if not path.is_file():
        raise SystemExit("OwnerGuard Pro backup source missing")

    text = path.read_text(encoding="utf-8")
    old = (
        "                int takeFlags = data.getFlags() & "
        "(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);\n"
        "                getContentResolver().takePersistableUriPermission(uri, takeFlags);"
    )
    new = (
        "                int grantFlags = data.getFlags();\n"
        "                boolean grantRead = (grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;\n"
        "                boolean grantWrite = (grantFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;\n"
        "                if (grantRead && grantWrite) {\n"
        "                    getContentResolver().takePersistableUriPermission(uri, "
        "Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);\n"
        "                } else if (grantRead) {\n"
        "                    getContentResolver().takePersistableUriPermission(uri, "
        "Intent.FLAG_GRANT_READ_URI_PERMISSION);\n"
        "                } else if (grantWrite) {\n"
        "                    getContentResolver().takePersistableUriPermission(uri, "
        "Intent.FLAG_GRANT_WRITE_URI_PERMISSION);\n"
        "                }"
    )

    if old in text:
        text = text.replace(old, new, 1)
    elif "boolean grantRead = (grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;" not in text:
        raise SystemExit("OwnerGuard Pro persistable URI flag anchor missing")

    path.write_text(text, encoding="utf-8")


def patch_manifest_camera_feature(path: Path) -> None:
    if not path.is_file():
        raise SystemExit("OwnerGuard AndroidManifest.xml missing")

    text = path.read_text(encoding="utf-8")
    generic = '    <uses-feature android:name="android.hardware.camera" android:required="false" />'
    if generic not in text:
        front_pattern = re.compile(
            r'(?m)^(?P<line>[ \t]*<uses-feature android:name="android\.hardware\.camera\.front"[^>]+/>)$'
        )
        match = front_pattern.search(text)
        if match:
            text = text[:match.end()] + "\n" + generic + text[match.end():]
        else:
            root_end = text.find(">")
            if root_end < 0:
                raise SystemExit("OwnerGuard manifest root anchor missing")
            text = text[:root_end + 1] + "\n" + generic + text[root_end + 1:]

    path.write_text(text, encoding="utf-8")


def main() -> int:
    for name in BACK_ACTIVITIES:
        patch_back_navigation(JAVA / name)

    patch_pro_backup_flags(JAVA / "ProBackupActivity.java")
    patch_manifest_camera_feature(MANIFEST)

    for name in BACK_ACTIVITIES:
        text = (JAVA / name).read_text(encoding="utf-8")
        for token in (BACK_BRIDGE, BACK_SUPPRESS):
            if token not in text:
                raise SystemExit(f"Android 16 back-navigation invariant missing in {name}: {token}")

    pro = (JAVA / "ProBackupActivity.java").read_text(encoding="utf-8")
    if "int takeFlags = data.getFlags()" in pro:
        raise SystemExit("OwnerGuard Pro still uses lint-ambiguous persistable URI flags")

    manifest = MANIFEST.read_text(encoding="utf-8")
    if '<uses-feature android:name="android.hardware.camera" android:required="false" />' not in manifest:
        raise SystemExit("Optional generic camera feature invariant missing")

    print("Applied OwnerGuard Android 16 back-navigation, URI flag, and camera-feature lint fixes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
