package dummydomain.yetanothercallblocker.data;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import dummydomain.yetanothercallblocker.data.provider.AggregatedResult;
import dummydomain.yetanothercallblocker.data.provider.InMemoryResultCache;
import dummydomain.yetanothercallblocker.data.provider.NumberInfoProvider;
import dummydomain.yetanothercallblocker.data.provider.ProviderResult;
import dummydomain.yetanothercallblocker.data.sources.ListedNumber;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SourcesManagerTest {

    private static final String NUMBER = "+4915112345678";
    private static final String OTHER_NUMBER = "+4930123456";

    /** In-memory preferences. */
    static class MemoryPreferences implements SourcesManager.Preferences {
        String order;
        String disabled;
        String enabled;
        int writes;

        @Override
        public String getSourcesOrder() {
            return order;
        }

        @Override
        public void setSourcesOrder(String order) {
            this.order = order;
            writes++;
        }

        @Override
        public String getDisabledSources() {
            return disabled;
        }

        @Override
        public void setDisabledSources(String ids) {
            this.disabled = ids;
            writes++;
        }

        @Override
        public String getEnabledSources() {
            return enabled;
        }

        @Override
        public void setEnabledSources(String ids) {
            this.enabled = ids;
            writes++;
        }
    }

    /** Built-in offline provider that rates a single number as positive. */
    static class BuiltInProvider implements NumberInfoProvider {
        @Override
        public String getId() {
            return "yacb";
        }

        @Override
        public String getDisplayName() {
            return "YACB";
        }

        @Override
        public boolean isOffline() {
            return true;
        }

        @Override
        public ProviderResult lookup(String number) {
            return OTHER_NUMBER.equals(number)
                    ? new ProviderResult("yacb", ProviderResult.Rating.POSITIVE) : null;
        }
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private NumberListStore store;
    private MemoryPreferences prefs;

    @Before
    public void setUp() throws IOException {
        store = new NumberListStore(tmp.newFolder("lists"));
        prefs = new MemoryPreferences();
    }

    private SourcesManager newManager() {
        return new SourcesManager(store, prefs,
                Collections.singletonList(new BuiltInProvider()));
    }

    private static List<ListedNumber> entries(String... numbers) {
        List<ListedNumber> list = new ArrayList<>();
        for (String n : numbers) {
            list.add(ListedNumber.builder().number(n).category("Spam").build());
        }
        return list;
    }

    private static List<String> ids(List<SourcesManager.SourceInfo> sources) {
        List<String> ids = new ArrayList<>();
        for (SourcesManager.SourceInfo s : sources) ids.add(s.getId());
        return ids;
    }

    @Test
    public void builtInOnlyByDefault() {
        SourcesManager manager = newManager();

        List<SourcesManager.SourceInfo> sources = manager.getSources();
        assertEquals(Collections.singletonList("yacb"), ids(sources));
        assertTrue(sources.get(0).isEnabled());
        assertFalse(sources.get(0).isImported());
        assertNull(sources.get(0).getMetadata());
        assertNull(manager.lookupListedNumbers(NUMBER));
        assertEquals(0, prefs.writes);
    }

    @Test
    public void storedListsAreLoadedAfterBuiltIns() throws IOException {
        store.save("csv_b", "B", 2, entries(OTHER_NUMBER));
        store.save("bnetza", "Bundesnetzagentur", 1, entries(NUMBER));

        SourcesManager manager = newManager();
        assertFalse(manager.isLoaded());

        List<SourcesManager.SourceInfo> sources = manager.getSources();
        assertTrue(manager.isLoaded());
        assertEquals(Arrays.asList("yacb", "bnetza", "csv_b"), ids(sources));

        SourcesManager.SourceInfo bnetza = sources.get(1);
        assertTrue(bnetza.isImported());
        assertTrue(bnetza.isOffline());
        assertEquals("Bundesnetzagentur", bnetza.getDisplayName());
        assertEquals(1, bnetza.getMetadata().getEntryCount());
        assertEquals(1, bnetza.getMetadata().getImportedAt());

        ProviderResult result = manager.lookupListedNumbers(NUMBER);
        assertNotNull(result);
        assertEquals("bnetza", result.getSourceId());
        assertEquals(ProviderResult.Rating.NEGATIVE, result.getRating());
        assertEquals("Spam", result.getCategory());
    }

    @Test
    public void lookupAcceptsNationalNumbers() throws IOException {
        store.save("bnetza", null, 1, entries(NUMBER));
        SourcesManager manager = newManager();

        assertNotNull(manager.lookupListedNumbers("015112345678"));
        assertNull(manager.lookupListedNumbers("015112345679"));
        assertNull(manager.lookupListedNumbers(""));
        assertNull(manager.lookupListedNumbers(null));
    }

    @Test
    public void disabledListIsNotConsulted() throws IOException {
        store.save("bnetza", null, 1, entries(NUMBER));
        SourcesManager manager = newManager();

        manager.setEnabled("bnetza", false);
        assertFalse(manager.isEnabled("bnetza"));
        assertEquals("bnetza", prefs.disabled);
        assertNull(manager.lookupListedNumbers(NUMBER));
        assertFalse(manager.getSource("bnetza").isEnabled());

        // persisted
        SourcesManager reloaded = newManager();
        assertFalse(reloaded.isEnabled("bnetza"));
        assertNull(reloaded.lookupListedNumbers(NUMBER));

        reloaded.setEnabled("bnetza", true);
        assertEquals("", prefs.disabled);
        assertNotNull(reloaded.lookupListedNumbers(NUMBER));
    }

    @Test
    public void setEnabledWithoutChangeDoesNotWrite() {
        SourcesManager manager = newManager();
        manager.setEnabled("yacb", true);
        assertEquals(0, prefs.writes);
    }

    @Test
    public void builtInCanBeDisabled() {
        SourcesManager manager = newManager();
        manager.setEnabled("yacb", false);

        assertFalse(manager.isEnabled("yacb"));
        assertTrue(manager.getAggregator().getProviders().isEmpty());
    }

    @Test
    public void moveChangesAndPersistsOrder() throws IOException {
        store.save("a", null, 1, entries(NUMBER));
        store.save("b", null, 1, entries(NUMBER));
        SourcesManager manager = newManager();

        assertFalse(manager.moveUp("yacb"));
        assertFalse(manager.moveDown("b"));
        assertFalse(manager.moveUp("missing"));
        assertEquals(0, prefs.writes);

        assertTrue(manager.moveUp("b"));
        assertEquals(Arrays.asList("yacb", "b", "a"), ids(manager.getSources()));
        assertEquals("yacb,b,a", prefs.order);

        assertTrue(manager.moveDown("yacb"));
        assertEquals(Arrays.asList("b", "yacb", "a"), ids(manager.getSources()));

        // the first enabled list in order wins
        assertEquals("b", manager.lookupListedNumbers(NUMBER).getSourceId());

        SourcesManager reloaded = newManager();
        assertEquals(Arrays.asList("b", "yacb", "a"), ids(reloaded.getSources()));
    }

    @Test
    public void storedOrderIgnoresUnknownAndAppendsNewSources() throws IOException {
        prefs.order = "gone, b ,yacb,b";
        store.save("a", null, 1, entries(NUMBER));
        store.save("b", null, 1, entries(NUMBER));

        assertEquals(Arrays.asList("b", "yacb", "a"), ids(newManager().getSources()));
    }

    @Test
    public void importAddsEnabledListAtTheEnd() throws IOException {
        prefs.disabled = "csv_new";
        SourcesManager manager = newManager();

        NumberListStore.ListMetadata metadata = manager.importList(
                "csv_new", "New", entries(NUMBER, OTHER_NUMBER), 42);

        assertEquals(2, metadata.getEntryCount());
        assertEquals(42, metadata.getImportedAt());
        assertTrue(store.exists("csv_new"));
        assertEquals(Arrays.asList("yacb", "csv_new"), ids(manager.getSources()));
        assertTrue(manager.isEnabled("csv_new")); // stale flag was cleared
        assertEquals("New", manager.getDisplayName("csv_new"));
        assertEquals("csv_new", manager.lookupListedNumbers(OTHER_NUMBER).getSourceId());
    }

    @Test
    public void reimportReplacesListAndKeepsState() throws IOException {
        store.save("a", "A", 1, entries(NUMBER));
        store.save("b", "B", 1, entries(NUMBER));
        SourcesManager manager = newManager();
        manager.setEnabled("a", false);

        manager.importList("a", "A2", entries(OTHER_NUMBER), 2);

        assertEquals(Arrays.asList("yacb", "a", "b"), ids(manager.getSources()));
        assertFalse(manager.isEnabled("a"));
        assertEquals("A2", manager.getDisplayName("a"));
        assertEquals(2, manager.getSource("a").getMetadata().getImportedAt());

        manager.setEnabled("a", true);
        assertEquals("a", manager.lookupListedNumbers(OTHER_NUMBER).getSourceId());
        assertEquals("b", manager.lookupListedNumbers(NUMBER).getSourceId());
    }

    @Test
    public void deleteRemovesListAndSettings() throws IOException {
        store.save("a", null, 1, entries(NUMBER));
        store.save("b", null, 1, entries(NUMBER));
        SourcesManager manager = newManager();
        manager.moveUp("b");
        manager.setEnabled("a", false);

        assertTrue(manager.deleteList("a"));

        assertFalse(store.exists("a"));
        assertEquals(Arrays.asList("yacb", "b"), ids(manager.getSources()));
        assertEquals("yacb,b", prefs.order);
        assertEquals("", prefs.disabled);
        assertFalse(manager.deleteList("a"));
    }

    @Test
    public void builtInCanNotBeDeletedOrReplaced() throws IOException {
        SourcesManager manager = newManager();
        try {
            manager.deleteList("yacb");
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            manager.importList("yacb", null, entries(NUMBER), 1);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void storedListWithReservedIdIsIgnored() throws IOException {
        store.save("yacb", null, 1, entries(NUMBER));
        SourcesManager manager = newManager();

        assertEquals(Collections.singletonList("yacb"), ids(manager.getSources()));
        assertNull(manager.lookupListedNumbers(NUMBER));
    }

    @Test
    public void brokenListIsKeptAsFailedEmptySource() throws IOException {
        store.save("good", null, 1, entries(NUMBER));
        store.save("bad", "Bad", 1, entries(NUMBER));
        File badFile = new File(store.getDirectory(), "numberlist_bad.csv");
        String content = new String(Files.readAllBytes(badFile.toPath()), StandardCharsets.UTF_8);
        // drop the entry: the stored entry count no longer matches
        content = content.substring(0, content.indexOf("\"entry\""));
        Files.write(badFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        SourcesManager manager = newManager();
        SourcesManager.SourceInfo bad = manager.getSource("bad");
        assertNotNull(bad);
        assertTrue(bad.isLoadFailed());
        assertEquals("Bad", bad.getDisplayName());
        assertFalse(manager.getSource("good").isLoadFailed());
        assertEquals("good", manager.lookupListedNumbers(NUMBER).getSourceId());

        assertTrue(manager.deleteList("bad"));
        assertNull(manager.getSource("bad"));
    }

    @Test
    public void aggregatorUsesEnabledSourcesInOrder() throws IOException {
        store.save("bnetza", null, 1, entries(NUMBER, OTHER_NUMBER));
        SourcesManager manager = newManager();

        AggregatedResult result = manager.getAggregator().lookup(OTHER_NUMBER);
        // a trusted negative rating wins over the positive built-in rating
        assertEquals(ProviderResult.Rating.NEGATIVE, result.getRating());
        assertEquals("bnetza", result.getRatingSourceId());
        assertEquals(Arrays.asList("yacb", "bnetza"), result.getSourceIds());

        manager.setEnabled("bnetza", false);
        result = manager.getAggregator().lookup(OTHER_NUMBER);
        assertEquals(ProviderResult.Rating.POSITIVE, result.getRating());
        assertEquals(1, manager.getAggregator().getProviders().size());
    }

    @Test
    public void csvSourceIds() {
        assertEquals("csv_my_list", SourcesManager.csvSourceId("My List"));
        assertEquals("csv_spam-2024.v2", SourcesManager.csvSourceId(" spam-2024.v2 "));
        assertEquals("csv_list", SourcesManager.csvSourceId(null));
        assertEquals("csv_list", SourcesManager.csvSourceId("  "));

        String cyrillic = SourcesManager.csvSourceId("Спам");
        String cyrillic2 = SourcesManager.csvSourceId("Реклама");
        assertTrue(cyrillic, cyrillic.matches("csv_[0-9a-f]+"));
        assertFalse(cyrillic.equals(cyrillic2));

        String mixed = SourcesManager.csvSourceId("Спам list");
        assertTrue(mixed, mixed.matches("csv_list_[0-9a-f]+"));

        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; i++) longName.append('x');
        String id = SourcesManager.csvSourceId(longName.toString());
        assertEquals(SourcesManager.MAX_SOURCE_ID_LENGTH, id.length());

        String longLossy = SourcesManager.csvSourceId(longName + "ä");
        assertTrue(longLossy.length() <= SourcesManager.MAX_SOURCE_ID_LENGTH);

        // every generated id is accepted by the store
        for (String s : new String[]{id, longLossy, cyrillic, mixed}) {
            assertFalse(store.exists(s));
        }
    }

    @Test
    public void fileNameToDisplayName() {
        assertEquals("spam", SourcesManager.fileNameToDisplayName("spam.csv"));
        assertEquals("spam", SourcesManager.fileNameToDisplayName("spam.TXT"));
        assertEquals("spam.html", SourcesManager.fileNameToDisplayName("spam.html"));
        assertEquals(".csv", SourcesManager.fileNameToDisplayName(".csv"));
        assertNull(SourcesManager.fileNameToDisplayName("  "));
        assertNull(SourcesManager.fileNameToDisplayName(null));
    }

    /** Online provider, disabled by default, rating one number as negative. */
    static class OnlineProvider implements NumberInfoProvider {
        final AtomicInteger lookups = new AtomicInteger();

        @Override
        public String getId() {
            return "online";
        }

        @Override
        public String getDisplayName() {
            return "Online";
        }

        @Override
        public boolean isOffline() {
            return false;
        }

        @Override
        public boolean isEnabledByDefault() {
            return false;
        }

        @Override
        public ProviderResult lookup(String number) {
            lookups.incrementAndGet();
            return NUMBER.equals(number) ? new ProviderResult("online",
                    ProviderResult.Rating.NEGATIVE, "G_FRAUD", null, 42) : null;
        }
    }

    private SourcesManager newManagerWithOnline(OnlineProvider online, ExecutorService executor) {
        return new SourcesManager(store, prefs, Arrays.asList(new BuiltInProvider(), online),
                executor, new InMemoryResultCache(60_000), 2_000);
    }

    @Test
    public void onlineProviderIsDisabledByDefault() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OnlineProvider online = new OnlineProvider();
            SourcesManager manager = newManagerWithOnline(online, executor);

            assertEquals(Arrays.asList("yacb", "online"), ids(manager.getSources()));
            assertFalse(manager.isEnabled("online"));
            assertFalse(manager.getSource("online").isEnabled());
            assertTrue(manager.isEnabled("yacb"));
            assertFalse(manager.hasEnabledOnlineSources());
            assertNull(manager.lookupOnline(NUMBER));
            assertEquals(0, online.lookups.get());
            assertEquals(0, prefs.writes);

            manager.setEnabled("online", true);
            assertEquals("online", prefs.enabled);
            assertNull(prefs.disabled);
            assertTrue(manager.hasEnabledOnlineSources());

            ProviderResult result = manager.lookupOnline(NUMBER);
            assertNotNull(result);
            assertEquals("online", result.getSourceId());
            assertEquals("G_FRAUD", result.getCategory());
            assertNull(manager.lookupOnline(OTHER_NUMBER));
            assertEquals(2, online.lookups.get());

            // cached
            assertNotNull(manager.lookupOnline(NUMBER));
            assertEquals(2, online.lookups.get());

            // online sources are not used by the synchronous list lookup
            assertNull(manager.lookupListedNumbers(NUMBER));

            // persisted
            SourcesManager reloaded = newManagerWithOnline(new OnlineProvider(), executor);
            assertTrue(reloaded.isEnabled("online"));

            reloaded.setEnabled("online", false);
            assertEquals("", prefs.enabled);
            assertFalse(newManagerWithOnline(new OnlineProvider(), executor).isEnabled("online"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void onlineProviderWithoutExecutorIsNotQueried() {
        prefs.enabled = "online";
        OnlineProvider online = new OnlineProvider();
        SourcesManager manager = new SourcesManager(store, prefs,
                Arrays.asList(new BuiltInProvider(), online));

        assertTrue(manager.getSource("online").isEnabled());
        assertFalse(manager.hasEnabledOnlineSources());
        assertNull(manager.lookupOnline(NUMBER));
        assertFalse(manager.getAggregator().lookup(NUMBER).hasInfo());
        assertEquals(0, online.lookups.get());
    }

    @Test
    public void parseAndJoinIds() {
        assertEquals(Arrays.asList("a", "b"), SourcesManager.parseIds(" a,,b, a "));
        assertTrue(SourcesManager.parseIds(null).isEmpty());
        assertEquals("a,b", SourcesManager.joinIds(Arrays.asList("a", "b")));
    }

}
