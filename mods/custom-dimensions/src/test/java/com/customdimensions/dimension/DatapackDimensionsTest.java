package com.customdimensions.dimension;

import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two parts of {@link DatapackDimensions} that do not need live registries:
 * where the resource is looked for, and how mod JSON is parsed.
 *
 * <p>The decode itself — {@code DimensionOptions.CODEC} under {@code RegistryOps}
 * — is NOT covered here and cannot be: it needs the server's dynamic registries,
 * which this suite cannot bootstrap. Only a boot proves that path.
 */
class DatapackDimensionsTest {

    @Test
    void looksForTheDimensionUnderItsRegistryDirectory() {
        assertEquals("dimension/the_nether.json",
                DatapackDimensions.resourcePathFor("dimension", "the_nether"));
    }

    @Test
    void aLineCommentDoesNotLoseTheFile() {
        // T35: mods ship lenient JSON and a strict parser drops the whole file.
        JsonElement e = DatapackDimensions.parseLenient(
                "{\n  // which generator\n  \"type\": \"minecraft:noise\"\n}");
        assertEquals("minecraft:noise", e.getAsJsonObject().get("type").getAsString());
    }

    @Test
    void aBlockCommentDoesNotLoseTheFile() {
        JsonElement e = DatapackDimensions.parseLenient(
                "{ /* RAW_GENERATION */ \"type\": \"minecraft:noise\" }");
        assertEquals("minecraft:noise", e.getAsJsonObject().get("type").getAsString());
    }

    @Test
    void aTrailingCommaIsREJECTED_asTheGameRejectsItToo() {
        // MEASURED: Gson's lenient JsonReader throws
        // "MalformedJsonException: Expected name" on a trailing comma in an
        // object. Vanilla reads worldgen JSON through the same parser, so an
        // entry carrying one never reached the registry either — accepting
        // more than the game does would compose from a file the game refused.
        assertThrows(JsonSyntaxException.class,
                () -> DatapackDimensions.parseLenient("{ \"a\": 1, \"b\": 2, }"));
    }

    @Test
    void aCommentMarkerInsideAStringSurvives() {
        // Stripping comments textually eats this one; a real parser does not.
        JsonElement e = DatapackDimensions.parseLenient(
                "{ \"settings\": \"minecraft:nether\", \"note\": \"a // b\" }");
        assertEquals("a // b", e.getAsJsonObject().get("note").getAsString());
    }

    @Test
    void readsTheNestedShapeADimensionEntryActuallyHas() {
        JsonElement e = DatapackDimensions.parseLenient(
                "{\"type\":\"minecraft:the_nether\",\"generator\":{\"type\":\"minecraft:noise\","
                + "\"settings\":\"minecraft:nether\",\"biome_source\":{\"type\":\"minecraft:multi_noise\","
                + "\"biomes\":[{\"biome\":\"incendium:weeping_valley\"}]}}}");
        var source = e.getAsJsonObject().getAsJsonObject("generator").getAsJsonObject("biome_source");
        assertEquals("minecraft:multi_noise", source.get("type").getAsString());
        assertEquals(1, source.getAsJsonArray("biomes").size());
        assertTrue(e.getAsJsonObject().has("type"));
    }
}
