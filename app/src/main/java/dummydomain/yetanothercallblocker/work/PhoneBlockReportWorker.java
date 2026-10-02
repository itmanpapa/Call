package dummydomain.yetanothercallblocker.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.App;
import dummydomain.yetanothercallblocker.PhoneBlockReports;
import dummydomain.yetanothercallblocker.R;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockReporter;

/**
 * Sends the queued reports of the user's marks to PhoneBlock
 * ({@link PhoneBlockReports}). One-time work that waits for a network connection and
 * is retried with exponential backoff, so reports made offline are sent later; the
 * queue itself is persistent, so nothing is lost if the work is cancelled.
 */
public class PhoneBlockReportWorker extends Worker {

    private static final String WORK_NAME = "phoneBlockReport";

    /**
     * Short delay before sending: an "undo" or the category choice right after the
     * mark replaces the queued report instead of sending two.
     */
    private static final long INITIAL_DELAY_SECONDS = 20;

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockReportWorker.class);

    public PhoneBlockReportWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Schedules (or restarts) the sending. Never throws. */
    public static void schedule(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                    PhoneBlockReportWorker.class)
                    .setConstraints(constraints)
                    .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request);
            LOG.debug("schedule() scheduled");
        } catch (Exception e) {
            // e.g. WorkManager is not available before the first unlock;
            // the queue is sent at the next start of the app
            LOG.warn("schedule() failed", e);
        }
    }

    public static void cancel(Context context) {
        try {
            WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(WORK_NAME);
        } catch (Exception e) {
            LOG.warn("cancel() failed", e);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        LOG.info("doWork() started, attempt {}", getRunAttemptCount());

        if (!PhoneBlockReports.isActive()) {
            LOG.info("doWork() reporting is not active");
            return Result.success();
        }
        PhoneBlockReporter reporter = PhoneBlockReports.getReporter();
        if (reporter == null) {
            LOG.warn("doWork() PhoneBlock is not initialized");
            return Result.retry();
        }

        try {
            PhoneBlockReporter.Result result =
                    reporter.sendPending(App.getSettings().getPhoneBlockToken());
            LOG.info("doWork() finished: {}", result);
            PhoneBlockReports.setLastError(result.isAuthError()
                    ? getApplicationContext().getString(R.string.pbreport_auth_error)
                    : result.getError());

            if (result.isAuthError()) {
                // retrying doesn't help; sent again when the key changes or on a new mark
                return Result.failure();
            }
            return result.shouldRetry() ? Result.retry() : Result.success();
        } catch (Exception e) {
            LOG.error("doWork() error", e);
            return Result.retry();
        }
    }

}
