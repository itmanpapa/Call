package dummydomain.yetanothercallblocker.data.sources;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Parser for phone books exported from a Fritz!Box (and the community block lists
 * published in this format):
 *
 * <pre>
 * &lt;phonebooks&gt;
 *   &lt;phonebook name="Rufsperren"&gt;
 *     &lt;contact&gt;
 *       &lt;person&gt;&lt;realName&gt;Gewinnspiel&lt;/realName&gt;&lt;/person&gt;
 *       &lt;telephony&gt;
 *         &lt;number type="home" prio="1" id="0"&gt;015112345678&lt;/number&gt;
 *         &lt;number type="work" id="1"&gt;0304070744*&lt;/number&gt;
 *       &lt;/telephony&gt;
 *     &lt;/contact&gt;
 *   &lt;/phonebook&gt;
 * &lt;/phonebooks&gt;
 * </pre>
 *
 * <p>Every {@code <number>} becomes one entry named after the contact's
 * {@code realName}; names without a single letter (block lists often number their
 * contacts "1", "2", ...) are dropped. Numbers are normalized with
 * {@link GermanNumberNormalizer}, so trailing wildcards ({@code 0304070744*}) become
 * prefixes. Invalid numbers are reported in {@link ParseResult#getSkipped()} with
 * the XML line number.</p>
 *
 * <p>Uses SAX ({@code javax.xml.parsers}), which is available both on the JVM and
 * on Android. DTDs and external entities are not resolved. Instances are stateless
 * and thread-safe.</p>
 */
public class FritzboxPhonebookParser {

    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");

    /**
     * Parses an XML document from a byte stream; the encoding is taken from the
     * XML declaration (UTF-8 by default).
     */
    public ParseResult parse(InputStream inputStream) throws IOException {
        return parse(new InputSource(inputStream));
    }

    public ParseResult parse(byte[] content) throws IOException {
        return parse(new ByteArrayInputStream(content));
    }

    /** Parses an already decoded document (the encoding declaration is ignored). */
    public ParseResult parse(String content) throws IOException {
        // a BOM before the XML declaration is a fatal error for SAX
        if (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);
        return parse(new InputSource(new StringReader(content)));
    }

    private ParseResult parse(InputSource source) throws IOException {
        Handler handler = new Handler();
        try {
            XMLReader reader = newXmlReader();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            // never fetch DTDs or external entities
            reader.setEntityResolver((publicId, systemId) ->
                    new InputSource(new ByteArrayInputStream(new byte[0])));
            reader.parse(source);
        } catch (SAXParseException e) {
            throw new IOException("Invalid XML at line " + e.getLineNumber()
                    + ": " + e.getMessage(), e);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("Invalid XML: " + e.getMessage(), e);
        }

        if (!handler.phonebooksSeen) {
            throw new IOException("Not a Fritz!Box phone book: no <phonebooks> or <phonebook> element");
        }
        return new ParseResult(handler.entries, handler.skipped);
    }

    private static XMLReader newXmlReader() throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        // best effort: not every parser (e.g. Android's Expat-based one) supports these
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newSAXParser().getXMLReader();
    }

    private static void setFeature(SAXParserFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception e) {
            // not supported by this parser
        }
    }

    private static String elementName(String localName, String qName) {
        String name = localName != null && !localName.isEmpty() ? localName : qName;
        if (name == null) return "";
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        return name.toLowerCase(Locale.ROOT);
    }

    private static final class PendingNumber {
        final String text;
        final int line;

        PendingNumber(String text, int line) {
            this.text = text;
            this.line = line;
        }
    }

    private static final class Handler extends DefaultHandler {

        final List<ListedNumber> entries = new ArrayList<>();
        final List<ParseResult.SkippedLine> skipped = new ArrayList<>();
        boolean phonebooksSeen;

        private Locator locator;
        private final StringBuilder text = new StringBuilder();
        private boolean collecting;

        private boolean inContact;
        private boolean inPerson;
        private String contactName;
        private int numberLine;
        private final List<PendingNumber> numbers = new ArrayList<>();

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        private int line() {
            return locator != null ? locator.getLineNumber() : 0;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String name = elementName(localName, qName);
            switch (name) {
                case "phonebooks":
                case "phonebook":
                    phonebooksSeen = true;
                    break;
                case "contact":
                    inContact = true;
                    inPerson = false;
                    contactName = null;
                    numbers.clear();
                    break;
                case "person":
                    inPerson = true;
                    break;
                case "realname":
                    if (inContact && inPerson) startText();
                    break;
                case "number":
                    if (inContact) {
                        numberLine = line();
                        startText();
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = elementName(localName, qName);
            switch (name) {
                case "realname":
                    if (collecting) {
                        contactName = text.toString().trim();
                        collecting = false;
                    }
                    break;
                case "person":
                    inPerson = false;
                    break;
                case "number":
                    if (collecting) {
                        String number = text.toString().trim();
                        collecting = false;
                        if (!number.isEmpty()) numbers.add(new PendingNumber(number, numberLine));
                    }
                    break;
                case "contact":
                    finishContact();
                    inContact = false;
                    break;
                default:
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (collecting) text.append(ch, start, length);
        }

        private void startText() {
            text.setLength(0);
            collecting = true;
        }

        private void finishContact() {
            String name = contactName != null && HAS_LETTER.matcher(contactName).find()
                    ? contactName : null;

            for (PendingNumber number : numbers) {
                List<String> errors = new ArrayList<>();
                List<GermanNumberNormalizer.NumberSpec> specs =
                        GermanNumberNormalizer.parseSpecs(number.text, errors);
                if (specs.isEmpty() || !errors.isEmpty()) {
                    skipped.add(new ParseResult.SkippedLine(number.line, number.text,
                            errors.isEmpty() ? "no number" : String.join("; ", errors)));
                }
                for (GermanNumberNormalizer.NumberSpec spec : specs) {
                    entries.add(ListedNumber.builder()
                            .spec(spec)
                            .name(name)
                            .measureType(MeasureType.NONE)
                            .rawText(number.text)
                            .build());
                }
            }
            numbers.clear();
            contactName = null;
        }

        @Override
        public void error(SAXParseException e) {
            // recoverable (validation) errors are irrelevant for a non-validating parse
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    }

}
