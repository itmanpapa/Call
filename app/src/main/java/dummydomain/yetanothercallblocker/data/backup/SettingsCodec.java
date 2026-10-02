package dummydomain.yetanothercallblocker.data.backup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Text form of a settings dump (SharedPreferences values), one value per line:
 * <pre>
 * callguard-settings	1
 * b	blockHiddenNumbers	true
 * i	phoneBlockMinVotes	10
 * s	callLogGrouping	consecutive
 * set	blockInLimitedMode	blacklist	rating
 * </pre>
 * <p>Types: {@code b} boolean, {@code i} int, {@code l} long, {@code f} float,
 * {@code s} string, {@code set} string set (the elements follow). Values escape
 * "\" as "\\", tab as "\t" and line breaks as "\n" / "\r". Lines of unknown types are
 * skipped (written by a newer version).</p>
 *
 * <p>Plain Java, no Android dependencies.</p>
 */
public final class SettingsCodec {

    public static final String HEADER = "callguard-settings";
    public static final int VERSION = 1;

    private SettingsCodec() {
    }

    /** Unsupported values (null, other types) are skipped. Keys are sorted. */
    public static String encode(Map<String, ?> values) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\t').append(VERSION).append('\n');
        for (Map.Entry<String, ?> e : new TreeMap<>(values).entrySet()) {
            String key = escape(e.getKey());
            Object v = e.getValue();
            if (v instanceof Boolean) {
                line(sb, "b", key, v.toString());
            } else if (v instanceof Integer) {
                line(sb, "i", key, v.toString());
            } else if (v instanceof Long) {
                line(sb, "l", key, v.toString());
            } else if (v instanceof Float) {
                line(sb, "f", key, v.toString());
            } else if (v instanceof String) {
                line(sb, "s", key, escape((String) v));
            } else if (v instanceof Set) {
                sb.append("set\t").append(key);
                for (Object element : new TreeSet<>((Set<?>) v)) {
                    sb.append('\t').append(escape(String.valueOf(element)));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * @return key to Boolean, Integer, Long, Float, String or {@code Set<String>}
     * @throws IOException if the text is not a settings dump or has a newer version
     */
    public static Map<String, Object> decode(String text) throws IOException {
        BufferedReader in = new BufferedReader(new StringReader(text));
        String header = in.readLine();
        if (header == null || !header.startsWith(HEADER + "\t")) {
            throw new IOException("Not a settings dump");
        }
        int version;
        try {
            version = Integer.parseInt(header.substring(HEADER.length() + 1).trim());
        } catch (NumberFormatException e) {
            throw new IOException("Bad settings version: " + header);
        }
        if (version > VERSION) throw new IOException("Unsupported settings version: " + version);

        Map<String, Object> values = new LinkedHashMap<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 2 || f[1].isEmpty()) continue;
            String key = unescape(f[1]);
            try {
                switch (f[0]) {
                    case "b":
                        values.put(key, Boolean.parseBoolean(f[2]));
                        break;
                    case "i":
                        values.put(key, Integer.parseInt(f[2]));
                        break;
                    case "l":
                        values.put(key, Long.parseLong(f[2]));
                        break;
                    case "f":
                        values.put(key, Float.parseFloat(f[2]));
                        break;
                    case "s":
                        values.put(key, unescape(f[2]));
                        break;
                    case "set":
                        Set<String> set = new LinkedHashSet<>();
                        for (int i = 2; i < f.length; i++) set.add(unescape(f[i]));
                        values.put(key, set);
                        break;
                    default:
                        // a type of a newer version
                        break;
                }
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                throw new IOException("Bad settings line: " + line, e);
            }
        }
        return values;
    }

    private static void line(StringBuilder sb, String type, String key, String value) {
        sb.append(type).append('\t').append(key).append('\t').append(value).append('\n');
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 't': sb.append('\t'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

}
