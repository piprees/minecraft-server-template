package com.customdimensions.command;

import com.customdimensions.config.DimensionConfig;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link InputHash#hashOf} is the pure core — no {@code FabricLoader} or
 * {@code MinecraftServer} involved — so these tests call it directly with
 * hand-supplied stack version and mod list rather than going through
 * {@link InputHash#of}.
 */
class InputHashTest {

    private static DimensionConfig dim(String name, String type) {
        DimensionConfig def = new DimensionConfig();
        def.setName(name);
        def.setType(type);
        def.setSeed(42L);
        return def;
    }

    private static final String ARTEFACT = "artefact-hash-abc123";

    @Test
    void sameInputsGiveTheSameHash() {
        DimensionConfig a = dim("the_boneyard", "overworld");
        DimensionConfig b = dim("the_boneyard", "overworld");
        List<String> mods = List.of("fabric-api=0.100.0", "customdimensions=1.2.3");

        assertEquals(
                InputHash.hashOf(a, "v4.2.0", mods, ARTEFACT),
                InputHash.hashOf(b, "v4.2.0", mods, ARTEFACT));
    }

    @Test
    void changedConfigGivesADifferentHash() {
        DimensionConfig a = dim("the_boneyard", "overworld");
        DimensionConfig b = dim("the_boneyard", "nether");
        List<String> mods = List.of("fabric-api=0.100.0");

        assertNotEquals(
                InputHash.hashOf(a, "v4.2.0", mods, ARTEFACT),
                InputHash.hashOf(b, "v4.2.0", mods, ARTEFACT));
    }

    @Test
    void changedStackVersionGivesADifferentHash() {
        DimensionConfig def = dim("the_boneyard", "overworld");
        List<String> mods = List.of("fabric-api=0.100.0");

        assertNotEquals(
                InputHash.hashOf(def, "v4.2.0", mods, ARTEFACT),
                InputHash.hashOf(def, "v4.3.0", mods, ARTEFACT));
    }

    @Test
    void changedModListGivesADifferentHash() {
        DimensionConfig def = dim("the_boneyard", "overworld");

        assertNotEquals(
                InputHash.hashOf(def, "v4.2.0", List.of("fabric-api=0.100.0"), ARTEFACT),
                InputHash.hashOf(def, "v4.2.0", List.of("fabric-api=0.101.0"), ARTEFACT));
    }

    @Test
    void changedArtefactHashGivesADifferentHashEvenWithAnIdenticalStackVersion() {
        DimensionConfig def = dim("the_boneyard", "overworld");
        List<String> mods = List.of("fabric-api=0.100.0");

        // Two dev builds both report "0.0.0-local" for stackVersion; the
        // artefact hash is what tells them apart.
        assertNotEquals(
                InputHash.hashOf(def, "0.0.0-local", mods, "artefact-hash-before"),
                InputHash.hashOf(def, "0.0.0-local", mods, "artefact-hash-after"));
    }

    @Test
    void unchangedArtefactHashGivesTheSameHash() {
        DimensionConfig def = dim("the_boneyard", "overworld");
        List<String> mods = List.of("fabric-api=0.100.0");

        assertEquals(
                InputHash.hashOf(def, "0.0.0-local", mods, ARTEFACT),
                InputHash.hashOf(def, "0.0.0-local", mods, ARTEFACT));
    }

    @Test
    void differentDimensionNameGivesADifferentHashEvenWithIdenticalSettings() {
        DimensionConfig a = dim("the_boneyard", "overworld");
        DimensionConfig b = dim("the_dustbowl", "overworld");
        List<String> mods = List.of("fabric-api=0.100.0");

        assertNotEquals(
                InputHash.hashOf(a, "v4.2.0", mods, ARTEFACT),
                InputHash.hashOf(b, "v4.2.0", mods, ARTEFACT));
    }

    @Test
    void nullDimensionHashesOnlyStackVersionArtefactAndMods() {
        List<String> mods = List.of("fabric-api=0.100.0");

        assertEquals(
                InputHash.hashOf(null, "v4.2.0", mods, ARTEFACT),
                InputHash.hashOf(null, "v4.2.0", mods, ARTEFACT));
        assertNotEquals(
                InputHash.hashOf(null, "v4.2.0", mods, ARTEFACT),
                InputHash.hashOf(dim("the_boneyard", "overworld"), "v4.2.0", mods, ARTEFACT));
    }

    @Test
    void modListOrderDoesNotMatter() {
        DimensionConfig def = dim("the_boneyard", "overworld");

        assertEquals(
                InputHash.hashOf(def, "v4.2.0", List.of("b=2", "a=1"), ARTEFACT),
                InputHash.hashOf(def, "v4.2.0", List.of("a=1", "b=2"), ARTEFACT));
    }

    @Test
    void jsonKeyOrderDoesNotMatterInCanonicalisation() {
        JsonObject inOrder = new JsonObject();
        inOrder.addProperty("a", 1);
        inOrder.addProperty("b", 2);

        JsonObject reversed = new JsonObject();
        reversed.addProperty("b", 2);
        reversed.addProperty("a", 1);

        assertEquals(
                InputHash.canonicaliseJson(inOrder),
                InputHash.canonicaliseJson(reversed));
    }

    @Test
    void jsonCanonicalisationDistinguishesDifferentValues() {
        JsonObject a = new JsonObject();
        a.addProperty("x", 1);
        JsonObject b = new JsonObject();
        b.addProperty("x", 2);

        assertNotEquals(InputHash.canonicaliseJson(a), InputHash.canonicaliseJson(b));
    }
}
