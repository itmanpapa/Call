package dummydomain.yetanothercallblocker.work;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Schedules the daily update of the number lists imported from a URL
 * ({@link ListsUpdateWorker}). Independent of the YACB database updates
 * ({@link UpdateScheduler}).
 */
public class ListsUpdateScheduler {

    private static final String WORK_TAG = "numberListsUpdateWork";
    private static final String WORK_NAME = "numberListsDailyUpdate";

    private static final Logger LOG = LoggerFactory.getLogger(ListsUpdateScheduler.class);

    private final Context context;

    public static ListsUpdateScheduler get(Context context) {
        return new ListsUpdateScheduler(context);
    }

    private ListsUpdateScheduler(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Schedules or cancels the periodic update.
     *
     * @param needed whether at least one list has automatic updates enabled
     */
    public void update(boolean needed) {
        if (needed) {
            schedule();
        } else {
            cancel();
        }
    }

    /** Schedules the daily update; an already scheduled update is kept as is. */
    public void schedule() {
        LOG.debug("schedule()");

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();

        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(ListsUpdateWorker.class, 1, TimeUnit.DAYS)
                        .addTag(WORK_TAG)
                        .setConstraints(constraints)
                        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public void cancel() {
        LOG.debug("cancel()");
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }

}
