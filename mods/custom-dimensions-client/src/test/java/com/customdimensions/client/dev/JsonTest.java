package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The wire format. A shell asserting with {@code jq} gets nothing useful from
 * output that is almost JSON, so quoting, control characters and non-finite
 * doubles are all pinned here.
 */
class JsonTest {

    // ----------------------------------------------------------------- quote

    @Test
    void aPlainStringIsQuoted() {
        assertEquals("\"hello\"", Json.quote("hello"));
    }

    @Test
    void nullIsTheJsonLiteral() {
        assertEquals("null", Json.quote(null));
    }

    @Test
    void quotesAndBackslashesAreEscaped() {
        assertEquals("\"a\\\"b\\\\c\"", Json.quote("a\"b\\c"));
    }

    @Test
    void newlinesTabsAndReturnsGetTheirShortEscapes() {
        assertEquals("\"a\\nb\\tc\\rd\"", Json.quote("a\nb\tc\rd"));
    }

    @Test
    void otherControlCharactersBecomeUnicodeEscapes() {
        String control = String.valueOf((char) 0) + (char) 0x1f;
        assertEquals("\"\\u0000\\u001f\"", Json.quote(control));
    }

    @Test
    void aFilePathSurvivesQuoting() {
        assertEquals("\"/tmp/e2e/before.png\"", Json.quote("/tmp/e2e/before.png"));
    }

    // ---------------------------------------------------------------- number

    @Test
    void aWholeNumberHasNoTrailingZeros() {
        assertEquals("1", Json.number(1.0));
    }

    @Test
    void aRoundHundredIsNotWrittenInScientificNotation() {
        assertEquals("100", Json.number(100.0));
    }

    @Test
    void floatingPointNoiseIsRoundedAway() {
        assertEquals("11.8", Json.number(11.799999999999999));
    }

    @Test
    void threeDecimalPlacesAreKept() {
        assertEquals("-3.142", Json.number(-3.14159));
    }

    @Test
    void notANumberIsNull() {
        assertEquals("null", Json.number(Double.NaN));
    }

    @Test
    void infinityIsNull() {
        assertEquals("null", Json.number(Double.POSITIVE_INFINITY));
        assertEquals("null", Json.number(Double.NEGATIVE_INFINITY));
    }

    // ------------------------------------------------------------------ objects

    @Test
    void anEmptyObjectIsBraces() {
        assertEquals("{}", Json.obj().toString());
    }

    @Test
    void fieldsAreWrittenInTheOrderTheyAreAdded() {
        assertEquals("{\"mod\":\"customdimensionsclient\",\"tick\":41,\"ok\":true}",
                Json.obj()
                        .str("mod", "customdimensionsclient")
                        .num("tick", 41L)
                        .bool("ok", true)
                        .toString());
    }

    @Test
    void aDoubleFieldUsesTheNumberRule() {
        assertEquals("{\"travelled\":11.8}", Json.obj().num("travelled", 11.799999999999999).toString());
    }

    @Test
    void aNullStringFieldIsWrittenAsNull() {
        assertEquals("{\"currentScreen\":null}", Json.obj().str("currentScreen", null).toString());
    }

    @Test
    void aRawFieldIsInsertedUntouched() {
        assertEquals("{\"before\":{\"x\":1}}", Json.obj().raw("before", "{\"x\":1}").toString());
    }

    @Test
    void aRawFieldThatIsNullIsWrittenAsNull() {
        assertEquals("{\"before\":null}", Json.obj().raw("before", null).toString());
    }

    // ------------------------------------------------------------------ arrays

    @Test
    void aNumberArrayIsBracketed() {
        assertEquals("[1,64.5,-3]", Json.numbers(1.0, 64.5, -3.0));
    }

    @Test
    void anEmptyNumberArrayIsBrackets() {
        assertEquals("[]", Json.numbers());
    }

    @Test
    void aPositionArrayNestsInsideAnObject() {
        assertEquals("{\"pos\":[8,64,-12]}",
                Json.obj().raw("pos", Json.numbers(8, 64, -12)).toString());
    }
}
