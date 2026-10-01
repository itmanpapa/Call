package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import dummydomain.yetanothercallblocker.data.sources.NumberListFormatDetector.Format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class NumberListFormatDetectorTest {

    @Test
    public void detect() {
        assertEquals(Format.FRITZBOX_XML, NumberListFormatDetector.detect(
                "﻿  <?xml version=\"1.0\"?>\n<phonebooks><phonebook/></phonebooks>"));
        assertEquals(Format.FRITZBOX_XML, NumberListFormatDetector.detect("<phonebook name=\"x\">"));
        assertEquals(Format.VCARD, NumberListFormatDetector.detect("\r\nBEGIN:VCARD\r\nVERSION:3.0"));
        assertEquals(Format.VCARD, NumberListFormatDetector.detect("begin:vcard"));
        assertEquals(Format.CSV, NumberListFormatDetector.detect("name;number\nx;015112345678"));
        assertEquals(Format.CSV, NumberListFormatDetector.detect("015112345678\n+4930123456\n"));
        assertEquals(Format.CSV, NumberListFormatDetector.detect(""));
        assertEquals(Format.HTML, NumberListFormatDetector.detect(
                "<!DOCTYPE html><html><body>GitHub</body></html>"));
        assertEquals(Format.OTHER_XML, NumberListFormatDetector.detect("<?xml version=\"1.0\"?><rss/>"));
    }

    @Test
    public void parseRealFiles() throws IOException {
        assertEquals(7, NumberListFormatDetector.parse(
                read("spamcalllist_fritzbox_excerpt.xml")).getEntries().size());
        assertEquals(6, NumberListFormatDetector.parse(
                read("spamcalllist_nextcloud_excerpt.vcf")).getEntries().size());
    }

    @Test
    public void parseNameNumberCsv() throws IOException {
        // the "name,number" layout of andreaspreuss/fritzbox_blacklists
        ParseResult result = NumberListFormatDetector.parse(read("name_number_synthetic.csv"));
        assertEquals(3, result.getEntries().size());
        assertEquals("+4915201036631", result.getEntries().get(0).getNumber());
        assertEquals("Abo-Falle", result.getEntries().get(0).getName());
        assertEquals("+4952469391102", result.getEntries().get(2).getNumber());
        assertEquals("1&1 Werbung", result.getEntries().get(2).getName());
        // "03222" (meant as a range) and an overlong number are not guessed
        assertEquals(2, result.getSkipped().size());
    }

    @Test
    public void parseTextWithBomAndWindows1252() throws IOException {
        byte[] utf8Bom = "﻿015112345678;Müller\n".getBytes(StandardCharsets.UTF_8);
        ParseResult result = NumberListFormatDetector.parse(utf8Bom);
        assertEquals("Müller", result.getEntries().get(0).getName());

        byte[] cp1252 = "015112345678;Müller\n".getBytes(Charset.forName("windows-1252"));
        result = NumberListFormatDetector.parse(cp1252);
        assertEquals("Müller", result.getEntries().get(0).getName());
    }

    @Test
    public void parseString() throws IOException {
        assertEquals(1, NumberListFormatDetector.parse(
                "BEGIN:VCARD\nTEL:015112345678\nEND:VCARD").getEntries().size());
    }

    @Test
    public void htmlIsRejected() throws IOException {
        try {
            NumberListFormatDetector.parse(
                    "<!DOCTYPE html><html><body>…</body></html>".getBytes(StandardCharsets.UTF_8));
            fail();
        } catch (NumberListFormatDetector.UnsupportedFormatException e) {
            assertEquals(Format.HTML, e.getFormat());
        }
    }

    private static byte[] read(String name) throws IOException {
        try (InputStream in = FritzboxPhonebookParserTest.openResource(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

}
