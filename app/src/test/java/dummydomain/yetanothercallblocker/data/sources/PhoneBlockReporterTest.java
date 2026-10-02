package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;

import dummydomain.yetanothercallblocker.data.UserMark;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportQueue.Status;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportQueue.Verdict;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockReporter.Category;

import static dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportTestSupport.AUTH_REQUIRED;
import static dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportTestSupport.NOT_IN_LIST;
import static dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportTestSupport.RATE_INVALID;
import static dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportTestSupport.RATE_OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhoneBlockReporterTest {

    private static final String TOKEN = "pbt_test-token";
    private static final String NUMBER = "+4930123456";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private PhoneBlockReportTestSupport.FakeTransport transport;
    private PhoneBlockReportQueue queue;
    private PhoneBlockReporter reporter;
    private long now = 1000;

    @Before
    public void setUp() {
        transport = new PhoneBlockReportTestSupport.FakeTransport();
        queue = new PhoneBlockReportQueue(new File(tmp.getRoot(), "reports.tsv"));
        reporter = new PhoneBlockReporter(new PhoneBlockClient(transport, null, null), queue,
                () -> now);
    }

    @Test
    public void shouldReport() {
        assertTrue(PhoneBlockReporter.shouldReport(true, TOKEN, false, false, NUMBER));
        assertFalse(PhoneBlockReporter.shouldReport(false, TOKEN, false, false, NUMBER));
        assertFalse(PhoneBlockReporter.shouldReport(true, "", false, false, NUMBER));
        assertFalse(PhoneBlockReporter.shouldReport(true, null, false, false, NUMBER));
        // contacts and hidden numbers are never reported
        assertFalse(PhoneBlockReporter.shouldReport(true, TOKEN, true, false, NUMBER));
        assertFalse(PhoneBlockReporter.shouldReport(true, TOKEN, false, true, NUMBER));
        // only numbers in international form can be reported
        assertFalse(PhoneBlockReporter.shouldReport(true, TOKEN, false, false, "030123456"));
        assertFalse(PhoneBlockReporter.shouldReport(true, TOKEN, false, false, null));
    }

    @Test
    public void categories() {
        assertEquals(Category.ADVERTISING, Category.DEFAULT);
        assertEquals(PhoneBlockClient.Rating.E_ADVERTISING, Category.ADVERTISING.getRating());
        assertEquals(PhoneBlockClient.Rating.D_POLL, Category.POLL.getRating());
        assertEquals(PhoneBlockClient.Rating.G_FRAUD, Category.FRAUD.getRating());
        assertEquals(PhoneBlockClient.Rating.C_PING, Category.PING.getRating());
        assertEquals(PhoneBlockClient.Rating.F_GAMBLE, Category.GAMBLE.getRating());
        assertEquals(PhoneBlockClient.Rating.B_MISSED, Category.OTHER.getRating());
        for (Category c : Category.values()) {
            assertTrue(c.getRating() != PhoneBlockClient.Rating.A_LEGITIMATE);
        }
        assertEquals(Category.FRAUD, Category.fromName("FRAUD"));
        assertEquals(Category.DEFAULT, Category.fromName("nonsense"));
        assertEquals(Category.DEFAULT, Category.fromName(null));
    }

    @Test
    public void spamMarkIsRatedWithCategory() throws IOException {
        reporter.onMarkChanged(NUMBER, UserMark.Type.SPAM, Category.FRAUD);
        transport.respond(200, RATE_OK);

        PhoneBlockReporter.Result result = reporter.sendPending(TOKEN);

        assertEquals(1, result.getSent());
        assertFalse(result.shouldRetry());
        assertEquals("POST", transport.requests.get(0).method);
        assertEquals("{\"phone\":\"+4930123456\",\"rating\":\"G_FRAUD\"}",
                transport.requests.get(0).body);
        assertEquals(Status.SENT, queue.getStatus(NUMBER));
        assertEquals(1, queue.getSentCount());
    }

    @Test
    public void spamMarkWithoutCategoryIsAdvertising() throws IOException {
        reporter.onMarkChanged(NUMBER, UserMark.Type.SPAM, null);
        transport.respond(200, RATE_OK);
        reporter.sendPending(TOKEN);
        assertTrue(transport.requests.get(0).body.contains("\"E_ADVERTISING\""));
    }

    @Test
    public void notSpamMarkIsRatedLegitimate() throws IOException {
        reporter.onMarkChanged(NUMBER, UserMark.Type.NOT_SPAM, Category.FRAUD);
        transport.respond(200, RATE_OK);
        reporter.sendPending(TOKEN);
        assertEquals("{\"phone\":\"+4930123456\",\"rating\":\"A_LEGITIMATE\"}",
                transport.requests.get(0).body);
        assertEquals(Verdict.LEGITIMATE, queue.get(NUMBER).getSent());
    }

    @Test
    public void removedMarkWithdrawsTheReport() throws IOException {
        reporter.onMarkChanged(NUMBER, UserMark.Type.SPAM, null);
        transport.respond(200, RATE_OK);
        reporter.sendPending(TOKEN);

        reporter.onMarkChanged(NUMBER, null, null);
        transport.respond(204, null);
        PhoneBlockReporter.Result result = reporter.sendPending(TOKEN);

        assertEquals(1, result.getSent());
        assertEquals("DELETE", transport.requests.get(1).method);
        assertEquals(PhoneBlockClient.DEFAULT_BASE_URL + "/blacklist/+4930123456",
                transport.requests.get(1).url);
        assertEquals(Status.NONE, queue.getStatus(NUMBER));
        // removals are not counted as reports
        assertEquals(1, queue.getSentCount());
    }

    @Test
    public void removedNotSpamMarkUsesWhitelistAndAccepts404() throws IOException {
        reporter.onMarkChanged(NUMBER, UserMark.Type.NOT_SPAM, null);
        transport.respond(200, RATE_OK);
        reporter.sendPending(TOKEN);

        reporter.onMarkChanged(NUMBER, null, null);
        transport.respond(404, NOT_IN_LIST);
        reporter.sendPending(TOKEN);

        assertEquals(PhoneBlockClient.DEFAULT_BASE_URL + "/whitelist/+4930123456",
                transport.requests.get(1).url);
        assertEquals(Status.NONE, queue.getStatus(NUMBER));
    }

    @Test
    public void undoBeforeSendingSendsNothing() throws IOException {
        reporter.onMarkChanged(NUMBER, UserMark.Type.NOT_SPAM, null);
        reporter.onMarkChanged(NUMBER, null, null);
        PhoneBlockReporter.Result result = reporter.sendPending(TOKEN);
        assertEquals(0, result.getSent());
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void networkErrorIsRetried() throws IOException {
        reporter.onMarkChanged(NUMBER, UserMark.Type.SPAM, null);
        reporter.onMarkChanged("+4940111", UserMark.Type.SPAM, null);
        transport.fail(new SocketTimeoutException("timeout"));

        PhoneBlockReporter.Result result = reporter.sendPending(TOKEN);

        assertTrue(result.shouldRetry());
        assertEquals(1, transport.requests.size()); // stopped at the first error
        assertEquals(2, queue.getPendingCount());
        assertEquals(1, queue.get(NUMBER).getAttempts());

        transport.respond(200, RATE_OK).respond(200, RATE_OK);
        result = reporter.sendPending(TOKEN);
        assertEquals(2, result.getSent());
        assertEquals(0, queue.getPendingCount());
    }

    @Test
    public void serverErrorAndRateLimitAreRetried() throws IOException {
        reporter.onMarkChanged(NUMBER, UserMark.Type.SPAM, null);
        transport.respond(503, "down");
        assertTrue(reporter.sendPending(TOKEN).shouldRetry());
        transport.respond(429, null);
        assertTrue(reporter.sendPending(TOKEN).shouldRetry());
        assertEquals(Status.PENDING, queue.getStatus(NUMBER));
    }

    @Test
    public void authErrorStopsWithoutRetry() throws IOException {
        reporter.onMarkChanged(NUMBER, UserMark.Type.SPAM, null);
        reporter.onMarkChanged("+4940111", UserMark.Type.SPAM, null);
        transport.respond(401, AUTH_REQUIRED);

        PhoneBlockReporter.Result result = reporter.sendPending(TOKEN);

        assertTrue(result.isAuthError());
        assertFalse(result.shouldRetry());
        assertEquals(1, transport.requests.size());
        // kept for a later run with a valid key
        assertEquals(2, queue.getPendingCount());
    }

    @Test
    public void rejectedNumberIsSkipped() throws IOException {
        reporter.onMarkChanged(NUMBER, UserMark.Type.SPAM, null);
        reporter.onMarkChanged("+4940111", UserMark.Type.SPAM, null);
        transport.respond(400, RATE_INVALID).respond(200, RATE_OK);

        PhoneBlockReporter.Result result = reporter.sendPending(TOKEN);

        assertEquals(1, result.getSent());
        assertEquals(1, result.getRejected());
        assertFalse(result.shouldRetry());
        assertEquals(Status.REJECTED, queue.getStatus(NUMBER));
        assertEquals(Status.SENT, queue.getStatus("+4940111"));
    }

    @Test
    public void limitsRequestsPerRun() throws IOException {
        for (int i = 0; i < PhoneBlockReporter.MAX_REQUESTS_PER_RUN + 2; i++) {
            now++;
            reporter.onMarkChanged("+49401" + (10000 + i), UserMark.Type.SPAM, null);
            transport.respond(200, RATE_OK);
        }
        PhoneBlockReporter.Result result = reporter.sendPending(TOKEN);
        assertEquals(PhoneBlockReporter.MAX_REQUESTS_PER_RUN, result.getSent());
        assertTrue(result.shouldRetry());
        assertEquals(2, queue.getPendingCount());
    }

}
