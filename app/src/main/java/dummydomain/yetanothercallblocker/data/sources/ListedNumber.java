package dummydomain.yetanothercallblocker.data.sources;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A single number (or number prefix) from an offline list.
 *
 * <p>Exactly one of {@link #getNumber()} and {@link #getPrefix()} is non-null:
 * an exact number is stored in E.164 form ({@code +4915112345678}), a range or a
 * wildcard entry is stored as an E.164 prefix ({@code +4990012345} matches every
 * number starting with these digits).</p>
 *
 * <p>Instances are immutable.</p>
 */
public final class ListedNumber {

    private final String number;
    private final String prefix;
    private final String name;
    private final String category;
    private final String comment;
    private final MeasureType measureType;
    private final String measureText;
    private final LocalDate date;
    private final String rawText;

    private ListedNumber(Builder b) {
        this.number = b.number;
        this.prefix = b.prefix;
        this.name = b.name;
        this.category = b.category;
        this.comment = b.comment;
        this.measureType = b.measureType != null ? b.measureType : MeasureType.NONE;
        this.measureText = b.measureText;
        this.date = b.date;
        this.rawText = b.rawText;
    }

    /** Exact number in E.164 form, or {@code null} for prefix entries. */
    public String getNumber() {
        return number;
    }

    /** E.164 prefix (without wildcard characters), or {@code null} for exact entries. */
    public String getPrefix() {
        return prefix;
    }

    public boolean isPrefix() {
        return prefix != null;
    }

    /** Optional name (CSV import). */
    public String getName() {
        return name;
    }

    /** Free-text category, e.g. "Spam-SMS" (BNetzA) or a user-defined category (CSV). */
    public String getCategory() {
        return category;
    }

    /** Optional comment (CSV import). */
    public String getComment() {
        return comment;
    }

    public MeasureType getMeasureType() {
        return measureType;
    }

    /** Original measure text, e.g. "Abschaltung der Rufnummer". */
    public String getMeasureText() {
        return measureText;
    }

    /** Date of the decision ("Bescheid vom"), or {@code null} if unknown. */
    public LocalDate getDate() {
        return date;
    }

    /** The raw source text this entry was parsed from (a table row or a CSV line). */
    public String getRawText() {
        return rawText;
    }

    /**
     * Checks whether the given E.164 number is covered by this entry.
     */
    public boolean matches(String e164Number) {
        if (e164Number == null) return false;
        if (number != null) return number.equals(e164Number);
        return e164Number.startsWith(prefix);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ListedNumber)) return false;
        ListedNumber that = (ListedNumber) o;
        return Objects.equals(number, that.number)
                && Objects.equals(prefix, that.prefix)
                && Objects.equals(name, that.name)
                && Objects.equals(category, that.category)
                && Objects.equals(comment, that.comment)
                && measureType == that.measureType
                && Objects.equals(measureText, that.measureText)
                && Objects.equals(date, that.date)
                && Objects.equals(rawText, that.rawText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(number, prefix, name, category, comment,
                measureType, measureText, date, rawText);
    }

    @Override
    public String toString() {
        return "ListedNumber{" +
                (number != null ? "number='" + number + '\'' : "prefix='" + prefix + '\'') +
                ", name='" + name + '\'' +
                ", category='" + category + '\'' +
                ", comment='" + comment + '\'' +
                ", measureType=" + measureType +
                ", measureText='" + measureText + '\'' +
                ", date=" + date +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.number = number;
        b.prefix = prefix;
        b.name = name;
        b.category = category;
        b.comment = comment;
        b.measureType = measureType;
        b.measureText = measureText;
        b.date = date;
        b.rawText = rawText;
        return b;
    }

    public static final class Builder {

        private String number;
        private String prefix;
        private String name;
        private String category;
        private String comment;
        private MeasureType measureType;
        private String measureText;
        private LocalDate date;
        private String rawText;

        private Builder() {}

        public Builder number(String number) {
            this.number = number;
            this.prefix = null;
            return this;
        }

        public Builder prefix(String prefix) {
            this.prefix = prefix;
            this.number = null;
            return this;
        }

        /** Sets either the exact number or the prefix, depending on the spec. */
        public Builder spec(GermanNumberNormalizer.NumberSpec spec) {
            return spec.isPrefix() ? prefix(spec.getValue()) : number(spec.getValue());
        }

        public Builder name(String name) {
            this.name = emptyToNull(name);
            return this;
        }

        public Builder category(String category) {
            this.category = emptyToNull(category);
            return this;
        }

        public Builder comment(String comment) {
            this.comment = emptyToNull(comment);
            return this;
        }

        public Builder measureType(MeasureType measureType) {
            this.measureType = measureType;
            return this;
        }

        public Builder measureText(String measureText) {
            this.measureText = emptyToNull(measureText);
            return this;
        }

        public Builder date(LocalDate date) {
            this.date = date;
            return this;
        }

        public Builder rawText(String rawText) {
            this.rawText = rawText;
            return this;
        }

        public ListedNumber build() {
            if ((number == null) == (prefix == null)) {
                throw new IllegalStateException("Exactly one of number and prefix must be set");
            }
            return new ListedNumber(this);
        }

        private static String emptyToNull(String s) {
            if (s == null) return null;
            s = s.trim();
            return s.isEmpty() ? null : s;
        }
    }

}
