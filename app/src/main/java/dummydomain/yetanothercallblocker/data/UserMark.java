package dummydomain.yetanothercallblocker.data;

import java.util.Objects;

/**
 * The user's own verdict on a number ("My mark"): spam or not spam.
 * Immutable; stored by {@link UserMarksStore}.
 */
public final class UserMark {

    public enum Type {
        /** The user says the number is spam: it is treated as NEGATIVE. */
        SPAM,
        /** The user says the number is not spam: ratings and lists never block it. */
        NOT_SPAM
    }

    private final String number;
    private final Type type;
    private final long timestamp;
    private final String note;

    public UserMark(String number, Type type, long timestamp, String note) {
        this.number = Objects.requireNonNull(number);
        this.type = Objects.requireNonNull(type);
        this.timestamp = timestamp;
        this.note = note != null && !note.trim().isEmpty() ? note.trim() : null;
    }

    /** @return the normalized number (the key in the store) */
    public String getNumber() {
        return number;
    }

    public Type getType() {
        return type;
    }

    public boolean isSpam() {
        return type == Type.SPAM;
    }

    public boolean isNotSpam() {
        return type == Type.NOT_SPAM;
    }

    /** @return when the mark was set, milliseconds since the epoch */
    public long getTimestamp() {
        return timestamp;
    }

    /** @return an optional note, null if none */
    public String getNote() {
        return note;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserMark)) return false;
        UserMark that = (UserMark) o;
        return timestamp == that.timestamp && number.equals(that.number)
                && type == that.type && Objects.equals(note, that.note);
    }

    @Override
    public int hashCode() {
        return Objects.hash(number, type, timestamp, note);
    }

    @Override
    public String toString() {
        return "UserMark{" + number + ", " + type + ", " + timestamp
                + (note != null ? ", note='" + note + '\'' : "") + '}';
    }

}
