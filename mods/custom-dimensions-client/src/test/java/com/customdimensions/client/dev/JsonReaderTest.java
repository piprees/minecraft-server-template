package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads request bodies. Text that is not JSON must be refused rather than
 * half-read: a body silently parsed as an empty object turns a typo in a
 * {@code curl} into an action that runs with every default.
 */
class JsonReaderTest {

    private static Map<String, Object> object(String text) {
        return JsonReader.object(text);
    }

    // ---------------------------------------------------------------- shapes

    @Test
    void anEmptyObjectReadsAsAnEmptyMap() {
        assertTrue(object("{}").isEmpty());
    }

    @Test
    void aFlatObjectKeepsEveryValueType() {
        Map<String, Object> read = object("{\"a\":\"x\",\"b\":12,\"c\":true,\"d\":null}");
        assertEquals("x", read.get("a"));
        assertEquals(12.0, (Double) read.get("b"));
        assertEquals(Boolean.TRUE, read.get("c"));
        assertNull(read.get("d"));
        assertTrue(read.containsKey("d"));
    }

    @Test
    void aNestedObjectIsAMap() {
        Map<String, Object> read = object("{\"walk\":{\"blocks\":12,\"timeoutMs\":20000}}");
        Map<?, ?> walk = assertInstanceOf(Map.class, read.get("walk"));
        assertEquals(12.0, (Double) walk.get("blocks"));
        assertEquals(20000.0, (Double) walk.get("timeoutMs"));
    }

    @Test
    void anArrayIsAList() {
        Map<String, Object> read = object("{\"pos\":[8,64,-12]}");
        List<?> pos = assertInstanceOf(List.class, read.get("pos"));
        assertEquals(List.of(8.0, 64.0, -12.0), pos);
    }

    @Test
    void anEmptyArrayIsAnEmptyList() {
        Map<String, Object> read = object("{\"pos\":[]}");
        assertEquals(List.of(), read.get("pos"));
    }

    @Test
    void whitespaceAnywhereIsTolerated() {
        Map<String, Object> read = object("  {\n  \"walk\" : {\t\"blocks\" : 12 }\n}  ");
        Map<?, ?> walk = assertInstanceOf(Map.class, read.get("walk"));
        assertEquals(12.0, (Double) walk.get("blocks"));
    }

    // --------------------------------------------------------------- numbers

    @Test
    void negativeAndFractionalNumbersAreRead() {
        Map<String, Object> read = object("{\"yaw\":-179.5,\"pitch\":90}");
        assertEquals(-179.5, (Double) read.get("yaw"));
        assertEquals(90.0, (Double) read.get("pitch"));
    }

    @Test
    void exponentNotationIsRead() {
        assertEquals(20000.0, (Double) object("{\"timeoutMs\":2e4}").get("timeoutMs"));
    }

    // --------------------------------------------------------------- strings

    @Test
    void shortEscapesAreDecoded() {
        assertEquals("a\nb\tc\"d\\e/f", object("{\"s\":\"a\\nb\\tc\\\"d\\\\e\\/f\"}").get("s"));
    }

    @Test
    void unicodeEscapesAreDecoded() {
        assertEquals("\u00e4", object("{\"s\":\"\\u00e4\"}").get("s"));
    }

    // --------------------------------------------------------------- refusals

    @Test
    void anEmptyBodyIsRefused() {
        assertThrows(JsonReader.Malformed.class, () -> object(""));
    }

    @Test
    void whitespaceOnlyIsRefused() {
        assertThrows(JsonReader.Malformed.class, () -> object("   \n "));
    }

    @Test
    void trailingGarbageIsRefused() {
        assertThrows(JsonReader.Malformed.class, () -> object("{\"a\":1} junk"));
    }

    @Test
    void anUnterminatedObjectIsRefused() {
        assertThrows(JsonReader.Malformed.class, () -> object("{\"a\":1"));
    }

    @Test
    void anUnterminatedStringIsRefused() {
        assertThrows(JsonReader.Malformed.class, () -> object("{\"a\":\"oops}"));
    }

    @Test
    void anUnterminatedArrayIsRefused() {
        assertThrows(JsonReader.Malformed.class, () -> object("{\"a\":[1,2}"));
    }

    @Test
    void aMissingColonIsRefused() {
        assertThrows(JsonReader.Malformed.class, () -> object("{\"a\" 1}"));
    }

    @Test
    void aBareWordIsRefused() {
        assertThrows(JsonReader.Malformed.class, () -> object("walk"));
    }

    @Test
    void aTopLevelArrayIsNotAnObject() {
        assertThrows(JsonReader.Malformed.class, () -> object("[1,2]"));
    }

    @Test
    void anUnquotedKeyIsRefused() {
        assertThrows(JsonReader.Malformed.class, () -> object("{walk: 1}"));
    }

    @Test
    void aTrailingCommaIsRefused() {
        assertThrows(JsonReader.Malformed.class, () -> object("{\"a\":1,}"));
    }

    @Test
    void aRefusalSaysWhereItGaveUp() {
        JsonReader.Malformed thrown =
                assertThrows(JsonReader.Malformed.class, () -> object("{\"a\" 1}"));
        assertTrue(thrown.getMessage().contains("offset 5"), thrown.getMessage());
    }

    // ------------------------------------------------------------ round trip

    /** What the writer produces, the reader must read. */
    @Test
    void theWritersOutputReadsBack() {
        String written = Json.obj()
                .str("dimension", "adventure:the_crucible")
                .num("health", 19.5)
                .bool("onGround", true)
                .str("currentScreen", null)
                .raw("pos", Json.numbers(8.25, 64, -12))
                .toString();
        Map<String, Object> read = object(written);
        assertEquals("adventure:the_crucible", read.get("dimension"));
        assertEquals(19.5, (Double) read.get("health"));
        assertEquals(Boolean.TRUE, read.get("onGround"));
        assertNull(read.get("currentScreen"));
        assertEquals(List.of(8.25, 64.0, -12.0), read.get("pos"));
    }

    @Test
    void aQuotedPathSurvivesTheRoundTrip() {
        String written = Json.obj().str("path", "/tmp/e2e/\"odd\"\\name.png").toString();
        assertEquals("/tmp/e2e/\"odd\"\\name.png", object(written).get("path"));
    }
}
