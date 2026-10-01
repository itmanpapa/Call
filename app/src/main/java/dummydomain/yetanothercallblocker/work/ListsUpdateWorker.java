package dummydomain.yetanothercallblocker.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import dummydomain.yetanothercallblocker.data.RemoteListManager;
import dummydomain.yetanothercallblocker.data.YacbHolder;

/**
 * Periodic job that refreshes the number lists imported from a URL with automatic
 * updates enabled. Errors are recorded per list (and shown on the sources screen),
 * so the job always succeeds and simply runs again the next day.
 */
public class ListsUpdateWorker extends Worker {

    private static final Logger LOG = LoggerFactory.getLogger(ListsUpdateWorker.class);

    public ListsUpdateWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        LOG.info("doWork() started");

        try {
            RemoteListManager manager = YacbHolder.getRemoteListManager();
            if (manager == null) {
                LOG.warn("doWork() not initialized");
                return Result.success();
            }

            if (!manager.hasAutoUpdateLists()) {
                LOG.info("doWork() no lists with automatic updates, cancelling");
                ListsUpdateScheduler.get(getApplicationContext()).cancel();
                return Result.success();
            }

            List<RemoteListManager.UpdateResult> results = manager.updateAutoUpdateLists();
            for (RemoteListManager.UpdateResult result : results) {
                LOG.info("doWork() {}", result);
            }
        } catch (Exception e) {
            LOG.error("doWork() error", e);
        }

        LOG.info("doWork() finished");
        return Result.success();
    }

}
