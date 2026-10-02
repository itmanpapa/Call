package dummydomain.yetanothercallblocker.data.rules;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RuleEngineTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final int NOON = 12 * 60;
    private static final long MINUTE = 60_000L;

    private static CallFacts call(String number) {
        return new CallFacts(number, null, false, false, "49");
    }

    private static CallFacts contactCall(String number) {
        return new CallFacts(number, null, false, true, "49");
    }

    private static CallFacts hiddenCall() {
        return new CallFacts("", null, true, false, "49");
    }

    private static CallRule pattern(long id, String patterns, RuleAction action) {
        return CallRule.builder(RuleType.NUMBER_PATTERN).id(id).patterns(patterns)
                .action(action).build();
    }

    private static CallRule evaluate(List<CallRule> rules, CallFacts facts) {
        return RuleEngine.evaluate(rules, facts, NOW, NOON, null);
    }

    // prefix / pattern

    @Test
    public void prefixRuleMatchesNationalAndInternationalForms() {
        CallRule rule = pattern(1, "0900*", RuleAction.BLOCK);
        List<CallRule> rules = Collections.singletonList(rule);

        assertSame(rule, evaluate(rules, call("0900123456")));
        assertSame(rule, evaluate(rules, call("+49900123456")));
        assertSame(rule, evaluate(rules, call("0049 900 123456")));
        assertNull(evaluate(rules, call("030123456")));
        // the national form only exists for the home country
        assertNull(evaluate(rules, call("+43900123456")));
    }

    @Test
    public void internationalPrefixMatchesNationalNumber() {
        CallRule rule = pattern(1, "+49137*", RuleAction.BLOCK);
        List<CallRule> rules = Collections.singletonList(rule);

        assertSame(rule, evaluate(rules, call("01371234567")));
        assertSame(rule, evaluate(rules, call("+491371234567")));
        assertNull(evaluate(rules, call("0138123")));
    }

    @Test
    public void foreignPrefix() {
        CallRule rule = pattern(1, "+44*", RuleAction.BLOCK);
        List<CallRule> rules = Collections.singletonList(rule);

        assertSame(rule, evaluate(rules, call("+447700900123")));
        assertSame(rule, evaluate(rules, call("00447700900123")));
        assertNull(evaluate(rules, call("+4930123")));
        assertNull(evaluate(rules, call("044123"))); // national number of the home country
    }

    @Test
    public void patternUsesAppNormalizedNumber() {
        CallRule rule = pattern(1, "0151*", RuleAction.BLOCK);
        CallFacts facts = new CallFacts("1511234567", "+491511234567", false, false, "49");
        assertSame(rule, evaluate(Collections.singletonList(rule), facts));
    }

    @Test
    public void germanPremiumPreset() {
        CallRule rule = RulePresets.create(RulePresets.Preset.GERMAN_PREMIUM);
        List<CallRule> rules = Collections.singletonList(rule);

        for (String number : Arrays.asList("0900123456", "01371234", "0180123456",
                "0190123456", "+49900123", "+49 137 1234")) {
            assertSame(number, rule, evaluate(rules, call(number)));
        }
        for (String number : Arrays.asList("0700123456", "030123456", "01511234567",
                "11833", "+43900123")) {
            assertNull(number, evaluate(rules, call(number)));
        }
        // works for a user in another country as well
        CallFacts fromAustria = new CallFacts("+49900123", null, false, false, "43");
        assertSame(rule, evaluate(rules, fromAustria));
    }

    @Test
    public void patternRulesDontMatchHiddenNumbers() {
        assertNull(evaluate(Collections.singletonList(pattern(1, "*", RuleAction.BLOCK)),
                hiddenCall()));
    }

    // hidden numbers

    @Test
    public void hiddenNumberRule() {
        CallRule rule = CallRule.builder(RuleType.HIDDEN_NUMBER).id(1).build();
        List<CallRule> rules = Collections.singletonList(rule);

        assertSame(rule, evaluate(rules, hiddenCall()));
        assertNull(evaluate(rules, call("030123456")));
    }

    // foreign numbers

    @Test
    public void foreignNumberRule() {
        CallRule rule = CallRule.builder(RuleType.FOREIGN_NUMBER).id(1).build();
        List<CallRule> rules = Collections.singletonList(rule);

        assertSame(rule, evaluate(rules, call("+447700900123")));
        assertSame(rule, evaluate(rules, call("0043123456")));
        assertNull(evaluate(rules, call("+4930123456")));
        assertNull(evaluate(rules, call("030123456")));
        assertNull(evaluate(rules, call("11833")));
        assertNull(evaluate(rules, hiddenCall()));

        // unknown home country: never foreign
        CallFacts unknownHome = new CallFacts("+447700900123", null, false, false, null);
        assertNull(evaluate(rules, unknownHome));
    }

    @Test
    public void foreignNumberRuleExceptContacts() {
        CallRule except = CallRule.builder(RuleType.FOREIGN_NUMBER).id(1).build();
        assertNull(evaluate(Collections.singletonList(except), contactCall("+447700900123")));

        CallRule including = except.toBuilder().exceptContacts(false).build();
        assertSame(including, evaluate(Collections.singletonList(including),
                contactCall("+447700900123")));
    }

    @Test
    public void allowRulesMatchContacts() {
        CallRule allow = pattern(1, "+44*", RuleAction.ALLOW);
        assertSame(allow, evaluate(Collections.singletonList(allow),
                contactCall("+447700900123")));
    }

    // schedule

    @Test
    public void scheduleWithinDay() {
        CallRule rule = pattern(1, "*", RuleAction.BLOCK).toBuilder()
                .schedule(true, 9 * 60, 17 * 60).build();
        assertFalse(rule.isActiveAt(8 * 60 + 59));
        assertTrue(rule.isActiveAt(9 * 60));
        assertTrue(rule.isActiveAt(16 * 60 + 59));
        assertFalse(rule.isActiveAt(17 * 60));
    }

    @Test
    public void scheduleOverMidnight() {
        CallRule rule = pattern(1, "*", RuleAction.BLOCK).toBuilder()
                .schedule(true, 22 * 60, 7 * 60).build();
        List<CallRule> rules = Collections.singletonList(rule);

        assertTrue(rule.isActiveAt(22 * 60));
        assertTrue(rule.isActiveAt(23 * 60 + 59));
        assertTrue(rule.isActiveAt(0));
        assertTrue(rule.isActiveAt(6 * 60 + 59));
        assertFalse(rule.isActiveAt(7 * 60));
        assertFalse(rule.isActiveAt(NOON));
        assertFalse(rule.isActiveAt(21 * 60 + 59));

        assertSame(rule, RuleEngine.evaluate(rules, call("030123"), NOW, 23 * 60, null));
        assertSame(rule, RuleEngine.evaluate(rules, call("030123"), NOW, 3 * 60, null));
        assertNull(RuleEngine.evaluate(rules, call("030123"), NOW, NOON, null));
    }

    @Test
    public void scheduleStartEqualsEndMeansWholeDayAndDisabledScheduleIsIgnored() {
        CallRule wholeDay = pattern(1, "*", RuleAction.BLOCK).toBuilder()
                .schedule(true, 8 * 60, 8 * 60).build();
        assertTrue(wholeDay.isActiveAt(0));
        assertTrue(wholeDay.isActiveAt(NOON));

        CallRule disabled = pattern(1, "*", RuleAction.BLOCK).toBuilder()
                .schedule(false, 22 * 60, 7 * 60).build();
        assertTrue(disabled.isActiveAt(NOON));
    }

    @Test
    public void scheduleMinutesAreNormalized() {
        CallRule rule = pattern(1, "*", RuleAction.BLOCK).toBuilder()
                .schedule(true, -60, 24 * 60 + 30).build();
        assertEquals(23 * 60, rule.getScheduleStart());
        assertEquals(30, rule.getScheduleEnd());
    }

    // repeated caller

    private static final class FakeRecentCalls implements RecentCalls {
        final RecentCallsMemory memory = new RecentCallsMemory(0, null);

        @Override
        public boolean hasCallBetween(String key, long fromMillis, long toMillis) {
            return memory.hasCallBetween(key, fromMillis, toMillis);
        }
    }

    @Test
    public void repeatedCallerWithinWindow() {
        CallRule rule = RulePresets.create(RulePresets.Preset.REPEATED_CALLER)
                .toBuilder().id(1).build();
        assertEquals(RuleAction.ALLOW, rule.getAction());
        assertEquals(3, rule.getRepeatWindowMinutes());
        List<CallRule> rules = Collections.singletonList(rule);

        FakeRecentCalls recent = new FakeRecentCalls();
        CallFacts facts = call("01511234567");

        // first call: nothing known
        assertNull(RuleEngine.evaluate(rules, facts, NOW, NOON, recent));

        // called 2 minutes ago (as the international form, the key is the same)
        recent.memory.record("+491511234567", NOW - 2 * MINUTE);
        assertSame(rule, RuleEngine.evaluate(rules, facts, NOW, NOON, recent));

        // exactly at the window border: still inside
        assertSame(rule, RuleEngine.evaluate(rules, facts, NOW - 2 * MINUTE + 3 * MINUTE,
                NOON, recent));
        // after the window
        assertNull(RuleEngine.evaluate(rules, facts, NOW + 2 * MINUTE, NOON, recent));

        // another number
        assertNull(RuleEngine.evaluate(rules, call("01517654321"), NOW, NOON, recent));

        // contacts are not "unknown callers"
        assertNull(RuleEngine.evaluate(rules, contactCall("01511234567"), NOW, NOON, recent));

        // no recent calls given (e.g. describing the call log)
        assertNull(RuleEngine.evaluate(rules, facts, NOW, NOON, null));
    }

    @Test
    public void repeatedCallerIgnoresTheSameCallSeenTwice() {
        CallRule rule = RulePresets.create(RulePresets.Preset.REPEATED_CALLER)
                .toBuilder().id(1).build();
        List<CallRule> rules = Collections.singletonList(rule);
        FakeRecentCalls recent = new FakeRecentCalls();
        CallFacts facts = call("01511234567");

        recent.memory.record(facts.forms.key(), NOW - 2_000);
        assertNull(RuleEngine.evaluate(rules, facts, NOW, NOON, recent));

        // an earlier real call is still found when the duplicate was recorded later
        recent.memory.record(facts.forms.key(), NOW - 60_000);
        assertSame(rule, RuleEngine.evaluate(rules, facts, NOW, NOON, recent));
    }

    @Test
    public void repeatedCallerCustomWindowAndHiddenNumbers() {
        CallRule rule = CallRule.builder(RuleType.REPEATED_CALLER).id(1)
                .repeatWindowMinutes(10).build();
        List<CallRule> rules = Collections.singletonList(rule);
        FakeRecentCalls recent = new FakeRecentCalls();
        CallFacts facts = call("030123456");

        recent.memory.record(facts.forms.key(), NOW - 9 * MINUTE);
        assertSame(rule, RuleEngine.evaluate(rules, facts, NOW, NOON, recent));

        recent.memory.record("", NOW - MINUTE);
        assertNull(RuleEngine.evaluate(rules, hiddenCall(), NOW, NOON, recent));
    }

    @Test
    public void repeatWindowIsClamped() {
        assertEquals(1, CallRule.builder(RuleType.REPEATED_CALLER).repeatWindowMinutes(0)
                .build().getRepeatWindowMinutes());
        assertEquals(60, CallRule.builder(RuleType.REPEATED_CALLER).repeatWindowMinutes(1000)
                .build().getRepeatWindowMinutes());
        // the action of a repeated-caller rule is always ALLOW
        assertEquals(RuleAction.ALLOW, CallRule.builder(RuleType.REPEATED_CALLER)
                .action(RuleAction.BLOCK).build().getAction());
    }

    // priority

    @Test
    public void firstMatchingRuleWins() {
        CallRule allowOne = pattern(1, "+447700900123", RuleAction.ALLOW);
        CallRule blockUk = pattern(2, "+44*", RuleAction.BLOCK);
        CallRule blockAll = pattern(3, "*", RuleAction.BLOCK);
        List<CallRule> rules = Arrays.asList(allowOne, blockUk, blockAll);

        assertSame(allowOne, evaluate(rules, call("+447700900123")));
        assertSame(blockUk, evaluate(rules, call("+447700900999")));
        assertSame(blockAll, evaluate(rules, call("030123")));

        // reversed order: the catch-all wins
        List<CallRule> reversed = Arrays.asList(blockAll, blockUk, allowOne);
        assertSame(blockAll, evaluate(reversed, call("+447700900123")));
    }

    @Test
    public void disabledAndInactiveRulesAreSkipped() {
        CallRule disabled = pattern(1, "*", RuleAction.ALLOW).toBuilder().enabled(false).build();
        CallRule night = pattern(2, "*", RuleAction.BLOCK).toBuilder()
                .schedule(true, 22 * 60, 7 * 60).build();
        CallRule fallback = pattern(3, "0*", RuleAction.ALLOW);
        List<CallRule> rules = Arrays.asList(disabled, night, fallback);

        assertSame(fallback, RuleEngine.evaluate(rules, call("030123"), NOW, NOON, null));
        assertSame(night, RuleEngine.evaluate(rules, call("030123"), NOW, 23 * 60, null));
    }

    @Test
    public void repeatedCallerBeforeBlockRuleLetsTheCallerThrough() {
        CallRule repeated = CallRule.builder(RuleType.REPEATED_CALLER).id(1).build();
        CallRule foreign = CallRule.builder(RuleType.FOREIGN_NUMBER).id(2).build();
        FakeRecentCalls recent = new FakeRecentCalls();
        CallFacts facts = call("+447700900123");

        List<CallRule> rules = Arrays.asList(repeated, foreign);
        assertSame(foreign, RuleEngine.evaluate(rules, facts, NOW, NOON, recent));
        recent.memory.record(facts.forms.key(), NOW - MINUTE);
        assertSame(repeated, RuleEngine.evaluate(rules, facts, NOW, NOON, recent));

        // below the block rule it never gets a chance
        List<CallRule> lower = Arrays.asList(foreign, repeated);
        assertSame(foreign, RuleEngine.evaluate(lower, facts, NOW, NOON, recent));
    }

    @Test
    public void emptyRules() {
        assertNull(evaluate(Collections.emptyList(), call("030123")));
    }

    @Test
    public void invalidPatternRuleNeverMatches() {
        CallRule rule = pattern(1, "abc", RuleAction.BLOCK);
        assertFalse(rule.isValid());
        assertNull(evaluate(Collections.singletonList(rule), call("030123")));
    }

}
