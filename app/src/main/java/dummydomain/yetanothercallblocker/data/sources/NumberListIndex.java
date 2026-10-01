package dummydomain.yetanothercallblocker.data.sources;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Immutable in-memory lookup structure over a list of {@link ListedNumber}.
 *
 * <ul>
 *     <li>exact numbers are kept in a {@link HashMap}: O(1);</li>
 *     <li>prefixes are kept in a second map, grouped by the set of prefix lengths
 *     present in the list; a lookup probes only those lengths, longest first, so
 *     the longest matching prefix wins and a lookup costs at most
 *     (number of distinct prefix lengths) hash probes, i.e. at most ~16.</li>
 * </ul>
 *
 * <p>An exact entry always wins over a prefix entry. If the same number or prefix
 * occurs several times, the first occurrence is kept.</p>
 *
 * <p>Keys are compared as is, so both the entries and the looked-up numbers must be
 * in the same form (E.164 with "+", as produced by {@link GermanNumberNormalizer}).
 * Instances are immutable and thread-safe.</p>
 */
public final class NumberListIndex {

    private static final NumberListIndex EMPTY = new NumberListIndex(Collections.emptyList());

    private final Map<String, ListedNumber> exact;
    private final Map<String, ListedNumber> prefixes;
    /** Distinct prefix lengths in descending order. */
    private final int[] prefixLengths;

    public NumberListIndex(Collection<ListedNumber> entries) {
        Map<String, ListedNumber> exact = new HashMap<>();
        Map<String, ListedNumber> prefixes = new HashMap<>();
        TreeSet<Integer> lengths = new TreeSet<>(Collections.reverseOrder());

        for (ListedNumber entry : entries) {
            if (entry == null) continue;
            if (entry.isPrefix()) {
                String prefix = entry.getPrefix();
                if (prefix.isEmpty()) continue; // would match everything
                if (prefixes.putIfAbsent(prefix, entry) == null) {
                    lengths.add(prefix.length());
                }
            } else {
                exact.putIfAbsent(entry.getNumber(), entry);
            }
        }

        this.exact = exact;
        this.prefixes = prefixes;

        int[] lengthArray = new int[lengths.size()];
        int i = 0;
        for (Integer length : lengths) lengthArray[i++] = length;
        this.prefixLengths = lengthArray;
    }

    public static NumberListIndex empty() {
        return EMPTY;
    }

    /**
     * Finds the entry covering the number: the exact entry if present, otherwise
     * the entry with the longest matching prefix.
     *
     * @param number number in the same form as the entries (E.164)
     * @return the entry, or {@code null} if the number is not listed
     */
    public ListedNumber find(String number) {
        if (number == null || number.isEmpty()) return null;

        ListedNumber entry = exact.get(number);
        if (entry != null) return entry;

        return findPrefix(number);
    }

    /**
     * @return the entry with the longest prefix of the number (exact entries are
     * ignored), or {@code null}
     */
    public ListedNumber findPrefix(String number) {
        if (number == null) return null;

        int numberLength = number.length();
        for (int length : prefixLengths) {
            if (length > numberLength) continue;
            ListedNumber entry = prefixes.get(length == numberLength
                    ? number : number.substring(0, length));
            if (entry != null) return entry;
        }
        return null;
    }

    public boolean contains(String number) {
        return find(number) != null;
    }

    /** Number of distinct exact numbers. */
    public int getExactCount() {
        return exact.size();
    }

    /** Number of distinct prefixes. */
    public int getPrefixCount() {
        return prefixes.size();
    }

    public int size() {
        return exact.size() + prefixes.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public String toString() {
        return "NumberListIndex{exact=" + exact.size() + ", prefixes=" + prefixes.size()
                + ", prefixLengths=" + Arrays.toString(prefixLengths) + '}';
    }

}
