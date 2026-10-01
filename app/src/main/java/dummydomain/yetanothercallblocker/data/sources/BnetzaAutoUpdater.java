package dummydomain.yetanothercallblocker.data.sources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dummydomain.yetanothercallblocker.data.SourcesManager;

/**
 * Keeps the Bundesnetzagentur "Maßnahmenliste" up to date without any user action.
 *
 * <p>An update downloads</p>
 * <ul>
 *     <li>the page with the measures of the last six months ({@link #CURRENT_MEASURES_URL};
 *     if it fails, the short link {@link BnetzaMeasuresParser#SOURCE_URL} that redirects
 *     to it) – required;</li>
 *     <li>the yearly lists in HTML form for the current and the previous year
 *     ({@link #YEARLY_LIST_URL_PATTERN}) and yearly HTML lists linked from the page –
 *     optional: a missing list (HTTP 404, the list exists only as PDF) or a failed
 *     download is skipped.</li>
 * </ul>
 * <p>All pages are parsed with {@link BnetzaMeasuresParser#parseHtml(String)}, the
 * entries are merged (one entry per number or prefix, the one with the most recent
 * decision date wins) and stored with {@link SourcesManager#importList} under the
 * fixed source id {@link SourcesManager#BNETZA_SOURCE_ID}, replacing any previous
 * (also a manually imported) Bundesnetzagentur list.</p>
 *
 * <p>Robustness: if the required page can't be downloaded or contains no entries
 * (e.g. an error page or a changed layout), the old list is kept and the error is
 * recorded in the list's {@link RemoteListInfo} (last check, last success, last error).
 * Requests are conditional ({@code If-None-Match} / {@code If-Modified-Since} per URL,
 * stored as {@link RemoteListInfo.Part}s); if every page is unchanged, nothing is
 * re-parsed. Redirects are followed by the downloader.</p>
 *
 * <p>Plain Java, no Android dependencies. Downloads block, so {@link #update(boolean)}
 * must be called from a background thread; updates are serialized.</p>
 */
public class BnetzaAutoUpdater {

    /** Source id of the list (shared with the manual import). */
    public static final String SOURCE_ID = SourcesManager.BNETZA_SOURCE_ID;
    public static final String DISPLAY_NAME = SourcesManager.BNETZA_DISPLAY_NAME;

    /**
     * Page with the measures of the last six months; the target of the short link
     * {@link BnetzaMeasuresParser#SOURCE_URL}.
     */
    public static final String CURRENT_MEASURES_URL = "https://www.bundesnetzagentur.de"
            + "/DE/Vportal/TK/Aerger/Aktuelles/Ma%C3%9Fnahmen/start_RM.html";

    /**
     * Yearly lists in HTML form, {@code %d} is the year. Known to exist for 2023 and
     * 2024 (Ma&szlig;nahmenliste2023.html, Ma&szlig;nahmenliste2024.html); other years
     * may be PDF only.
     */
    public static final String YEARLY_LIST_URL_PATTERN = "https://www.bundesnetzagentur.de"
            + "/SharedDocs/Downloads/DE/Sachgebiete/Telekommunikation/Verbraucher"
            + "/Rufnummernmissbrauch/Massnahmenlisten/Ma%%C3%%9Fnahmenliste%d.html";

    /** Size limit of a single page; the yearly lists are below 1 MB. */
    public static final long MAX_PAGE_SIZE = 10L * 1024 * 1024;

    /** At most this many yearly lists are downloaded per update. */
    static final int MAX_YEARLY_LISTS = 4;

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /** Links to yearly HTML lists on the page, e.g. ".../Massnahmenlisten/Maßnahmenliste2024.html". */
    private static final Pattern YEARLY_LINK = Pattern.compile(
            "(?i)href\\s*=\\s*[\"']([^\"'<>\\s]*Massnahmenlisten/[^\"'<>\\s]*?(20\\d\\d)[^\"'<>\\s/]*?\\.html[^\"'<>\\s]*)[\"']");

    private static final Logger LOG = LoggerFactory.getLogger(BnetzaAutoUpdater.class);

    /** Outcome of an update. Immutable. */
    public static final class Result {

        public enum Status {
            /** New content was downloaded and stored. */
            UPDATED,
            /** No page has changed since the last update. */
            NOT_MODIFIED,
            /** The update failed; the old list (if any) is kept. */
            FAILED
        }

        private final Status status;
        private final int entryCount;
        private final int addedCount;
        private final int removedCount;
        private final int skippedRowCount;
        private final List<String> downloadedUrls;
        private final List<String> warnings;
        private final String error;
        private final long checkedAt;

        Result(Status status, int entryCount, int addedCount, int removedCount,
               int skippedRowCount, List<String> downloadedUrls, List<String> warnings,
               String error, long checkedAt) {
            this.status = status;
            this.entryCount = entryCount;
            this.addedCount = addedCount;
            this.removedCount = removedCount;
            this.skippedRowCount = skippedRowCount;
            this.downloadedUrls = Collections.unmodifiableList(new ArrayList<>(downloadedUrls));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.error = error;
            this.checkedAt = checkedAt;
        }

        public Status getStatus() {
            return status;
        }

        public boolean isSuccess() {
            return status != Status.FAILED;
        }

        /** Number of entries in the stored list after the update (the old count if it failed). */
        public int getEntryCount() {
            return entryCount;
        }

        /** Entries (numbers or prefixes) that were not in the previous list. */
        public int getAddedCount() {
            return addedCount;
        }

        /** Entries of the previous list that are no longer listed. */
        public int getRemovedCount() {
            return removedCount;
        }

        /** Table rows that could not be parsed (wholly or partially). */
        public int getSkippedRowCount() {
            return skippedRowCount;
        }

        /** URLs whose content makes up the list (successful or unchanged downloads). */
        public List<String> getDownloadedUrls() {
            return downloadedUrls;
        }

        /** Problems with optional pages (yearly lists) that did not fail the update. */
        public List<String> getWarnings() {
            return warnings;
        }

        /** Error message for {@link Status#FAILED}, otherwise null. */
        public String getError() {
            return error;
        }

        /** Time of the update attempt, millis since the epoch. */
        public long getCheckedAt() {
            return checkedAt;
        }

        @Override
        public String toString() {
            return "Result{status=" + status + ", entryCount=" + entryCount
                    + ", added=" + addedCount + ", removed=" + removedCount
                    + ", skippedRows=" + skippedRowCount + ", urls=" + downloadedUrls
                    + ", warnings=" + warnings + ", error='" + error + "'}";
        }
    }

    /** Stored state of the list for the UI. Immutable. */
    public static final class Status {

        private final boolean listStored;
        private final int entryCount;
        private final long importedAt;
        private final long lastCheckAt;
        private final long lastSuccessAt;
        private final String lastError;
        private final boolean autoUpdated;

        Status(boolean listStored, int entryCount, long importedAt, long lastCheckAt,
               long lastSuccessAt, String lastError, boolean autoUpdated) {
            this.listStored = listStored;
            this.entryCount = entryCount;
            this.importedAt = importedAt;
            this.lastCheckAt = lastCheckAt;
            this.lastSuccessAt = lastSuccessAt;
            this.lastError = lastError;
            this.autoUpdated = autoUpdated;
        }

        /** Whether a list is stored at all. */
        public boolean isListStored() {
            return listStored;
        }

        public int getEntryCount() {
            return entryCount;
        }

        /** When the current entries were stored (downloaded or imported), 0 if never. */
        public long getImportedAt() {
            return importedAt;
        }

        /** Last update attempt, 0 if never. */
        public long getLastCheckAt() {
            return lastCheckAt;
        }

        /** Last successful update attempt, 0 if never. */
        public long getLastSuccessAt() {
            return lastSuccessAt;
        }

        /** Error of the last update attempt, null if it succeeded. */
        public String getLastError() {
            return lastError;
        }

        /** False if the stored list was imported manually and not yet updated automatically. */
        public boolean isAutoUpdated() {
            return autoUpdated;
        }

        @Override
        public String toString() {
            return "Status{listStored=" + listStored + ", entryCount=" + entryCount
                    + ", importedAt=" + importedAt + ", lastCheckAt=" + lastCheckAt
                    + ", lastSuccessAt=" + lastSuccessAt + ", lastError='" + lastError
                    + "', autoUpdated=" + autoUpdated + '}';
        }
    }

    /** Outcome of a single page download. */
    private static final class Page {
        final String url;
        RemoteListDownloader.Response response;
        List<ListedNumber> entries;
        int skipped;

        Page(String url) {
            this.url = url;
        }
    }

    private final SourcesManager sourcesManager;
    private final RemoteListDownloader downloader;
    private final LongSupplier clock;
    private final List<String> mainUrls;
    private final String yearlyListUrlPattern;
    private final BnetzaMeasuresParser parser = new BnetzaMeasuresParser();

    private final Object lock = new Object();

    private volatile Result lastResult;

    public BnetzaAutoUpdater(SourcesManager sourcesManager, RemoteListDownloader downloader) {
        this(sourcesManager, downloader, System::currentTimeMillis);
    }

    public BnetzaAutoUpdater(SourcesManager sourcesManager, RemoteListDownloader downloader,
                             LongSupplier clock) {
        this(sourcesManager, downloader, clock,
                Arrays.asList(CURRENT_MEASURES_URL, BnetzaMeasuresParser.SOURCE_URL),
                YEARLY_LIST_URL_PATTERN);
    }

    /**
     * @param mainUrls             URLs of the page with the current measures, tried in order
     * @param yearlyListUrlPattern URL of a yearly list with {@code %d} for the year,
     *                             or null for none
     */
    BnetzaAutoUpdater(SourcesManager sourcesManager, RemoteListDownloader downloader,
                      LongSupplier clock, List<String> mainUrls, String yearlyListUrlPattern) {
        this.sourcesManager = Objects.requireNonNull(sourcesManager, "sourcesManager");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (mainUrls.isEmpty()) throw new IllegalArgumentException("No main URL");
        this.mainUrls = Collections.unmodifiableList(new ArrayList<>(mainUrls));
        this.yearlyListUrlPattern = yearlyListUrlPattern;
    }

    /**
     * @return whether the source is enabled (it is by default)
     */
    public boolean isEnabled() {
        return sourcesManager.isEnabled(SOURCE_ID);
    }

    /**
     * @return true if no list is stored yet (a cheap check, no list loading)
     */
    public boolean needsInitialDownload() {
        return !sourcesManager.hasStoredList(SOURCE_ID);
    }

    /** Result of the last update in this process, or null. */
    public Result getLastResult() {
        return lastResult;
    }

    /**
     * @return the stored state of the list (loads the lists if necessary)
     */
    public Status getStatus() {
        SourcesManager.SourceInfo source = sourcesManager.getSource(SOURCE_ID);
        NumberListStore.ListMetadata metadata = source != null ? source.getMetadata() : null;
        RemoteListInfo remote = metadata != null ? metadata.getRemote() : null;
        Result last = lastResult;

        if (metadata == null) {
            // nothing stored (e.g. the first download failed): only this process knows
            return new Status(false, 0, 0,
                    last != null ? last.getCheckedAt() : 0, 0,
                    last != null ? last.getError() : null, false);
        }
        return new Status(true, metadata.getEntryCount(), metadata.getImportedAt(),
                remote != null ? remote.getLastCheckAt() : 0,
                remote != null ? remote.getLastSuccessAt() : 0,
                remote != null ? remote.getLastError() : null,
                remote != null);
    }

    /**
     * Downloads the pages and replaces the stored list if they changed. Never throws
     * for network or parse problems; they are returned (and stored in the list metadata
     * if a list exists).
     *
     * @param force true to download everything without conditional requests
     */
    public Result update(boolean force) {
        synchronized (lock) {
            Result result;
            try {
                result = doUpdate(force);
            } catch (Exception e) {
                LOG.warn("update() failed", e);
                result = fail(currentRemote(), errorMessage(e), new ArrayList<>());
            }
            lastResult = result;
            LOG.info("update() {}", result);
            return result;
        }
    }

    private Result doUpdate(boolean force) throws IOException {
        SourcesManager.SourceInfo source = sourcesManager.getSource(SOURCE_ID);
        NumberListStore.ListMetadata metadata = source != null ? source.getMetadata() : null;
        RemoteListInfo stored = metadata != null ? metadata.getRemote() : null;
        // without a usable stored list everything is downloaded in full
        boolean conditional = !force && stored != null && !source.isLoadFailed();
        RemoteListInfo validators = conditional ? stored : null;

        List<String> warnings = new ArrayList<>();

        // the required page: the direct URL, then the short link
        Page main = null;
        String mainError = null;
        for (String url : mainUrls) {
            try {
                Page page = fetch(url, validators, true);
                if (page != null) {
                    main = page;
                    break;
                }
                if (mainError == null) mainError = "No entries found on " + url;
            } catch (IOException e) {
                LOG.info("doUpdate() {} failed: {}", url, e.toString());
                if (mainError == null) mainError = errorMessage(e);
            }
        }
        if (main == null) {
            return fail(stored, mainError, warnings);
        }

        // optional yearly lists
        List<Page> pages = new ArrayList<>();
        pages.add(main);
        for (String url : yearlyListUrls(main, validators)) {
            try {
                Page page = fetch(url, validators, false);
                if (page != null) pages.add(page);
            } catch (RemoteListDownloader.HttpStatusException e) {
                if (e.getCode() != 404 && e.getCode() != 410) {
                    warnings.add(url + ": " + errorMessage(e));
                }
                LOG.debug("doUpdate() {} skipped: {}", url, e.toString());
            } catch (IOException e) {
                warnings.add(url + ": " + errorMessage(e));
                LOG.info("doUpdate() {} failed: {}", url, e.toString());
            }
        }

        boolean allUnchanged = true;
        for (Page page : pages) {
            if (!page.response.isNotModified()) allUnchanged = false;
        }
        Set<String> urls = new LinkedHashSet<>();
        for (Page page : pages) urls.add(page.url);

        long now = clock.getAsLong();

        if (allUnchanged && stored != null && partUrls(stored).equals(urls)) {
            RemoteListInfo remote = stored.withSuccess(main.response.getEtag(),
                    main.response.getLastModified(), parts(pages), now);
            sourcesManager.updateRemoteInfo(SOURCE_ID, remote);
            return new Result(Result.Status.NOT_MODIFIED, metadata.getEntryCount(), 0, 0, 0,
                    new ArrayList<>(urls), warnings, null, now);
        }

        // some pages changed: unchanged pages are needed in full for the merge
        List<Page> complete = new ArrayList<>();
        for (Page page : pages) {
            if (page.entries != null) {
                complete.add(page);
                continue;
            }
            try {
                Page full = fetch(page.url, null, page == main);
                if (full != null) {
                    complete.add(full);
                } else if (page == main) {
                    return fail(stored, "No entries found on " + page.url, warnings);
                }
            } catch (IOException e) {
                if (page == main) return fail(stored, errorMessage(e), warnings);
                warnings.add(page.url + ": " + errorMessage(e));
            }
        }

        List<List<ListedNumber>> lists = new ArrayList<>();
        int skipped = 0;
        for (Page page : complete) {
            lists.add(page.entries);
            skipped += page.skipped;
        }
        List<ListedNumber> merged = merge(lists);

        Set<String> oldKeys = new HashSet<>();
        if (metadata != null) oldKeys.addAll(storedKeys());
        Set<String> newKeys = new HashSet<>();
        for (ListedNumber entry : merged) newKeys.add(key(entry));
        int added = 0;
        for (String key : newKeys) if (!oldKeys.contains(key)) added++;
        int removed = 0;
        for (String key : oldKeys) if (!newKeys.contains(key)) removed++;

        Page mainPage = complete.get(0);
        RemoteListInfo base = stored != null ? stored
                : new RemoteListInfo(BnetzaMeasuresParser.SOURCE_URL, true);
        RemoteListInfo remote = base.withSuccess(mainPage.response.getEtag(),
                mainPage.response.getLastModified(), parts(complete), now);
        sourcesManager.importList(SOURCE_ID, DISPLAY_NAME, merged, now, remote);

        List<String> downloaded = new ArrayList<>();
        for (Page page : complete) downloaded.add(page.url);
        return new Result(Result.Status.UPDATED, merged.size(), added, removed, skipped,
                downloaded, warnings, null, now);
    }

    /**
     * Downloads and parses a page.
     *
     * @return the page (with entries unless not modified), or null if it has no entries
     */
    private Page fetch(String url, RemoteListInfo validators, boolean required) throws IOException {
        RemoteListInfo.Part part = validators != null ? validators.getPart(url) : null;
        Page page = new Page(url);
        page.response = downloader.download(url,
                part != null ? part.getEtag() : null,
                part != null ? part.getLastModified() : null);
        if (page.response.isNotModified()) {
            if (part == null) throw new IOException("Unexpected HTTP 304 response");
            return page;
        }

        String html = decode(page.response.getBody());
        ParseResult result = parser.parseHtml(html);
        page.entries = result.getEntries();
        page.skipped = result.getSkipped().size();
        LOG.debug("fetch() {}: {} entries, {} skipped rows", url, page.entries.size(), page.skipped);
        if (page.entries.isEmpty()) {
            if (required) LOG.warn("fetch() no entries on {}", url);
            return null;
        }
        return page;
    }

    /**
     * Yearly HTML lists to download: links on the main page (if any) and the pattern
     * URLs for the current and the previous year.
     */
    private List<String> yearlyListUrls(Page main, RemoteListInfo validators) {
        int year = Instant.ofEpochMilli(clock.getAsLong()).atZone(ZONE).getYear();
        Set<String> urls = new LinkedHashSet<>();
        if (yearlyListUrlPattern != null) {
            urls.add(String.format(Locale.ROOT, yearlyListUrlPattern, year));
            urls.add(String.format(Locale.ROOT, yearlyListUrlPattern, year - 1));
        }

        if (main.response.getBody() != null) {
            String html = decode(main.response.getBody());
            for (String link : findYearlyListLinks(html, main.url, year - 1)) {
                if (urls.size() >= MAX_YEARLY_LISTS) break;
                if (!containsSameDocument(urls, link)) urls.add(link);
            }
        } else if (validators != null) {
            // the page is unchanged: check the yearly lists found on it last time
            for (RemoteListInfo.Part part : validators.getParts()) {
                if (urls.size() >= MAX_YEARLY_LISTS) break;
                String url = part.getUrl();
                if (mainUrls.contains(url)) continue;
                if (!containsSameDocument(urls, url)) urls.add(url);
            }
        }
        return new ArrayList<>(urls);
    }

    /**
     * Finds links to yearly HTML lists ({@code .../Massnahmenlisten/...2024....html})
     * of the given year or later, resolved against the page URL.
     */
    static List<String> findYearlyListLinks(String html, String pageUrl, int minYear) {
        List<String> links = new ArrayList<>();
        Matcher m = YEARLY_LINK.matcher(html);
        while (m.find()) {
            int year = Integer.parseInt(m.group(2));
            if (year < minYear) continue;
            String href = BnetzaMeasuresParser.decodeEntities(m.group(1));
            try {
                URI resolved = new URI(pageUrl).resolve(href.trim());
                String scheme = resolved.getScheme();
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) continue;
                String link = resolved.toString();
                if (!links.contains(link)) links.add(link);
            } catch (URISyntaxException | IllegalArgumentException e) {
                LOG.debug("findYearlyListLinks() invalid link {}", href);
            }
        }
        return links;
    }

    /** Compares URLs without query and session id, so that "x.html?nn=1" equals "x.html". */
    private static boolean containsSameDocument(Set<String> urls, String url) {
        String doc = documentPath(url);
        for (String u : urls) {
            if (documentPath(u).equalsIgnoreCase(doc)) return true;
        }
        return false;
    }

    private static String documentPath(String url) {
        int end = url.length();
        for (char c : new char[]{'?', '#', ';'}) {
            int i = url.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        return url.substring(0, end);
    }

    /**
     * Merges the entries of several pages: one entry per number or prefix, the one with
     * the most recent decision date; on equal dates the first one wins (pages are in
     * priority order). The result is sorted by date (newest first), then by number.
     */
    static List<ListedNumber> merge(List<List<ListedNumber>> lists) {
        Map<String, ListedNumber> byKey = new LinkedHashMap<>();
        for (List<ListedNumber> list : lists) {
            for (ListedNumber entry : list) {
                String key = key(entry);
                ListedNumber existing = byKey.get(key);
                if (existing == null || isNewer(entry.getDate(), existing.getDate())) {
                    byKey.put(key, entry);
                }
            }
        }

        List<ListedNumber> result = new ArrayList<>(byKey.values());
        Collections.sort(result, Comparator
                .comparing(ListedNumber::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(BnetzaAutoUpdater::key));
        return result;
    }

    private static boolean isNewer(LocalDate date, LocalDate than) {
        return date != null && (than == null || date.isAfter(than));
    }

    static String key(ListedNumber entry) {
        return entry.isPrefix() ? "P" + entry.getPrefix() : "N" + entry.getNumber();
    }

    private Set<String> storedKeys() {
        Set<String> keys = new HashSet<>();
        try {
            for (ListedNumber entry : sourcesManager.loadListEntries(SOURCE_ID)) {
                keys.add(key(entry));
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("storedKeys() failed", e);
        }
        return keys;
    }

    private static List<RemoteListInfo.Part> parts(List<Page> pages) {
        List<RemoteListInfo.Part> parts = new ArrayList<>();
        for (Page page : pages) {
            parts.add(new RemoteListInfo.Part(page.url, page.response.getEtag(),
                    page.response.getLastModified()));
        }
        return parts;
    }

    private static Set<String> partUrls(RemoteListInfo remote) {
        Set<String> urls = new LinkedHashSet<>();
        for (RemoteListInfo.Part part : remote.getParts()) urls.add(part.getUrl());
        return urls;
    }

    private RemoteListInfo currentRemote() {
        try {
            SourcesManager.SourceInfo source = sourcesManager.getSource(SOURCE_ID);
            NumberListStore.ListMetadata metadata = source != null ? source.getMetadata() : null;
            return metadata != null ? metadata.getRemote() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Records a failed update; the stored entries are kept. */
    private Result fail(RemoteListInfo stored, String error, List<String> warnings) {
        long now = clock.getAsLong();
        if (error == null) error = "Update failed";
        int entryCount = 0;
        try {
            SourcesManager.SourceInfo source = sourcesManager.getSource(SOURCE_ID);
            if (source != null && source.getMetadata() != null) {
                entryCount = source.getMetadata().getEntryCount();
                RemoteListInfo base = stored != null ? stored
                        : new RemoteListInfo(BnetzaMeasuresParser.SOURCE_URL, true);
                sourcesManager.updateRemoteInfo(SOURCE_ID, base.withError(error, now));
            }
        } catch (Exception e) {
            LOG.warn("fail() failed to record the error", e);
        }
        return new Result(Result.Status.FAILED, entryCount, 0, 0, 0,
                Collections.emptyList(), warnings, error, now);
    }

    /** Decodes a page: UTF-8, or Windows-1252 if the content is not valid UTF-8. */
    static String decode(byte[] content) {
        if (content == null) return "";
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            Charset fallback;
            try {
                fallback = Charset.forName("windows-1252");
            } catch (RuntimeException e2) {
                fallback = StandardCharsets.ISO_8859_1;
            }
            return new String(content, fallback);
        }
    }

    static String errorMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        if (e instanceof java.net.UnknownHostException) return "Unknown host: " + message;
        return message;
    }

}
