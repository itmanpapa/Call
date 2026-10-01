package dummydomain.yetanothercallblocker;

import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs updates of number sources in the background and remembers which sources are
 * being updated. The state is process-wide, so a screen that is closed and opened
 * again during an update still shows the progress; the outcome itself is persisted
 * by the sources (list metadata, PhoneBlock state), see {@link SourceItem}.
 *
 * <p>The YACB database is updated by the {@code TaskService} instead (its state is
 * published with sticky events).</p>
 */
final class SourceTasks {

    /** A background update. */
    interface Task {
        /**
         * @return a short message for the user (e.g. "nothing changed"), or null
         * @throws Exception on failure; the message is shown to the user
         */
        String run() throws Exception;
    }

    /** Outcome of a finished task. Immutable. */
    static final class Outcome {

        private final String notice;
        private final String error;

        Outcome(String notice, String error) {
            this.notice = notice;
            this.error = error;
        }

        /** @return the message of a successful task, or null */
        String getNotice() {
            return notice;
        }

        /** @return the error message of a failed task, or null */
        String getError() {
            return error;
        }
    }

    /** Called on the main thread. */
    interface Listener {
        /**
         * @param outcome null when the task has started
         */
        void onSourceTaskChanged(String sourceId, Outcome outcome);
    }

    private static final Logger LOG = LoggerFactory.getLogger(SourceTasks.class);

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // guarded by SourceTasks.class
    private static final Set<String> RUNNING = new HashSet<>();
    private static final Map<String, String> ERRORS = new HashMap<>();

    // main thread only
    private static final List<Listener> LISTENERS = new ArrayList<>();

    private SourceTasks() {}

    /**
     * Starts the task unless a task for the same source is already running.
     *
     * @return true if the task was started
     */
    static boolean start(String sourceId, Task task) {
        synchronized (SourceTasks.class) {
            if (!RUNNING.add(sourceId)) return false;
            ERRORS.remove(sourceId);
        }
        MAIN_HANDLER.post(() -> notifyListeners(sourceId, null));

        EXECUTOR.execute(() -> {
            String notice = null;
            String error = null;
            try {
                notice = task.run();
            } catch (Exception e) {
                LOG.warn("start() task for {} failed", sourceId, e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }

            synchronized (SourceTasks.class) {
                RUNNING.remove(sourceId);
                if (error != null) ERRORS.put(sourceId, error);
            }
            Outcome outcome = new Outcome(notice, error);
            MAIN_HANDLER.post(() -> notifyListeners(sourceId, outcome));
        });
        return true;
    }

    static synchronized boolean isRunning(String sourceId) {
        return RUNNING.contains(sourceId);
    }

    /**
     * @return the error of the last task of this source in this process, or null
     */
    static synchronized String getLastError(String sourceId) {
        return ERRORS.get(sourceId);
    }

    static void addListener(Listener listener) {
        if (!LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners(String sourceId, Outcome outcome) {
        for (Listener listener : new ArrayList<>(LISTENERS)) {
            listener.onSourceTaskChanged(sourceId, outcome);
        }
    }

}
