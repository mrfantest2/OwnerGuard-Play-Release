package com.fantest.ownerguard;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/** Base activity that distinguishes internal navigation from leaving OwnerGuard. */
public abstract class SecureActivity extends Activity {
    private void markIfInternal(Intent intent) {
        if (intent == null) return;
        try {
            ComponentName component = intent.getComponent();
            if (component != null && getPackageName().equals(component.getPackageName())) {
                OwnerGuardApp.markInternalTransition();
                return;
            }
            ComponentName resolved = intent.resolveActivity(getPackageManager());
            if (resolved != null && getPackageName().equals(resolved.getPackageName())) OwnerGuardApp.markInternalTransition();
        } catch (Throwable ignored) {}
    }

    @Override public void startActivity(Intent intent) { markIfInternal(intent); super.startActivity(intent); }
    @Override public void startActivity(Intent intent, Bundle options) { markIfInternal(intent); super.startActivity(intent, options); }
    @Override public void startActivityForResult(Intent intent, int requestCode) { markIfInternal(intent); super.startActivityForResult(intent, requestCode); }
    @Override public void startActivityForResult(Intent intent, int requestCode, Bundle options) { markIfInternal(intent); super.startActivityForResult(intent, requestCode, options); }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!OwnerGuardApp.isInternalTransitionActive()) AuthSession.onBackground(this);
    }

    @Override protected void onStop() {
        super.onStop();
        if (!OwnerGuardApp.isAppVisible() && !OwnerGuardApp.isInternalTransitionActive()) AuthSession.onBackground(this);
    }
}
