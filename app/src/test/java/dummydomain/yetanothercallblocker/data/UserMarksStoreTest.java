package dummydomain.yetanothercallblocker.data;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UserMarksStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File file() {
        return new File(folder.getRoot(), "user_marks.csv");
    }

    @Test
    public void emptyWhenNoFile() {
        UserMarksStore store = new UserMarksStore(file());
        assertNull(store.get("+4930123456"));
        assertEquals(0, store.size());
        assertFalse(file().exists());
    }

    @Test
    public void setGetRemove() throws IOException {
        UserMarksStore store = new UserMarksStore(file());

        assertNull(store.set("+4930123456", UserMark.Type.SPAM, 1000, "Gewinnspiel"));
        UserMark mark = store.get("+4930123456");
        assertEquals(new UserMark("+4930123456", UserMark.Type.SPAM, 1000, "Gewinnspiel"), mark);
        assertTrue(mark.isSpam());

        UserMark previous = store.set("+4930123456", UserMark.Type.NOT_SPAM, 2000, null);
        assertEquals(mark, previous);
        assertTrue(store.get("+4930123456").isNotSpam());
        assertNull(store.get("+4930123456").getNote());

        assertEquals(UserMark.Type.NOT_SPAM, store.remove("+4930123456").getType());
        assertNull(store.get("+4930123456"));
        assertNull(store.remove("+4930123456"));
    }

    @Test
    public void persistsAcrossInstances() throws IOException {
        UserMarksStore store = new UserMarksStore(file());
        store.set("+4930123456", UserMark.Type.SPAM, 1000, "comma, \"quotes\"\nnew line");
        store.set("+4915112345678", UserMark.Type.NOT_SPAM, 2000, null);

        UserMarksStore reloaded = new UserMarksStore(file());
        assertEquals(2, reloaded.size());
        assertEquals(new UserMark("+4930123456", UserMark.Type.SPAM, 1000,
                "comma, \"quotes\"\nnew line"), reloaded.get("+4930123456"));
        assertEquals(new UserMark("+4915112345678", UserMark.Type.NOT_SPAM, 2000, null),
                reloaded.get("+4915112345678"));

        List<UserMark> all = reloaded.getAll();
        assertEquals("+4915112345678", all.get(0).getNumber()); // newest first
        assertEquals("+4930123456", all.get(1).getNumber());

        assertFalse(new File(file().getPath() + UserMarksStore.TMP_SUFFIX).exists());
    }

    @Test
    public void keysIgnoreFormatting() throws IOException {
        UserMarksStore store = new UserMarksStore(file());
        store.set("+49 (30) 123-456", UserMark.Type.SPAM, 1000, null);

        assertEquals("+4930123456", store.get("+4930123456").getNumber());
        assertEquals("+4930123456", store.get("+49 30 123 456").getNumber());
        assertNull(store.get("030123456")); // no country guessing

        assertNull(UserMarksStore.normalizeKey(null));
        assertNull(UserMarksStore.normalizeKey(" - "));
        assertEquals("+4930123", UserMarksStore.normalizeKey("+49/30.123"));
    }

    @Test
    public void getTriesCandidatesInOrder() throws IOException {
        UserMarksStore store = new UserMarksStore(file());
        store.set("030123456", UserMark.Type.NOT_SPAM, 1000, null);
        store.set("+4930999", UserMark.Type.SPAM, 1000, null);

        // normalized number unknown to the store, the raw number has a mark
        assertTrue(store.get("+4930123456", "030123456").isNotSpam());
        // the first matching candidate wins
        assertTrue(store.get("+4930999", "030123456").isSpam());
        // null and empty candidates are skipped
        assertTrue(store.get(null, "", "030123456").isNotSpam());
        assertNull(store.get((String) null));
        assertNull(store.get((String[]) null));
    }

    @Test
    public void restoreForUndo() throws IOException {
        UserMarksStore store = new UserMarksStore(file());

        // undo of a new mark removes it
        assertNull(store.set("+4930123456", UserMark.Type.NOT_SPAM, 1000, null));
        store.restore("+4930123456", null);
        assertNull(store.get("+4930123456"));

        // undo of a changed mark restores the previous one
        store.set("+4930123456", UserMark.Type.SPAM, 1000, "note");
        UserMark previous = store.set("+4930123456", UserMark.Type.NOT_SPAM, 2000, null);
        store.restore("+4930123456", previous);
        assertEquals(new UserMark("+4930123456", UserMark.Type.SPAM, 1000, "note"),
                new UserMarksStore(file()).get("+4930123456"));
    }

    @Test
    public void listenerIsNotified() throws IOException {
        UserMarksStore store = new UserMarksStore(file());
        AtomicInteger calls = new AtomicInteger();
        store.setListener(calls::incrementAndGet);

        store.set("+4930123456", UserMark.Type.SPAM, 1000, null);
        store.remove("+4930123456");
        store.remove("+4930123456"); // nothing to remove: no notification
        assertEquals(2, calls.get());
    }

    @Test
    public void rejectsEmptyNumber() throws IOException {
        UserMarksStore store = new UserMarksStore(file());
        try {
            store.set("  ", UserMark.Type.SPAM, 1000, null);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertNull(store.remove(""));
    }

    @Test
    public void ignoresUnknownRecordsAndTypes() throws IOException {
        Files.write(file().toPath(), ("format,yacb-user-marks,1\n"
                + "mark,+4930123456,SPAM,1000,\n"
                + "mark,+4930999,MAYBE_SPAM,1000,\n"
                + "future,whatever\n"
                + "mark,+4930888,NOT_SPAM,notanumber,n\n"
                + "mark,short\n").getBytes(StandardCharsets.UTF_8));

        UserMarksStore store = new UserMarksStore(file());
        assertEquals(2, store.size());
        assertTrue(store.get("+4930123456").isSpam());
        assertNull(store.get("+4930999"));
        assertEquals(0, store.get("+4930888").getTimestamp());
        assertEquals("n", store.get("+4930888").getNote());
    }

    @Test
    public void newerFormatIsNotOverwritten() throws IOException {
        String content = "format,yacb-user-marks,2\nmark,+4930123456,SPAM,1000,\n";
        Files.write(file().toPath(), content.getBytes(StandardCharsets.UTF_8));

        UserMarksStore store = new UserMarksStore(file());
        assertNull(store.get("+4930123456"));
        try {
            store.set("+4930111", UserMark.Type.SPAM, 1000, null);
            fail();
        } catch (IOException expected) {
            // ok
        }
        assertNull(store.get("+4930111"));
        assertEquals(content, new String(Files.readAllBytes(file().toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void failedWriteRollsBack() throws IOException {
        // the parent "directory" is a file: writes fail
        File blocker = folder.newFile("blocker");
        UserMarksStore store = new UserMarksStore(new File(blocker, "user_marks.csv"));
        try {
            store.set("+4930123456", UserMark.Type.SPAM, 1000, null);
            fail();
        } catch (IOException expected) {
            // ok
        }
        assertNull(store.get("+4930123456"));
        assertEquals(0, store.size());
    }

}
