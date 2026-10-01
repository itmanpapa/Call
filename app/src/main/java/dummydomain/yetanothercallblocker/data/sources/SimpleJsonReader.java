package dummydomain.yetanothercallblocker.data.sources;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal streaming JSON (RFC 8259) reader.
 *
 * <p>{@code org.json} is only available on Android (its JVM stub throws), and the
 * project avoids heavy JSON libraries, so this small pull reader is used to parse
 * the responses of web APIs. It supports two styles that can be mixed:</p>
 * <ul>
 *     <li>streaming: {@link #beginObject()}, {@link #nextName()}, {@link #beginArray()},
 *     {@link #hasNext()}, ... (used for large arrays, so that the whole document
 *     never has to be held in memory as a tree);</li>
 *     <li>tree: {@link #readValue()} returns the next value as {@link Map} (insertion
 *     ordered), {@link List}, {@link String}, {@link Long} / {@link Double},
 *     {@link Boolean} or {@code null}.</li>
 * </ul>
 *
 * <p>Not thread-safe. Nesting depth is limited to protect against malicious input.</p>
 */
public class SimpleJsonReader implements Closeable {

    /** Type of the next token. */
    public enum Token {
        BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME,
        STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT
    }

    static final int MAX_DEPTH = 64;

    private static final int BUFFER_SIZE = 8192;

    /** Scope kinds on the stack. */
    private static final int EMPTY_ARRAY = 1;
    private static final int NONEMPTY_ARRAY = 2;
    private static final int EMPTY_OBJECT = 3;
    /** In an object, a name was read, a value is expected. */
    private static final int DANGLING_NAME = 4;
    /** In an object after a value, a comma or end is expected. */
    private static final int NONEMPTY_OBJECT = 5;
    private static final int EMPTY_DOCUMENT = 6;
    private static final int NONEMPTY_DOCUMENT = 7;

    private final Reader in;
    private final char[] buffer = new char[BUFFER_SIZE];
    private int pos;
    private int limit;
    private long consumed; // chars before buffer[0], for error messages

    private final int[] stack = new int[MAX_DEPTH + 1];
    private int stackSize;

    /** The peeked token, or null. */
    private Token peeked;

    public SimpleJsonReader(Reader in) {
        if (in == null) throw new NullPointerException("in");
        this.in = in;
        stack[stackSize++] = EMPTY_DOCUMENT;
    }

    /**
     * @return the type of the next token without consuming it
     */
    public Token peek() throws IOException {
        if (peeked != null) return peeked;

        int scope = stack[stackSize - 1];
        switch (scope) {
            case EMPTY_ARRAY:
                stack[stackSize - 1] = NONEMPTY_ARRAY;
                if (nextNonWhitespace() == ']') {
                    pos++;
                    return peeked = Token.END_ARRAY;
                }
                break;
            case NONEMPTY_ARRAY: {
                int c = nextNonWhitespace();
                pos++;
                if (c == ']') return peeked = Token.END_ARRAY;
                if (c != ',') throw syntaxError("Expected ',' or ']'");
                break;
            }
            case EMPTY_OBJECT:
            case NONEMPTY_OBJECT: {
                int c = nextNonWhitespace();
                if (c == '}') {
                    pos++;
                    return peeked = Token.END_OBJECT;
                }
                if (scope == NONEMPTY_OBJECT) {
                    pos++;
                    if (c != ',') throw syntaxError("Expected ',' or '}'");
                    c = nextNonWhitespace();
                }
                if (c != '"') throw syntaxError("Expected a name");
                stack[stackSize - 1] = DANGLING_NAME;
                return peeked = Token.NAME;
            }
            case DANGLING_NAME: {
                int c = nextNonWhitespace();
                pos++;
                if (c != ':') throw syntaxError("Expected ':'");
                stack[stackSize - 1] = NONEMPTY_OBJECT;
                break;
            }
            case EMPTY_DOCUMENT:
                stack[stackSize - 1] = NONEMPTY_DOCUMENT;
                break;
            case NONEMPTY_DOCUMENT:
                if (nextNonWhitespaceOrEof() == -1) return peeked = Token.END_DOCUMENT;
                throw syntaxError("Unexpected data after the top-level value");
            default:
                throw new IllegalStateException("Bad scope " + scope);
        }

        int c = nextNonWhitespace();
        switch (c) {
            case '{':
                return peeked = Token.BEGIN_OBJECT;
            case '[':
                return peeked = Token.BEGIN_ARRAY;
            case '"':
                return peeked = Token.STRING;
            case 't':
            case 'f':
                return peeked = Token.BOOLEAN;
            case 'n':
                return peeked = Token.NULL;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return peeked = Token.NUMBER;
                throw syntaxError("Unexpected character '" + (char) c + "'");
        }
    }

    public boolean hasNext() throws IOException {
        Token t = peek();
        return t != Token.END_OBJECT && t != Token.END_ARRAY && t != Token.END_DOCUMENT;
    }

    public void beginObject() throws IOException {
        expect(Token.BEGIN_OBJECT);
        pos++;
        push(EMPTY_OBJECT);
    }

    public void endObject() throws IOException {
        expect(Token.END_OBJECT);
        stackSize--;
    }

    public void beginArray() throws IOException {
        expect(Token.BEGIN_ARRAY);
        pos++;
        push(EMPTY_ARRAY);
    }

    public void endArray() throws IOException {
        expect(Token.END_ARRAY);
        stackSize--;
    }

    public String nextName() throws IOException {
        expect(Token.NAME);
        pos++; // opening quote
        return readString();
    }

    public String nextString() throws IOException {
        expect(Token.STRING);
        pos++;
        return readString();
    }

    public boolean nextBoolean() throws IOException {
        expect(Token.BOOLEAN);
        if (consumeLiteral("true")) return true;
        if (consumeLiteral("false")) return false;
        throw syntaxError("Expected a boolean");
    }

    public void nextNull() throws IOException {
        expect(Token.NULL);
        if (!consumeLiteral("null")) throw syntaxError("Expected null");
    }

    /**
     * @return the next number as {@link Long} if it is an integer that fits,
     * otherwise as {@link Double}
     */
    public Number nextNumber() throws IOException {
        expect(Token.NUMBER);
        peeked = null;

        StringBuilder sb = new StringBuilder();
        boolean integer = true;
        while (true) {
            if (pos == limit && !fill()) break;
            char c = buffer[pos];
            if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                sb.append(c);
            } else if (c == '.' || c == 'e' || c == 'E') {
                integer = false;
                sb.append(c);
            } else {
                break;
            }
            pos++;
            if (sb.length() > 64) throw syntaxError("Number too long");
        }

        String s = sb.toString();
        try {
            if (integer) {
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException e) {
                    // too large for a long
                }
            }
            double d = Double.parseDouble(s);
            if (Double.isNaN(d) || Double.isInfinite(d)) throw syntaxError("Bad number " + s);
            return d;
        } catch (NumberFormatException e) {
            throw syntaxError("Bad number " + s);
        }
    }

    /**
     * Reads the next value as a tree (see the class description).
     * Must not be called when the next token is a name or an end token.
     */
    public Object readValue() throws IOException {
        Token t = peek();
        switch (t) {
            case BEGIN_OBJECT: {
                Map<String, Object> map = new LinkedHashMap<>();
                beginObject();
                while (hasNext()) {
                    String name = nextName();
                    map.put(name, readValue());
                }
                endObject();
                return map;
            }
            case BEGIN_ARRAY: {
                List<Object> list = new ArrayList<>();
                beginArray();
                while (hasNext()) list.add(readValue());
                endArray();
                return list;
            }
            case STRING:
                return nextString();
            case NUMBER:
                return nextNumber();
            case BOOLEAN:
                return nextBoolean();
            case NULL:
                nextNull();
                return null;
            default:
                throw syntaxError("Expected a value but was " + t);
        }
    }

    /** Skips the next value (including nested objects and arrays). */
    public void skipValue() throws IOException {
        Token t = peek();
        switch (t) {
            case BEGIN_OBJECT:
                beginObject();
                while (hasNext()) {
                    nextName();
                    skipValue();
                }
                endObject();
                break;
            case BEGIN_ARRAY:
                beginArray();
                while (hasNext()) skipValue();
                endArray();
                break;
            case STRING:
                nextString();
                break;
            case NUMBER:
                nextNumber();
                break;
            case BOOLEAN:
                nextBoolean();
                break;
            case NULL:
                nextNull();
                break;
            default:
                throw syntaxError("Expected a value but was " + t);
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // value helpers for trees

    /** @return the value as a string (numbers and booleans are converted), or null */
    public static String asString(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return null;
    }

    /** @return the value as a long, or {@code defaultValue} if it is not a number */
    public static long asLong(Object value, long defaultValue) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** @return the value as a boolean, or {@code defaultValue} if it is not a boolean */
    public static boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            String s = (String) value;
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return defaultValue;
    }

    // internals

    private void expect(Token expected) throws IOException {
        Token t = peek();
        if (t != expected) throw syntaxError("Expected " + expected + " but was " + t);
        peeked = null;
    }

    private void push(int scope) throws IOException {
        if (stackSize > MAX_DEPTH) throw syntaxError("Nesting too deep");
        stack[stackSize++] = scope;
    }

    private boolean consumeLiteral(String literal) throws IOException {
        for (int i = 0; i < literal.length(); i++) {
            if (pos == limit && !fill()) return false;
            if (buffer[pos] != literal.charAt(i)) return false;
            pos++;
        }
        if (pos < limit || fill()) {
            char c = buffer[pos];
            if (Character.isLetterOrDigit(c)) throw syntaxError("Unexpected literal");
        }
        return true;
    }

    /** Reads a string after the opening quote, consuming the closing quote. */
    private String readString() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos == limit && !fill()) throw syntaxError("Unterminated string");
            char c = buffer[pos++];
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos == limit && !fill()) throw syntaxError("Unterminated escape");
                char e = buffer[pos++];
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': {
                        int v = 0;
                        for (int i = 0; i < 4; i++) {
                            if (pos == limit && !fill()) throw syntaxError("Unterminated escape");
                            int d = Character.digit(buffer[pos++], 16);
                            if (d < 0) throw syntaxError("Bad unicode escape");
                            v = (v << 4) | d;
                        }
                        sb.append((char) v);
                        break;
                    }
                    default:
                        throw syntaxError("Bad escape '\\" + e + "'");
                }
            } else if (c < 0x20) {
                throw syntaxError("Control character in string");
            } else {
                sb.append(c);
            }
        }
    }

    private int nextNonWhitespace() throws IOException {
        int c = nextNonWhitespaceOrEof();
        if (c == -1) throw syntaxError("Unexpected end of input");
        return c;
    }

    /** Skips whitespace; doesn't consume the returned char. */
    private int nextNonWhitespaceOrEof() throws IOException {
        while (true) {
            if (pos == limit && !fill()) return -1;
            char c = buffer[pos];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') {
                pos++;
                continue;
            }
            return c;
        }
    }

    private boolean fill() throws IOException {
        consumed += limit;
        pos = 0;
        limit = 0;
        int n;
        do {
            n = in.read(buffer, 0, buffer.length);
        } while (n == 0);
        if (n < 0) return false;
        limit = n;
        return true;
    }

    private IOException syntaxError(String message) {
        return new MalformedJsonException(message + " at offset " + (consumed + pos));
    }

    /** Thrown for syntactically invalid JSON. */
    public static class MalformedJsonException extends IOException {

        private static final long serialVersionUID = 1L;

        public MalformedJsonException(String message) {
            super(message);
        }
    }

}
