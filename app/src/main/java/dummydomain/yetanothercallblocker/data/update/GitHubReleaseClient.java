package dummydomain.yetanothercallblocker.data.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient.HttpResponse;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient.HttpTransport;
import dummydomain.yetanothercallblocker.data.sources.SimpleJsonReader;

/**
 * Reads the latest release of the app from the GitHub REST API
 * ({@code GET /repos/{owner}/{repo}/releases/latest}, which returns the most recent
 * published release that is neither a draft nor a pre-release).
 *
 * <p>Each release has the asset {@code callguard-vX.Y.Z.apk}; if the name differs,
 * the first {@code .apk} asset is used. The APK of the F-Droid flavor (without the
 * self-updater, {@code callguard_fdroid-vX.Y.Z.apk}) is never used: installing it
 * would end the updates. Unauthenticated requests are limited to
 * 60 per hour per IP address, which is plenty for a daily check.</p>
 *
 * <p>Plain Java; the transport is pluggable for tests. Thread-safe if the transport is.</p>
 */
public class GitHubReleaseClient {

    public static final String DEFAULT_OWNER = "itmanpapa";
    public static final String DEFAULT_REPO = "Call";

    public static final String API_BASE_URL = "https://api.github.com";

    /** The releases page in the browser (fallback when nothing better is known). */
    public static final String RELEASES_PAGE_URL
            = "https://github.com/" + DEFAULT_OWNER + "/" + DEFAULT_REPO + "/releases";

    /** Prefix and suffix of the APK asset name. */
    static final String APK_PREFIX = "callguard-";
    static final String APK_SUFFIX = ".apk";
    /** Part of the name of the F-Droid flavor's APK asset, which is skipped. */
    static final String FDROID_MARKER = "fdroid";

    private static final int MAX_ERROR_BODY = 200;

    private static final Logger LOG = LoggerFactory.getLogger(GitHubReleaseClient.class);

    /** An error answer of the API. */
    public static class ApiException extends IOException {

        private static final long serialVersionUID = 1L;

        private final int httpCode;

        public ApiException(int httpCode, String message) {
            super(message);
            this.httpCode = httpCode;
        }

        public int getHttpCode() {
            return httpCode;
        }

        /** @return true if GitHub refused the request because of its rate limit */
        public boolean isRateLimited() {
            return httpCode == 403 || httpCode == 429;
        }
    }

    private final HttpTransport transport;
    private final String latestReleaseUrl;
    private final String userAgent;

    /**
     * @param transport HTTP transport
     * @param userAgent User-Agent header (required by GitHub), e.g. "CallGuard/0.11.0"
     */
    public GitHubReleaseClient(HttpTransport transport, String userAgent) {
        this(transport, API_BASE_URL, DEFAULT_OWNER, DEFAULT_REPO, userAgent);
    }

    public GitHubReleaseClient(HttpTransport transport, String apiBaseUrl,
                               String owner, String repo, String userAgent) {
        this.transport = Objects.requireNonNull(transport, "transport");
        String base = Objects.requireNonNull(apiBaseUrl, "apiBaseUrl");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.latestReleaseUrl = base + "/repos/" + owner + "/" + repo + "/releases/latest";
        this.userAgent = userAgent != null ? userAgent : "CallGuard";
    }

    public String getLatestReleaseUrl() {
        return latestReleaseUrl;
    }

    /**
     * @return the latest release, or null if the repository has no published release yet
     * @throws ApiException on HTTP errors (e.g. the rate limit)
     * @throws IOException  on network errors or an invalid answer
     */
    public ReleaseInfo fetchLatestRelease() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", userAgent);

        LOG.debug("fetchLatestRelease() {}", latestReleaseUrl);
        try (HttpResponse response = transport.get(latestReleaseUrl, headers)) {
            int code = response.getCode();
            if (code == 404) {
                LOG.info("fetchLatestRelease() no release published");
                return null;
            }
            if (code < 200 || code >= 300) {
                String message = "HTTP " + code;
                String body = readErrorBody(response.getBody());
                if (!body.isEmpty()) message += ": " + body;
                throw new ApiException(code, message);
            }
            if (response.getBody() == null) throw new IOException("Empty response");

            ReleaseInfo release = parseRelease(response.getBody());
            LOG.debug("fetchLatestRelease() {}", release);
            return release;
        }
    }

    /**
     * Parses one release object of the GitHub API.
     *
     * @throws IOException if the JSON is invalid or has no tag
     */
    @SuppressWarnings("unchecked")
    public static ReleaseInfo parseRelease(Reader json) throws IOException {
        Object value;
        try (SimpleJsonReader reader = new SimpleJsonReader(json)) {
            value = reader.readValue();
        }
        if (!(value instanceof Map)) throw new IOException("Not a release object");
        Map<String, Object> release = (Map<String, Object>) value;

        String tag = SimpleJsonReader.asString(release.get("tag_name"));
        if (tag == null || tag.trim().isEmpty()) throw new IOException("Release without a tag");
        tag = tag.trim();

        AppVersion version = AppVersion.parse(tag);
        if (version == null) {
            // the release title may contain the version, e.g. "CallGuard 0.12.0"
            String name = SimpleJsonReader.asString(release.get("name"));
            version = name != null ? AppVersion.parse(lastWord(name)) : null;
        }
        if (version == null) throw new IOException("Unrecognized release tag: " + tag);

        String apkName = null;
        String apkUrl = null;
        long apkSize = -1;
        Object assetsValue = release.get("assets");
        if (assetsValue instanceof List) {
            Map<String, Object> apk = findApkAsset((List<Object>) assetsValue);
            if (apk != null) {
                apkName = SimpleJsonReader.asString(apk.get("name"));
                apkUrl = SimpleJsonReader.asString(apk.get("browser_download_url"));
                apkSize = SimpleJsonReader.asLong(apk.get("size"), -1);
                if (apkSize <= 0) apkSize = -1;
            }
        }

        return new ReleaseInfo(tag, version.getDisplayName(),
                SimpleJsonReader.asString(release.get("name")),
                SimpleJsonReader.asString(release.get("body")),
                SimpleJsonReader.asString(release.get("html_url")),
                apkName, apkUrl, apkSize,
                parseTime(SimpleJsonReader.asString(release.get("published_at"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findApkAsset(List<Object> assets) {
        Map<String, Object> fallback = null;
        for (Object item : assets) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> asset = (Map<String, Object>) item;
            String name = SimpleJsonReader.asString(asset.get("name"));
            String url = SimpleJsonReader.asString(asset.get("browser_download_url"));
            if (name == null || url == null) continue;
            String state = SimpleJsonReader.asString(asset.get("state"));
            if (state != null && !"uploaded".equals(state)) continue;

            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(APK_SUFFIX) || lower.contains(FDROID_MARKER)) continue;
            if (lower.startsWith(APK_PREFIX)) return asset;
            if (fallback == null) fallback = asset;
        }
        return fallback;
    }

    private static String lastWord(String s) {
        String trimmed = s.trim();
        int space = trimmed.lastIndexOf(' ');
        return space >= 0 ? trimmed.substring(space + 1) : trimmed;
    }

    /** @return ISO 8601 time ("2026-10-01T12:34:56Z") in millis, 0 if absent or invalid */
    static long parseTime(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException e) {
            LOG.debug("parseTime() invalid time {}", s);
            return 0;
        }
    }

    private static String readErrorBody(Reader body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[MAX_ERROR_BODY];
        try {
            int n;
            while (sb.length() < MAX_ERROR_BODY && (n = body.read(buffer)) != -1) {
                sb.append(buffer, 0, Math.min(n, MAX_ERROR_BODY - sb.length()));
            }
        } catch (IOException e) {
            LOG.debug("readErrorBody()", e);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

}
