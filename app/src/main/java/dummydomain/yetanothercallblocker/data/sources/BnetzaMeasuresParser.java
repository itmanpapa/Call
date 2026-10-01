package dummydomain.yetanothercallblocker.data.sources;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the Bundesnetzagentur "Maßnahmenliste" – the list of official measures
 * against misused numbers (https://www.bundesnetzagentur.de/massnahmenliste).
 *
 * <p>The list is published as a table with the columns
 * <b>Bescheid vom | Rufnummer | Kategorie | Maßnahme</b>, e.g.</p>
 * <pre>
 * 24.04.2025 | 015112345678, 016012345678 | Spam-SMS      | Abschaltung der Rufnummern
 * 09.09.2025 | 0421123456                 | Ping-Anrufe   | Rechnungslegungs- und Inkassierungsverbot
 * </pre>
 * <p>The last six months are shown as an HTML table on the web page; the full lists
 * per year are PDF files (Maßnahmenliste2025.pdf etc.). One "Rufnummer" cell may hold
 * several numbers separated by commas or line breaks; numbers are mostly written
 * without spaces with a leading 0, foreign numbers with +/00.</p>
 *
 * <p>Supported inputs:</p>
 * <ul>
 *     <li>{@link #parseHtml(String)} – the HTML page (or a saved copy of it);</li>
 *     <li>{@link #parseText(Reader)} – text extracted from the PDF (e.g. {@code pdftotext -layout}),
 *     one row per line, wrapped cells continue on following lines;</li>
 *     <li>{@link #parseRows(List)} – already split table rows (e.g. from a CSV conversion).</li>
 * </ul>
 *
 * <p>Plain Java, no Android dependencies. Instances are stateless and thread-safe.</p>
 */
public class BnetzaMeasuresParser {

    public static final String SOURCE_URL = "https://www.bundesnetzagentur.de/massnahmenliste";

    static final int COL_DATE = 0;
    static final int COL_NUMBERS = 1;
    static final int COL_CATEGORY = 2;
    static final int COL_MEASURE = 3;

    private static final Pattern DATE = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})");
    private static final Pattern LEADING_DATE = Pattern.compile(
            "^\\s*(\\d{1,2}\\.\\d{1,2}\\.\\d{2,4})\\s+(.*)$");

    /** Words that start the "Maßnahme" column in a text row. */
    private static final Pattern MEASURE_START = Pattern.compile(
            "(?i)\\b(Abschaltung|Rechnungslegungs|Inkassierungs|Auszahlungs|Portierungs"
                    + "|Gesch(?:ä|ae)ftsmodell|Untersagung|Verbot)");

    /** Characters that can be part of the "Rufnummer" column in a text row. */
    private static final Pattern NUMBER_TOKEN = Pattern.compile(
            "^[+0-9][0-9 /\\-()xX*.,;\\u2026]*$|^bis$|^und$|^[0-9xX*\\u2026.]+[,;]?$");

    private static final Pattern NUMBERS_ONLY_LINE = Pattern.compile(
            "^[\\s+0-9/\\-()xX*.,;\\u2026]+$");

    private static final Pattern PAGE_FOOTER = Pattern.compile(
            "(?i)^\\s*(Seite\\s+\\d+(\\s+von\\s+\\d+)?|\\d+\\s*/\\s*\\d+|-\\s*\\d+\\s*-)\\s*$");

    private static final Pattern TABLE_ROW = Pattern.compile(
            "(?is)<tr\\b[^>]*>(.*?)</tr\\s*>");
    private static final Pattern TABLE_CELL = Pattern.compile(
            "(?is)<t([dh])\\b[^>]*>(.*?)</t[dh]\\s*>");
    private static final Pattern LINE_BREAK_TAG = Pattern.compile(
            "(?i)<br\\s*/?>|</p\\s*>|</li\\s*>|</div\\s*>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /**
     * Parses the HTML page. All {@code <tr>} rows are considered; a header row
     * (with "Rufnummer") defines the column order, rows without a parseable date
     * and number are reported as skipped.
     */
    public ParseResult parseHtml(String html) {
        List<List<String>> rows = new ArrayList<>();
        Matcher rowMatcher = TABLE_ROW.matcher(html);
        while (rowMatcher.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = TABLE_CELL.matcher(rowMatcher.group(1));
            while (cellMatcher.find()) {
                cells.add(htmlToText(cellMatcher.group(2)));
            }
            if (!cells.isEmpty()) rows.add(cells);
        }
        return parseRows(rows);
    }

    /**
     * Parses table rows. If a header row is found (a cell containing "Rufnummer"),
     * the columns are mapped by their header names; otherwise the default order
     * Bescheid vom, Rufnummer, Kategorie, Maßnahme is assumed.
     */
    public ParseResult parseRows(List<List<String>> rows) {
        int[] columns = {COL_DATE, COL_NUMBERS, COL_CATEGORY, COL_MEASURE};

        List<ListedNumber> entries = new ArrayList<>();
        List<ParseResult.SkippedLine> skipped = new ArrayList<>();

        int rowNumber = 0;
        for (List<String> row : rows) {
            rowNumber++;

            int[] header = detectHeader(row);
            if (header != null) {
                columns = header;
                continue;
            }

            if (isBlank(row)) continue;

            String rawText = String.join(" | ", row);
            addRow(cell(row, columns[0]), cell(row, columns[1]),
                    cell(row, columns[2]), cell(row, columns[3]),
                    rowNumber, rawText, entries, skipped);
        }

        return new ParseResult(entries, skipped);
    }

    /**
     * Parses text extracted from the PDF version of the list (e.g. with
     * {@code pdftotext -layout}). Every row starts with the date; lines without a date
     * continue the previous row (wrapped number lists or wrapped category/measure
     * texts). Headers, page footers and lines starting with "#" are ignored.
     *
     * <p>If a header line ("Bescheid vom  Rufnummer  Kategorie  Maßnahme") is present,
     * its word positions are used as column boundaries, which keeps wrapped cells in
     * their columns. Without a header the columns are split heuristically.</p>
     */
    public ParseResult parseText(Reader reader) throws IOException {
        List<ListedNumber> entries = new ArrayList<>();
        List<ParseResult.SkippedLine> skipped = new ArrayList<>();

        BufferedReader br = reader instanceof BufferedReader
                ? (BufferedReader) reader : new BufferedReader(reader);

        int[] offsets = null;
        TextRow current = null;
        String line;
        int lineNumber = 0;
        while ((line = br.readLine()) != null) {
            lineNumber++;
            if (lineNumber == 1) line = stripBom(line);
            line = line.replace('\t', ' ').replace(' ', ' ').replace("\f", "");

            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (PAGE_FOOTER.matcher(trimmed).matches()) continue;
            if (isTextHeader(trimmed)) {
                offsets = headerOffsets(line);
                continue;
            }

            boolean rowStart = LEADING_DATE.matcher(trimmed).matches();
            if (!rowStart && current == null) continue; // title, introduction etc.

            if (rowStart) {
                flush(current, entries, skipped);
                current = new TextRow(lineNumber, trimmed);
            } else {
                current.raw.append('\n').append(trimmed);
            }

            String[] fields = offsets != null ? splitByOffsets(line, offsets) : null;
            if (fields != null && (rowStart || fields[0].isEmpty())) {
                current.addFields(fields);
            } else if (rowStart) {
                Matcher m = LEADING_DATE.matcher(trimmed);
                if (m.matches()) {
                    current.date.append(m.group(1));
                    current.appendHeuristic(m.group(2));
                }
            } else {
                current.appendContinuationHeuristic(trimmed);
            }
        }
        flush(current, entries, skipped);

        return new ParseResult(entries, skipped);
    }

    /**
     * Start positions of the columns Rufnummer, Kategorie and Maßnahme in a header line,
     * or {@code null} if they cannot be determined.
     */
    static int[] headerOffsets(String header) {
        String l = header.toLowerCase(Locale.GERMAN);
        int numbers = l.indexOf("rufnummer");
        int category = l.indexOf("kategorie");
        int measure = l.indexOf("maßnahme");
        if (measure < 0) measure = l.indexOf("massnahme");

        if (numbers <= 0 || category <= numbers || measure <= category) return null;
        return new int[]{numbers, category, measure};
    }

    /**
     * Splits a layout line into date, numbers, category and measure using the header
     * offsets. A word crossing a boundary belongs to the column it starts in.
     */
    static String[] splitByOffsets(String line, int[] offsets) {
        int len = line.length();
        int[] bounds = new int[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            int b = Math.min(offsets[i], len);
            while (b > 0 && b < len && line.charAt(b - 1) != ' ' && line.charAt(b) != ' ') {
                b--;
            }
            bounds[i] = Math.max(b, i > 0 ? bounds[i - 1] : 0);
        }

        String[] fields = new String[offsets.length + 1];
        int start = 0;
        for (int i = 0; i <= offsets.length; i++) {
            int end = i < offsets.length ? bounds[i] : len;
            fields[i] = line.substring(start, Math.max(start, end)).trim();
            start = Math.max(start, end);
        }
        return fields;
    }

    private void flush(TextRow row, List<ListedNumber> entries,
                       List<ParseResult.SkippedLine> skipped) {
        if (row == null) return;

        addRow(row.date.toString(), row.numbers.toString(), row.category(), row.measure(),
                row.lineNumber, row.raw.toString(), entries, skipped);
    }

    /**
     * Converts one logical row to entries. A row with an invalid date or without any
     * valid number is skipped; if only some numbers of a row are invalid, the valid
     * ones are kept and the row is additionally reported with the invalid parts.
     */
    private void addRow(String dateText, String numbersText, String category,
                        String measureText, int lineNumber, String rawText,
                        List<ListedNumber> out, List<ParseResult.SkippedLine> skipped) {
        LocalDate date = parseDate(dateText);
        if (date == null) {
            skipped.add(new ParseResult.SkippedLine(lineNumber, rawText,
                    "invalid date: \"" + nullToEmpty(dateText) + "\""));
            return;
        }

        List<String> errors = new ArrayList<>();
        List<GermanNumberNormalizer.NumberSpec> specs =
                GermanNumberNormalizer.parseSpecs(numbersText, errors);
        if (specs.isEmpty()) {
            skipped.add(new ParseResult.SkippedLine(lineNumber, rawText,
                    errors.isEmpty() ? "no number" : String.join("; ", errors)));
            return;
        }
        if (!errors.isEmpty()) {
            skipped.add(new ParseResult.SkippedLine(lineNumber, rawText,
                    "partially parsed, " + String.join("; ", errors)));
        }

        String measure = normalizeSpaces(measureText);
        MeasureType type = MeasureType.fromGermanText(measure);
        if (type == MeasureType.NONE) type = MeasureType.UNKNOWN;

        for (GermanNumberNormalizer.NumberSpec spec : specs) {
            out.add(ListedNumber.builder()
                    .spec(spec)
                    .date(date)
                    .category(normalizeSpaces(category))
                    .measureType(type)
                    .measureText(measure)
                    .rawText(rawText)
                    .build());
        }
    }

    /** Parses "24.04.2025" (also "1.2.25"); returns {@code null} if invalid. */
    static LocalDate parseDate(String text) {
        if (text == null) return null;

        Matcher m = DATE.matcher(text);
        if (!m.find()) return null;

        try {
            int day = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int year = Integer.parseInt(m.group(3));
            if (m.group(3).length() == 2) {
                year += 2000;
            } else if (m.group(3).length() != 4) {
                return null;
            }
            return LocalDate.of(year, month, day);
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the column mapping if the row is a header row, otherwise {@code null}.
     */
    static int[] detectHeader(List<String> row) {
        int date = -1, numbers = -1, category = -1, measure = -1;
        for (int i = 0; i < row.size(); i++) {
            String c = row.get(i).toLowerCase(Locale.GERMAN);
            if (numbers < 0 && c.contains("rufnummer")) {
                numbers = i;
            } else if (date < 0 && (c.contains("bescheid") || c.contains("datum"))) {
                date = i;
            } else if (category < 0 && c.contains("kategorie")) {
                category = i;
            } else if (measure < 0 && (c.contains("maßnahme") || c.contains("massnahme"))) {
                measure = i;
            }
        }

        if (numbers < 0 || date < 0) return null;
        return new int[]{date, numbers, category, measure};
    }

    private static boolean isTextHeader(String line) {
        String l = line.toLowerCase(Locale.GERMAN);
        return l.contains("rufnummer") && (l.contains("bescheid") || l.contains("kategorie"))
                && !LEADING_DATE.matcher(line).matches();
    }

    private static String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : null;
    }

    private static boolean isBlank(List<String> row) {
        for (String c : row) {
            if (c != null && !c.trim().isEmpty()) return false;
        }
        return true;
    }

    /** Converts cell HTML to text; line breaks become newlines (they separate numbers). */
    static String htmlToText(String html) {
        String s = LINE_BREAK_TAG.matcher(html).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);

        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = normalizeSpaces(line);
            if (t == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;

        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                sb.appendCodePoint(cp);
            } catch (IllegalArgumentException e) {
                sb.append(m.group());
            }
            last = m.end();
        }
        sb.append(s.substring(last));

        return sb.toString()
                .replace("&nbsp;", " ")
                .replace("&auml;", "ä").replace("&ouml;", "ö").replace("&uuml;", "ü")
                .replace("&Auml;", "Ä").replace("&Ouml;", "Ö").replace("&Uuml;", "Ü")
                .replace("&szlig;", "ß")
                .replace("&ndash;", "–").replace("&mdash;", "—")
                .replace("&hellip;", "…")
                .replace("&shy;", "")
                .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String normalizeSpaces(String s) {
        if (s == null) return null;
        s = s.replace(' ', ' ').replace("­", "").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    /**
     * A row of the PDF text being assembled from one or more lines.
     */
    private static final class TextRow {

        final int lineNumber;
        final StringBuilder raw;
        final StringBuilder date = new StringBuilder();
        final StringBuilder numbers = new StringBuilder();
        final StringBuilder category = new StringBuilder();
        final StringBuilder measure = new StringBuilder();

        /** Category and measure text for heuristic splitting (no header offsets). */
        final StringBuilder rest = new StringBuilder();

        TextRow(int lineNumber, String rawLine) {
            this.lineNumber = lineNumber;
            this.raw = new StringBuilder(rawLine);
        }

        /** Adds the fields of a line split by header offsets. */
        void addFields(String[] fields) {
            appendText(date, fields[0]);
            appendNumbers(fields[1]);
            appendText(category, fields[2]);
            appendText(measure, fields[3]);
        }

        /** Splits the remainder of the first line into the numbers part and the text part. */
        void appendHeuristic(String text) {
            String[] tokens = text.trim().split("\\s+");
            int i = 0;
            // While the text part is still empty, leading number-like tokens belong to the numbers
            if (rest.length() == 0) {
                StringBuilder sb = new StringBuilder();
                while (i < tokens.length && NUMBER_TOKEN.matcher(tokens[i]).matches()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(tokens[i]);
                    i++;
                }
                appendNumbers(sb.toString());
            }
            if (i < tokens.length) {
                appendText(rest, String.join(" ", Arrays.asList(tokens).subList(i, tokens.length)));
            }
        }

        void appendContinuationHeuristic(String line) {
            if (NUMBERS_ONLY_LINE.matcher(line).matches()) {
                appendNumbers(line);
            } else if (rest.length() == 0) {
                appendHeuristic(line);
            } else {
                // Layout output may put number continuations in front of wrapped text
                String[] tokens = line.split("\\s+");
                int i = 0;
                while (i < tokens.length && looksLikeFullNumber(tokens[i])) {
                    appendNumbers(tokens[i]);
                    i++;
                }
                if (i < tokens.length) {
                    appendText(rest, String.join(" ", Arrays.asList(tokens).subList(i, tokens.length)));
                }
            }
        }

        /**
         * Appends a fragment of the numbers cell. A fragment that is a complete number
         * or wildcard/range entry (or follows a trailing comma) starts a new list item,
         * anything else continues the current number (a number wrapped in the middle).
         */
        private void appendNumbers(String fragment) {
            fragment = fragment.trim();
            if (fragment.isEmpty()) return;

            if (numbers.length() == 0) {
                numbers.append(fragment);
                return;
            }

            String current = numbers.toString().trim();
            boolean newItem = current.endsWith(",") || current.endsWith(";")
                    || GermanNumberNormalizer.parseSingleSpec(fragment.replaceAll("[,;]$", "")) != null;
            numbers.append(newItem ? "\n" : " ").append(fragment);
        }

        private static void appendText(StringBuilder sb, String text) {
            text = text.trim();
            if (text.isEmpty()) return;
            if (sb.length() > 0) sb.append(' ');
            sb.append(text);
        }

        private static boolean looksLikeFullNumber(String token) {
            return GermanNumberNormalizer.normalize(token.replaceAll("[,;]$", "")) != null;
        }

        private boolean hasColumns() {
            return category.length() > 0 || measure.length() > 0;
        }

        String category() {
            if (hasColumns()) return category.toString();

            String r = rest.toString().trim();
            Matcher m = MEASURE_START.matcher(r);
            return m.find() ? r.substring(0, m.start()).trim() : r;
        }

        String measure() {
            if (hasColumns()) return measure.toString();

            String r = rest.toString().trim();
            Matcher m = MEASURE_START.matcher(r);
            return m.find() ? r.substring(m.start()).trim() : null;
        }
    }

}
