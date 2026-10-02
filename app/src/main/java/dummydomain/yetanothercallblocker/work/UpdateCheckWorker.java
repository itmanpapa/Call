package dummydomain.yetanothercallblocker.work;

import android.content.Context;

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

import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.AppUpdateManager;
import dummydomain.yetanothercallblocker.UpdateNotifications;
import dummydomain.yetanothercallblocker.data.update.UpdateChecker;

/**
 * Daily check for a new app release on GitHub. Stores the time of the check and the
 * latest version (see {@link AppUpdateManager}) and notifies once per new version.
 * Runs only while the "check for updates" setting is on and never in debug builds.
 */
public class UpdateCheckWorker extends Worker {

    private static final String WORK_TAG = "appUpdateCheckWork";
    private static final String WORK_NAME = "appUpdateDailyCheck";

    private static final Logger LOG = LoggerFactory.getLogger(UpdateCheckWorker.class);

    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    /** Schedules or cancels the daily check according to the current setting. */
    public static void updateSchedule(Context context) {
        updateSchedule(context, AppUpdateManager.get(context).isCheckEnabled());
    }

    /**
     * Schedules or cancels the daily check.
     *
     * @param enabled the (new) value of the "check for updates" setting
     */
    public static void updateSchedule(Context context, boolean enabled) {
        WorkManager workManager = WorkManager.getInstance(context.getApplicationContext());
        if (!enabled || !AppUpdateManager.isAutoCheckAllowed()) {
            LOG.debug("updateSchedule() cancel");
            workManager.cancelUniqueWork(WORK_NAME);
            return;
        }

        LOG.debug("updateSchedule() schedule");
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(UpdateCheckWorker.class, 1, TimeUnit.DAYS)
                        .addTag(WORK_TAG)
                        .setConstraints(constraints)
                        .setInitialDelay(1, TimeUnit.HOURS)
                        .build();

        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                request);
    }

    @NonNull
    @Override
    public Result doWork() {
        LOG.info("doWork() started");

        Context context = getApplicationContext();
        AppUpdateManager manager = AppUpdateManager.get(context);
        if (!manager.isAutoCheckActive()) {
            LOG.info("doWork() automatic checks are off");
            return Result.success();
        }

        try {
            UpdateChecker.Result result = manager.check();
            if (result.isNotificationDue()) {
                String version = result.getRelease().getVersion();
                if (UpdateNotifications.showUpdateAvailable(context, version)) {
                    manager.markNotified(version);
                }
            }
        } catch (Exception e) {
            // the next periodic run tries again
            LOG.warn("doWork() check failed", e);
        }

        LOG.info("doWork() finished");
        return Result.success();
    }

}
