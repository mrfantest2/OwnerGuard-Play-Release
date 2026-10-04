#!/usr/bin/env python3
"""Reconstruct OwnerGuard 1.0.50 with scalable vault loading and hardened Pro lifetime billing."""
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

def replace_between(text,start,end,replacement):
    s=text.find(start)
    if s<0:
        raise SystemExit("start marker not found: "+start)
    e=text.find(end,s)
    if e<0:
        raise SystemExit("end marker not found: "+end)
    return text[:s]+replacement+text[e:]

def main():
    run(sys.executable,str(TOOLS/"prepare_release_1_0_49.py"))

    build=GRADLE.read_text(encoding="utf-8")
    build=re.sub(r"versionCode\s+10049\b","versionCode 10050",build,count=1)
    build=re.sub(r"versionName\s+'1\.0\.49'","versionName '1.0.50'",build,count=1)
    if "versionCode 10050" not in build or "versionName '1.0.50'" not in build:
        raise SystemExit("version promotion failed")
    GRADLE.write_text(build,encoding="utf-8")

    vault=VAULT.read_text(encoding="utf-8")
    vault=vault.replace(
        '    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);\n'
        '    private LruCache<String, Bitmap> thumbnailCache;\n',
        '    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);\n'
        '    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor();\n'
        '    private static final int PAGE_SIZE = 40;\n'
        '    private LruCache<String, Bitmap> thumbnailCache;\n'
        '    private List<EventRow> indexedEvents;\n'
        '    private boolean indexLoading;\n'
        '    private int visibleLimit = PAGE_SIZE;\n',
        1)
    vault=vault.replace(
        '        if (list != null) render();\n',
        '        if (list != null && indexedEvents != null) {\n'
        '            indexedEvents = null;\n'
        '            visibleLimit = PAGE_SIZE;\n'
        '            render();\n'
        '        }\n',
        1)
    vault=vault.replace(
        '        thumbnailExecutor.shutdownNow();\n',
        '        thumbnailExecutor.shutdownNow();\n'
        '        indexExecutor.shutdownNow();\n',
        1)

    new_render='''    private void render() {
        final int generation = ++renderGeneration;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor(BG));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        list.setPadding(p, p, p, p);
        scroll.addView(list);

        list.addView(title("Incident vault"));
        addFilterPanel();

        if (indexedEvents == null) {
            TextView loading = subtitle(indexLoading
                    ? "Indexing encrypted incidents in the background…"
                    : "Preparing encrypted incident index…");
            loading.setGravity(Gravity.CENTER);
            loading.setPadding(0, dp(38), 0, dp(38));
            list.addView(loading);
            Button back = button("Back", SURFACE_ALT);
            back.setOnClickListener(v -> finish());
            list.addView(back, topMarginParams(18));
            setContentView(scroll);
            startIndexLoad();
            return;
        }

        List<EventRow> allEvents = indexedEvents;
        List<EventRow> events = applyFilters(allEvents);
        Collections.sort(events, (a, b) -> newestFirst
                ? Long.compare(b.timestamp, a.timestamp)
                : Long.compare(a.timestamp, b.timestamp));

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        int shown = Math.min(visibleLimit, events.size());
        String countLabel = events.size() + (events.size() == 1 ? " incident" : " incidents");
        if (events.size() != allEvents.size()) countLabel += " of " + allEvents.size();
        if (shown < events.size()) countLabel += " • showing " + shown;
        TextView count = subtitle(countLabel);
        summary.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button sort = compactButton(newestFirst ? "Newest first" : "Oldest first", SURFACE_ALT);
        sort.setOnClickListener(v -> {
            newestFirst = !newestFirst;
            visibleLimit = PAGE_SIZE;
            render();
        });
        summary.addView(sort, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        list.addView(summary, topMarginParams(6));

        if (events.isEmpty()) {
            TextView empty = subtitle(allEvents.isEmpty()
                    ? "No incidents stored yet."
                    : "No incidents match the selected filters.");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(38), 0, dp(38));
            list.addView(empty);
        } else {
            String currentDay = null;
            for (int i = 0; i < shown; i++) {
                EventRow event = events.get(i);
                String dayKey = dayKey(event.timestamp);
                if (!dayKey.equals(currentDay)) {
                    currentDay = dayKey;
                    addDateHeader(event.timestamp, countForDay(events, dayKey));
                }
                addEvent(event, generation);
            }
            if (shown < events.size()) {
                int remaining = events.size() - shown;
                int next = Math.min(PAGE_SIZE, remaining);
                Button more = button("Load " + next + " more", SURFACE_ALT);
                more.setOnClickListener(v -> {
                    visibleLimit = Math.min(events.size(), visibleLimit + PAGE_SIZE);
                    render();
                });
                list.addView(more, topMarginParams(14));
            }
        }

        Button refresh = compactButton("Refresh vault", SURFACE_ALT);
        refresh.setOnClickListener(v -> {
            indexedEvents = null;
            visibleLimit = PAGE_SIZE;
            render();
        });
        list.addView(refresh, topMarginParams(16));

        Button back = button("Back", SURFACE_ALT);
        back.setOnClickListener(v -> finish());
        list.addView(back, topMarginParams(10));
        setContentView(scroll);
    }

    private void startIndexLoad() {
        if (indexLoading) return;
        indexLoading = true;
        indexExecutor.execute(() -> {
            List<EventRow> loaded = loadAllEvents();
            mainHandler.post(() -> {
                indexLoading = false;
                if (isFinishing() || isDestroyed()) return;
                indexedEvents = loaded;
                visibleLimit = PAGE_SIZE;
                render();
            });
        });
    }

'''
    vault=replace_between(vault,'    private void render() {\n','    private List<EventRow> loadAllEvents() {\n',new_render)

    vault=vault.replace(
        '        Collections.sort(rows, (a, b) -> newestFirst\n'
        '                ? Long.compare(b.timestamp, a.timestamp)\n'
        '                : Long.compare(a.timestamp, b.timestamp));\n'
        '        return rows;\n',
        '        return rows;\n',
        1)

    vault=vault.replace(
        '        date.setOnClickListener(v -> { dateFilter = (dateFilter + 1) % 5; render(); });\n',
        '        date.setOnClickListener(v -> { dateFilter = (dateFilter + 1) % 5; visibleLimit = PAGE_SIZE; render(); });\n',
        1)
    vault=vault.replace(
        '        type.setOnClickListener(v -> { typeFilter = (typeFilter + 1) % 5; render(); });\n',
        '        type.setOnClickListener(v -> { typeFilter = (typeFilter + 1) % 5; visibleLimit = PAGE_SIZE; render(); });\n',
        1)
    vault=vault.replace(
        '        owner.setOnClickListener(v -> { ownerFilter = (ownerFilter + 1) % 4; render(); });\n',
        '        owner.setOnClickListener(v -> { ownerFilter = (ownerFilter + 1) % 4; visibleLimit = PAGE_SIZE; render(); });\n',
        1)
    vault=vault.replace(
        '            ownerFilter = 0;\n'
        '            render();\n',
        '            ownerFilter = 0;\n'
        '            visibleLimit = PAGE_SIZE;\n'
        '            render();\n',
        1)
    VAULT.write_text(vault,encoding="utf-8")

    pro=PRO.read_text(encoding="utf-8")
    pro=pro.replace(
        '        BillingResult result = billing.launchBillingFlow(activity, params);\n'
        '        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {\n'
        '            setStatus("Unable to start purchase: " + result.getDebugMessage(), null);\n'
        '        }\n',
        '        BillingResult result = billing.launchBillingFlow(activity, params);\n'
        '        if (result.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {\n'
        '            setStatus("Restoring your existing OwnerGuard Pro lifetime purchase…", null);\n'
        '            refreshPurchases();\n'
        '        } else if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {\n'
        '            setStatus("Unable to start purchase: " + result.getDebugMessage(), null);\n'
        '        }\n',
        1)
    pro=pro.replace(
        '                setStatus("OwnerGuard Pro is not available from Google Play yet", null);\n',
        '                setStatus("OwnerGuard Pro lifetime is not available from Google Play yet. Merchant account and product activation may still be pending.", null);\n',
        1)
    old_refresh='''            boolean found = false;
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
    new_refresh='''            boolean found = false;
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
    if old_refresh not in pro:
        raise SystemExit("refresh purchase block not found")
    pro=pro.replace(old_refresh,new_refresh,1)

    old_updated='''        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                if (purchase.getProducts().contains(PRODUCT_ID)
                        && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) grant(purchase);
            }
        } else if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
'''
    new_updated='''        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
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
    if old_updated not in pro:
        raise SystemExit("purchase update block not found")
    pro=pro.replace(old_updated,new_updated,1)
    PRO.write_text(pro,encoding="utf-8")

    run(sys.executable,str(TOOLS/"release_contract_1_0_50.py"))
    print("OwnerGuard 1.0.50 production preparation: PASS")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
