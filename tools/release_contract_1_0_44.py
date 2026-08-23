#!/usr/bin/env python3
"""Static release contract for the reconstructed OwnerGuard 1.0.44 Play build."""
from __future__ import annotations

from pathlib import Path
import sys

REQUIRED = {
    "app/build.gradle": (
        "applicationId 'com.fantest.ownerguard'",
        "targetSdk 36",
        "versionCode 10044",
        "versionName '1.0.44'",
        "com.android.billingclient:billing:9.1.0",
    ),
    "app/src/main/AndroidManifest.xml": (
        '<activity android:name=".ProBackupActivity" android:exported="false" />',
    ),
    "app/src/main/java/com/fantest/ownerguard/ProEntitlement.java": (
        'PRODUCT_ID = "ownerguard_pro_lifetime"',
        "BillingClient.ProductType.INAPP",
        "enableOneTimeProducts()",
        "Purchase.PurchaseState.PURCHASED",
        "acknowledgePurchase",
    ),
    "app/src/main/java/com/fantest/ownerguard/DriveBackupManager.java": (
        "OwnerGuard Pro is required for Google Drive backup",
        "OwnerGuard Pro is required for backup restore",
        "CloudCrypto.getOrCreateKey",
        "VaultCrypto.decryptBytes",
        "VaultCrypto.encryptBytes",
        "OwnerGuard Portable Backup",
    ),
    "app/src/main/java/com/fantest/ownerguard/ProBackupActivity.java": (
        "Choose Google Drive backup folder",
        "Restore an OwnerGuard .ogb backup",
    ),
    "app/src/main/java/com/fantest/ownerguard/AppUpdateManager.java": (
        "PLAY_MANAGED_RELEASE = true",
        "Updates managed by Google Play",
    ),
    "app/src/main/java/com/fantest/ownerguard/MainActivity.java": (
        "ProBackupActivity.class",
        "OwnerGuard Pro",
        "Google Play managed release",
    ),
    "app/src/main/java/com/fantest/ownerguard/CloudAccountManager.java": (
        'MIN_SERVER_VERSION = "1.3.27.6"',
    ),
    "app/src/main/java/com/fantest/ownerguard/CloudBackupManager.java": (
        "APPEND_OR_REPLACE",
        "Resuming Cloud upload",
    ),
    "app/src/main/java/com/fantest/ownerguard/CloudBackupEngine.java": (
        "session_rebound",
        "markUploadingObject",
    ),
    "app/src/main/java/com/fantest/ownerguard/PinStore.java": (
        "PIN must contain exactly six digits",
    ),
    "app/src/main/java/com/fantest/ownerguard/OwnerGuardRequirements.java": (
        "REQUEST_OWNER_FACE = 2042",
    ),
    "app/src/main/java/com/fantest/ownerguard/TheftAdminReceiver.java": (
        "ACTION_FAILED_CREDENTIAL",
        "ACTION_SUCCESSFUL_CREDENTIAL",
    ),
    "app/src/main/java/com/fantest/ownerguard/ProtectionService.java": (
        "three front-camera photos and a 5-second video",
    ),
}

FORBIDDEN = {
    "app/src/main/AndroidManifest.xml": (
        "REQUEST_INSTALL_PACKAGES",
    ),
    "app/src/main/java/com/fantest/ownerguard/OwnerGuardRequirements.java": (
        "ACTION_MANAGE_UNKNOWN_APP_SOURCES",
        "canRequestPackageInstalls",
        "Install OwnerGuard updates",
        'INSTALL_UPDATES = "install_updates"',
    ),
}

CREDENTIAL_PATHS = (
    "signing/keystore.properties",
    "signing/ownerguard-release.jks",
)


def validate_tree(root: Path) -> list[str]:
    root = Path(root)
    errors: list[str] = []

    for relative, tokens in REQUIRED.items():
        path = root / relative
        if not path.is_file():
            errors.append(f"missing required file: {relative}")
            continue
        text = path.read_text(encoding="utf-8")
        for token in tokens:
            if token not in text:
                errors.append(f"missing release invariant in {relative}: {token}")

    for relative, tokens in FORBIDDEN.items():
        path = root / relative
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for token in tokens:
            if token in text:
                errors.append(f"forbidden Play-release token in {relative}: {token}")

    for relative in CREDENTIAL_PATHS:
        if (root / relative).exists():
            errors.append(f"tracked/reconstructed credential path must be absent: {relative}")

    for pattern in ("*.jks", "*.keystore", "service-account*.json", "play-service-account*.json"):
        for path in root.rglob(pattern):
            if any(part in {".git", "build", ".gradle"} for part in path.parts):
                continue
            errors.append(f"credential-like file must not be present in source tree: {path.relative_to(root)}")

    return sorted(set(errors))


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    errors = validate_tree(root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("OwnerGuard 1.0.44 release contract: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
