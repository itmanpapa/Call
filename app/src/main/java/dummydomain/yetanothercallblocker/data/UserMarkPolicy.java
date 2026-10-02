package dummydomain.yetanothercallblocker.data;

/**
 * Decision rules for the user's own marks ("My mark") and for the prominent
 * incoming-call notification. Plain Java so the priorities can be unit-tested.
 *
 * <p>Priority of a number's verdict (highest first):</p>
 * <ol>
 *     <li>Contacts: never blocked (unchanged behaviour).</li>
 *     <li>An explicit blacklist entry: blocks even if the user marked the number
 *     "not spam" &ndash; the blacklist is an explicit, deliberate rule (it may be a
 *     pattern), so a mark never silently disables it. Remove the entry to allow calls.</li>
 *     <li>The user's mark: SPAM makes the number NEGATIVE (so it is blocked when blocking
 *     by rating is enabled), NOT_SPAM makes it POSITIVE, so ratings and lists never block
 *     it. Other sources (imported lists, online lookups) are not consulted at all.</li>
 *     <li>Ratings of the YACB database, imported lists, online sources.</li>
 * </ol>
 */
public final class UserMarkPolicy {

    /** {@code NumberInfo.sourceId} of a rating that comes from the user's mark. */
    public static final String SOURCE_ID = "user_mark";

    /** What the mark does to the rating of a number. */
    public enum Effect {
        /** No mark: the rating comes from the databases. */
        NONE,
        /** SPAM: the number is NEGATIVE. */
        FORCE_NEGATIVE,
        /** NOT_SPAM: the number is POSITIVE and never blocked by ratings or lists. */
        FORCE_NOT_SPAM
    }

    /** How the incoming call is announced. */
    public enum IncomingNotification {
        /** Nothing is shown. */
        NONE,
        /** The regular (low importance) notification. */
        REGULAR,
        /** The heads-up notification with "Block" / "Not spam" actions. */
        PROMINENT
    }

    private UserMarkPolicy() {
    }

    public static Effect effect(UserMark mark) {
        if (mark == null) return Effect.NONE;
        return mark.isSpam() ? Effect.FORCE_NEGATIVE : Effect.FORCE_NOT_SPAM;
    }

    /**
     * @return whether imported lists and online sources should be consulted;
     * not needed when the user already decided
     */
    public static boolean shouldQueryOtherSources(UserMark mark) {
        return mark == null;
    }

    /**
     * Chooses the incoming-call notification for a call that was <b>not</b> blocked.
     *
     * @param negative          the number is rated NEGATIVE (including a SPAM mark)
     * @param unknown           the number has no rating at all
     * @param inContacts        the number is a contact
     * @param noNumber          the number is hidden
     * @param mark              the user's mark, may be null
     * @param prominentEnabled  the "prominent incoming call notification" setting
     * @param regularAllowed    whether the regular notification may be shown for this
     *                          kind of call (legacy per-kind toggles before Android 8)
     */
    public static IncomingNotification incomingNotification(
            boolean negative, boolean unknown, boolean inContacts, boolean noNumber,
            UserMark mark, boolean prominentEnabled, boolean regularAllowed) {
        if (!regularAllowed) return IncomingNotification.NONE;

        if (!prominentEnabled || inContacts) return IncomingNotification.REGULAR;
        if (mark != null && mark.isNotSpam()) return IncomingNotification.REGULAR;

        if (negative) return IncomingNotification.PROMINENT;
        if (unknown || noNumber) return IncomingNotification.PROMINENT;

        return IncomingNotification.REGULAR;
    }

    /**
     * @return whether the "Not spam" action makes sense for a blocked call: only when it
     * was blocked by rating (a NOT_SPAM mark doesn't override the blacklist or the
     * "block hidden numbers" setting)
     * @param blockedByRating the call was blocked because of its NEGATIVE rating
     * @param noNumber        the number is hidden
     */
    public static boolean offerNotSpamForBlockedCall(boolean blockedByRating, boolean noNumber) {
        return blockedByRating && !noNumber;
    }

}
