package dummydomain.yetanothercallblocker.data.rules;

/**
 * What the rules know about an incoming call.
 */
public final class CallFacts {

    /** The number as received, may be empty for hidden numbers. */
    public final String number;

    /** No caller ID (hidden, private, anonymous or empty). */
    public final boolean hidden;

    /** The caller is in the user's contacts (only known with "use contacts" on). */
    public final boolean contact;

    /** Calling code of the user's country without "+" ("49"), or null if unknown. */
    public final String homeCallingCode;

    /** Forms of the number to match (empty forms for hidden numbers). */
    public final NumberForms forms;

    /**
     * @param number           the number as received
     * @param normalizedNumber the number normalized by the app (E.164), may be null
     * @param hidden           no caller ID
     * @param contact          the caller is a contact
     * @param homeCallingCode  the user's country calling code ("49"), may be null
     */
    public CallFacts(String number, String normalizedNumber, boolean hidden, boolean contact,
                     String homeCallingCode) {
        this.number = number != null ? number : "";
        this.hidden = hidden;
        this.contact = contact;
        this.homeCallingCode = homeCallingCode != null && !homeCallingCode.isEmpty()
                ? homeCallingCode : null;
        this.forms = hidden ? NumberForms.of("", null, null)
                : NumberForms.of(number, normalizedNumber, this.homeCallingCode);
    }

    @Override
    public String toString() {
        return "CallFacts{" + forms + (hidden ? ", hidden" : "") + (contact ? ", contact" : "")
                + ", home=" + homeCallingCode + '}';
    }

}
