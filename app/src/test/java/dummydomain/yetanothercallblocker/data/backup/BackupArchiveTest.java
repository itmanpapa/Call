package dummydomain.yetanothercallblocker.data.backup;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BackupArchiveTest {

    static BackupBundle sampleBundle() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("blockHiddenNumbers", true);
        settings.put("phoneBlockMinVotes", 10);
        settings.put("someLong", 1234567890123L);
        settings.put("someFloat", 1.5f);
        settings.put("callLogGrouping", "day");
        settings.put("phoneBlockToken", "secret\ttoken\\with\nbreaks");
        settings.put("blockInLimitedMode", new HashSet<>(Arrays.asList("rating", "blacklist")));
        settings.put("emptySet", new HashSet<String>());
        settings.put("emptyString", "");

        return new BackupBundle()
                .setCreatedAt(1_790_000_000_000L)
                .setAppVersion("0.12.0")
                .setIncludesSecrets(true)
                .setSettings(settings)
                .setBlacklistCsv("ID,name,pattern,creationTimestamp,numberOfCalls,lastCallTimestamp\r\n"
                        + "1,Spam,+4930*,1700000000000,3,\r\n")
                .setRulesText("callguard-rules\t1\nid=1\ttype=HIDDEN_NUMBER\taction=BLOCK\tenabled=1\n")
                .setUserMarksCsv("format,yacb-user-marks,1\nmark,+4930123456,SPAM,1727777777000,Ärger\n")
                .putList("bnetza", "format,yacb-number-list,1\n".getBytes(StandardCharsets.UTF_8))
                .putList("csv_my.list", new byte[]{1, 2, 3});
    }

    private static byte[] write(BackupBundle bundle) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupArchive.write(bundle, out);
        return out.toByteArray();
    }

    private static BackupBundle read(byte[] data) throws IOException {
        return BackupArchive.read(new ByteArrayInputStream(data));
    }

    @Test
    public void roundTrip() throws IOException {
        BackupBundle bundle = sampleBundle();
        BackupBundle read = read(write(bundle));

        assertEquals(bundle, read);
        assertEquals("0.12.0", read.getAppVersion());
        assertTrue(read.includesSecrets());
        assertEquals("secret\ttoken\\with\nbreaks", read.getSettings().get("phoneBlockToken"));
        assertEquals(1234567890123L, read.getSettings().get("someLong"));
        assertEquals(1.5f, read.getSettings().get("someFloat"));
        assertEquals(new HashSet<>(Arrays.asList("rating", "blacklist")),
                read.getSettings().get("blockInLimitedMode"));
        assertEquals(new HashSet<String>(), read.getSettings().get("emptySet"));
        assertEquals("", read.getSettings().get("emptyString"));
        assertArrayEquals(new byte[]{1, 2, 3}, read.getLists().get("csv_my.list"));
    }

    @Test
    public void missingPartsStayMissing() throws IOException {
        BackupBundle bundle = new BackupBundle().setCreatedAt(5);
        BackupBundle read = read(write(bundle));
        assertEquals(bundle, read);
        assertNull(read.getSettings());
        assertNull(read.getBlacklistCsv());
        assertNull(read.getRulesText());
        assertNull(read.getUserMarksCsv());
        assertTrue(read.getLists().isEmpty());
        assertFalse(read.includesSecrets());
        assertNull(read.getAppVersion());
    }

    @Test
    public void notAZipFile() {
        assertReason(BackupException.Reason.NOT_A_BACKUP,
                "ID,name,pattern\n".getBytes(StandardCharsets.UTF_8));
        assertReason(BackupException.Reason.NOT_A_BACKUP, new byte[0]);
    }

    @Test
    public void zipWithoutManifest() throws IOException {
        assertReason(BackupException.Reason.NOT_A_BACKUP,
                zip("readme.txt", "hello"));
        assertReason(BackupException.Reason.NOT_A_BACKUP,
                zip(BackupArchive.ENTRY_MANIFEST, "something else\t1\n"));
    }

    @Test
    public void newerVersionIsRejected() throws IOException {
        assertReason(BackupException.Reason.NEWER_VERSION,
                zip(BackupArchive.ENTRY_MANIFEST, "callguard-backup\t2\ncreatedAt=1\n"));
    }

    @Test
    public void unknownEntriesAndKeysAreIgnored() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, BackupArchive.ENTRY_MANIFEST,
                    "callguard-backup\t1\ncreatedAt=7\nfutureKey=x\nsecrets=0\n");
            put(zip, "future/part.bin", "data");
            put(zip, BackupArchive.ENTRY_SETTINGS,
                    "callguard-settings\t1\nb\ta\ttrue\nnewtype\tb\tx\n");
        }
        BackupBundle read = read(out.toByteArray());
        assertEquals(7, read.getCreatedAt());
        assertEquals(1, read.getSettings().size());
        assertEquals(true, read.getSettings().get("a"));
    }

    @Test
    public void unsafeEntryNamesAreRejected() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, BackupArchive.ENTRY_MANIFEST, "callguard-backup\t1\n");
            put(zip, "lists/../../evil.csv", "x");
        }
        assertReason(BackupException.Reason.CORRUPT, out.toByteArray());
    }

    @Test
    public void compressedBombIsRejected() throws IOException {
        // ~9 MB of zeros compress to a few KB: a text part above its limit
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, BackupArchive.ENTRY_MANIFEST, "callguard-backup\t1\n");
            zip.putNextEntry(new ZipEntry(BackupArchive.ENTRY_BLACKLIST));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 9; i++) zip.write(zeros);
            zip.closeEntry();
        }
        assertTrue(out.size() < 100_000);
        assertReason(BackupException.Reason.TOO_LARGE, out.toByteArray());
    }

    @Test
    public void tooManyDirectoryEntriesAreRejected() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, BackupArchive.ENTRY_MANIFEST, "callguard-backup\t1\n");
            for (int i = 0; i <= BackupArchive.MAX_ENTRIES; i++) {
                zip.putNextEntry(new ZipEntry("dir" + i + "/"));
                zip.closeEntry();
            }
        }
        assertReason(BackupException.Reason.TOO_LARGE, out.toByteArray());
    }

    @Test
    public void garbageAfterZipHeaderFailsCleanly() {
        byte[] data = new byte[4096];
        data[0] = 'P';
        data[1] = 'K';
        data[2] = 3;
        data[3] = 4;
        for (int i = 4; i < data.length; i++) data[i] = (byte) (i * 7);
        try {
            read(data);
            fail();
        } catch (IOException expected) {
            // a BackupException or another IOException, never a RuntimeException
        }
    }

    @Test
    public void restoreSkipsValuesOfAnotherType() {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("blockHiddenNumbers", true);
        current.put("callLogGrouping", "day");
        current.put("appUpdateLatestVersion", "0.12.0");
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("blockHiddenNumbers", "yes"); // a String instead of a Boolean
        backup.put("callLogGrouping", "none");
        backup.put("appUpdateLatestVersion", "0.1.0");

        Map<String, Object> toSet = new LinkedHashMap<>();
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        BackupSettingsPolicy.planRestore(current, backup, false, toSet, toRemove);

        assertEquals(1, toSet.size());
        assertEquals("none", toSet.get("callLogGrouping"));
        assertTrue(toRemove.toString(), toRemove.isEmpty());
    }

    @Test
    public void badSettingsAreCorrupt() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, BackupArchive.ENTRY_MANIFEST, "callguard-backup\t1\n");
            put(zip, BackupArchive.ENTRY_SETTINGS, "callguard-settings\t1\ni\tkey\tnotanumber\n");
        }
        assertReason(BackupException.Reason.CORRUPT, out.toByteArray());
    }

    @Test
    public void truncatedArchiveFails() throws IOException {
        byte[] data = write(sampleBundle());
        byte[] cut = Arrays.copyOf(data, data.length / 2);
        try {
            read(cut);
            fail();
        } catch (IOException expected) {
            // any IOException (a BackupException or an EOF) is fine
        }
    }

    @Test
    public void settingsCodecRoundTrip() throws IOException {
        Map<String, Object> values = new LinkedHashMap<>(sampleBundle().getSettings());
        values.put("key with\ttab", "v");
        values.put("ignored", new Object());
        Map<String, Object> decoded = SettingsCodec.decode(SettingsCodec.encode(values));
        values.remove("ignored");
        assertEquals(values, decoded);
    }

    private static byte[] zip(String name, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, name, content);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void assertReason(BackupException.Reason reason, byte[] data) {
        try {
            read(data);
            fail("expected " + reason);
        } catch (BackupException e) {
            assertEquals(e.getMessage(), reason, e.getReason());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

}
