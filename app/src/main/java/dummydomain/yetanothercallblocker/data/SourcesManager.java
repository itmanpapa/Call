package dummydomain.yetanothercallblocker.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;

import dummydomain.yetanothercallblocker.data.provider.AggregatedResult;
import dummydomain.yetanothercallblocker.data.provider.ListedNumbersProvider;
import dummydomain.yetanothercallblocker.data.provider.NumberInfoProvider;
import dummydomain.yetanothercallblocker.data.provider.ProviderAggregator;
import dummydomain.yetanothercallblocker.data.provider.ProviderResult;
import dummydomain.yetanothercallblocker.data.provider.ResultCache;
import dummydomain.yetanothercallblocker.data.sources.ListedNumber;
import dummydomain.yetanothercallblocker.data.sources.NumberListIndex;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;

/**
 * Owns all number information sources: the built-in providers (the YACB database)
 * and the imported offline lists (Bundesnetzagentur list, user CSV lists).
 *
 * <p>Responsibilities:</p>
 * <ul>
 *     <li>loading the stored lists into {@link ListedNumbersProvider}s (lazily, on the
 *     first access, or explicitly with {@link #loadLists()});</li>
 *     <li>the user-defined order and the enabled flags of the sources, persisted
 *     through {@link Preferences};</li>
 *     <li>importing and deleting lists;</li>
 *     <li>building a {@link ProviderAggregator} from the enabled sources.</li>
 * </ul>
 *
 * <p>The default order is: built-in providers first, then imported lists in the
 * order of their ids. Sources are enabled by default; only the ids of disabled
 * sources are stored, so newly added sources start enabled. Built-in providers whose
 * {@link NumberInfoProvider#isEnabledByDefault()} is false (online services) start
 * disabled; for them the ids of the sources enabled by the user are stored.</p>
 *
 * <p>Online providers are queried only with an executor, see
 * {@link #lookupOnline(String)}.</p>
 *
 * <p>Plain Java, no Android dependencies. Thread-safe: modifications are synchronized,
 * lookups read an immutable snapshot without locking.</p>
 */
public class SourcesManager {

    /**
     * Persistent storage for the source settings (implemented by the app settings).
     * Values are comma-separated source ids; source ids never contain commas.
     */
    public interface Preferences {

        /** @return comma-separated source ids in the user-defined order, or null */
        String getSourcesOrder();

        void setSourcesOrder(String order);

        /** @return comma-separated ids of disabled sources, or null */
        String getDisabledSources();

        void setDisabledSources(String ids);

        /**
         * @return comma-separated ids of sources that are disabled by default
         * ({@link NumberInfoProvider#isEnabledByDefault()}) but were enabled by the user,
         * or null
         */
        default String getEnabledSources() {
            return null;
        }

        default void setEnabledSources(String ids) {
            throw new UnsupportedOperationException("setEnabledSources");
        }

    }

    /** Information about a single source for the UI. Immutable. */
    public static final class SourceInfo {

        private final NumberInfoProvider provider;
        private final boolean imported;
        private final boolean enabled;
        private final NumberListStore.ListMetadata metadata;
        private final boolean loadFailed;

        SourceInfo(NumberInfoProvider provider, boolean imported, boolean enabled,
                   NumberListStore.ListMetadata metadata, boolean loadFailed) {
            this.provider = provider;
            this.imported = imported;
            this.enabled = enabled;
            this.metadata = metadata;
            this.loadFailed = loadFailed;
        }

        public String getId() {
            return provider.getId();
        }

        public String getDisplayName() {
            return provider.getDisplayName();
        }

        public NumberInfoProvider getProvider() {
            return provider;
        }

        public boolean isOffline() {
            return provider.isOffline();
        }

        /** @return true for imported lists (which can be deleted), false for built-in sources */
        public boolean isImported() {
            return imported;
        }

        public boolean isEnabled() {
            return enabled;
        }

        /** @return metadata of an imported list, null for built-in sources or unreadable files */
        public NumberListStore.ListMetadata getMetadata() {
            return metadata;
        }

        /** @return true if the stored list could not be loaded (the source is empty) */
        public boolean isLoadFailed() {
            return loadFailed;
        }

        @Override
        public String toString() {
            return "SourceInfo{id='" + getId() + "', imported=" + imported
                    + ", enabled=" + enabled + ", metadata=" + metadata
                    + ", loadFailed=" + loadFailed + '}';
        }
    }

    /** Immutable state used by lookups. */
    private static final class Snapshot {

        final List<SourceInfo> sources;
        final List<ListedNumbersProvider> enabledLists;
        final ProviderAggregator aggregator;
        /** Aggregator over the enabled online providers only, null if there are none. */
        final ProviderAggregator onlineAggregator;

        Snapshot(List<SourceInfo> sources, List<ListedNumbersProvider> enabledLists,
                 ProviderAggregator aggregator, ProviderAggregator onlineAggregator) {
            this.sources = Collections.unmodifiableList(sources);
            this.enabledLists = Collections.unmodifiableList(enabledLists);
            this.aggregator = aggregator;
            this.onlineAggregator = onlineAggregator;
        }
    }

    /** Source id of the Bundesnetzagentur list; a new import replaces the old list. */
    public static final String BNETZA_SOURCE_ID = "bnetza";
    public static final String BNETZA_DISPLAY_NAME = "Bundesnetzagentur";

    static final String CSV_SOURCE_ID_PREFIX = "csv_";
    static final String CSV_SOURCE_ID_FALLBACK = CSV_SOURCE_ID_PREFIX + "list";

    /** Max length of a source id accepted by {@link NumberListStore}. */
    static final int MAX_SOURCE_ID_LENGTH = 64;

    private static final String SEPARATOR = ",";

    private static final Logger LOG = LoggerFactory.getLogger(SourcesManager.class);

    private final NumberListStore store;
    private final Preferences preferences;
    private final List<NumberInfoProvider> builtInProviders;
    private final ExecutorService onlineExecutor;
    private final ResultCache onlineCache;
    private final long onlineTimeoutMillis;

    // guarded by this
    private final Map<String, ListedNumbersProvider> listProviders = new LinkedHashMap<>();
    private final Map<String, NumberListStore.ListMetadata> listMetadata = new HashMap<>();
    private final Set<String> failedLists = new HashSet<>();
    private final Set<String> disabled = new TreeSet<>();
    /** Default-disabled sources enabled by the user. */
    private final Set<String> enabledOverrides = new TreeSet<>();

    private volatile boolean loaded;
    private volatile Snapshot snapshot;

    /**
     * @param store            storage of the imported lists
     * @param preferences      storage of the order and the enabled flags
     * @param builtInProviders built-in providers (the YACB database), in default order;
     *                         they are always listed before imported lists by default
     */
    public SourcesManager(NumberListStore store, Preferences preferences,
                          List<? extends NumberInfoProvider> builtInProviders) {
        this(store, preferences, builtInProviders, null, null,
                ProviderAggregator.DEFAULT_ONLINE_TIMEOUT_MILLIS);
    }

    /**
     * @param store               storage of the imported lists
     * @param preferences         storage of the order and the enabled flags
     * @param builtInProviders    built-in providers in default order (offline and online);
     *                            they are always listed before imported lists by default
     * @param onlineExecutor      executor for online lookups; without it (null) online
     *                            providers are listed but never queried
     * @param onlineCache         cache of online results, may be null
     * @param onlineTimeoutMillis how long to wait for online providers
     */
    public SourcesManager(NumberListStore store, Preferences preferences,
                          List<? extends NumberInfoProvider> builtInProviders,
                          ExecutorService onlineExecutor, ResultCache onlineCache,
                          long onlineTimeoutMillis) {
        this.onlineExecutor = onlineExecutor;
        this.onlineCache = onlineCache;
        this.onlineTimeoutMillis = onlineTimeoutMillis;
        this.store = Objects.requireNonNull(store, "store");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.builtInProviders = Collections.unmodifiableList(new ArrayList<>(builtInProviders));

        Set<String> ids = new HashSet<>();
        for (NumberInfoProvider provider : this.builtInProviders) {
            if (!ids.add(provider.getId())) {
                throw new IllegalArgumentException("Duplicate provider id: " + provider.getId());
            }
        }

        disabled.addAll(parseIds(preferences.getDisabledSources()));
        enabledOverrides.addAll(parseIds(preferences.getEnabledSources()));
        // a usable (list-less) snapshot until the lists are loaded
        snapshot = buildSnapshot();
    }

    /**
     * Loads all stored lists. Called on the first access automatically; can be
     * called in advance from a background thread. Does nothing if already loaded.
     * A list that can't be read is kept as an empty source (so that it can be deleted).
     */
    public void loadLists() {
        if (loaded) return;

        synchronized (this) {
            if (loaded) return;

            long start = System.nanoTime();
            for (String id : store.listSourceIds()) {
                if (isBuiltIn(id)) {
                    LOG.warn("loadLists() ignoring list with a reserved id: {}", id);
                    continue;
                }

                try {
                    NumberListStore.StoredList list = store.load(id);
                    if (list == null) continue; // deleted concurrently

                    NumberListStore.ListMetadata metadata = list.getMetadata();
                    listProviders.put(id, createProvider(id, metadata.getDisplayName(),
                            new NumberListIndex(list.getEntries())));
                    listMetadata.put(id, metadata);
                } catch (IOException | RuntimeException e) {
                    LOG.warn("loadLists() failed to load list {}", id, e);
                    NumberListStore.ListMetadata metadata = null;
                    try {
                        metadata = store.loadMetadata(id);
                    } catch (IOException | RuntimeException e2) {
                        LOG.debug("loadLists() failed to load metadata of {}", id, e2);
                    }
                    listProviders.put(id, createProvider(id,
                            metadata != null ? metadata.getDisplayName() : null, null));
                    if (metadata != null) listMetadata.put(id, metadata);
                    failedLists.add(id);
                }
            }

            snapshot = buildSnapshot();
            loaded = true;

            LOG.debug("loadLists() loaded {} lists in {} ms", listProviders.size(),
                    (System.nanoTime() - start) / 1_000_000);
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * @return all sources in the effective order
     */
    public List<SourceInfo> getSources() {
        loadLists();
        return snapshot.sources;
    }

    /**
     * @return the source with the given id, or null
     */
    public SourceInfo getSource(String id) {
        for (SourceInfo source : getSources()) {
            if (source.getId().equals(id)) return source;
        }
        return null;
    }

    /**
     * @return the display name of the source, or null if there is no such source
     */
    public String getDisplayName(String id) {
        SourceInfo source = getSource(id);
        return source != null ? source.getDisplayName() : null;
    }

    /**
     * Doesn't require the lists to be loaded.
     *
     * @return whether the source is enabled (unknown sources are enabled by default)
     */
    public synchronized boolean isEnabled(String id) {
        return isEnabledLocked(id);
    }

    public void setEnabled(String id, boolean enabled) {
        loadLists();
        synchronized (this) {
            if (isDisabledByDefault(id)) {
                boolean changed = enabled ? enabledOverrides.add(id) : enabledOverrides.remove(id);
                if (!changed) return;
                preferences.setEnabledSources(joinIds(enabledOverrides));
            } else {
                boolean changed = enabled ? disabled.remove(id) : disabled.add(id);
                if (!changed) return;
                preferences.setDisabledSources(joinIds(disabled));
            }
            snapshot = buildSnapshot();
        }
    }

    /**
     * Moves the source one position up (towards higher priority).
     *
     * @return true if the source was moved
     */
    public boolean moveUp(String id) {
        return move(id, -1);
    }

    /**
     * Moves the source one position down (towards lower priority).
     *
     * @return true if the source was moved
     */
    public boolean moveDown(String id) {
        return move(id, 1);
    }

    private boolean move(String id, int delta) {
        loadLists();
        synchronized (this) {
            List<String> order = computeOrder();
            int index = order.indexOf(id);
            int newIndex = index + delta;
            if (index < 0 || newIndex < 0 || newIndex >= order.size()) return false;

            Collections.swap(order, index, newIndex);
            preferences.setSourcesOrder(joinIds(order));
            snapshot = buildSnapshot();
            return true;
        }
    }

    /**
     * Stores the list and makes it available as a source. An existing list with
     * the same id is replaced (keeping its position and enabled state); a new list
     * is enabled and added at the end.
     *
     * @return metadata of the stored list
     * @throws IOException if the list could not be stored; the old list is kept then
     */
    public NumberListStore.ListMetadata importList(String sourceId, String displayName,
                                                  List<ListedNumber> entries,
                                                  long importedAt) throws IOException {
        Objects.requireNonNull(sourceId, "sourceId");
        if (isBuiltIn(sourceId)) {
            throw new IllegalArgumentException("Reserved source id: " + sourceId);
        }

        loadLists();
        synchronized (this) {
            NumberListIndex index = new NumberListIndex(entries);
            NumberListStore.ListMetadata metadata
                    = store.save(sourceId, displayName, importedAt, entries);

            boolean isNew = !listProviders.containsKey(sourceId);
            // LinkedHashMap keeps the original position for an existing key
            listProviders.put(sourceId, createProvider(sourceId, displayName, index));
            listMetadata.put(sourceId, metadata);
            failedLists.remove(sourceId);

            if (isNew && disabled.remove(sourceId)) {
                // a stale flag of an earlier list with the same id
                preferences.setDisabledSources(joinIds(disabled));
            }

            snapshot = buildSnapshot();

            LOG.info("importList() imported {} entries into {}", entries.size(), sourceId);
            return metadata;
        }
    }

    /**
     * Deletes an imported list.
     *
     * @return true if the list existed
     * @throws IllegalArgumentException for built-in sources
     */
    public boolean deleteList(String sourceId) {
        if (isBuiltIn(sourceId)) {
            throw new IllegalArgumentException("Built-in sources can't be deleted: " + sourceId);
        }

        loadLists();
        synchronized (this) {
            boolean existed = listProviders.remove(sourceId) != null;
            listMetadata.remove(sourceId);
            failedLists.remove(sourceId);
            boolean deleted = store.delete(sourceId);

            if (disabled.remove(sourceId)) {
                preferences.setDisabledSources(joinIds(disabled));
            }
            List<String> storedOrder = parseIds(preferences.getSourcesOrder());
            if (storedOrder.remove(sourceId)) {
                preferences.setSourcesOrder(joinIds(storedOrder));
            }

            snapshot = buildSnapshot();

            LOG.info("deleteList() deleted {}: existed={}, fileDeleted={}",
                    sourceId, existed, deleted);
            return existed || deleted;
        }
    }

    /**
     * @return an aggregator over the enabled sources in the effective order
     */
    public ProviderAggregator getAggregator() {
        loadLists();
        return snapshot.aggregator;
    }

    /**
     * Synchronously looks up the number in the enabled imported lists, in order.
     *
     * @param number the normalized number
     * @return the first {@link ProviderResult.Rating#NEGATIVE} result, or null
     */
    public ProviderResult lookupListedNumbers(String number) {
        if (number == null || number.isEmpty()) return null;

        loadLists();
        for (ListedNumbersProvider provider : snapshot.enabledLists) {
            try {
                ProviderResult result = provider.lookup(number);
                if (result != null && result.getRating() == ProviderResult.Rating.NEGATIVE) {
                    LOG.debug("lookupListedNumbers() found in {}: {}", provider.getId(), result);
                    return result;
                }
            } catch (RuntimeException e) {
                LOG.warn("lookupListedNumbers() lookup in {} failed", provider.getId(), e);
            }
        }
        return null;
    }

    /**
     * @return true if at least one online source is enabled (and can be queried)
     */
    public boolean hasEnabledOnlineSources() {
        loadLists();
        return snapshot.onlineAggregator != null;
    }

    /**
     * Looks up the number in the enabled online sources (waiting at most the online
     * timeout; answers are cached). Blocks the calling thread, but never longer than
     * the timeout.
     *
     * @param number the normalized number
     * @return the result of the source that rated the number NEGATIVE, or null
     */
    public ProviderResult lookupOnline(String number) {
        if (number == null || number.isEmpty()) return null;

        loadLists();
        ProviderAggregator aggregator = snapshot.onlineAggregator;
        if (aggregator == null) return null;

        AggregatedResult result = aggregator.lookup(number);
        if (result.getRating() != ProviderResult.Rating.NEGATIVE) return null;

        for (ProviderResult r : result.getResults()) {
            if (r.getSourceId().equals(result.getRatingSourceId())) {
                LOG.debug("lookupOnline() found in {}: {}", r.getSourceId(), r);
                return r;
            }
        }
        return null;
    }

    /**
     * Creates a source id for a CSV list from its display name (usually the file name),
     * so that re-importing a file with the same name replaces the old list.
     */
    public static String csvSourceId(String displayName) {
        if (displayName == null) return CSV_SOURCE_ID_FALLBACK;

        String lower = displayName.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean lossy = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-') {
                sb.append(c);
            } else {
                if (c != '_' && c != ' ') lossy = true;
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.setLength(sb.length() - 1);
        }

        String suffix = "";
        if (lossy) {
            // keep names that differ only in non-ASCII characters apart
            suffix = "_" + Integer.toHexString(displayName.trim().hashCode());
        }

        int maxBody = MAX_SOURCE_ID_LENGTH - CSV_SOURCE_ID_PREFIX.length() - suffix.length();
        String body = sb.length() > maxBody ? sb.substring(0, maxBody) : sb.toString();
        if (body.isEmpty() && suffix.isEmpty()) return CSV_SOURCE_ID_FALLBACK;
        if (body.isEmpty()) suffix = suffix.substring(1);

        return CSV_SOURCE_ID_PREFIX + body + suffix;
    }

    /**
     * Removes a ".csv" / ".txt" extension from a file name to use it as a list name.
     */
    public static String fileNameToDisplayName(String fileName) {
        if (fileName == null) return null;
        String name = fileName.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".csv", ".txt", ".tsv"}) {
            if (lower.endsWith(ext) && name.length() > ext.length()) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name.isEmpty() ? null : name;
    }

    // internals

    private ListedNumbersProvider createProvider(String id, String displayName,
                                                 NumberListIndex index) {
        return new ListedNumbersProvider(id, displayName, true, index);
    }

    /** Must be called with the lock held. */
    private boolean isEnabledLocked(String id) {
        return isDisabledByDefault(id) ? enabledOverrides.contains(id) : !disabled.contains(id);
    }

    private boolean isDisabledByDefault(String id) {
        for (NumberInfoProvider provider : builtInProviders) {
            if (provider.getId().equals(id)) return !provider.isEnabledByDefault();
        }
        return false;
    }

    private boolean isBuiltIn(String id) {
        for (NumberInfoProvider provider : builtInProviders) {
            if (provider.getId().equals(id)) return true;
        }
        return false;
    }

    /** Must be called with the lock held. */
    private List<String> computeOrder() {
        List<String> defaultOrder = new ArrayList<>();
        for (NumberInfoProvider provider : builtInProviders) defaultOrder.add(provider.getId());
        defaultOrder.addAll(listProviders.keySet());

        Set<String> result = new LinkedHashSet<>();
        for (String id : parseIds(preferences.getSourcesOrder())) {
            if (defaultOrder.contains(id)) result.add(id);
        }
        result.addAll(defaultOrder);

        return new ArrayList<>(result);
    }

    /** Must be called with the lock held. */
    private Snapshot buildSnapshot() {
        List<SourceInfo> sources = new ArrayList<>();
        List<ListedNumbersProvider> enabledLists = new ArrayList<>();
        List<NumberInfoProvider> enabledProviders = new ArrayList<>();
        List<NumberInfoProvider> enabledOnline = new ArrayList<>();

        for (String id : computeOrder()) {
            NumberInfoProvider provider = null;
            for (NumberInfoProvider p : builtInProviders) {
                if (p.getId().equals(id)) provider = p;
            }

            ListedNumbersProvider listProvider = listProviders.get(id);
            boolean imported = provider == null;
            if (imported) provider = listProvider;
            if (provider == null) continue;

            boolean enabled = isEnabledLocked(id);
            sources.add(new SourceInfo(provider, imported, enabled,
                    listMetadata.get(id), failedLists.contains(id)));

            if (!enabled) continue;
            if (!provider.isOffline()) {
                if (onlineExecutor == null) {
                    LOG.debug("buildSnapshot() no executor for online source {}", id);
                    continue;
                }
                enabledOnline.add(provider);
            }
            enabledProviders.add(provider);
            if (listProvider != null) enabledLists.add(listProvider);
        }

        ProviderAggregator aggregator = new ProviderAggregator(enabledProviders,
                onlineExecutor, onlineCache, onlineTimeoutMillis);
        ProviderAggregator onlineAggregator = enabledOnline.isEmpty() ? null
                : new ProviderAggregator(enabledOnline, onlineExecutor, onlineCache,
                onlineTimeoutMillis);

        return new Snapshot(sources, enabledLists, aggregator, onlineAggregator);
    }

    static List<String> parseIds(String s) {
        List<String> ids = new ArrayList<>();
        if (s == null) return ids;
        for (String part : s.split(SEPARATOR)) {
            String id = part.trim();
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    static String joinIds(Iterable<String> ids) {
        return String.join(SEPARATOR, ids);
    }

}
