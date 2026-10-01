package dummydomain.yetanothercallblocker.data.provider;

import java.util.Objects;

import dummydomain.yetanothercallblocker.data.sources.GermanNumberNormalizer;
import dummydomain.yetanothercallblocker.data.sources.ListedNumber;
import dummydomain.yetanothercallblocker.data.sources.MeasureType;
import dummydomain.yetanothercallblocker.data.sources.NumberListIndex;

/**
 * Offline provider backed by one imported number list (the Bundesnetzagentur
 * measures list, a user CSV list, ...).
 *
 * <p>Every listed number (exact or covered by a prefix) is reported as
 * {@link ProviderResult.Rating#NEGATIVE}. The category is the entry's category,
 * or, if it has none, the {@link MeasureType} constant name (e.g. {@code DISCONNECTION});
 * the name is the entry's name.</p>
 *
 * <h3>Number format</h3>
 * <p>List entries are stored in E.164 form with "+". The app normalizes incoming
 * numbers with {@code NumberUtils.normalizeNumber()}, which returns E.164
 * ({@code +4915112345678}) when {@code PhoneNumberUtils.formatNumberToE164()} succeeds,
 * and otherwise only strips separators, which may leave the number in national
 * ({@code 015112345678}) or international-without-plus ({@code 004915112345678},
 * {@code 4915112345678}) form. {@link #toE164(String)} converts all of these to E.164;
 * a national number with a trunk zero is interpreted as a German number, since
 * that is what the supported lists contain.</p>
 *
 * <p>The index can be swapped at any time with {@link #setIndex(NumberListIndex)}
 * (e.g. after a list update); lookups running concurrently see either the old
 * or the new index, never a mix.</p>
 */
public class ListedNumbersProvider implements NumberInfoProvider {

    /** Min digits of a number without "+" / "00" / "0" to be read as international. */
    static final int MIN_INTERNATIONAL_DIGITS = 8;

    private final String id;
    private final String displayName;
    private final boolean trusted;

    private volatile NumberListIndex index;

    /**
     * @param id          provider id (also the result source id)
     * @param displayName human-readable source label
     * @param trusted     whether NEGATIVE results from this list override other providers
     * @param index       initial index; null means an empty list
     */
    public ListedNumbersProvider(String id, String displayName, boolean trusted,
                                 NumberListIndex index) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = displayName != null ? displayName : id;
        this.trusted = trusted;
        this.index = index != null ? index : NumberListIndex.empty();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean isOffline() {
        return true;
    }

    @Override
    public boolean isTrusted() {
        return trusted;
    }

    public NumberListIndex getIndex() {
        return index;
    }

    /**
     * Atomically replaces the index (e.g. after the list was re-imported).
     *
     * @param index new index; null means an empty list
     */
    public void setIndex(NumberListIndex index) {
        this.index = index != null ? index : NumberListIndex.empty();
    }

    @Override
    public ProviderResult lookup(String number) {
        String e164 = toE164(number);
        if (e164 == null) return null;

        ListedNumber entry = index.find(e164);
        if (entry == null) return null;

        return toResult(entry);
    }

    ProviderResult toResult(ListedNumber entry) {
        return new ProviderResult(id, ProviderResult.Rating.NEGATIVE, getCategory(entry),
                entry.getName(), ProviderResult.UNKNOWN_REVIEW_COUNT);
    }

    /**
     * @return the entry category, or the measure type name if the entry has an
     * official measure, or null
     */
    static String getCategory(ListedNumber entry) {
        if (entry.getCategory() != null) return entry.getCategory();
        MeasureType measureType = entry.getMeasureType();
        if (measureType != null && measureType != MeasureType.NONE) return measureType.name();
        return null;
    }

    /**
     * Converts a number as produced by {@code NumberUtils.normalizeNumber()} to E.164.
     *
     * <ul>
     *     <li>{@code +4915112345678} &rarr; unchanged;</li>
     *     <li>{@code 004915112345678} &rarr; {@code +4915112345678};</li>
     *     <li>{@code 015112345678} (national, trunk zero) &rarr; {@code +4915112345678}
     *     (German numbering is assumed);</li>
     *     <li>{@code 4915112345678} (digits only, no trunk zero) &rarr; {@code +4915112345678}.</li>
     * </ul>
     *
     * @return the E.164 number, or null if the input is empty or not a phone number
     * (hidden numbers, letters, short codes, ...)
     */
    static String toE164(String number) {
        if (number == null) return null;

        String s = stripSeparators(number);
        if (s.isEmpty()) return null;

        if (s.charAt(0) == '+') {
            return s.length() > 1 && isDigits(s, 1) ? s : null;
        }
        if (!isDigits(s, 0)) return null;

        if (s.startsWith("00")) {
            return s.length() > 2 ? "+" + s.substring(2) : null;
        }
        if (s.startsWith("0")) {
            return GermanNumberNormalizer.normalize(s);
        }
        // Short digit strings without a trunk prefix are service/short codes, not E.164
        return s.length() >= MIN_INTERNATIONAL_DIGITS ? "+" + s : null;
    }

    private static String stripSeparators(String number) {
        StringBuilder sb = new StringBuilder(number.length());
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' || c == '/'
                    || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isDigits(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return from < s.length();
    }

    @Override
    public String toString() {
        return "ListedNumbersProvider{id='" + id + "', index=" + index + '}';
    }

}
