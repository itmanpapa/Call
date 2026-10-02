package dummydomain.yetanothercallblocker.data.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Append-only log of handled incoming calls for the statistics.
 *
 * <p>A small UTF-8 text file, one call per line (no quoting: the values never contain
 * commas, they are sanitized on write):</p>
 * <pre>
 * format,callguard-call-events,1
 * c,1727777777000,+4930123456,BLOCKED,LIST,bnetza
 * c,1727777790000,,BLOCKED,HIDDEN,
 * c,1727777999000,+4989123456,ALLOWED,NONE,
 * </pre>
 * <p>Fields: timestamp (millis), normalized number (empty for a hidden number), outcome,
 * reason, source id. Lines that can't be parsed (e.g. cut off by a crash) and unknown
 * record types are skipped; a higher format version is rejected by {@link #load(long)}
 * and the file is then never compacted (so a newer file is not rewritten).</p>
 *
 * <p>One call is often reported twice (by the call screening service and the phone
 * state listener): events of the same number within {@link #DEDUPE_WINDOW_MILLIS} are
 * one call. The second report is only written if its outcome is stronger
 * (BLOCKED &gt; NOTIFIED &gt; ALLOWED), and {@link #load(long)} merges such pairs.</p>
 *
 * <p>Events older than the retention period (365 days) are dropped by compaction, which
 * also keeps the file under a size cap; compaction writes a temporary file and renames
 * it over the log (atomic). It runs once per process when the oldest event has expired,
 * and whenever the file grows over the cap.</p>
 *
 * <p>Plain Java, no Android dependencies. All methods are synchronized.</p>
 */
public class CallEventStore {

    public static final String FORMAT_NAME = "callguard-call-events";
    public static final int FORMAT_VERSION = 1;

    public static final long RETENTION_MILLIS = TimeUnit.DAYS.toMillis(365);
    public static final long DEDUPE_WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(15);

    static final long DEFAULT_MAX_FILE_BYTES = 1024 * 1024;
    static final int DEFAULT_MAX_EVENTS = 20_000;

    static final String TYPE_FORMAT = "format";
    static final String TYPE_CALL = "c";
    static final String TMP_SUFFIX = ".tmp";

    /** Slack before an expired first event triggers a compaction (avoids daily rewrites). */
    private static final long COMPACTION_SLACK_MILLIS = TimeUnit.DAYS.toMillis(7);

    private static final Logger LOG = LoggerFactory.getLogger(CallEventStore.class);

    private final File file;
    private final long maxFileBytes;
    private final int maxEvents;

    /** Events written recently (within the de-duplication window), newest last. */
    private final Deque<CallStatEvent> recent = new ArrayDeque<>();
    private boolean expiryChecked;

    public CallEventStore(File file) {
        this(file, DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_EVENTS);
    }

    CallEventStore(File file, long maxFileBytes, int maxEvents) {
        this.file = Objects.requireNonNull(file, "file");
        this.maxFileBytes = maxFileBytes;
        this.maxEvents = maxEvents;
    }

    public File getFile() {
        return file;
    }

    /**
     * Appends a handled call (blocks on file I/O: call it from a background thread).
     *
     * @return false if the event was a repeated report of an already recorded call
     */
    public synchronized boolean record(CallStatEvent event) throws IOException {
        Objects.requireNonNull(event, "event");

        long ts = event.getTimestamp();
        for (Iterator<CallStatEvent> it = recent.iterator(); it.hasNext(); ) {
            CallStatEvent e = it.next();
            if (Math.abs(ts - e.getTimestamp()) > DEDUPE_WINDOW_MILLIS) it.remove();
        }

        CallStatEvent previous = null;
        for (CallStatEvent e : recent) {
            if (e.getNumber().equals(event.getNumber())) previous = e;
        }
        if (previous != null) {
            if (event.getOutcome().compareTo(previous.getOutcome()) <= 0) {
                LOG.debug("record() repeated report ignored: {}", event);
                return false;
            }
            // a stronger outcome for the same call: written, merged on load
            recent.remove(previous);
        }

        append(event);
        recent.addLast(event);

        try {
            if (!expiryChecked) {
                expiryChecked = true;
                long first = readFirstTimestamp();
                if (first >= 0 && first < ts - RETENTION_MILLIS - COMPACTION_SLACK_MILLIS) {
                    LOG.info("record() compacting: expired events");
                    compact(ts);
                }
            }
            if (file.length() > maxFileBytes) {
                LOG.info("record() compacting: file size {}", file.length());
                compact(ts);
            }
        } catch (IOException e) {
            // the event itself is recorded
            LOG.warn("record() compaction failed", e);
        }
        return true;
    }

    /**
     * Reads all events of the retention period (repeated reports merged), oldest first.
     *
     * @param now the current time, millis
     * @throws IOException if the file can't be read or was written by a newer version
     */
    public synchronized List<CallStatEvent> load(long now) throws IOException {
        List<CallStatEvent> events = new ArrayList<>();
        BufferedReader reader;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8));
        } catch (FileNotFoundException e) {
            return events;
        }

        long cutoff = now - RETENTION_MILLIS;
        try (BufferedReader in = reader) {
            String line;
            boolean formatSeen = false;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] f = line.split(",", -1);
                if (TYPE_FORMAT.equals(f[0])) {
                    if (f.length < 3 || !FORMAT_NAME.equals(f[1])) {
                        throw new IOException("Not a call events file");
                    }
                    int version;
                    try {
                        version = Integer.parseInt(f[2].trim());
                    } catch (NumberFormatException e) {
                        throw new IOException("Bad format version: " + line);
                    }
                    if (version > FORMAT_VERSION) {
                        throw new IOException("Unsupported format version: " + version);
                    }
                    formatSeen = true;
                } else if (TYPE_CALL.equals(f[0])) {
                    if (!formatSeen) throw new IOException("Missing format header");
                    CallStatEvent event = parse(f);
                    if (event != null && event.getTimestamp() >= cutoff) events.add(event);
                }
                // unknown record types are ignored
            }
        }
        return mergeRepeated(events);
    }

    /**
     * Rewrites the file without expired events and repeated reports, keeping at most
     * the newest events that fit into the size cap (with some headroom).
     */
    public synchronized void compact(long now) throws IOException {
        List<CallStatEvent> events = load(now);

        // newest first until the limits are reached
        long budget = maxFileBytes * 3 / 4;
        List<String> lines = new ArrayList<>();
        long size = header().length();
        for (int i = events.size() - 1; i >= 0 && lines.size() < maxEvents; i--) {
            String line = format(events.get(i));
            size += line.getBytes(StandardCharsets.UTF_8).length;
            if (size > budget) break;
            lines.add(line);
        }
        Collections.reverse(lines);

        writeAtomically(lines);
        LOG.debug("compact() kept {} of {} events", lines.size(), events.size());
    }

    /** Deletes all recorded events. */
    public synchronized void clear() throws IOException {
        recent.clear();
        if (file.exists() && !file.delete()) throw new IOException("Can't delete " + file);
    }

    // implementation

    static List<CallStatEvent> mergeRepeated(List<CallStatEvent> events) {
        List<CallStatEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(CallStatEvent::getTimestamp)); // stable

        List<CallStatEvent> result = new ArrayList<>(sorted.size());
        Map<String, Integer> lastIndex = new HashMap<>();
        for (CallStatEvent event : sorted) {
            Integer index = lastIndex.get(event.getNumber());
            if (index != null) {
                CallStatEvent kept = result.get(index);
                if (event.getTimestamp() - kept.getTimestamp() <= DEDUPE_WINDOW_MILLIS) {
                    if (event.getOutcome().compareTo(kept.getOutcome()) > 0) {
                        // keep the time of the first report, the details of the stronger one
                        result.set(index, new CallStatEvent(kept.getTimestamp(),
                                event.getNumber(), event.getOutcome(), event.getReason(),
                                event.getSourceId()));
                    }
                    continue;
                }
            }
            lastIndex.put(event.getNumber(), result.size());
            result.add(event);
        }
        return result;
    }

    private static CallStatEvent parse(String[] f) {
        if (f.length < 5) return null;
        long timestamp;
        try {
            timestamp = Long.parseLong(f[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        CallStatEvent.Outcome outcome = CallStatEvent.Outcome.fromName(f[3].trim());
        if (outcome == null) return null;
        CallStatEvent.Reason reason = CallStatEvent.Reason.fromName(f[4].trim());
        String sourceId = f.length > 5 ? f[5].trim() : null;
        return new CallStatEvent(timestamp, f[2].trim(), outcome, reason, sourceId);
    }

    static String format(CallStatEvent e) {
        return TYPE_CALL + ',' + e.getTimestamp() + ',' + clean(e.getNumber()) + ','
                + e.getOutcome().name() + ',' + e.getReason().name() + ','
                + clean(e.getSourceId()) + '\n';
    }

    private static String header() {
        return TYPE_FORMAT + ',' + FORMAT_NAME + ',' + FORMAT_VERSION + '\n';
    }

    /** Removes characters that would break the line format. */
    private static String clean(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '\n' || c == '\r' || c == '"') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private void append(CallStatEvent event) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Can't create directory " + dir);
        }

        StringBuilder sb = new StringBuilder();
        long length = file.length();
        if (length == 0) {
            sb.append(header());
        } else if (!endsWithNewline()) {
            sb.append('\n'); // the previous line was cut off
        }
        sb.append(format(event));

        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean endsWithNewline() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            if (length == 0) return true;
            raf.seek(length - 1);
            return raf.read() == '\n';
        }
    }

    /** @return the timestamp of the first event, -1 if there is none or it can't be read */
    private long readFirstTimestamp() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split(",", -1);
                if (TYPE_CALL.equals(f[0]) && f.length > 1) {
                    try {
                        return Long.parseLong(f[1].trim());
                    } catch (NumberFormatException e) {
                        // skip a broken line
                    }
                }
            }
        } catch (IOException e) {
            LOG.debug("readFirstTimestamp() failed", e);
        }
        return -1;
    }

    private void writeAtomically(List<String> lines) throws IOException {
        File tmp = new File(file.getPath() + TMP_SUFFIX);
        boolean ok = false;
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                Writer writer = new BufferedWriter(
                        new OutputStreamWriter(out, StandardCharsets.UTF_8));
                writer.write(header());
                for (String line : lines) writer.write(line);
                writer.flush();
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

}
