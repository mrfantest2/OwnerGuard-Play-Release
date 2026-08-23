package com.fantest.ownerguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** In-memory private-session gate with a user-selectable background timeout. */
final class AuthSession {
    static final String PREF = "owner_guard_settings";
    static final String KEY_TIMEOUT = "app_lock_timeout_ms";
    static final long DEFAULT_TIMEOUT = 60_000L;
    static final long[] ALLOWED_TIMEOUTS = new long[]{0L, 60_000L, 180_000L, 300_000L, 600_000L};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean unlocked;
    private static volatile long lockDeadline;
    private static final Runnable EXPIRE = () -> {
        if (lockDeadline > 0L && SystemClock.elapsedRealtime() >= lockDeadline) lock();
    };

    private AuthSession() {}

    static synchronized void unlock() {
        unlocked = true;
        lockDeadline = 0L;
        MAIN.removeCallbacks(EXPIRE);
    }

    static synchronized boolean isUnlocked() {
        if (unlocked && lockDeadline > 0L && SystemClock.elapsedRealtime() >= lockDeadline) lock();
        return unlocked;
    }

    static synchronized void lock() {
        unlocked = false;
        lockDeadline = 0L;
        MAIN.removeCallbacks(EXPIRE);
    }

    static long timeout(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long value = p.getLong(KEY_TIMEOUT, DEFAULT_TIMEOUT);
        for (long allowed : ALLOWED_TIMEOUTS) if (value == allowed) return value;
        return DEFAULT_TIMEOUT;
    }

    static void setTimeout(Context context, long timeout) {
        boolean valid = false;
        for (long allowed : ALLOWED_TIMEOUTS) if (timeout == allowed) valid = true;
        if (!valid) timeout = DEFAULT_TIMEOUT;
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong(KEY_TIMEOUT, timeout).apply();
    }

    static synchronized void onBackground(Context context) {
        if (!unlocked) return;
        long delay = timeout(context);
        MAIN.removeCallbacks(EXPIRE);
        if (delay <= 0L) {
            lock();
            return;
        }
        lockDeadline = SystemClock.elapsedRealtime() + delay;
        MAIN.postDelayed(EXPIRE, delay + 25L);
    }

    static synchronized void onForeground() {
        if (!unlocked) return;
        if (lockDeadline > 0L && SystemClock.elapsedRealtime() >= lockDeadline) {
            lock();
            return;
        }
        lockDeadline = 0L;
        MAIN.removeCallbacks(EXPIRE);
    }

    static String timeoutLabel(Context context) {
        long value = timeout(context);
        if (value == 0L) return "Instantly";
        if (value == 60_000L) return "After 1 minute";
        if (value == 180_000L) return "After 3 minutes";
        if (value == 300_000L) return "After 5 minutes";
        return "After 10 minutes";
    }
}
