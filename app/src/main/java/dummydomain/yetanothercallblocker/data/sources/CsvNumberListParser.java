package dummydomain.yetanothercallblocker.data.sources;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Parser for user-imported number lists in CSV format.
 *
 * <ul>
 *     <li>the delimiter ({@code ,} {@code ;} or tab) is detected automatically;</li>
 *     <li>an optional header row maps the columns by name (number/Rufnummer/pattern,
 *     name, category/Kategorie, comment/Kommentar, ...); without a header the number
 *     column is detected from the data and the remaining columns are read as
 *     name, category and comment in this order;</li>
 *     <li>UTF-8 BOM, quoted fields, empty lines and {@code #} comment lines are handled;</li>
 *     <li>numbers are normalized with {@link GermanNumberNormalizer}, wildcards
 *     ({@code 0900123*}) and ranges are supported;</li>
 *     <li>invalid lines are skipped and reported in {@link ParseResult#getSkipped()}.</li>
 * </ul>
 *
 * <p>Each physical line is parsed as one record, so quoted fields may not span lines.
 * Plain Java, no Android dependencies. Instances are stateless and thread-safe.</p>
 */
public class CsvNumberListParser {

    static final char[] CANDIDATE_DELIMITERS = {',', ';', '\t'};

    /** How many lines are inspected to detect the delimiter and the number column. */
    private static final int SAMPLE_LINES = 20;

    private static final String[] NUMBER_HEADERS = {"number", "nummer", "phone", "telefon",
            "tel", "pattern", "muster", "msisdn", "numero", "número", "caller", "номер"};
    private static final String[] NAME_HEADERS = {"name", "bezeichnung", "firma", "company",
            "title", "titel", "absender", "имя", "название"};
    private static final String[] CATEGORY_HEADERS = {"category", "kategorie", "type", "typ",
            "art", "tag", "категория", "тип"};
    private static final String[] COMMENT_HEADERS = {"comment", "kommentar", "bemerkung",
            "notiz", "note", "description", "beschreibung", "info", "комментарий"};

    /** Column indexes; -1 means "not present". */
    static final class Columns {
        int number = -1;
        int name = -1;
        int category = -1;
        int comment = -1;
    }

    /**
     * Parses a UTF-8 (with or without BOM) CSV stream.
     */
    public ParseResult parse(InputStream inputStream) throws IOException {
        return parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    public ParseResult parse(Reader reader) throws IOException {
        List<String> lines = readLines(reader);
        return parseLines(lines);
    }

    public ParseResult parse(String content) {
        try {
            return parse(new StringReader(content));
        } catch (IOException e) {
            throw new IllegalStateException(e); // StringReader does not throw
        }
    }

    private static List<String> readLines(Reader reader) throws IOException {
        BufferedReader br = reader instanceof BufferedReader
                ? (BufferedReader) reader : new BufferedReader(reader);

        List<String> lines = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
            if (lines.isEmpty() && !line.isEmpty() && line.charAt(0) == '﻿') {
                line = line.substring(1);
            }
            lines.add(line);
        }
        return lines;
    }

    ParseResult parseLines(List<String> lines) {
        List<ListedNumber> entries = new ArrayList<>();
        List<ParseResult.SkippedLine> skipped = new ArrayList<>();

        char delimiter = detectDelimiter(lines);
        CSVFormat format = CSVFormat.DEFAULT
                .withDelimiter(delimiter)
                .withQuote('"')
                .withTrim()
                .withIgnoreSurroundingSpaces()
                .withIgnoreEmptyLines();

        // Split all lines into cells first: needed for header and column detection
        List<Integer> lineNumbers = new ArrayList<>();
        List<List<String>> records = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isIgnorable(line)) continue;

            try {
                List<String> cells = parseLine(line, format);
                if (cells == null || isBlank(cells)) continue;
                records.add(cells);
                lineNumbers.add(i + 1);
            } catch (IOException | RuntimeException e) {
                skipped.add(new ParseResult.SkippedLine(i + 1, line,
                        "malformed CSV: " + e.getMessage()));
            }
        }

        if (records.isEmpty()) return new ParseResult(entries, skipped);

        int first = 0;
        Columns columns = columnsFromHeader(records.get(0));
        if (columns != null) {
            first = 1;
        } else {
            if (!hasNumberCell(records.get(0))) {
                // A header without recognizable names: skip it, detect the columns from data
                first = 1;
            }
            columns = detectColumns(records.subList(first, records.size()));
        }

        for (int i = first; i < records.size(); i++) {
            List<String> cells = records.get(i);
            int lineNumber = lineNumbers.get(i);
            String raw = lines.get(lineNumber - 1);

            String numberCell = cell(cells, columns.number);
            if (numberCell == null || numberCell.isEmpty()) {
                skipped.add(new ParseResult.SkippedLine(lineNumber, raw, "no number"));
                continue;
            }

            List<String> errors = new ArrayList<>();
            List<GermanNumberNormalizer.NumberSpec> specs =
                    GermanNumberNormalizer.parseSpecs(numberCell, errors);
            if (specs.isEmpty() || !errors.isEmpty()) {
                skipped.add(new ParseResult.SkippedLine(lineNumber, raw,
                        errors.isEmpty() ? "no number" : String.join("; ", errors)));
                if (specs.isEmpty()) continue;
            }

            for (GermanNumberNormalizer.NumberSpec spec : specs) {
                entries.add(ListedNumber.builder()
                        .spec(spec)
                        .name(cell(cells, columns.name))
                        .category(cell(cells, columns.category))
                        .comment(cell(cells, columns.comment))
                        .measureType(MeasureType.NONE)
                        .rawText(raw)
                        .build());
            }
        }

        return new ParseResult(entries, skipped);
    }

    private static boolean isIgnorable(String line) {
        String t = line.trim();
        return t.isEmpty() || t.startsWith("#") || t.startsWith("//");
    }

    private static List<String> parseLine(String line, CSVFormat format) throws IOException {
        try (CSVParser parser = CSVParser.parse(line, format)) {
            List<CSVRecord> list = parser.getRecords();
            if (list.isEmpty()) return null;
            if (list.size() > 1) throw new IOException("unexpected line break");

            List<String> cells = new ArrayList<>();
            for (String value : list.get(0)) cells.add(value != null ? value.trim() : "");
            return cells;
        }
    }

    /**
     * Picks the delimiter that occurs (outside quotes) in most sample lines,
     * preferring the one with a consistent count per line. Defaults to comma.
     */
    static char detectDelimiter(List<String> lines) {
        char best = ',';
        int bestScore = 0;

        for (char d : CANDIDATE_DELIMITERS) {
            List<Integer> counts = new ArrayList<>();
            for (String line : lines) {
                if (isIgnorable(line)) continue;
                counts.add(countOutsideQuotes(line, d));
                if (counts.size() >= SAMPLE_LINES) break;
            }

            int linesWith = 0;
            for (int c : counts) if (c > 0) linesWith++;
            if (linesWith == 0) continue;

            // Lines sharing the most common non-zero count
            int consistent = 0;
            for (int c : counts) {
                if (c == 0) continue;
                int same = Collections.frequency(counts, c);
                if (same > consistent) consistent = same;
            }

            int score = linesWith * 2 + consistent;
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    private static int countOutsideQuotes(String line, char delimiter) {
        int count = 0;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == delimiter && !quoted) count++;
        }
        return count;
    }

    /** Returns the column mapping if the record is a header with a number column. */
    static Columns columnsFromHeader(List<String> cells) {
        if (hasNumberCell(cells)) return null;

        Columns columns = new Columns();
        for (int i = 0; i < cells.size(); i++) {
            String c = cells.get(i).toLowerCase(Locale.ROOT).trim();
            if (c.isEmpty()) continue;

            if (columns.number < 0 && containsAny(c, NUMBER_HEADERS)) {
                columns.number = i;
            } else if (columns.name < 0 && containsAny(c, NAME_HEADERS)) {
                columns.name = i;
            } else if (columns.category < 0 && containsAny(c, CATEGORY_HEADERS)) {
                columns.category = i;
            } else if (columns.comment < 0 && containsAny(c, COMMENT_HEADERS)) {
                columns.comment = i;
            }
        }
        return columns.number >= 0 ? columns : null;
    }

    /**
     * Detects the columns of a list without (recognizable) header: the number column is
     * the one where most values are valid numbers, the other columns follow in the
     * order name, category, comment.
     */
    static Columns detectColumns(List<List<String>> records) {
        int maxColumns = 0;
        for (int i = 0; i < records.size() && i < SAMPLE_LINES; i++) {
            maxColumns = Math.max(maxColumns, records.get(i).size());
        }

        int[] valid = new int[maxColumns];
        for (int i = 0; i < records.size() && i < SAMPLE_LINES; i++) {
            List<String> cells = records.get(i);
            for (int col = 0; col < cells.size(); col++) {
                if (isNumberCell(cells.get(col))) valid[col]++;
            }
        }

        Columns columns = new Columns();
        columns.number = 0;
        for (int col = 1; col < maxColumns; col++) {
            if (valid[col] > valid[columns.number]) columns.number = col;
        }

        int next = 0;
        int[] others = new int[3];
        for (int col = 0; col < maxColumns && next < others.length; col++) {
            if (col != columns.number) others[next++] = col;
        }
        columns.name = next > 0 ? others[0] : -1;
        columns.category = next > 1 ? others[1] : -1;
        columns.comment = next > 2 ? others[2] : -1;
        return columns;
    }

    private static boolean hasNumberCell(List<String> cells) {
        for (String c : cells) {
            if (isNumberCell(c)) return true;
        }
        return false;
    }

    private static boolean isNumberCell(String cell) {
        if (cell == null || cell.isEmpty()) return false;
        List<String> errors = new ArrayList<>();
        return !GermanNumberNormalizer.parseSpecs(cell, errors).isEmpty() && errors.isEmpty();
    }

    /**
     * Keywords of 4+ characters match anywhere ("Rufnummer" contains "nummer"),
     * shorter ones only as a whole word ("Tel." but not "Titel").
     */
    private static boolean containsAny(String s, String[] keywords) {
        String[] words = s.split("[^\\p{L}]+");
        for (String k : keywords) {
            if (k.length() >= 4) {
                if (s.contains(k)) return true;
            } else {
                for (String w : words) {
                    if (w.equals(k)) return true;
                }
            }
        }
        return false;
    }

    private static String cell(List<String> cells, int index) {
        if (index < 0 || index >= cells.size()) return null;
        String value = cells.get(index);
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean isBlank(List<String> cells) {
        for (String c : cells) {
            if (c != null && !c.isEmpty()) return false;
        }
        return true;
    }

}
