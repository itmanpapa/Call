package dummydomain.yetanothercallblocker.data.backup;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dummydomain.yetanothercallblocker.data.SourcesManager;
import dummydomain.yetanothercallblocker.data.UserMark;
import dummydomain.yetanothercallblocker.data.UserMarksStore;
import dummydomain.yetanothercallblocker.data.rules.CallRule;
import dummydomain.yetanothercallblocker.data.rules.RuleAction;
import dummydomain.yetanothercallblocker.data.rules.RuleType;
import dummydomain.yetanothercallblocker.data.rules.RulesManager;
import dummydomain.yetanothercallblocker.data.rules.RulesStore;
import dummydomain.yetanothercallblocker.data.sources.ListedNumber;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;
import dummydomain.yetanothercallblocker.data.sources.RemoteListInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Creates a backup from real stores, restores it into fresh stores and compares. */
public class BackupRestoreTest {

    private static final long NOW = 1_790_000_000_000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Settings in memory, like SharedPreferences. */
    static class MemorySettings implements BackupRestorer.SettingsTarget,
            SourcesManager.Preferences {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public boolean applyAll(Map<String, ?> toSet, Collection<String> toRemove) {
            for (String key : toRemove) values.remove(key);
            values.putAll(toSet);
            return true;
        }

        @Override
        public String getSourcesOrder() {
            return (String) values.get("sourcesOrder");
        }

        @Override
        public void setSourcesOrder(String order) {
            values.put("sourcesOrder", order);
        }

        @Override
        public String getDisabledSources() {
            return (String) values.get("sourcesDisabled");
        }

        @Override
        public void setDisabledSources(String ids) {
            values.put("sourcesDisabled", ids);
        }

        @Override
        public String getEnabledSources() {
            return (String) values.get("sourcesEnabled");
        }

        @Override
        public void setEnabledSources(String ids) {
            values.put("sourcesEnabled", ids);
        }
    }

    /** The blacklist as the raw export lines. */
    static class MemoryBlacklist implements BackupRestorer.BlacklistTarget {
        final List<String> merged = new ArrayList<>();

        @Override
        public int merge(byte[] csv) {
            String text = new String(csv, StandardCharsets.UTF_8);
            if (!text.startsWith("ID,name,pattern")) return -1;
            String[] lines = text.split("\r?\n");
            merged.addAll(Arrays.asList(lines).subList(1, lines.length));
            return lines.length - 1;
        }
    }

    /** One app instance: the stores in a directory. */
    class Instance {
        final MemorySettings settings = new MemorySettings();
        final MemoryBlacklist blacklist = new MemoryBlacklist();
        final NumberListStore listStore;
        final SourcesManager sourcesManager;
        final RulesManager rulesManager;
        final UserMarksStore marks;
        final File marksFile;

        Instance(String name) throws IOException {
            File dir = folder.newFolder(name);
            listStore = new NumberListStore(new File(dir, "lists"));
            sourcesManager = new SourcesManager(listStore, settings, Collections.emptyList());
            rulesManager = new RulesManager(new RulesStore(new File(dir, "rules/rules.txt")),
                    RulesManager.SYSTEM_CLOCK, null);
            marksFile = new File(dir, "user_marks.csv");
            marks = new UserMarksStore(marksFile);
        }

        BackupRestorer restorer() {
            return new BackupRestorer(settings, blacklist, rulesManager, marks, sourcesManager);
        }
    }

    private Instance source;

    @Before
    public void setUp() throws IOException {
        source = new Instance("source");

        source.settings.values.put("blockHiddenNumbers", true);
        source.settings.values.put("callLogGrouping", "day");
        source.settings.values.put("phoneBlockToken", "secret");
        source.settings.values.put("lastUpdateTime", 123L);         // excluded
        source.settings.values.put("__preferencesVersion", 2);      // internal

        source.sourcesManager.importList("csv_mine", "My list", Collections.singletonList(
                ListedNumber.builder().number("+4915112345678").name("Spam").build()), NOW);
        source.sourcesManager.importList("url_list", "Remote", Collections.singletonList(
                ListedNumber.builder().prefix("+49900").build()), NOW,
                new RemoteListInfo("https://example.org/list.csv", true));
        source.sourcesManager.setEnabled("url_list", false);

        source.rulesManager.add(CallRule.builder(RuleType.HIDDEN_NUMBER)
                .action(RuleAction.BLOCK).build());
        source.rulesManager.add(CallRule.builder(RuleType.NUMBER_PATTERN)
                .action(RuleAction.BLOCK).patterns("+44*").label("UK").build());

        source.marks.set("+4930123456", UserMark.Type.SPAM, NOW - 1000, "note");
        source.marks.set("+4989123456", UserMark.Type.NOT_SPAM, NOW - 2000, null);
    }

    private BackupBundle create(boolean secrets) throws IOException {
        BackupBundle bundle = BackupCreator.create(source.settings.getAll(), secrets,
                "ID,name,pattern,creationTimestamp,numberOfCalls,lastCallTimestamp\n"
                        + "1,Spam,+4930*,1700000000000,3,\n",
                source.rulesManager.getRules(), source.marksFile, source.listStore,
                "0.12.0", NOW);
        // through the file format
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupArchive.write(bundle, out);
        return BackupArchive.read(new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    public void createSelectsSettings() throws IOException {
        BackupBundle bundle = create(true);
        Map<String, Object> settings = bundle.getSettings();
        assertEquals(true, settings.get("blockHiddenNumbers"));
        assertEquals("secret", settings.get("phoneBlockToken"));
        assertFalse(settings.containsKey("lastUpdateTime"));
        assertFalse(settings.containsKey("__preferencesVersion"));
        assertEquals("url_list", settings.get("sourcesDisabled"));

        assertFalse(create(false).getSettings().containsKey("phoneBlockToken"));
        assertFalse(create(false).includesSecrets());
    }

    @Test
    public void summary() throws IOException {
        BackupSummary summary = BackupSummary.of(create(true));
        assertEquals(NOW, summary.getCreatedAt());
        assertEquals("0.12.0", summary.getAppVersion());
        assertTrue(summary.hasPhoneBlockToken());
        assertEquals(1, summary.getBlacklistCount());
        assertEquals(2, summary.getRulesCount());
        assertEquals(2, summary.getUserMarksCount());
        assertEquals(2, summary.getLists().size());
        assertEquals("My list", summary.getLists().get(0).getDisplayName());
        assertEquals(1, summary.getLists().get(0).getEntryCount());
        assertTrue(summary.getSettingsCount() >= 4);

        assertFalse(BackupSummary.of(create(false)).hasPhoneBlockToken());
    }

    @Test
    public void summaryDetectsDamagedParts() throws IOException {
        BackupBundle bundle = create(true).setRulesText("not rules");
        try {
            BackupSummary.of(bundle);
            fail();
        } catch (BackupException e) {
            assertEquals(BackupException.Reason.CORRUPT, e.getReason());
        }

        bundle = create(true).putList("csv_mine", "garbage".getBytes(StandardCharsets.UTF_8));
        try {
            BackupSummary.of(bundle);
            fail();
        } catch (BackupException e) {
            assertEquals(BackupException.Reason.CORRUPT, e.getReason());
        }
    }

    @Test
    public void restoreIntoEmptyInstance() throws IOException {
        BackupBundle bundle = create(true);
        Instance target = new Instance("target");

        BackupRestorer.Result result = target.restorer().restore(bundle,
                new BackupRestorer.Options());
        assertTrue(result.getFailed().toString(), result.isSuccess());

        // settings
        assertEquals(true, target.settings.values.get("blockHiddenNumbers"));
        assertEquals("day", target.settings.values.get("callLogGrouping"));
        assertEquals("secret", target.settings.values.get("phoneBlockToken"));
        assertNull(target.settings.values.get("lastUpdateTime"));

        // lists, with their state and the enabled flags of the settings
        assertEquals(Arrays.asList("csv_mine", "url_list"), target.listStore.listSourceIds());
        assertEquals("My list", target.sourcesManager.getDisplayName("csv_mine"));
        assertTrue(target.sourcesManager.isEnabled("csv_mine"));
        assertFalse(target.sourcesManager.isEnabled("url_list"));
        NumberListStore.ListMetadata remote = target.listStore.loadMetadata("url_list");
        assertEquals("https://example.org/list.csv", remote.getRemote().getUrl());
        assertNotNull(target.sourcesManager.lookupListedNumbers("+4915112345678"));

        // rules, in order
        List<CallRule> rules = target.rulesManager.getRules();
        assertEquals(2, rules.size());
        assertEquals(RuleType.HIDDEN_NUMBER, rules.get(0).getType());
        assertEquals("UK", rules.get(1).getLabel());
        // persisted
        assertEquals(2, new RulesStore(new File(folder.getRoot(), "target/rules/rules.txt"))
                .load().size());

        // marks
        assertEquals(UserMark.Type.SPAM, target.marks.get("+4930123456").getType());
        assertEquals("note", target.marks.get("+4930123456").getNote());
        assertEquals(2, new UserMarksStore(target.marksFile).size());

        // blacklist
        assertEquals(Collections.singletonList("1,Spam,+4930*,1700000000000,3,"),
                target.blacklist.merged);

        assertEquals(Integer.valueOf(2), result.getRestored().get(BackupRestorer.Part.LISTS));
        assertEquals(Integer.valueOf(2), result.getRestored().get(BackupRestorer.Part.RULES));
    }

    @Test
    public void settingsAndRulesAreReplacedMarksAreMerged() throws IOException {
        BackupBundle bundle = create(false);
        Instance target = new Instance("target");

        target.settings.values.put("blockHiddenNumbers", false);
        target.settings.values.put("useContacts", true);          // not in the backup
        target.settings.values.put("phoneBlockToken", "mine");    // backup has no secrets
        target.settings.values.put("lastUpdateTime", 999L);       // excluded

        target.rulesManager.add(CallRule.builder(RuleType.FOREIGN_NUMBER)
                .action(RuleAction.BLOCK).build());

        target.marks.set("+4930123456", UserMark.Type.NOT_SPAM, NOW, null);   // newer
        target.marks.set("+4989123456", UserMark.Type.SPAM, NOW - 5000, null); // older
        target.marks.set("+4940555000", UserMark.Type.SPAM, NOW, null);        // only here

        target.sourcesManager.importList("csv_other", "Other", Collections.singletonList(
                ListedNumber.builder().number("+4940111222").build()), NOW);

        BackupRestorer.Result result = target.restorer().restore(bundle,
                new BackupRestorer.Options());
        assertTrue(result.isSuccess());

        assertEquals(true, target.settings.values.get("blockHiddenNumbers"));
        assertFalse(target.settings.values.containsKey("useContacts"));
        assertEquals("mine", target.settings.values.get("phoneBlockToken"));
        assertEquals(999L, target.settings.values.get("lastUpdateTime"));

        assertEquals(2, target.rulesManager.getRules().size());
        assertEquals(RuleType.HIDDEN_NUMBER, target.rulesManager.getRules().get(0).getType());

        assertEquals(UserMark.Type.NOT_SPAM, target.marks.get("+4930123456").getType());
        assertEquals(UserMark.Type.NOT_SPAM, target.marks.get("+4989123456").getType());
        assertEquals(UserMark.Type.SPAM, target.marks.get("+4940555000").getType());

        // lists are merged by id
        assertEquals(Arrays.asList("csv_mine", "csv_other", "url_list"),
                target.listStore.listSourceIds());
    }

    @Test
    public void onlySelectedPartsAreRestored() throws IOException {
        BackupBundle bundle = create(true);
        Instance target = new Instance("target");

        BackupRestorer.Options options = new BackupRestorer.Options();
        options.settings = false;
        options.lists = false;
        options.blacklist = false;
        BackupRestorer.Result result = target.restorer().restore(bundle, options);

        assertTrue(result.isSuccess());
        assertTrue(target.settings.values.isEmpty());
        assertTrue(target.listStore.listSourceIds().isEmpty());
        assertTrue(target.blacklist.merged.isEmpty());
        assertEquals(2, target.rulesManager.getRules().size());
        assertEquals(2, target.marks.size());
    }

    @Test
    public void failedPartDoesNotStopOthers() throws IOException {
        BackupBundle bundle = create(true).setBlacklistCsv("garbage");
        Instance target = new Instance("target");

        BackupRestorer.Result result = target.restorer().restore(bundle,
                new BackupRestorer.Options());
        assertFalse(result.isSuccess());
        assertTrue(result.getFailed().containsKey(BackupRestorer.Part.BLACKLIST));
        assertEquals(2, target.rulesManager.getRules().size());
        assertEquals(2, target.marks.size());
    }

}
