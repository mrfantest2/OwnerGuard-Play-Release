#!/usr/bin/env python3
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
BUILD=(ROOT/"app"/"build.gradle").read_text(encoding="utf-8")
VAULT=(ROOT/"app"/"src"/"main"/"java"/"com"/"fantest"/"ownerguard"/"VaultActivity.java").read_text(encoding="utf-8")
PRO=(ROOT/"app"/"src"/"main"/"java"/"com"/"fantest"/"ownerguard"/"ProEntitlement.java").read_text(encoding="utf-8")
MANIFEST=(ROOT/"app"/"src"/"main"/"AndroidManifest.xml").read_text(encoding="utf-8")

for token in ("versionCode 10050","versionName '1.0.50'","targetSdk 36","com.android.billingclient:billing:9.1.0"):
    if token not in BUILD:
        raise SystemExit("build invariant missing: "+token)

for token in (
    "DEFAULT_PAGE_SIZE = 24",
    "Select all filtered",
    "Export this range",
    "VaultExportManager.exportIncidents",
    "Executors.newSingleThreadExecutor()",
    "private List<EventRow> indexedEvents;",
    "private boolean indexLoading;",
    "private void startIndexLoad()",
    "indexExecutor.execute",
    "Indexing encrypted incidents in the background",
    "List<EventRow> allEvents = new ArrayList<>(indexedEvents);",
):
    if token not in VAULT:
        raise SystemExit("vault scalability invariant missing: "+token)

if "List<EventRow> allEvents = loadAllEvents();" in VAULT:
    raise SystemExit("vault still performs synchronous full indexing in render()")

for token in (
    "ITEM_ALREADY_OWNED",
    "Purchase.PurchaseState.PENDING",
    "Restoring your existing OwnerGuard Pro lifetime purchase",
    "purchase pending — lifetime access activates when Google Play completes the payment",
    "Merchant account and product activation may still be pending",
    "ownerguard_pro_lifetime",
):
    if token not in PRO:
        raise SystemExit("Pro lifetime invariant missing: "+token)

for token in (
    'android.permission.ACCESS_BACKGROUND_LOCATION',
    'android.permission.FOREGROUND_SERVICE_LOCATION',
):
    if token in MANIFEST:
        raise SystemExit("forbidden location permission returned: "+token)

print("OwnerGuard 1.0.50 release contract: PASS")
