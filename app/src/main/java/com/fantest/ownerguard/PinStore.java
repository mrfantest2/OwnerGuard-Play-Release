package com.fantest.ownerguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PinStore {
    private static final String PREF = "owner_guard_security";
    private static final String SALT = "pin_salt";
    private static final String HASH = "pin_hash";
    private static final String PIN_LENGTH = "pin_length";
    private static final int REQUIRED_LENGTH = 6;
    private static final int ITERATIONS = 180_000;

    private PinStore() {}

    static int requiredLength() { return REQUIRED_LENGTH; }

    static boolean isConfigured(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).contains(HASH);
    }

    static boolean requiresSixDigitMigration(Context c) {
        if (!isConfigured(c)) return false;
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(PIN_LENGTH, -1) != REQUIRED_LENGTH;
    }

    static void setPin(Context c, String pin) throws Exception {
        if (pin == null || !pin.matches("\\d{" + REQUIRED_LENGTH + "}")) {
            throw new IllegalArgumentException("PIN must contain exactly six digits");
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(pin, salt);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt(PIN_LENGTH, REQUIRED_LENGTH)
                .apply();
        Arrays.fill(hash, (byte) 0);
    }

    static boolean verify(Context c, String pin) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            byte[] salt = Base64.decode(p.getString(SALT, ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(p.getString(HASH, ""), Base64.NO_WRAP);
            byte[] actual = derive(pin, salt);
            boolean ok = constantTimeEquals(expected, actual);
            Arrays.fill(actual, (byte) 0);
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(String pin, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
