package dummydomain.yetanothercallblocker.data.stats;

import java.util.Objects;

/**
 * One handled incoming call for the statistics. Immutable.
 *
 * <p>Plain Java, no Android dependencies.</p>
 */
public final class CallStatEvent {

    /** What happened to the call. The order is the "strength" used for de-duplication. */
    public enum Outcome {
        /** The call rang normally. */
        ALLOWED,
        /** The call rang, but the number was flagged as spam (the user was warned). */
        NOTIFIED,
        /** The call was rejected. */
        BLOCKED;

        /** @return the outcome with this name, or null */
        public static Outcome fromName(String name) {
            if (name == null) return null;
            for (Outcome o : values()) {
                if (o.name().equals(name)) return o;
            }
            return null;
        }
    }

    /** Why the call got its outcome. */
    public enum Reason {
        /** Nothing special (an unknown, unrated number). */
        NONE,
        /** The user's blacklist. */
        BLACKLIST,
        /** The community rating of the built-in database. */
        RATING,
        /** An imported or downloaded list, or an online source (see the source id). */
        LIST,
        /** A call rule (block or allow). */
        RULE,
        /** The user's own mark ("My mark"). */
        USER_MARK,
        /** A hidden (anonymous) number. */
        HIDDEN,
        /** A contact. */
        CONTACT,
        /** A reason written by a newer version of the app. */
        OTHER;

        /** @return the reason with this name, {@link #OTHER} for unknown names */
        public static Reason fromName(String name) {
            if (name == null || name.isEmpty()) return NONE;
            for (Reason r : values()) {
                if (r.name().equals(name)) return r;
            }
            return OTHER;
        }
    }

    private final long timestamp;
    private final String number;
    private final Outcome outcome;
    private final Reason reason;
    private final String sourceId;

    /**
     * @param timestamp when the call came in, milliseconds since the epoch
     * @param number    the normalized number, empty (or null) for a hidden number
     * @param outcome   what happened
     * @param reason    why, null for {@link Reason#NONE}
     * @param sourceId  the data source that rated the number, may be null
     */
    public CallStatEvent(long timestamp, String number, Outcome outcome, Reason reason,
                         String sourceId) {
        this.timestamp = timestamp;
        this.number = number != null ? number : "";
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.reason = reason != null ? reason : Reason.NONE;
        this.sourceId = sourceId != null && !sourceId.isEmpty() ? sourceId : null;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /** @return the normalized number, empty for a hidden number */
    public String getNumber() {
        return number;
    }

    public boolean isHiddenNumber() {
        return number.isEmpty();
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public boolean isBlocked() {
        return outcome == Outcome.BLOCKED;
    }

    public Reason getReason() {
        return reason;
    }

    /** @return the id of the source that rated the number, or null */
    public String getSourceId() {
        return sourceId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CallStatEvent)) return false;
        CallStatEvent that = (CallStatEvent) o;
        return timestamp == that.timestamp
                && number.equals(that.number)
                && outcome == that.outcome
                && reason == that.reason
                && Objects.equals(sourceId, that.sourceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, number, outcome, reason, sourceId);
    }

    @Override
    public String toString() {
        return "CallStatEvent{" + timestamp + ", " + number + ", " + outcome + ", " + reason
                + (sourceId != null ? ", " + sourceId : "") + '}';
    }

}
