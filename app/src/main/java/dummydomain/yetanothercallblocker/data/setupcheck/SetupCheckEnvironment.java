package dummydomain.yetanothercallblocker.data.setupcheck;

import java.util.List;

/**
 * Everything {@link SetupCheck} needs to know about the device and the app settings.
 * The Android implementation reads the system services and the preferences; tests use a
 * plain fake. Methods may do I/O (sources), so call them on a background thread.
 */
public interface SetupCheckEnvironment {

    /** {@code Build.VERSION.SDK_INT}. */
    int getSdkInt();

    /** Current time, millis since the epoch. */
    long now();

    // call screening

    /**
     * @return true if the app holds the Call Screening role (Android 10+); also true
     * if the system lets the app screen calls because it is the default phone app
     */
    boolean hasCallScreeningRole();

    /** @return true if the app is the default phone app (Android 7–9 call screening) */
    boolean isDefaultDialer();

    /** @return true if the "monitoring service" setting is on */
    boolean isMonitoringServiceEnabled();

    // permissions

    /** @return true if READ_PHONE_STATE and READ_CALL_LOG are granted */
    boolean hasPhonePermissions();

    /** @return true if the permissions to hang up a call (CALL_PHONE, ANSWER_PHONE_CALLS) are granted */
    boolean hasCallControlPermissions();

    /** @return true if READ_CONTACTS is granted */
    boolean hasContactsPermission();

    /** @return true if POST_NOTIFICATIONS is granted (always true below Android 13) */
    boolean hasNotificationPermission();

    /** @return false if the user turned off all notifications of the app */
    boolean areNotificationsEnabled();

    /**
     * @return the number of notification channels for incoming and blocked calls
     * the user turned off (0 below Android 8)
     */
    int getBlockedCallChannelCount();

    // app settings

    /** @return the "show caller info" setting */
    boolean isIncomingCallNotificationsEnabled();

    /** @return the "caller ID card over the call screen" setting */
    boolean isCallerIdOverlayEnabled();

    /** @return true if the app may draw over other apps ("display over other apps") */
    boolean canDrawOverlays();

    /** @return the "block by rating" setting (numbers flagged by the databases) */
    boolean isBlockByRatingEnabled();

    /** @return the "block hidden numbers" setting */
    boolean isBlockHiddenEnabled();

    /** @return the "block blacklisted numbers" setting */
    boolean isBlockBlacklistedEnabled();

    /** @return true if the blacklist has no entries */
    boolean isBlacklistEmpty();

    /** @return the "use contacts" setting */
    boolean isUseContactsEnabled();

    /** @return true if the app is exempt from battery optimization */
    boolean isIgnoringBatteryOptimizations();

    /** @return the number sources in display order */
    List<Source> getSources();

    /**
     * @return the version of a newer app release found by the last update check,
     * or null if none is known
     */
    default String getAvailableAppUpdate() {
        return null;
    }

    /** State of one number source, as far as the check is concerned. Immutable. */
    final class Source {

        private final String id;
        private final String name;
        private final boolean enabled;
        private final boolean configured;
        private final boolean hasData;
        private final boolean updatable;
        private final boolean running;
        private final long lastSuccessAt;
        private final String lastError;

        /**
         * @param configured    false if the source can't work yet (e.g. no API key)
         * @param hasData       true if numbers of this source are available offline
         * @param updatable     true if the app can update the source (false for files)
         * @param lastSuccessAt last successful update or check, 0 if unknown
         * @param lastError     error of the last update attempt if it failed, or null
         */
        public Source(String id, String name, boolean enabled, boolean configured,
                      boolean hasData, boolean updatable, boolean running,
                      long lastSuccessAt, String lastError) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.configured = configured;
            this.hasData = hasData;
            this.updatable = updatable;
            this.running = running;
            this.lastSuccessAt = lastSuccessAt;
            this.lastError = lastError;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isConfigured() {
            return configured;
        }

        public boolean hasData() {
            return hasData;
        }

        public boolean isUpdatable() {
            return updatable;
        }

        public boolean isRunning() {
            return running;
        }

        public long getLastSuccessAt() {
            return lastSuccessAt;
        }

        public String getLastError() {
            return lastError;
        }

        @Override
        public String toString() {
            return "Source{id='" + id + "', enabled=" + enabled + ", configured=" + configured
                    + ", hasData=" + hasData + ", updatable=" + updatable
                    + ", running=" + running + ", lastSuccessAt=" + lastSuccessAt
                    + ", lastError='" + lastError + "'}";
        }
    }

}
