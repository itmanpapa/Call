package dummydomain.yetanothercallblocker.data.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of parsing an offline number list: the parsed entries plus the lines
 * that were skipped, with a reason for each.
 */
public final class ParseResult {

    /** A line (or table row) that could not be parsed. */
    public static final class SkippedLine {

        private final int lineNumber;
        private final String rawText;
        private final String reason;

        public SkippedLine(int lineNumber, String rawText, String reason) {
            this.lineNumber = lineNumber;
            this.rawText = rawText;
            this.reason = reason;
        }

        /** 1-based line (or table row) number in the source, or 0 if unknown. */
        public int getLineNumber() {
            return lineNumber;
        }

        public String getRawText() {
            return rawText;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return "line " + lineNumber + ": " + reason + " [" + rawText + "]";
        }
    }

    private final List<ListedNumber> entries;
    private final List<SkippedLine> skipped;

    ParseResult(List<ListedNumber> entries, List<SkippedLine> skipped) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.skipped = Collections.unmodifiableList(new ArrayList<>(skipped));
    }

    public List<ListedNumber> getEntries() {
        return entries;
    }

    public List<SkippedLine> getSkipped() {
        return skipped;
    }

    @Override
    public String toString() {
        return "ParseResult{entries=" + entries.size() + ", skipped=" + skipped.size() + '}';
    }

}
