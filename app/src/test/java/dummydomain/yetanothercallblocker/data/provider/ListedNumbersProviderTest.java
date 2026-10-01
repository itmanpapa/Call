package dummydomain.yetanothercallblocker.data.provider;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dummydomain.yetanothercallblocker.data.sources.ListedNumber;
import dummydomain.yetanothercallblocker.data.sources.MeasureType;
import dummydomain.yetanothercallblocker.data.sources.NumberListIndex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ListedNumbersProviderTest {

    private static NumberListIndex index(ListedNumber... entries) {
        return new NumberListIndex(Arrays.asList(entries));
    }

    private static ListedNumbersProvider provider(NumberListIndex index) {
        return new ListedNumbersProvider("bnetza", "Bundesnetzagentur", true, index);
    }

    @Test
    public void basicProperties() {
        ListedNumbersProvider p = new ListedNumbersProvider("csv_1", "My list", false, null);
        assertEquals("csv_1", p.getId());
        assertEquals("My list", p.getDisplayName());
        assertTrue(p.isOffline());
        assertFalse(p.isTrusted());
        assertTrue(p.getIndex().isEmpty());
        assertNull(p.lookup("+4915112345678"));

        assertTrue(provider(null).isTrusted());
    }

    @Test
    public void listedNumberIsNegativeWithCategoryAndName() {
        ListedNumbersProvider p = provider(index(ListedNumber.builder()
                .number("+4915112345678")
                .name("Gewinnspiel GmbH")
                .category("Werbung")
                .measureType(MeasureType.DISCONNECTION)
                .build()));

        assertEquals(new ProviderResult("bnetza", ProviderResult.Rating.NEGATIVE, "Werbung",
                "Gewinnspiel GmbH", ProviderResult.UNKNOWN_REVIEW_COUNT),
                p.lookup("+4915112345678"));
        assertNull(p.lookup("+4915112345679"));
    }

    @Test
    public void categoryFallsBackToMeasureType() {
        ListedNumbersProvider p = provider(index(
                ListedNumber.builder().prefix("+4990012")
                        .measureType(MeasureType.BILLING_PROHIBITION).build(),
                ListedNumber.builder().number("+4930123456").build()));

        ProviderResult prefixResult = p.lookup("+49900123456");
        assertEquals(ProviderResult.Rating.NEGATIVE, prefixResult.getRating());
        assertEquals("BILLING_PROHIBITION", prefixResult.getCategory());
        assertNull(prefixResult.getName());
        assertFalse(prefixResult.hasReviewCount());

        ProviderResult plain = p.lookup("+4930123456");
        assertEquals(ProviderResult.Rating.NEGATIVE, plain.getRating());
        assertNull(plain.getCategory()); // MeasureType.NONE
    }

    @Test
    public void acceptsAllFormsProducedByNumberUtils() {
        ListedNumbersProvider p = provider(index(
                ListedNumber.builder().number("+4915112345678").build(),
                ListedNumber.builder().number("+375291234567").build()));

        assertNotNull(p.lookup("+4915112345678"));   // formatNumberToE164()
        assertNotNull(p.lookup("004915112345678"));  // stripSeparators(), international
        assertNotNull(p.lookup("015112345678"));     // stripSeparators(), national
        assertNotNull(p.lookup("4915112345678"));    // international without "+"
        assertNotNull(p.lookup("0151 1234-5678"));   // separators left over
        assertNotNull(p.lookup("+375291234567"));
        assertNotNull(p.lookup("00375291234567"));

        assertNull(p.lookup(null));
        assertNull(p.lookup(""));
        assertNull(p.lookup("+"));
        assertNull(p.lookup("ANONYMOUS"));
        assertNull(p.lookup("-1"));
        assertNull(p.lookup("*31#015112345678"));
    }

    @Test
    public void toE164() {
        assertEquals("+4915112345678", ListedNumbersProvider.toE164("+4915112345678"));
        assertEquals("+4915112345678", ListedNumbersProvider.toE164("004915112345678"));
        assertEquals("+4915112345678", ListedNumbersProvider.toE164("015112345678"));
        assertEquals("+4915112345678", ListedNumbersProvider.toE164("4915112345678"));
        assertEquals("+4915112345678", ListedNumbersProvider.toE164("+49 151 1234 5678"));
        assertNull(ListedNumbersProvider.toE164("-1"));
        assertNull(ListedNumbersProvider.toE164("112"));
        assertNull(ListedNumbersProvider.toE164("00"));
        assertNull(ListedNumbersProvider.toE164("0"));
        assertNull(ListedNumbersProvider.toE164("+49abc"));
        assertNull(ListedNumbersProvider.toE164("   "));
    }

    @Test
    public void reloadSwapsIndex() {
        ListedNumbersProvider p = provider(index(
                ListedNumber.builder().number("+4915100000001").build()));

        assertNotNull(p.lookup("+4915100000001"));
        assertNull(p.lookup("+4915100000002"));

        p.setIndex(index(ListedNumber.builder().number("+4915100000002").build()));
        assertNull(p.lookup("+4915100000001"));
        assertNotNull(p.lookup("+4915100000002"));

        p.setIndex(null);
        assertNull(p.lookup("+4915100000002"));
        assertTrue(p.getIndex().isEmpty());
    }

    @Test
    public void concurrentLookupsDuringReload() throws Exception {
        // Index A lists numbers 0..999, index B lists 1000..1999. A lookup must see
        // either index completely: for a fixed pair (i, i + 1000) exactly one is listed.
        List<ListedNumber> a = new ArrayList<>();
        List<ListedNumber> b = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            a.add(ListedNumber.builder().number("+491510000" + (1000 + i)).build());
            b.add(ListedNumber.builder().number("+491510000" + (2000 + i)).build());
        }
        NumberListIndex indexA = new NumberListIndex(a);
        NumberListIndex indexB = new NumberListIndex(b);
        ListedNumbersProvider p = provider(indexA);

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger totalLookups = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> readers = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                readers.add(executor.submit(() -> {
                    int lookups = 0;
                    while (!stop.get()) {
                        int i = lookups % 1000;
                        NumberListIndex snapshot = p.getIndex();
                        boolean inA = snapshot.contains("+491510000" + (1000 + i));
                        boolean inB = snapshot.contains("+491510000" + (2000 + i));
                        if (inA == inB) throw new AssertionError("inconsistent index");
                        p.lookup("+491510000" + (1000 + i));
                        lookups++;
                        totalLookups.incrementAndGet();
                    }
                    return lookups;
                }));
            }
            // Keep swapping until the readers have done enough lookups (bounded in time)
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int swaps = 0;
            while (totalLookups.get() < 30_000 && System.nanoTime() < deadline) {
                p.setIndex(swaps++ % 2 == 0 ? indexB : indexA);
                Thread.yield();
            }
            stop.set(true);
            int total = 0;
            for (Future<Integer> reader : readers) {
                total += reader.get(10, TimeUnit.SECONDS); // rethrows reader assertion errors
            }
            assertTrue("lookups: " + total, total > 0);
        } finally {
            stop.set(true);
            executor.shutdownNow();
        }

        p.setIndex(new NumberListIndex(Collections.emptyList()));
        assertNull(p.lookup("+4915100001000"));
    }

    @Test
    public void nationalNumberMatchesMeasureEntry() {
        ListedNumbersProvider p = provider(index(ListedNumber.builder().number("+4915112345678")
                .measureType(MeasureType.DISCONNECTION).build()));
        ProviderResult r = p.lookup("015112345678");
        assertEquals("bnetza", r.getSourceId());
        assertEquals("DISCONNECTION", r.getCategory());
    }

}
