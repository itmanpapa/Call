package dummydomain.yetanothercallblocker.data.rules;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One-tap rules of the "Rules" screen.
 */
public final class RulePresets {

    /**
     * German ranges an incoming call from which is almost never legitimate, while calling
     * back costs money (numbering plan of the Bundesnetzagentur):
     * <ul>
     * <li>(0)900 - premium-rate services ("Premium-Rate-Dienste", up to 3 EUR/min or
     * 30 EUR per call); the successor of 0190;</li>
     * <li>(0)137 - mass-traffic services ("Massenverkehrsdienste", televoting, 0137-1..9,
     * fixed price per call); the classic "Lockanruf"/ping-call caller ID;</li>
     * <li>(0)180 - shared-cost service numbers ("Service-Dienste", 0180-1..7); hotlines
     * use them for incoming calls, so an outgoing call showing one is unusual - the only
     * entry with some risk of blocking a real company;</li>
     * <li>(0)190 - the former premium-rate range, switched off at the end of 2005;
     * a caller ID from it is certainly spoofed.</li>
     * </ul>
     * Deliberately not included: (0)700 personal numbers (normal subscribers),
     * 118xx directory enquiries and (0)12 innovative services (no outgoing calls seen
     * in practice), (0)32 national subscriber numbers.
     *
     * <p>The patterns are international, so they work for any home country; for a German
     * user a national "0900..." is matched through its international form.</p>
     */
    public static final List<String> GERMAN_PREMIUM_PATTERNS = Collections.unmodifiableList(
            Arrays.asList("+49900*", "+49137*", "+49180*", "+49190*"));

    public enum Preset {
        HIDDEN_NUMBERS(RuleType.HIDDEN_NUMBER),
        GERMAN_PREMIUM(RuleType.PREMIUM_DE),
        REPEATED_CALLER(RuleType.REPEATED_CALLER),
        FOREIGN_NUMBERS(RuleType.FOREIGN_NUMBER);

        public final RuleType type;

        Preset(RuleType type) {
            this.type = type;
        }
    }

    private RulePresets() {}

    /** @return a new (not yet added) rule for the preset */
    public static CallRule create(Preset preset) {
        switch (preset) {
            case HIDDEN_NUMBERS:
                return CallRule.builder(RuleType.HIDDEN_NUMBER).action(RuleAction.BLOCK).build();
            case GERMAN_PREMIUM:
                return CallRule.builder(RuleType.PREMIUM_DE).action(RuleAction.BLOCK).build();
            case REPEATED_CALLER:
                return CallRule.builder(RuleType.REPEATED_CALLER)
                        .repeatWindowMinutes(CallRule.DEFAULT_REPEAT_WINDOW_MINUTES).build();
            case FOREIGN_NUMBERS:
                return CallRule.builder(RuleType.FOREIGN_NUMBER).action(RuleAction.BLOCK)
                        .exceptContacts(true).build();
            default:
                throw new IllegalArgumentException("Unknown preset: " + preset);
        }
    }

    /** @return whether the list already has a rule of the preset's type */
    public static boolean isPresent(List<CallRule> rules, Preset preset) {
        for (CallRule rule : rules) {
            if (rule.getType() == preset.type) return true;
        }
        return false;
    }

}
