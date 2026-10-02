package dummydomain.yetanothercallblocker.data.rules;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory record of the recent incoming calls (no I/O on the call path).
 *
 * <p>The memory only knows the calls since the process started. For an interval
 * reaching before that, the optional fallback (e.g. the system call log) is asked,
 * so a fallback query happens only during the first minutes after a process start.</p>
 */
public class RecentCallsMemory implements RecentCalls {

    /** At most this many callers are remembered. */
    static final int MAX_CALLERS = 256;

    /** At most this many calls are remembered per caller. */
    static final int MAX_CALLS_PER_CALLER = 4;

    /** Calls older than this are forgotten (the longest repeat window). */
    static final long MAX_AGE_MILLIS = CallRule.MAX_REPEAT_WINDOW_MINUTES * 60_000L;

    private final long startedAtMillis;
    private final RecentCalls fallback;

    // insertion order = order of the latest call, the eldest caller first
    private final LinkedHashMap<String, ArrayDeque<Long>> calls = new LinkedHashMap<>();

    /**
     * @param startedAtMillis since when the memory records calls (the process start)
     * @param fallback        asked about the time before {@code startedAtMillis}, may be null
     */
    public RecentCallsMemory(long startedAtMillis, RecentCalls fallback) {
        this.startedAtMillis = startedAtMillis;
        this.fallback = fallback;
    }

    /** Records an incoming call. */
    public synchronized void record(String key, long timeMillis) {
        if (key == null || key.isEmpty()) return;

        ArrayDeque<Long> times = calls.remove(key);
        if (times == null) times = new ArrayDeque<>(MAX_CALLS_PER_CALLER);
        times.addLast(timeMillis);
        while (times.size() > MAX_CALLS_PER_CALLER) times.removeFirst();
        calls.put(key, times); // moves the caller to the end

        prune(timeMillis);
    }

    private void prune(long nowMillis) {
        long cutoff = nowMillis - MAX_AGE_MILLIS;
        Iterator<Map.Entry<String, ArrayDeque<Long>>> it = calls.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ArrayDeque<Long>> entry = it.next();
            if (calls.size() > MAX_CALLERS || entry.getValue().peekLast() < cutoff) {
                it.remove();
            } else {
                break; // the following callers called later
            }
        }
    }

    @Override
    public boolean hasCallBetween(String key, long fromMillis, long toMillis) {
        if (key == null || key.isEmpty() || fromMillis > toMillis) return false;

        synchronized (this) {
            ArrayDeque<Long> times = calls.get(key);
            if (times != null) {
                for (long time : times) {
                    if (time >= fromMillis && time <= toMillis) return true;
                }
            }
        }

        if (fallback != null && fromMillis < startedAtMillis) {
            return fallback.hasCallBetween(key, fromMillis, Math.min(toMillis, startedAtMillis));
        }
        return false;
    }

    synchronized int size() {
        return calls.size();
    }

}
