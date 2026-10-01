package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class NumberListIndexTest {

    private static ListedNumber exact(String number, String name) {
        return ListedNumber.builder().number(number).name(name).build();
    }

    private static ListedNumber prefix(String prefix, String name) {
        return ListedNumber.builder().prefix(prefix).name(name).build();
    }

    @Test
    public void exactMatch() {
        ListedNumber a = exact("+4915112345678", "a");
        NumberListIndex index = new NumberListIndex(Arrays.asList(a, exact("+4930123456", "b")));

        assertSame(a, index.find("+4915112345678"));
        assertNull(index.find("+491511234567"));   // shorter
        assertNull(index.find("+49151123456789")); // longer
        assertEquals(2, index.getExactCount());
        assertEquals(0, index.getPrefixCount());
    }

    @Test
    public void prefixMatch() {
        ListedNumber p = prefix("+4990012345", "p");
        NumberListIndex index = new NumberListIndex(Collections.singletonList(p));

        assertSame(p, index.find("+4990012345"));
        assertSame(p, index.find("+499001234599"));
        assertNull(index.find("+499001234"));
        assertNull(index.find("+4990012346000"));
    }

    @Test
    public void longestPrefixWins() {
        ListedNumber shortP = prefix("+49900", "short");
        ListedNumber midP = prefix("+4990012", "mid");
        ListedNumber longP = prefix("+499001234", "long");
        NumberListIndex index = new NumberListIndex(Arrays.asList(shortP, longP, midP));

        assertSame(longP, index.find("+49900123456"));
        assertSame(midP, index.find("+49900129999"));
        assertSame(shortP, index.find("+49900999999"));
        assertNull(index.find("+49800123456"));
    }

    @Test
    public void exactWinsOverPrefix() {
        ListedNumber p = prefix("+4990012", "p");
        ListedNumber e = exact("+49900123456", "e");
        NumberListIndex index = new NumberListIndex(Arrays.asList(p, e));

        assertSame(e, index.find("+49900123456"));
        assertSame(p, index.find("+49900123457"));
        assertSame(p, index.findPrefix("+49900123456"));
    }

    @Test
    public void firstDuplicateIsKept() {
        ListedNumber first = exact("+4915112345678", "first");
        ListedNumber second = exact("+4915112345678", "second");
        NumberListIndex index = new NumberListIndex(Arrays.asList(first, second));

        assertSame(first, index.find("+4915112345678"));
        assertEquals(1, index.size());
    }

    @Test
    public void noMatchAndEdgeCases() {
        NumberListIndex index = new NumberListIndex(Arrays.asList(
                exact("+4915112345678", "a"), prefix("+49900", "p")));

        assertNull(index.find(null));
        assertNull(index.find(""));
        assertNull(index.find("+4917000000000"));
        assertNull(index.find("4915112345678")); // keys are compared as is
        assertFalse(index.contains("+4917000000000"));
        assertTrue(index.contains("+499001"));

        assertTrue(NumberListIndex.empty().isEmpty());
        assertNull(NumberListIndex.empty().find("+4915112345678"));
    }

    @Test
    public void performanceWith50kEntries() {
        List<ListedNumber> entries = new ArrayList<>();
        for (int i = 0; i < 45_000; i++) {
            entries.add(exact("+49151" + (10_000_000 + i), null));
        }
        for (int i = 0; i < 5_000; i++) {
            // prefixes of different lengths (+499000 .. +499004999)
            entries.add(prefix("+49900" + i, null));
        }

        long buildStart = System.nanoTime();
        NumberListIndex index = new NumberListIndex(entries);
        long buildMillis = (System.nanoTime() - buildStart) / 1_000_000;

        assertEquals(50_000, index.size());

        int lookups = 200_000;
        int hits = 0;
        long start = System.nanoTime();
        for (int i = 0; i < lookups; i++) {
            String number;
            switch (i % 3) {
                case 0: number = "+49151" + (10_000_000 + (i % 45_000)); break; // exact hit
                case 1: number = "+49900" + (i % 5_000) + "123"; break;         // prefix hit
                default: number = "+4917" + (10_000_000 + i); break;           // miss
            }
            if (index.find(number) != null) hits++;
        }
        long lookupMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(lookups - lookups / 3, hits);
        // Generous bounds: a sanity check, not a benchmark
        assertTrue("build took " + buildMillis + " ms", buildMillis < 2_000);
        assertTrue(lookups + " lookups took " + lookupMillis + " ms", lookupMillis < 2_000);
    }

}
