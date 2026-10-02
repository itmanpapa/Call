package dummydomain.yetanothercallblocker.data.sources;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.QuoteMode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Persists named lists of {@link ListedNumber} (an imported BNetzA list, a user CSV, ...)
 * as files in a directory, one file per source.
 *
 * <p>File format (UTF-8 CSV, RFC 4180 quoting, one record per row; quoted values
 * may contain line breaks). The first column is the record type:</p>
 * <pre>
 * format,yacb-number-list,1
 * meta,sourceId,bnetza
 * meta,displayName,Bundesnetzagentur
 * meta,importedAt,1727777777000
 * meta,entryCount,2
 * meta,remoteUrl,https://example.org/list.xml
 * meta,remoteAutoUpdate,true
 * entry,N,+4915112345678,name,category,comment,DISCONNECTION,measure text,2024-01-15,raw text
 * entry,P,+4990012345,,,,NONE,,,
 * </pre>
 * <p>{@code N} marks an exact number, {@code P} a prefix. A {@code null} value is
 * written as an empty field; empty fields are read back as {@code null} (so an empty
 * raw text becomes {@code null}; all other text fields of {@link ListedNumber} are
 * never empty anyway). Unknown
 * {@code meta} keys and unknown record types are ignored, so the format can be
 * extended without bumping the version; a higher version is rejected.</p>
 *
 * <p>Lists downloaded from a URL additionally store a {@link RemoteListInfo} as
 * optional {@code meta} records ({@code remoteUrl}, {@code remoteAutoUpdate},
 * {@code remoteEtag}, {@code remoteLastModified}, {@code remoteLastCheckAt},
 * {@code remoteLastError}, {@code remoteLastSuccessAt}) and, for lists assembled from
 * several downloads, one {@code meta,remotePart,<url>,<etag>,<lastModified>} record per
 * downloaded URL; older versions of the app ignore them.</p>
 *
 * <p>Writes are atomic: the list is written to a temporary file, synced and then
 * renamed over the old file, so a crash never leaves a half-written list behind.
 * The stored entry count is checked on load to detect truncated files.</p>
 *
 * <p>Plain Java, no Android dependencies (the app passes {@code context.getFilesDir()}
 * or a subdirectory of it). All methods are synchronized on the store instance.</p>
 */
public class NumberListStore {

    public static final String FORMAT_NAME = "yacb-number-list";
    public static final int FORMAT_VERSION = 1;

    static final String FILE_PREFIX = "numberlist_";
    static final String FILE_SUFFIX = ".csv";
    static final String TMP_SUFFIX = ".tmp";

    static final String TYPE_FORMAT = "format";
    static final String TYPE_META = "meta";
    static final String TYPE_ENTRY = "entry";

    static final String META_SOURCE_ID = "sourceId";
    static final String META_DISPLAY_NAME = "displayName";
    static final String META_IMPORTED_AT = "importedAt";
    static final String META_ENTRY_COUNT = "entryCount";
    static final String META_REMOTE_URL = "remoteUrl";
    static final String META_REMOTE_AUTO_UPDATE = "remoteAutoUpdate";
    static final String META_REMOTE_ETAG = "remoteEtag";
    static final String META_REMOTE_LAST_MODIFIED = "remoteLastModified";
    static final String META_REMOTE_LAST_CHECK_AT = "remoteLastCheckAt";
    static final String META_REMOTE_LAST_ERROR = "remoteLastError";
    static final String META_REMOTE_LAST_SUCCESS_AT = "remoteLastSuccessAt";
    static final String META_REMOTE_PART = "remotePart";

    static final String KIND_NUMBER = "N";
    static final String KIND_PREFIX = "P";

    private static final Pattern SOURCE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    /**
     * {@code null} is written as an empty unquoted field, non-null values are quoted.
     * commons-csv 1.8 reads both an empty and a quoted empty field as {@code null}.
     */
    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT
            .withRecordSeparator('\n')
            .withQuoteMode(QuoteMode.ALL_NON_NULL)
            .withNullString("");

    /** Metadata of a stored list. Immutable. */
    public static final class ListMetadata {

        private final String sourceId;
        private final String displayName;
        private final long importedAt;
        private final int entryCount;
        private final RemoteListInfo remote;

        public ListMetadata(String sourceId, String displayName, long importedAt, int entryCount) {
            this(sourceId, displayName, importedAt, entryCount, null);
        }

        public ListMetadata(String sourceId, String displayName, long importedAt, int entryCount,
                            RemoteListInfo remote) {
            this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
            this.displayName = displayName;
            this.importedAt = importedAt;
            this.entryCount = entryCount;
            this.remote = remote;
        }

        public String getSourceId() {
            return sourceId;
        }

        /** Human-readable list name, may be null. */
        public String getDisplayName() {
            return displayName;
        }

        /** Import time in milliseconds since the epoch. */
        public long getImportedAt() {
            return importedAt;
        }

        public int getEntryCount() {
            return entryCount;
        }

        /** Download state of a list imported from a URL, null for lists imported from a file. */
        public RemoteListInfo getRemote() {
            return remote;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ListMetadata)) return false;
            ListMetadata that = (ListMetadata) o;
            return importedAt == that.importedAt
                    && entryCount == that.entryCount
                    && sourceId.equals(that.sourceId)
                    && Objects.equals(displayName, that.displayName)
                    && Objects.equals(remote, that.remote);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceId, displayName, importedAt, entryCount, remote);
        }

        @Override
        public String toString() {
            return "ListMetadata{" +
                    "sourceId='" + sourceId + '\'' +
                    ", displayName='" + displayName + '\'' +
                    ", importedAt=" + importedAt +
                    ", entryCount=" + entryCount +
                    (remote != null ? ", remote=" + remote : "") +
                    '}';
        }
    }

    /** A loaded list: metadata plus entries. Immutable. */
    public static final class StoredList {

        private final ListMetadata metadata;
        private final List<ListedNumber> entries;

        StoredList(ListMetadata metadata, List<ListedNumber> entries) {
            this.metadata = metadata;
            this.entries = Collections.unmodifiableList(entries);
        }

        public ListMetadata getMetadata() {
            return metadata;
        }

        public List<ListedNumber> getEntries() {
            return entries;
        }
    }

    private final File directory;

    /**
     * @param directory directory for list files; created on the first save if missing
     */
    public NumberListStore(File directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public File getDirectory() {
        return directory;
    }

    /**
     * Atomically replaces the stored list of the given source.
     *
     * @param sourceId    source id, {@code [A-Za-z0-9_.-]{1,64}}
     * @param displayName human-readable name, may be null
     * @param importedAt  import time, millis since the epoch
     * @param entries     entries to store
     * @return metadata of the stored list
     */
    public synchronized ListMetadata save(String sourceId, String displayName, long importedAt,
                                          List<ListedNumber> entries) throws IOException {
        return save(sourceId, displayName, importedAt, entries, null);
    }

    /**
     * Atomically replaces the stored list of the given source.
     *
     * @param remote download state of a list imported from a URL, or null
     * @see #save(String, String, long, List)
     */
    public synchronized ListMetadata save(String sourceId, String displayName, long importedAt,
                                          List<ListedNumber> entries,
                                          RemoteListInfo remote) throws IOException {
        checkSourceId(sourceId);
        Objects.requireNonNull(entries, "entries");

        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Can't create directory " + directory);
        }

        ListMetadata metadata = new ListMetadata(sourceId, displayName, importedAt,
                entries.size(), remote);

        File target = getFile(sourceId);
        File tmp = new File(directory, target.getName() + TMP_SUFFIX);

        boolean ok = false;
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
                CSVPrinter printer = new CSVPrinter(writer, CSV_FORMAT);
                write(printer, metadata, entries);
                printer.flush();
                out.getFD().sync();
            }
            moveAtomically(tmp, target);
            ok = true;
        } finally {
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }

        return metadata;
    }

    /**
     * Loads the stored list of the given source.
     *
     * @return the list, or {@code null} if nothing is stored for this source
     * @throws IOException if the file can't be read or is malformed
     */
    public synchronized StoredList load(String sourceId) throws IOException {
        checkSourceId(sourceId);
        try (Reader reader = openReader(sourceId)) {
            if (reader == null) return null;
            return read(reader, sourceId, false);
        }
    }

    /**
     * Reads only the metadata of the stored list (fast, entries are not parsed).
     *
     * @return metadata, or {@code null} if nothing is stored for this source
     */
    public synchronized ListMetadata loadMetadata(String sourceId) throws IOException {
        checkSourceId(sourceId);
        try (Reader reader = openReader(sourceId)) {
            if (reader == null) return null;
            return read(reader, sourceId, true).getMetadata();
        }
    }

    /**
     * Replaces the remote info of a stored list, keeping its entries, name and import time.
     *
     * @return the new metadata, or {@code null} if nothing is stored for this source
     * @throws IOException if the list can't be read or written
     */
    public synchronized ListMetadata updateRemote(String sourceId,
                                                  RemoteListInfo remote) throws IOException {
        StoredList list = load(sourceId);
        if (list == null) return null;
        ListMetadata old = list.getMetadata();
        return save(sourceId, old.getDisplayName(), old.getImportedAt(), list.getEntries(), remote);
    }

    // not synchronized: a single file check (saves replace the file atomically), so it
    // doesn't wait for a long load or save, e.g. when called on the main thread
    public boolean exists(String sourceId) {
        checkSourceId(sourceId);
        return getFile(sourceId).isFile();
    }

    /**
     * Deletes the stored list of the given source.
     *
     * @return true if a list was deleted
     */
    public synchronized boolean delete(String sourceId) {
        checkSourceId(sourceId);
        new File(directory, getFile(sourceId).getName() + TMP_SUFFIX).delete();
        return getFile(sourceId).delete();
    }

    /**
     * @return ids of all stored lists, sorted
     */
    public synchronized List<String> listSourceIds() {
        List<String> ids = new ArrayList<>();
        String[] names = directory.list();
        if (names == null) return ids;

        for (String name : names) {
            if (name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)) {
                String id = name.substring(FILE_PREFIX.length(),
                        name.length() - FILE_SUFFIX.length());
                if (SOURCE_ID_PATTERN.matcher(id).matches()) ids.add(id);
            }
        }
        Collections.sort(ids);
        return ids;
    }

    /** @return the file of the list (it may not exist), e.g. to copy it into a backup */
    public File getFile(String sourceId) {
        checkSourceId(sourceId);
        return new File(directory, FILE_PREFIX + sourceId + FILE_SUFFIX);
    }

    /**
     * Parses a list file of this format (e.g. from a backup) without storing it.
     *
     * @param expectedSourceId the source id the file must belong to
     * @throws IOException if the data is malformed or has a newer version
     */
    public static StoredList parse(Reader reader, String expectedSourceId) throws IOException {
        checkSourceId(expectedSourceId);
        return read(reader, expectedSourceId, false);
    }

    private static void checkSourceId(String sourceId) {
        if (sourceId == null || !SOURCE_ID_PATTERN.matcher(sourceId).matches()) {
            throw new IllegalArgumentException("Invalid source id: " + sourceId);
        }
    }

    private Reader openReader(String sourceId) throws IOException {
        File file = getFile(sourceId);
        if (!file.isFile()) return null;
        try {
            return new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8));
        } catch (FileNotFoundException e) {
            return null; // deleted concurrently by someone else
        }
    }

    private static void moveAtomically(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // Serialization

    static void write(CSVPrinter printer, ListMetadata metadata,
                      List<ListedNumber> entries) throws IOException {
        printer.printRecord(TYPE_FORMAT, FORMAT_NAME, String.valueOf(FORMAT_VERSION));
        printer.printRecord(TYPE_META, META_SOURCE_ID, metadata.getSourceId());
        printer.printRecord(TYPE_META, META_DISPLAY_NAME, metadata.getDisplayName());
        printer.printRecord(TYPE_META, META_IMPORTED_AT, String.valueOf(metadata.getImportedAt()));
        printer.printRecord(TYPE_META, META_ENTRY_COUNT, String.valueOf(metadata.getEntryCount()));

        RemoteListInfo remote = metadata.getRemote();
        if (remote != null) {
            printer.printRecord(TYPE_META, META_REMOTE_URL, remote.getUrl());
            printer.printRecord(TYPE_META, META_REMOTE_AUTO_UPDATE,
                    String.valueOf(remote.isAutoUpdate()));
            printer.printRecord(TYPE_META, META_REMOTE_ETAG, remote.getEtag());
            printer.printRecord(TYPE_META, META_REMOTE_LAST_MODIFIED, remote.getLastModified());
            printer.printRecord(TYPE_META, META_REMOTE_LAST_CHECK_AT,
                    String.valueOf(remote.getLastCheckAt()));
            printer.printRecord(TYPE_META, META_REMOTE_LAST_ERROR, remote.getLastError());
            printer.printRecord(TYPE_META, META_REMOTE_LAST_SUCCESS_AT,
                    String.valueOf(remote.getLastSuccessAt()));
            for (RemoteListInfo.Part part : remote.getParts()) {
                printer.printRecord(TYPE_META, META_REMOTE_PART, part.getUrl(),
                        part.getEtag(), part.getLastModified());
            }
        }

        for (ListedNumber e : entries) {
            printer.printRecord(TYPE_ENTRY,
                    e.isPrefix() ? KIND_PREFIX : KIND_NUMBER,
                    e.isPrefix() ? e.getPrefix() : e.getNumber(),
                    e.getName(),
                    e.getCategory(),
                    e.getComment(),
                    e.getMeasureType().name(),
                    e.getMeasureText(),
                    e.getDate() != null ? e.getDate().toString() : null,
                    e.getRawText());
        }
    }

    static StoredList read(Reader reader, String expectedSourceId,
                           boolean metadataOnly) throws IOException {
        CSVParser parser;
        try {
            parser = CSV_FORMAT.parse(reader);
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed list file", e);
        }

        String sourceId = null;
        String displayName = null;
        long importedAt = 0;
        int entryCount = -1;
        String remoteUrl = null;
        boolean remoteAutoUpdate = false;
        String remoteEtag = null;
        String remoteLastModified = null;
        long remoteLastCheckAt = 0;
        String remoteLastError = null;
        long remoteLastSuccessAt = -1;
        List<RemoteListInfo.Part> remoteParts = new ArrayList<>();
        boolean formatSeen = false;
        List<ListedNumber> entries = new ArrayList<>();

        try {
            Iterator<CSVRecord> it = parser.iterator();
            while (it.hasNext()) {
                CSVRecord record = it.next();
                String type = get(record, 0);
                if (type == null) continue;

                if (!formatSeen) {
                    if (!TYPE_FORMAT.equals(type) || !FORMAT_NAME.equals(get(record, 1))) {
                        throw new IOException("Not a number list file");
                    }
                    int version = parseInt(get(record, 2), "format version");
                    if (version < 1 || version > FORMAT_VERSION) {
                        throw new IOException("Unsupported format version: " + version);
                    }
                    formatSeen = true;
                    continue;
                }

                if (TYPE_META.equals(type)) {
                    String key = get(record, 1);
                    String value = get(record, 2);
                    if (META_SOURCE_ID.equals(key)) {
                        sourceId = value;
                    } else if (META_DISPLAY_NAME.equals(key)) {
                        displayName = value;
                    } else if (META_IMPORTED_AT.equals(key)) {
                        importedAt = parseLong(value, "importedAt");
                    } else if (META_ENTRY_COUNT.equals(key)) {
                        entryCount = parseInt(value, "entryCount");
                    } else if (META_REMOTE_URL.equals(key)) {
                        remoteUrl = value;
                    } else if (META_REMOTE_AUTO_UPDATE.equals(key)) {
                        remoteAutoUpdate = Boolean.parseBoolean(value);
                    } else if (META_REMOTE_ETAG.equals(key)) {
                        remoteEtag = value;
                    } else if (META_REMOTE_LAST_MODIFIED.equals(key)) {
                        remoteLastModified = value;
                    } else if (META_REMOTE_LAST_CHECK_AT.equals(key)) {
                        remoteLastCheckAt = value != null ? parseLong(value, "remoteLastCheckAt") : 0;
                    } else if (META_REMOTE_LAST_ERROR.equals(key)) {
                        remoteLastError = value;
                    } else if (META_REMOTE_LAST_SUCCESS_AT.equals(key)) {
                        remoteLastSuccessAt = value != null
                                ? parseLong(value, "remoteLastSuccessAt") : 0;
                    } else if (META_REMOTE_PART.equals(key)) {
                        if (value != null) {
                            remoteParts.add(new RemoteListInfo.Part(value,
                                    get(record, 3), get(record, 4)));
                        }
                    }
                } else if (TYPE_ENTRY.equals(type)) {
                    if (metadataOnly) break;
                    entries.add(parseEntry(record));
                }
            }
        } catch (IllegalStateException e) {
            // commons-csv wraps parse errors (e.g. an unterminated quote) this way
            throw new IOException("Malformed list file", e);
        }

        if (!formatSeen) throw new IOException("Empty list file");
        if (sourceId == null) throw new IOException("Missing source id");
        if (expectedSourceId != null && !expectedSourceId.equals(sourceId)) {
            throw new IOException("Source id mismatch: " + sourceId);
        }
        if (entryCount < 0) throw new IOException("Missing entry count");
        if (!metadataOnly && entries.size() != entryCount) {
            throw new IOException("Truncated list file: expected " + entryCount
                    + " entries, found " + entries.size());
        }

        RemoteListInfo remote = remoteUrl != null
                ? new RemoteListInfo(remoteUrl, remoteAutoUpdate, remoteEtag, remoteLastModified,
                        remoteLastCheckAt, remoteLastError,
                        // files of older versions: a check without an error was a success
                        remoteLastSuccessAt >= 0 ? remoteLastSuccessAt
                                : remoteLastError == null ? remoteLastCheckAt : 0,
                        remoteParts)
                : null;
        ListMetadata metadata = new ListMetadata(sourceId, displayName, importedAt, entryCount,
                remote);
        return new StoredList(metadata, metadataOnly ? Collections.emptyList() : entries);
    }

    private static ListedNumber parseEntry(CSVRecord record) throws IOException {
        String kind = get(record, 1);
        String value = get(record, 2);
        if (value == null || value.isEmpty()) {
            throw new IOException("Entry without a number at record " + record.getRecordNumber());
        }

        ListedNumber.Builder b = ListedNumber.builder();
        if (KIND_PREFIX.equals(kind)) {
            b.prefix(value);
        } else if (KIND_NUMBER.equals(kind)) {
            b.number(value);
        } else {
            throw new IOException("Unknown entry kind \"" + kind
                    + "\" at record " + record.getRecordNumber());
        }

        b.name(get(record, 3))
                .category(get(record, 4))
                .comment(get(record, 5))
                .measureType(parseMeasureType(get(record, 6)))
                .measureText(get(record, 7))
                .rawText(get(record, 9));

        String date = get(record, 8);
        if (date != null && !date.isEmpty()) {
            try {
                b.date(LocalDate.parse(date));
            } catch (DateTimeParseException e) {
                throw new IOException("Invalid date at record " + record.getRecordNumber(), e);
            }
        }

        return b.build();
    }

    private static MeasureType parseMeasureType(String s) {
        if (s == null) return MeasureType.NONE;
        try {
            return MeasureType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return MeasureType.UNKNOWN; // written by a newer version
        }
    }

    private static String get(CSVRecord record, int index) {
        return index < record.size() ? record.get(index) : null;
    }

    private static int parseInt(String s, String what) throws IOException {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid " + what + ": " + s, e);
        }
    }

    private static long parseLong(String s, String what) throws IOException {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid " + what + ": " + s, e);
        }
    }

}
