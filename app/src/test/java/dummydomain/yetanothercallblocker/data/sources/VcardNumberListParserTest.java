package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VcardNumberListParserTest {

    private final VcardNumberListParser parser = new VcardNumberListParser();

    @Test
    public void realExcerptOfSpamCalllist() throws IOException {
        ParseResult result = parser.parse(
                FritzboxPhonebookParserTest.openResource("spamcalllist_nextcloud_excerpt.vcf"));

        List<ListedNumber> entries = result.getEntries();
        assertEquals(6, entries.size());

        assertEquals("+12603404548", entries.get(0).getNumber());
        assertEquals("Ausland - +1...", entries.get(0).getName());
        assertEquals("Rufsperren", entries.get(0).getCategory());
        assertEquals("+16465535819", entries.get(1).getNumber());

        assertEquals("+4970739144444", entries.get(2).getNumber());
        assertEquals("Inland - 07...", entries.get(2).getName());
        assertEquals("+4971120703144", entries.get(3).getNumber());
        assertEquals("+497816396101", entries.get(4).getNumber());

        // the last card has no END:VCARD and no line break at the end of the file
        assertEquals("+499118818", entries.get(5).getPrefix());
        assertEquals("Inland - 09...", entries.get(5).getName());

        // a number without the trunk zero is ambiguous
        assertEquals(1, result.getSkipped().size());
        assertEquals("71196884113", result.getSkipped().get(0).getRawText());
        assertEquals(15, result.getSkipped().get(0).getLineNumber());
    }

    @Test
    public void foldingGroupsUrisEscapesAndQuotedPrintable() throws IOException {
        ParseResult result = parser.parse(
                FritzboxPhonebookParserTest.openResource("vcard_synthetic.vcf"));

        List<ListedNumber> entries = result.getEntries();
        assertEquals(4, entries.size());

        // folded TEL in a property group, name from N, escaped NOTE, CATEGORIES list
        ListedNumber first = entries.get(0);
        assertEquals("+4915112345678", first.getNumber());
        assertEquals("Dr. Max Muster", first.getName());
        assertEquals("Werbung, Gewinnspiel", first.getCategory());
        assertEquals("Ruft täglich an, angeblich Gewinnspiel\nzweite Zeile", first.getComment());

        // tel: URI with an extension parameter, escaped semicolon in FN
        assertEquals("+49301234567", entries.get(1).getNumber());
        assertEquals("Inkasso; Fake", entries.get(1).getName());

        // vCard 2.1 quoted-printable name with a soft line break, wildcard number
        assertEquals("+4990012345", entries.get(2).getPrefix());
        assertEquals("Gewinnspiel München GmbH", entries.get(2).getName());

        // unterminated last card, name from ORG
        assertEquals("+4922198765432", entries.get(3).getNumber());
        assertEquals("Nur Firma AG", entries.get(3).getName());
        assertNull(entries.get(3).getComment());

        // "keine Nummer" and the card without TEL
        assertEquals(2, result.getSkipped().size());
        assertEquals("keine Nummer", result.getSkipped().get(0).getRawText());
        assertEquals(14, result.getSkipped().get(0).getLineNumber());
        assertEquals("Ohne Nummer", result.getSkipped().get(1).getRawText());
        assertEquals("no number", result.getSkipped().get(1).getReason());
        assertEquals(22, result.getSkipped().get(1).getLineNumber());
    }

    @Test
    public void bomAndLowerCaseProperties() {
        ParseResult result = parser.parse("﻿begin:vcard\nversion:3.0\nfn:Spam\n"
                + "tel;type=cell:0151 12345678\nend:vcard\n");
        assertEquals(1, result.getEntries().size());
        assertEquals("Spam", result.getEntries().get(0).getName());
        assertEquals("+4915112345678", result.getEntries().get(0).getNumber());
    }

    @Test
    public void linesOutsideCardsAreIgnored() {
        ParseResult result = parser.parse("TEL:015112345678\nBEGIN:VCARD\nTEL:015187654321\n"
                + "END:VCARD\nTEL:030123456789\n");
        assertEquals(1, result.getEntries().size());
        assertEquals("+4915187654321", result.getEntries().get(0).getNumber());
        assertTrue(result.getSkipped().isEmpty());
    }

    @Test
    public void unfold() throws IOException {
        List<VcardNumberListParser.ContentLine> lines = VcardNumberListParser.unfold(
                new StringReader("A:1\r\n 2\r\n\t3\r\n\r\nB:x\n"));
        assertEquals(2, lines.size());
        assertEquals("A:123", lines.get(0).text);
        assertEquals(1, lines.get(0).lineNumber);
        assertEquals("B:x", lines.get(1).text);
        assertEquals(5, lines.get(1).lineNumber);
    }

    @Test
    public void quotedPrintable() {
        assertEquals("Grüße = ok", VcardNumberListParser.decodeQuotedPrintable(
                "Gr=C3=BC=C3=9Fe =3D ok", StandardCharsets.UTF_8));
        assertEquals("a=zz", VcardNumberListParser.decodeQuotedPrintable(
                "a=zz", StandardCharsets.UTF_8));
    }

    @Test
    public void telValue() {
        assertEquals("+49-30-1234567", VcardNumberListParser.telValue("tel:+49-30-1234567;ext=1"));
        assertEquals("0151 12345678", VcardNumberListParser.telValue(" 0151 12345678 "));
    }

}
