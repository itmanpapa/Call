package dummydomain.yetanothercallblocker.data.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Number patterns of call rules.
 *
 * <p>Syntax (the same wildcards as the human-readable blacklist patterns):</p>
 * <ul>
 * <li>{@code *} matches any number of digits (also none), {@code #} or {@code ?} exactly
 * one digit, everything else is matched literally; a leading {@code *} also matches
 * the "+" of international numbers ("*" = every number, "*1234" = numbers ending
 * with 1234);</li>
 * <li>the whole number must match: "0900*" is a prefix, "0900" only the number 0900;</li>
 * <li>a leading "00" is the same as "+" ("0044*" == "+44*");</li>
 * <li>separators (spaces, "-", "/", ".", brackets) are ignored;</li>
 * <li>several patterns can be given in one rule, separated by "," ";" or line breaks.</li>
 * </ul>
 *
 * <p>National patterns ("0900*") are matched against the national form of the number,
 * international ones ("+49900*") against the international form, see {@link NumberForms}.</p>
 */
public final class NumberPatterns {

    private static final Pattern LIST_SEPARATOR = Pattern.compile("[,;\\n\\r]+");
    private static final Pattern IGNORED_CHARS = Pattern.compile("[\\s\\-/.()\\[\\]]");
    private static final Pattern VALID_PATTERN = Pattern.compile("\\+?[0-9*#?]+");

    private NumberPatterns() {}

    /**
     * @return the normalized single patterns of a pattern list, invalid ones included
     * (see {@link #isValid}); empty items are dropped
     */
    public static List<String> split(String patternList) {
        if (patternList == null) return Collections.emptyList();

        List<String> result = new ArrayList<>();
        for (String item : LIST_SEPARATOR.split(patternList)) {
            String pattern = normalize(item);
            if (!pattern.isEmpty() && !result.contains(pattern)) result.add(pattern);
        }
        return result;
    }

    /**
     * @return the pattern without separators, "00" replaced with "+" and "?" with "#"
     */
    public static String normalize(String pattern) {
        if (pattern == null) return "";
        String result = IGNORED_CHARS.matcher(pattern).replaceAll("").replace('?', '#');
        if (result.startsWith("00")) result = "+" + result.substring(2);
        return result;
    }

    /** @return whether the single (normalized) pattern is valid */
    public static boolean isValid(String pattern) {
        return pattern != null && VALID_PATTERN.matcher(pattern).matches();
    }

    /**
     * @return whether the pattern list has at least one pattern and all are valid
     */
    public static boolean isValidList(String patternList) {
        List<String> patterns = split(patternList);
        if (patterns.isEmpty()) return false;
        for (String pattern : patterns) {
            if (!isValid(pattern)) return false;
        }
        return true;
    }

    /** @return the canonical text of a pattern list: normalized patterns joined with ", " */
    public static String canonical(String patternList) {
        return join(split(patternList));
    }

    static String join(List<String> patterns) {
        StringBuilder sb = new StringBuilder();
        for (String pattern : patterns) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(pattern);
        }
        return sb.toString();
    }

    /**
     * Compiles the valid patterns of the list into one regular expression
     * (invalid ones are skipped).
     *
     * @return the expression, or null if the list has no valid patterns
     */
    public static Pattern compile(String patternList) {
        return compile(split(patternList));
    }

    static Pattern compile(List<String> patterns) {
        StringBuilder regex = new StringBuilder();
        for (String pattern : patterns) {
            if (!isValid(pattern)) continue;

            if (regex.length() > 0) regex.append('|');
            regex.append("(?:");
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') {
                    // a leading "*" also covers the "+" of international numbers
                    regex.append(i == 0 ? "\\+?[0-9]*" : "[0-9]*");
                } else if (c == '#') {
                    regex.append("[0-9]");
                } else if (c == '+') {
                    regex.append("\\+");
                } else {
                    regex.append(c);
                }
            }
            regex.append(')');
        }
        return regex.length() > 0 ? Pattern.compile(regex.toString()) : null;
    }

}
