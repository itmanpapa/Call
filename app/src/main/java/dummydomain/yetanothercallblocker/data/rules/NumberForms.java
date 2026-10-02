package dummydomain.yetanothercallblocker.data.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The forms of an incoming number the rules are matched against: the number as received
 * (digits and a leading "+" only), the international form ("+4990012345") and the
 * national form ("090012345", only for numbers of the user's country).
 *
 * <p>The national form is derived as "0" + national significant number. That is right
 * for Germany and most of Europe, not for every country (e.g. Italy keeps the leading
 * zero in the international form) - international patterns are always exact.</p>
 */
public final class NumberForms {

    /** As received: digits and a leading "+". */
    public final String received;

    /** International form ("+49..."), or null if unknown. */
    public final String international;

    /** National form ("0..."), or null if the number is foreign or the country unknown. */
    public final String national;

    private NumberForms(String received, String international, String national) {
        this.received = received;
        this.international = international;
        this.national = national;
    }

    /**
     * @param number            the number as received (may contain separators)
     * @param normalizedNumber  the number normalized by the app (usually E.164), may be null
     * @param homeCallingCode   the calling code of the user's country without "+" ("49"),
     *                          may be null
     */
    public static NumberForms of(String number, String normalizedNumber, String homeCallingCode) {
        String received = clean(number);

        String international = null;
        String normalized = clean(normalizedNumber);
        if (normalized.startsWith("+") && normalized.length() > 1) {
            international = normalized;
        } else if (received.startsWith("+") && received.length() > 1) {
            international = received;
        } else if (received.startsWith("00") && received.length() > 2) {
            international = "+" + received.substring(2);
        } else if (received.startsWith("0") && received.length() > 1
                && homeCallingCode != null && !homeCallingCode.isEmpty()) {
            international = "+" + homeCallingCode + received.substring(1);
        }

        String national = null;
        if (international != null && homeCallingCode != null && !homeCallingCode.isEmpty()
                && international.startsWith("+" + homeCallingCode)
                && international.length() > homeCallingCode.length() + 1) {
            national = "0" + international.substring(homeCallingCode.length() + 1);
        }

        return new NumberForms(received, international, national);
    }

    /** @return the number with digits and a leading "+" only (never null) */
    static String clean(String number) {
        if (number == null) return "";
        StringBuilder sb = new StringBuilder(number.length());
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if (c == '+' && sb.length() == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** @return the distinct non-empty forms */
    public List<String> all() {
        List<String> result = new ArrayList<>(3);
        add(result, received);
        add(result, international);
        add(result, national);
        return Collections.unmodifiableList(result);
    }

    private static void add(List<String> list, String value) {
        if (value != null && !value.isEmpty() && !list.contains(value)) list.add(value);
    }

    /**
     * @return true if the number certainly belongs to another country calling code,
     * false if it's domestic or that can't be told (no international form or unknown
     * home country)
     */
    public boolean isForeign(String homeCallingCode) {
        if (homeCallingCode == null || homeCallingCode.isEmpty()) return false;
        if (international == null) return false;
        return !international.startsWith("+" + homeCallingCode);
    }

    /** @return a stable key to recognize the same caller (the international form if known) */
    public String key() {
        return international != null ? international : received;
    }

    @Override
    public String toString() {
        return "NumberForms{" + received + ", " + international + ", " + national + '}';
    }

}
