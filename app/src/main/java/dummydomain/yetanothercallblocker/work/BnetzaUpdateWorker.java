package dummydomain.yetanothercallblocker.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.sources.BnetzaAutoUpdater;

/**
 * Background update of the Bundesnetzagentur "Maßnahmenliste"
 * ({@link BnetzaAutoUpdater}): weekly while the source is enabled, plus a one-time
 * download on the first start and on request ({@link #runNow(Context)}).
 *
 * <p>Errors are recorded in the list metadata by the updater; a failed attempt is
 * retried a few times with backoff, then the job waits for the next period.</p>
 */
public class BnetzaUpdateWorker extends Worker {

    static final String PERIODIC_WORK_NAME = "bnetzaWeeklyUpdate";
    static final String INITIAL_WORK_NAME = "bnetzaInitialDownload";
    static final String MANUAL_WORK_NAME = "bnetzaUpdateNow";
    static final String WORK_TAG = "bnetzaUpdateWork";

    /** Input: download everything without conditional requests. */
    static final String KEY_FORCE = "force";
    /** Input: started by the user, runs even if the source is disabled. */
    static final String KEY_MANUAL = "manual";

    static final long PERIOD_DAYS = 7;
    static final int MAX_ATTEMPTS = 3;

    private static final Logger LOG = LoggerFactory.getLogger(BnetzaUpdateWorker.class);

    public BnetzaUpdateWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    /**
     * Schedules the weekly update if the source is enabled (keeping an existing
     * schedule), cancels it otherwise; also starts the first download if no list is
     * stored yet. Cheap: no list loading, safe to call from {@code Application.onCreate}.
     */
    public static void updateSchedule(Context context) {
        BnetzaAutoUpdater updater = YacbHolder.getBnetzaAutoUpdater();
        if (updater == null) {
            LOG.warn("updateSchedule() not initialized");
            return;
        }

        WorkManager workManager = WorkManager.getInstance(context.getApplicationContext());

        if (!updater.isEnabled()) {
            LOG.debug("updateSchedule() source disabled, cancelling");
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME);
            workManager.cancelUniqueWork(INITIAL_WORK_NAME);
            return;
        }

        // the first periodic run is a week later; the first download is the one-time work
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                BnetzaUpdateWorker.class, PERIOD_DAYS, TimeUnit.DAYS)
                .addTag(WORK_TAG)
                .setConstraints(constraints(true))
                .setInitialDelay(PERIOD_DAYS, TimeUnit.DAYS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build();
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, periodic);

        if (updater.needsInitialDownload()) {
            LOG.debug("updateSchedule() no list yet, enqueuing the first download");
            workManager.enqueueUniqueWork(INITIAL_WORK_NAME, ExistingWorkPolicy.KEEP,
                    oneTimeRequest(false, false));
        }
    }

    /** Updates the list as soon as the network is available (conditional requests). */
    public static void runNow(Context context) {
        runNow(context, false);
    }

    /**
     * Updates the list as soon as the network is available, even if the source is
     * disabled. A pending "run now" request is replaced.
     *
     * @param force true to download everything without conditional requests
     */
    public static void runNow(Context context, boolean force) {
        LOG.debug("runNow() force={}", force);
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                MANUAL_WORK_NAME, ExistingWorkPolicy.REPLACE, oneTimeRequest(force, true));
    }

    /** Cancels all scheduled and pending updates. */
    public static void cancel(Context context) {
        WorkManager workManager = WorkManager.getInstance(context.getApplicationContext());
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME);
        workManager.cancelUniqueWork(INITIAL_WORK_NAME);
        workManager.cancelUniqueWork(MANUAL_WORK_NAME);
    }

    private static OneTimeWorkRequest oneTimeRequest(boolean force, boolean manual) {
        Data input = new Data.Builder()
                .putBoolean(KEY_FORCE, force)
                .putBoolean(KEY_MANUAL, manual)
                .build();
        return new OneTimeWorkRequest.Builder(BnetzaUpdateWorker.class)
                .addTag(WORK_TAG)
                .setConstraints(constraints(!manual))
                .setInputData(input)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build();
    }

    private static Constraints constraints(boolean batteryNotLow) {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(batteryNotLow)
                .build();
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean force = getInputData().getBoolean(KEY_FORCE, false);
        boolean manual = getInputData().getBoolean(KEY_MANUAL, false);
        LOG.info("doWork() started, force={}, manual={}, attempt={}",
                force, manual, getRunAttemptCount());

        BnetzaAutoUpdater updater = YacbHolder.getBnetzaAutoUpdater();
        if (updater == null) {
            LOG.warn("doWork() not initialized");
            return Result.success();
        }

        try {
            if (!manual && !updater.isEnabled()) {
                LOG.info("doWork() source disabled, skipping");
                return Result.success();
            }

            BnetzaAutoUpdater.Result result = updater.update(force);
            LOG.info("doWork() finished: {}", result);

            if (!result.isSuccess() && getRunAttemptCount() + 1 < MAX_ATTEMPTS) {
                return Result.retry();
            }
        } catch (Exception e) {
            LOG.error("doWork() error", e);
        }
        return Result.success();
    }

}
