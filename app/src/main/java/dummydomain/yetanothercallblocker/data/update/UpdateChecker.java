package dummydomain.yetanothercallblocker.data.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Checks GitHub for a newer release and remembers the outcome (time of the last check,
 * the latest version found, the version the user was already notified about).
 * Plain Java; the storage and the client are injected.
 */
public class UpdateChecker {

    /** Persistent state of the update check. */
    public interface Store {
        /** @return time of the last successful check, millis since the epoch, 0 if never */
        long getLastCheckTime();

        void setLastCheckTime(long time);

        /** @return the version of the latest release found, or null */
        String getLatestVersion();

        void setLatestVersion(String version);

        /** @return the version the user was last notified about, or null */
        String getNotifiedVersion();

        void setNotifiedVersion(String version);
    }

    /** Outcome of a check. Immutable. */
    public static final class Result {

        private final ReleaseInfo release;
        private final boolean updateAvailable;
        private final boolean notificationDue;

        Result(ReleaseInfo release, boolean updateAvailable, boolean notificationDue) {
            this.release = release;
            this.updateAvailable = updateAvailable;
            this.notificationDue = notificationDue;
        }

        /** @return the latest release, null if none is published */
        public ReleaseInfo getRelease() {
            return release;
        }

        /** @return true if the latest release is newer than the installed version */
        public boolean isUpdateAvailable() {
            return updateAvailable;
        }

        /**
         * @return true if an update is available and the user wasn't notified about
         * this version yet
         */
        public boolean isNotificationDue() {
            return notificationDue;
        }

        @Override
        public String toString() {
            return "Result{release=" + release + ", updateAvailable=" + updateAvailable
                    + ", notificationDue=" + notificationDue + '}';
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(UpdateChecker.class);

    private final GitHubReleaseClient client;
    private final Store store;
    private final String currentVersion;
    private final LongSupplier clock;

    /**
     * @param currentVersion the installed version ({@code BuildConfig.VERSION_NAME})
     */
    public UpdateChecker(GitHubReleaseClient client, Store store, String currentVersion,
                         LongSupplier clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.store = Objects.requireNonNull(store, "store");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    /**
     * Asks GitHub for the latest release and records the result.
     * Must not be called on the Android main thread.
     */
    public Result check() throws IOException {
        ReleaseInfo release = client.fetchLatestRelease();

        store.setLastCheckTime(clock.getAsLong());
        store.setLatestVersion(release != null ? release.getVersion() : null);

        boolean available = release != null && release.isNewerThan(currentVersion);
        boolean notify = available
                && !release.getVersion().equals(store.getNotifiedVersion());

        Result result = new Result(release, available, notify);
        LOG.info("check() current={}, {}", currentVersion, result);
        return result;
    }

    /** Remembers that the user was notified about the version. */
    public void markNotified(String version) {
        store.setNotifiedVersion(version);
    }

    /**
     * @return the version of the update found by the last check, or null if the last
     * check found nothing newer than the installed version (no network access)
     */
    public static String getKnownUpdate(Store store, String currentVersion) {
        String latest = store.getLatestVersion();
        return latest != null && AppVersion.isNewer(latest, currentVersion) ? latest : null;
    }

}
