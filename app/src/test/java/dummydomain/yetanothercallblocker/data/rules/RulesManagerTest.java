package dummydomain.yetanothercallblocker.data.rules;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RulesManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class FakeClock implements RulesManager.Clock {
        long now = 1_800_000_000_000L;
        int minuteOfDay = 12 * 60;

        @Override
        public long currentTimeMillis() {
            return now;
        }

        @Override
        public int minuteOfDay(long millis) {
            return minuteOfDay;
        }
    }

    private File file;
    private FakeClock clock;
    private RulesManager manager;
    private final List<List<CallRule>> notifications = new ArrayList<>();

    @Before
    public void setUp() {
        file = new File(tmp.getRoot(), "rules.txt");
        clock = new FakeClock();
        manager = newManager();
    }

    private RulesManager newManager() {
        RulesManager m = new RulesManager(new RulesStore(file), clock, null);
        m.setListener(notifications::add);
        return m;
    }

    private static CallFacts call(String number) {
        return new CallFacts(number, null, false, false, "49");
    }

    @Test
    public void addAssignsIdsAndPersists() throws IOException {
        CallRule hidden = manager.add(RulePresets.create(RulePresets.Preset.HIDDEN_NUMBERS));
        CallRule premium = manager.add(RulePresets.create(RulePresets.Preset.GERMAN_PREMIUM));
        assertEquals(1, hidden.getId());
        assertEquals(2, premium.getId());
        assertEquals(Arrays.asList(hidden, premium), manager.getRules());

        assertEquals(Arrays.asList(hidden, premium), newManager().getRules());
        assertFalse(notifications.isEmpty());
        assertEquals(Arrays.asList(hidden, premium), notifications.get(notifications.size() - 1));
    }

    @Test
    public void updateEnableDeleteInsertReorder() throws IOException {
        CallRule a = manager.add(CallRule.builder(RuleType.NUMBER_PATTERN).patterns("+44*").build());
        CallRule b = manager.add(CallRule.builder(RuleType.HIDDEN_NUMBER).build());
        CallRule c = manager.add(CallRule.builder(RuleType.FOREIGN_NUMBER).build());

        CallRule a2 = a.toBuilder().patterns("+44*, +33*").build();
        manager.update(a2);
        assertEquals("+44*, +33*", manager.getRules().get(0).getPatterns());

        manager.setEnabled(b.getId(), false);
        assertFalse(manager.getRules().get(1).isEnabled());

        manager.reorder(Arrays.asList(c.getId(), a.getId()));
        assertEquals(Arrays.asList(c.getId(), a.getId(), b.getId()), ids(manager.getRules()));

        CallRule removed = manager.delete(c.getId());
        assertEquals(c, removed);
        assertNull(manager.delete(c.getId()));
        assertEquals(Arrays.asList(a.getId(), b.getId()), ids(manager.getRules()));

        manager.insert(0, removed); // undo keeps the id
        assertEquals(Arrays.asList(c.getId(), a.getId(), b.getId()), ids(manager.getRules()));

        assertEquals(manager.getRules(), newManager().getRules());
    }

    private static List<Long> ids(List<CallRule> rules) {
        List<Long> result = new ArrayList<>();
        for (CallRule rule : rules) result.add(rule.getId());
        return result;
    }

    @Test
    public void hasBlockingRules() throws IOException {
        assertFalse(manager.hasBlockingRules());
        CallRule allow = manager.add(CallRule.builder(RuleType.NUMBER_PATTERN)
                .patterns("+44*").action(RuleAction.ALLOW).build());
        manager.add(CallRule.builder(RuleType.REPEATED_CALLER).build());
        assertFalse(manager.hasBlockingRules());

        CallRule block = manager.add(CallRule.builder(RuleType.HIDDEN_NUMBER).build());
        assertTrue(manager.hasBlockingRules());
        manager.setEnabled(block.getId(), false);
        assertFalse(manager.hasBlockingRules());

        manager.update(allow.toBuilder().action(RuleAction.BLOCK).build());
        assertTrue(manager.hasBlockingRules());
    }

    @Test
    public void evaluateUsesClockForSchedule() throws IOException {
        CallRule night = manager.add(CallRule.builder(RuleType.NUMBER_PATTERN).patterns("*")
                .schedule(true, 22 * 60, 7 * 60).build());

        clock.minuteOfDay = 12 * 60;
        assertNull(manager.evaluate(call("030123"), true));
        clock.minuteOfDay = 23 * 60 + 30;
        assertEquals(night, manager.evaluate(call("030123"), true));
    }

    @Test
    public void repeatedCallerThroughManager() throws IOException {
        CallRule repeated = manager.add(RulePresets.create(RulePresets.Preset.REPEATED_CALLER));
        CallFacts facts = call("01511234567");

        assertNull(manager.evaluate(facts, true));
        manager.recordIncomingCall(facts);

        // the same call seen again a second later
        clock.now += 1_000;
        assertNull(manager.evaluate(facts, true));
        manager.recordIncomingCall(facts);

        // two minutes later: a repeated call
        clock.now += 2 * 60_000;
        assertEquals(repeated, manager.evaluate(facts, true));
        // not for describing numbers
        assertNull(manager.evaluate(facts, false));

        // after the window
        clock.now += 10 * 60_000;
        assertNull(manager.evaluate(facts, true));
    }

    @Test
    public void hiddenCallsAreNotRecorded() throws IOException {
        manager.add(RulePresets.create(RulePresets.Preset.REPEATED_CALLER));
        CallFacts hidden = new CallFacts("", null, true, false, "49");
        manager.recordIncomingCall(hidden);
        clock.now += 60_000;
        assertNull(manager.evaluate(hidden, true));
    }

    @Test
    public void brokenFileMeansNoRules() throws IOException {
        Files.write(file.toPath(), "not a rules file".getBytes(StandardCharsets.UTF_8));
        assertTrue(manager.getRules().isEmpty());
        assertNull(manager.evaluate(call("030123"), true));
    }

    @Test
    public void missingAndDuplicateIdsAreFixedOnLoad() throws IOException {
        Files.write(file.toPath(), ("callguard-rules\t1\n"
                + "id=5\ttype=HIDDEN_NUMBER\taction=BLOCK\n"
                + "type=FOREIGN_NUMBER\taction=BLOCK\n"
                + "id=5\ttype=PREMIUM_DE\taction=BLOCK\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(Arrays.asList(5L, 6L, 7L), ids(manager.getRules()));
    }

    @Test
    public void presetsArePresent() throws IOException {
        assertFalse(RulePresets.isPresent(manager.getRules(), RulePresets.Preset.GERMAN_PREMIUM));
        manager.add(RulePresets.create(RulePresets.Preset.GERMAN_PREMIUM));
        assertTrue(RulePresets.isPresent(manager.getRules(), RulePresets.Preset.GERMAN_PREMIUM));
        assertFalse(RulePresets.isPresent(manager.getRules(), RulePresets.Preset.HIDDEN_NUMBERS));
    }

    @Test
    public void unchangedReorderDoesNotNotify() throws IOException {
        CallRule a = manager.add(CallRule.builder(RuleType.HIDDEN_NUMBER).build());
        int count = notifications.size();
        manager.reorder(Arrays.asList(a.getId()));
        assertEquals(count, notifications.size());
        assertSame(a, manager.getRules().get(0));
    }

}
