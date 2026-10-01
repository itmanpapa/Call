package dummydomain.yetanothercallblocker.data.provider;

/**
 * A single independent source of information about phone numbers
 * (a local database, an imported list, an online service, ...).
 *
 * <p>Implementations must be thread-safe: online providers are queried
 * from executor threads, possibly concurrently for different numbers.</p>
 *
 * <p>This interface intentionally has no Android dependencies so that
 * providers and the aggregator can be unit-tested on a plain JVM.</p>
 */
public interface NumberInfoProvider {

    /**
     * @return a stable unique identifier of this provider (used as the source id
     * in results, as a cache key and in settings). Must not change between releases.
     */
    String getId();

    /**
     * @return a human-readable source label, e.g. for notifications
     */
    String getDisplayName();

    /**
     * @return true if lookups are answered from local data only and are fast enough
     * to be performed synchronously on the call-screening path
     */
    boolean isOffline();

    /**
     * Whether a {@link ProviderResult.Rating#NEGATIVE} rating from this provider
     * is reliable enough to override ratings from all other providers.
     *
     * @return true by default
     */
    default boolean isTrusted() {
        return true;
    }

    /**
     * Looks up information about the number.
     *
     * @param number the number to look up, already normalized by the caller
     *               (as produced by {@code NumberUtils.normalizeNumber()})
     * @return the result, or null if this provider knows nothing about the number
     * @throws Exception on any failure (I/O errors, malformed responses, ...);
     * the aggregator treats a failure the same as "no information"
     */
    ProviderResult lookup(String number) throws Exception;

}
