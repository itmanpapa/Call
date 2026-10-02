package dummydomain.yetanothercallblocker.data.stats;

import org.junit.Test;

import dummydomain.yetanothercallblocker.data.stats.CallStatEvent.Outcome;
import dummydomain.yetanothercallblocker.data.stats.CallStatEvent.Reason;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CallStatClassifierTest {

    private static CallStatClassifier.Facts facts(String number) {
        CallStatClassifier.Facts f = new CallStatClassifier.Facts();
        f.number = number;
        f.normalizedNumber = number;
        return f;
    }

    @Test
    public void blockedByReason() {
        CallStatClassifier.Facts f = facts("+4930123456");
        f.blocked = true;

        f.blockingReason = "BLACKLISTED";
        assertEquals(Reason.BLACKLIST, CallStatClassifier.classify(f, 1).getReason());

        f.blockingReason = "RULE";
        assertEquals(Reason.RULE, CallStatClassifier.classify(f, 1).getReason());

        f.blockingReason = "SIA_RATING";
        CallStatEvent e = CallStatClassifier.classify(f, 1);
        assertEquals(Outcome.BLOCKED, e.getOutcome());
        assertEquals(Reason.RATING, e.getReason());
        assertEquals("yacb", e.getSourceId());

        f.sourceId = "bnetza";
        e = CallStatClassifier.classify(f, 1);
        assertEquals(Reason.LIST, e.getReason());
        assertEquals("bnetza", e.getSourceId());

        f.sourceId = "user_mark";
        assertEquals(Reason.USER_MARK, CallStatClassifier.classify(f, 1).getReason());
    }

    @Test
    public void hiddenNumber() {
        CallStatClassifier.Facts f = facts("");
        f.hiddenNumber = true;
        f.blocked = true;
        f.blockingReason = "HIDDEN_NUMBER";
        CallStatEvent e = CallStatClassifier.classify(f, 5);
        assertEquals("", e.getNumber());
        assertEquals(Reason.HIDDEN, e.getReason());
        assertEquals(5, e.getTimestamp());

        f.blocked = false;
        f.blockingReason = null;
        e = CallStatClassifier.classify(f, 5);
        assertEquals(Outcome.ALLOWED, e.getOutcome());
        assertEquals(Reason.HIDDEN, e.getReason());
    }

    @Test
    public void notBlocked() {
        CallStatClassifier.Facts f = facts("+4930123456");
        CallStatEvent e = CallStatClassifier.classify(f, 1);
        assertEquals(Outcome.ALLOWED, e.getOutcome());
        assertEquals(Reason.NONE, e.getReason());
        assertNull(e.getSourceId());

        f.negativeRating = true;
        f.sourceId = "phoneblock";
        e = CallStatClassifier.classify(f, 1);
        assertEquals(Outcome.NOTIFIED, e.getOutcome());
        assertEquals(Reason.LIST, e.getReason());

        f.ruleMatched = true;
        e = CallStatClassifier.classify(f, 1);
        assertEquals(Outcome.ALLOWED, e.getOutcome());
        assertEquals(Reason.RULE, e.getReason());

        f.contact = true;
        assertEquals(Reason.CONTACT, CallStatClassifier.classify(f, 1).getReason());

        CallStatClassifier.Facts notSpam = facts("+4930123456");
        notSpam.sourceId = "user_mark";
        e = CallStatClassifier.classify(notSpam, 1);
        assertEquals(Outcome.ALLOWED, e.getOutcome());
        assertEquals(Reason.USER_MARK, e.getReason());
    }

    @Test
    public void rawNumberWhenNotNormalized() {
        CallStatClassifier.Facts f = facts(" 030 123 ");
        f.normalizedNumber = null;
        assertEquals("030 123", CallStatClassifier.classify(f, 1).getNumber());
    }

}
