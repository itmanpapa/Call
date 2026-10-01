package dummydomain.yetanothercallblocker.data.provider;

/**
 * Cache of online provider responses, keyed by provider id and number.
 *
 * <p>A cached entry may hold a null result, which means "the provider was asked
 * and knows nothing about the number"; this avoids repeating useless requests.</p>
 *
 * <p>Implementations must be thread-safe: entries are written from executor
 * threads, possibly after the aggregator has stopped waiting for them.</p>
 */
public interface ResultCache {

    /**
     * Cached lookup outcome.
     */
    final class Entry {

        private final ProviderResult result;
        private final long storedAtMillis;

        public Entry(ProviderResult result, long storedAtMillis) {
            this.result = result;
            this.storedAtMillis = storedAtMillis;
        }

        /**
         * @return the cached result, or null if the provider had no information
         */
        public ProviderResult getResult() {
            return result;
        }

        public long getStoredAtMillis() {
            return storedAtMillis;
        }

    }

    /**
     * @return a non-expired entry, or null if there is none
     */
    Entry get(String providerId, String number);

    /**
     * Stores a lookup outcome.
     *
     * @param result the result, or null if the provider had no information
     */
    void put(String providerId, String number, ProviderResult result);

    /**
     * Removes all entries.
     */
    void clear();

}
