package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportQueue.Entry;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportQueue.Status;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportQueue.Verdict;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PhoneBlockReportQueueTest {

    private static final String NUMBER = "+4930123456";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file() throws IOException {
        return new File(tmp.getRoot(), "phoneblock/reports.tsv");
    }

    @Test
    public void requestMakesPendingAndSentClearsIt() throws IOException {
        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file());
        assertEquals(Status.NONE, queue.getStatus(NUMBER));

        Entry entry = queue.request("004930123456", Verdict.SPAM,
                PhoneBlockClient.Rating.E_ADVERTISING, 1000);
        assertEquals(NUMBER, entry.getNumber());
        assertEquals(Status.PENDING, queue.getStatus(NUMBER));
        assertEquals(1, queue.getPendingCount());

        queue.markSent(NUMBER, Verdict.SPAM, PhoneBlockClient.Rating.E_ADVERTISING, 2000, true);
        assertEquals(Status.SENT, queue.getStatus(NUMBER));
        assertEquals(0, queue.getPendingCount());
        assertEquals(1, queue.getSentCount());
        assertEquals(2000, queue.get(NUMBER).getSentAt());
    }

    @Test
    public void invalidNumbersAreIgnored() throws IOException {
        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file());
        assertNull(queue.request("030123456", Verdict.SPAM, null, 1));
        assertNull(queue.request(null, Verdict.SPAM, null, 1));
        assertEquals(0, queue.getPendingCount());
        assertEquals(Status.NONE, queue.getStatus(null));
    }

    @Test
    public void spamWithoutRatingUsesNegativeDefault() throws IOException {
        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file());
        assertEquals(PhoneBlockClient.Rating.B_MISSED,
                queue.request(NUMBER, Verdict.SPAM, null, 1).getWantedRating());
        assertEquals(PhoneBlockClient.Rating.B_MISSED, queue.request(NUMBER, Verdict.SPAM,
                PhoneBlockClient.Rating.A_LEGITIMATE, 1).getWantedRating());
        assertNull(queue.request(NUMBER, Verdict.LEGITIMATE,
                PhoneBlockClient.Rating.G_FRAUD, 1).getWantedRating());
    }

    @Test
    public void markAndUnmarkBeforeSendingSendsNothing() throws IOException {
        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file());
        queue.request(NUMBER, Verdict.SPAM, PhoneBlockClient.Rating.G_FRAUD, 1);
        queue.request(NUMBER, Verdict.NONE, null, 2);
        assertEquals(0, queue.getPendingCount());
        assertNull(queue.get(NUMBER));
    }

    @Test
    public void changedCategoryAfterSendingIsPendingAgain() throws IOException {
        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file());
        queue.request(NUMBER, Verdict.SPAM, PhoneBlockClient.Rating.E_ADVERTISING, 1);
        queue.markSent(NUMBER, Verdict.SPAM, PhoneBlockClient.Rating.E_ADVERTISING, 2, true);
        queue.request(NUMBER, Verdict.SPAM, PhoneBlockClient.Rating.G_FRAUD, 3);
        assertEquals(Status.PENDING, queue.getStatus(NUMBER));

        // the same wish as sent is not pending
        queue.request(NUMBER, Verdict.SPAM, PhoneBlockClient.Rating.E_ADVERTISING, 4);
        assertEquals(Status.SENT, queue.getStatus(NUMBER));
    }

    @Test
    public void wishChangedWhileSendingStaysPending() throws IOException {
        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file());
        queue.request(NUMBER, Verdict.SPAM, PhoneBlockClient.Rating.E_ADVERTISING, 1);
        queue.request(NUMBER, Verdict.LEGITIMATE, null, 2);
        // the worker had picked up the SPAM wish
        queue.markSent(NUMBER, Verdict.SPAM, PhoneBlockClient.Rating.E_ADVERTISING, 3, true);

        Entry entry = queue.get(NUMBER);
        assertTrue(entry.isPending());
        assertEquals(Verdict.LEGITIMATE, entry.getWanted());
        assertEquals(Verdict.SPAM, entry.getSent());
    }

    @Test
    public void cancelKeepsWhatWasSent() throws IOException {
        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file());
        queue.request(NUMBER, Verdict.LEGITIMATE, null, 1);
        queue.markSent(NUMBER, Verdict.LEGITIMATE, null, 2, true);
        queue.request(NUMBER, Verdict.SPAM, PhoneBlockClient.Rating.D_POLL, 3);
        queue.cancel(NUMBER);

        Entry entry = queue.get(NUMBER);
        assertFalse(entry.isPending());
        assertEquals(Verdict.LEGITIMATE, entry.getWanted());

        queue.request("+4940111", Verdict.SPAM, null, 4);
        queue.cancel("+4940111");
        assertNull(queue.get("+4940111"));
    }

    @Test
    public void cancelAll() throws IOException {
        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file());
        queue.request(NUMBER, Verdict.SPAM, null, 1);
        queue.request("+4940111", Verdict.LEGITIMATE, null, 2);
        queue.cancelAll();
        assertEquals(0, queue.getPendingCount());
    }

    @Test
    public void failuresAndRejection() throws IOException {
        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file());
        queue.request(NUMBER, Verdict.SPAM, null, 1);
        queue.markFailed(NUMBER, "HTTP 503:\n down", false);
        Entry entry = queue.get(NUMBER);
        assertEquals(1, entry.getAttempts());
        assertEquals("HTTP 503: down", entry.getLastError());
        assertEquals(Status.PENDING, entry.getStatus());

        queue.markFailed(NUMBER, "HTTP 400: Invalid phone number.", true);
        assertEquals(Status.REJECTED, queue.getStatus(NUMBER));
        assertEquals(0, queue.getPendingCount());

        // a new mark tries again
        queue.request(NUMBER, Verdict.LEGITIMATE, null, 5);
        entry = queue.get(NUMBER);
        assertEquals(Status.PENDING, entry.getStatus());
        assertEquals(0, entry.getAttempts());
        assertNull(entry.getLastError());
    }

    @Test
    public void pendingIsOrderedByChange() throws IOException {
        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file());
        queue.request("+4940111", Verdict.SPAM, null, 20);
        queue.request("+4940222", Verdict.SPAM, null, 10);
        queue.request("+4940333", Verdict.SPAM, null, 30);
        List<Entry> pending = queue.getPending();
        assertEquals("+4940222", pending.get(0).getNumber());
        assertEquals("+4940111", pending.get(1).getNumber());
        assertEquals("+4940333", pending.get(2).getNumber());
    }

    @Test
    public void persistsAcrossInstances() throws IOException {
        File file = file();
        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file);
        queue.request(NUMBER, Verdict.SPAM, PhoneBlockClient.Rating.F_GAMBLE, 100);
        queue.markSent(NUMBER, Verdict.SPAM, PhoneBlockClient.Rating.F_GAMBLE, 200, true);
        queue.request("+4940111", Verdict.LEGITIMATE, null, 300);
        queue.markFailed("+4940111", "tab\there", false);

        PhoneBlockReportQueue reloaded = new PhoneBlockReportQueue(file);
        assertEquals(1, reloaded.getSentCount());
        Entry sent = reloaded.get(NUMBER);
        assertEquals(Verdict.SPAM, sent.getSent());
        assertEquals(PhoneBlockClient.Rating.F_GAMBLE, sent.getSentRating());
        assertEquals(200, sent.getSentAt());
        assertEquals(100, sent.getUpdated());
        Entry pending = reloaded.get("+4940111");
        assertTrue(pending.isPending());
        assertEquals(1, pending.getAttempts());
        assertEquals("tab here", pending.getLastError());
        assertFalse(new File(file.getPath() + ".tmp").exists());
    }

    @Test
    public void malformedLinesAreSkipped() throws IOException {
        File file = file();
        assertTrue(file.getParentFile().mkdirs());
        Files.write(file.toPath(), ("#pbreport\t1\t7\n"
                + "garbage\n"
                + "+4930123456\tSPAM\tG_FRAUD\tNONE\t\t5\t0\t0\t0\t\n"
                + "+49x\tSPAM\t\tNONE\t\t5\t0\t0\t0\t\n"
                + "+4940111\tMAYBE\t\tNONE\t\t5\t0\t0\t0\t\n").getBytes(StandardCharsets.UTF_8));

        PhoneBlockReportQueue queue = new PhoneBlockReportQueue(file);
        assertEquals(7, queue.getSentCount());
        assertEquals(1, queue.getAll().size());
        assertEquals(PhoneBlockClient.Rating.G_FRAUD, queue.get(NUMBER).getWantedRating());
    }

}
