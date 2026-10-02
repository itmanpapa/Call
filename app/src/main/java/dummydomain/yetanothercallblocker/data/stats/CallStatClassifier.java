package dummydomain.yetanothercallblocker.data.stats;

/**
 * Turns the decision about an incoming call into a {@link CallStatEvent}.
 *
 * <p>Plain Java: the caller extracts the facts from {@code NumberInfo} (which depends
 * on Android classes), so the mapping itself can be tested on the JVM.</p>
 */
public final class CallStatClassifier {

    /** Source id of the built-in (YACB) database, see {@code YacbDatabaseProvider.ID}. */
    public static final String YACB_SOURCE_ID = "yacb";
    /** Source id of the user's own marks, see {@code UserMarkPolicy.SOURCE_ID}. */
    public static final String USER_MARK_SOURCE_ID = "user_mark";

    /** The facts about a call (filled from {@code NumberInfo}). */
    public static final class Facts {
        /** Whether the call was actually rejected. */
        public boolean blocked;
        /** {@code NumberInfo.BlockingReason} name, or null. */
        public String blockingReason;
        public String number;
        public String normalizedNumber;
        public boolean hiddenNumber;
        public boolean contact;
        /** Whether a call rule matched (block or allow). */
        public boolean ruleMatched;
        /** Whether the number has the NEGATIVE rating. */
        public boolean negativeRating;
        /** {@code NumberInfo.sourceId}: the source of the rating, or null. */
        public String sourceId;
    }

    private CallStatClassifier() {
    }

    public static CallStatEvent classify(Facts facts, long timestamp) {
        String number = facts.hiddenNumber ? ""
                : facts.normalizedNumber != null && !facts.normalizedNumber.isEmpty()
                ? facts.normalizedNumber : facts.number;
        if (number == null) number = "";
        number = number.trim();

        CallStatEvent.Outcome outcome;
        CallStatEvent.Reason reason;
        String sourceId = null;

        if (facts.blocked) {
            outcome = CallStatEvent.Outcome.BLOCKED;
            String r = facts.blockingReason != null ? facts.blockingReason : "";
            switch (r) {
                case "HIDDEN_NUMBER":
                    reason = CallStatEvent.Reason.HIDDEN;
                    break;
                case "BLACKLISTED":
                    reason = CallStatEvent.Reason.BLACKLIST;
                    break;
                case "RULE":
                    reason = CallStatEvent.Reason.RULE;
                    break;
                case "SIA_RATING":
                    reason = ratingReason(facts.sourceId);
                    sourceId = ratingSource(facts.sourceId);
                    break;
                default:
                    reason = CallStatEvent.Reason.OTHER;
                    break;
            }
        } else if (facts.contact) {
            outcome = CallStatEvent.Outcome.ALLOWED;
            reason = CallStatEvent.Reason.CONTACT;
        } else if (facts.ruleMatched) {
            // an allow rule (a matching block rule would have blocked the call)
            outcome = CallStatEvent.Outcome.ALLOWED;
            reason = CallStatEvent.Reason.RULE;
        } else if (facts.negativeRating) {
            outcome = CallStatEvent.Outcome.NOTIFIED;
            reason = ratingReason(facts.sourceId);
            sourceId = ratingSource(facts.sourceId);
        } else if (USER_MARK_SOURCE_ID.equals(facts.sourceId)) {
            // marked "not spam"
            outcome = CallStatEvent.Outcome.ALLOWED;
            reason = CallStatEvent.Reason.USER_MARK;
            sourceId = USER_MARK_SOURCE_ID;
        } else {
            outcome = CallStatEvent.Outcome.ALLOWED;
            reason = facts.hiddenNumber ? CallStatEvent.Reason.HIDDEN : CallStatEvent.Reason.NONE;
        }

        return new CallStatEvent(timestamp, number, outcome, reason, sourceId);
    }

    private static CallStatEvent.Reason ratingReason(String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return CallStatEvent.Reason.RATING;
        if (USER_MARK_SOURCE_ID.equals(sourceId)) return CallStatEvent.Reason.USER_MARK;
        return CallStatEvent.Reason.LIST;
    }

    private static String ratingSource(String sourceId) {
        // NumberInfo leaves the source empty for the built-in database
        return sourceId == null || sourceId.isEmpty() ? YACB_SOURCE_ID : sourceId;
    }

}
