package dummydomain.yetanothercallblocker.data.provider;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class InMemoryResultCacheTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    @Test
    public void returnsStoredResultWithinTtl() {
        InMemoryResultCache cache = new InMemoryResultCache(1000, 10, now::get);
        ProviderResult result = new ProviderResult("p", ProviderResult.Rating.NEGATIVE);

        cache.put("p", "+49301234", result);
        now.addAndGet(999);

        ResultCache.Entry entry = cache.get("p", "+49301234");
        assertNotNull(entry);
        assertEquals(result, entry.getResult());
        assertEquals(1_000_000, entry.getStoredAtMillis());
    }

    @Test
    public void expiresAfterTtl() {
        InMemoryResultCache cache = new InMemoryResultCache(1000, 10, now::get);
        cache.put("p", "+49301234", new ProviderResult("p", ProviderResult.Rating.NEGATIVE));

        now.addAndGet(1000);

        assertNull(cache.get("p", "+49301234"));
        assertEquals(0, cache.size());
    }

    @Test
    public void dropsEntriesFromTheFuture() {
        InMemoryResultCache cache = new InMemoryResultCache(1000, 10, now::get);
        cache.put("p", "+49301234", new ProviderResult("p", ProviderResult.Rating.NEGATIVE));

        now.addAndGet(-1);

        assertNull(cache.get("p", "+49301234"));
    }

    @Test
    public void storesEmptyResults() {
        InMemoryResultCache cache = new InMemoryResultCache(1000, 10, now::get);
        cache.put("p", "+49301234", null);

        ResultCache.Entry entry = cache.get("p", "+49301234");
        assertNotNull(entry);
        assertNull(entry.getResult());
    }

    @Test
    public void keysByProviderAndNumber() {
        InMemoryResultCache cache = new InMemoryResultCache(1000, 10, now::get);
        cache.put("a", "1", new ProviderResult("a", ProviderResult.Rating.NEGATIVE));

        assertNull(cache.get("b", "1"));
        assertNull(cache.get("a", "2"));
        assertNotNull(cache.get("a", "1"));
    }

    @Test
    public void evictsLeastRecentlyUsed() {
        InMemoryResultCache cache = new InMemoryResultCache(1000, 2, now::get);
        cache.put("p", "1", null);
        cache.put("p", "2", null);
        cache.get("p", "1"); // "2" is now the eldest
        cache.put("p", "3", null);

        assertEquals(2, cache.size());
        assertNotNull(cache.get("p", "1"));
        assertNull(cache.get("p", "2"));
        assertNotNull(cache.get("p", "3"));
    }

    @Test
    public void clearRemovesEverything() {
        InMemoryResultCache cache = new InMemoryResultCache(1000, 10, now::get);
        cache.put("p", "1", null);
        cache.clear();

        assertNull(cache.get("p", "1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveTtl() {
        new InMemoryResultCache(0, 10, now::get);
    }

}
