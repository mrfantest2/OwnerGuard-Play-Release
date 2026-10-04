#!/usr/bin/env python3
"""Reconstruct OwnerGuard 1.0.50 with async vault indexing and hardened Pro lifetime billing."""
from pathlib import Path
import re, subprocess, sys

ROOT=Path(__file__).resolve().parents[1]
TOOLS=ROOT/"tools"
GRADLE=ROOT/"app"/"build.gradle"
JAVA=ROOT/"app"/"src"/"main"/"java"/"com"/"fantest"/"ownerguard"
VAULT=JAVA/"VaultActivity.java"
PRO=JAVA/"ProEntitlement.java"

def run(*a):
    subprocess.run(list(a),cwd=ROOT,check=True)

def main():
    run(sys.executable,str(TOOLS/"prepare_release_1_0_49.py"))

    build=GRADLE.read_text(encoding="utf-8")
    build=re.sub(r"versionCode\s+10049\b","versionCode 10050",build,count=1)
    build=re.sub(r"versionName\s+'1\.0\.49'","versionName '1.0.50'",build,count=1)
    if "versionCode 10050" not in build or "versionName '1.0.50'" not in build:
        raise SystemExit("version promotion failed")
    GRADLE.write_text(build,encoding="utf-8")

    vault=VAULT.read_text(encoding="utf-8")

    field_anchor='    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);\n'
    if field_anchor not in vault:
        raise SystemExit("vault thumbnail executor anchor missing")
    if "private final ExecutorService indexExecutor" not in vault:
        vault=vault.replace(
            field_anchor,
            field_anchor +
            '    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor();\n'
            '    private List<EventRow> indexedEvents;\n'
            '    private boolean indexLoading;\n',
            1)

    old_resume='        if (list != null) render();\n'
    new_resume=(
        '        if (list != null && indexedEvents != null) {\n'
        '            indexedEvents = null;\n'
        '            currentPage = 0;\n'
        '            render();\n'
        '        }\n')
    if old_resume in vault:
        vault=vault.replace(old_resume,new_resume,1)
    elif "indexedEvents = null;" not in vault:
        raise SystemExit("vault onResume anchor missing")

    if "indexExecutor.shutdownNow();" not in vault:
        shutdown_anchor='        thumbnailExecutor.shutdownNow();\n'
        if shutdown_anchor not in vault:
            raise SystemExit("vault onDestroy anchor missing")
        vault=vault.replace(
            shutdown_anchor,
            shutdown_anchor+'        indexExecutor.shutdownNow();\n',
            1)

    eager='        List<EventRow> allEvents = loadAllEvents();\n'
    async_block='''        if (indexedEvents == null) {
            TextView loading = subtitle(indexLoading
                    ? "Indexing encrypted incidents in the background…"
                    : "Preparing encrypted incident index…");
            loading.setGravity(Gravity.CENTER);
            loading.setPadding(0, dp(34), 0, dp(34));
            list.addView(loading);
            Button back = button("Back", SURFACE_ALT);
            back.setOnClickListener(v -> finish());
            list.addView(back, topMarginParams(14));
            setContentView(scroll);
            startIndexLoad();
            return;
        }

        List<EventRow> allEvents = new ArrayList<>(indexedEvents);
        Collections.sort(allEvents, (a, b) -> newestFirst
                ? Long.compare(b.timestamp, a.timestamp)
                : Long.compare(a.timestamp, b.timestamp));
'''
    if eager not in vault:
        raise SystemExit("vault eager indexing anchor missing")
    vault=vault.replace(eager,async_block,1)

    load_anchor='    private List<EventRow> loadAllEvents() {\n'
    if load_anchor not in vault:
        raise SystemExit("vault loadAllEvents anchor missing")
    if "private void startIndexLoad()" not in vault:
        loader='''    private void startIndexLoad() {
        if (indexLoading) return;
        indexLoading = true;
        indexExecutor.execute(() -> {
            List<EventRow> loaded;
            try {
                loaded = loadAllEvents();
            } catch (Throwable ignored) {
                loaded = new ArrayList<>();
            }
            final List<EventRow> result = loaded;
            mainHandler.post(() -> {
                indexLoading = false;
                if (isFinishing() || isDestroyed()) return;
                indexedEvents = result;
                currentPage = 0;
                render();
            });
        });
    }

'''
        vault=vault.replace(load_anchor,loader+load_anchor,1)

    VAULT.write_text(vault,encoding="utf-8")

    pro=PRO.read_text(encoding="utf-8")
    purchase_old='''        BillingResult result = billing.launchBillingFlow(activity, params);
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            setStatus("Unable to start purchase: " + result.getDebugMessage(), null);
        }
'''
    purchase_new='''        BillingResult result = billing.launchBillingFlow(activity, params);
        if (result.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            setStatus("Restoring your existing OwnerGuard Pro lifetime purchase…", null);
            refreshPurchases();
        } else if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            setStatus("Unable to start purchase: " + result.getDebugMessage(), null);
        }
'''
    if purchase_old not in pro:
        raise SystemExit("Pro purchase launch anchor missing")
    pro=pro.replace(purchase_old,purchase_new,1)

    unavailable='                setStatus("OwnerGuard Pro is not available from Google Play yet", null);\n'
    if unavailable not in pro:
        raise SystemExit("Pro unavailable status anchor missing")
    pro=pro.replace(
        unavailable,
        '                setStatus("OwnerGuard Pro lifetime is not available from Google Play yet. Merchant account and product activation may still be pending.", null);\n',
        1)

    refresh_old='''            boolean found = false;
            if (purchases != null) {
                for (Purchase purchase : purchases) {
                    if (purchase.getProducts().contains(PRODUCT_ID)
                            && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                        found = true;
                        grant(purchase);
                    }
                }
            }
            if (!found) setStatus("OwnerGuard Free", false);
'''
    refresh_new='''            boolean found = false;
            boolean pending = false;
            if (purchases != null) {
                for (Purchase purchase : purchases) {
                    if (!purchase.getProducts().contains(PRODUCT_ID)) continue;
                    if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                        found = true;
                        grant(purchase);
                    } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                        pending = true;
                    }
                }
            }
            if (!found) {
                if (pending) {
                    setStatus("OwnerGuard Pro purchase pending — lifetime access activates when Google Play completes the payment.", false);
                } else {
                    setStatus("OwnerGuard Free", false);
                }
            }
'''
    if refresh_old not in pro:
        raise SystemExit("Pro refresh anchor missing")
    pro=pro.replace(refresh_old,refresh_new,1)

    updated_old='''        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                if (purchase.getProducts().contains(PRODUCT_ID)
                        && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) grant(purchase);
            }
        } else if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
'''
    updated_new='''        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            boolean pending = false;
            for (Purchase purchase : purchases) {
                if (!purchase.getProducts().contains(PRODUCT_ID)) continue;
                if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    grant(purchase);
                } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                    pending = true;
                }
            }
            if (pending && !cached(app)) {
                setStatus("OwnerGuard Pro purchase pending — lifetime access activates when Google Play completes the payment.", false);
            }
        } else if (result.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            setStatus("Restoring your existing OwnerGuard Pro lifetime purchase…", null);
            refreshPurchases();
        } else if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
'''
    if updated_old not in pro:
        raise SystemExit("Pro purchase callback anchor missing")
    pro=pro.replace(updated_old,updated_new,1)
    PRO.write_text(pro,encoding="utf-8")

    run(sys.executable,str(TOOLS/"release_contract_1_0_50.py"))
    print("OwnerGuard 1.0.50 production preparation: PASS")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
