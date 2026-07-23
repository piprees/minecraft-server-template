package com.customdimensions.dimension;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExitTargetTest {
    private static final Gson GSON = new Gson();

    private ExitTarget parse(String json) {
        return ExitTarget.parse(GSON.fromJson(json, JsonElement.class));
    }

    @Test
    void shorthandsRoundTrip() {
        for (String mode : new String[]{"bed", "worldSpawn", "origin"}) {
            ExitTarget t = ExitTarget.parse(mode);
            assertNotNull(t, mode);
            assertEquals(mode, t.canonical());
            assertNotEquals(ExitTarget.Kind.DIMENSION, t.getKind());
        }
    }

    @Test
    void descriptorObjectsCanonicaliseAndRoundTrip() {
        assertEquals("dim!adventure:the_starwell!spawn",
                parse("{\"dimension\":\"adventure:the_starwell\"}").canonical());
        assertEquals("dim!adventure:the_starwell!anchor",
                parse("{\"dimension\":\"adventure:the_starwell\",\"arrival\":\"anchor\"}").canonical());
        assertEquals("dim!adventure:the_starwell!0,80,0",
                parse("{\"dimension\":\"adventure:the_starwell\",\"arrival\":[0,80,0]}").canonical());
        // canonical strings parse back to identical canonical strings
        for (String c : new String[]{"dim!adventure:x!spawn", "dim!adventure:x!anchor",
                                     "dim!adventure:x!12,-3,400"}) {
            ExitTarget t = ExitTarget.parse(c);
            assertNotNull(t, c);
            assertEquals(c, t.canonical());
            assertEquals(ExitTarget.Kind.DIMENSION, t.getKind());
            assertEquals("adventure:x", t.getDimensionId());
        }
    }

    @Test
    void invalidInputsReturnNull() {
        assertNull(ExitTarget.parse((String) null));
        assertNull(ExitTarget.parse(""));
        assertNull(ExitTarget.parse("attic"));                 // unknown shorthand
        assertNull(ExitTarget.parse("dim!not an id!spawn"));   // bad identifier
        assertNull(ExitTarget.parse("dim!adventure:x!sideways"));
        assertNull(parse("{\"arrival\":\"spawn\"}"));           // no dimension
        assertNull(parse("{\"dimension\":\"adventure:x\",\"arrival\":[1,2]}"));  // 2 coords
        assertNull(parse("{\"dimension\":\"CAPS BAD\"}"));
        assertNull(parse("42"));
    }

    @Test
    void canonicaliseFallsBackOnInvalid() {
        assertEquals("bed", ExitTarget.canonicalise(GSON.fromJson("\"nonsense\"",
                JsonElement.class), "bed"));
        assertEquals("dim!adventure:x!spawn", ExitTarget.canonicalise(
                GSON.fromJson("{\"dimension\":\"adventure:x\"}", JsonElement.class), "bed"));
    }
}
