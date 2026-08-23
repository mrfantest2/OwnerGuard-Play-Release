package com.fantest.ownerguard;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Tracks app visibility and starts the selected lock countdown only after OwnerGuard is left. */
public class OwnerGuardApp extends Application implements Application.ActivityLifecycleCallbacks {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int startedActivities;
    private static volatile int resumedActivities;
    private static volatile long internalTransitionUntil;
    private static volatile long lastBackupRetryAt;
    private static OwnerGuardApp instance;

    private static final Runnable BACKGROUND_CHECK = () -> {
        OwnerGuardApp app = instance;
        if (app != null && resumedActivities == 0 && startedActivities == 0 && !isInternalTransitionActive()) {
            AuthSession.onBackground(app);
        }
    };

    static void markInternalTransition() {
        internalTransitionUntil = SystemClock.uptimeMillis() + 1000L;
        MAIN.removeCallbacks(BACKGROUND_CHECK);
    }

    static boolean isInternalTransitionActive() {
        return SystemClock.uptimeMillis() < internalTransitionUntil;
    }

    static boolean isAppVisible() { return startedActivities > 0; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        AuthSession.lock();
        registerActivityLifecycleCallbacks(this);
        AppUpdateManager.initialize(this);
    }

    @Override public synchronized void onActivityStarted(Activity activity) {
        startedActivities++;
        MAIN.removeCallbacks(BACKGROUND_CHECK);
    }

    @Override public synchronized void onActivityResumed(Activity activity) {
        resumedActivities++;
        MAIN.removeCallbacks(BACKGROUND_CHECK);
        AuthSession.onForeground();
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackupRetryAt > 30_000L) {
            lastBackupRetryAt = now;
            MAIN.postDelayed(() -> CloudBackupManager.retryPending(this), 800L);
        }
    }

    @Override public synchronized void onActivityPaused(Activity activity) {
        resumedActivities = Math.max(0, resumedActivities - 1);
    }

    @Override public synchronized void onActivityStopped(Activity activity) {
        startedActivities = Math.max(0, startedActivities - 1);
        MAIN.removeCallbacks(BACKGROUND_CHECK);
        if (startedActivities == 0) MAIN.postDelayed(BACKGROUND_CHECK, 120L);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN && !isInternalTransitionActive()) AuthSession.onBackground(this);
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}
