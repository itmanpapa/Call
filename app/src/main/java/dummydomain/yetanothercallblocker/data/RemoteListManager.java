package dummydomain.yetanothercallblocker.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

import dummydomain.yetanothercallblocker.data.sources.NumberListFormatDetector;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;
import dummydomain.yetanothercallblocker.data.sources.ParseResult;
import dummydomain.yetanothercallblocker.data.sources.RemoteListDownloader;
import dummydomain.yetanothercallblocker.data.sources.RemoteListInfo;

/**
 * Number lists imported from a URL (community block lists on GitHub and the like):
 * adding a list, updating it on request and the periodic update of the lists with
 * automatic updates enabled.
 *
 * <p>The list content is downloaded with a {@link RemoteListDownloader} (conditional
 * requests with the stored {@code ETag} / {@code Last-Modified}), its format is detected
 * with {@link NumberListFormatDetector}, and the list is stored through
 * {@link SourcesManager} together with its {@link RemoteListInfo}. A failed update keeps
 * the old entries and records the error, so that the sources screen can show it.</p>
 *
 * <p>Plain Java, no Android dependencies. Downloads block, so the methods must be
 * called from a background thread; they are serialized by an internal lock.</p>
 */
public class RemoteListManager {

    /** Prefix of the source ids of lists imported from a URL. */
    public static final String SOURCE_ID_PREFIX = "url_";

    /** Outcome of adding or updating a list. Immutable. */
    public static final class UpdateResult {

        public enum Status {
            /** New content was downloaded and stored. */
            UPDATED,
            /** The server reported no changes (HTTP 304). */
            NOT_MODIFIED,
            /** The update failed, the old list (if any) is kept. */
            FAILED
        }

        private final String sourceId;
        private final Status status;
        private final ParseResult parseResult;
        private final String error;

        UpdateResult(String sourceId, Status status, ParseResult parseResult, String error) {
            this.sourceId = sourceId;
            this.status = status;
            this.parseResult = parseResult;
            this.error = error;
        }

        public String getSourceId() {
            return sourceId;
        }

        public Status getStatus() {
            return status;
        }

        /** Parse result of the downloaded content, null if nothing was parsed. */
        public ParseResult getParseResult() {
            return parseResult;
        }

        /** Error message for {@link Status#FAILED}, otherwise null. */
        public String getError() {
            return error;
        }

        @Override
        public String toString() {
            return "UpdateResult{sourceId='" + sourceId + "', status=" + status
                    + ", parseResult=" + parseResult + ", error='" + error + "'}";
        }
    }

    /** The downloaded content contains no phone numbers. */
    public static class NoNumbersException extends IOException {

        private static final long serialVersionUID = 1L;
        public NoNumbersException() {
            super("No phone numbers found");
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RemoteListManager.class);

    private final SourcesManager sourcesManager;
    private final RemoteListDownloader downloader;
    private final LongSupplier clock;

    private final Object lock = new Object();

    public RemoteListManager(SourcesManager sourcesManager, RemoteListDownloader downloader) {
        this(sourcesManager, downloader, System::currentTimeMillis);
    }

    public RemoteListManager(SourcesManager sourcesManager, RemoteListDownloader downloader,
                             LongSupplier clock) {
        this.sourcesManager = Objects.requireNonNull(sourcesManager, "sourcesManager");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Downloads a list and adds it as a new source (or replaces the list previously
     * added from the same URL). Nothing is stored if the download or parsing fails.
     *
     * @param url         list URL; see {@link #normalizeUrl(String)}
     * @param displayName list name, or null to derive it from the URL
     * @param autoUpdate  whether the list is updated periodically
     * @return the result with status {@link UpdateResult.Status#UPDATED}
     * @throws IllegalArgumentException for an invalid URL
     * @throws IOException              if the download fails or the content has no numbers
     */
    public UpdateResult addList(String url, String displayName,
                                boolean autoUpdate) throws IOException {
        String normalizedUrl = normalizeUrl(url);
        String sourceId = sourceIdForUrl(normalizedUrl);
        String name = displayName != null && !displayName.trim().isEmpty()
                ? displayName.trim() : displayNameFromUrl(normalizedUrl);

        synchronized (lock) {
            LOG.info("addList() {} as {}", normalizedUrl, sourceId);

            RemoteListDownloader.Response response = downloader.download(normalizedUrl, null, null);
            if (response.isNotModified()) {
                throw new IOException("Unexpected HTTP 304 response");
            }

            ParseResult result = parse(response.getBody());
            long now = clock.getAsLong();
            RemoteListInfo remote = new RemoteListInfo(normalizedUrl, autoUpdate)
                    .withSuccess(response.getEtag(), response.getLastModified(), now);
            sourcesManager.importList(sourceId, name, result.getEntries(), now, remote);

            return new UpdateResult(sourceId, UpdateResult.Status.UPDATED, result, null);
        }
    }

    /**
     * Updates a list imported from a URL. Errors are not thrown but recorded in the
     * list's {@link RemoteListInfo} and returned.
     *
     * @return the result, or null if there is no such URL list
     */
    public UpdateResult update(String sourceId) {
        synchronized (lock) {
            SourcesManager.SourceInfo source = sourcesManager.getSource(sourceId);
            RemoteListInfo remote = getRemote(source);
            if (remote == null) return null;

            // a list that couldn't be loaded is downloaded in full
            boolean conditional = !source.isLoadFailed();
            long now;

            try {
                RemoteListDownloader.Response response = downloader.download(remote.getUrl(),
                        conditional ? remote.getEtag() : null,
                        conditional ? remote.getLastModified() : null);
                now = clock.getAsLong();

                if (response.isNotModified()) {
                    LOG.info("update() {}: not modified", sourceId);
                    sourcesManager.updateRemoteInfo(sourceId, remote.withSuccess(
                            response.getEtag(), response.getLastModified(), now));
                    return new UpdateResult(sourceId, UpdateResult.Status.NOT_MODIFIED,
                            null, null);
                }

                ParseResult result = parse(response.getBody());

                if (sourcesManager.getSource(sourceId) == null) {
                    // deleted while downloading
                    LOG.info("update() {}: deleted meanwhile", sourceId);
                    return null;
                }

                sourcesManager.importList(sourceId, source.getDisplayName(), result.getEntries(),
                        now, remote.withSuccess(response.getEtag(), response.getLastModified(), now));
                LOG.info("update() {}: {} entries", sourceId, result.getEntries().size());
                return new UpdateResult(sourceId, UpdateResult.Status.UPDATED, result, null);
            } catch (Exception e) {
                LOG.warn("update() {} failed", sourceId, e);
                String error = errorMessage(e);
                try {
                    sourcesManager.updateRemoteInfo(sourceId,
                            remote.withError(error, clock.getAsLong()));
                } catch (Exception e2) {
                    LOG.warn("update() failed to record the error of {}", sourceId, e2);
                }
                return new UpdateResult(sourceId, UpdateResult.Status.FAILED, null, error);
            }
        }
    }

    /**
     * Updates all URL lists with automatic updates enabled (the periodic job).
     *
     * @return results in source order
     */
    public List<UpdateResult> updateAutoUpdateLists() {
        List<UpdateResult> results = new ArrayList<>();
        for (SourcesManager.SourceInfo source : sourcesManager.getSources()) {
            RemoteListInfo remote = getRemote(source);
            if (remote == null || !remote.isAutoUpdate()) continue;

            UpdateResult result = update(source.getId());
            if (result != null) results.add(result);
        }
        return results;
    }

    /**
     * @return whether at least one URL list has automatic updates enabled
     */
    public boolean hasAutoUpdateLists() {
        for (SourcesManager.SourceInfo source : sourcesManager.getSources()) {
            RemoteListInfo remote = getRemote(source);
            if (remote != null && remote.isAutoUpdate()) return true;
        }
        return false;
    }

    /**
     * Turns automatic updates of a URL list on or off.
     *
     * @return false if there is no such URL list
     */
    public boolean setAutoUpdate(String sourceId, boolean autoUpdate) throws IOException {
        synchronized (lock) {
            RemoteListInfo remote = getRemote(sourcesManager.getSource(sourceId));
            if (remote == null) return false;
            if (remote.isAutoUpdate() == autoUpdate) return true;
            return sourcesManager.updateRemoteInfo(sourceId,
                    remote.withAutoUpdate(autoUpdate)) != null;
        }
    }

    /**
     * @return the remote info of the source, or null if it is not a URL list added by
     * the user (the automatically updated Bundesnetzagentur list also has remote info,
     * but is managed by {@link dummydomain.yetanothercallblocker.data.sources.BnetzaAutoUpdater})
     */
    public static RemoteListInfo getRemote(SourcesManager.SourceInfo source) {
        if (source == null || !source.getId().startsWith(SOURCE_ID_PREFIX)) return null;
        NumberListStore.ListMetadata metadata = source.getMetadata();
        return metadata != null ? metadata.getRemote() : null;
    }

    static ParseResult parse(byte[] content) throws IOException {
        if (content == null || content.length == 0) throw new NoNumbersException();
        ParseResult result = NumberListFormatDetector.parse(content);
        // never replace a list with an empty one (e.g. an error page served with HTTP 200)
        if (result.getEntries().isEmpty()) throw new NoNumbersException();
        return result;
    }

    static String errorMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        if (e instanceof java.net.UnknownHostException) return "Unknown host: " + message;
        return message;
    }

    // URLs

    /**
     * Normalizes a user-entered list URL: trims it, adds "https://" if the scheme is
     * missing, and turns a GitHub file page ({@code github.com/user/repo/blob/branch/file})
     * into the raw file URL ({@code raw.githubusercontent.com/user/repo/branch/file}).
     *
     * @throws IllegalArgumentException if it is not an http(s) URL with a host
     */
    public static String normalizeUrl(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty URL");
        }
        String s = input.trim();
        if (!s.contains("://")) s = "https://" + s;

        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + input, e);
        }

        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("Only http and https URLs are supported: " + input);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Invalid URL: " + input);
        }
        host = host.toLowerCase(Locale.ROOT);

        if ((host.equals("github.com") || host.equals("www.github.com"))
                && uri.getRawPath() != null && uri.getRawQuery() == null) {
            // /user/repo/blob/branch/path or /user/repo/raw/branch/path
            String[] parts = uri.getRawPath().split("/", 6);
            if (parts.length == 6 && parts[0].isEmpty()
                    && (parts[3].equals("blob") || parts[3].equals("raw"))) {
                return "https://raw.githubusercontent.com/" + parts[1] + "/" + parts[2]
                        + "/" + parts[4] + "/" + parts[5];
            }
        }

        return s;
    }

    /** Stable source id for a normalized URL, so that re-adding a URL replaces its list. */
    public static String sourceIdForUrl(String normalizedUrl) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedUrl.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(SOURCE_ID_PREFIX);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always available
        }
    }

    /** "https://host/dir/My%20List.xml" → "My List". */
    public static String displayNameFromUrl(String url) {
        String path;
        String host;
        try {
            URI uri = new URI(url);
            path = uri.getRawPath();
            host = uri.getHost();
        } catch (URISyntaxException e) {
            return url;
        }

        String name = null;
        if (path != null) {
            int slash = path.lastIndexOf('/');
            name = path.substring(slash + 1);
            try {
                name = URLDecoder.decode(name.replace("+", "%2B"), "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                // keep the raw name
            }
            String lower = name.toLowerCase(Locale.ROOT);
            for (String ext : new String[]{".csv", ".txt", ".tsv", ".xml", ".vcf", ".vcard"}) {
                if (lower.endsWith(ext) && name.length() > ext.length()) {
                    name = name.substring(0, name.length() - ext.length());
                    break;
                }
            }
            name = name.trim();
        }
        if (name == null || name.isEmpty()) name = host;
        return name != null ? name : url;
    }

}
