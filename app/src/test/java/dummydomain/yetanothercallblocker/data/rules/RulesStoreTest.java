package dummydomain.yetanothercallblocker.data.rules;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RulesStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static List<CallRule> sampleRules() {
        return Arrays.asList(
                CallRule.builder(RuleType.HIDDEN_NUMBER).id(1).action(RuleAction.BLOCK).build(),
                CallRule.builder(RuleType.NUMBER_PATTERN).id(2).action(RuleAction.ALLOW)
                        .patterns("+44*; 0900*").exceptContacts(false)
                        .label("Tab\there, back\\slash\nnew line = ok").build(),
                CallRule.builder(RuleType.REPEATED_CALLER).id(3).repeatWindowMinutes(5)
                        .schedule(true, 22 * 60, 7 * 60).enabled(false).build(),
                CallRule.builder(RuleType.FOREIGN_NUMBER).id(4).build(),
                CallRule.builder(RuleType.PREMIUM_DE).id(5).label("Платные").build());
    }

    @Test
    public void roundTripThroughFile() throws IOException {
        RulesStore store = new RulesStore(new File(tmp.getRoot(), "rules/rules.txt"));
        List<CallRule> rules = sampleRules();

        store.save(rules);
        assertEquals(rules, store.load());
        assertFalse(new File(tmp.getRoot(), "rules/rules.txt.tmp").exists());

        // overwrite
        store.save(Collections.singletonList(rules.get(0)));
        assertEquals(Collections.singletonList(rules.get(0)), store.load());
    }

    @Test
    public void missingFileIsEmpty() throws IOException {
        RulesStore store = new RulesStore(new File(tmp.getRoot(), "nothing.txt"));
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void textFormat() throws IOException {
        String text = RulesStore.toText(sampleRules());
        String[] lines = text.split("\n");
        assertEquals("callguard-rules\t1", lines[0]);
        assertEquals("id=1\ttype=HIDDEN_NUMBER\taction=BLOCK\tenabled=1\texceptContacts=1"
                + "\tschedule=0\tstart=1320\tend=420", lines[1]);
        assertTrue(lines[2], lines[2].contains("\tpatterns=+44*, 0900*\t"));
        assertTrue(lines[2], lines[2].endsWith(
                "\tlabel=Tab\\there, back\\\\slash\\nnew line = ok"));
        assertTrue(lines[3], lines[3].contains("\twindow=5\t"));
        assertEquals(6, lines.length);
    }

    @Test
    public void toleratesUnknownKeysTypesAndMissingFields() throws IOException {
        String text = "﻿callguard-rules\t2\n"
                + "# comment\n"
                + "\n"
                + "id=7\ttype=HIDDEN_NUMBER\taction=ALLOW\tcolor=red\n"
                + "id=8\ttype=SOMETHING_NEW\taction=BLOCK\n"
                + "id=9\ttype=NUMBER_PATTERN\taction=MAYBE\tpatterns=1*\n"
                + "type=REPEATED_CALLER\n"
                + "garbage line without fields\n";
        List<CallRule> rules = RulesStore.fromText(text);

        assertEquals(2, rules.size());
        CallRule hidden = rules.get(0);
        assertEquals(7, hidden.getId());
        assertEquals(RuleType.HIDDEN_NUMBER, hidden.getType());
        assertEquals(RuleAction.ALLOW, hidden.getAction());
        assertTrue(hidden.isEnabled());
        assertTrue(hidden.isExceptContacts());
        assertFalse(hidden.isScheduleEnabled());

        CallRule repeated = rules.get(1);
        assertEquals(0, repeated.getId());
        assertEquals(RuleAction.ALLOW, repeated.getAction());
        assertEquals(CallRule.DEFAULT_REPEAT_WINDOW_MINUTES, repeated.getRepeatWindowMinutes());
    }

    @Test
    public void rejectsOtherFiles() {
        for (String text : Arrays.asList("", "hello\n", "callguard-rules\tx\n",
                "callguard-rulesX\t1\n")) {
            try {
                RulesStore.fromText(text);
                fail("accepted: " + text);
            } catch (IOException expected) {
                // ok
            }
        }
    }

    @Test
    public void escaping() {
        String value = "a\\b\tc\nd\re\\t";
        assertEquals(value, RulesStore.unescape(RulesStore.escape(value)));
        assertEquals("a\\\\b\\tc", RulesStore.escape("a\\b\tc"));
    }

    @Test
    public void fileIsUtf8() throws IOException {
        File file = new File(tmp.getRoot(), "rules.txt");
        new RulesStore(file).save(sampleRules());
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("label=Платные"));
    }

}
