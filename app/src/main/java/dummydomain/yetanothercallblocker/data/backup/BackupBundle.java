package dummydomain.yetanothercallblocker.data.backup;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The contents of a backup: each part is kept in the format of its own store, so a
 * backup restores exactly what was saved (including fields only newer versions know).
 * A part that is null was not included.
 *
 * <p>Plain Java, no Android dependencies.</p>
 */
public final class BackupBundle {

    private long createdAt;
    private String appVersion;
    private boolean includesSecrets;

    private Map<String, Object> settings;
    private String blacklistCsv;
    private String rulesText;
    private String userMarksCsv;
    private final Map<String, byte[]> lists = new TreeMap<>();

    /** @return when the backup was created, millis since the epoch */
    public long getCreatedAt() {
        return createdAt;
    }

    public BackupBundle setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    /** @return the version name of the app that created the backup, may be null */
    public String getAppVersion() {
        return appVersion;
    }

    public BackupBundle setAppVersion(String appVersion) {
        this.appVersion = appVersion;
        return this;
    }

    /** @return whether secrets (the PhoneBlock API key) were included */
    public boolean includesSecrets() {
        return includesSecrets;
    }

    public BackupBundle setIncludesSecrets(boolean includesSecrets) {
        this.includesSecrets = includesSecrets;
        return this;
    }

    /** @return the settings (key to Boolean, Integer, Long, Float, String or Set), or null */
    public Map<String, Object> getSettings() {
        return settings != null ? Collections.unmodifiableMap(settings) : null;
    }

    public BackupBundle setSettings(Map<String, ?> settings) {
        this.settings = settings != null ? new LinkedHashMap<>(settings) : null;
        return this;
    }

    /** @return the blacklist in the format of the blacklist export (CSV), or null */
    public String getBlacklistCsv() {
        return blacklistCsv;
    }

    public BackupBundle setBlacklistCsv(String blacklistCsv) {
        this.blacklistCsv = blacklistCsv;
        return this;
    }

    /** @return the rules file ({@code RulesStore} format), or null */
    public String getRulesText() {
        return rulesText;
    }

    public BackupBundle setRulesText(String rulesText) {
        this.rulesText = rulesText;
        return this;
    }

    /** @return the user marks file ({@code UserMarksStore} format), or null */
    public String getUserMarksCsv() {
        return userMarksCsv;
    }

    public BackupBundle setUserMarksCsv(String userMarksCsv) {
        this.userMarksCsv = userMarksCsv;
        return this;
    }

    /** @return imported and downloaded lists: source id to file ({@code NumberListStore}) */
    public Map<String, byte[]> getLists() {
        return Collections.unmodifiableMap(lists);
    }

    public BackupBundle putList(String sourceId, byte[] content) {
        lists.put(Objects.requireNonNull(sourceId), Objects.requireNonNull(content));
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BackupBundle)) return false;
        BackupBundle that = (BackupBundle) o;
        if (lists.size() != that.lists.size()) return false;
        for (Map.Entry<String, byte[]> e : lists.entrySet()) {
            if (!Arrays.equals(e.getValue(), that.lists.get(e.getKey()))) return false;
        }
        return createdAt == that.createdAt
                && includesSecrets == that.includesSecrets
                && Objects.equals(appVersion, that.appVersion)
                && Objects.equals(settings, that.settings)
                && Objects.equals(blacklistCsv, that.blacklistCsv)
                && Objects.equals(rulesText, that.rulesText)
                && Objects.equals(userMarksCsv, that.userMarksCsv);
    }

    @Override
    public int hashCode() {
        return Objects.hash(createdAt, appVersion, includesSecrets, settings, blacklistCsv,
                rulesText, userMarksCsv, lists.keySet());
    }

    @Override
    public String toString() {
        return "BackupBundle{createdAt=" + createdAt + ", appVersion=" + appVersion
                + ", secrets=" + includesSecrets
                + ", settings=" + (settings != null ? settings.size() : "-")
                + ", blacklist=" + (blacklistCsv != null)
                + ", rules=" + (rulesText != null)
                + ", marks=" + (userMarksCsv != null)
                + ", lists=" + lists.keySet() + '}';
    }

}
