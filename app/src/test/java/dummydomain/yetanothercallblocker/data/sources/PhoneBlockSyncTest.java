package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dummydomain.yetanothercallblocker.data.sources.PhoneBlockClientTest.TOKEN;
import static dummydomain.yetanothercallblocker.data.sources.PhoneBlockClientTest.resource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PhoneBlockSyncTest {

    private static final long T0 = 1_759_500_000_000L;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    /** Records the imported lists. */
    static class RecordingImporter implements PhoneBlockSync.ListImporter {
        final List<List<ListedNumber>> imports = new ArrayList<>();
        String sourceId;
        String displayName;
        long importedAt;
        IOException failure;

        @Override
        public void importList(String sourceId, String displayName, List<ListedNumber> entries,
                               long importedAt) throws IOException {
            if (failure != null) throw failure;
            this.sourceId = sourceId;
            this.displayName = displayName;
            this.importedAt = importedAt;
            imports.add(new ArrayList<>(entries));
        }

        List<ListedNumber> last() {
            return imports.get(imports.size() - 1);
        }
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private PhoneBlockClientTest.FakeTransport transport;
    private RecordingImporter importer;
    private File stateFile;
    private long now;

    @Before
    public void setUp() throws IOException {
        transport = new PhoneBlockClientTest.FakeTransport();
        importer = new RecordingImporter();
        stateFile = new File(tmp.newFolder("phoneblock"), "state.txt");
        now = T0;
    }

    private PhoneBlockSync newSync() {
        return new PhoneBlockSync(new PhoneBlockClient(transport, null, null),
                stateFile, importer, () -> now);
    }

    private static List<String> numbers(List<ListedNumber> entries) {
        List<String> result = new ArrayList<>();
        for (ListedNumber e : entries) result.add(e.getNumber());
        return result;
    }

    @Test
    public void firstSyncDownloadsFullList() throws IOException {
        transport.respond(200, resource("phoneblock_blocklist_full.json"));
        PhoneBlockSync sync = newSync();

        PhoneBlockSync.SyncResult result = sync.sync(TOKEN, 10, true);

        assertFalse(result.isSkipped());
        assertTrue(result.isFull());
        assertEquals(6, result.getReceived());
        assertEquals(6, result.getTotalNumbers());
        assertEquals(6, result.getListedNumbers());
        assertEquals(4217, result.getVersion());
        assertTrue(transport.urls.get(0).endsWith("/blocklist?format=json"));

        assertEquals(PhoneBlockSync.SOURCE_ID, importer.sourceId);
        assertEquals("PhoneBlock", importer.displayName);
        assertEquals(T0, importer.importedAt);

        List<ListedNumber> listed = importer.last();
        // sorted by number
        assertEquals("[+390456789123, +4915112345678, +4922112345604, +4930123456701, "
                + "+4940123456703, +498912345602]", numbers(listed).toString());
        ListedNumber fraud = listed.get(3);
        assertEquals("G_FRAUD", fraud.getCategory());
        assertFalse(fraud.isPrefix());
        assertEquals(MeasureType.NONE, fraud.getMeasureType());

        PhoneBlockSync.Status status = sync.getStatus();
        assertEquals(T0, status.getLastSync());
        assertEquals(T0, status.getLastFullSync());
        assertEquals(4217, status.getVersion());
        assertEquals(6, status.getTotalNumbers());
        assertTrue(stateFile.isFile());
    }

    @Test
    public void thresholdFiltersList() throws IOException {
        transport.respond(200, resource("phoneblock_blocklist_full.json"));
        PhoneBlockSync sync = newSync();

        PhoneBlockSync.SyncResult result = sync.sync(TOKEN, 50, true);
        assertEquals(6, result.getTotalNumbers());
        assertEquals(2, result.getListedNumbers());
        assertEquals("[+4915112345678, +4940123456703]", numbers(importer.last()).toString());

        // changing the threshold doesn't need the network
        assertEquals(3, sync.rebuildList(20));
        assertEquals("[+4915112345678, +4940123456703, +498912345602]",
                numbers(importer.last()).toString());
        assertEquals(1, transport.urls.size());

        // off-grid values are snapped up to the allowed options
        assertEquals(1, sync.rebuildList(51));
        assertEquals(6, sync.rebuildList(1));
    }

    @Test
    public void incrementalUpdateAfterADay() throws IOException {
        transport.respond(200, resource("phoneblock_blocklist_full.json"));
        newSync().sync(TOKEN, 10, false);

        // a new instance loads the saved state
        PhoneBlockSync sync = newSync();
        now = T0 + DAY;
        transport.respond(200, resource("phoneblock_blocklist_update.json"));
        PhoneBlockSync.SyncResult result = sync.sync(TOKEN, 10, false);

        assertFalse(result.isFull());
        assertEquals("https://phoneblock.net/phoneblock/api/blocklist?format=json&since=4217",
                transport.urls.get(1));
        assertEquals(2, result.getReceived());
        assertEquals(1, result.getRemoved()); // +4971112345699 was not stored
        assertEquals(6, result.getTotalNumbers());
        assertEquals(4230, result.getVersion());

        List<String> listed = numbers(importer.last());
        assertFalse(listed.contains("+498912345602"));
        assertTrue(listed.contains("+4969123456705"));
        assertEquals(6, listed.size());

        PhoneBlockSync.Status status = sync.getStatus();
        assertEquals(T0 + DAY, status.getLastSync());
        assertEquals(T0, status.getLastFullSync());

        // the updated vote count is kept
        assertEquals(3, newSync().rebuildList(20));
        assertEquals("[+4915112345678, +4930123456701, +4940123456703]",
                numbers(importer.last()).toString());
    }

    @Test
    public void tooFrequentSyncsAreSkipped() throws IOException {
        transport.respond(200, resource("phoneblock_blocklist_full.json"));
        PhoneBlockSync sync = newSync();
        sync.sync(TOKEN, 10, true);

        now = T0 + TimeUnit.HOURS.toMillis(12);
        PhoneBlockSync.SyncResult auto = sync.sync(TOKEN, 10, false);
        assertTrue(auto.isSkipped());
        assertEquals(6, auto.getTotalNumbers());

        now = T0 + TimeUnit.MINUTES.toMillis(1);
        assertTrue(sync.sync(TOKEN, 10, true).isSkipped());
        assertEquals(1, transport.urls.size());

        // a manual sync is allowed after a few minutes
        now = T0 + TimeUnit.MINUTES.toMillis(10);
        transport.respond(200, "{\"numbers\":[],\"version\":4218}");
        PhoneBlockSync.SyncResult manual = sync.sync(TOKEN, 10, true);
        assertFalse(manual.isSkipped());
        assertFalse(manual.isFull());
        assertEquals(4218, manual.getVersion());
    }

    @Test
    public void fullSyncAfterAMonth() throws IOException {
        transport.respond(200, resource("phoneblock_blocklist_full.json"));
        PhoneBlockSync sync = newSync();
        sync.sync(TOKEN, 10, false);

        now = T0 + 30 * DAY;
        transport.respond(200, "{\"numbers\":[{\"phone\":\"+4930999999\",\"votes\":10,"
                + "\"rating\":\"G_FRAUD\",\"lastActivity\":1}],\"version\":5000}");
        PhoneBlockSync.SyncResult result = sync.sync(TOKEN, 10, false);

        assertTrue(result.isFull());
        assertTrue(transport.urls.get(1).endsWith("/blocklist?format=json"));
        assertEquals(1, result.getTotalNumbers());
        assertEquals("[+4930999999]", numbers(importer.last()).toString());
        assertEquals(T0 + 30 * DAY, sync.getStatus().getLastFullSync());
    }

    @Test
    public void failedDownloadKeepsState() throws IOException {
        transport.respond(200, resource("phoneblock_blocklist_full.json"));
        PhoneBlockSync sync = newSync();
        sync.sync(TOKEN, 10, false);

        now = T0 + DAY;
        transport.respond(401, "Please provide login credentials.");
        try {
            sync.sync(TOKEN, 10, false);
            fail();
        } catch (PhoneBlockClient.ApiException e) {
            assertTrue(e.isAuthError());
        }
        assertEquals(1, importer.imports.size());
        assertEquals(T0, sync.getStatus().getLastSync());

        transport.respond(200, "{\"numbers\":[{\"phone\":\"+4930123456701\",\"votes\":0}],");
        try {
            sync.sync(TOKEN, 10, false);
            fail();
        } catch (IOException expected) {
            // truncated response
        }
        assertEquals(6, sync.getStatus().getTotalNumbers());
        assertEquals(4217, newSync().getStatus().getVersion());
    }

    @Test
    public void failedImportDoesNotLoseTheUpdate() throws IOException {
        transport.respond(200, resource("phoneblock_blocklist_full.json"));
        importer.failure = new IOException("disk full");
        PhoneBlockSync sync = newSync();
        try {
            sync.sync(TOKEN, 10, false);
            fail();
        } catch (IOException expected) {
            // ok
        }
        // the state is saved, the list can be rebuilt without downloading again
        importer.failure = null;
        assertEquals(6, sync.rebuildList(10));
    }

    @Test
    public void resetForcesFullSync() throws IOException {
        transport.respond(200, resource("phoneblock_blocklist_full.json"));
        PhoneBlockSync sync = newSync();
        sync.sync(TOKEN, 10, false);

        sync.reset();
        assertFalse(stateFile.exists());
        assertEquals(0, sync.getStatus().getLastSync());
        assertEquals(-1, sync.rebuildList(10));

        transport.respond(200, resource("phoneblock_blocklist_full.json"));
        assertTrue(sync.sync(TOKEN, 10, false).isFull());
    }

    @Test
    public void corruptedStateTriggersFullSync() throws IOException {
        Files.write(stateFile.toPath(), "garbage\n".getBytes(StandardCharsets.UTF_8));
        PhoneBlockSync sync = newSync();
        assertEquals(0, sync.getStatus().getLastSync());

        transport.respond(200, resource("phoneblock_blocklist_full.json"));
        assertTrue(sync.sync(TOKEN, 10, false).isFull());
    }

    @Test(expected = IllegalArgumentException.class)
    public void requiresToken() throws IOException {
        newSync().sync("", 10, true);
    }

    @Test
    public void stateRoundTrip() throws IOException {
        PhoneBlockState state = new PhoneBlockState();
        state.applyFull(PhoneBlockClient.parseBlocklist(new java.io.StringReader(
                resource("phoneblock_blocklist_full.json"))), T0);
        state.applyUpdate(new PhoneBlockClient.Blocklist(java.util.Arrays.asList(
                new PhoneBlockClient.BlocklistEntry("+4930555", 4, null, 0)), -1, 0), T0 + 1);
        state.save(stateFile);

        PhoneBlockState loaded = PhoneBlockState.load(stateFile);
        assertNotNull(loaded);
        assertEquals(4217, loaded.getVersion());
        assertEquals(T0, loaded.getLastFullSync());
        assertEquals(T0 + 1, loaded.getLastSync());
        assertEquals(7, loaded.size());
        assertNull(loaded.get("+4930555").getRating());
        assertEquals(state.get("+4930123456701"), loaded.get("+4930123456701"));
        assertNull(PhoneBlockState.load(new File(stateFile.getParentFile(), "missing")));
    }

    @Test
    public void legitimateNumbersAreNotListed() {
        PhoneBlockState state = new PhoneBlockState();
        state.applyFull(new PhoneBlockClient.Blocklist(java.util.Arrays.asList(
                new PhoneBlockClient.BlocklistEntry("+4930111", 10,
                        PhoneBlockClient.Rating.A_LEGITIMATE, 0),
                new PhoneBlockClient.BlocklistEntry("+4930222", 10, null, 0),
                new PhoneBlockClient.BlocklistEntry("+4930333", 0,
                        PhoneBlockClient.Rating.G_FRAUD, 0)), 1, 0), T0);

        assertEquals(2, state.size());
        List<ListedNumber> listed = state.toListedNumbers(10);
        assertEquals(1, listed.size());
        assertEquals("+4930222", listed.get(0).getNumber());
        assertNull(listed.get(0).getCategory());
    }

}
