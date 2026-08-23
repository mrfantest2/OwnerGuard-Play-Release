package com.fantest.ownerguard;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Daily best-effort OwnerGuard Pro backup to the persisted cloud document tree. */
public final class DriveBackupWorker extends Worker {
    public DriveBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        if (!ProEntitlement.cached(context) || !DriveBackupManager.automatic(context) || !DriveBackupManager.configured(context)) {
            return Result.success();
        }
        try {
            DriveBackupManager.backupNow(context);
            return Result.success();
        } catch (SecurityException e) {
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
