package dummydomain.yetanothercallblocker.data.rules;

/**
 * Kinds of call rules. The names are persisted (see {@link RulesStore}), don't rename them.
 */
public enum RuleType {

    /** The number matches one of the user's patterns ("+44*", "0900*", "+49137*"). */
    NUMBER_PATTERN,

    /** The caller ID is hidden (private, anonymous, unavailable). */
    HIDDEN_NUMBER,

    /** The country calling code of the number differs from the user's one. */
    FOREIGN_NUMBER,

    /** German premium-rate and similar ranges, see {@link RulePresets#GERMAN_PREMIUM_PATTERNS}. */
    PREMIUM_DE,

    /**
     * The same number already called within the last N minutes ("if it's urgent, they
     * call twice"). Always an {@link RuleAction#ALLOW} rule.
     */
    REPEATED_CALLER;

    /** @return the type with the name, or null for unknown (newer) types */
    public static RuleType fromName(String name) {
        if (name == null) return null;
        for (RuleType type : values()) {
            if (type.name().equals(name)) return type;
        }
        return null;
    }

}
