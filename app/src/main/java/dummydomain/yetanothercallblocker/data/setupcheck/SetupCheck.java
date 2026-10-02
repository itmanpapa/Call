package dummydomain.yetanothercallblocker.data.setupcheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.data.CallerIdOverlayPolicy;

import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Action;
import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Reason;
import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Status;
import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Type;

/**
 * "Is the protection working?": checks the call screening role, permissions,
 * notifications, blocking settings and the number sources, and explains each finding.
 * Plain Java; everything it needs comes from a {@link SetupCheckEnvironment}.
 */
public final class SetupCheck {

    /** Android 10: the Call Screening role. */
    static final int SDK_Q = 29;
    /** Android 13: the notification permission. */
    static final int SDK_TIRAMISU = 33;

    /** A source updated longer ago than this is reported as outdated. */
    public static final long STALE_AFTER_MILLIS = TimeUnit.DAYS.toMillis(14);

    private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);

    /** Outcome of a check. Immutable. */
    public static final class Result {

        private final List<CheckItem> items;
        private final int errorCount;
        private final int warningCount;

        Result(List<CheckItem> items) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            int errors = 0;
            int warnings = 0;
            for (CheckItem item : items) {
                if (item.getStatus() == Status.ERROR) errors++;
                else if (item.getStatus() == Status.WARNING) warnings++;
            }
            this.errorCount = errors;
            this.warningCount = warnings;
        }

        /** @return the items in display order */
        public List<CheckItem> getItems() {
            return items;
        }

        public int getErrorCount() {
            return errorCount;
        }

        public int getWarningCount() {
            return warningCount;
        }

        /** @return errors and warnings */
        public int getProblemCount() {
            return errorCount + warningCount;
        }

        /** @return the worst status of all items ({@link Status#INFO} counts as OK) */
        public Status getOverallStatus() {
            if (errorCount > 0) return Status.ERROR;
            if (warningCount > 0) return Status.WARNING;
            return Status.OK;
        }

        /** @return true if nothing prevents calls from being checked and blocked */
        public boolean isProtectionWorking() {
            return errorCount == 0;
        }

        /** @return the items with the given status */
        public List<CheckItem> getItems(Status status) {
            List<CheckItem> result = new ArrayList<>();
            for (CheckItem item : items) {
                if (item.getStatus() == status) result.add(item);
            }
            return result;
        }

        /**
         * @return a stable string that changes whenever the set of errors changes
         * (empty if there are no errors); used to show a dismissed banner again
         */
        public String getErrorSignature() {
            List<String> keys = new ArrayList<>();
            for (CheckItem item : getItems(Status.ERROR)) keys.add(item.getKey());
            Collections.sort(keys);
            return String.join(",", keys);
        }

        @Override
        public String toString() {
            return "Result{errors=" + errorCount + ", warnings=" + warningCount
                    + ", items=" + items + '}';
        }
    }

    private SetupCheck() {}

    /** Runs all checks. May do I/O through the environment. */
    public static Result run(SetupCheckEnvironment env) {
        boolean blocking = isBlockingEnabled(env);

        List<CheckItem> items = new ArrayList<>();
        items.add(checkCallScreening(env, blocking));
        items.add(checkPermissions(env, blocking));
        items.add(checkBlocking(env));
        items.add(checkNotifications(env, blocking));

        CheckItem overlay = checkOverlay(env);
        if (overlay != null) items.add(overlay);

        CheckItem battery = checkBattery(env);
        if (battery != null) items.add(battery);

        if (env.isUseContactsEnabled()) {
            items.add(new CheckItem(Type.CONTACTS, Status.INFO,
                    Reason.CONTACTS_NEVER_BLOCKED, Action.OPEN_SETTINGS));
        }

        items.addAll(checkSources(env));
        return new Result(items);
    }

    /** Mirrors {@code Settings.getCallBlockingEnabled()}. */
    static boolean isBlockingEnabled(SetupCheckEnvironment env) {
        return env.isBlockByRatingEnabled() || env.isBlockHiddenEnabled()
                || (env.isBlockBlacklistedEnabled() && !env.isBlacklistEmpty());
    }

    /** @return true if the system asks the app about each call before it rings */
    static boolean isScreeningActive(SetupCheckEnvironment env) {
        return env.getSdkInt() >= SDK_Q ? env.hasCallScreeningRole() : env.isDefaultDialer();
    }

    static CheckItem checkCallScreening(SetupCheckEnvironment env, boolean blocking) {
        Type type = Type.CALL_SCREENING;
        if (env.getSdkInt() >= SDK_Q) {
            if (env.hasCallScreeningRole()) {
                return new CheckItem(type, Status.OK, Reason.SCREENING_ROLE_HELD, Action.NONE);
            }
            // without the role calls can only be hung up after they started ringing
            return new CheckItem(type, blocking ? Status.ERROR : Status.WARNING,
                    Reason.SCREENING_ROLE_MISSING, Action.REQUEST_CALL_SCREENING);
        }

        if (env.isDefaultDialer()) {
            return new CheckItem(type, Status.OK, Reason.SCREENING_DEFAULT_DIALER, Action.NONE);
        }
        if (env.isMonitoringServiceEnabled()) {
            return new CheckItem(type, Status.OK, Reason.SCREENING_MONITORING_SERVICE,
                    Action.NONE);
        }
        // the phone state broadcast works, but the system may delay it
        return new CheckItem(type, Status.WARNING, Reason.SCREENING_LEGACY,
                Action.REQUEST_CALL_SCREENING);
    }

    static CheckItem checkPermissions(SetupCheckEnvironment env, boolean blocking) {
        Type type = Type.PERMISSIONS;
        if (!env.hasPhonePermissions()) {
            return new CheckItem(type, Status.ERROR, Reason.PERMISSIONS_PHONE_MISSING,
                    Action.REQUEST_PERMISSIONS);
        }
        // hanging up needs the permissions only when the system doesn't screen calls for us
        if (blocking && !isScreeningActive(env) && !env.hasCallControlPermissions()) {
            return new CheckItem(type, Status.ERROR, Reason.PERMISSIONS_CALL_CONTROL_MISSING,
                    Action.REQUEST_PERMISSIONS);
        }
        if (env.isUseContactsEnabled() && !env.hasContactsPermission()) {
            return new CheckItem(type, Status.WARNING, Reason.PERMISSIONS_CONTACTS_MISSING,
                    Action.REQUEST_PERMISSIONS);
        }
        return new CheckItem(type, Status.OK, Reason.PERMISSIONS_OK, Action.NONE);
    }

    static CheckItem checkBlocking(SetupCheckEnvironment env) {
        Type type = Type.BLOCKING;
        if (env.isBlockByRatingEnabled()) {
            return new CheckItem(type, Status.OK, Reason.BLOCKING_RATING, Action.NONE);
        }
        if (isBlockingEnabled(env)) {
            // only the blacklist and/or hidden numbers: spam from the databases rings through
            return new CheckItem(type, Status.WARNING, Reason.BLOCKING_NO_RATING,
                    Action.OPEN_SETTINGS);
        }
        if (env.isBlockBlacklistedEnabled()) {
            return new CheckItem(type, Status.ERROR, Reason.BLOCKING_EMPTY_BLACKLIST,
                    Action.OPEN_SETTINGS);
        }
        return new CheckItem(type, Status.ERROR, Reason.BLOCKING_OFF, Action.OPEN_SETTINGS);
    }

    static CheckItem checkNotifications(SetupCheckEnvironment env, boolean blocking) {
        Type type = Type.NOTIFICATIONS;
        boolean info = env.isIncomingCallNotificationsEnabled();

        if (info || blocking) {
            // nothing visible happens without notifications: the protection seems "dead"
            if (env.getSdkInt() >= SDK_TIRAMISU && !env.hasNotificationPermission()) {
                return new CheckItem(type, Status.WARNING,
                        Reason.NOTIFICATIONS_PERMISSION_MISSING,
                        Action.OPEN_NOTIFICATION_SETTINGS);
            }
            if (!env.areNotificationsEnabled()) {
                return new CheckItem(type, Status.WARNING, Reason.NOTIFICATIONS_APP_DISABLED,
                        Action.OPEN_NOTIFICATION_SETTINGS);
            }
            if (env.getBlockedCallChannelCount() > 0) {
                return new CheckItem(type, Status.WARNING,
                        Reason.NOTIFICATIONS_CHANNELS_BLOCKED,
                        Action.OPEN_NOTIFICATION_SETTINGS);
            }
        }
        if (!info) {
            return new CheckItem(type, Status.INFO, Reason.NOTIFICATIONS_SETTING_OFF,
                    Action.OPEN_SETTINGS);
        }
        return new CheckItem(type, Status.OK, Reason.NOTIFICATIONS_OK, Action.NONE);
    }

    /**
     * @return the caller ID card item, or null if the card is off (by its own setting or
     * because caller info is off)
     */
    static CheckItem checkOverlay(SetupCheckEnvironment env) {
        if (!env.isIncomingCallNotificationsEnabled() || !env.isCallerIdOverlayEnabled()) {
            return null;
        }
        if (CallerIdOverlayPolicy.isPermissionMissing(true, true, env.canDrawOverlays())) {
            // the notification still works, but the dialer's own heads-up hides it
            return new CheckItem(Type.OVERLAY, Status.WARNING,
                    Reason.OVERLAY_PERMISSION_MISSING, Action.REQUEST_OVERLAY_PERMISSION);
        }
        return new CheckItem(Type.OVERLAY, Status.OK, Reason.OVERLAY_OK, Action.NONE);
    }

    /**
     * @return the battery item, or null if battery optimization doesn't matter
     * (only the monitoring service runs permanently in the background)
     */
    static CheckItem checkBattery(SetupCheckEnvironment env) {
        if (!env.isMonitoringServiceEnabled()) return null;
        if (env.isIgnoringBatteryOptimizations()) {
            return new CheckItem(Type.BATTERY, Status.OK, Reason.BATTERY_EXEMPT,
                    Action.OPEN_BATTERY_SETTINGS);
        }
        return new CheckItem(Type.BATTERY, Status.INFO, Reason.BATTERY_OPTIMIZED,
                Action.OPEN_BATTERY_SETTINGS);
    }

    static List<CheckItem> checkSources(SetupCheckEnvironment env) {
        List<SetupCheckEnvironment.Source> sources = env.getSources();
        List<CheckItem> items = new ArrayList<>();

        boolean anyEnabled = false;
        for (SetupCheckEnvironment.Source source : sources) {
            if (source.isEnabled()) anyEnabled = true;
        }
        if (!anyEnabled) {
            // nothing can flag a number: "block by rating" has no effect
            items.add(new CheckItem(Type.SOURCES,
                    env.isBlockByRatingEnabled() ? Status.ERROR : Status.WARNING,
                    Reason.SOURCES_NONE_ENABLED, Action.OPEN_SOURCES));
        }

        for (SetupCheckEnvironment.Source source : sources) {
            items.add(checkSource(source, env.now()));
        }
        return items;
    }

    static CheckItem checkSource(SetupCheckEnvironment.Source source, long now) {
        long age = -1;
        if (source.getLastSuccessAt() > 0) {
            age = Math.max(0, now - source.getLastSuccessAt());
        }
        long ageDays = age >= 0 ? age / DAY_MILLIS : -1;

        Status status;
        Reason reason;
        Action action;
        if (!source.isEnabled()) {
            status = Status.INFO;
            reason = Reason.SOURCE_DISABLED;
            action = Action.OPEN_SOURCE;
        } else if (source.isRunning()) {
            status = Status.OK;
            reason = Reason.SOURCE_UPDATING;
            action = Action.NONE;
        } else if (!source.isConfigured()) {
            status = Status.INFO;
            reason = Reason.SOURCE_NOT_CONFIGURED;
            action = Action.OPEN_SOURCE;
        } else if (!source.hasData()) {
            status = Status.ERROR;
            reason = Reason.SOURCE_NEVER;
            action = source.isUpdatable() ? Action.UPDATE_SOURCE : Action.OPEN_SOURCE;
        } else if (source.getLastError() != null) {
            status = Status.WARNING;
            reason = Reason.SOURCE_ERROR;
            action = source.isUpdatable() ? Action.UPDATE_SOURCE : Action.OPEN_SOURCE;
        } else if (source.isUpdatable() && age > STALE_AFTER_MILLIS) {
            status = Status.WARNING;
            reason = Reason.SOURCE_STALE;
            action = Action.UPDATE_SOURCE;
        } else {
            status = Status.OK;
            reason = Reason.SOURCE_OK;
            action = Action.OPEN_SOURCE;
        }
        return new CheckItem(Type.SOURCE, status, reason, action, source, ageDays);
    }

}
