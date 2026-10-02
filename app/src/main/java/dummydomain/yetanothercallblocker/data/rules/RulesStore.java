package dummydomain.yetanothercallblocker.data.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the call rules in a small UTF-8 text file (no JSON).
 *
 * <p>Format: a header line {@code callguard-rules<TAB><version>}, then one rule per line
 * in priority order, as tab-separated {@code key=value} fields:</p>
 * <pre>
 * callguard-rules	1
 * id=1	type=HIDDEN_NUMBER	action=BLOCK	enabled=1
 * id=2	type=NUMBER_PATTERN	action=BLOCK	enabled=1	patterns=+44*, 0900*	exceptContacts=1
 * id=3	type=REPEATED_CALLER	action=ALLOW	enabled=1	window=3	schedule=1	start=1320	end=420
 * </pre>
 * <p>Values escape "\" as "\\", tab as "\t", line breaks as "\n" / "\r". Unknown keys
 * are ignored and lines with an unknown type or action are skipped (written by a newer
 * version), missing keys get the defaults. Empty lines and lines starting with "#" are
 * ignored. Saving writes a temporary file and renames it over the old one.</p>
 */
public class RulesStore {

    public static final String HEADER = "callguard-rules";
    public static final int VERSION = 1;

    private static final Logger LOG = LoggerFactory.getLogger(RulesStore.class);

    private final File file;

    public RulesStore(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    /**
     * @return the rules in priority order; empty if the file doesn't exist
     * @throws IOException if the file can't be read or isn't a rules file
     */
    public List<CallRule> load() throws IOException {
        try (Reader reader = new InputStreamReader(new FileInputStream(file),
                StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (FileNotFoundException e) {
            if (file.exists()) throw e;
            return new ArrayList<>();
        }
    }

    /** Replaces the file with the rules. */
    public void save(List<CallRule> rules) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Can't create directory " + dir);
        }

        File tmp = new File(dir, file.getName() + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp),
                StandardCharsets.UTF_8)) {
            write(rules, writer);
        }

        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // format

    public static String toText(List<CallRule> rules) {
        StringWriter writer = new StringWriter();
        try {
            write(rules, writer);
        } catch (IOException e) {
            throw new IllegalStateException(e); // StringWriter doesn't throw
        }
        return writer.toString();
    }

    public static List<CallRule> fromText(String text) throws IOException {
        return read(new StringReader(text));
    }

    static void write(List<CallRule> rules, Writer writer) throws IOException {
        writer.write(HEADER + "\t" + VERSION + "\n");
        for (CallRule rule : rules) {
            StringBuilder sb = new StringBuilder();
            field(sb, "id", String.valueOf(rule.getId()));
            field(sb, "type", rule.getType().name());
            field(sb, "action", rule.getAction().name());
            field(sb, "enabled", flag(rule.isEnabled()));
            if (!rule.getPatterns().isEmpty()) field(sb, "patterns", rule.getPatterns());
            field(sb, "exceptContacts", flag(rule.isExceptContacts()));
            if (rule.getType() == RuleType.REPEATED_CALLER) {
                field(sb, "window", String.valueOf(rule.getRepeatWindowMinutes()));
            }
            field(sb, "schedule", flag(rule.isScheduleEnabled()));
            field(sb, "start", String.valueOf(rule.getScheduleStart()));
            field(sb, "end", String.valueOf(rule.getScheduleEnd()));
            if (!rule.getLabel().isEmpty()) field(sb, "label", rule.getLabel());
            sb.append('\n');
            writer.write(sb.toString());
        }
    }

    private static String flag(boolean value) {
        return value ? "1" : "0";
    }

    private static void field(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append('\t');
        sb.append(key).append('=').append(escape(value));
    }

    static List<CallRule> read(Reader reader) throws IOException {
        BufferedReader in = new BufferedReader(reader);

        String header = in.readLine();
        if (header != null && header.startsWith("﻿")) header = header.substring(1);
        if (header == null || !header.startsWith(HEADER + "\t")) {
            throw new IOException("Not a rules file");
        }
        int version;
        try {
            version = Integer.parseInt(header.substring(HEADER.length() + 1).trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid rules file version: " + header);
        }
        if (version > VERSION) {
            LOG.warn("read() file version {} is newer than {}, reading what is known",
                    version, VERSION);
        }

        List<CallRule> rules = new ArrayList<>();
        String line;
        int lineNumber = 1;
        while ((line = in.readLine()) != null) {
            lineNumber++;
            if (line.trim().isEmpty() || line.startsWith("#")) continue;

            CallRule rule = parseRule(line);
            if (rule != null) {
                rules.add(rule);
            } else {
                LOG.warn("read() skipped line {}", lineNumber);
            }
        }
        return rules;
    }

    private static CallRule parseRule(String line) {
        Map<String, String> fields = new HashMap<>();
        for (String field : line.split("\t")) {
            int eq = field.indexOf('=');
            if (eq <= 0) continue;
            fields.put(field.substring(0, eq), unescape(field.substring(eq + 1)));
        }

        RuleType type = RuleType.fromName(fields.get("type"));
        if (type == null) return null;

        RuleAction action = RuleAction.fromName(fields.get("action"));
        if (action == null) {
            if (type != RuleType.REPEATED_CALLER) return null;
            action = RuleAction.ALLOW;
        }

        try {
            CallRule.Builder builder = CallRule.builder(type)
                    .id(parseLong(fields.get("id"), 0))
                    .action(action)
                    .enabled(parseFlag(fields.get("enabled"), true))
                    .patterns(fields.getOrDefault("patterns", ""))
                    .exceptContacts(parseFlag(fields.get("exceptContacts"), true))
                    .repeatWindowMinutes((int) parseLong(fields.get("window"),
                            CallRule.DEFAULT_REPEAT_WINDOW_MINUTES))
                    .schedule(parseFlag(fields.get("schedule"), false),
                            (int) parseLong(fields.get("start"), CallRule.DEFAULT_SCHEDULE_START),
                            (int) parseLong(fields.get("end"), CallRule.DEFAULT_SCHEDULE_END))
                    .label(fields.getOrDefault("label", ""));
            return builder.build();
        } catch (RuntimeException e) {
            LOG.warn("parseRule() invalid rule", e);
            return null;
        }
    }

    private static long parseLong(String value, long defValue) {
        if (value == null || value.isEmpty()) return defValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    private static boolean parseFlag(String value, boolean defValue) {
        if (value == null || value.isEmpty()) return defValue;
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\t': sb.append("\\t"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 't': sb.append('\t'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    default: sb.append(next); // "\\" and unknown escapes
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

}
