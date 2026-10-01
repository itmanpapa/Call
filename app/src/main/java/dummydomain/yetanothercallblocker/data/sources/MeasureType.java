package dummydomain.yetanothercallblocker.data.sources;

import java.util.Locale;

/**
 * Type of an official measure taken by the Bundesnetzagentur against a number
 * (see https://www.bundesnetzagentur.de/massnahmenliste).
 */
public enum MeasureType {

    /** "Abschaltung der Rufnummer(n)": the number has been disconnected. */
    DISCONNECTION,

    /**
     * "Rechnungslegungs- und Inkassierungsverbot" (often combined with
     * "Auszahlungsverbot"): calls to the number may not be billed or collected.
     */
    BILLING_PROHIBITION,

    /** Any other recognized prohibition ("Portierungsverbot", "Geschäftsmodellverbot", ...). */
    OTHER_PROHIBITION,

    /** A measure text was present but not recognized. */
    UNKNOWN,

    /** No measure at all (e.g. entries from user-imported CSV lists). */
    NONE;

    /**
     * Maps the German measure text from the list to a {@link MeasureType}.
     * If several measures are mentioned, disconnection wins over prohibitions.
     */
    public static MeasureType fromGermanText(String text) {
        if (text == null) return NONE;

        String s = text.toLowerCase(Locale.GERMAN).trim();
        if (s.isEmpty()) return NONE;

        if (s.contains("abschaltung") || s.contains("abgeschaltet")) return DISCONNECTION;
        if (s.contains("rechnungslegung") || s.contains("inkass")
                || s.contains("auszahlung")) return BILLING_PROHIBITION;
        if (s.contains("verbot") || s.contains("untersagung")) return OTHER_PROHIBITION;

        return UNKNOWN;
    }

}
