package dummydomain.yetanothercallblocker.data.sources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Client of the PhoneBlock API (https://phoneblock.net, an open-source German community
 * spam list, sources: https://github.com/haumacher/phoneblock).
 *
 * <p>API facts (from the OpenAPI description {@code phoneblock/src/main/webapp/api/phoneblock.json}
 * and {@code INTEGRATIONS.md} of the PhoneBlock repository):</p>
 * <ul>
 *     <li>base URL {@value #DEFAULT_BASE_URL} (a test installation runs under
 *     {@code https://phoneblock.net/pb-test/api});</li>
 *     <li>authentication: {@code Authorization: Bearer <API key>}; a user creates
 *     an API key on the PhoneBlock settings page ("Your API keys");</li>
 *     <li>{@code GET /test} checks the credentials ("ok" or HTTP 401);</li>
 *     <li>{@code GET /blocklist[?since=<version>]} returns
 *     {@code {"numbers":[{"phone":"+49…","votes":10,"rating":"G_FRAUD","lastActivity":…}],
 *     "version":42}}. Without {@code since} it is the full list (numbers below the
 *     server's minimum vote threshold are excluded); with {@code since} only the changes
 *     after that version, where {@code votes == 0} means "remove the number". Votes are
 *     quantized to buckets 2, 4, 10, 20, 50, 100. Fair use: full download at most
 *     monthly, incremental update at most daily;</li>
 *     <li>{@code GET /check?sha1=<SHA-1 of the E.164 number, hex>} returns a
 *     {@code PhoneInfo} object ({@code phone, votes, votesWildcard, rating, whiteListed,
 *     blackListed, archived, dateAdded, lastUpdate, label, location, ...}); an unknown
 *     number is answered with {@code phone="unknown"}, {@code votes=0},
 *     {@code rating="A_LEGITIMATE"}.</li>
 * </ul>
 *
 * <p>The transport is pluggable ({@link HttpTransport}) so that parsing can be tested
 * without network access. Plain Java, no Android dependencies. Thread-safe if the
 * transport is.</p>
 */
public class PhoneBlockClient {

    public static final String DEFAULT_BASE_URL = "https://phoneblock.net/phoneblock/api";

    /** The settings page where a user creates API keys. */
    public static final String TOKEN_PAGE_URL = "https://phoneblock.net/phoneblock/settings#myAPIKeys";

    /** The PhoneBlock home page. */
    public static final String HOME_PAGE_URL = "https://phoneblock.net/phoneblock/";

    /** Phone value of a /check answer for an unknown number. */
    static final String UNKNOWN_PHONE = "unknown";

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockClient.class);

    /** Rating codes of PhoneBlock (enum {@code Rating} in {@code api.proto}). */
    public enum Rating {
        /** A regular non-spam call. */
        A_LEGITIMATE,
        /** Negatively rated without determining the call type. */
        B_MISSED,
        /** The caller immediately cut the connection. */
        C_PING,
        /** A poll. */
        D_POLL,
        /** Advertising, marketing, unwanted consulting. */
        E_ADVERTISING,
        /** Gambling or prize notifications. */
        F_GAMBLE,
        /** Fraud. */
        G_FRAUD;

        /** @return the rating with the given code, or null for null / unknown codes */
        public static Rating fromCode(String code) {
            if (code == null) return null;
            try {
                return valueOf(code.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** One entry of the blocklist. Immutable. */
    public static final class BlocklistEntry {

        private final String phone;
        private final int votes;
        private final Rating rating;
        private final long lastActivity;

        public BlocklistEntry(String phone, int votes, Rating rating, long lastActivity) {
            this.phone = Objects.requireNonNull(phone, "phone");
            this.votes = votes;
            this.rating = rating;
            this.lastActivity = lastActivity;
        }

        /** The number in international form with "+", e.g. {@code +4930123456}. */
        public String getPhone() {
            return phone;
        }

        /** Published vote bucket; 0 in an incremental update means "remove". */
        public int getVotes() {
            return votes;
        }

        /** The dominant rating, may be null if unknown. */
        public Rating getRating() {
            return rating;
        }

        /** Time of the last activity in millis since the epoch, 0 if unknown. */
        public long getLastActivity() {
            return lastActivity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BlocklistEntry)) return false;
            BlocklistEntry that = (BlocklistEntry) o;
            return votes == that.votes && lastActivity == that.lastActivity
                    && phone.equals(that.phone) && rating == that.rating;
        }

        @Override
        public int hashCode() {
            return Objects.hash(phone, votes, rating, lastActivity);
        }

        @Override
        public String toString() {
            return "BlocklistEntry{phone='" + phone + "', votes=" + votes
                    + ", rating=" + rating + ", lastActivity=" + lastActivity + '}';
        }
    }

    /** A blocklist (full or incremental) with its version. Immutable. */
    public static final class Blocklist {

        private final List<BlocklistEntry> entries;
        private final long version;
        private final int invalidEntries;

        public Blocklist(List<BlocklistEntry> entries, long version, int invalidEntries) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.version = version;
            this.invalidEntries = invalidEntries;
        }

        public List<BlocklistEntry> getEntries() {
            return entries;
        }

        /** Version for the next incremental update, -1 if the response had none. */
        public long getVersion() {
            return version;
        }

        /** Number of entries in the response that were skipped as malformed. */
        public int getInvalidEntries() {
            return invalidEntries;
        }

        @Override
        public String toString() {
            return "Blocklist{entries=" + entries.size() + ", version=" + version
                    + ", invalidEntries=" + invalidEntries + '}';
        }
    }

    /** Result of a single-number lookup ({@code PhoneInfo}). Immutable. */
    public static final class PhoneInfo {

        private final String phone;
        private final int votes;
        private final int votesWildcard;
        private final Rating rating;
        private final boolean whiteListed;
        private final boolean blackListed;
        private final boolean archived;
        private final String label;
        private final String location;

        public PhoneInfo(String phone, int votes, int votesWildcard, Rating rating,
                         boolean whiteListed, boolean blackListed, boolean archived,
                         String label, String location) {
            this.phone = phone;
            this.votes = votes;
            this.votesWildcard = votesWildcard;
            this.rating = rating;
            this.whiteListed = whiteListed;
            this.blackListed = blackListed;
            this.archived = archived;
            this.label = label;
            this.location = location;
        }

        /** The number, or "unknown" / null if the server doesn't know it. */
        public String getPhone() {
            return phone;
        }

        public boolean isKnown() {
            return phone != null && !UNKNOWN_PHONE.equals(phone);
        }

        public int getVotes() {
            return votes;
        }

        public int getVotesWildcard() {
            return votesWildcard;
        }

        public Rating getRating() {
            return rating;
        }

        /** The number is on the global whitelist (can't receive votes). */
        public boolean isWhiteListed() {
            return whiteListed;
        }

        /** The number is on the user's personal blacklist. */
        public boolean isBlackListed() {
            return blackListed;
        }

        /** Deprecated by PhoneBlock (always false since API v1.7). */
        public boolean isArchived() {
            return archived;
        }

        public String getLabel() {
            return label;
        }

        public String getLocation() {
            return location;
        }

        @Override
        public String toString() {
            return "PhoneInfo{phone='" + phone + "', votes=" + votes
                    + ", votesWildcard=" + votesWildcard + ", rating=" + rating
                    + ", whiteListed=" + whiteListed + ", blackListed=" + blackListed
                    + ", archived=" + archived + ", label='" + label + '\''
                    + ", location='" + location + "'}";
        }
    }

    /** An HTTP response; the body must be closed. */
    public static final class HttpResponse implements Closeable {

        private final int code;
        private final Reader body;
        private final Closeable resource;

        /**
         * @param code     HTTP status code
         * @param body     response body (UTF-8 decoded), may be null for an empty body
         * @param resource additional resource to close (e.g. the OkHttp response), may be null
         */
        public HttpResponse(int code, Reader body, Closeable resource) {
            this.code = code;
            this.body = body;
            this.resource = resource;
        }

        public static HttpResponse of(int code, String body) {
            return new HttpResponse(code, body != null ? new StringReader(body) : null, null);
        }

        public int getCode() {
            return code;
        }

        public Reader getBody() {
            return body;
        }

        @Override
        public void close() throws IOException {
            try {
                if (body != null) body.close();
            } finally {
                if (resource != null) resource.close();
            }
        }
    }

    /** Executes HTTP GET requests. */
    public interface HttpTransport {
        /**
         * @param url     full URL
         * @param headers request headers
         * @return the response (also for non-2xx codes)
         * @throws IOException on network errors
         */
        HttpResponse get(String url, Map<String, String> headers) throws IOException;
    }

    /** An error response of the API. */
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

        /** @return true if the token is missing, invalid or revoked */
        public boolean isAuthError() {
            return httpCode == 401 || httpCode == 403;
        }

        /** @return true if the server asks to slow down */
        public boolean isRateLimited() {
            return httpCode == 429;
        }
    }

    /** Max characters of an error body to include in exception messages. */
    private static final int MAX_ERROR_BODY = 200;

    private final HttpTransport transport;
    private final String baseUrl;
    private final String userAgent;

    /**
     * @param transport HTTP transport
     * @param baseUrl   API base URL without the trailing slash, null for the default
     * @param userAgent value of the User-Agent header (PhoneBlock asks clients to send
     *                  a descriptive one), may be null
     */
    public PhoneBlockClient(HttpTransport transport, String baseUrl, String userAgent) {
        this.transport = Objects.requireNonNull(transport, "transport");
        String url = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        this.baseUrl = url;
        this.userAgent = userAgent;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Checks the token ({@code GET /test}).
     *
     * @throws ApiException with {@link ApiException#isAuthError()} if the token is invalid
     * @throws IOException  on other errors
     */
    public void testConnection(String token) throws IOException {
        try (HttpResponse response = transport.get(baseUrl + "/test", headers(token))) {
            checkSuccess(response);
        }
    }

    /**
     * Downloads the blocklist.
     *
     * @param token API key, not empty
     * @param since version of the previous download for an incremental update,
     *              or a value &lt;= 0 for the full list
     */
    public Blocklist fetchBlocklist(String token, long since) throws IOException {
        String url = baseUrl + "/blocklist?format=json";
        if (since > 0) url += "&since=" + since;

        LOG.debug("fetchBlocklist() since={}", since);
        try (HttpResponse response = transport.get(url, headers(token))) {
            checkSuccess(response);
            if (response.getBody() == null) throw new IOException("Empty response");
            Blocklist blocklist = parseBlocklist(response.getBody());
            LOG.debug("fetchBlocklist() received {}", blocklist);
            return blocklist;
        }
    }

    /**
     * Looks up a single number using its SHA-1 hash ({@code GET /check}), so that the
     * number itself is not sent to the server.
     *
     * @param token API key, not empty
     * @param e164  the number in E.164 form ({@code +49...})
     */
    public PhoneInfo check(String token, String e164) throws IOException {
        String url = baseUrl + "/check?sha1=" + sha1Hex(e164) + "&format=json";
        try (HttpResponse response = transport.get(url, headers(token))) {
            checkSuccess(response);
            if (response.getBody() == null) throw new IOException("Empty response");
            return parsePhoneInfo(response.getBody());
        }
    }

    // parsing

    /**
     * Parses a {@code /blocklist} JSON response. The {@code numbers} array is processed
     * in a streaming fashion. Malformed entries (no phone, phone not in "+digits" form)
     * are skipped and counted.
     */
    public static Blocklist parseBlocklist(Reader reader) throws IOException {
        SimpleJsonReader json = new SimpleJsonReader(reader);
        List<BlocklistEntry> entries = new ArrayList<>();
        long version = -1;
        int invalid = 0;
        boolean hasNumbers = false;

        json.beginObject();
        while (json.hasNext()) {
            String name = json.nextName();
            if ("numbers".equals(name) && json.peek() == SimpleJsonReader.Token.BEGIN_ARRAY) {
                hasNumbers = true;
                json.beginArray();
                while (json.hasNext()) {
                    Object item = json.readValue();
                    BlocklistEntry entry = item instanceof Map ? toEntry((Map<?, ?>) item) : null;
                    if (entry != null) {
                        entries.add(entry);
                    } else {
                        invalid++;
                    }
                }
                json.endArray();
            } else if ("version".equals(name)) {
                version = SimpleJsonReader.asLong(json.readValue(), -1);
            } else {
                json.skipValue();
            }
        }
        json.endObject();

        if (!hasNumbers && version < 0) {
            throw new SimpleJsonReader.MalformedJsonException("Not a PhoneBlock blocklist");
        }
        if (invalid > 0) LOG.debug("parseBlocklist() skipped {} invalid entries", invalid);

        return new Blocklist(entries, version, invalid);
    }

    private static BlocklistEntry toEntry(Map<?, ?> map) {
        String phone = normalizePhone(SimpleJsonReader.asString(map.get("phone")));
        if (phone == null) return null;

        long votes = SimpleJsonReader.asLong(map.get("votes"), -1);
        if (votes < 0) return null;

        return new BlocklistEntry(phone, (int) Math.min(votes, Integer.MAX_VALUE),
                Rating.fromCode(SimpleJsonReader.asString(map.get("rating"))),
                SimpleJsonReader.asLong(map.get("lastActivity"), 0));
    }

    /**
     * Parses a {@code /check} or {@code /num} JSON response ({@code PhoneInfo}).
     */
    public static PhoneInfo parsePhoneInfo(Reader reader) throws IOException {
        Object value = new SimpleJsonReader(reader).readValue();
        if (!(value instanceof Map)) {
            throw new SimpleJsonReader.MalformedJsonException("Not a PhoneBlock PhoneInfo");
        }
        Map<?, ?> map = (Map<?, ?>) value;

        String phone = SimpleJsonReader.asString(map.get("phone"));
        return new PhoneInfo(
                phone,
                (int) clamp(SimpleJsonReader.asLong(map.get("votes"), 0)),
                (int) clamp(SimpleJsonReader.asLong(map.get("votesWildcard"), 0)),
                Rating.fromCode(SimpleJsonReader.asString(map.get("rating"))),
                SimpleJsonReader.asBoolean(map.get("whiteListed"), false),
                SimpleJsonReader.asBoolean(map.get("blackListed"), false),
                SimpleJsonReader.asBoolean(map.get("archived"), false),
                SimpleJsonReader.asString(map.get("label")),
                SimpleJsonReader.asString(map.get("location")));
    }

    private static long clamp(long v) {
        return Math.max(0, Math.min(v, Integer.MAX_VALUE));
    }

    /**
     * Checks that the phone number from the API is in "+digits" form
     * (PhoneBlock sends {@code PhoneNumer.getPlus()}); a "00" prefix is converted.
     *
     * @return the E.164 number or null
     */
    static String normalizePhone(String phone) {
        if (phone == null) return null;
        String s = phone.trim();
        if (s.startsWith("00")) s = "+" + s.substring(2);
        if (s.length() < 4 || s.length() > 20 || s.charAt(0) != '+') return null;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        if (s.charAt(1) == '0') return null;
        return s;
    }

    /**
     * SHA-1 of the UTF-8 bytes of the number, as 40 upper-case hex digits
     * (as PhoneBlock's {@code PhoneHash.encodeHash()} and its Android app compute it).
     */
    public static String sha1Hex(String e164) {
        Objects.requireNonNull(e164, "e164");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(e164.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0x0F, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(b & 0x0F, 16)));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }

    // internals

    private Map<String, String> headers(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("No PhoneBlock API key");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token.trim());
        headers.put("Accept", "application/json");
        if (userAgent != null) headers.put("User-Agent", userAgent);
        return headers;
    }

    private static void checkSuccess(HttpResponse response) throws IOException {
        int code = response.getCode();
        if (code >= 200 && code < 300) return;

        String message = "HTTP " + code;
        String body = readErrorBody(response.getBody());
        if (!body.isEmpty()) message += ": " + body;
        throw new ApiException(code, message);
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
