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
        "Create an exact 6-digit OwnerGuard PIN",
    ),
    "app/src/main/java/com/fantest/ownerguard/CloudAuthActivity.java": (
        "REQUEST_CODE",
        "Online OwnerGuard accounts are not required in this Play release.",
    ),
    "app/src/main/java/com/fantest/ownerguard/CloudAccountManager.java": (
        "HOSTED_ACCOUNT_DISABLED = true",
    ),
    "app/src/main/java/com/fantest/ownerguard/CloudBackupManager.java": (
        "LEGACY_HOSTED_CLOUD_DISABLED = true",
    ),
    "app/src/main/java/com/fantest/ownerguard/FaceSimilarity.java": (
        "ENROLLMENT_ORIENTATIONS = new int[]{0, 90, 270, 180}",
        "descriptorEnrollmentVariants(bitmap)",
    ),
    "app/src/main/java/com/fantest/ownerguard/EnrollmentActivity.java": (
        "if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);",
    ),
    "app/src/main/java/com/fantest/ownerguard/PinStore.java": (
        "PIN must contain exactly six digits",
    ),
    "app/src/main/java/com/fantest/ownerguard/OwnerGuardRequirements.java": (
        "REQUEST_OWNER_FACE = 2042",
        "UNVERIFIABLE_OEM_SETTINGS_ARE_ADVISORY = true",
        'appendAdvisory(out, "Pause app activity if unused"',
        'appendAdvisory(out, "Samsung Never sleeping apps"',
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
        "if (!unusedAppProtectionReady(context)) out.add(UNUSED_APP);",
        "if (!samsungNeverSleepingReady(context)) out.add(SAMSUNG_NEVER_SLEEPING);",
    ),
    "app/src/main/java/com/fantest/ownerguard/MainActivity.java": (
        "launchAutomaticVaultSetup(",
        "ensureCloudAccountLinked",
        'sectionTitle("Automatic Cloud backup")',
        'sectionTitle("Encrypted cloud backup")',
        'sectionTitle("Cloud account")',
        "new CloudSyncStatusView(this)",
    ),
}

GLOBAL_ANDROID_FORBIDDEN = {
    "fantest.win": "legacy fantest.win hosted-cloud domain",
    "Create your username and six-digit OwnerGuard PIN": "forced hosted-cloud registration",
    "Cloud backup is enabled automatically": "forced hosted-cloud registration",
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

    app_source = root / "app/src/main"
    if app_source.is_dir():
        for path in app_source.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in {".java", ".kt", ".xml", ".json", ".txt"}:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for token, label in GLOBAL_ANDROID_FORBIDDEN.items():
                if token in text:
                    errors.append(
                        f"{label} is forbidden in {path.relative_to(root)}: {token}"
                    )

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
