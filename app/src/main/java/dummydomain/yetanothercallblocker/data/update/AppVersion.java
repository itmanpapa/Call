package dummydomain.yetanothercallblocker.data.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A version of the app as used in {@code versionName} and in the release tags
 * ({@code 0.11.0}, {@code v0.11.0}, {@code 0.11.0-debug}).
 *
 * <p>Comparison rules:</p>
 * <ul>
 *     <li>a leading {@code v} / {@code V} and surrounding whitespace are ignored;</li>
 *     <li>the numeric parts are compared as numbers ({@code 0.10.0 > 0.9.0}), missing
 *     parts count as 0 ({@code 1.2 == 1.2.0});</li>
 *     <li>build markers ({@code -debug}, {@code -dev}, {@code -snapshot}, {@code -local}
 *     and build metadata after {@code +}) are ignored: {@code 0.11.0-debug == 0.11.0};</li>
 *     <li>any other suffix marks a pre-release ({@code -beta1}, {@code -rc.2}), which is
 *     older than the release with the same numbers; two pre-releases are compared
 *     by their suffix (numbers in it numerically).</li>
 * </ul>
 *
 * <p>Immutable. Plain Java.</p>
 */
public final class AppVersion implements Comparable<AppVersion> {

    /** Suffixes that describe how a build was made, not which version it is. */
    private static final String[] BUILD_MARKERS = {"debug", "dev", "snapshot", "local"};

    private static final int MAX_PARTS = 8;

    private final String original;
    private final List<Integer> numbers;
    /** Lower-case pre-release suffix without the dash, or empty for a release. */
    private final String preRelease;

    private AppVersion(String original, List<Integer> numbers, String preRelease) {
        this.original = original;
        this.numbers = numbers;
        this.preRelease = preRelease;
    }

    /**
     * @return the parsed version, or null if the string doesn't start with a number
     * (after an optional "v")
     */
    public static AppVersion parse(String version) {
        if (version == null) return null;
        String s = version.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);

        // build metadata is never significant
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);

        List<Integer> numbers = new ArrayList<>();
        int i = 0;
        while (i < s.length() && numbers.size() < MAX_PARTS) {
            int start = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i == start) break;
            try {
                numbers.add(Integer.parseInt(s.substring(start, i)));
            } catch (NumberFormatException e) {
                return null; // absurdly long number
            }
            if (i < s.length() && s.charAt(i) == '.'
                    && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) {
                i++;
            } else {
                break;
            }
        }
        if (numbers.isEmpty()) return null;

        // drop trailing zeros so that 1.2 == 1.2.0
        while (numbers.size() > 1 && numbers.get(numbers.size() - 1) == 0) {
            numbers.remove(numbers.size() - 1);
        }

        String suffix = s.substring(i);
        while (!suffix.isEmpty() && "-._ ".indexOf(suffix.charAt(0)) >= 0) {
            suffix = suffix.substring(1);
        }
        suffix = stripBuildMarkers(suffix.toLowerCase(Locale.ROOT));

        return new AppVersion(version.trim(), Collections.unmodifiableList(numbers), suffix);
    }

    /** Removes build markers, e.g. "beta1-debug" becomes "beta1", "debug" becomes "". */
    private static String stripBuildMarkers(String suffix) {
        String[] parts = suffix.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || isBuildMarker(part)) continue;
            if (sb.length() > 0) sb.append('-');
            sb.append(part);
        }
        return sb.toString();
    }

    private static boolean isBuildMarker(String part) {
        for (String marker : BUILD_MARKERS) {
            if (marker.equals(part)) return true;
        }
        return false;
    }

    /**
     * @return true if {@code candidate} is a newer version than {@code current};
     * false if either can't be parsed
     */
    public static boolean isNewer(String candidate, String current) {
        AppVersion c = parse(candidate);
        AppVersion cur = parse(current);
        return c != null && cur != null && c.compareTo(cur) > 0;
    }

    /** @return the string this version was parsed from (trimmed) */
    public String getOriginal() {
        return original;
    }

    /** @return the version without "v" and build markers, e.g. "0.11.0" or "0.12.0-beta1" */
    public String getDisplayName() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(numbers.size(), 3); i++) {
            if (i > 0) sb.append('.');
            sb.append(i < numbers.size() ? numbers.get(i) : 0);
        }
        if (!preRelease.isEmpty()) sb.append('-').append(preRelease);
        return sb.toString();
    }

    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    @Override
    public int compareTo(AppVersion o) {
        int n = Math.max(numbers.size(), o.numbers.size());
        for (int i = 0; i < n; i++) {
            int a = i < numbers.size() ? numbers.get(i) : 0;
            int b = i < o.numbers.size() ? o.numbers.get(i) : 0;
            if (a != b) return Integer.compare(a, b);
        }
        if (preRelease.isEmpty() || o.preRelease.isEmpty()) {
            // a release is newer than a pre-release of the same version
            return Boolean.compare(preRelease.isEmpty(), o.preRelease.isEmpty());
        }
        return compareNatural(preRelease, o.preRelease);
    }

    /** Compares strings with runs of digits compared as numbers ("rc10" > "rc9"). */
    static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = stripLeadingZeros(a.substring(si, i));
                String nb = stripLeadingZeros(b.substring(sj, j));
                if (na.length() != nb.length()) return Integer.compare(na.length(), nb.length());
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (ca != cb) return Character.compare(ca, cb);
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String stripLeadingZeros(String s) {
        int k = 0;
        while (k < s.length() - 1 && s.charAt(k) == '0') k++;
        return s.substring(k);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppVersion)) return false;
        AppVersion that = (AppVersion) o;
        return numbers.equals(that.numbers) && preRelease.equals(that.preRelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(numbers, preRelease);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

}
