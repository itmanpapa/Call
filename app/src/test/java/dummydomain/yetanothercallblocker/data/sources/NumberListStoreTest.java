package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NumberListStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static List<ListedNumber> sampleEntries() {
        return Arrays.asList(
                ListedNumber.builder()
                        .number("+4915112345678")
                        .name("Gewinnspiel GmbH")
                        .category("Werbung")
                        .comment("comma, \"quotes\"; semicolon")
                        .rawText("015112345678;Gewinnspiel GmbH;Werbung")
                        .build(),
                ListedNumber.builder()
                        .prefix("+4990012345")
                        .category("Spam-SMS")
                        .measureType(MeasureType.DISCONNECTION)
                        .measureText("Abschaltung der Rufnummer")
                        .date(LocalDate.of(2024, 1, 15))
                        .rawText("line one\nline two\r\nÄÖÜ ß €")
                        .build(),
                ListedNumber.builder()
                        .number("+375291234567")
                        .measureType(MeasureType.BILLING_PROHIBITION)
                        .rawText("x")
                        .build(),
                ListedNumber.builder()
                        .number("+493012345678")
                        .build()
        );
    }

    @Test
    public void roundTrip() throws IOException {
        File dir = new File(folder.getRoot(), "lists"); // does not exist yet
        NumberListStore store = new NumberListStore(dir);
        List<ListedNumber> entries = sampleEntries();

        NumberListStore.ListMetadata saved = store.save("bnetza",
                "Bundesnetzagentur, \"Maßnahmen\"", 1727777777000L, entries);
        assertEquals(4, saved.getEntryCount());

        NumberListStore.StoredList loaded = new NumberListStore(dir).load("bnetza");
        assertEquals(saved, loaded.getMetadata());
        assertEquals("bnetza", loaded.getMetadata().getSourceId());
        assertEquals("Bundesnetzagentur, \"Maßnahmen\"", loaded.getMetadata().getDisplayName());
        assertEquals(1727777777000L, loaded.getMetadata().getImportedAt());
        assertEquals(entries, loaded.getEntries());
        assertNull(loaded.getEntries().get(3).getRawText());
    }

    @Test
    public void emptyRawTextIsReadAsNull() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        store.save("x", "", 0, Collections.singletonList(
                ListedNumber.builder().number("+4915112345678").rawText("").build()));

        NumberListStore.StoredList loaded = store.load("x");
        assertNull(loaded.getEntries().get(0).getRawText());
        assertNull(loaded.getMetadata().getDisplayName());
    }

    @Test
    public void emptyListAndNullDisplayName() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        store.save("empty", null, 0, Collections.emptyList());

        NumberListStore.StoredList loaded = store.load("empty");
        assertNull(loaded.getMetadata().getDisplayName());
        assertEquals(0, loaded.getMetadata().getEntryCount());
        assertTrue(loaded.getEntries().isEmpty());
    }

    @Test
    public void missingListReturnsNull() throws IOException {
        NumberListStore store = new NumberListStore(new File(folder.getRoot(), "nope"));
        assertNull(store.load("csv1"));
        assertNull(store.loadMetadata("csv1"));
        assertFalse(store.exists("csv1"));
        assertTrue(store.listSourceIds().isEmpty());
    }

    @Test
    public void saveReplacesListAndLeavesNoTempFile() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        store.save("csv1", "First", 1, sampleEntries());
        store.save("csv1", "Second", 2, sampleEntries().subList(0, 1));

        NumberListStore.StoredList loaded = store.load("csv1");
        assertEquals("Second", loaded.getMetadata().getDisplayName());
        assertEquals(1, loaded.getEntries().size());

        assertEquals(Collections.singletonList("numberlist_csv1.csv"),
                Arrays.asList(folder.getRoot().list()));
    }

    @Test
    public void metadataOnly() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        store.save("bnetza", "BNetzA", 42, sampleEntries());

        assertEquals(new NumberListStore.ListMetadata("bnetza", "BNetzA", 42, 4),
                store.loadMetadata("bnetza"));
    }

    @Test
    public void listAndDelete() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        store.save("b", "B", 1, sampleEntries());
        store.save("a", "A", 1, sampleEntries());
        assertTrue(new File(folder.getRoot(), "unrelated.txt").createNewFile());

        assertEquals(Arrays.asList("a", "b"), store.listSourceIds());
        assertTrue(store.exists("a"));

        assertTrue(store.delete("a"));
        assertFalse(store.delete("a"));
        assertEquals(Collections.singletonList("b"), store.listSourceIds());
    }

    @Test
    public void largeList() throws IOException {
        List<ListedNumber> entries = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            entries.add(ListedNumber.builder().number("+49151" + (10_000_000 + i))
                    .category("c" + (i % 7)).build());
        }
        NumberListStore store = new NumberListStore(folder.getRoot());
        store.save("big", "Big", 1, entries);
        assertEquals(entries, store.load("big").getEntries());
    }

    @Test
    public void truncatedFileIsRejected() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        store.save("csv1", "X", 1, sampleEntries());

        File file = store.getFile("csv1");
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        // drop the last physical line (the last entry)
        Files.write(file.toPath(), lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8);

        try {
            store.load("csv1");
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Truncated"));
        }
    }

    @Test
    public void foreignAndNewerFilesAreRejected() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        File file = store.getFile("x");

        write(file, "number;name\n0151;foo\n");
        assertLoadFails(store, "x");

        write(file, "format,yacb-number-list,99\nmeta,sourceId,x\nmeta,entryCount,0\n");
        assertLoadFails(store, "x");

        write(file, "format,yacb-number-list,1\nmeta,sourceId,other\nmeta,entryCount,0\n");
        assertLoadFails(store, "x");

        write(file, "");
        assertLoadFails(store, "x");
    }

    @Test
    public void unknownRecordsAndMetaKeysAreIgnored() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        write(store.getFile("x"), "format,yacb-number-list,1\n"
                + "meta,sourceId,x\nmeta,futureKey,1\nmeta,entryCount,1\n"
                + "future,record\n"
                + "entry,N,+4915112345678,,,,SOMETHING_NEW,,,\n");

        NumberListStore.StoredList loaded = store.load("x");
        assertEquals(1, loaded.getEntries().size());
        assertEquals("+4915112345678", loaded.getEntries().get(0).getNumber());
        assertEquals(MeasureType.UNKNOWN, loaded.getEntries().get(0).getMeasureType());
    }

    @Test
    public void remoteInfoRoundTrip() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        RemoteListInfo remote = new RemoteListInfo("https://example.org/a,b.xml", true,
                "\"abc\"", "Tue, 01 Oct 2026 10:00:00 GMT", 1727777777000L,
                "HTTP 404\nNot Found");

        NumberListStore.ListMetadata saved = store.save("url_1", "List", 5, sampleEntries(), remote);
        assertEquals(remote, saved.getRemote());

        NumberListStore.ListMetadata loaded = new NumberListStore(folder.getRoot())
                .loadMetadata("url_1");
        assertEquals(saved, loaded);
        assertEquals(remote, loaded.getRemote());
        assertEquals(sampleEntries(), store.load("url_1").getEntries());

        // a list without remote info has none after loading
        store.save("csv_x", "x", 0, sampleEntries());
        assertNull(store.loadMetadata("csv_x").getRemote());
    }

    @Test
    public void remoteInfoWithPartsAndLastSuccessRoundTrip() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        RemoteListInfo remote = new RemoteListInfo("https://example.org/main", true,
                "\"m\"", null, 2000, "HTTP 503", 1000, Arrays.asList(
                new RemoteListInfo.Part("https://example.org/main", "\"m\"", null),
                new RemoteListInfo.Part("https://example.org/a,b.html", null,
                        "Tue, 01 Oct 2026 10:00:00 GMT")));

        store.save("bnetza", "Bundesnetzagentur", 5, sampleEntries(), remote);
        RemoteListInfo loaded = new NumberListStore(folder.getRoot())
                .loadMetadata("bnetza").getRemote();

        assertEquals(remote, loaded);
        assertEquals(1000, loaded.getLastSuccessAt());
        assertEquals(2, loaded.getParts().size());
        assertEquals("Tue, 01 Oct 2026 10:00:00 GMT",
                loaded.getPart("https://example.org/a,b.html").getLastModified());
        assertNull(loaded.getPart("https://example.org/a,b.html").getEtag());
        assertNull(loaded.getPart("https://example.org/other"));
    }

    @Test
    public void remoteInfoOfOlderVersionsDerivesLastSuccess() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        String file = "format,yacb-number-list,1\n"
                + "meta,sourceId,url_old\n"
                + "meta,displayName,Old\n"
                + "meta,importedAt,10\n"
                + "meta,entryCount,0\n"
                + "meta,remoteUrl,https://example.org/list.xml\n"
                + "meta,remoteAutoUpdate,true\n"
                + "meta,remoteLastCheckAt,500\n";
        java.nio.file.Files.write(store.getFile("url_old").toPath(),
                file.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        RemoteListInfo remote = store.loadMetadata("url_old").getRemote();
        assertEquals(500, remote.getLastSuccessAt());
        assertTrue(remote.getParts().isEmpty());
    }

    @Test
    public void remoteInfoWithNullFields() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        RemoteListInfo remote = new RemoteListInfo("http://example.org/list.csv", false);
        store.save("url_2", null, 0, Collections.emptyList(), remote);
        assertEquals(remote, store.loadMetadata("url_2").getRemote());
        assertNull(store.loadMetadata("url_2").getRemote().getEtag());
        assertNull(store.loadMetadata("url_2").getRemote().getLastError());
    }

    @Test
    public void updateRemoteKeepsEntries() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        RemoteListInfo remote = new RemoteListInfo("https://example.org/list.xml", true);
        store.save("url_3", "Name", 42, sampleEntries(), remote);

        RemoteListInfo failed = remote.withError("timeout", 100);
        NumberListStore.ListMetadata updated = store.updateRemote("url_3", failed);
        assertEquals(failed, updated.getRemote());
        assertEquals(42, updated.getImportedAt());
        assertEquals("Name", updated.getDisplayName());

        NumberListStore.StoredList loaded = store.load("url_3");
        assertEquals(sampleEntries(), loaded.getEntries());
        assertEquals("timeout", loaded.getMetadata().getRemote().getLastError());
        assertEquals(100, loaded.getMetadata().getRemote().getLastCheckAt());

        assertNull(store.updateRemote("missing", failed));
    }

    @Test
    public void remoteMetaRecordsAreReadFromHandWrittenFile() throws IOException {
        NumberListStore store = new NumberListStore(folder.getRoot());
        write(store.getFile("x"), "format,yacb-number-list,1\n"
                + "meta,sourceId,x\nmeta,entryCount,0\n"
                + "meta,remoteUrl,https://example.org/l.vcf\nmeta,remoteAutoUpdate,true\n"
                + "meta,remoteEtag,\nmeta,remoteLastCheckAt,\n");
        RemoteListInfo remote = store.loadMetadata("x").getRemote();
        assertEquals("https://example.org/l.vcf", remote.getUrl());
        assertTrue(remote.isAutoUpdate());
        assertNull(remote.getEtag());
        assertEquals(0, remote.getLastCheckAt());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidSourceIdIsRejected() throws IOException {
        new NumberListStore(folder.getRoot()).save("../evil", null, 0, Collections.emptyList());
    }

    private static void write(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertLoadFails(NumberListStore store, String id) {
        try {
            store.load(id);
            fail("expected IOException");
        } catch (IOException expected) {
            // ok
        }
    }

}
