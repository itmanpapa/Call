package dummydomain.yetanothercallblocker.data.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dummydomain.yetanothercallblocker.data.SourcesManager;
import dummydomain.yetanothercallblocker.data.UserMark;
import dummydomain.yetanothercallblocker.data.UserMarksStore;
import dummydomain.yetanothercallblocker.data.rules.CallRule;
import dummydomain.yetanothercallblocker.data.rules.RulesManager;
import dummydomain.yetanothercallblocker.data.rules.RulesStore;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;

/**
 * Applies a {@link BackupBundle} to the app's stores and reloads the in-memory managers:
 * <ul>
 *     <li>settings: replaced (see {@link BackupSettingsPolicy#planRestore}),</li>
 *     <li>imported lists: a list of the backup replaces the list with the same id,
 *     other lists are kept,</li>
 *     <li>rules: replaced,</li>
 *     <li>user marks: merged (the newer mark of a number wins),</li>
 *     <li>blacklist: merged (like the blacklist import).</li>
 * </ul>
 * <p>Each part is restored independently; a failed part is reported in the
 * {@link Result} and doesn't stop the others. Blocks on file I/O.</p>
 *
 * <p>Plain Java, no Android dependencies: the settings and the blacklist are reached
 * through small interfaces.</p>
 */
public class BackupRestorer {

    /** The app's settings. */
    public interface SettingsTarget {
        Map<String, ?> getAll();

        /** Writes the changes in one commit; @return whether they were written */
        boolean applyAll(Map<String, ?> toSet, Collection<String> toRemove);
    }

    /** The blacklist. */
    public interface BlacklistTarget {
        /**
         * Merges a blacklist export into the blacklist.
         *
         * @return the number of entries read, -1 if the data is not a blacklist export
         */
        int merge(byte[] csv) throws IOException;
    }

    /** Which parts to restore. */
    public static final class Options {
        public boolean settings = true;
        public boolean lists = true;
        public boolean rules = true;
        public boolean userMarks = true;
        public boolean blacklist = true;
    }

    public enum Part {
        SETTINGS, LISTS, RULES, USER_MARKS, BLACKLIST
    }

    /** What was restored. */
    public static final class Result {
        private final Map<Part, Integer> restored = new LinkedHashMap<>();
        private final Map<Part, Exception> failed = new LinkedHashMap<>();
        private final List<String> settingsChanged = new ArrayList<>();

        /** @return restored parts and their item counts */
        public Map<Part, Integer> getRestored() {
            return Collections.unmodifiableMap(restored);
        }

        /** @return failed parts and their errors */
        public Map<Part, Exception> getFailed() {
            return Collections.unmodifiableMap(failed);
        }

        public boolean isSuccess() {
            return failed.isEmpty();
        }

        /** @return the setting keys that were set or removed */
        public List<String> getSettingsChanged() {
            return Collections.unmodifiableList(settingsChanged);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(BackupRestorer.class);

    private final SettingsTarget settingsTarget;
    private final BlacklistTarget blacklistTarget;
    private final RulesManager rulesManager;
    private final UserMarksStore userMarksStore;
    private final SourcesManager sourcesManager;

    /** Any target may be null: that part is then skipped. */
    public BackupRestorer(SettingsTarget settingsTarget, BlacklistTarget blacklistTarget,
                          RulesManager rulesManager, UserMarksStore userMarksStore,
                          SourcesManager sourcesManager) {
        this.settingsTarget = settingsTarget;
        this.blacklistTarget = blacklistTarget;
        this.rulesManager = rulesManager;
        this.userMarksStore = userMarksStore;
        this.sourcesManager = sourcesManager;
    }

    public Result restore(BackupBundle bundle, Options options) {
        Result result = new Result();

        if (options.settings && bundle.getSettings() != null && settingsTarget != null) {
            try {
                Map<String, Object> toSet = new LinkedHashMap<>();
                List<String> toRemove = new ArrayList<>();
                BackupSettingsPolicy.planRestore(settingsTarget.getAll(), bundle.getSettings(),
                        bundle.includesSecrets(), toSet, toRemove);
                if (!settingsTarget.applyAll(toSet, toRemove)) {
                    throw new IOException("The settings could not be written");
                }
                result.settingsChanged.addAll(toSet.keySet());
                result.settingsChanged.addAll(toRemove);
                result.restored.put(Part.SETTINGS, toSet.size());
            } catch (Exception e) {
                fail(result, Part.SETTINGS, e);
            }
        }

        if (options.lists && !bundle.getLists().isEmpty() && sourcesManager != null) {
            try {
                result.restored.put(Part.LISTS, restoreLists(bundle));
            } catch (Exception e) {
                fail(result, Part.LISTS, e);
            }
        }
        if (sourcesManager != null && result.restored.containsKey(Part.SETTINGS)) {
            // the enabled flags and the order of the sources are settings
            sourcesManager.reloadPreferences();
        }

        if (options.rules && bundle.getRulesText() != null && rulesManager != null) {
            try {
                List<CallRule> rules = RulesStore.fromText(bundle.getRulesText());
                rulesManager.replaceAll(rules);
                result.restored.put(Part.RULES, rules.size());
            } catch (Exception e) {
                fail(result, Part.RULES, e);
            }
        }

        if (options.userMarks && bundle.getUserMarksCsv() != null && userMarksStore != null) {
            try {
                List<UserMark> marks = UserMarksStore.read(
                        new StringReader(bundle.getUserMarksCsv()));
                userMarksStore.merge(marks);
                result.restored.put(Part.USER_MARKS, marks.size());
            } catch (Exception e) {
                fail(result, Part.USER_MARKS, e);
            }
        }

        if (options.blacklist && bundle.getBlacklistCsv() != null && blacklistTarget != null) {
            try {
                int count = blacklistTarget.merge(
                        bundle.getBlacklistCsv().getBytes(StandardCharsets.UTF_8));
                if (count < 0) throw new IOException("Not a blacklist export");
                result.restored.put(Part.BLACKLIST, count);
            } catch (Exception e) {
                fail(result, Part.BLACKLIST, e);
            }
        }

        LOG.info("restore() restored {}, failed {}", result.restored, result.failed.keySet());
        return result;
    }

    private int restoreLists(BackupBundle bundle) throws IOException {
        int count = 0;
        IOException firstError = null;
        for (Map.Entry<String, byte[]> e : bundle.getLists().entrySet()) {
            String id = e.getKey();
            try (Reader reader = new InputStreamReader(new ByteArrayInputStream(e.getValue()),
                    StandardCharsets.UTF_8)) {
                NumberListStore.StoredList list = NumberListStore.parse(reader, id);
                NumberListStore.ListMetadata metadata = list.getMetadata();
                sourcesManager.importList(id, metadata.getDisplayName(), list.getEntries(),
                        metadata.getImportedAt(), metadata.getRemote());
                count++;
            } catch (IllegalArgumentException ex) {
                LOG.warn("restoreLists() skipping list {}", id, ex); // e.g. a reserved id
            } catch (IOException ex) {
                LOG.warn("restoreLists() failed to restore list {}", id, ex);
                if (firstError == null) firstError = ex;
            }
        }
        if (firstError != null) throw firstError;
        return count;
    }

    private static void fail(Result result, Part part, Exception e) {
        LOG.warn("restore() failed to restore {}", part, e);
        result.failed.put(part, e);
    }

}
