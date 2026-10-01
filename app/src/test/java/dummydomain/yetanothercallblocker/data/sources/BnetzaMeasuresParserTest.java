package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BnetzaMeasuresParserTest {

    private final BnetzaMeasuresParser parser = new BnetzaMeasuresParser();

    @Test
    public void parsesHtmlSample() throws IOException {
        ParseResult result = parser.parseHtml(readResource("bnetza_massnahmenliste_sample.html"));
        List<ListedNumber> entries = result.getEntries();

        // 1 + 1 + 1 + 2 + 3 + 1 + 1 (wildcard) + 2 (range prefixes) + 1 = 13
        assertEquals(13, entries.size());

        ListedNumber first = entries.get(0);
        assertEquals("+4971100000001", first.getNumber());
        assertNull(first.getPrefix());
        assertEquals(LocalDate.of(2025, 9, 16), first.getDate());
        assertEquals("Spam-SMS", first.getCategory());
        assertEquals(MeasureType.DISCONNECTION, first.getMeasureType());
        assertEquals("Abschaltung der Rufnummer", first.getMeasureText());
        assertTrue(first.getRawText().contains("071100000001"));

        // Two numbers in one cell, comma-separated
        ListedNumber a = find(entries, "+4915100000004");
        ListedNumber b = find(entries, "+4916000000005");
        assertEquals("Spam-Messenger", a.getCategory());
        assertEquals(LocalDate.of(2025, 4, 24), b.getDate());

        // Three numbers separated by <br>
        assertNotNull(find(entries, "+4917600000006"));
        assertNotNull(find(entries, "+4917600000007"));
        assertEquals("SMS/Messenger mit Zahlungsaufforderung",
                find(entries, "+4917600000008").getCategory());

        // Foreign number, billing prohibition
        ListedNumber ping = find(entries, "+37500000009");
        assertEquals("Ping-Anrufe", ping.getCategory());
        assertEquals(MeasureType.BILLING_PROHIBITION, ping.getMeasureType());

        // Wildcard entry
        ListedNumber wildcard = findPrefix(entries, "+49900100010");
        assertEquals(MeasureType.BILLING_PROHIBITION, wildcard.getMeasureType());
        assertTrue(wildcard.matches("+499001000100"));
        assertTrue(wildcard.matches("+499001000109"));
        assertFalse(wildcard.matches("+499001000110"));

        // Range 09001000020–09001000039 as two prefixes
        assertNotNull(findPrefix(entries, "+49900100002"));
        assertNotNull(findPrefix(entries, "+49900100003"));

        // &nbsp; in category, space inside the number
        assertEquals("Spam Fax", find(entries, "+4915200000010").getCategory());

        // "unbekannt" is reported
        assertEquals(1, result.getSkipped().size());
        ParseResult.SkippedLine skipped = result.getSkipped().get(0);
        assertTrue(skipped.getRawText().contains("unbekannt"));
        assertTrue(skipped.getReason().contains("invalid number"));
    }

    @Test
    public void parsesPdfTextSample() throws IOException {
        ParseResult result;
        try (Reader reader = openResource("bnetza_massnahmenliste_sample.txt")) {
            result = parser.parseText(reader);
        }
        List<ListedNumber> entries = result.getEntries();

        assertEquals(7, entries.size());

        ListedNumber fax = find(entries, "+4915200000101");
        assertEquals(LocalDate.of(2025, 12, 29), fax.getDate());
        assertEquals("Spam Fax", fax.getCategory());
        assertEquals("Abschaltung der Rufnummer", fax.getMeasureText());

        // Number list wrapped over three lines
        for (String n : Arrays.asList("+4971100000102", "+4971100000103", "+4971100000104")) {
            ListedNumber e = find(entries, n);
            assertEquals("Spam-SMS", e.getCategory());
            assertEquals(LocalDate.of(2025, 12, 16), e.getDate());
            assertEquals(MeasureType.DISCONNECTION, e.getMeasureType());
        }

        // Wrapped measure text
        ListedNumber dialer = findPrefix(entries, "+49900100020");
        assertEquals("Telefonie-Dialer", dialer.getCategory());
        assertEquals("Rechnungslegungs- und Inkassierungsverbot", dialer.getMeasureText());
        assertEquals(MeasureType.BILLING_PROHIBITION, dialer.getMeasureType());

        // Wrapped category text stays in the category column
        ListedNumber sms = find(entries, "+496900000105");
        assertEquals("SMS/Messenger mit Zahlungsaufforderung", sms.getCategory());
        assertEquals("Abschaltung der Rufnummer", sms.getMeasureText());

        // After a page break and a repeated header
        ListedNumber ping = find(entries, "+37500000106");
        assertEquals("Ping-Anrufe", ping.getCategory());
        assertEquals(LocalDate.of(2025, 11, 3), ping.getDate());

        // 31.02.2025 is not a valid date
        assertEquals(1, result.getSkipped().size());
        assertTrue(result.getSkipped().get(0).getReason().startsWith("invalid date"));
        assertEquals(20, result.getSkipped().get(0).getLineNumber());
    }

    @Test
    public void parsesTextWithoutHeaderHeuristically() throws IOException {
        String text = "24.04.2025 015112345678, 016012345678 Spam-SMS Abschaltung der Rufnummern\n"
                + "09.09.2025 0421 1234567 Ping-Anrufe Rechnungslegungs- und\n"
                + "Inkassierungsverbot\n"
                + "10.09.2025 017612345678 Sonstiges Abschaltung der Rufnummern\n"
                + "017612345679\n";
        ParseResult result = parser.parseText(new StringReader(text));
        List<ListedNumber> entries = result.getEntries();

        assertEquals(5, entries.size());
        assertEquals("Spam-SMS", find(entries, "+4916012345678").getCategory());

        ListedNumber ping = find(entries, "+494211234567");
        assertEquals("Ping-Anrufe", ping.getCategory());
        assertEquals("Rechnungslegungs- und Inkassierungsverbot", ping.getMeasureText());

        assertEquals(LocalDate.of(2025, 9, 10), find(entries, "+4917612345679").getDate());
        assertTrue(result.getSkipped().isEmpty());
    }

    @Test
    public void parsesRowsWithDifferentColumnOrder() {
        List<List<String>> rows = Arrays.asList(
                Arrays.asList("Rufnummer", "Maßnahme", "Datum"),
                Arrays.asList("015112345678", "Abschaltung", "1.2.25"),
                Arrays.asList("", "", ""),
                Arrays.asList("015112345679", "Abschaltung", "kein Datum"));

        ParseResult result = parser.parseRows(rows);

        assertEquals(1, result.getEntries().size());
        ListedNumber e = result.getEntries().get(0);
        assertEquals("+4915112345678", e.getNumber());
        assertEquals(LocalDate.of(2025, 2, 1), e.getDate());
        assertNull(e.getCategory());
        assertEquals(MeasureType.DISCONNECTION, e.getMeasureType());

        assertEquals(1, result.getSkipped().size());
        assertEquals(4, result.getSkipped().get(0).getLineNumber());
    }

    @Test
    public void partiallyInvalidRowKeepsValidNumbers() {
        List<List<String>> rows = Arrays.asList(
                Arrays.asList("01.01.2025", "015112345678, ???", "Spam-SMS", "Abschaltung"));

        ParseResult result = parser.parseRows(rows);

        assertEquals(1, result.getEntries().size());
        assertEquals(1, result.getSkipped().size());
        assertTrue(result.getSkipped().get(0).getReason().startsWith("partially parsed"));
    }

    @Test
    public void measureTypes() {
        assertEquals(MeasureType.DISCONNECTION, MeasureType.fromGermanText("Abschaltung der Rufnummern"));
        assertEquals(MeasureType.BILLING_PROHIBITION,
                MeasureType.fromGermanText("Rechnungslegungs- und Inkassierungsverbot"));
        assertEquals(MeasureType.BILLING_PROHIBITION,
                MeasureType.fromGermanText("Inkassierungsverbot sowie Auszahlungsverbot"));
        assertEquals(MeasureType.OTHER_PROHIBITION, MeasureType.fromGermanText("Portierungsverbot"));
        assertEquals(MeasureType.UNKNOWN, MeasureType.fromGermanText("Sonstiges"));
        assertEquals(MeasureType.NONE, MeasureType.fromGermanText(" "));
    }

    @Test
    public void htmlToText() {
        assertEquals("Maßnahme", BnetzaMeasuresParser.htmlToText("<b>Ma&szlig;nahme</b>"));
        assertEquals("a\nb", BnetzaMeasuresParser.htmlToText("a<br>b"));
        assertEquals("– x", BnetzaMeasuresParser.htmlToText("&#8211;&nbsp;x"));
        assertEquals("ä", BnetzaMeasuresParser.htmlToText("&#xE4;"));
    }

    private static ListedNumber find(List<ListedNumber> entries, String number) {
        for (ListedNumber e : entries) {
            if (number.equals(e.getNumber())) return e;
        }
        throw new AssertionError("Not found: " + number + " in " + entries);
    }

    private static ListedNumber findPrefix(List<ListedNumber> entries, String prefix) {
        for (ListedNumber e : entries) {
            if (prefix.equals(e.getPrefix())) return e;
        }
        throw new AssertionError("Prefix not found: " + prefix + " in " + entries);
    }

    static Reader openResource(String name) {
        InputStream in = BnetzaMeasuresParserTest.class.getClassLoader()
                .getResourceAsStream("sources/" + name);
        if (in == null) throw new AssertionError("Missing resource " + name);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    static String readResource(String name) throws IOException {
        try (Reader reader = openResource(name); Scanner scanner = new Scanner(reader)) {
            return scanner.useDelimiter("\\A").next();
        }
    }

}
