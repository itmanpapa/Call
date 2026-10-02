package dummydomain.yetanothercallblocker.data.backup;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which settings go into a backup and how a restore replaces them.
 *
 * <p>All settings of the app are backed up except values that describe this device or
 * the state of the app rather than the user's choices (the keys are the
 * {@code Settings.PREF_*} constants). Secrets are only included when the user agrees.
 * Plain Java, no Android dependencies.</p>
 */
public final class BackupSettingsPolicy {

    /** Secrets: only backed up when the user agrees. */
    public static final Set<String> SECRET_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Collections.singletonList("phoneBlockToken")));

    /** Device-specific or runtime state, never backed up or replaced. */
    public static final Set<String> EXCLUDED_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    // derived from the blacklist and the rules, maintained by the app
                    "blacklistIsNotEmpty",
                    "rulesBlockingEnabled",
                    // state of the database updates
                    "lastUpdateTime",
                    "lastUpdateCheckTime",
                    // state of the PhoneBlock sync
                    "phoneBlockLastError",
                    "phoneBlockLastErrorTime",
                    // screen position of the caller ID card on this device
                    "callerIdOverlayY",
                    // state of the app update check (AppUpdateManager)
                    "appUpdateLastCheckTime",
                    "appUpdateLatestVersion",
                    "appUpdateNotifiedVersion",
                    // not persistent (the state lives in the system), listed for safety
                    "useCallScreeningService",
                    "autoUpdateEnabled",
                    // debugging
                    "saveCrashesToExternalStorage",
                    "saveLogcatOnCrash")));

    /** Keys starting with this are internal (e.g. the preferences version). */
    public static final String INTERNAL_PREFIX = "__";

    private BackupSettingsPolicy() {
    }

    /** @return whether the key is backed up at all (ignoring the secrets choice) */
    public static boolean isBackedUp(String key) {
        return key != null && !key.isEmpty() && !key.startsWith(INTERNAL_PREFIX)
                && !EXCLUDED_KEYS.contains(key);
    }

    public static boolean isSecret(String key) {
        return SECRET_KEYS.contains(key);
    }

    /** @return the values to back up, sorted by key */
    public static Map<String, Object> select(Map<String, ?> all, boolean includeSecrets) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : new TreeSet<>(all.keySet())) {
            if (!isBackedUp(key)) continue;
            if (isSecret(key) && !includeSecrets) continue;
            Object value = all.get(key);
            if (value != null) result.put(key, value);
        }
        return result;
    }

    /**
     * Computes a restore that replaces the backed up settings: keys of the backup are
     * set, other backed up keys are removed (back to their defaults). Excluded keys are
     * kept; a secret is only touched when the backup contains secrets. A value whose type
     * differs from the current value of the key is skipped (the current value is kept):
     * reading it later would throw a ClassCastException.
     *
     * @param current   the current values
     * @param fromBackup the values of the backup
     * @param backupHasSecrets {@link BackupBundle#includesSecrets()}
     * @param toSet     receives the values to set
     * @param toRemove  receives the keys to remove
     */
    public static void planRestore(Map<String, ?> current, Map<String, ?> fromBackup,
                                   boolean backupHasSecrets, Map<String, Object> toSet,
                                   Collection<String> toRemove) {
        for (Map.Entry<String, ?> e : fromBackup.entrySet()) {
            String key = e.getKey();
            if (!isBackedUp(key) || e.getValue() == null) continue;
            if (isSecret(key) && !backupHasSecrets) continue;
            Object currentValue = current.get(key);
            if (currentValue != null && !sameType(currentValue, e.getValue())) continue;
            toSet.put(key, e.getValue());
        }
        for (String key : current.keySet()) {
            if (!isBackedUp(key) || toSet.containsKey(key)) continue;
            if (fromBackup.get(key) != null && current.get(key) != null) continue; // type mismatch
            if (isSecret(key) && !backupHasSecrets) continue;
            toRemove.add(key);
        }
    }

    private static boolean sameType(Object a, Object b) {
        if (a instanceof java.util.Set) return b instanceof java.util.Set;
        return a.getClass() == b.getClass();
    }

}
