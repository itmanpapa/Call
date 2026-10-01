package dummydomain.yetanothercallblocker.data.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Thread-safe in-memory {@link ResultCache} with a fixed time-to-live and
 * a bounded number of entries (least recently used entries are evicted first).
 */
public class InMemoryResultCache implements ResultCache {

    public static final int DEFAULT_MAX_ENTRIES = 500;

    private final long ttlMillis;
    private final LongSupplier clock;
    private final Map<String, Entry> entries;

    /**
     * @param ttlMillis  time-to-live of entries, must be positive
     * @param maxEntries maximum number of entries, must be positive
     * @param clock      source of the current time in milliseconds
     */
    public InMemoryResultCache(long ttlMillis, int maxEntries, LongSupplier clock) {
        if (ttlMillis <= 0) throw new IllegalArgumentException("ttlMillis must be positive");
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive");

        this.ttlMillis = ttlMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entries = new LinkedHashMap<String, Entry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public InMemoryResultCache(long ttlMillis) {
        this(ttlMillis, DEFAULT_MAX_ENTRIES, System::currentTimeMillis);
    }

    @Override
    public synchronized Entry get(String providerId, String number) {
        String key = key(providerId, number);
        Entry entry = entries.get(key);
        if (entry == null) return null;

        long age = clock.getAsLong() - entry.getStoredAtMillis();
        // entries "from the future" (the clock was moved back) are dropped too
        if (age >= ttlMillis || age < 0) {
            entries.remove(key);
            return null;
        }

        return entry;
    }

    @Override
    public synchronized void put(String providerId, String number, ProviderResult result) {
        entries.put(key(providerId, number), new Entry(result, clock.getAsLong()));
    }

    @Override
    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    private static String key(String providerId, String number) {
        return providerId + '\u0000' + number;
    }

}
