package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FritzboxPhonebookParserTest {

    private final FritzboxPhonebookParser parser = new FritzboxPhonebookParser();

    @Test
    public void realExcerptOfSpamCalllist() throws IOException {
        ParseResult result = parser.parse(openResource("spamcalllist_fritzbox_excerpt.xml"));

        List<ListedNumber> entries = result.getEntries();
        assertEquals(7, entries.size());
        assertTrue(result.getSkipped().isEmpty());

        assertEquals("+12603404548", entries.get(0).getNumber());
        assertEquals("Ausland - +1...", entries.get(0).getName());
        assertEquals("+12603404548", entries.get(0).getRawText());
        assertEquals("+16465535819", entries.get(1).getNumber());

        assertEquals("+4930138839942", entries.get(2).getNumber());
        assertEquals("Inland - 03...", entries.get(2).getName());
        assertEquals("+493020181800", entries.get(3).getNumber());

        // "0304070744*" is a prefix
        assertTrue(entries.get(4).isPrefix());
        assertEquals("+49304070744", entries.get(4).getPrefix());
        assertTrue(entries.get(4).matches("+493040707441"));
        assertEquals("0304070744*", entries.get(4).getRawText());
        assertEquals("+49304669001", entries.get(5).getPrefix());

        assertEquals("+4930499189782", entries.get(6).getNumber());
        for (ListedNumber e : entries) {
            assertEquals(MeasureType.NONE, e.getMeasureType());
            assertNull(e.getCategory());
        }
    }

    @Test
    public void compactExportWithNumericNamesAndInvalidNumbers() throws IOException {
        ParseResult result = parser.parse(openResource("fritzbox_phonebook_synthetic.xml"));

        List<ListedNumber> entries = result.getEntries();
        assertEquals(5, entries.size());

        // contacts named "1", "2", ... carry no information
        assertEquals("+4915112345678", entries.get(0).getNumber());
        assertNull(entries.get(0).getName());
        assertEquals("+49301234567", entries.get(1).getNumber());
        assertEquals("+375291234567", entries.get(2).getNumber());

        // entity in the name, wildcard number
        assertEquals("+49900123", entries.get(3).getPrefix());
        assertEquals("Gewinnspiel & Co. KG", entries.get(3).getName());

        assertEquals("+4922198765432", entries.get(4).getNumber());
        assertEquals("Energie-Abzocke", entries.get(4).getName());

        // "0011129" is too short; the empty number is ignored silently
        assertEquals(1, result.getSkipped().size());
        ParseResult.SkippedLine skipped = result.getSkipped().get(0);
        assertEquals("0011129", skipped.getRawText());
        // the start tag spans lines 7-8; parsers report either its start or its end
        assertTrue(String.valueOf(skipped.getLineNumber()),
                skipped.getLineNumber() == 7 || skipped.getLineNumber() == 8);
    }

    @Test
    public void stringInputWithBom() throws IOException {
        ParseResult result = parser.parse("﻿<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<phonebooks><phonebook><contact><person><realName>Müller</realName></person>"
                + "<telephony><number>030 1234567</number></telephony></contact>"
                + "</phonebook></phonebooks>");
        assertEquals(1, result.getEntries().size());
        assertEquals("Müller", result.getEntries().get(0).getName());
        assertEquals("+49301234567", result.getEntries().get(0).getNumber());
    }

    @Test
    public void encodingDeclarationIsRespectedForBytes() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"
                + "<phonebooks><phonebook><contact><person><realName>Gebühren-Falle</realName>"
                + "</person><telephony><number>030 1234567</number></telephony></contact>"
                + "</phonebook></phonebooks>";
        ParseResult result = parser.parse(xml.getBytes(Charset.forName("ISO-8859-1")));
        assertEquals("Gebühren-Falle", result.getEntries().get(0).getName());
    }

    @Test
    public void numbersOutsideContactsAndOtherNamesAreIgnored() throws IOException {
        ParseResult result = parser.parse("<phonebooks><phonebook name=\"x\">"
                + "<number>015112345678</number>"
                + "<contact><realName>not in person</realName>"
                + "<telephony><number>015187654321</number></telephony></contact>"
                + "</phonebook></phonebooks>");
        assertEquals(1, result.getEntries().size());
        assertEquals("+4915187654321", result.getEntries().get(0).getNumber());
        assertNull(result.getEntries().get(0).getName());
    }

    @Test
    public void malformedXmlFails() {
        try {
            parser.parse("<phonebooks><phonebook><contact></phonebook>");
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Invalid XML"));
        }
    }

    @Test
    public void otherXmlFails() {
        try {
            parser.parse("<?xml version=\"1.0\"?><rss><channel/></rss>");
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Not a Fritz!Box phone book"));
        }
    }

    @Test
    public void externalEntitiesAreNotResolved() throws IOException {
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE phonebooks [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<phonebooks><phonebook><contact><person><realName>&xxe;</realName></person>"
                + "<telephony><number>030 1234567</number></telephony></contact>"
                + "</phonebook></phonebooks>";
        try {
            ParseResult result = parser.parse(xml);
            // a parser that allows DTDs must at least not expand the external entity
            String name = result.getEntries().get(0).getName();
            assertFalse(name != null && name.contains("root:"));
        } catch (IOException e) {
            // a parser that rejects DOCTYPE declarations is fine as well
            assertTrue(e.getMessage().startsWith("Invalid XML"));
        }
    }

    static InputStream openResource(String name) {
        InputStream in = FritzboxPhonebookParserTest.class.getClassLoader()
                .getResourceAsStream("sources/" + name);
        if (in == null) throw new AssertionError("Missing resource " + name);
        return in;
    }

}
