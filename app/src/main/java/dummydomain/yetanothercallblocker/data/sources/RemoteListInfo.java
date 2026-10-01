package dummydomain.yetanothercallblocker.data.sources;

import java.util.Objects;

/**
 * Download state of a list that was imported from a URL: where it comes from,
 * whether it is updated automatically, the HTTP validators of the last download
 * and the outcome of the last update attempt.
 *
 * <p>Stored as optional {@code meta} records of the list file (see
 * {@link NumberListStore}); lists imported from a file have no remote info.</p>
 *
 * <p>Immutable; the {@code with...} methods return modified copies.</p>
 */
public final class RemoteListInfo {

    private final String url;
    private final boolean autoUpdate;
    private final String etag;
    private final String lastModified;
    private final long lastCheckAt;
    private final String lastError;

    public RemoteListInfo(String url, boolean autoUpdate) {
        this(url, autoUpdate, null, null, 0, null);
    }

    public RemoteListInfo(String url, boolean autoUpdate, String etag, String lastModified,
                          long lastCheckAt, String lastError) {
        this.url = Objects.requireNonNull(url, "url");
        this.autoUpdate = autoUpdate;
        this.etag = emptyToNull(etag);
        this.lastModified = emptyToNull(lastModified);
        this.lastCheckAt = lastCheckAt;
        this.lastError = emptyToNull(lastError);
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

    public RemoteListInfo withAutoUpdate(boolean autoUpdate) {
        return new RemoteListInfo(url, autoUpdate, etag, lastModified, lastCheckAt, lastError);
    }

    /** A successful check: new validators, no error. */
    public RemoteListInfo withSuccess(String etag, String lastModified, long checkedAt) {
        return new RemoteListInfo(url, autoUpdate, etag, lastModified, checkedAt, null);
    }

    /** A failed check: the validators are kept, so the old list stays "current". */
    public RemoteListInfo withError(String error, long checkedAt) {
        return new RemoteListInfo(url, autoUpdate, etag, lastModified, checkedAt,
                error != null ? error : "error");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RemoteListInfo)) return false;
        RemoteListInfo that = (RemoteListInfo) o;
        return autoUpdate == that.autoUpdate
                && lastCheckAt == that.lastCheckAt
                && url.equals(that.url)
                && Objects.equals(etag, that.etag)
                && Objects.equals(lastModified, that.lastModified)
                && Objects.equals(lastError, that.lastError);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, autoUpdate, etag, lastModified, lastCheckAt, lastError);
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
                '}';
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

}
