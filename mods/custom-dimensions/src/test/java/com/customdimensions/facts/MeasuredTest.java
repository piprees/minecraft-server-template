package com.customdimensions.facts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measurements are exact or absent, never estimated — the whole point of
 * this type. These tests pin the ways that could quietly stop being true.
 */
class MeasuredTest {

    @Test
    void aMeasurementIsExactlyOneOfAValueOrAReason() {
        assertThrows(IllegalArgumentException.class, () -> new Measured<>(null, null),
                "neither a value nor a reason is not a measurement");
        assertThrows(IllegalArgumentException.class, () -> new Measured<>(1.0, "why"),
                "a value AND a reason lets a caller read whichever suits it");
    }

    @Test
    void absenceMustCarryAReason() {
        assertThrows(IllegalArgumentException.class, () -> Measured.absent(null));
        assertThrows(IllegalArgumentException.class, () -> Measured.absent("  "),
                "\"not measured\" tells a reader nothing they can act on");
    }

    @Test
    void ofRefusesNullRatherThanInventingAnAbsence() {
        // The failure this prevents: of(maybeNull) silently becoming an absence
        // with no reason, which is how a default sneaks in later.
        assertThrows(IllegalArgumentException.class, () -> Measured.of(null));
    }

    @Test
    void anAbsentValueCannotBeReadAsANumber() {
        Measured<Double> m = Measured.absent("this dimension has no terrain");
        assertFalse(m.isPresent());
        IllegalStateException e = assertThrows(IllegalStateException.class, m::orThrow);
        assertTrue(e.getMessage().contains("no terrain"),
                "the reason must survive to the point of use");
    }

    @Test
    void absenceSerialisesAsAnObjectSoNothingCanCoerceItToZero() {
        // A viewer reading this would have to render an object as a number to
        // get 0 out of it — which is exactly the mistake the shape prevents.
        assertEquals("{\"absent\": \"no sea level\"}",
                Measured.<Double>absent("no sea level").toJson(Json::number));
        assertEquals("0.5", Measured.of(0.5).toJson(Json::number));
    }

    @Test
    void mapCarriesTheReasonThroughRatherThanLosingIt() {
        Measured<Integer> absent = Measured.absent("nothing generated here");
        Measured<String> mapped = absent.map(String::valueOf);
        assertFalse(mapped.isPresent());
        assertEquals("nothing generated here", mapped.reason());
        assertEquals("7", Measured.of(7).map(String::valueOf).orThrow());
    }

    @Test
    void nonFiniteValuesAreRefusedRatherThanWrittenAsJsonLiterals() {
        // NaN and Infinity are not valid JSON and are not measurements. A
        // division by zero upstream must surface as an absence, not as a
        // token no parser accepts.
        assertThrows(IllegalArgumentException.class, () -> Json.number(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> Json.number(Double.POSITIVE_INFINITY));
    }

    @Test
    void doublesAreWrittenAsExactReprNotRounded() {
        // A %.6f rendering would make two different measurements compare equal,
        // which is the one thing a facts layer must never do.
        assertEquals("0.1", Json.number(0.1));
        assertEquals("1.0E-7", Json.number(0.0000001));
        assertEquals("0.30000000000000004", Json.number(0.1 + 0.2));
    }
}
