package dummydomain.yetanothercallblocker.data.sources;

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
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persistent state of the reports of the user's marks to PhoneBlock: for every number
 * what the user wants PhoneBlock to know ("wanted") and what PhoneBlock was told
 * successfully ("sent"). A number is pending while the two differ. Only the latest
 * wish per number is kept, so marking and unmarking a number before the report was
 * sent sends nothing at all.
 *
 * <p>The file is a small UTF-8 text file with tab-separated lines:
 * a header {@code #pbreport\t1\t<sentCount>} followed by
 * {@code number, wanted, wantedRating, sent, sentRating, updated, sentAt, attempts,
 * rejected, lastError}. Plain Java, thread-safe.</p>
 */
public class PhoneBlockReportQueue {

    /** What PhoneBlock should know about a number. */
    public enum Verdict {
        /** Nothing: the number is not on the user's PhoneBlock lists. */
        NONE,
        /** A spam rating (the number is on the user's PhoneBlock blacklist). */
        SPAM,
        /** A legitimate rating (the number is on the user's PhoneBlock whitelist). */
        LEGITIMATE
    }

    /** The report status of a number for the UI. */
    public enum Status {
        /** Nothing to report and nothing reported. */
        NONE,
        /** Waiting to be sent (e.g. no network yet). */
        PENDING,
        /** PhoneBlock knows the current verdict. */
        SENT,
        /** The server rejected the report (e.g. invalid number); not retried. */
        REJECTED
    }

    /** The state of one number. Immutable. */
    public static final class Entry {

        private final String number;
        private final Verdict wanted;
        private final PhoneBlockClient.Rating wantedRating;
        private final Verdict sent;
        private final PhoneBlockClient.Rating sentRating;
        private final long updated;
        private final long sentAt;
        private final int attempts;
        private final boolean rejected;
        private final String lastError;

        Entry(String number, Verdict wanted, PhoneBlockClient.Rating wantedRating,
              Verdict sent, PhoneBlockClient.Rating sentRating, long updated, long sentAt,
              int attempts, boolean rejected, String lastError) {
            this.number = Objects.requireNonNull(number);
            this.wanted = Objects.requireNonNull(wanted);
            this.wantedRating = wanted == Verdict.SPAM ? wantedRating : null;
            this.sent = Objects.requireNonNull(sent);
            this.sentRating = sent == Verdict.SPAM ? sentRating : null;
            this.updated = updated;
            this.sentAt = sentAt;
            this.attempts = attempts;
            this.rejected = rejected;
            this.lastError = lastError;
        }

        /** The number in E.164 form. */
        public String getNumber() {
            return number;
        }

        public Verdict getWanted() {
            return wanted;
        }

        /** The rating to send for {@link Verdict#SPAM}, null otherwise. */
        public PhoneBlockClient.Rating getWantedRating() {
            return wantedRating;
        }

        public Verdict getSent() {
            return sent;
        }

        public PhoneBlockClient.Rating getSentRating() {
            return sentRating;
        }

        /** When the wish was last changed, millis since the epoch. */
        public long getUpdated() {
            return updated;
        }

        /** When the last report was sent successfully, 0 if never. */
        public long getSentAt() {
            return sentAt;
        }

        /** Failed attempts since the last change. */
        public int getAttempts() {
            return attempts;
        }

        public boolean isRejected() {
            return rejected;
        }

        public String getLastError() {
            return lastError;
        }

        /** @return whether something has to be sent for this number */
        public boolean isPending() {
            if (rejected) return false;
            if (wanted != sent) return true;
            return wanted == Verdict.SPAM && wantedRating != sentRating;
        }

        public Status getStatus() {
            if (rejected) return Status.REJECTED;
            if (isPending()) return Status.PENDING;
            return sent != Verdict.NONE ? Status.SENT : Status.NONE;
        }

        @Override
        public String toString() {
            return "Entry{" + number + ", wanted=" + wanted
                    + (wantedRating != null ? "/" + wantedRating : "")
                    + ", sent=" + sent + (sentRating != null ? "/" + sentRating : "")
                    + ", attempts=" + attempts + (rejected ? ", rejected" : "")
                    + (lastError != null ? ", lastError='" + lastError + '\'' : "") + '}';
        }
    }

    static final String HEADER = "#pbreport";
    static final int FORMAT_VERSION = 1;
    static final int MAX_ERROR_LENGTH = 200;

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockReportQueue.class);

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private int sentCount;
    private boolean loaded;

    public PhoneBlockReportQueue(File file) {
        this.file = Objects.requireNonNull(file);
    }

    /**
     * Records what PhoneBlock should know about the number. Resets the failure state.
     *
     * @param e164    the number in E.164 form
     * @param verdict the wish; {@link Verdict#NONE} withdraws an earlier report
     * @param rating  the rating for {@link Verdict#SPAM} (null: {@code B_MISSED})
     * @return the new entry, null if the number is not in E.164 form
     */
    public synchronized Entry request(String e164, Verdict verdict,
                                      PhoneBlockClient.Rating rating, long now)
            throws IOException {
        String number = PhoneBlockClient.normalizePhone(e164);
        if (number == null) return null;
        Objects.requireNonNull(verdict, "verdict");
        if (verdict == Verdict.SPAM) {
            if (rating == null || rating == PhoneBlockClient.Rating.A_LEGITIMATE) {
                rating = PhoneBlockClient.Rating.B_MISSED;
            }
        } else {
            rating = null;
        }

        ensureLoaded();
        Entry old = entries.get(number);
        Entry entry = new Entry(number, verdict, rating,
                old != null ? old.sent : Verdict.NONE, old != null ? old.sentRating : null,
                now, old != null ? old.sentAt : 0, 0, false, null);
        put(entry);
        save();
        LOG.debug("request() {}", entry);
        return entry;
    }

    /**
     * Forgets an unsent wish for the number: the number stays as PhoneBlock knows it.
     */
    public synchronized void cancel(String e164) throws IOException {
        String number = PhoneBlockClient.normalizePhone(e164);
        if (number == null) return;
        ensureLoaded();
        Entry old = entries.get(number);
        if (old == null || !old.isPending() && !old.rejected) return;
        put(new Entry(number, old.sent, old.sentRating, old.sent, old.sentRating,
                old.updated, old.sentAt, 0, false, null));
        save();
    }

    /** Forgets all unsent wishes (e.g. when reporting is switched off). */
    public synchronized void cancelAll() throws IOException {
        ensureLoaded();
        boolean changed = false;
        for (Entry old : new ArrayList<>(entries.values())) {
            if (old.isPending() || old.rejected) {
                put(new Entry(old.number, old.sent, old.sentRating, old.sent, old.sentRating,
                        old.updated, old.sentAt, 0, false, null));
                changed = true;
            }
        }
        if (changed) save();
    }

    /** @return the entry of the number, null if none */
    public synchronized Entry get(String e164) {
        String number = PhoneBlockClient.normalizePhone(e164);
        if (number == null) return null;
        ensureLoaded();
        return entries.get(number);
    }

    /** @return the report status of the number */
    public Status getStatus(String e164) {
        Entry entry = get(e164);
        return entry != null ? entry.getStatus() : Status.NONE;
    }

    /** @return the entries that have to be sent, oldest change first */
    public synchronized List<Entry> getPending() {
        ensureLoaded();
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.isPending()) result.add(entry);
        }
        result.sort((a, b) -> Long.compare(a.updated, b.updated));
        return result;
    }

    public synchronized int getPendingCount() {
        return getPending().size();
    }

    /** @return the number of ratings sent successfully so far */
    public synchronized int getSentCount() {
        ensureLoaded();
        return sentCount;
    }

    /**
     * Records that PhoneBlock now knows the verdict. The wish may have changed
     * meanwhile; then the number stays pending.
     *
     * @param countAsReport true for a rating (counted), false for a removal
     */
    public synchronized void markSent(String number, Verdict verdict,
                                      PhoneBlockClient.Rating rating, long now,
                                      boolean countAsReport) throws IOException {
        ensureLoaded();
        Entry old = entries.get(number);
        if (old == null) {
            old = new Entry(number, verdict, rating, Verdict.NONE, null, now, 0, 0, false, null);
        }
        boolean sameWish = old.wanted == verdict && old.wantedRating == rating;
        put(new Entry(number, old.wanted, old.wantedRating, verdict, rating, old.updated, now,
                sameWish ? 0 : old.attempts, false, sameWish ? null : old.lastError));
        if (countAsReport) sentCount++;
        save();
    }

    /**
     * Records a failed attempt.
     *
     * @param permanent true if retrying is useless (the server rejected the request);
     *                  the number is not retried until the user changes the mark
     */
    public synchronized void markFailed(String number, String error, boolean permanent)
            throws IOException {
        ensureLoaded();
        Entry old = entries.get(number);
        if (old == null) return;
        put(new Entry(number, old.wanted, old.wantedRating, old.sent, old.sentRating,
                old.updated, old.sentAt, old.attempts + 1, permanent, sanitize(error)));
        save();
    }

    private void put(Entry entry) {
        if (entry.wanted == Verdict.NONE && entry.sent == Verdict.NONE && !entry.rejected) {
            // nothing to remember
            entries.remove(entry.number);
        } else {
            entries.put(entry.number, entry);
        }
    }

    // persistence

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        entries.clear();
        sentCount = 0;
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t", -1);
                if (HEADER.equals(fields[0])) {
                    if (fields.length > 2) sentCount = parseInt(fields[2]);
                    continue;
                }
                Entry entry = parseEntry(fields);
                if (entry != null) {
                    put(entry);
                } else {
                    LOG.warn("ensureLoaded() skipped a malformed line");
                }
            }
        } catch (IOException e) {
            LOG.error("ensureLoaded() failed to read {}", file, e);
        }
    }

    private static Entry parseEntry(String[] f) {
        if (f.length < 10) return null;
        String number = PhoneBlockClient.normalizePhone(f[0]);
        Verdict wanted = parseVerdict(f[1]);
        Verdict sent = parseVerdict(f[3]);
        if (number == null || wanted == null || sent == null) return null;
        return new Entry(number, wanted, PhoneBlockClient.Rating.fromCode(emptyToNull(f[2])),
                sent, PhoneBlockClient.Rating.fromCode(emptyToNull(f[4])),
                parseLong(f[5]), parseLong(f[6]), parseInt(f[7]), "1".equals(f[8]),
                emptyToNull(f[9]));
    }

    private void save() throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Can't create " + dir);
        }

        File tmp = new File(file.getPath() + ".tmp");
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            writer.write(HEADER + "\t" + FORMAT_VERSION + "\t" + sentCount + "\n");
            for (Entry e : entries.values()) {
                writer.write(e.number + "\t" + e.wanted + "\t" + name(e.wantedRating)
                        + "\t" + e.sent + "\t" + name(e.sentRating) + "\t" + e.updated
                        + "\t" + e.sentAt + "\t" + e.attempts + "\t" + (e.rejected ? "1" : "0")
                        + "\t" + (e.lastError != null ? e.lastError : "") + "\n");
            }
        }
        if (!tmp.renameTo(file)) {
            // some file systems don't replace on rename
            if (!file.delete() || !tmp.renameTo(file)) {
                throw new IOException("Can't replace " + file);
            }
        }
    }

    /** For tests: the entries in file order. */
    synchronized List<Entry> getAll() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    private static String name(PhoneBlockClient.Rating rating) {
        return rating != null ? rating.name() : "";
    }

    private static Verdict parseVerdict(String s) {
        try {
            return Verdict.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseInt(String s) {
        try {
            return Math.max(0, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String sanitize(String s) {
        if (s == null) return null;
        String result = s.replaceAll("\\s+", " ").trim();
        if (result.length() > MAX_ERROR_LENGTH) result = result.substring(0, MAX_ERROR_LENGTH);
        return result.isEmpty() ? null : result;
    }

}
