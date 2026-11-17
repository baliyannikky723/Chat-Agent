package app.orrery.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    record Point(int x, String label, List<String> tags) {}

    @Test
    void parsesNestedDocuments() {
        JNode n = Json.tree("{\"a\":[1,2.5,{\"b\":null}],\"s\":\"x\\ny\",\"t\":true,\"big\":12345678901234567890}");
        assertEquals(1L, n.get("a").at(0).asLong(0));
        assertEquals(2.5, n.get("a").at(1).asDouble(0), 0.0001);
        assertTrue(n.get("a").at(2).get("b").isNull());
        assertTrue(n.get("missing").isMissing());
        assertEquals("x\ny", n.get("s").text());
        assertTrue(n.get("t").asBoolean(false));
        assertEquals("12345678901234567890", n.get("big").asString());
    }

    @Test
    void decodesUnicodeEscapesIncludingSurrogatePairs() {
        assertEquals("é😀", Json.tree("\"\\u00e9\\ud83d\\ude00\"").text());
    }

    @Test
    void writesAndRoundTrips() {
        Map<String, Object> obj = Json.obj("q", "say \"hi\"\n", "n", 3, "d", 1.5, "list", List.of(1, "two"), "nil", null);
        String text = Json.write(obj);
        assertEquals("{\"q\":\"say \\\"hi\\\"\\n\",\"n\":3,\"d\":1.5,\"list\":[1,\"two\"],\"nil\":null}", text);
        assertEquals(obj.get("q"), Json.tree(text).get("q").text());
    }

    @Test
    void writesRecordsAndDates() {
        String text = Json.write(new Point(2, "p", List.of("a")));
        assertEquals("{\"x\":2,\"label\":\"p\",\"tags\":[\"a\"]}", text);
        assertEquals("\"2026-11-09\"", Json.write(LocalDate.of(2026, 11, 9)));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(JsonException.class, () -> Json.parse("[1,2"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1} trailing"));
        assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));
    }

    @Test
    void lenientObjectFindsJsonInsideProseOrFences() {
        JNode n = Json.lenientObject("Sure! Here it is:\n```json\n{\"entities\":[\"Neon\"],\"claims\":[]}\n```");
        assertNotNull(n);
        assertEquals("Neon", n.get("entities").at(0).text());
        assertNull(Json.lenientObject("no json here"));
    }
}
