package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dummydomain.yetanothercallblocker.data.SourcesManager;
import dummydomain.yetanothercallblocker.data.provider.ProviderResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BnetzaAutoUpdaterTest {

    private static final String MAIN = "https://bnetza.test/start_RM.html";
    private static final String ALIAS = "https://bnetza.test/massnahmenliste";
    private static final String YEAR_PATTERN = "https://bnetza.test/Liste%d.html";
    private static final String YEAR_2026 = "https://bnetza.test/Liste2026.html";
    private static final String YEAR_2025 = "https://bnetza.test/Liste2025.html";

    /** 2026-03-01 12:00 in Germany. */
    private static final long MARCH_2026 = LocalDate.of(2026, 3, 1)
            .atTime(12, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli();

    /** Replays queued responses (or exceptions) per URL and records the requests. */
    static class FakeDownloader implements RemoteListDownloader {

        final Map<String, Deque<Object>> responses = new HashMap<>();
        final List<String[]> requests = new ArrayList<>();

        void enqueue(String url, Object responseOrException) {
            responses.computeIfAbsent(url, k -> new ArrayDeque<>()).add(responseOrException);
        }

        @Override
        public Response download(String url, String etag, String lastModified) throws IOException {
            requests.add(new String[]{url, etag, lastModified});
            Deque<Object> queue = responses.get(url);
            Object next = queue != null ? queue.poll() : null;
            if (next == null) throw new AssertionError("Unexpected request " + url);
            if (next instanceof IOException) throw (IOException) next;
            return (Response) next;
        }

        String[] lastRequest(String url) {
            String[] last = null;
            for (String[] r : requests) if (r[0].equals(url)) last = r;
            return last;
        }

        boolean allConsumed() {
            for (Deque<Object> q : responses.values()) if (!q.isEmpty()) return false;
            return true;
        }
    }

    static class MemoryPreferences implements SourcesManager.Preferences {
        String order;
        String disabled;

        @Override
        public String getSourcesOrder() {
            return order;
        }

        @Override
        public void setSourcesOrder(String order) {
            this.order = order;
        }

        @Override
        public String getDisabledSources() {
            return disabled;
        }

        @Override
        public void setDisabledSources(String ids) {
            this.disabled = ids;
        }
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private NumberListStore store;
    private MemoryPreferences preferences;
    private SourcesManager sourcesManager;
    private FakeDownloader downloader;
    private long now = MARCH_2026;
    private BnetzaAutoUpdater updater;

    private String mainHtml;
    private String yearHtml;

    @Before
    public void setUp() throws IOException {
        store = new NumberListStore(folder.getRoot());
        preferences = new MemoryPreferences();
        sourcesManager = newSourcesManager();
        downloader = new FakeDownloader();
        updater = new BnetzaAutoUpdater(sourcesManager, downloader, () -> now,
                Arrays.asList(MAIN, ALIAS), YEAR_PATTERN);

        mainHtml = BnetzaMeasuresParserTest.readResource("bnetza_massnahmenliste_sample.html");
        yearHtml = BnetzaMeasuresParserTest.readResource("bnetza_massnahmenliste_year_sample.html");
    }

    private SourcesManager newSourcesManager() {
        return new SourcesManager(store, preferences, Collections.emptyList());
    }

    private static RemoteListDownloader.Response ok(String body, String etag) {
        return RemoteListDownloader.Response.ok(body.getBytes(StandardCharsets.UTF_8), etag, null);
    }

    private static RemoteListDownloader.Response notModified(String etag) {
        return RemoteListDownloader.Response.notModified(etag, null);
    }

    private static RemoteListDownloader.HttpStatusException http(int code) {
        return new RemoteListDownloader.HttpStatusException(code, null);
    }

    private RemoteListInfo storedRemote() {
        return sourcesManager.getSource(SourcesManager.BNETZA_SOURCE_ID).getMetadata().getRemote();
    }

    /** First download: main page + 2026 list, the 2025 list doesn't exist. */
    private BnetzaAutoUpdater.Result initialUpdate() {
        downloader.enqueue(MAIN, ok(mainHtml, "\"m1\""));
        downloader.enqueue(YEAR_2026, ok(yearHtml, "\"y1\""));
        downloader.enqueue(YEAR_2025, http(404));
        return updater.update(false);
    }

    @Test
    public void firstUpdateDownloadsMergesAndStores() throws IOException {
        assertTrue(updater.needsInitialDownload());
        assertTrue(updater.isEnabled());

        BnetzaAutoUpdater.Result result = initialUpdate();

        assertEquals(BnetzaAutoUpdater.Result.Status.UPDATED, result.getStatus());
        assertTrue(result.isSuccess());
        assertNull(result.getError());
        // 13 from the page + 2 new from the yearly list; 2 numbers are on both
        assertEquals(15, result.getEntryCount());
        assertEquals(15, result.getAddedCount());
        assertEquals(0, result.getRemovedCount());
        assertEquals(2, result.getSkippedRowCount());
        assertEquals(Arrays.asList(MAIN, YEAR_2026), result.getDownloadedUrls());
        assertTrue(result.getWarnings().isEmpty()); // a 404 is no warning
        assertEquals(now, result.getCheckedAt());
        assertTrue(downloader.allConsumed());
        assertFalse(updater.needsInitialDownload());

        SourcesManager.SourceInfo source = sourcesManager.getSource("bnetza");
        assertNotNull(source);
        assertEquals("Bundesnetzagentur", source.getDisplayName());
        assertTrue(source.isEnabled());
        assertEquals(15, source.getMetadata().getEntryCount());
        assertEquals(now, source.getMetadata().getImportedAt());

        RemoteListInfo remote = source.getMetadata().getRemote();
        assertEquals(BnetzaMeasuresParser.SOURCE_URL, remote.getUrl());
        assertEquals("\"m1\"", remote.getEtag());
        assertEquals(now, remote.getLastCheckAt());
        assertEquals(now, remote.getLastSuccessAt());
        assertNull(remote.getLastError());
        assertEquals(2, remote.getParts().size());
        assertEquals("\"y1\"", remote.getPart(YEAR_2026).getEtag());

        // the newer decision wins: 2026 list for ...001, the page for ...002
        List<ListedNumber> entries = sourcesManager.loadListEntries("bnetza");
        assertEquals(LocalDate.of(2026, 1, 20), find(entries, "+4971100000001").getDate());
        assertEquals("Spam-Messenger", find(entries, "+4971100000001").getCategory());
        assertEquals(LocalDate.of(2025, 9, 9), find(entries, "+4942100000002").getDate());
        assertNotNull(find(entries, "+4930000000101"));
        // newest first
        assertEquals(LocalDate.of(2026, 1, 20), entries.get(0).getDate());

        // the numbers are blocked
        ProviderResult lookup = sourcesManager.lookupListedNumbers("+4930000000100");
        assertNotNull(lookup);
        assertEquals("bnetza", lookup.getSourceId());

        BnetzaAutoUpdater.Status status = updater.getStatus();
        assertTrue(status.isListStored());
        assertTrue(status.isAutoUpdated());
        assertEquals(15, status.getEntryCount());
        assertEquals(now, status.getLastSuccessAt());
        assertNull(status.getLastError());

        // persisted
        RemoteListInfo reloaded = newSourcesManager().getSource("bnetza").getMetadata().getRemote();
        assertEquals(remote, reloaded);
    }

    @Test
    public void conditionalRequestNotModified() throws IOException {
        initialUpdate();
        now += 1000;

        downloader.enqueue(MAIN, notModified("\"m1\""));
        downloader.enqueue(YEAR_2026, notModified("\"y1\""));
        downloader.enqueue(YEAR_2025, http(404));
        BnetzaAutoUpdater.Result result = updater.update(false);

        assertEquals(BnetzaAutoUpdater.Result.Status.NOT_MODIFIED, result.getStatus());
        assertEquals(15, result.getEntryCount());
        assertEquals("\"m1\"", downloader.lastRequest(MAIN)[1]);
        assertEquals("\"y1\"", downloader.lastRequest(YEAR_2026)[1]);
        assertNull(downloader.lastRequest(YEAR_2025)[1]);
        assertTrue(downloader.allConsumed());

        RemoteListInfo remote = storedRemote();
        assertEquals(now, remote.getLastCheckAt());
        assertEquals(now, remote.getLastSuccessAt());
        assertEquals(MARCH_2026, sourcesManager.getSource("bnetza").getMetadata().getImportedAt());
        assertEquals(15, sourcesManager.loadListEntries("bnetza").size());
    }

    @Test
    public void changedYearlyListRefetchesUnchangedPage() throws IOException {
        initialUpdate();
        now += 1000;

        downloader.enqueue(MAIN, notModified("\"m1\""));
        downloader.enqueue(YEAR_2026, ok(yearHtml.replace("030000000101", "030000000102"), "\"y2\""));
        downloader.enqueue(YEAR_2025, http(404));
        // the unchanged page is needed for the merge
        downloader.enqueue(MAIN, ok(mainHtml, "\"m1\""));

        BnetzaAutoUpdater.Result result = updater.update(false);

        assertEquals(BnetzaAutoUpdater.Result.Status.UPDATED, result.getStatus());
        assertEquals(15, result.getEntryCount());
        assertEquals(1, result.getAddedCount());
        assertEquals(1, result.getRemovedCount());
        assertTrue(downloader.allConsumed());
        assertNull("full download", downloader.lastRequest(MAIN)[1]);
        assertEquals("\"y2\"", storedRemote().getPart(YEAR_2026).getEtag());
        assertNull(find(sourcesManager.loadListEntries("bnetza"), "+4930000000101"));
    }

    @Test
    public void forceIgnoresValidators() {
        initialUpdate();
        now += 1000;

        downloader.enqueue(MAIN, ok(mainHtml, "\"m2\""));
        downloader.enqueue(YEAR_2026, ok(yearHtml, "\"y1\""));
        downloader.enqueue(YEAR_2025, http(404));
        BnetzaAutoUpdater.Result result = updater.update(true);

        assertEquals(BnetzaAutoUpdater.Result.Status.UPDATED, result.getStatus());
        assertEquals(0, result.getAddedCount());
        assertNull(downloader.lastRequest(MAIN)[1]);
        assertNull(downloader.lastRequest(YEAR_2026)[1]);
    }

    @Test
    public void replacesManuallyImportedList() throws IOException {
        sourcesManager.importList("bnetza", "Bundesnetzagentur", Collections.singletonList(
                ListedNumber.builder().number("+4989000000999").build()), 5);
        assertFalse(updater.needsInitialDownload());
        assertFalse(updater.getStatus().isAutoUpdated());

        BnetzaAutoUpdater.Result result = initialUpdate();

        assertEquals(BnetzaAutoUpdater.Result.Status.UPDATED, result.getStatus());
        assertEquals(15, result.getAddedCount());
        assertEquals(1, result.getRemovedCount());
        // no validators: the manual list has no download state
        assertNull(downloader.lastRequest(MAIN)[1]);
        assertNull(sourcesManager.lookupListedNumbers("+4989000000999"));
        assertEquals(1, sourcesManager.getSources().size());
        assertTrue(updater.getStatus().isAutoUpdated());
    }

    @Test
    public void failureKeepsOldListAndRecordsError() throws IOException {
        initialUpdate();
        now += 1000;

        // an error page without the table, then the short link fails too
        downloader.enqueue(MAIN, ok("<html><body>Wartungsarbeiten</body></html>", null));
        downloader.enqueue(ALIAS, http(503));
        BnetzaAutoUpdater.Result result = updater.update(false);

        assertEquals(BnetzaAutoUpdater.Result.Status.FAILED, result.getStatus());
        assertFalse(result.isSuccess());
        assertEquals("No entries found on " + MAIN, result.getError());
        assertEquals(15, result.getEntryCount());
        assertTrue(downloader.allConsumed());

        assertEquals(15, sourcesManager.loadListEntries("bnetza").size());
        RemoteListInfo remote = storedRemote();
        assertEquals("No entries found on " + MAIN, remote.getLastError());
        assertEquals(now, remote.getLastCheckAt());
        assertEquals(MARCH_2026, remote.getLastSuccessAt());
        // the validators stay, so the next check can still be conditional
        assertEquals("\"m1\"", remote.getEtag());
        assertEquals(2, remote.getParts().size());

        BnetzaAutoUpdater.Status status = updater.getStatus();
        assertEquals(remote.getLastError(), status.getLastError());
        assertEquals(MARCH_2026, status.getLastSuccessAt());

        // a later success clears the error
        now += 1000;
        downloader.enqueue(MAIN, notModified("\"m1\""));
        downloader.enqueue(YEAR_2026, notModified("\"y1\""));
        downloader.enqueue(YEAR_2025, http(404));
        assertEquals(BnetzaAutoUpdater.Result.Status.NOT_MODIFIED, updater.update(false).getStatus());
        assertNull(storedRemote().getLastError());
    }

    @Test
    public void networkErrorWithoutListStoresNothing() {
        downloader.enqueue(MAIN, new SocketTimeoutException("timeout"));
        downloader.enqueue(ALIAS, new SocketTimeoutException("timeout"));

        BnetzaAutoUpdater.Result result = updater.update(false);

        assertEquals(BnetzaAutoUpdater.Result.Status.FAILED, result.getStatus());
        assertEquals("timeout", result.getError());
        assertEquals(0, result.getEntryCount());
        assertNull(sourcesManager.getSource("bnetza"));
        assertTrue(updater.needsInitialDownload());

        BnetzaAutoUpdater.Status status = updater.getStatus();
        assertFalse(status.isListStored());
        assertEquals("timeout", status.getLastError());
        assertEquals(now, status.getLastCheckAt());
        assertEquals(result, updater.getLastResult());
    }

    @Test
    public void fallsBackToShortLink() {
        downloader.enqueue(MAIN, http(404));
        downloader.enqueue(ALIAS, ok(mainHtml, "\"a1\""));
        downloader.enqueue(YEAR_2026, http(404));
        downloader.enqueue(YEAR_2025, http(404));

        BnetzaAutoUpdater.Result result = updater.update(false);

        assertEquals(BnetzaAutoUpdater.Result.Status.UPDATED, result.getStatus());
        assertEquals(13, result.getEntryCount());
        assertEquals(Collections.singletonList(ALIAS), result.getDownloadedUrls());
        assertEquals("\"a1\"", storedRemote().getEtag());
    }

    @Test
    public void failedYearlyListIsAWarning() {
        downloader.enqueue(MAIN, ok(mainHtml, null));
        downloader.enqueue(YEAR_2026, http(403));
        downloader.enqueue(YEAR_2025, new SocketTimeoutException("timeout"));

        BnetzaAutoUpdater.Result result = updater.update(false);

        assertEquals(BnetzaAutoUpdater.Result.Status.UPDATED, result.getStatus());
        assertEquals(13, result.getEntryCount());
        assertEquals(2, result.getWarnings().size());
        assertEquals(YEAR_2026 + ": HTTP 403", result.getWarnings().get(0));
    }

    @Test
    public void yearlyListsLinkedFromThePage() {
        String page = mainHtml.replace("<h1>",
                "<a href=\"/SharedDocs/Downloads/Massnahmenlisten/Ma%C3%9Fnahmenliste2024.html?nn=1\">2024</a>"
                        + "<a href='https://bnetza.test/Massnahmenlisten/Liste2026.html'>dup</a>"
                        + "<a href=\"/Massnahmenlisten/Ma%C3%9Fnahmenliste2019.html\">old</a><h1>");
        // in 2025 the pattern yields the lists 2025 and 2024; linked lists before 2024 are ignored
        now = LocalDate.of(2025, 3, 1).atStartOfDay(ZoneId.of("Europe/Berlin"))
                .toInstant().toEpochMilli();
        String linked2024 = "https://bnetza.test/SharedDocs/Downloads/Massnahmenlisten/"
                + "Ma%C3%9Fnahmenliste2024.html?nn=1";
        String linked2026 = "https://bnetza.test/Massnahmenlisten/Liste2026.html";
        downloader.enqueue(MAIN, ok(page, null));
        downloader.enqueue(YEAR_2025, http(404));
        downloader.enqueue("https://bnetza.test/Liste2024.html", http(404));
        downloader.enqueue(linked2024, ok(yearHtml, null));
        downloader.enqueue(linked2026, http(404));

        BnetzaAutoUpdater.Result result = updater.update(false);

        assertEquals(BnetzaAutoUpdater.Result.Status.UPDATED, result.getStatus());
        assertEquals(Arrays.asList(MAIN, linked2024), result.getDownloadedUrls());
        assertTrue(downloader.allConsumed());
    }

    @Test
    public void findYearlyListLinks() {
        String html = "<a href=\"/DE/Massnahmenlisten/Ma%C3%9Fnahmenliste2025.html\">a</a>"
                + "<a href=\"../Massnahmenlisten/Ma&szlig;nahmenliste2024.html\">b</a>"
                + "<a href=\"/DE/Massnahmenlisten/Ma%C3%9Fnahmenliste2025.pdf?__blob=publicationFile\">pdf</a>"
                + "<a href=\"/DE/Massnahmenlisten/Ma%C3%9Fnahmenliste2020.html\">old</a>"
                + "<a href=\"/DE/andere/Seite2025.html\">other</a>";

        List<String> links = BnetzaAutoUpdater.findYearlyListLinks(html,
                "https://example.org/DE/Aktuelles/start.html", 2024);

        assertEquals(Arrays.asList(
                "https://example.org/DE/Massnahmenlisten/Ma%C3%9Fnahmenliste2025.html",
                "https://example.org/DE/Massnahmenlisten/Maßnahmenliste2024.html"), links);
    }

    @Test
    public void mergeKeepsMostRecentDecision() {
        ListedNumber oldA = ListedNumber.builder().number("+491").date(LocalDate.of(2024, 1, 1))
                .category("old").build();
        ListedNumber newA = ListedNumber.builder().number("+491").date(LocalDate.of(2025, 1, 1))
                .category("new").build();
        ListedNumber sameDate = ListedNumber.builder().number("+491").date(LocalDate.of(2025, 1, 1))
                .category("later list").build();
        ListedNumber prefix = ListedNumber.builder().prefix("+491").build();

        List<ListedNumber> merged = BnetzaAutoUpdater.merge(Arrays.asList(
                Arrays.asList(oldA, prefix), Arrays.asList(newA), Arrays.asList(sameDate)));

        assertEquals(2, merged.size());
        assertEquals("new", merged.get(0).getCategory());
        assertTrue(merged.get(1).isPrefix()); // undated last
    }

    @Test
    public void decodesUtf8AndWindows1252() {
        assertEquals("Maßnahme", BnetzaAutoUpdater.decode("Maßnahme".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Maßnahme", BnetzaAutoUpdater.decode(
                "Maßnahme".getBytes(java.nio.charset.Charset.forName("windows-1252"))));
        assertEquals("", BnetzaAutoUpdater.decode(null));
    }

    @Test
    public void disabledSourceIsReported() {
        sourcesManager.setEnabled("bnetza", false);
        assertFalse(updater.isEnabled());
    }

    private static ListedNumber find(List<ListedNumber> entries, String number) {
        for (ListedNumber e : entries) {
            if (number.equals(e.getNumber())) return e;
        }
        return null;
    }

}
