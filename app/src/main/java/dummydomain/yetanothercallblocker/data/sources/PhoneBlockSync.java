package dummydomain.yetanothercallblocker.data.sources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Synchronizes the PhoneBlock community blocklist into an offline number list.
 *
 * <p>Procedure ({@link #sync(String, int, boolean)}):</p>
 * <ol>
 *     <li>the first sync, and then one sync every {@link #FULL_SYNC_INTERVAL_MILLIS}
 *     (to correct a possible drift), downloads the full list; all other syncs request
 *     only the changes since the stored version ({@code ?since=}), as PhoneBlock asks
 *     clients to do (full download at most monthly, incremental at most daily);</li>
 *     <li>the result is applied to the {@link PhoneBlockState} (all published numbers
 *     with their vote buckets), which is saved to a file;</li>
 *     <li>the numbers with at least {@code minVotes} votes are stored as the list
 *     {@link #SOURCE_ID} through the {@link ListImporter} (in the app: the
 *     {@code SourcesManager}, so the existing {@code ListedNumbersProvider} serves
 *     them offline).</li>
 * </ol>
 *
 * <p>Plain Java, no Android dependencies. Thread-safe (all operations are synchronized).</p>
 */
public class PhoneBlockSync {

    /** Source id of the synchronized list. */
    public static final String SOURCE_ID = "phoneblock";
    public static final String DISPLAY_NAME = "PhoneBlock";

    /**
     * Allowed vote thresholds. The server publishes vote buckets 2, 4, 10, 20, 50, 100,
     * but only numbers with at least 10 votes (its default minimum) are in the list.
     */
    public static final int[] MIN_VOTES_OPTIONS = {10, 20, 50, 100};
    public static final int DEFAULT_MIN_VOTES = 10;

    /** A full download is forced after this time. */
    public static final long FULL_SYNC_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(30);

    /**
     * Automatic syncs closer than this to the previous successful sync are skipped
     * (PhoneBlock: incremental update at most once a day; the interval is a bit shorter
     * than a day because WorkManager runs periodic work with some jitter).
     */
    public static final long MIN_AUTO_SYNC_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(20);

    /** Manual syncs closer than this to the previous one are skipped as well. */
    public static final long MIN_MANUAL_SYNC_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockSync.class);

    /** Stores the list of the blocked numbers. */
    public interface ListImporter {
        void importList(String sourceId, String displayName, List<ListedNumber> entries,
                        long importedAt) throws IOException;
    }

    /** Result of a sync. Immutable. */
    public static final class SyncResult {

        private final boolean skipped;
        private final boolean full;
        private final int received;
        private final int removed;
        private final int totalNumbers;
        private final int listedNumbers;
        private final long version;

        SyncResult(boolean skipped, boolean full, int received, int removed,
                   int totalNumbers, int listedNumbers, long version) {
            this.skipped = skipped;
            this.full = full;
            this.received = received;
            this.removed = removed;
            this.totalNumbers = totalNumbers;
            this.listedNumbers = listedNumbers;
            this.version = version;
        }

        /** The sync was skipped because the previous one was too recent. */
        public boolean isSkipped() {
            return skipped;
        }

        /** The full list was downloaded (otherwise an incremental update). */
        public boolean isFull() {
            return full;
        }

        /** Number of added or updated numbers in the response. */
        public int getReceived() {
            return received;
        }

        /** Number of removed numbers. */
        public int getRemoved() {
            return removed;
        }

        /** All numbers in the local copy (any vote count). */
        public int getTotalNumbers() {
            return totalNumbers;
        }

        /** Numbers above the threshold, i.e. in the offline list. */
        public int getListedNumbers() {
            return listedNumbers;
        }

        public long getVersion() {
            return version;
        }

        @Override
        public String toString() {
            return "SyncResult{skipped=" + skipped + ", full=" + full
                    + ", received=" + received + ", removed=" + removed
                    + ", totalNumbers=" + totalNumbers + ", listedNumbers=" + listedNumbers
                    + ", version=" + version + '}';
        }
    }

    /** Summary of the local state for the UI. Immutable. */
    public static final class Status {

        private final long lastSync;
        private final long lastFullSync;
        private final long version;
        private final int totalNumbers;

        Status(long lastSync, long lastFullSync, long version, int totalNumbers) {
            this.lastSync = lastSync;
            this.lastFullSync = lastFullSync;
            this.version = version;
            this.totalNumbers = totalNumbers;
        }

        /** Time of the last successful sync, 0 if never. */
        public long getLastSync() {
            return lastSync;
        }

        public long getLastFullSync() {
            return lastFullSync;
        }

        public long getVersion() {
            return version;
        }

        public int getTotalNumbers() {
            return totalNumbers;
        }
    }

    private final PhoneBlockClient client;
    private final File stateFile;
    private final ListImporter importer;
    private final LongSupplier clock;

    // guarded by this
    private PhoneBlockState state;
    private boolean stateLoaded;

    /**
     * @param client    API client
     * @param stateFile file of the {@link PhoneBlockState}
     * @param importer  receives the list of blocked numbers
     * @param clock     current time in millis (System::currentTimeMillis in the app)
     */
    public PhoneBlockSync(PhoneBlockClient client, File stateFile, ListImporter importer,
                          LongSupplier clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.importer = Objects.requireNonNull(importer, "importer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PhoneBlockClient getClient() {
        return client;
    }

    /**
     * @return the closest allowed threshold (see {@link #MIN_VOTES_OPTIONS})
     */
    public static int normalizeMinVotes(int minVotes) {
        for (int option : MIN_VOTES_OPTIONS) {
            if (minVotes <= option) return option;
        }
        return MIN_VOTES_OPTIONS[MIN_VOTES_OPTIONS.length - 1];
    }

    /**
     * Synchronizes the list.
     *
     * @param token    PhoneBlock API key
     * @param minVotes vote threshold for the offline list
     * @param manual   true for a sync requested by the user (shorter minimum interval)
     * @return the result; {@link SyncResult#isSkipped()} if the previous sync was too recent
     * @throws IOException on network, API ({@link PhoneBlockClient.ApiException}) or storage errors;
     *                     the previous list and state are kept then
     */
    public synchronized SyncResult sync(String token, int minVotes, boolean manual)
            throws IOException {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("No PhoneBlock API key");
        }
        minVotes = normalizeMinVotes(minVotes);

        long now = clock.getAsLong();
        PhoneBlockState current = getState();

        long minInterval = manual ? MIN_MANUAL_SYNC_INTERVAL_MILLIS : MIN_AUTO_SYNC_INTERVAL_MILLIS;
        if (current != null && current.getLastSync() > 0 && current.getLastSync() <= now
                && now - current.getLastSync() < minInterval) {
            LOG.debug("sync() skipped, last sync at {}", current.getLastSync());
            return new SyncResult(true, false, 0, 0, current.size(),
                    current.toListedNumbers(minVotes).size(), current.getVersion());
        }

        boolean full = current == null || current.getVersion() <= 0
                || current.getLastFullSync() <= 0
                || now - current.getLastFullSync() >= FULL_SYNC_INTERVAL_MILLIS
                || now < current.getLastFullSync(); // clock went back

        LOG.info("sync() starting {} sync", full ? "full" : "incremental");

        PhoneBlockClient.Blocklist blocklist = client.fetchBlocklist(token,
                full ? 0 : current.getVersion());

        // work on a copy so that a failure keeps the old state
        PhoneBlockState updated = full ? new PhoneBlockState() : new PhoneBlockState(current);
        PhoneBlockState.ApplyResult applied = full
                ? updated.applyFull(blocklist, now)
                : updated.applyUpdate(blocklist, now);

        List<ListedNumber> listed = updated.toListedNumbers(minVotes);

        updated.save(stateFile);
        state = updated;

        importer.importList(SOURCE_ID, DISPLAY_NAME, listed, now);

        SyncResult result = new SyncResult(false, full, applied.addedOrUpdated,
                full ? 0 : applied.removed, updated.size(), listed.size(),
                updated.getVersion());
        LOG.info("sync() finished: {}", result);
        return result;
    }

    /**
     * Rebuilds the offline list from the local copy with a new threshold (no network).
     *
     * @return the number of listed numbers, or -1 if there is no local copy yet
     */
    public synchronized int rebuildList(int minVotes) throws IOException {
        PhoneBlockState current = getState();
        if (current == null) return -1;

        List<ListedNumber> listed = current.toListedNumbers(normalizeMinVotes(minVotes));
        importer.importList(SOURCE_ID, DISPLAY_NAME, listed, current.getLastSync());
        return listed.size();
    }

    /**
     * Forgets the local copy (e.g. after the list was deleted): the next sync
     * downloads the full list.
     */
    public synchronized void reset() {
        state = null;
        stateLoaded = true;
        if (stateFile.exists() && !stateFile.delete()) {
            LOG.warn("reset() failed to delete {}", stateFile);
        }
    }

    /**
     * @return the state summary; "never synced" if there is no local copy
     */
    public synchronized Status getStatus() {
        PhoneBlockState current = getState();
        if (current == null) return new Status(0, 0, -1, 0);
        return new Status(current.getLastSync(), current.getLastFullSync(),
                current.getVersion(), current.size());
    }

    /** Must be called with the lock held. */
    private PhoneBlockState getState() {
        if (!stateLoaded) {
            try {
                state = PhoneBlockState.load(stateFile);
            } catch (IOException e) {
                // a full download recreates it
                LOG.warn("getState() failed to load {}", stateFile, e);
                state = null;
            }
            stateLoaded = true;
        }
        return state;
    }

}
