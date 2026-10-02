package dummydomain.yetanothercallblocker.data.rules;

/**
 * What a matching rule does with the call. The names are persisted, don't rename them.
 */
public enum RuleAction {

    /** Reject the call, like a blacklist entry. */
    BLOCK,

    /** Let the call through even if a rating or a number list would block it. */
    ALLOW;

    /** @return the action with the name, or null if unknown */
    public static RuleAction fromName(String name) {
        if (name == null) return null;
        for (RuleAction action : values()) {
            if (action.name().equals(name)) return action;
        }
        return null;
    }

}
