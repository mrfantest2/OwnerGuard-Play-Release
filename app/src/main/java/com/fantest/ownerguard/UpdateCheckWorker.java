package com.fantest.ownerguard;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Daily network-constrained check for a newer signed OwnerGuard release. */
public final class UpdateCheckWorker extends Worker {
    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        return AppUpdateManager.backgroundCheck(getApplicationContext()) ? Result.success() : Result.retry();
    }
}
