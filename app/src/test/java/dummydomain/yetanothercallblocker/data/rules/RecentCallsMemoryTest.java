package dummydomain.yetanothercallblocker.data.rules;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecentCallsMemoryTest {

    private static final long START = 1_000_000_000L;
    private static final long MINUTE = 60_000L;

    private static final class RecordingFallback implements RecentCalls {
        final List<long[]> queries = new ArrayList<>();
        boolean answer;

        @Override
        public boolean hasCallBetween(String key, long fromMillis, long toMillis) {
            queries.add(new long[]{fromMillis, toMillis});
            return answer;
        }
    }

    @Test
    public void remembersCallsInInterval() {
        RecentCallsMemory memory = new RecentCallsMemory(START, null);
        memory.record("+4930", START + MINUTE);

        assertTrue(memory.hasCallBetween("+4930", START, START + 2 * MINUTE));
        assertTrue(memory.hasCallBetween("+4930", START + MINUTE, START + MINUTE));
        assertFalse(memory.hasCallBetween("+4930", START + 2 * MINUTE, START + 3 * MINUTE));
        assertFalse(memory.hasCallBetween("+4931", START, START + 2 * MINUTE));
        assertFalse(memory.hasCallBetween("", START, START + 2 * MINUTE));
        assertFalse(memory.hasCallBetween(null, START, START + 2 * MINUTE));
    }

    @Test
    public void keepsSeveralCallsPerCaller() {
        RecentCallsMemory memory = new RecentCallsMemory(START, null);
        memory.record("+4930", START + MINUTE);
        memory.record("+4930", START + 5 * MINUTE);
        assertTrue(memory.hasCallBetween("+4930", START, START + 2 * MINUTE));
        assertTrue(memory.hasCallBetween("+4930", START + 4 * MINUTE, START + 6 * MINUTE));
    }

    @Test
    public void fallbackOnlyForTimeBeforeStart() {
        RecordingFallback fallback = new RecordingFallback();
        RecentCallsMemory memory = new RecentCallsMemory(START, fallback);

        // entirely after the start: the memory is authoritative
        assertFalse(memory.hasCallBetween("+4930", START + MINUTE, START + 2 * MINUTE));
        assertEquals(0, fallback.queries.size());

        // reaching before the start: ask the fallback for that part only
        fallback.answer = true;
        assertTrue(memory.hasCallBetween("+4930", START - MINUTE, START + MINUTE));
        assertEquals(1, fallback.queries.size());
        assertEquals(START - MINUTE, fallback.queries.get(0)[0]);
        assertEquals(START, fallback.queries.get(0)[1]);

        // found in memory: no fallback query
        memory.record("+4931", START + 10);
        assertTrue(memory.hasCallBetween("+4931", START - MINUTE, START + MINUTE));
        assertEquals(1, fallback.queries.size());
    }

    @Test
    public void forgetsOldCallsAndLimitsSize() {
        RecentCallsMemory memory = new RecentCallsMemory(START, null);
        memory.record("old", START);
        memory.record("new", START + RecentCallsMemory.MAX_AGE_MILLIS + 1);
        assertEquals(1, memory.size());

        for (int i = 0; i < RecentCallsMemory.MAX_CALLERS + 50; i++) {
            memory.record("n" + i, START + RecentCallsMemory.MAX_AGE_MILLIS + 2 + i);
        }
        assertEquals(RecentCallsMemory.MAX_CALLERS, memory.size());
        // the latest callers are kept
        long last = START + RecentCallsMemory.MAX_AGE_MILLIS + 2
                + RecentCallsMemory.MAX_CALLERS + 49;
        assertTrue(memory.hasCallBetween("n" + (RecentCallsMemory.MAX_CALLERS + 49), last, last));
    }

}
