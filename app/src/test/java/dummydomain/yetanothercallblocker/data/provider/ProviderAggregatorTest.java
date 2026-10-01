package dummydomain.yetanothercallblocker.data.provider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static dummydomain.yetanothercallblocker.data.provider.ProviderResult.Rating.NEGATIVE;
import static dummydomain.yetanothercallblocker.data.provider.ProviderResult.Rating.NEUTRAL;
import static dummydomain.yetanothercallblocker.data.provider.ProviderResult.Rating.POSITIVE;
import static dummydomain.yetanothercallblocker.data.provider.ProviderResult.Rating.UNKNOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProviderAggregatorTest {

    private static final String NUMBER = "+4930123456";

    private ExecutorService executor;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private InMemoryResultCache cache;
    private CountDownLatch gate;

    @Before
    public void setUp() {
        executor = Executors.newCachedThreadPool();
        cache = new InMemoryResultCache(TimeUnit.DAYS.toMillis(7), 100, now::get);
        gate = new CountDownLatch(1);
    }

    @After
    public void tearDown() throws InterruptedException {
        gate.countDown();
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    private ProviderAggregator aggregator(long timeoutMillis, NumberInfoProvider... providers) {
        return new ProviderAggregator(Arrays.asList(providers), executor, cache, timeoutMillis);
    }

    // --- source priority ---

    @Test
    public void trustedNegativeWinsOverEarlierRatings() {
        AggregatedResult result = aggregator(1000,
                FakeProvider.offline("a").returning(POSITIVE),
                FakeProvider.offline("b").returning(NEUTRAL),
                FakeProvider.online("c").returning(NEGATIVE)
        ).lookup(NUMBER);

        assertEquals(NEGATIVE, result.getRating());
        assertEquals("c", result.getRatingSourceId());
        assertEquals(Arrays.asList("a", "b", "c"), result.getSourceIds());
    }

    @Test
    public void untrustedNegativeDoesNotOverrideEarlierRating() {
        AggregatedResult result = aggregator(1000,
                FakeProvider.offline("a").returning(POSITIVE),
                FakeProvider.offline("b").returning(NEGATIVE).untrusted()
        ).lookup(NUMBER);

        assertEquals(POSITIVE, result.getRating());
        assertEquals("a", result.getRatingSourceId());
    }

    @Test
    public void untrustedNegativeIsUsedWhenItIsTheFirstKnownRating() {
        AggregatedResult result = aggregator(1000,
                FakeProvider.offline("a").returning(UNKNOWN),
                FakeProvider.offline("b").returning(NEGATIVE).untrusted(),
                FakeProvider.offline("c").returning(POSITIVE)
        ).lookup(NUMBER);

        assertEquals(NEGATIVE, result.getRating());
        assertEquals("b", result.getRatingSourceId());
    }

    @Test
    public void firstKnownRatingInProviderOrderWins() {
        AggregatedResult result = aggregator(1000,
                FakeProvider.offline("a"), // no info
                FakeProvider.offline("b").returning(UNKNOWN),
                FakeProvider.online("c").returning(NEUTRAL),
                FakeProvider.offline("d").returning(POSITIVE)
        ).lookup(NUMBER);

        assertEquals(NEUTRAL, result.getRating());
        assertEquals("c", result.getRatingSourceId());
        assertEquals(Arrays.asList("b", "c", "d"), result.getSourceIds());
    }

    @Test
    public void unknownWhenNobodyKnowsTheNumber() {
        AggregatedResult result = aggregator(1000,
                FakeProvider.offline("a"),
                FakeProvider.online("b")
        ).lookup(NUMBER);

        assertEquals(UNKNOWN, result.getRating());
        assertNull(result.getRatingSourceId());
        assertFalse(result.hasInfo());
        assertTrue(result.getFailedProviderIds().isEmpty());
        assertTrue(result.getTimedOutProviderIds().isEmpty());
    }

    @Test
    public void categoryComesFromRatingSourceAndNameFromFirstProviderWithName() {
        AggregatedResult result = aggregator(1000,
                FakeProvider.offline("a").returning(POSITIVE, "COMPANY", null),
                FakeProvider.offline("b").returning(UNKNOWN, null, "ACME GmbH"),
                FakeProvider.offline("c").returning(NEGATIVE, "TELEMARKETER", "Spam Inc")
        ).lookup(NUMBER);

        assertEquals(NEGATIVE, result.getRating());
        assertEquals("TELEMARKETER", result.getCategory());
        assertEquals("ACME GmbH", result.getName());
    }

    @Test
    public void categoryFallsBackToFirstAvailable() {
        AggregatedResult result = aggregator(1000,
                FakeProvider.offline("a").returning(NEUTRAL),
                FakeProvider.offline("b").returning(UNKNOWN, "SCAM", "  "),
                FakeProvider.offline("c").returning(UNKNOWN, "COMPANY", "Name")
        ).lookup(NUMBER);

        assertEquals("a", result.getRatingSourceId());
        assertEquals("SCAM", result.getCategory());
        assertEquals("Name", result.getName());
    }

    // --- offline-only operation ---

    @Test
    public void worksWithOfflineProvidersOnlyAndNoExecutor() {
        ProviderAggregator aggregator = new ProviderAggregator(
                Collections.singletonList(FakeProvider.offline("a").returning(NEGATIVE)),
                null, null);

        AggregatedResult result = aggregator.lookup(NUMBER);

        assertEquals(NEGATIVE, result.getRating());
        assertEquals(ProviderAggregator.DEFAULT_ONLINE_TIMEOUT_MILLIS,
                aggregator.getOnlineTimeoutMillis());
    }

    @Test
    public void worksWithoutProviders() {
        AggregatedResult result = new ProviderAggregator(
                Collections.<NumberInfoProvider>emptyList(), null, null).lookup(NUMBER);

        assertEquals(UNKNOWN, result.getRating());
        assertFalse(result.hasInfo());
    }

    @Test
    public void offlineResultsAreNotCached() {
        FakeProvider offline = FakeProvider.offline("a").returning(NEGATIVE);
        ProviderAggregator aggregator = aggregator(1000, offline);

        aggregator.lookup(NUMBER);
        aggregator.lookup(NUMBER);

        assertEquals(2, offline.calls.get());
        assertEquals(0, cache.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void executorIsRequiredForOnlineProviders() {
        new ProviderAggregator(Collections.singletonList(FakeProvider.online("a")), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateIdsAreRejected() {
        aggregator(1000, FakeProvider.offline("a"), FakeProvider.online("a"));
    }

    // --- timeouts ---

    @Test
    public void timedOutOnlineProviderFallsBackToOfflineData() {
        FakeProvider slow = FakeProvider.online("slow").returning(NEGATIVE).blockedBy(gate);
        ProviderAggregator aggregator = aggregator(200,
                FakeProvider.offline("a").returning(POSITIVE, null, "Pizza"),
                slow);

        long start = System.nanoTime();
        AggregatedResult result = aggregator.lookup(NUMBER);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("took " + elapsedMillis + " ms", elapsedMillis >= 150);
        assertTrue("took " + elapsedMillis + " ms", elapsedMillis < 1000);
        assertEquals(POSITIVE, result.getRating());
        assertEquals("a", result.getRatingSourceId());
        assertEquals("Pizza", result.getName());
        assertEquals(Collections.singletonList("slow"), result.getTimedOutProviderIds());
        assertEquals(Collections.singletonList("a"), result.getSourceIds());
    }

    @Test
    public void allOnlineProvidersTimingOutGivesUnknown() {
        AggregatedResult result = aggregator(100,
                FakeProvider.online("x").returning(NEGATIVE).blockedBy(gate),
                FakeProvider.online("y").returning(NEGATIVE).blockedBy(gate)
        ).lookup(NUMBER);

        assertEquals(UNKNOWN, result.getRating());
        assertEquals(Arrays.asList("x", "y"), result.getTimedOutProviderIds());
    }

    @Test
    public void onlineProvidersAreQueriedInParallel() {
        ProviderAggregator aggregator = aggregator(2000,
                FakeProvider.online("x").returning(NEUTRAL).delayed(300),
                FakeProvider.online("y").returning(POSITIVE).delayed(300),
                FakeProvider.online("z").returning(NEGATIVE).delayed(300));

        long start = System.nanoTime();
        AggregatedResult result = aggregator.lookup(NUMBER);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("took " + elapsedMillis + " ms", elapsedMillis < 800);
        assertEquals(NEGATIVE, result.getRating());
        assertEquals(Arrays.asList("x", "y", "z"), result.getSourceIds());
        assertTrue(result.getTimedOutProviderIds().isEmpty());
    }

    @Test
    public void timeoutIsSharedBetweenOnlineProviders() {
        ProviderAggregator aggregator = aggregator(300,
                FakeProvider.online("x").blockedBy(gate),
                FakeProvider.online("y").blockedBy(gate),
                FakeProvider.online("z").blockedBy(gate));

        long start = System.nanoTime();
        AggregatedResult result = aggregator.lookup(NUMBER);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // not 3 x 300 ms
        assertTrue("took " + elapsedMillis + " ms", elapsedMillis < 700);
        assertEquals(3, result.getTimedOutProviderIds().size());
    }

    @Test
    public void lateOnlineResultIsCachedForNextLookup() throws InterruptedException {
        FakeProvider slow = FakeProvider.online("slow").returning(NEGATIVE).blockedBy(gate);
        ProviderAggregator aggregator = aggregator(100,
                FakeProvider.offline("a").returning(POSITIVE), slow);

        assertEquals(POSITIVE, aggregator.lookup(NUMBER).getRating());

        gate.countDown();
        awaitCached("slow", NUMBER);

        AggregatedResult second = aggregator.lookup(NUMBER);
        assertEquals(NEGATIVE, second.getRating());
        assertEquals("slow", second.getRatingSourceId());
        assertEquals(1, slow.calls.get());
    }

    // --- exceptions ---

    @Test
    public void failingOfflineProviderIsSkipped() {
        AggregatedResult result = aggregator(1000,
                FakeProvider.offline("bad").throwing(new IllegalStateException("db corrupted")),
                FakeProvider.offline("good").returning(NEGATIVE)
        ).lookup(NUMBER);

        assertEquals(NEGATIVE, result.getRating());
        assertEquals(Collections.singletonList("bad"), result.getFailedProviderIds());
        assertEquals(Collections.singletonList("good"), result.getSourceIds());
    }

    @Test
    public void failingOnlineProviderIsSkippedAndNotCached() {
        FakeProvider bad = FakeProvider.online("bad").throwing(new RuntimeException("HTTP 500"));
        ProviderAggregator aggregator = aggregator(1000,
                bad, FakeProvider.offline("good").returning(NEUTRAL));

        AggregatedResult result = aggregator.lookup(NUMBER);

        assertEquals(NEUTRAL, result.getRating());
        assertEquals(Collections.singletonList("bad"), result.getFailedProviderIds());
        assertNull(cache.get("bad", NUMBER));

        aggregator.lookup(NUMBER);
        assertEquals("failure must be retried", 2, bad.calls.get());
    }

    @Test
    public void allOnlineProvidersFailingGivesOfflineResult() {
        AggregatedResult result = aggregator(1000,
                FakeProvider.online("x").throwing(new RuntimeException("no network")),
                FakeProvider.online("y").throwing(new RuntimeException("no network")),
                FakeProvider.offline("a").returning(POSITIVE)
        ).lookup(NUMBER);

        assertEquals(POSITIVE, result.getRating());
        assertEquals(Arrays.asList("x", "y"), result.getFailedProviderIds());
    }

    @Test
    public void rejectedExecutionIsTreatedAsFailure() {
        executor.shutdown();

        AggregatedResult result = aggregator(1000,
                FakeProvider.online("x").returning(NEGATIVE),
                FakeProvider.offline("a").returning(NEUTRAL)
        ).lookup(NUMBER);

        assertEquals(NEUTRAL, result.getRating());
        assertEquals(Collections.singletonList("x"), result.getFailedProviderIds());
    }

    @Test
    public void interruptionStopsWaitingAndKeepsFlag() {
        ProviderAggregator aggregator = aggregator(5000,
                FakeProvider.online("x").blockedBy(gate),
                FakeProvider.offline("a").returning(POSITIVE));

        Thread.currentThread().interrupt();
        try {
            long start = System.nanoTime();
            AggregatedResult result = aggregator.lookup(NUMBER);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue("took " + elapsedMillis + " ms", elapsedMillis < 1000);
            assertEquals(POSITIVE, result.getRating());
            assertEquals(Collections.singletonList("x"), result.getTimedOutProviderIds());
        } finally {
            Thread.interrupted(); // clear the flag
        }
    }

    // --- cache ---

    @Test
    public void onlineResultIsCachedAndReused() {
        FakeProvider online = FakeProvider.online("x").returning(NEGATIVE, "SCAM", null);
        ProviderAggregator aggregator = aggregator(1000, online);

        aggregator.lookup(NUMBER);
        AggregatedResult second = aggregator.lookup(NUMBER);

        assertEquals(1, online.calls.get());
        assertEquals(NEGATIVE, second.getRating());
        assertEquals("SCAM", second.getCategory());
        assertNotNull(cache.get("x", NUMBER));
    }

    @Test
    public void emptyOnlineResultIsCachedToo() {
        FakeProvider online = FakeProvider.online("x");
        ProviderAggregator aggregator = aggregator(1000, online);

        aggregator.lookup(NUMBER);
        AggregatedResult second = aggregator.lookup(NUMBER);

        assertEquals(1, online.calls.get());
        assertFalse(second.hasInfo());
    }

    @Test
    public void cacheIsPerNumber() {
        FakeProvider online = FakeProvider.online("x").returning(NEGATIVE);
        ProviderAggregator aggregator = aggregator(1000, online);

        aggregator.lookup(NUMBER);
        aggregator.lookup("+4930999999");

        assertEquals(2, online.calls.get());
    }

    @Test
    public void expiredCacheEntryIsRequeried() {
        FakeProvider online = FakeProvider.online("x").returning(NEGATIVE);
        ProviderAggregator aggregator = aggregator(1000, online);

        aggregator.lookup(NUMBER);
        now.addAndGet(TimeUnit.DAYS.toMillis(7) - 1);
        aggregator.lookup(NUMBER);
        assertEquals("still fresh", 1, online.calls.get());

        now.addAndGet(1);
        aggregator.lookup(NUMBER);
        assertEquals("expired", 2, online.calls.get());
    }

    @Test
    public void cachedResultIsUsedEvenIfProviderIsNowUnreachable() {
        cache.put("x", NUMBER, new ProviderResult("x", NEGATIVE));
        FakeProvider online = FakeProvider.online("x").blockedBy(gate);

        long start = System.nanoTime();
        AggregatedResult result = aggregator(1000, online).lookup(NUMBER);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(NEGATIVE, result.getRating());
        assertEquals(0, online.calls.get());
        assertTrue("took " + elapsedMillis + " ms", elapsedMillis < 500);
    }

    @Test
    public void worksWithoutCache() {
        FakeProvider online = FakeProvider.online("x").returning(NEGATIVE);
        ProviderAggregator aggregator = new ProviderAggregator(
                Collections.<NumberInfoProvider>singletonList(online), executor, null, 1000);

        assertEquals(NEGATIVE, aggregator.lookup(NUMBER).getRating());
        assertEquals(NEGATIVE, aggregator.lookup(NUMBER).getRating());
        assertEquals(2, online.calls.get());
    }

    private void awaitCached(String providerId, String number) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.get(providerId, number) == null) {
            if (System.nanoTime() > deadline) fail("result was not cached");
            Thread.sleep(10);
        }
    }

}
