package dummydomain.yetanothercallblocker.data;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.QuoteMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The user's own spam marks ("My mark"): normalized number → {@link UserMark}.
 *
 * <p>Kept in a small UTF-8 CSV file (no database schema change), loaded lazily on the
 * first access and kept in memory afterwards:</p>
 * <pre>
 * format,yacb-user-marks,1
 * mark,+4930123456,SPAM,1727777777000,note
 * mark,+4915112345678,NOT_SPAM,1727777778000,
 * </pre>
 * <p>Unknown record types and marks of unknown types are ignored (forward compatible),
 * a higher format version is rejected (the file is then left untouched and the store
 * stays empty and read-only for this session, so a newer file is never overwritten).
 * Writes are atomic (temporary file + rename).</p>
 *
 * <p>Keys are normalized with {@link #normalizeKey(String)}: callers should pass the
 * E.164 number ({@code NumberInfo.normalizedNumber}) when it is known; lookups accept
 * several candidates (e.g. the normalized and the raw number).</p>
 *
 * <p>Plain Java, no Android dependencies. All methods are synchronized.</p>
 */
public class UserMarksStore {

    public interface Listener {
        /** Called after a mark was set or removed (on the thread that changed it). */
        void onUserMarksChanged();
    }

    public static final String FORMAT_NAME = "yacb-user-marks";
    public static final int FORMAT_VERSION = 1;

    static final String TYPE_FORMAT = "format";
    static final String TYPE_MARK = "mark";
    static final String TMP_SUFFIX = ".tmp";

    private static final Logger LOG = LoggerFactory.getLogger(UserMarksStore.class);

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT
            .withRecordSeparator("\n")
            .withQuoteMode(QuoteMode.MINIMAL);

    private final File file;

    private Map<String, UserMark> marks;
    private boolean readOnly;
    private volatile Listener listener;

    public UserMarksStore(File file) {
        this.file = Objects.requireNonNull(file);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Removes formatting from a number: whitespace and the usual separators
     * ({@code - ( ) / .}). A leading {@code 00} is kept as is (no country guessing).
     *
     * @return the key, or null for an empty number
     */
    public static String normalizeKey(String number) {
        if (number == null) return null;
        StringBuilder sb = new StringBuilder(number.length());
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (Character.isWhitespace(c) || c == '-' || c == '(' || c == ')'
                    || c == '/' || c == '.' || c == ' ') {
                continue;
            }
            sb.append(c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** Loads the file if it was not loaded yet; can be called from a background thread. */
    public synchronized void ensureLoaded() {
        if (marks != null) return;

        marks = new LinkedHashMap<>();
        if (!file.isFile()) return;

        try {
            load();
        } catch (Exception e) {
            LOG.error("ensureLoaded() failed to read {}", file, e);
            readOnly = true; // don't overwrite a file we don't understand
            marks.clear();
        }
    }

    /**
     * @param numbers candidate numbers (e.g. the normalized and the raw number);
     *                null and empty values are skipped
     * @return the mark of the first candidate that has one, or null
     */
    public synchronized UserMark get(String... numbers) {
        ensureLoaded();
        if (numbers == null) return null;
        for (String number : numbers) {
            String key = normalizeKey(number);
            if (key == null) continue;
            UserMark mark = marks.get(key);
            if (mark != null) return mark;
        }
        return null;
    }

    /**
     * Sets (replaces) the mark of a number.
     *
     * @return the previous mark, or null
     */
    public UserMark set(String number, UserMark.Type type, long timestamp, String note)
            throws IOException {
        UserMark previous;
        synchronized (this) {
            ensureLoaded();
            String key = requireKey(number);
            checkWritable();
            previous = marks.put(key, new UserMark(key, type, timestamp, note));
            persist(key, previous);
        }
        notifyListener();
        return previous;
    }

    /**
     * Removes the mark of a number.
     *
     * @return the removed mark, or null if there was none
     */
    public UserMark remove(String number) throws IOException {
        UserMark previous;
        synchronized (this) {
            ensureLoaded();
            String key = normalizeKey(number);
            if (key == null || !marks.containsKey(key)) return null;
            checkWritable();
            previous = marks.remove(key);
            persist(key, previous);
        }
        notifyListener();
        return previous;
    }

    /**
     * Restores a previous state (for "undo"): puts {@code previous} back, or removes the
     * mark of {@code number} if {@code previous} is null.
     */
    public void restore(String number, UserMark previous) throws IOException {
        if (previous == null) {
            remove(number);
            return;
        }
        synchronized (this) {
            ensureLoaded();
            checkWritable();
            UserMark replaced = marks.put(previous.getNumber(), previous);
            persist(previous.getNumber(), replaced);
        }
        notifyListener();
    }

    /** @return all marks, newest first */
    public synchronized List<UserMark> getAll() {
        ensureLoaded();
        List<UserMark> list = new ArrayList<>(marks.values());
        list.sort(Comparator.comparingLong(UserMark::getTimestamp).reversed());
        return Collections.unmodifiableList(list);
    }

    public synchronized int size() {
        ensureLoaded();
        return marks.size();
    }

    private static String requireKey(String number) {
        String key = normalizeKey(number);
        if (key == null) throw new IllegalArgumentException("Empty number");
        return key;
    }

    private void checkWritable() throws IOException {
        if (readOnly) {
            throw new IOException("The marks file could not be read, not overwriting it: "
                    + file);
        }
    }

    /** Writes the file; on failure rolls back the in-memory change of {@code key}. */
    private void persist(String key, UserMark previous) throws IOException {
        try {
            save();
        } catch (IOException e) {
            if (previous != null) marks.put(key, previous);
            else marks.remove(key);
            throw e;
        }
    }

    private void notifyListener() {
        Listener l = listener;
        if (l != null) {
            try {
                l.onUserMarksChanged();
            } catch (Exception e) {
                LOG.warn("notifyListener() listener failed", e);
            }
        }
    }

    private void load() throws IOException {
        try (Reader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8));
             CSVParser parser = CSV_FORMAT.parse(reader)) {
            boolean formatSeen = false;
            for (CSVRecord record : parser) {
                if (record.size() == 0) continue;
                String recordType = record.get(0);

                if (TYPE_FORMAT.equals(recordType)) {
                    if (record.size() < 3 || !FORMAT_NAME.equals(record.get(1))) {
                        throw new IOException("Unknown format: " + record);
                    }
                    int version;
                    try {
                        version = Integer.parseInt(record.get(2).trim());
                    } catch (NumberFormatException e) {
                        throw new IOException("Bad format version: " + record);
                    }
                    if (version > FORMAT_VERSION) {
                        throw new IOException("Unsupported format version: " + version);
                    }
                    formatSeen = true;
                } else if (TYPE_MARK.equals(recordType)) {
                    if (!formatSeen) throw new IOException("Missing format header");
                    UserMark mark = parseMark(record);
                    if (mark != null) marks.put(mark.getNumber(), mark);
                }
                // unknown record types are ignored
            }
            if (!formatSeen && !marks.isEmpty()) throw new IOException("Missing format header");
        }
        LOG.debug("load() loaded {} marks", marks.size());
    }

    private static UserMark parseMark(CSVRecord record) {
        if (record.size() < 4) {
            LOG.warn("parseMark() skipping short record {}", record.getRecordNumber());
            return null;
        }
        String key = normalizeKey(record.get(1));
        if (key == null) return null;

        UserMark.Type type;
        try {
            type = UserMark.Type.valueOf(record.get(2).trim());
        } catch (IllegalArgumentException e) {
            LOG.warn("parseMark() skipping unknown type {}", record.get(2));
            return null;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(record.get(3).trim());
        } catch (NumberFormatException e) {
            timestamp = 0;
        }

        String note = record.size() > 4 ? record.get(4) : null;
        return new UserMark(key, type, timestamp, note);
    }

    private void save() throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }

        File tmp = new File(file.getPath() + TMP_SUFFIX);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            CSVPrinter printer = new CSVPrinter(writer, CSV_FORMAT);
            printer.printRecord(TYPE_FORMAT, FORMAT_NAME, FORMAT_VERSION);
            for (UserMark mark : marks.values()) {
                printer.printRecord(TYPE_MARK, mark.getNumber(), mark.getType().name(),
                        mark.getTimestamp(), mark.getNote() != null ? mark.getNote() : "");
            }
            printer.flush();
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }

        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

}
