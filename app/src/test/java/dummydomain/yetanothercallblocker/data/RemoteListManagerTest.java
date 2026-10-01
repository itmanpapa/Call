package dummydomain.yetanothercallblocker.data;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import dummydomain.yetanothercallblocker.data.provider.ProviderResult;
import dummydomain.yetanothercallblocker.data.sources.NumberListFormatDetector;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;
import dummydomain.yetanothercallblocker.data.sources.RemoteListDownloader;
import dummydomain.yetanothercallblocker.data.sources.RemoteListInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RemoteListManagerTest {

    private static final String URL = "https://example.org/lists/Spam%20Liste.xml";

    private static final String XML_V1 = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<phonebooks><phonebook name=\"Rufsperren\"><contact><person>"
            + "<realName>Gewinnspiel</realName></person><telephony>"
            + "<number>015112345678</number><number>0900123*</number>"
            + "</telephony></contact></phonebook></phonebooks>";

    private static final String VCARD_V2 = "BEGIN:VCARD\nFN:Neu\nTEL:030 1234567\nEND:VCARD\n";

    /** Records the requests and replays queued responses or exceptions. */
    static class FakeDownloader implements RemoteListDownloader {

        final Deque<Object> responses = new ArrayDeque<>();
        final List<String[]> requests = new ArrayList<>();

        void enqueue(Object responseOrException) {
            responses.add(responseOrException);
        }

        @Override
        public Response download(String url, String etag, String lastModified) throws IOException {
            requests.add(new String[]{url, etag, lastModified});
            Object next = responses.poll();
            if (next == null) throw new AssertionError("Unexpected request " + url);
            if (next instanceof IOException) throw (IOException) next;
            return (Response) next;
        }
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private NumberListStore store;
    private SourcesManager sourcesManager;
    private FakeDownloader downloader;
    private long now = 1000;
    private RemoteListManager manager;

    @Before
    public void setUp() {
        store = new NumberListStore(folder.getRoot());
        sourcesManager = newSourcesManager();
        downloader = new FakeDownloader();
        manager = new RemoteListManager(sourcesManager, downloader, () -> now);
    }

    private SourcesManager newSourcesManager() {
        return new SourcesManager(store, new SourcesManagerTest.MemoryPreferences(),
                Collections.emptyList());
    }

    private static RemoteListDownloader.Response ok(String body, String etag) {
        return RemoteListDownloader.Response.ok(body.getBytes(StandardCharsets.UTF_8), etag, null);
    }

    @Test
    public void addListDownloadsParsesAndStores() throws IOException {
        downloader.enqueue(ok(XML_V1, "\"v1\""));

        RemoteListManager.UpdateResult result = manager.addList(URL, null, true);

        assertEquals(RemoteListManager.UpdateResult.Status.UPDATED, result.getStatus());
        assertEquals(2, result.getParseResult().getEntries().size());
        String id = result.getSourceId();
        assertEquals(RemoteListManager.sourceIdForUrl(URL), id);
        assertTrue(id.startsWith(RemoteListManager.SOURCE_ID_PREFIX));

        SourcesManager.SourceInfo source = sourcesManager.getSource(id);
        assertNotNull(source);
        assertEquals("Spam Liste", source.getDisplayName());
        assertEquals(2, source.getMetadata().getEntryCount());
        assertEquals(now, source.getMetadata().getImportedAt());

        RemoteListInfo remote = RemoteListManager.getRemote(source);
        assertEquals(URL, remote.getUrl());
        assertTrue(remote.isAutoUpdate());
        assertEquals("\"v1\"", remote.getEtag());
        assertEquals(now, remote.getLastCheckAt());
        assertNull(remote.getLastError());

        ProviderResult hit = sourcesManager.lookupListedNumbers("+4915112345678");
        assertNotNull(hit);
        assertNotNull(sourcesManager.lookupListedNumbers("+499001234567"));

        // persisted: a fresh manager sees the same list and remote info
        SourcesManager reloaded = newSourcesManager();
        assertEquals(remote, RemoteListManager.getRemote(reloaded.getSource(id)));
        assertTrue(new RemoteListManager(reloaded, downloader).hasAutoUpdateLists());

        String[] request = downloader.requests.get(0);
        assertEquals(URL, request[0]);
        assertNull(request[1]);
    }

    @Test
    public void addListFailureStoresNothing() {
        downloader.enqueue(ok("<!DOCTYPE html><html><body>404</body></html>", null));
        try {
            manager.addList(URL, "x", false);
            fail();
        } catch (NumberListFormatDetector.UnsupportedFormatException expected) {
            // ok
        } catch (IOException e) {
            fail(e.toString());
        }

        downloader.enqueue(ok("just some text\nwithout numbers\n", null));
        try {
            manager.addList(URL, "x", false);
            fail();
        } catch (RemoteListManager.NoNumbersException expected) {
            // ok
        } catch (IOException e) {
            fail(e.toString());
        }

        downloader.enqueue(new SocketTimeoutException("timeout"));
        try {
            manager.addList(URL, "x", false);
            fail();
        } catch (IOException expected) {
            // ok
        }

        assertTrue(sourcesManager.getSources().isEmpty());
        assertTrue(store.listSourceIds().isEmpty());
    }

    @Test
    public void updateNotModifiedUsesValidators() throws IOException {
        downloader.enqueue(RemoteListDownloader.Response.ok(
                XML_V1.getBytes(StandardCharsets.UTF_8), "\"v1\"", "Mon, 01 Jan 2024 00:00:00 GMT"));
        String id = manager.addList(URL, "Liste", true).getSourceId();

        now = 2000;
        downloader.enqueue(RemoteListDownloader.Response.notModified(
                "\"v1\"", "Mon, 01 Jan 2024 00:00:00 GMT"));
        RemoteListManager.UpdateResult result = manager.update(id);

        assertEquals(RemoteListManager.UpdateResult.Status.NOT_MODIFIED, result.getStatus());
        String[] request = downloader.requests.get(1);
        assertEquals("\"v1\"", request[1]);
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", request[2]);

        SourcesManager.SourceInfo source = sourcesManager.getSource(id);
        assertEquals(2000, RemoteListManager.getRemote(source).getLastCheckAt());
        // the content didn't change, so neither did the import time
        assertEquals(1000, source.getMetadata().getImportedAt());
        assertEquals(2, source.getMetadata().getEntryCount());
        assertEquals("Liste", source.getDisplayName());
    }

    @Test
    public void updateReplacesContentInAnyFormat() throws IOException {
        downloader.enqueue(ok(XML_V1, "\"v1\""));
        String id = manager.addList(URL, "Liste", false).getSourceId();
        sourcesManager.setEnabled(id, false);

        now = 3000;
        downloader.enqueue(ok(VCARD_V2, "\"v2\""));
        RemoteListManager.UpdateResult result = manager.update(id);

        assertEquals(RemoteListManager.UpdateResult.Status.UPDATED, result.getStatus());
        SourcesManager.SourceInfo source = sourcesManager.getSource(id);
        assertEquals(1, source.getMetadata().getEntryCount());
        assertEquals(3000, source.getMetadata().getImportedAt());
        assertEquals("\"v2\"", RemoteListManager.getRemote(source).getEtag());
        assertFalse(RemoteListManager.getRemote(source).isAutoUpdate());
        // name and enabled state are kept
        assertEquals("Liste", source.getDisplayName());
        assertFalse(source.isEnabled());
    }

    @Test
    public void failedUpdateKeepsListAndRecordsError() throws IOException {
        downloader.enqueue(ok(XML_V1, "\"v1\""));
        String id = manager.addList(URL, "Liste", true).getSourceId();

        now = 4000;
        downloader.enqueue(new RemoteListDownloader.HttpStatusException(503, "Service Unavailable"));
        RemoteListManager.UpdateResult result = manager.update(id);

        assertEquals(RemoteListManager.UpdateResult.Status.FAILED, result.getStatus());
        assertEquals("HTTP 503 Service Unavailable", result.getError());

        SourcesManager.SourceInfo source = sourcesManager.getSource(id);
        RemoteListInfo remote = RemoteListManager.getRemote(source);
        assertEquals("HTTP 503 Service Unavailable", remote.getLastError());
        assertEquals(4000, remote.getLastCheckAt());
        assertEquals("\"v1\"", remote.getEtag());
        assertEquals(2, source.getMetadata().getEntryCount());
        assertNotNull(sourcesManager.lookupListedNumbers("+4915112345678"));

        // an empty download doesn't wipe the list either
        downloader.enqueue(ok("", "\"v3\""));
        result = manager.update(id);
        assertEquals(RemoteListManager.UpdateResult.Status.FAILED, result.getStatus());
        assertEquals(2, sourcesManager.getSource(id).getMetadata().getEntryCount());

        // a later success clears the error
        now = 5000;
        downloader.enqueue(RemoteListDownloader.Response.notModified("\"v1\"", null));
        manager.update(id);
        remote = RemoteListManager.getRemote(sourcesManager.getSource(id));
        assertNull(remote.getLastError());
        assertEquals(5000, remote.getLastCheckAt());
    }

    @Test
    public void updateAutoUpdateListsOnly() throws IOException {
        downloader.enqueue(ok(XML_V1, "\"a\""));
        String auto = manager.addList("https://example.org/auto.xml", null, true).getSourceId();
        downloader.enqueue(ok(XML_V1, "\"m\""));
        manager.addList("https://example.org/manual.xml", null, false);
        sourcesManager.importList("csv_local", "Local", Collections.emptyList(), 0);

        downloader.enqueue(RemoteListDownloader.Response.notModified("\"a\"", null));
        List<RemoteListManager.UpdateResult> results = manager.updateAutoUpdateLists();

        assertEquals(1, results.size());
        assertEquals(auto, results.get(0).getSourceId());
        assertEquals("https://example.org/auto.xml", downloader.requests.get(2)[0]);
        assertEquals(3, downloader.requests.size());
    }

    @Test
    public void setAutoUpdate() throws IOException {
        downloader.enqueue(ok(XML_V1, null));
        String id = manager.addList(URL, null, true).getSourceId();
        assertTrue(manager.hasAutoUpdateLists());

        assertTrue(manager.setAutoUpdate(id, false));
        assertFalse(manager.hasAutoUpdateLists());
        assertFalse(RemoteListManager.getRemote(newSourcesManager().getSource(id)).isAutoUpdate());
        assertEquals(2, sourcesManager.getSource(id).getMetadata().getEntryCount());

        assertFalse(manager.setAutoUpdate("url_missing", true));
        sourcesManager.importList("csv_local", "Local", Collections.emptyList(), 0);
        assertFalse(manager.setAutoUpdate("csv_local", true));
    }

    @Test
    public void bnetzaListIsNotAUrlList() throws IOException {
        // the automatically updated official list has remote info, but its own updater
        sourcesManager.importList(SourcesManager.BNETZA_SOURCE_ID, "Bundesnetzagentur",
                Collections.emptyList(), 0,
                new RemoteListInfo("https://www.bundesnetzagentur.de/massnahmenliste", true));

        assertNull(RemoteListManager.getRemote(sourcesManager.getSource("bnetza")));
        assertFalse(manager.hasAutoUpdateLists());
        assertTrue(manager.updateAutoUpdateLists().isEmpty());
        assertNull(manager.update("bnetza"));
        assertTrue(downloader.requests.isEmpty());
    }

    @Test
    public void updateOfUnknownOrLocalListReturnsNull() throws IOException {
        assertNull(manager.update("url_missing"));
        sourcesManager.importList("csv_local", "Local", Collections.emptyList(), 0);
        assertNull(manager.update("csv_local"));
        assertTrue(downloader.requests.isEmpty());
    }

    @Test
    public void reAddingTheSameUrlReplacesTheList() throws IOException {
        downloader.enqueue(ok(XML_V1, null));
        String first = manager.addList(URL, "A", true).getSourceId();
        downloader.enqueue(ok(VCARD_V2, null));
        String second = manager.addList(" " + URL + " ", "B", false).getSourceId();

        assertEquals(first, second);
        assertEquals(1, sourcesManager.getSources().size());
        assertEquals("B", sourcesManager.getSource(first).getDisplayName());
        assertEquals(1, sourcesManager.getSource(first).getMetadata().getEntryCount());
    }

    @Test
    public void normalizeUrl() {
        assertEquals("https://raw.githubusercontent.com/dontobi/SpamCalllist/main/export/fritzbox_export.xml",
                RemoteListManager.normalizeUrl(
                        "https://github.com/dontobi/SpamCalllist/blob/main/export/fritzbox_export.xml"));
        assertEquals("https://raw.githubusercontent.com/u/r/master/a%20b.csv",
                RemoteListManager.normalizeUrl("github.com/u/r/raw/master/a%20b.csv"));
        assertEquals("https://example.org/list.csv",
                RemoteListManager.normalizeUrl("  example.org/list.csv "));
        assertEquals("http://example.org/list.csv",
                RemoteListManager.normalizeUrl("http://example.org/list.csv"));
        // other GitHub pages are left alone
        assertEquals("https://github.com/u/r",
                RemoteListManager.normalizeUrl("https://github.com/u/r"));

        for (String invalid : new String[]{"", "   ", "ftp://example.org/x", "file:///etc/passwd",
                "https://", "http://exa mple.org/"}) {
            try {
                RemoteListManager.normalizeUrl(invalid);
                fail(invalid);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void displayNameFromUrl() {
        assertEquals("FRITZ.Box_Telefonbuch_Telefon Blacklist_24.03.18_0125",
                RemoteListManager.displayNameFromUrl("https://raw.githubusercontent.com/a/b/master/"
                        + "FRITZ.Box_Telefonbuch_Telefon%20Blacklist_24.03.18_0125.xml"));
        assertEquals("nextcloud_export", RemoteListManager.displayNameFromUrl(
                "https://example.org/export/nextcloud_export.vcf"));
        assertEquals("example.org", RemoteListManager.displayNameFromUrl("https://example.org/"));
        assertEquals("a+b", RemoteListManager.displayNameFromUrl("https://example.org/a+b.txt"));
    }

    @Test
    public void sourceIdIsStableAndValid() {
        String id = RemoteListManager.sourceIdForUrl(URL);
        assertEquals(id, RemoteListManager.sourceIdForUrl(URL));
        assertFalse(id.equals(RemoteListManager.sourceIdForUrl(URL + "?x")));
        assertTrue(id.matches("url_[0-9a-f]{16}"));
    }

}
