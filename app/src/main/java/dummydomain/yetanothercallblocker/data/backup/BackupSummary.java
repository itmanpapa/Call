package dummydomain.yetanothercallblocker.data.backup;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dummydomain.yetanothercallblocker.data.UserMarksStore;
import dummydomain.yetanothercallblocker.data.rules.RulesStore;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;

/**
 * What a backup contains, for the confirmation before a restore. Creating it parses
 * every part, so a damaged part is found before anything is changed.
 *
 * <p>Plain Java, no Android dependencies. Immutable.</p>
 */
public final class BackupSummary {

    /** A list in the backup. */
    public static final class ListInfo {
        private final String sourceId;
        private final String displayName;
        private final int entryCount;

        ListInfo(String sourceId, String displayName, int entryCount) {
            this.sourceId = sourceId;
            this.displayName = displayName;
            this.entryCount = entryCount;
        }

        public String getSourceId() {
            return sourceId;
        }

        /** @return the list name, the source id if it has none */
        public String getDisplayName() {
            return displayName != null && !displayName.isEmpty() ? displayName : sourceId;
        }

        public int getEntryCount() {
            return entryCount;
        }
    }

    private final long createdAt;
    private final String appVersion;
    private final int settingsCount;
    private final boolean hasPhoneBlockToken;
    private final int blacklistCount;
    private final int rulesCount;
    private final int userMarksCount;
    private final List<ListInfo> lists;

    private BackupSummary(long createdAt, String appVersion, int settingsCount,
                          boolean hasPhoneBlockToken, int blacklistCount, int rulesCount,
                          int userMarksCount, List<ListInfo> lists) {
        this.createdAt = createdAt;
        this.appVersion = appVersion;
        this.settingsCount = settingsCount;
        this.hasPhoneBlockToken = hasPhoneBlockToken;
        this.blacklistCount = blacklistCount;
        this.rulesCount = rulesCount;
        this.userMarksCount = userMarksCount;
        this.lists = Collections.unmodifiableList(lists);
    }

    /**
     * @throws BackupException if a part can't be parsed
     */
    public static BackupSummary of(BackupBundle bundle) throws BackupException {
        int settingsCount = -1;
        boolean token = false;
        Map<String, Object> settings = bundle.getSettings();
        if (settings != null) {
            settingsCount = settings.size();
            Object value = settings.get("phoneBlockToken");
            token = value instanceof String && !((String) value).trim().isEmpty();
        }

        int blacklistCount = -1;
        if (bundle.getBlacklistCsv() != null) {
            blacklistCount = countBlacklist(bundle.getBlacklistCsv());
        }

        int rulesCount = -1;
        if (bundle.getRulesText() != null) {
            try {
                rulesCount = RulesStore.fromText(bundle.getRulesText()).size();
            } catch (IOException e) {
                throw corrupt("rules", e);
            }
        }

        int marksCount = -1;
        if (bundle.getUserMarksCsv() != null) {
            try {
                marksCount = UserMarksStore.read(new StringReader(bundle.getUserMarksCsv())).size();
            } catch (IOException | RuntimeException e) {
                throw corrupt("user marks", e);
            }
        }

        List<ListInfo> lists = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : bundle.getLists().entrySet()) {
            try (Reader reader = new InputStreamReader(new ByteArrayInputStream(e.getValue()),
                    StandardCharsets.UTF_8)) {
                NumberListStore.StoredList list = NumberListStore.parse(reader, e.getKey());
                lists.add(new ListInfo(e.getKey(), list.getMetadata().getDisplayName(),
                        list.getEntries().size()));
            } catch (IOException | RuntimeException ex) {
                throw corrupt("list " + e.getKey(), ex);
            }
        }

        return new BackupSummary(bundle.getCreatedAt(), bundle.getAppVersion(), settingsCount,
                token, blacklistCount, rulesCount, marksCount, lists);
    }

    /** @return the number of blacklist entries (records without the header line) */
    static int countBlacklist(String csv) throws BackupException {
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(csv))) {
            int count = 0;
            boolean first = true;
            for (CSVRecord record : parser) {
                if (first) {
                    first = false;
                    if (record.size() >= 3 && "ID".equals(record.get(0))
                            && "name".equals(record.get(1)) && "pattern".equals(record.get(2))) {
                        continue;
                    }
                }
                if (record.size() == 1 && record.get(0).isEmpty()) continue;
                count++;
            }
            return count;
        } catch (IOException | RuntimeException e) {
            throw corrupt("blacklist", e);
        }
    }

    private static BackupException corrupt(String part, Exception e) {
        return new BackupException(BackupException.Reason.CORRUPT,
                "Bad " + part + ": " + e.getMessage(), e);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /** @return the version of the app that created the backup, may be null */
    public String getAppVersion() {
        return appVersion;
    }

    /** @return the number of settings, -1 if not included */
    public int getSettingsCount() {
        return settingsCount;
    }

    public boolean hasSettings() {
        return settingsCount >= 0;
    }

    /** @return whether the backup contains a PhoneBlock API key */
    public boolean hasPhoneBlockToken() {
        return hasPhoneBlockToken;
    }

    /** @return the number of blacklist entries, -1 if not included */
    public int getBlacklistCount() {
        return blacklistCount;
    }

    public boolean hasBlacklist() {
        return blacklistCount >= 0;
    }

    /** @return the number of rules, -1 if not included */
    public int getRulesCount() {
        return rulesCount;
    }

    public boolean hasRules() {
        return rulesCount >= 0;
    }

    /** @return the number of user marks, -1 if not included */
    public int getUserMarksCount() {
        return userMarksCount;
    }

    public boolean hasUserMarks() {
        return userMarksCount >= 0;
    }

    public List<ListInfo> getLists() {
        return lists;
    }

    public boolean hasLists() {
        return !lists.isEmpty();
    }

}
