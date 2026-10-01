package dummydomain.yetanothercallblocker.data.sources;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Detects the format of a downloaded or picked number list by its content and
 * parses it with the matching parser:
 *
 * <ul>
 *     <li>{@link Format#FRITZBOX_XML} &ndash; Fritz!Box phone book export
 *     ({@link FritzboxPhonebookParser});</li>
 *     <li>{@link Format#VCARD} &ndash; vCard file ({@link VcardNumberListParser});</li>
 *     <li>{@link Format#CSV} &ndash; everything else that is plain text: CSV or a
 *     plain list with one number per line ({@link CsvNumberListParser});</li>
 *     <li>{@link Format#HTML} and {@link Format#OTHER_XML} are recognized only to
 *     report a clear error (e.g. a GitHub page URL instead of a raw file URL).</li>
 * </ul>
 *
 * <p>Plain Java, no Android dependencies.</p>
 */
public final class NumberListFormatDetector {

    public enum Format {
        FRITZBOX_XML,
        VCARD,
        CSV,
        HTML,
        OTHER_XML
    }

    /** Thrown for content that is not a supported number list. */
    public static class UnsupportedFormatException extends IOException {

        private static final long serialVersionUID = 1L;

        private final Format format;

        public UnsupportedFormatException(Format format) {
            super(format == Format.HTML
                    ? "The file is a web page (HTML), not a number list"
                    : "Unsupported XML format");
            this.format = format;
        }

        public Format getFormat() {
            return format;
        }
    }

    /** How much of the content is inspected. */
    static final int SAMPLE_CHARS = 64 * 1024;

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private NumberListFormatDetector() {}

    public static Format detect(byte[] content) {
        int length = Math.min(content.length, SAMPLE_CHARS);
        // a cut multi-byte character at the end of the sample doesn't matter here
        return detect(new String(content, 0, length, StandardCharsets.UTF_8));
    }

    public static Format detect(String content) {
        String sample = content.length() > SAMPLE_CHARS ? content.substring(0, SAMPLE_CHARS) : content;
        int start = 0;
        while (start < sample.length()
                && (Character.isWhitespace(sample.charAt(start)) || sample.charAt(start) == '﻿')) {
            start++;
        }
        String head = sample.substring(start);
        String lower = head.toLowerCase(Locale.ROOT);

        if (lower.startsWith("<")) {
            if (lower.contains("<phonebooks") || lower.contains("<phonebook")) {
                return Format.FRITZBOX_XML;
            }
            if (lower.contains("<html") || lower.contains("<!doctype html")
                    || lower.contains("<body") || lower.contains("<table")) {
                return Format.HTML;
            }
            return Format.OTHER_XML;
        }

        if (lower.startsWith("begin:vcard")) return Format.VCARD;

        return Format.CSV;
    }

    /**
     * Detects the format and parses the content.
     *
     * @throws UnsupportedFormatException for HTML pages and unknown XML documents
     * @throws IOException                for malformed XML
     */
    public static ParseResult parse(byte[] content) throws IOException {
        Format format = detect(content);
        switch (format) {
            case FRITZBOX_XML:
                // bytes, so that the encoding declaration is respected
                return new FritzboxPhonebookParser().parse(content);
            case VCARD:
                return new VcardNumberListParser().parse(decodeText(content));
            case CSV:
                return new CsvNumberListParser().parse(decodeText(content));
            default:
                throw new UnsupportedFormatException(format);
        }
    }

    /** Detects the format and parses already decoded text. */
    public static ParseResult parse(String content) throws IOException {
        Format format = detect(content);
        switch (format) {
            case FRITZBOX_XML:
                return new FritzboxPhonebookParser().parse(content);
            case VCARD:
                return new VcardNumberListParser().parse(content);
            case CSV:
                return new CsvNumberListParser().parse(content);
            default:
                throw new UnsupportedFormatException(format);
        }
    }

    /**
     * Decodes text as UTF-8 (without BOM); content that is not valid UTF-8 is decoded
     * as Windows-1252, the usual encoding of lists exported by older Windows tools.
     */
    static String decodeText(byte[] content) {
        int offset = 0;
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        ByteBuffer buffer = ByteBuffer.wrap(content, offset, content.length - offset);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer)
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(content, offset, content.length - offset, WINDOWS_1252);
        }
    }

}
