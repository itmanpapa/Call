package dummydomain.yetanothercallblocker.data.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Queries an ordered list of {@link NumberInfoProvider}s and combines their answers.
 *
 * <p>Lookup procedure:</p>
 * <ol>
 *     <li>For every online provider the cache is checked; on a miss the lookup
 *     is submitted to the executor. A completed online lookup always stores its
 *     result in the cache, even if the aggregator has stopped waiting for it,
 *     so a late answer is available for the next call from the same number.</li>
 *     <li>Offline providers are queried synchronously on the calling thread.</li>
 *     <li>The aggregator waits for online lookups until the timeout (measured from
 *     the start of the lookup) expires; providers that did not answer are ignored.</li>
 * </ol>
 *
 * <p>Rating priority: a {@link ProviderResult.Rating#NEGATIVE} rating from any
 * {@linkplain NumberInfoProvider#isTrusted() trusted} provider wins; otherwise
 * the first rating other than {@link ProviderResult.Rating#UNKNOWN} in provider
 * order is used.</p>
 *
 * <p>A failing provider (exception, rejected task) never fails the whole lookup,
 * it is treated as "no information".</p>
 */
public class ProviderAggregator {

    public static final long DEFAULT_ONLINE_TIMEOUT_MILLIS = 1500;

    private static final Logger LOG = LoggerFactory.getLogger(ProviderAggregator.class);

    private final List<NumberInfoProvider> providers;
    private final ExecutorService executor;
    private final ResultCache cache;
    private final long onlineTimeoutMillis;

    /**
     * @param providers           providers in priority order; ids must be unique
     * @param executor            executor for online lookups; may be null only
     *                            if there are no online providers
     * @param cache               cache for online results, may be null (no caching)
     * @param onlineTimeoutMillis how long to wait for online providers, must not be negative
     */
    public ProviderAggregator(List<NumberInfoProvider> providers, ExecutorService executor,
                              ResultCache cache, long onlineTimeoutMillis) {
        Objects.requireNonNull(providers, "providers");
        if (onlineTimeoutMillis < 0) {
            throw new IllegalArgumentException("onlineTimeoutMillis must not be negative");
        }

        Set<String> ids = new HashSet<>();
        boolean hasOnline = false;
        for (NumberInfoProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            if (!ids.add(provider.getId())) {
                throw new IllegalArgumentException("Duplicate provider id: " + provider.getId());
            }
            if (!provider.isOffline()) hasOnline = true;
        }
        if (hasOnline && executor == null) {
            throw new IllegalArgumentException("executor is required for online providers");
        }

        this.providers = Collections.unmodifiableList(new ArrayList<>(providers));
        this.executor = executor;
        this.cache = cache;
        this.onlineTimeoutMillis = onlineTimeoutMillis;
    }

    public ProviderAggregator(List<NumberInfoProvider> providers, ExecutorService executor,
                              ResultCache cache) {
        this(providers, executor, cache, DEFAULT_ONLINE_TIMEOUT_MILLIS);
    }

    public List<NumberInfoProvider> getProviders() {
        return providers;
    }

    public long getOnlineTimeoutMillis() {
        return onlineTimeoutMillis;
    }

    /**
     * Looks up the number in all providers.
     *
     * @param number the normalized number
     * @return the combined result, never null
     */
    public AggregatedResult lookup(String number) {
        LOG.debug("lookup({}) started", number);

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(onlineTimeoutMillis);

        int size = providers.size();
        ProviderResult[] results = new ProviderResult[size];
        boolean[] failed = new boolean[size];
        boolean[] timedOut = new boolean[size];

        // start online lookups first so that they run while offline providers are queried
        Map<Integer, Future<ProviderResult>> pending = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            NumberInfoProvider provider = providers.get(i);
            if (provider.isOffline()) continue;

            if (cache != null) {
                ResultCache.Entry entry = cache.get(provider.getId(), number);
                if (entry != null) {
                    LOG.trace("lookup() cache hit for {}: {}", provider.getId(), entry.getResult());
                    results[i] = entry.getResult();
                    continue;
                }
            }

            try {
                pending.put(i, executor.submit(() -> queryOnline(provider, number)));
            } catch (RejectedExecutionException e) {
                LOG.warn("lookup() couldn't start lookup in {}", provider.getId(), e);
                failed[i] = true;
            }
        }

        for (int i = 0; i < size; i++) {
            NumberInfoProvider provider = providers.get(i);
            if (!provider.isOffline()) continue;

            try {
                results[i] = provider.lookup(number);
                LOG.trace("lookup() {} returned {}", provider.getId(), results[i]);
            } catch (Exception e) {
                LOG.warn("lookup() offline provider {} failed", provider.getId(), e);
                failed[i] = true;
            }
        }

        boolean interrupted = false;
        for (Map.Entry<Integer, Future<ProviderResult>> e : pending.entrySet()) {
            int i = e.getKey();
            Future<ProviderResult> future = e.getValue();
            String id = providers.get(i).getId();

            if (interrupted) {
                timedOut[i] = true;
                continue;
            }

            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                results[i] = future.get(remaining, TimeUnit.NANOSECONDS);
                LOG.trace("lookup() {} returned {}", id, results[i]);
            } catch (TimeoutException ex) {
                // the task is not cancelled: when it completes, its result goes to the cache
                LOG.debug("lookup() {} timed out", id);
                timedOut[i] = true;
            } catch (ExecutionException ex) {
                LOG.warn("lookup() online provider {} failed", id, ex.getCause());
                failed[i] = true;
            } catch (CancellationException ex) {
                LOG.warn("lookup() online lookup in {} was cancelled", id);
                failed[i] = true;
            } catch (InterruptedException ex) {
                LOG.debug("lookup() interrupted while waiting for {}", id);
                interrupted = true;
                timedOut[i] = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();

        AggregatedResult result = combine(results, failed, timedOut);
        LOG.debug("lookup() finished: {}", result);
        return result;
    }

    private ProviderResult queryOnline(NumberInfoProvider provider, String number)
            throws Exception {
        ProviderResult result = provider.lookup(number);
        // failures are not cached so that the next call retries
        if (cache != null) cache.put(provider.getId(), number, result);
        return result;
    }

    private AggregatedResult combine(ProviderResult[] results,
                                     boolean[] failed, boolean[] timedOut) {
        ProviderResult ratingSource = null;

        // a negative rating from a trusted provider wins
        for (int i = 0; i < results.length; i++) {
            ProviderResult r = results[i];
            if (r != null && r.getRating() == ProviderResult.Rating.NEGATIVE
                    && providers.get(i).isTrusted()) {
                ratingSource = r;
                break;
            }
        }

        // otherwise the first known rating in provider order
        if (ratingSource == null) {
            for (ProviderResult r : results) {
                if (r != null && r.getRating() != ProviderResult.Rating.UNKNOWN) {
                    ratingSource = r;
                    break;
                }
            }
        }

        String category = ratingSource != null ? ratingSource.getCategory() : null;
        String name = null;
        List<ProviderResult> contributing = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();
        List<String> timedOutIds = new ArrayList<>();

        for (int i = 0; i < results.length; i++) {
            ProviderResult r = results[i];
            if (r != null) {
                contributing.add(r);
                if (category == null) category = r.getCategory();
                if (isEmpty(name) && !isEmpty(r.getName())) name = r.getName();
            }
            if (failed[i]) failedIds.add(providers.get(i).getId());
            if (timedOut[i]) timedOutIds.add(providers.get(i).getId());
        }

        return new AggregatedResult(
                ratingSource != null ? ratingSource.getRating() : ProviderResult.Rating.UNKNOWN,
                ratingSource != null ? ratingSource.getSourceId() : null,
                category, name, contributing, failedIds, timedOutIds);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

}
