package dummydomain.yetanothercallblocker.data.rules;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One call rule (immutable). Rules are evaluated in list order, the first matching
 * enabled rule decides, see {@link RuleEngine}.
 */
public final class CallRule {

    public static final int DEFAULT_REPEAT_WINDOW_MINUTES = 3;
    public static final int MIN_REPEAT_WINDOW_MINUTES = 1;
    public static final int MAX_REPEAT_WINDOW_MINUTES = 60;

    public static final int MINUTES_PER_DAY = 24 * 60;

    /** Default schedule: the night. */
    public static final int DEFAULT_SCHEDULE_START = 22 * 60;
    public static final int DEFAULT_SCHEDULE_END = 7 * 60;

    private final long id;
    private final RuleType type;
    private final RuleAction action;
    private final boolean enabled;
    private final String patterns;
    private final boolean exceptContacts;
    private final int repeatWindowMinutes;
    private final boolean scheduleEnabled;
    private final int scheduleStart;
    private final int scheduleEnd;
    private final String label;

    // lazily compiled matcher of NUMBER_PATTERN / PREMIUM_DE rules (benign race)
    private Pattern compiledPatterns;

    private CallRule(Builder b) {
        this.id = b.id;
        this.type = Objects.requireNonNull(b.type, "type");
        this.action = type == RuleType.REPEATED_CALLER
                ? RuleAction.ALLOW : Objects.requireNonNull(b.action, "action");
        this.enabled = b.enabled;
        this.patterns = type == RuleType.NUMBER_PATTERN
                ? NumberPatterns.canonical(b.patterns) : "";
        this.exceptContacts = b.exceptContacts;
        this.repeatWindowMinutes = clamp(b.repeatWindowMinutes,
                MIN_REPEAT_WINDOW_MINUTES, MAX_REPEAT_WINDOW_MINUTES);
        this.scheduleEnabled = b.scheduleEnabled;
        this.scheduleStart = normalizeMinute(b.scheduleStart);
        this.scheduleEnd = normalizeMinute(b.scheduleEnd);
        this.label = b.label != null ? b.label.trim() : "";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int normalizeMinute(int minute) {
        return ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
    }

    public static Builder builder(RuleType type) {
        return new Builder(type);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Unique within the rule list, assigned by {@link RulesManager} (0 = not yet). */
    public long getId() {
        return id;
    }

    public RuleType getType() {
        return type;
    }

    public RuleAction getAction() {
        return action;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The patterns of a {@link RuleType#NUMBER_PATTERN} rule ("+44*, 0900*"), else "". */
    public String getPatterns() {
        return patterns;
    }

    /**
     * Whether a {@link RuleAction#BLOCK} rule spares the user's contacts (the default).
     * Contacts are only known when the "use contacts" setting is on.
     */
    public boolean isExceptContacts() {
        return exceptContacts;
    }

    /** The window of a {@link RuleType#REPEATED_CALLER} rule. */
    public int getRepeatWindowMinutes() {
        return repeatWindowMinutes;
    }

    public boolean isScheduleEnabled() {
        return scheduleEnabled;
    }

    /** Start of the schedule, minutes since midnight (inclusive). */
    public int getScheduleStart() {
        return scheduleStart;
    }

    /** End of the schedule, minutes since midnight (exclusive). */
    public int getScheduleEnd() {
        return scheduleEnd;
    }

    /** Optional user-given name, "" if none. */
    public String getLabel() {
        return label;
    }

    /**
     * @param minuteOfDay local time, minutes since midnight
     * @return whether the rule is active at that time: always without a schedule;
     * a schedule with start == end means the whole day; start > end spans midnight
     * (22:00-07:00)
     */
    public boolean isActiveAt(int minuteOfDay) {
        if (!scheduleEnabled || scheduleStart == scheduleEnd) return true;
        if (scheduleStart < scheduleEnd) {
            return minuteOfDay >= scheduleStart && minuteOfDay < scheduleEnd;
        }
        return minuteOfDay >= scheduleStart || minuteOfDay < scheduleEnd;
    }

    /** @return whether the rule can block calls when enabled */
    public boolean isBlocking() {
        return action == RuleAction.BLOCK;
    }

    /**
     * @return whether the rule has the data it needs (e.g. a valid pattern)
     */
    public boolean isValid() {
        if (type == RuleType.NUMBER_PATTERN) return NumberPatterns.isValidList(patterns);
        return true;
    }

    /** @return the matcher of the number patterns, or null for other types */
    Pattern getCompiledPatterns() {
        Pattern pattern = compiledPatterns;
        if (pattern == null) {
            if (type == RuleType.NUMBER_PATTERN) {
                pattern = NumberPatterns.compile(patterns);
            } else if (type == RuleType.PREMIUM_DE) {
                pattern = NumberPatterns.compile(RulePresets.GERMAN_PREMIUM_PATTERNS);
            }
            compiledPatterns = pattern;
        }
        return pattern;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CallRule)) return false;
        CallRule rule = (CallRule) o;
        return id == rule.id && enabled == rule.enabled
                && exceptContacts == rule.exceptContacts
                && repeatWindowMinutes == rule.repeatWindowMinutes
                && scheduleEnabled == rule.scheduleEnabled
                && scheduleStart == rule.scheduleStart
                && scheduleEnd == rule.scheduleEnd
                && type == rule.type && action == rule.action
                && patterns.equals(rule.patterns) && label.equals(rule.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, action, enabled, patterns, exceptContacts,
                repeatWindowMinutes, scheduleEnabled, scheduleStart, scheduleEnd, label);
    }

    @Override
    public String toString() {
        return "CallRule{id=" + id + ", " + type + ", " + action
                + (enabled ? "" : ", disabled")
                + (patterns.isEmpty() ? "" : ", patterns=" + patterns)
                + (exceptContacts ? "" : ", including contacts")
                + (type == RuleType.REPEATED_CALLER ? ", window=" + repeatWindowMinutes : "")
                + (scheduleEnabled ? ", schedule=" + scheduleStart + "-" + scheduleEnd : "")
                + (label.isEmpty() ? "" : ", label=" + label) + '}';
    }

    public static final class Builder {
        private long id;
        private RuleType type;
        private RuleAction action = RuleAction.BLOCK;
        private boolean enabled = true;
        private String patterns = "";
        private boolean exceptContacts = true;
        private int repeatWindowMinutes = DEFAULT_REPEAT_WINDOW_MINUTES;
        private boolean scheduleEnabled;
        private int scheduleStart = DEFAULT_SCHEDULE_START;
        private int scheduleEnd = DEFAULT_SCHEDULE_END;
        private String label = "";

        private Builder(RuleType type) {
            this.type = type;
            if (type == RuleType.REPEATED_CALLER) action = RuleAction.ALLOW;
        }

        private Builder(CallRule rule) {
            id = rule.id;
            type = rule.type;
            action = rule.action;
            enabled = rule.enabled;
            patterns = rule.patterns;
            exceptContacts = rule.exceptContacts;
            repeatWindowMinutes = rule.repeatWindowMinutes;
            scheduleEnabled = rule.scheduleEnabled;
            scheduleStart = rule.scheduleStart;
            scheduleEnd = rule.scheduleEnd;
            label = rule.label;
        }

        public Builder id(long id) {
            this.id = id;
            return this;
        }

        public Builder type(RuleType type) {
            this.type = type;
            return this;
        }

        public Builder action(RuleAction action) {
            this.action = action;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder patterns(String patterns) {
            this.patterns = patterns;
            return this;
        }

        public Builder exceptContacts(boolean exceptContacts) {
            this.exceptContacts = exceptContacts;
            return this;
        }

        public Builder repeatWindowMinutes(int minutes) {
            this.repeatWindowMinutes = minutes;
            return this;
        }

        public Builder schedule(boolean enabled, int startMinute, int endMinute) {
            this.scheduleEnabled = enabled;
            this.scheduleStart = startMinute;
            this.scheduleEnd = endMinute;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public CallRule build() {
            return new CallRule(this);
        }
    }

}
