package com.fantest.ownerguard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONObject;

import java.util.List;

final class LocationSnapshot {
    private static final String PREF = "owner_guard_location";
    private LocationSnapshot() {}

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static void refresh(Context c) {
        if (!hasPermission(c)) return;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            List<String> providers = lm.getProviders(true);
            for (String provider : providers) {
                try {
                    Location candidate = lm.getLastKnownLocation(provider);
                    if (candidate == null) continue;
                    if (best == null || candidate.getTime() > best.getTime() ||
                            (Math.abs(candidate.getTime() - best.getTime()) < 60_000L && candidate.getAccuracy() < best.getAccuracy())) {
                        best = candidate;
                    }
                } catch (SecurityException ignored) {}
            }
            if (best != null) {
                c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                        .putLong("lat_bits", Double.doubleToRawLongBits(best.getLatitude()))
                        .putLong("lon_bits", Double.doubleToRawLongBits(best.getLongitude()))
                        .putFloat("accuracy", best.hasAccuracy() ? best.getAccuracy() : -1f)
                        .putLong("time", best.getTime())
                        .putString("provider", best.getProvider() == null ? "" : best.getProvider())
                        .apply();
            }
        } catch (Exception ignored) {}
    }

    static JSONObject capture(Context c) {
        refresh(c);
        android.content.SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        JSONObject o = new JSONObject();
        try {
            if (!p.contains("lat_bits") || !p.contains("lon_bits")) {
                o.put("available", false); return o;
            }
            o.put("available", true);
            o.put("latitude", Double.longBitsToDouble(p.getLong("lat_bits", 0L)));
            o.put("longitude", Double.longBitsToDouble(p.getLong("lon_bits", 0L)));
            o.put("accuracyMeters", p.getFloat("accuracy", -1f));
            o.put("locationTime", p.getLong("time", 0L));
            o.put("provider", p.getString("provider", ""));
        } catch (Exception ignored) {}
        return o;
    }

    static void addToMetadata(JSONObject meta, JSONObject location) {
        try {
            boolean available = location != null && location.optBoolean("available", false);
            meta.put("locationAvailable", available);
            if (available) {
                meta.put("latitude", location.optDouble("latitude"));
                meta.put("longitude", location.optDouble("longitude"));
                meta.put("locationAccuracyMeters", location.optDouble("accuracyMeters", -1));
                meta.put("locationCapturedAt", location.optLong("locationTime", 0));
                meta.put("locationProvider", location.optString("provider", ""));
            }
        } catch (Exception ignored) {}
    }
}
