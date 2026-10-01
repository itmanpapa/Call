package dummydomain.yetanothercallblocker.data.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Download state of a list that was imported from a URL: where it comes from,
 * whether it is updated automatically, the HTTP validators of the last download
 * and the outcome of the last update attempt.
 *
 * <p>A list assembled from several downloads (the Bundesnetzagentur list: the page
 * with the current measures plus yearly lists) additionally keeps the validators of
 * every downloaded URL as {@link Part}s; {@link #getUrl()} is then the main URL.</p>
 *
 * <p>Stored as optional {@code meta} records of the list file (see
 * {@link NumberListStore}); lists imported from a file have no remote info.</p>
 *
 * <p>Immutable; the {@code with...} methods return modified copies.</p>
 */
public final class RemoteListInfo {

    /** Validators of one downloaded URL of a list assembled from several downloads. */
    public static final class Part {

        private final String url;
        private final String etag;
        private final String lastModified;

        public Part(String url, String etag, String lastModified) {
            this.url = Objects.requireNonNull(url, "url");
            this.etag = emptyToNull(etag);
            this.lastModified = emptyToNull(lastModified);
        }

        public String getUrl() {
            return url;
        }

        public String getEtag() {
            return etag;
        }

        public String getLastModified() {
            return lastModified;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Part)) return false;
            Part that = (Part) o;
            return url.equals(that.url)
                    && Objects.equals(etag, that.etag)
                    && Objects.equals(lastModified, that.lastModified);
        }

        @Override
        public int hashCode() {
            return Objects.hash(url, etag, lastModified);
        }

        @Override
        public String toString() {
            return "Part{url='" + url + "', etag='" + etag
                    + "', lastModified='" + lastModified + "'}";
        }
    }

    private final String url;
    private final boolean autoUpdate;
    private final String etag;
    private final String lastModified;
    private final long lastCheckAt;
    private final String lastError;
    private final long lastSuccessAt;
    private final List<Part> parts;

    public RemoteListInfo(String url, boolean autoUpdate) {
        this(url, autoUpdate, null, null, 0, null);
    }

    public RemoteListInfo(String url, boolean autoUpdate, String etag, String lastModified,
                          long lastCheckAt, String lastError) {
        this(url, autoUpdate, etag, lastModified, lastCheckAt, lastError,
                lastError == null ? lastCheckAt : 0, null);
    }

    /**
     * @param lastSuccessAt time of the last successful check (new content or "not
     *                      modified"), 0 if never
     * @param parts         validators of the individual downloads, or null
     */
    public RemoteListInfo(String url, boolean autoUpdate, String etag, String lastModified,
                          long lastCheckAt, String lastError, long lastSuccessAt,
                          List<Part> parts) {
        this.url = Objects.requireNonNull(url, "url");
        this.autoUpdate = autoUpdate;
        this.etag = emptyToNull(etag);
        this.lastModified = emptyToNull(lastModified);
        this.lastCheckAt = lastCheckAt;
        this.lastError = emptyToNull(lastError);
        this.lastSuccessAt = lastSuccessAt;
        this.parts = parts == null || parts.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(parts));
    }

    /** Download URL (http or https). */
    public String getUrl() {
        return url;
    }

    /** Whether the list is refreshed by the periodic background job. */
    public boolean isAutoUpdate() {
        return autoUpdate;
    }

    /** {@code ETag} header of the last successful download, or null. */
    public String getEtag() {
        return etag;
    }

    /** {@code Last-Modified} header of the last successful download, or null. */
    public String getLastModified() {
        return lastModified;
    }

    /** Time of the last update attempt (successful or not), millis since the epoch, 0 if never. */
    public long getLastCheckAt() {
        return lastCheckAt;
    }

    /** Error message of the last update attempt, or null if it succeeded. */
    public String getLastError() {
        return lastError;
    }

    /**
     * Time of the last successful update attempt (new content or "not modified"),
     * millis since the epoch, 0 if unknown.
     */
    public long getLastSuccessAt() {
        return lastSuccessAt;
    }

    /** Validators of the individual downloads (empty for single-URL lists). */
    public List<Part> getParts() {
        return parts;
    }

    /** @return the part with the given URL, or null */
    public Part getPart(String partUrl) {
        for (Part part : parts) {
            if (part.url.equals(partUrl)) return part;
        }
        return null;
    }

    public RemoteListInfo withAutoUpdate(boolean autoUpdate) {
        return new RemoteListInfo(url, autoUpdate, etag, lastModified, lastCheckAt, lastError,
                lastSuccessAt, parts);
    }

    /** A successful check: new validators, no error. */
    public RemoteListInfo withSuccess(String etag, String lastModified, long checkedAt) {
        return new RemoteListInfo(url, autoUpdate, etag, lastModified, checkedAt, null,
                checkedAt, parts);
    }

    /** A successful check of a list assembled from several downloads. */
    public RemoteListInfo withSuccess(String etag, String lastModified, List<Part> parts,
                                      long checkedAt) {
        return new RemoteListInfo(url, autoUpdate, etag, lastModified, checkedAt, null,
                checkedAt, parts);
    }

    /** A failed check: the validators are kept, so the old list stays "current". */
    public RemoteListInfo withError(String error, long checkedAt) {
        return new RemoteListInfo(url, autoUpdate, etag, lastModified, checkedAt,
                error != null ? error : "error", lastSuccessAt, parts);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RemoteListInfo)) return false;
        RemoteListInfo that = (RemoteListInfo) o;
        return autoUpdate == that.autoUpdate
                && lastCheckAt == that.lastCheckAt
                && lastSuccessAt == that.lastSuccessAt
                && url.equals(that.url)
                && Objects.equals(etag, that.etag)
                && Objects.equals(lastModified, that.lastModified)
                && Objects.equals(lastError, that.lastError)
                && parts.equals(that.parts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, autoUpdate, etag, lastModified, lastCheckAt, lastError,
                lastSuccessAt, parts);
    }

    @Override
    public String toString() {
        return "RemoteListInfo{" +
                "url='" + url + '\'' +
                ", autoUpdate=" + autoUpdate +
                ", etag='" + etag + '\'' +
                ", lastModified='" + lastModified + '\'' +
                ", lastCheckAt=" + lastCheckAt +
                ", lastError='" + lastError + '\'' +
                ", lastSuccessAt=" + lastSuccessAt +
                (parts.isEmpty() ? "" : ", parts=" + parts) +
                '}';
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

}
