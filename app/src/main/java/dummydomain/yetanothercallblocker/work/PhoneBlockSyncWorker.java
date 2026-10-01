package dummydomain.yetanothercallblocker.work;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.App;
import dummydomain.yetanothercallblocker.PhoneBlockHelper;
import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockSync;

/**
 * Daily background sync of the PhoneBlock blocklist.
 *
 * <p>PhoneBlock asks clients to sync at most once a day and at randomized times (no
 * fixed "3 am" schedule), so the periodic work gets a random initial delay; the
 * minimum interval between syncs is enforced by {@link PhoneBlockSync}.</p>
 */
public class PhoneBlockSyncWorker extends Worker {

    private static final String WORK_NAME = "phoneBlockSync";

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockSyncWorker.class);

    public PhoneBlockSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    /**
     * Schedules the daily sync if an API key is set, cancels it otherwise.
     *
     * @param replace true to restart the schedule (e.g. after the key was changed)
     */
    public static void updateSchedule(Context context, boolean replace) {
        Settings settings = App.getSettings();
        WorkManager workManager = WorkManager.getInstance(context.getApplicationContext());

        if (TextUtils.isEmpty(settings.getPhoneBlockToken())) {
            LOG.debug("updateSchedule() no token, cancelling");
            workManager.cancelUniqueWork(WORK_NAME);
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        // a random start time spreads the load on the PhoneBlock server over the day
        long initialDelayMinutes = 60 + new Random().nextInt(23 * 60);

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                PhoneBlockSyncWorker.class, 1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                .build();

        workManager.enqueueUniquePeriodicWork(WORK_NAME,
                replace ? ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                        : ExistingPeriodicWorkPolicy.KEEP, request);
        LOG.debug("updateSchedule() scheduled, replace={}", replace);
    }

    @NonNull
    @Override
    public Result doWork() {
        LOG.info("doWork() started");

        if (TextUtils.isEmpty(App.getSettings().getPhoneBlockToken())
                || YacbHolder.getPhoneBlockSync() == null) {
            LOG.info("doWork() no token");
            return Result.success();
        }

        try {
            // also stores the outcome for the "Databases" screen
            PhoneBlockSync.SyncResult result =
                    PhoneBlockHelper.sync(getApplicationContext(), false);
            LOG.info("doWork() finished: {}", result);
            return Result.success();
        } catch (PhoneBlockClient.ApiException e) {
            LOG.warn("doWork() API error", e);
            // an invalid key doesn't get better by retrying; the next period tries again
            return e.isAuthError() ? Result.failure() : Result.retry();
        } catch (IOException e) {
            LOG.warn("doWork() failed", e);
            return Result.retry();
        } catch (Exception e) {
            LOG.error("doWork() error", e);
            return Result.failure();
        }
    }

}
