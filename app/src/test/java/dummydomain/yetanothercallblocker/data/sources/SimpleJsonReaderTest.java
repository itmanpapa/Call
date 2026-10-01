package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SimpleJsonReaderTest {

    private static Object parse(String json) throws IOException {
        SimpleJsonReader reader = new SimpleJsonReader(new StringReader(json));
        Object value = reader.readValue();
        assertEquals(SimpleJsonReader.Token.END_DOCUMENT, reader.peek());
        return value;
    }

    private static void assertMalformed(String json) {
        try {
            parse(json);
            fail("Expected an exception for " + json);
        } catch (SimpleJsonReader.MalformedJsonException expected) {
            // ok
        } catch (IOException e) {
            fail("Unexpected " + e);
        }
    }

    @Test
    public void parsesTree() throws IOException {
        Object value = parse(" {\"a\": 1, \"b\" : [true, false, null, \"x\"],\n"
                + "\"c\": {\"d\": -2.5e1}, \"e\": 12345678901234, \"f\": []} ");

        Map<?, ?> map = (Map<?, ?>) value;
        assertEquals(Arrays.asList("a", "b", "c", "e", "f"), Arrays.asList(map.keySet().toArray()));
        assertEquals(1L, map.get("a"));
        assertEquals(Arrays.asList(true, false, null, "x"), map.get("b"));
        assertEquals(-25.0, ((Map<?, ?>) map.get("c")).get("d"));
        assertEquals(12345678901234L, map.get("e"));
        assertTrue(((List<?>) map.get("f")).isEmpty());
    }

    @Test
    public void parsesStringEscapes() throws IOException {
        assertEquals("a\"b\\c/d\b\f\n\r\tä€", parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u00e4\\u20AC\""));
        assertEquals("Grüße", parse("\"Grüße\""));
    }

    @Test
    public void parsesScalarsAndEmptyContainers() throws IOException {
        assertEquals(0L, parse("0"));
        assertNull(parse("null"));
        assertEquals(true, parse("true"));
        assertTrue(((Map<?, ?>) parse("{}")).isEmpty());
        assertTrue(((List<?>) parse("[ ]")).isEmpty());
        // too large for a long
        assertEquals(1e20, parse("100000000000000000000"));
    }

    @Test
    public void streaming() throws IOException {
        SimpleJsonReader reader = new SimpleJsonReader(new StringReader(
                "{\"skip\": {\"x\": [1, {\"y\": 2}]}, \"items\": [{\"n\": 1}, {\"n\": 2}], \"z\": 3}"));
        reader.beginObject();
        assertEquals("skip", reader.nextName());
        reader.skipValue();
        assertEquals("items", reader.nextName());
        reader.beginArray();
        long sum = 0;
        while (reader.hasNext()) {
            sum += SimpleJsonReader.asLong(((Map<?, ?>) reader.readValue()).get("n"), 0);
        }
        reader.endArray();
        assertEquals(3, sum);
        assertTrue(reader.hasNext());
        assertEquals("z", reader.nextName());
        assertEquals(3L, reader.nextNumber());
        assertFalse(reader.hasNext());
        reader.endObject();
        assertEquals(SimpleJsonReader.Token.END_DOCUMENT, reader.peek());
    }

    @Test
    public void readsAcrossBufferBoundaries() throws IOException {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 5000; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"phone\":\"+49301234").append(i).append("\",\"votes\":").append(i).append('}');
        }
        sb.append(']');

        List<?> list = (List<?>) parse(sb.toString());
        assertEquals(5000, list.size());
        assertEquals("+493012344999", ((Map<?, ?>) list.get(4999)).get("phone"));
        assertEquals(4999L, ((Map<?, ?>) list.get(4999)).get("votes"));
    }

    @Test
    public void rejectsMalformedInput() {
        assertMalformed("");
        assertMalformed("{");
        assertMalformed("{\"a\" 1}");
        assertMalformed("{\"a\": 1,}");
        assertMalformed("[1 2]");
        assertMalformed("[1,]");
        assertMalformed("{a: 1}");
        assertMalformed("\"unterminated");
        assertMalformed("\"bad \\x escape\"");
        assertMalformed("tru");
        assertMalformed("nullx");
        assertMalformed("-");
        assertMalformed("{} {}");
        assertMalformed("<html>");
    }

    @Test
    public void rejectsDeepNesting() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SimpleJsonReader.MAX_DEPTH + 2; i++) sb.append('[');
        assertMalformed(sb.toString());
    }

    @Test
    public void valueHelpers() {
        assertEquals("12", SimpleJsonReader.asString(12L));
        assertNull(SimpleJsonReader.asString(Arrays.asList(1)));
        assertEquals(7, SimpleJsonReader.asLong("7", -1));
        assertEquals(-1, SimpleJsonReader.asLong("x", -1));
        assertEquals(3, SimpleJsonReader.asLong(3.9, -1));
        assertTrue(SimpleJsonReader.asBoolean("true", false));
        assertFalse(SimpleJsonReader.asBoolean(null, false));
    }

}
