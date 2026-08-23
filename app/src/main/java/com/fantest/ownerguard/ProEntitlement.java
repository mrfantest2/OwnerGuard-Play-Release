package com.fantest.ownerguard;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Google Play non-consumable entitlement for OwnerGuard Pro backup features. */
final class ProEntitlement implements PurchasesUpdatedListener {
    static final String PRODUCT_ID = "ownerguard_pro_lifetime";
    private static final String PREF = "owner_guard_pro";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_LAST_REFRESH = "last_refresh";
    private static volatile ProEntitlement instance;

    interface Listener { void onChanged(boolean active, String status); }

    private final Context app;
    private final BillingClient billing;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile ProductDetails productDetails;
    private volatile boolean connecting;

    private ProEntitlement(Context context) {
        app = context.getApplicationContext();
        billing = BillingClient.newBuilder(app)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
    }

    static ProEntitlement get(Context context) {
        ProEntitlement current = instance;
        if (current == null) {
            synchronized (ProEntitlement.class) {
                current = instance;
                if (current == null) instance = current = new ProEntitlement(context);
            }
        }
        return current;
    }

    static boolean cached(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false);
    }

    static String cachedStatus(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (p.getBoolean(KEY_ACTIVE, false)) return "OwnerGuard Pro — lifetime unlocked";
        return p.getString(KEY_LAST_STATUS, "OwnerGuard Free");
    }

    void addListener(Listener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void connectAndRefresh() {
        if (billing.isReady()) {
            refreshPurchases();
            queryProduct();
            return;
        }
        if (connecting) return;
        connecting = true;
        setStatus("Connecting to Google Play…", null);
        billing.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(BillingResult result) {
                connecting = false;
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    refreshPurchases();
                    queryProduct();
                } else {
                    setStatus("Google Play billing unavailable: " + result.getDebugMessage(), null);
                }
            }
            @Override public void onBillingServiceDisconnected() {
                connecting = false;
                setStatus("Google Play billing disconnected", null);
            }
        });
    }

    void restorePurchases() {
        connectAndRefresh();
    }

    void launchPurchase(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (!billing.isReady()) {
            addOneShotPurchaseListener(activity);
            connectAndRefresh();
            return;
        }
        ProductDetails details = productDetails;
        if (details == null) {
            setStatus("Loading OwnerGuard Pro from Google Play…", null);
            queryProduct(() -> launchPurchase(activity));
            return;
        }
        BillingFlowParams.ProductDetailsParams.Builder product = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details);
        ProductDetails.OneTimePurchaseOfferDetails offer = eligibleOffer(details);
        if (offer != null && offer.getOfferToken() != null && !offer.getOfferToken().isEmpty()) {
            product.setOfferToken(offer.getOfferToken());
        }
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(product.build()))
                .build();
        BillingResult result = billing.launchBillingFlow(activity, params);
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            setStatus("Unable to start purchase: " + result.getDebugMessage(), null);
        }
    }

    private void addOneShotPurchaseListener(Activity activity) {
        final Listener[] holder = new Listener[1];
        holder[0] = (active, status) -> {
            if (billing.isReady() && productDetails != null) {
                removeListener(holder[0]);
                activity.runOnUiThread(() -> launchPurchase(activity));
            }
        };
        addListener(holder[0]);
    }

    private void queryProduct() { queryProduct(null); }

    private void queryProduct(Runnable after) {
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(product))
                .build();
        billing.queryProductDetailsAsync(params, (BillingResult result, QueryProductDetailsResult detailsResult) -> {
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && detailsResult != null
                    && detailsResult.getProductDetailsList() != null && !detailsResult.getProductDetailsList().isEmpty()) {
                productDetails = detailsResult.getProductDetailsList().get(0);
                setStatus(cached(app) ? "OwnerGuard Pro — lifetime unlocked" : priceLabel(productDetails), null);
                if (after != null) after.run();
            } else {
                productDetails = null;
                setStatus("OwnerGuard Pro is not available from Google Play yet", null);
            }
        });
    }

    private static ProductDetails.OneTimePurchaseOfferDetails eligibleOffer(ProductDetails details) {
        try {
            List<ProductDetails.OneTimePurchaseOfferDetails> offers = details.getOneTimePurchaseOfferDetailsList();
            if (offers != null && !offers.isEmpty()) return offers.get(0);
            return details.getOneTimePurchaseOfferDetails();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String priceLabel(ProductDetails details) {
        try {
            ProductDetails.OneTimePurchaseOfferDetails offer = eligibleOffer(details);
            if (offer != null && offer.getFormattedPrice() != null) {
                return "OwnerGuard Pro lifetime — " + offer.getFormattedPrice();
            }
        } catch (Throwable ignored) {}
        return "OwnerGuard Pro lifetime";
    }

    private void refreshPurchases() {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        billing.queryPurchasesAsync(params, (result, purchases) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                setStatus("Could not verify purchases: " + result.getDebugMessage(), null);
                return;
            }
            boolean found = false;
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
        });
    }

    private void grant(Purchase purchase) {
        setStatus("OwnerGuard Pro — lifetime unlocked", true);
        if (!purchase.isAcknowledged()) {
            AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken())
                    .build();
            billing.acknowledgePurchase(params, result -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    setStatus("Pro unlocked; Play acknowledgement pending", true);
                }
            });
        }
    }

    @Override public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                if (purchase.getProducts().contains(PRODUCT_ID)
                        && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) grant(purchase);
            }
        } else if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            setStatus("Purchase cancelled", null);
        } else {
            setStatus("Purchase error: " + result.getDebugMessage(), null);
        }
    }

    private void setStatus(String status, Boolean active) {
        SharedPreferences.Editor e = app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_STATUS, status == null ? "" : status)
                .putLong(KEY_LAST_REFRESH, System.currentTimeMillis());
        if (active != null) e.putBoolean(KEY_ACTIVE, active);
        e.apply();
        boolean current = cached(app);
        for (Listener listener : listeners) {
            try { listener.onChanged(current, status); } catch (Throwable ignored) {}
        }
    }
}
