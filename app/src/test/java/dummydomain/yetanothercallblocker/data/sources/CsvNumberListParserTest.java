package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CsvNumberListParserTest {

    private final CsvNumberListParser parser = new CsvNumberListParser();

    @Test
    public void parsesSemicolonFileWithBomAndHeader() throws IOException {
        // The fixture starts with a UTF-8 BOM
        ParseResult result;
        try (InputStream in = openResource("user_list_semicolon.csv")) {
            result = parser.parse(in);
        }
        List<ListedNumber> entries = result.getEntries();

        assertEquals(4, entries.size());

        ListedNumber first = entries.get(0);
        assertEquals("+4915100000201", first.getNumber());
        assertEquals("Gewinnspiel GmbH", first.getName());
        assertEquals("Werbung", first.getCategory());
        assertEquals("angeblicher Gewinn; Rückruf", first.getComment());
        assertEquals(MeasureType.NONE, first.getMeasureType());
        assertNull(first.getDate());

        ListedNumber second = entries.get(1);
        assertEquals("+493000000202", second.getNumber());
        assertEquals("Inkasso Fake", second.getName());
        assertNull(second.getComment());

        ListedNumber wildcard = entries.get(2);
        assertEquals("+4990010003", wildcard.getPrefix());
        assertNull(wildcard.getName());
        assertEquals("Mehrwertdienst", wildcard.getCategory());

        ListedNumber quoted = entries.get(3);
        assertEquals("+494000000203", quoted.getNumber());
        assertEquals("Name mit \"Anführungszeichen\"", quoted.getName());

        assertEquals(1, result.getSkipped().size());
        ParseResult.SkippedLine skipped = result.getSkipped().get(0);
        assertEquals(6, skipped.getLineNumber());
        assertEquals("keine Nummer;Test;;", skipped.getRawText());
    }

    @Test
    public void detectsCommaWithoutHeader() {
        String csv = "015112345678,Spam Anrufer,Werbung\n"
                + "\n"
                + "016012345678,\"Doe, John\",Betrug,Rückruf nicht nötig\n";
        ParseResult result = parser.parse(csv);

        assertEquals(2, result.getEntries().size());
        ListedNumber second = result.getEntries().get(1);
        assertEquals("+4916012345678", second.getNumber());
        assertEquals("Doe, John", second.getName());
        assertEquals("Betrug", second.getCategory());
        assertEquals("Rückruf nicht nötig", second.getComment());
        assertTrue(result.getSkipped().isEmpty());
    }

    @Test
    public void detectsTabAndNumberInSecondColumn() {
        String csv = "Spam GmbH\t+49 30 1234567\n"
                + "Other\t0151 1234 5678\n";
        ParseResult result = parser.parse(csv);

        assertEquals(2, result.getEntries().size());
        assertEquals("+49301234567", result.getEntries().get(0).getNumber());
        assertEquals("Spam GmbH", result.getEntries().get(0).getName());
        assertEquals("+4915112345678", result.getEntries().get(1).getNumber());
    }

    @Test
    public void singleColumnList() {
        String csv = "# my list\n015112345678\n0900 123 456*\n\n  \nfoo\n";
        ParseResult result = parser.parse(csv);

        assertEquals(2, result.getEntries().size());
        assertEquals("+4915112345678", result.getEntries().get(0).getNumber());
        assertEquals("+49900123456", result.getEntries().get(1).getPrefix());
        assertEquals(1, result.getSkipped().size());
        assertEquals(6, result.getSkipped().get(0).getLineNumber());
    }

    @Test
    public void headerWithColumnsInAnyOrder() {
        String csv = "Kommentar,Telefonnummer,Firma,Kategorie\n"
                + "nervig,030 1234567,ACME,Werbung\n"
                + "nichts,,ACME,Werbung\n";
        ParseResult result = parser.parse(csv);

        assertEquals(1, result.getEntries().size());
        ListedNumber e = result.getEntries().get(0);
        assertEquals("+49301234567", e.getNumber());
        assertEquals("ACME", e.getName());
        assertEquals("Werbung", e.getCategory());
        assertEquals("nervig", e.getComment());

        assertEquals(1, result.getSkipped().size());
        assertEquals("no number", result.getSkipped().get(0).getReason());
    }

    @Test
    public void readsYacbBlacklistBackup() {
        // Format written by BlacklistImporterExporter.writeBackup()
        String csv = "ID,name,pattern,creationTimestamp,numberOfCalls,lastCallTimestamp\n"
                + "1,Spammer,+4915112345678,1600000000000,3,1600000001000\n"
                + "2,Block 0900,+49900*,1600000000000,0,\n";
        ParseResult result = parser.parse(csv);

        assertEquals(2, result.getEntries().size());
        assertEquals("+4915112345678", result.getEntries().get(0).getNumber());
        assertEquals("Spammer", result.getEntries().get(0).getName());
        assertEquals("+49900", result.getEntries().get(1).getPrefix());
    }

    @Test
    public void unknownHeaderIsSkipped() {
        String csv = "A;B\nWerbung;0151 12345678\n";
        ParseResult result = parser.parse(csv);

        assertEquals(1, result.getEntries().size());
        assertEquals("+4915112345678", result.getEntries().get(0).getNumber());
        assertEquals("Werbung", result.getEntries().get(0).getName());
        assertTrue(result.getSkipped().isEmpty());
    }

    @Test
    public void malformedQuotesAreReported() {
        String csv = "015112345678,ok\n\"016012345678,broken\n017612345678,ok\n";
        ParseResult result = parser.parse(csv);

        assertEquals(2, result.getEntries().size());
        assertEquals(1, result.getSkipped().size());
        assertEquals(2, result.getSkipped().get(0).getLineNumber());
    }

    @Test
    public void emptyInput() {
        assertTrue(parser.parse("").getEntries().isEmpty());
        assertTrue(parser.parse("﻿\n\n").getEntries().isEmpty());
    }

    @Test
    public void delimiterDetection() {
        assertEquals(';', CsvNumberListParser.detectDelimiter(Arrays.asList(
                "a;b;c", "\"x,y\";2;3")));
        assertEquals('\t', CsvNumberListParser.detectDelimiter(Arrays.asList(
                "a\tb", "c\td")));
        assertEquals(',', CsvNumberListParser.detectDelimiter(Arrays.asList(
                "015112345678")));
    }

    private static InputStream openResource(String name) {
        InputStream in = CsvNumberListParserTest.class.getClassLoader()
                .getResourceAsStream("sources/" + name);
        if (in == null) throw new AssertionError("Missing resource " + name);
        return in;
    }

}
