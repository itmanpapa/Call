package dummydomain.yetanothercallblocker.data.stats;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.data.stats.CallStatEvent.Outcome;
import dummydomain.yetanothercallblocker.data.stats.CallStatEvent.Reason;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CallEventStoreTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static CallStatEvent blocked(long ts, String number) {
        return new CallStatEvent(ts, number, Outcome.BLOCKED, Reason.LIST, "bnetza");
    }

    private static CallStatEvent allowed(long ts, String number) {
        return new CallStatEvent(ts, number, Outcome.ALLOWED, Reason.NONE, null);
    }

    @Test
    public void emptyStoreLoadsNothing() throws IOException {
        CallEventStore store = new CallEventStore(new File(folder.getRoot(), "x/events.csv"));
        assertTrue(store.load(NOW).isEmpty());
    }

    @Test
    public void recordAndLoadRoundTrip() throws IOException {
        File file = new File(folder.getRoot(), "stats/events.csv");
        CallEventStore store = new CallEventStore(file);

        CallStatEvent a = blocked(NOW - 2 * DAY, "+4930123456");
        CallStatEvent b = new CallStatEvent(NOW - DAY, "", Outcome.BLOCKED, Reason.HIDDEN, null);
        CallStatEvent c = new CallStatEvent(NOW, "+4989123456", Outcome.NOTIFIED,
                Reason.RATING, "yacb");
        assertTrue(store.record(a));
        assertTrue(store.record(b));
        assertTrue(store.record(c));

        assertEquals(Arrays.asList(a, b, c), new CallEventStore(file).load(NOW));

        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(text, text.startsWith("format,callguard-call-events,1\n"));
    }

    @Test
    public void repeatedReportIsWrittenOnce() throws IOException {
        File file = new File(folder.getRoot(), "events.csv");
        CallEventStore store = new CallEventStore(file);

        // the call screening service, then the phone state listener
        assertTrue(store.record(allowed(NOW, "+4930123456")));
        assertFalse(store.record(allowed(NOW + 2000, "+4930123456")));
        // another number is another call
        assertTrue(store.record(allowed(NOW + 3000, "+4989123456")));
        // the same number much later is a new call
        assertTrue(store.record(allowed(NOW + 60_000, "+4930123456")));

        assertEquals(3, store.load(NOW + 60_000).size());
    }

    @Test
    public void strongerRepeatedReportWins() throws IOException {
        File file = new File(folder.getRoot(), "events.csv");
        CallEventStore store = new CallEventStore(file);

        assertTrue(store.record(allowed(NOW, "+4930123456")));
        assertTrue(store.record(blocked(NOW + 1000, "+4930123456")));
        assertFalse(store.record(allowed(NOW + 2000, "+4930123456")));

        List<CallStatEvent> events = new CallEventStore(file).load(NOW);
        assertEquals(1, events.size());
        assertEquals(Outcome.BLOCKED, events.get(0).getOutcome());
        assertEquals(Reason.LIST, events.get(0).getReason());
        assertEquals("bnetza", events.get(0).getSourceId());
        assertEquals(NOW, events.get(0).getTimestamp());
    }

    @Test
    public void loadSkipsBrokenAndUnknownLinesAndOldEvents() throws IOException {
        File file = new File(folder.getRoot(), "events.csv");
        Files.write(file.toPath(), ("format,callguard-call-events,1\n"
                + "c," + (NOW - 400 * DAY) + ",+491,BLOCKED,LIST,x\n"
                + "c,notanumber,+492,BLOCKED,LIST,x\n"
                + "z,something new\n"
                + "c," + NOW + ",+493,BLOCKED,FUTURE_REASON,\n"
                + "c," + NOW + ",+494,UNKNOWN_OUTCOME,LIST,\n"
                + "c," + (NOW + 100_000) + ",+495,BLO").getBytes(StandardCharsets.UTF_8));

        List<CallStatEvent> events = new CallEventStore(file).load(NOW);
        assertEquals(1, events.size());
        assertEquals("+493", events.get(0).getNumber());
        assertEquals(Reason.OTHER, events.get(0).getReason());
    }

    @Test
    public void appendAfterCutOffLineStartsANewLine() throws IOException {
        File file = new File(folder.getRoot(), "events.csv");
        Files.write(file.toPath(), ("format,callguard-call-events,1\n"
                + "c," + NOW + ",+491,BLOCKED,LIST,x\nc,12").getBytes(StandardCharsets.UTF_8));

        CallEventStore store = new CallEventStore(file);
        store.record(blocked(NOW + 60_000, "+492"));

        List<CallStatEvent> events = store.load(NOW + 60_000);
        assertEquals(2, events.size());
        assertEquals("+492", events.get(1).getNumber());
    }

    @Test
    public void newerVersionIsRejected() throws IOException {
        File file = new File(folder.getRoot(), "events.csv");
        Files.write(file.toPath(), "format,callguard-call-events,2\n".getBytes(StandardCharsets.UTF_8));
        try {
            new CallEventStore(file).load(NOW);
            fail();
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void compactionDropsExpiredEvents() throws IOException {
        File file = new File(folder.getRoot(), "events.csv");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("format,callguard-call-events,1\n".getBytes(StandardCharsets.UTF_8));
            out.write(CallEventStore.format(blocked(NOW - 400 * DAY, "+491"))
                    .getBytes(StandardCharsets.UTF_8));
            out.write(CallEventStore.format(blocked(NOW - 10 * DAY, "+492"))
                    .getBytes(StandardCharsets.UTF_8));
        }

        CallEventStore store = new CallEventStore(file);
        // the first record of the process notices the expired event
        store.record(blocked(NOW, "+493"));

        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertFalse(text, text.contains("+491"));
        assertTrue(text, text.contains("+492"));
        assertTrue(text, text.contains("+493"));
        assertFalse(new File(file.getPath() + CallEventStore.TMP_SUFFIX).exists());
    }

    @Test
    public void sizeCapKeepsNewestEvents() throws IOException {
        File file = new File(folder.getRoot(), "events.csv");
        CallEventStore store = new CallEventStore(file, 2000, 1000);

        for (int i = 0; i < 200; i++) {
            store.record(blocked(NOW + i * 60_000L, "+49301234" + (1000 + i)));
            assertTrue("size " + file.length(), file.length() <= 2000 + 100);
        }

        List<CallStatEvent> events = store.load(NOW + 200 * 60_000L);
        assertTrue(events.size() > 10);
        assertTrue(events.size() < 200);
        assertEquals("+49301234" + 1199, events.get(events.size() - 1).getNumber());
    }

    @Test
    public void maxEventsIsRespected() throws IOException {
        File file = new File(folder.getRoot(), "events.csv");
        CallEventStore store = new CallEventStore(file, 1_000_000, 5);
        for (int i = 0; i < 10; i++) store.record(blocked(NOW + i * 60_000L, "+49" + i));
        store.compact(NOW + DAY);

        List<CallStatEvent> events = store.load(NOW + DAY);
        assertEquals(5, events.size());
        assertEquals("+495", events.get(0).getNumber());
    }

    @Test
    public void clearDeletesEverything() throws IOException {
        File file = new File(folder.getRoot(), "events.csv");
        CallEventStore store = new CallEventStore(file);
        store.record(blocked(NOW, "+491"));
        store.clear();
        assertTrue(store.load(NOW).isEmpty());
        // the same call can be recorded again after clearing
        assertTrue(store.record(blocked(NOW, "+491")));
    }

    @Test
    public void valuesWithSeparatorsAreSanitized() throws IOException {
        File file = new File(folder.getRoot(), "events.csv");
        CallEventStore store = new CallEventStore(file);
        store.record(new CallStatEvent(NOW, "+49,30\n1", Outcome.BLOCKED, Reason.LIST, "a,b"));
        List<CallStatEvent> events = store.load(NOW);
        assertEquals(1, events.size());
        assertEquals("+49301", events.get(0).getNumber());
        assertEquals("ab", events.get(0).getSourceId());
    }

}
