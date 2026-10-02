package dummydomain.yetanothercallblocker.data.rules;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Evaluates call rules: the first enabled, currently active rule that matches the call
 * wins. Pure function of its inputs, no I/O (except what {@link RecentCalls} does).
 *
 * <p>How a match is combined with contacts, the blacklist and ratings is decided by
 * {@code NumberInfoService}: a BLOCK rule acts like a blacklist entry, an ALLOW rule
 * beats ratings and number lists but not the explicit blacklist.</p>
 *
 * <p>Contacts: a BLOCK rule skips contacts unless the rule's "except contacts" option
 * is turned off; ALLOW rules match contacts as well (harmless, contacts are allowed
 * anyway).</p>
 */
public final class RuleEngine {

    /**
     * Calls closer than this to the current one are the same call seen twice (e.g. by the
     * call screening service and the phone state listener), not a repeated call.
     */
    public static final long MIN_REPEAT_GAP_MILLIS = 10_000;

    private RuleEngine() {}

    /**
     * @param rules       the rules in priority order
     * @param facts       the call
     * @param nowMillis   current time
     * @param minuteOfDay current local time, minutes since midnight (for schedules)
     * @param recentCalls earlier calls; null disables {@link RuleType#REPEATED_CALLER}
     *                    rules (e.g. when evaluating call log entries)
     * @return the first matching rule, or null
     */
    public static CallRule evaluate(List<CallRule> rules, CallFacts facts, long nowMillis,
                                    int minuteOfDay, RecentCalls recentCalls) {
        for (CallRule rule : rules) {
            if (!rule.isEnabled()) continue;
            if (!rule.isActiveAt(minuteOfDay)) continue;
            if (facts.contact && rule.isBlocking() && rule.isExceptContacts()) continue;

            if (matches(rule, facts, nowMillis, recentCalls)) return rule;
        }
        return null;
    }

    static boolean matches(CallRule rule, CallFacts facts, long nowMillis,
                           RecentCalls recentCalls) {
        switch (rule.getType()) {
            case HIDDEN_NUMBER:
                return facts.hidden;

            case NUMBER_PATTERN:
            case PREMIUM_DE:
                return !facts.hidden && matchesPatterns(rule.getCompiledPatterns(), facts.forms);

            case FOREIGN_NUMBER:
                return !facts.hidden && facts.forms.isForeign(facts.homeCallingCode);

            case REPEATED_CALLER:
                // only for unknown callers: contacts are let through anyway
                if (facts.hidden || facts.contact || recentCalls == null) return false;
                long window = rule.getRepeatWindowMinutes() * 60_000L;
                return recentCalls.hasCallBetween(facts.forms.key(),
                        nowMillis - window, nowMillis - MIN_REPEAT_GAP_MILLIS);

            default:
                return false;
        }
    }

    static boolean matchesPatterns(Pattern pattern, NumberForms forms) {
        if (pattern == null) return false;
        for (String form : forms.all()) {
            if (pattern.matcher(form).matches()) return true;
        }
        return false;
    }

}
