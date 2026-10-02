package dummydomain.yetanothercallblocker.data.setupcheck;

/**
 * One line of the setup check: what was checked, the outcome and how to fix it.
 * The texts are chosen by the UI from {@link #getReason()}. Immutable.
 */
public final class CheckItem {

    /** What is checked. */
    public enum Type {
        CALL_SCREENING,
        PERMISSIONS,
        BLOCKING,
        NOTIFICATIONS,
        /** The caller ID card over the incoming call screen. */
        OVERLAY,
        BATTERY,
        CONTACTS,
        /** No source is enabled at all. */
        SOURCES,
        /** One number source, see {@link #getSource()}. */
        SOURCE,
        /** A newer version of the app is available, see {@link #getDetail()}. */
        APP_UPDATE,
        /** Warnings about spam SMS (only listed when the setting is on). */
        SMS_WARNINGS
    }

    /** Outcome, from best to worst. Only warnings and errors count as problems. */
    public enum Status {
        OK,
        /** A hint, not a problem (e.g. "contacts are never blocked"). */
        INFO,
        WARNING,
        ERROR;

        public boolean isProblem() {
            return this == WARNING || this == ERROR;
        }
    }

    /** The exact finding; the UI has a title and an explanation for each. */
    public enum Reason {
        SCREENING_ROLE_HELD,
        SCREENING_ROLE_MISSING,
        SCREENING_DEFAULT_DIALER,
        SCREENING_MONITORING_SERVICE,
        SCREENING_LEGACY,

        PERMISSIONS_OK,
        PERMISSIONS_PHONE_MISSING,
        PERMISSIONS_CALL_CONTROL_MISSING,
        PERMISSIONS_CONTACTS_MISSING,

        BLOCKING_RATING,
        BLOCKING_NO_RATING,
        BLOCKING_EMPTY_BLACKLIST,
        BLOCKING_OFF,

        NOTIFICATIONS_OK,
        NOTIFICATIONS_PERMISSION_MISSING,
        NOTIFICATIONS_APP_DISABLED,
        NOTIFICATIONS_CHANNELS_BLOCKED,
        NOTIFICATIONS_SETTING_OFF,

        OVERLAY_OK,
        OVERLAY_PERMISSION_MISSING,

        BATTERY_EXEMPT,
        BATTERY_OPTIMIZED,

        CONTACTS_NEVER_BLOCKED,

        SOURCES_NONE_ENABLED,

        SOURCE_OK,
        SOURCE_DISABLED,
        SOURCE_UPDATING,
        SOURCE_NOT_CONFIGURED,
        SOURCE_NEVER,
        SOURCE_ERROR,
        SOURCE_STALE,

        APP_UPDATE_AVAILABLE,

        SMS_WARNINGS_ACTIVE,
        SMS_PERMISSION_MISSING
    }

    /** What the fix button does. */
    public enum Action {
        NONE,
        REQUEST_CALL_SCREENING,
        REQUEST_PERMISSIONS,
        OPEN_NOTIFICATION_SETTINGS,
        /** Open the system "display over other apps" screen of the app. */
        REQUEST_OVERLAY_PERMISSION,
        OPEN_SETTINGS,
        OPEN_BATTERY_SETTINGS,
        OPEN_SOURCES,
        /** Update the source now. */
        UPDATE_SOURCE,
        /** Open the details of the source (key, settings, switch). */
        OPEN_SOURCE,
        /** Open the app update screen. */
        OPEN_APP_UPDATE,
        /** Ask for the permission to receive SMS. */
        REQUEST_SMS_PERMISSION
    }

    private final Type type;
    private final Status status;
    private final Reason reason;
    private final Action action;
    private final SetupCheckEnvironment.Source source;
    private final long ageDays;
    private final String detail;

    CheckItem(Type type, Status status, Reason reason, Action action) {
        this(type, status, reason, action, null, -1);
    }

    CheckItem(Type type, Status status, Reason reason, Action action,
              SetupCheckEnvironment.Source source, long ageDays) {
        this(type, status, reason, action, source, ageDays, null);
    }

    CheckItem(Type type, Status status, Reason reason, Action action,
              SetupCheckEnvironment.Source source, long ageDays, String detail) {
        this.type = type;
        this.status = status;
        this.reason = reason;
        this.action = action;
        this.source = source;
        this.ageDays = ageDays;
        this.detail = detail;
    }

    public Type getType() {
        return type;
    }

    public Status getStatus() {
        return status;
    }

    public Reason getReason() {
        return reason;
    }

    public Action getAction() {
        return action;
    }

    /** @return the source of a {@link Type#SOURCE} item, null for other items */
    public SetupCheckEnvironment.Source getSource() {
        return source;
    }

    /**
     * @return full days since the last successful update of a source,
     * -1 if unknown or not a source item
     */
    public long getAgeDays() {
        return ageDays;
    }

    /**
     * @return an additional value for the text of the finding (the available version
     * of an {@link Type#APP_UPDATE} item), or null
     */
    public String getDetail() {
        return detail;
    }

    /** @return a stable key of the finding, used to remember dismissed warnings */
    public String getKey() {
        return type + ":" + reason + (source != null ? ":" + source.getId() : "");
    }

    @Override
    public String toString() {
        return "CheckItem{" + getKey() + ", " + status + ", action=" + action
                + (ageDays >= 0 ? ", ageDays=" + ageDays : "") + '}';
    }

}
