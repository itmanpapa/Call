package dummydomain.yetanothercallblocker.data.sources;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for number lists distributed as vCard files (vCard 2.1, 3.0 and 4.0),
 * e.g. a block list exported from Nextcloud:
 *
 * <pre>
 * BEGIN:VCARD
 * VERSION:4.0
 * FN:Gewinnspiel
 * TEL;TYPE=WORK,VOICE:+4915112345678
 * TEL;VALUE=uri:tel:+49-30-1234567
 * CATEGORIES:Rufsperren
 * END:VCARD
 * </pre>
 *
 * <ul>
 *     <li>every {@code TEL} becomes one entry; the name is taken from {@code FN}
 *     (or {@code N} / {@code ORG}), the category from {@code CATEGORIES} and the
 *     comment from {@code NOTE};</li>
 *     <li>folded lines (continuation lines starting with a space or tab), property
 *     groups ({@code item1.TEL}), {@code tel:} URIs, backslash escapes and vCard 2.1
 *     quoted-printable values are handled;</li>
 *     <li>numbers are normalized with {@link GermanNumberNormalizer}; invalid numbers
 *     and cards without numbers are reported in {@link ParseResult#getSkipped()}.</li>
 * </ul>
 *
 * <p>Plain Java, no Android dependencies. Instances are stateless and thread-safe.</p>
 */
public class VcardNumberListParser {

    /** A logical (unfolded) line with the number of its first physical line. */
    static final class ContentLine {
        final String text;
        final int lineNumber;

        ContentLine(String text, int lineNumber) {
            this.text = text;
            this.lineNumber = lineNumber;
        }
    }

    /** A parsed property: upper-case name without group, raw parameters, decoded value. */
    static final class Property {
        final String name;
        final String params;
        final String value;

        Property(String name, String params, String value) {
            this.name = name;
            this.params = params;
            this.value = value;
        }
    }

    private static final class Card {
        final int lineNumber;
        String formattedName;
        String structuredName;
        String organization;
        String category;
        String note;
        final List<ContentLine> numbers = new ArrayList<>();

        Card(int lineNumber) {
            this.lineNumber = lineNumber;
        }

        String name() {
            if (formattedName != null) return formattedName;
            if (structuredName != null) return structuredName;
            return organization;
        }
    }

    /** Parses a UTF-8 (with or without BOM) stream. */
    public ParseResult parse(InputStream inputStream) throws IOException {
        return parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    public ParseResult parse(String content) {
        try {
            return parse(new StringReader(content));
        } catch (IOException e) {
            throw new IllegalStateException(e); // StringReader does not throw
        }
    }

    public ParseResult parse(Reader reader) throws IOException {
        List<ListedNumber> entries = new ArrayList<>();
        List<ParseResult.SkippedLine> skipped = new ArrayList<>();

        Card card = null;
        for (ContentLine line : unfold(reader)) {
            Property property = parseProperty(line.text);
            if (property == null) continue;

            switch (property.name) {
                case "BEGIN":
                    if ("VCARD".equalsIgnoreCase(property.value.trim())) {
                        if (card != null) finishCard(card, entries, skipped); // missing END
                        card = new Card(line.lineNumber);
                    }
                    break;
                case "END":
                    if (card != null && "VCARD".equalsIgnoreCase(property.value.trim())) {
                        finishCard(card, entries, skipped);
                        card = null;
                    }
                    break;
                default:
                    if (card != null) addProperty(card, property, line);
                    break;
            }
        }
        if (card != null) finishCard(card, entries, skipped); // unterminated last card

        return new ParseResult(entries, skipped);
    }

    private static void addProperty(Card card, Property property, ContentLine line) {
        switch (property.name) {
            case "FN":
                card.formattedName = nonEmpty(unescape(property.value));
                break;
            case "N":
                card.structuredName = structuredName(property.value);
                break;
            case "ORG":
                card.organization = nonEmpty(unescape(property.value).replace(';', ' '));
                break;
            case "CATEGORIES":
                card.category = nonEmpty(joinList(property.value));
                break;
            case "NOTE":
                card.note = nonEmpty(unescape(property.value));
                break;
            case "TEL":
                String number = telValue(property.value);
                if (!number.isEmpty()) card.numbers.add(new ContentLine(number, line.lineNumber));
                break;
            default:
                break;
        }
    }

    private static void finishCard(Card card, List<ListedNumber> entries,
                                   List<ParseResult.SkippedLine> skipped) {
        if (card.numbers.isEmpty()) {
            String name = card.name();
            skipped.add(new ParseResult.SkippedLine(card.lineNumber,
                    name != null ? name : "BEGIN:VCARD", "no number"));
            return;
        }

        for (ContentLine number : card.numbers) {
            List<String> errors = new ArrayList<>();
            List<GermanNumberNormalizer.NumberSpec> specs =
                    GermanNumberNormalizer.parseSpecs(number.text, errors);
            if (specs.isEmpty() || !errors.isEmpty()) {
                skipped.add(new ParseResult.SkippedLine(number.lineNumber, number.text,
                        errors.isEmpty() ? "no number" : String.join("; ", errors)));
            }
            for (GermanNumberNormalizer.NumberSpec spec : specs) {
                entries.add(ListedNumber.builder()
                        .spec(spec)
                        .name(card.name())
                        .category(card.category)
                        .comment(card.note)
                        .measureType(MeasureType.NONE)
                        .rawText(number.text)
                        .build());
            }
        }
    }

    /**
     * Joins folded lines (RFC 6350 3.2: a line starting with a space or a tab continues
     * the previous one) and vCard 2.1 quoted-printable soft line breaks.
     */
    static List<ContentLine> unfold(Reader reader) throws IOException {
        BufferedReader br = reader instanceof BufferedReader
                ? (BufferedReader) reader : new BufferedReader(reader);

        List<ContentLine> result = new ArrayList<>();
        StringBuilder current = null;
        int currentLine = 0;
        boolean softBreak = false;

        String line;
        int lineNumber = 0;
        while ((line = br.readLine()) != null) {
            lineNumber++;
            if (lineNumber == 1 && !line.isEmpty() && line.charAt(0) == '﻿') {
                line = line.substring(1);
            }

            if (current != null && softBreak) {
                // quoted-printable: "=" at the end of the line joins the next line as is
                current.setLength(current.length() - 1);
                current.append(line);
            } else if (current != null && !line.isEmpty()
                    && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                current.append(line, 1, line.length());
            } else {
                if (current != null) result.add(new ContentLine(current.toString(), currentLine));
                if (line.trim().isEmpty()) {
                    current = null;
                    softBreak = false;
                    continue;
                }
                current = new StringBuilder(line);
                currentLine = lineNumber;
            }

            softBreak = isQuotedPrintable(current) && current.length() > 0
                    && current.charAt(current.length() - 1) == '=';
        }
        if (current != null) result.add(new ContentLine(current.toString(), currentLine));
        return result;
    }

    private static boolean isQuotedPrintable(CharSequence line) {
        int colon = indexOfUnquoted(line.toString(), ':');
        String head = colon >= 0 ? line.subSequence(0, colon).toString() : line.toString();
        return head.toUpperCase(Locale.ROOT).contains("QUOTED-PRINTABLE");
    }

    /**
     * Splits a content line into name, parameters and value.
     *
     * @return the property, or null if the line has no colon
     */
    static Property parseProperty(String line) {
        int colon = indexOfUnquoted(line, ':');
        if (colon <= 0) return null;

        String head = line.substring(0, colon);
        String value = line.substring(colon + 1);

        int semicolon = head.indexOf(';');
        String name = semicolon >= 0 ? head.substring(0, semicolon) : head;
        String params = semicolon >= 0 ? head.substring(semicolon + 1) : "";

        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(dot + 1); // "item1.TEL"
        name = name.trim().toUpperCase(Locale.ROOT);

        String upperParams = params.toUpperCase(Locale.ROOT);
        if (upperParams.contains("QUOTED-PRINTABLE")) {
            value = decodeQuotedPrintable(value, charsetParam(params));
        }

        return new Property(name, params, value);
    }

    private static int indexOfUnquoted(String s, char c) {
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == c && !quoted) return i;
        }
        return -1;
    }

    private static Charset charsetParam(String params) {
        for (String param : params.split(";")) {
            int eq = param.indexOf('=');
            if (eq > 0 && param.substring(0, eq).trim().equalsIgnoreCase("CHARSET")) {
                try {
                    return Charset.forName(param.substring(eq + 1).trim().replace("\"", ""));
                } catch (RuntimeException e) {
                    break; // unknown charset: fall back to UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    static String decodeQuotedPrintable(String value, Charset charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '=' && i + 2 < value.length()) {
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo);
                    i += 2;
                    continue;
                }
            }
            byte[] bytes = String.valueOf(c).getBytes(charset);
            out.write(bytes, 0, bytes.length);
        }
        return new String(out.toByteArray(), charset);
    }

    /** Removes the "tel:" URI scheme and URI parameters ("tel:+49-30-123;ext=5"). */
    static String telValue(String value) {
        String v = value.trim();
        if (v.regionMatches(true, 0, "tel:", 0, 4)) {
            v = v.substring(4);
            int semicolon = v.indexOf(';');
            if (semicolon >= 0) v = v.substring(0, semicolon);
        }
        return unescape(v).trim();
    }

    /** "Family;Given;Middle;Prefix;Suffix" → "Given Middle Family". */
    private static String structuredName(String value) {
        String[] parts = splitUnescaped(value, ';');
        StringBuilder sb = new StringBuilder();
        int[] order = {3, 1, 2, 0, 4};
        for (int index : order) {
            if (index >= parts.length) continue;
            String part = unescape(parts[index]).trim();
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part);
        }
        return nonEmpty(sb.toString());
    }

    /** "a,b\,c" → "a, b,c". */
    private static String joinList(String value) {
        StringBuilder sb = new StringBuilder();
        for (String part : splitUnescaped(value, ',')) {
            String p = unescape(part).trim();
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    private static String[] splitUnescaped(String value, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                current.append(c).append(value.charAt(++i));
            } else if (c == separator) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts.toArray(new String[0]);
    }

    /** Resolves the vCard escapes {@code \\n \\, \\; \\\\}. */
    static String unescape(String value) {
        if (value.indexOf('\\') < 0) return value;
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                sb.append(next == 'n' || next == 'N' ? '\n' : next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String nonEmpty(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

}
