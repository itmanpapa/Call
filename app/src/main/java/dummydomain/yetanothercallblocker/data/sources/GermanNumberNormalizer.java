package dummydomain.yetanothercallblocker.data.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes phone numbers as they are written in German lists to E.164.
 *
 * <ul>
 *     <li>{@code 0151 123 456 78}, {@code 0151/12345678}, {@code (030) 123-456} &rarr; {@code +49...}</li>
 *     <li>{@code 0049 30 1234567}, {@code +49 (0)30 1234567} &rarr; {@code +4930...}</li>
 *     <li>{@code 00375 29 1234567}, {@code +375 ...} &rarr; foreign E.164 number</li>
 * </ul>
 *
 * <p>In addition, {@link #parseSpecs(String, List)} understands the notations used for
 * blocks of numbers: trailing wildcards ({@code 0900 1234 5x}, {@code 0900123*},
 * {@code 0900123...}) and ranges ({@code 09001234500-09001234599},
 * {@code 0900 1234 50 bis 59}).</p>
 *
 * <p>Plain Java, no Android dependencies.</p>
 */
public final class GermanNumberNormalizer {

    public static final String COUNTRY_CODE = "49";

    /** Max digits of a German national significant number (E.164: 15 - 2). */
    static final int DE_MAX_NSN_LENGTH = 13;
    /** Min digits of a German national significant number we accept as an exact number. */
    static final int DE_MIN_NSN_LENGTH = 6;
    /** Min digits of a German national prefix (e.g. {@code 900} for {@code 0900*}). */
    static final int DE_MIN_PREFIX_NSN_LENGTH = 3;

    static final int MAX_E164_DIGITS = 15;
    static final int MIN_FOREIGN_DIGITS = 8;
    static final int MIN_FOREIGN_PREFIX_DIGITS = 4;

    /** Upper bound of prefixes a single range may expand to. */
    static final int MAX_RANGE_PREFIXES = 100;
    /**
     * Upper bound of numbers in a single range, so that two unrelated numbers joined
     * by a hyphen are not turned into a block of millions of numbers.
     */
    static final long MAX_RANGE_SIZE = 100_000;

    /** Characters used purely for formatting. */
    private static final Pattern FORMATTING_CHARS = Pattern.compile(
            "[\\s\\u00A0\\u2007\\u2009\\u202F/\\-\\u2010\\u2011\\u2012.()\\[\\]'\"]");

    /** "+49 (0) 30 ..." – the trunk zero in parentheses. */
    private static final Pattern TRUNK_ZERO_IN_PARENS = Pattern.compile("\\(\\s*0\\s*\\)");

    /** Wildcard characters at the end of an entry. "#", "_" and "%" are YACB pattern chars. */
    private static final Pattern TRAILING_WILDCARD = Pattern.compile(
            "^(.*?\\d)\\s*([xX*?#_%\\u2026]|\\.\\.\\.)+\\s*$");

    /** Separators of a range: "bis", en/em dash or a hyphen. */
    private static final Pattern RANGE_SEPARATOR = Pattern.compile(
            "\\s+bis\\s+|\\s*[\\u2013\\u2014]\\s*|\\s*-\\s*");

    /** Separators between several numbers in one cell. */
    private static final Pattern LIST_SEPARATOR = Pattern.compile(
            "[,;|\\r\\n]+|\\s+und\\s+|\\s+sowie\\s+");

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private GermanNumberNormalizer() {}

    /**
     * A parsed number specification: either an exact E.164 number or an E.164 prefix.
     */
    public static final class NumberSpec {

        private final String value;
        private final boolean prefix;

        NumberSpec(String value, boolean prefix) {
            this.value = value;
            this.prefix = prefix;
        }

        public static NumberSpec exact(String e164) {
            return new NumberSpec(e164, false);
        }

        public static NumberSpec prefix(String e164Prefix) {
            return new NumberSpec(e164Prefix, true);
        }

        /** E.164 number or prefix, always starting with "+". */
        public String getValue() {
            return value;
        }

        public boolean isPrefix() {
            return prefix;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NumberSpec)) return false;
            NumberSpec that = (NumberSpec) o;
            return prefix == that.prefix && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode() * 31 + (prefix ? 1 : 0);
        }

        @Override
        public String toString() {
            return prefix ? value + "*" : value;
        }
    }

    /**
     * Normalizes a single exact number to E.164.
     *
     * @return E.164 number ({@code +4915112345678}) or {@code null} if the input
     * is not a plausible phone number
     */
    public static String normalize(String raw) {
        String digits = toInternationalDigits(raw);
        if (digits == null || !isPlausible(digits, false)) return null;
        return "+" + digits;
    }

    /**
     * Normalizes a number prefix (the part before a wildcard) to an E.164 prefix.
     *
     * @return E.164 prefix ({@code +4990012345}) or {@code null} if invalid or too short
     */
    public static String normalizePrefix(String raw) {
        String digits = toInternationalDigits(raw);
        if (digits == null || !isPlausible(digits, true)) return null;
        return "+" + digits;
    }

    /**
     * Converts a formatted number to international digits without "+",
     * or returns {@code null} if it contains anything but digits and formatting.
     */
    static String toInternationalDigits(String raw) {
        if (raw == null) return null;

        String s = raw.trim();
        if (s.isEmpty()) return null;

        boolean international = s.startsWith("+") || s.startsWith("00");
        if (international) {
            s = TRUNK_ZERO_IN_PARENS.matcher(s).replaceAll("");
        }

        s = FORMATTING_CHARS.matcher(s).replaceAll("");

        if (s.startsWith("+")) {
            s = s.substring(1);
            if (s.startsWith("+")) return null;
        } else if (s.startsWith("00")) {
            s = s.substring(2);
        } else if (s.startsWith("0")) {
            s = COUNTRY_CODE + s.substring(1);
        } else if (s.startsWith(COUNTRY_CODE) && s.length() >= 11 && isDigits(s)) {
            // International number written without "+" (common in CSV exports): 4915112345678
            international = true;
        } else {
            // National number without the trunk zero is ambiguous
            return null;
        }

        if (!isDigits(s)) return null;

        // "+490151..." – a superfluous trunk zero after the country code
        if (international && s.startsWith(COUNTRY_CODE + "0")) {
            s = COUNTRY_CODE + s.substring(COUNTRY_CODE.length() + 1);
        }

        if (s.isEmpty() || s.startsWith("0")) return null;

        return s;
    }

    private static boolean isPlausible(String digits, boolean prefix) {
        if (digits.length() > MAX_E164_DIGITS) return false;

        if (digits.startsWith(COUNTRY_CODE)) {
            int nsn = digits.length() - COUNTRY_CODE.length();
            if (nsn > DE_MAX_NSN_LENGTH) return false;
            return nsn >= (prefix ? DE_MIN_PREFIX_NSN_LENGTH : DE_MIN_NSN_LENGTH);
        }

        return digits.length() >= (prefix ? MIN_FOREIGN_PREFIX_DIGITS : MIN_FOREIGN_DIGITS);
    }

    private static boolean isDigits(String s) {
        return !s.isEmpty() && DIGITS.matcher(s).matches();
    }

    /**
     * Parses a text that may contain one or several numbers, wildcards or ranges.
     *
     * @param text   cell text, e.g. {@code "015112345678, 0160 9876543"}
     * @param errors receives a message for every part that could not be parsed (may be null)
     * @return parsed specs in source order, never null
     */
    public static List<NumberSpec> parseSpecs(String text, List<String> errors) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();

        List<NumberSpec> result = new ArrayList<>();
        for (String part : splitList(text)) {
            List<NumberSpec> specs = parseSingleSpec(part);
            if (specs == null) {
                if (errors != null) errors.add("invalid number: \"" + part + "\"");
            } else {
                result.addAll(specs);
            }
        }
        return result;
    }

    /**
     * Splits a cell into separate number texts. Commas, semicolons and line breaks
     * always separate; whitespace separates only if every token is a complete number
     * on its own (so that "0151 12345678" stays one number).
     */
    static List<String> splitList(String text) {
        List<String> parts = new ArrayList<>();
        for (String part : LIST_SEPARATOR.split(text)) {
            part = part.trim();
            if (part.isEmpty()) continue;

            String[] tokens = part.split("\\s+");
            if (tokens.length > 1 && allTokensAreFullNumbers(tokens)) {
                Collections.addAll(parts, tokens);
            } else {
                parts.add(part);
            }
        }
        return parts;
    }

    private static boolean allTokensAreFullNumbers(String[] tokens) {
        for (String token : tokens) {
            // Long enough to be a complete number by itself, not just an area code
            if (!(token.startsWith("0") || token.startsWith("+"))) return false;
            if (token.replaceAll("\\D", "").length() < 9) return false;
            if (normalize(token) == null) return false;
        }
        return true;
    }

    /**
     * Parses one number, wildcard entry or range.
     *
     * @return list of specs, or {@code null} if invalid
     */
    static List<NumberSpec> parseSingleSpec(String part) {
        part = part.trim();
        if (part.isEmpty()) return null;

        Matcher wildcard = TRAILING_WILDCARD.matcher(part);
        if (wildcard.matches()) {
            String prefix = normalizePrefix(wildcard.group(1));
            return prefix != null ? Collections.singletonList(NumberSpec.prefix(prefix)) : null;
        }

        // Ranges first: "09001234500-09001234599" would otherwise be read as one long number
        List<NumberSpec> range = parseRange(part);
        if (range != null) return range;

        String exact = normalize(part);
        return exact != null ? Collections.singletonList(NumberSpec.exact(exact)) : null;
    }

    /**
     * Parses ranges like "09001234500-09001234599", "0900 1234 500 bis 599"
     * or "0900123450 bis 59" (the upper bound may repeat only the last digits).
     * A plain hyphen is also a formatting character ("0151-12345678"), so after a
     * hyphen the upper bound must be a complete number.
     */
    static List<NumberSpec> parseRange(String part) {
        Matcher m = RANGE_SEPARATOR.matcher(part);
        while (m.find()) {
            boolean hyphen = m.group().trim().equals("-");
            String left = part.substring(0, m.start());
            String right = part.substring(m.end());

            String from = normalize(left);
            if (from == null) continue;

            String rightDigits = FORMATTING_CHARS.matcher(right).replaceAll("");
            if (rightDigits.isEmpty()) continue;

            String to;
            if (rightDigits.startsWith("+") || rightDigits.startsWith("0")) {
                to = normalize(right);
            } else if (!hyphen && isDigits(rightDigits) && rightDigits.length() < from.length() - 1) {
                // Abbreviated upper bound: replaces the last digits of the lower bound
                to = from.substring(0, from.length() - rightDigits.length()) + rightDigits;
            } else {
                to = null;
            }

            if (to == null || to.length() != from.length() || to.compareTo(from) < 0) continue;
            if (Long.parseLong(to.substring(1)) - Long.parseLong(from.substring(1)) >= MAX_RANGE_SIZE) {
                return null;
            }

            List<String> prefixes = rangeToPrefixes(from.substring(1), to.substring(1));
            if (prefixes == null) return null;

            List<NumberSpec> specs = new ArrayList<>(prefixes.size());
            for (String prefix : prefixes) {
                if (!isPlausible(prefix, true)) return null;
                String value = "+" + prefix;
                specs.add(prefix.length() == from.length() - 1
                        ? NumberSpec.exact(value) : NumberSpec.prefix(value));
            }
            return specs;
        }
        return null;
    }

    /**
     * Converts an inclusive range of equally long digit strings into the minimal
     * set of prefixes covering exactly that range.
     *
     * @return prefixes, or {@code null} if more than {@link #MAX_RANGE_PREFIXES} would be needed
     */
    static List<String> rangeToPrefixes(String from, String to) {
        if (from.length() != to.length() || from.compareTo(to) > 0) {
            throw new IllegalArgumentException("Invalid range: " + from + " - " + to);
        }

        List<String> result = new ArrayList<>();
        if (!addRangePrefixes(from, to, result)) return null;
        return result;
    }

    private static boolean addRangePrefixes(String from, String to, List<String> out) {
        if (out.size() > MAX_RANGE_PREFIXES) return false;

        if (from.equals(to)) {
            out.add(from);
            return out.size() <= MAX_RANGE_PREFIXES;
        }

        int common = 0;
        while (from.charAt(common) == to.charAt(common)) common++;

        String head = from.substring(0, common);
        String fromRest = from.substring(common);
        String toRest = to.substring(common);

        if (isAll(fromRest, '0') && isAll(toRest, '9')) {
            out.add(head);
            return out.size() <= MAX_RANGE_PREFIXES;
        }

        char lo = fromRest.charAt(0);
        char hi = toRest.charAt(0);
        int tail = fromRest.length() - 1;

        // Lower partial block: head+lo followed by fromRest[1..] .. 99..9
        if (!addRangePrefixes(from, head + lo + repeat('9', tail), out)) return false;

        // Full blocks in between
        for (char d = (char) (lo + 1); d < hi; d++) {
            out.add(head + d);
            if (out.size() > MAX_RANGE_PREFIXES) return false;
        }

        // Upper partial block: head+hi followed by 00..0 .. toRest[1..]
        return addRangePrefixes(head + hi + repeat('0', tail), to, out);
    }

    private static boolean isAll(String s, char c) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != c) return false;
        }
        return true;
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

}
