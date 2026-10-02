package dummydomain.yetanothercallblocker.data.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import dummydomain.yetanothercallblocker.data.UserMarksStore;
import dummydomain.yetanothercallblocker.data.rules.CallRule;
import dummydomain.yetanothercallblocker.data.rules.RulesStore;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;

/**
 * Collects the user's data into a {@link BackupBundle}. Blocks on file I/O.
 *
 * <p>Plain Java, no Android dependencies: the app passes the settings and the blacklist
 * export, the rest is read from the stores.</p>
 */
public final class BackupCreator {

    private static final Logger LOG = LoggerFactory.getLogger(BackupCreator.class);

    /** Content of an empty user marks file. */
    static final String EMPTY_USER_MARKS = "format," + UserMarksStore.FORMAT_NAME + ","
            + UserMarksStore.FORMAT_VERSION + "\n";

    private BackupCreator() {
    }

    /**
     * @param allSettings    all stored settings (filtered by {@link BackupSettingsPolicy})
     * @param includeSecrets whether to include the PhoneBlock API key
     * @param blacklistCsv   the blacklist in the export format, null to leave it out
     * @param rules          the current rules, null to leave them out
     * @param userMarksFile  the file of the user marks (may not exist), null to leave out
     * @param lists          the store of imported lists, null to leave them out
     * @param appVersion     the version name of the app
     * @param now            the creation time, millis
     */
    public static BackupBundle create(Map<String, ?> allSettings, boolean includeSecrets,
                                      String blacklistCsv, List<CallRule> rules,
                                      File userMarksFile, NumberListStore lists,
                                      String appVersion, long now) throws IOException {
        BackupBundle bundle = new BackupBundle()
                .setCreatedAt(now)
                .setAppVersion(appVersion)
                .setIncludesSecrets(includeSecrets);

        if (allSettings != null) {
            bundle.setSettings(BackupSettingsPolicy.select(allSettings, includeSecrets));
        }
        bundle.setBlacklistCsv(blacklistCsv);
        if (rules != null) bundle.setRulesText(RulesStore.toText(rules));

        if (userMarksFile != null) {
            bundle.setUserMarksCsv(userMarksFile.isFile()
                    ? new String(Files.readAllBytes(userMarksFile.toPath()),
                    StandardCharsets.UTF_8)
                    : EMPTY_USER_MARKS);
        }

        if (lists != null) {
            for (String id : lists.listSourceIds()) {
                File file = lists.getFile(id);
                try {
                    bundle.putList(id, Files.readAllBytes(file.toPath()));
                } catch (IOException e) {
                    // deleted concurrently: nothing to back up
                    if (file.exists()) throw e;
                    LOG.debug("create() list {} disappeared", id);
                }
            }
        }

        return bundle;
    }

}
