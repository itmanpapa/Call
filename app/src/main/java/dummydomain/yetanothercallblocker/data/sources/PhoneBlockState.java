package dummydomain.yetanothercallblocker.data.sources;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Local copy of the PhoneBlock community blocklist with the vote counts and the
 * version needed for incremental updates.
 *
 * <p>The full state (all published numbers with their vote buckets) is kept separately
 * from the {@link NumberListStore} list: the list only contains the numbers above the
 * user's vote threshold, while the state allows changing the threshold and applying
 * incremental updates without downloading the full list again (PhoneBlock asks for
 * full downloads at most once a month).</p>
 *
 * <p>File format (UTF-8, one record per line, space separated):</p>
 * <pre>
 * phoneblock-state 1
 * version 42
 * lastFullSync 1727777777000
 * lastSync 1727777777000
 * entry +4930123456 10 G_FRAUD 1727000000000
 * </pre>
 * <p>An unknown rating is written as {@code -}. Unknown record types are ignored.</p>
 *
 * <p>Not thread-safe; {@link PhoneBlockSync} synchronizes access.</p>
 */
public class PhoneBlockState {

    static final String HEADER = "phoneblock-state";
    static final int FORMAT_VERSION = 1;

    private static final String TMP_SUFFIX = ".tmp";
    private static final String NO_RATING = "-";

    /** Counts of an applied update. */
    public static final class ApplyResult {
        public final int addedOrUpdated;
        public final int removed;

        ApplyResult(int addedOrUpdated, int removed) {
            this.addedOrUpdated = addedOrUpdated;
            this.removed = removed;
        }
    }

    private final Map<String, PhoneBlockClient.BlocklistEntry> entries = new TreeMap<>();
    private long version = -1;
    private long lastFullSync;
    private long lastSync;

    public PhoneBlockState() {
    }

    /** Creates a copy of the state. */
    public PhoneBlockState(PhoneBlockState other) {
        entries.putAll(other.entries);
        version = other.version;
        lastFullSync = other.lastFullSync;
        lastSync = other.lastSync;
    }

    /** @return the version for the next incremental update, -1 if there was no sync */
    public long getVersion() {
        return version;
    }

    /** @return time of the last full download, 0 if never */
    public long getLastFullSync() {
        return lastFullSync;
    }

    /** @return time of the last successful sync (full or incremental), 0 if never */
    public long getLastSync() {
        return lastSync;
    }

    public int size() {
        return entries.size();
    }

    public Collection<PhoneBlockClient.BlocklistEntry> getEntries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public PhoneBlockClient.BlocklistEntry get(String phone) {
        return entries.get(phone);
    }

    /**
     * Replaces the state with a full blocklist.
     */
    public ApplyResult applyFull(PhoneBlockClient.Blocklist blocklist, long now) {
        int removed = entries.size();
        entries.clear();
        int added = 0;
        for (PhoneBlockClient.BlocklistEntry entry : blocklist.getEntries()) {
            if (entry.getVotes() > 0) {
                entries.put(entry.getPhone(), entry);
                added++;
            }
        }
        version = blocklist.getVersion();
        lastFullSync = now;
        lastSync = now;
        return new ApplyResult(added, removed);
    }

    /**
     * Applies an incremental update: entries with {@code votes > 0} are added or
     * replaced, entries with {@code votes == 0} are removed.
     */
    public ApplyResult applyUpdate(PhoneBlockClient.Blocklist update, long now) {
        int added = 0;
        int removed = 0;
        for (PhoneBlockClient.BlocklistEntry entry : update.getEntries()) {
            if (entry.getVotes() > 0) {
                entries.put(entry.getPhone(), entry);
                added++;
            } else if (entries.remove(entry.getPhone()) != null) {
                removed++;
            }
        }
        if (update.getVersion() >= 0) version = update.getVersion();
        lastSync = now;
        return new ApplyResult(added, removed);
    }

    /**
     * Converts the numbers with at least {@code minVotes} votes to list entries.
     * Numbers rated as legitimate are never listed. The category is the PhoneBlock
     * rating code (e.g. {@code G_FRAUD}); the comment holds the vote bucket.
     *
     * @return entries sorted by number
     */
    public List<ListedNumber> toListedNumbers(int minVotes) {
        List<ListedNumber> result = new ArrayList<>();
        for (PhoneBlockClient.BlocklistEntry entry : entries.values()) {
            if (entry.getVotes() < minVotes) continue;
            if (entry.getRating() == PhoneBlockClient.Rating.A_LEGITIMATE) continue;

            result.add(ListedNumber.builder()
                    .number(entry.getPhone())
                    .category(entry.getRating() != null ? entry.getRating().name() : null)
                    .comment("votes>=" + entry.getVotes())
                    .build());
        }
        return result;
    }

    // persistence

    /**
     * Atomically writes the state to the file.
     */
    public void save(File file) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Can't create directory " + dir);
        }

        File tmp = new File(file.getPath() + TMP_SUFFIX);
        boolean ok = false;
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
                w.write(HEADER + " " + FORMAT_VERSION + "\n");
                w.write("version " + version + "\n");
                w.write("lastFullSync " + lastFullSync + "\n");
                w.write("lastSync " + lastSync + "\n");
                for (PhoneBlockClient.BlocklistEntry e : entries.values()) {
                    w.write("entry " + e.getPhone() + " " + e.getVotes() + " "
                            + (e.getRating() != null ? e.getRating().name() : NO_RATING)
                            + " " + e.getLastActivity() + "\n");
                }
                w.flush();
                out.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            ok = true;
        } finally {
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /**
     * Reads a state written by {@link #save(File)}.
     *
     * @return the state, or null if the file doesn't exist
     * @throws IOException if the file can't be read or is malformed
     */
    public static PhoneBlockState load(File file) throws IOException {
        if (!file.isFile()) return null;

        PhoneBlockState state = new PhoneBlockState();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (header == null || !header.startsWith(HEADER + " ")) {
                throw new IOException("Not a PhoneBlock state file: " + file);
            }
            int formatVersion = parseInt(header.substring(HEADER.length() + 1));
            if (formatVersion > FORMAT_VERSION) {
                throw new IOException("Unsupported state format version " + formatVersion);
            }

            String line;
            int lineNumber = 1;
            while ((line = r.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) continue;
                String[] parts = line.split(" ");
                try {
                    switch (parts[0]) {
                        case "version":
                            state.version = Long.parseLong(parts[1]);
                            break;
                        case "lastFullSync":
                            state.lastFullSync = Long.parseLong(parts[1]);
                            break;
                        case "lastSync":
                            state.lastSync = Long.parseLong(parts[1]);
                            break;
                        case "entry": {
                            String phone = PhoneBlockClient.normalizePhone(parts[1]);
                            if (phone == null) throw new IOException("Bad number");
                            PhoneBlockClient.Rating rating = NO_RATING.equals(parts[3])
                                    ? null : PhoneBlockClient.Rating.fromCode(parts[3]);
                            state.entries.put(phone, new PhoneBlockClient.BlocklistEntry(
                                    phone, Integer.parseInt(parts[2]), rating,
                                    Long.parseLong(parts[4])));
                            break;
                        }
                        default:
                            // unknown record type, ignored for forward compatibility
                    }
                } catch (RuntimeException e) {
                    throw new IOException("Malformed line " + lineNumber + " in " + file, e);
                }
            }
        }
        return state;
    }

    private static int parseInt(String s) throws IOException {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Bad number: " + s, e);
        }
    }

}
