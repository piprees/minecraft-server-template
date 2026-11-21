package com.customdimensions.compiler.convert;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The id-mapping rewrite over small definitions, one rule at a time. */
class IdMappingApplierTest {

    private static final NamespaceRegister REGISTER = new NamespaceRegister(Map.of(
            "cutmod", List.of(new NamespaceRegister.Owner("cutmod", "cut")),
            "dungeons_arise", List.of(new NamespaceRegister.Owner("dungeons_arise", "waiting")),
            "keptmod", List.of(new NamespaceRegister.Owner("keptmod", "kept"))));

    private static final IdMappingApplier.Targets TARGETS = new IdMappingApplier.Targets() {
        public boolean biome(String id) {
            return Set.of("minecraft:plains", "minecraft:desert", "adventure:prairie", "minecraft:crimson_forest").contains(id);
        }

        public boolean structure(String id) {
            return Set.of("minecraft:village_plains", "bridged:keep", "bridged:tower").contains(id);
        }

        public boolean structureSet(String id) {
            return Set.of("minecraft:villages", "bridged:tower").contains(id);
        }

        public boolean block(String id) {
            return Set.of("minecraft:obsidian", "minecraft:crying_obsidian").contains(id);
        }

        public boolean item(String id) {
            return Set.of("minecraft:lilac").contains(id);
        }

        public boolean dimension(String id) {
            return Set.of("adventure:the_aviary").contains(id);
        }
    };

    private static final IdMapping MAPPING = new IdMapping(List.of(
            row("cutmod:meadow", "biome", "adventure:prairie"),
            row("cutmod:field", "biome", "minecraft:plains"),
            row("cutmod:dunes", "biome", "minecraft:desert"),
            row("cutmod:gone", "biome", ""),
            row("cutmod:future", "biome", "adventure:not_built_yet"),
            row("cutmod:keep", "structure", "bridged:keep"),
            row("cutmod:castle", "structure", "bridged:keep"),
            row("cutmod:tower", "structure", "bridged:tower"),
            row("cutmod:camp", "structure", "unbridged:camp"),
            row("cutmod:frame", "portal", "minecraft:crying_obsidian"),
            row("cutmod:sapling", "portal", "minecraft:lilac"),
            row("cutmod:world", "type", "adventure:the_aviary"),
            row("dungeons_arise:coliseum", "structure", "bridged:keep")));

    private static IdMapping.Row row(String id, String kind, String replacement) {
        return new IdMapping.Row(id, kind, "cut", replacement, "");
    }

    private static IdMappingApplier.Result apply(String json) {
        return new IdMappingApplier(MAPPING, REGISTER, TARGETS).apply("the_test.json", JsonParser.parseString(json).getAsJsonObject());
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void multiNoiseBiomeIsReplacedAndRepeatsWithDifferentClimatesStay() {
        IdMappingApplier.Result r = apply("""
                {"generator": {"type": "noise", "preset": "overworld"}, "biomes": [
                  "minecraft:plains",
                  {"id": "cutmod:meadow", "climate": {"weirdness": [-1.0, 0.0]}},
                  {"id": "cutmod:meadow", "climate": {"weirdness": [0.0, 1.0]}},
                  "cutmod:field"]}""");
        assertEquals(json("""
                {"generator": {"type": "noise", "preset": "overworld"}, "biomes": [
                  "minecraft:plains",
                  {"id": "adventure:prairie", "climate": {"weirdness": [-1.0, 0.0]}},
                  {"id": "adventure:prairie", "climate": {"weirdness": [0.0, 1.0]}}]}"""), r.json());
        assertEquals(3, r.replaced().size());
        assertEquals("biomes[1].id", r.replaced().getFirst().where());
        assertEquals(1, r.removed().size());
        assertEquals("biomes[3]", r.removed().getFirst().where());
        assertTrue(r.removed().getFirst().reason().contains("`minecraft:plains` at biomes[0]"));
        assertEquals(3, r.biomesBefore());
        assertEquals(2, r.biomesAfter());
    }

    @Test
    void checkerboardListIsASetOfIds() {
        IdMappingApplier.Result r = apply("""
                {"generator": {"type": "checkerboard"}, "biomes": [
                  "cutmod:dunes", "minecraft:plains", "minecraft:desert",
                  {"id": "cutmod:field", "climate": {"weirdness": 0.5}}]}""");
        assertEquals(json("""
                {"generator": {"type": "checkerboard"}, "biomes": ["minecraft:desert", "minecraft:plains"]}"""), r.json());
        assertEquals(2, r.removed().size());
    }

    @Test
    void zoneBiomesAndSpawnFilterAreRewritten() {
        IdMappingApplier.Result r = apply("""
                {"generator": {"type": "noise"}, "biomes": ["minecraft:plains"],
                 "zones": [{"shape": "circle", "centre": [0, 0], "radius": 64, "biomes": ["cutmod:meadow", "adventure:prairie"]}],
                 "roll": {"spawnFilter": ["minecraft:plains", "cutmod:field", "cutmod:meadow"]}}""");
        assertEquals(json("""
                {"generator": {"type": "noise"}, "biomes": ["minecraft:plains"],
                 "zones": [{"shape": "circle", "centre": [0, 0], "radius": 64, "biomes": ["adventure:prairie"]}],
                 "roll": {"spawnFilter": ["minecraft:plains", "adventure:prairie"]}}"""), r.json());
        assertEquals("zones[0].biomes[0]", r.replaced().getFirst().where());
        assertEquals(2, r.removed().size());
        assertEquals(3, r.biomesBefore());
        assertEquals(2, r.biomesAfter());
    }

    @Test
    void structuresInForceKeysAndSetsAreRewritten() {
        IdMappingApplier.Result r = apply("""
                {"structures": {
                  "force": [{"structure": "cutmod:keep", "x": 0, "z": 0}, {"structure": "cutmod:castle", "x": 99, "z": 0}],
                  "terrainAdaptation": {"cutmod:keep": "bury", "cutmod:castle": "beard_thin", "#minecraft:village": "none"},
                  "rarity": {"cutmod:tower": "rare", "cutmod:keep": "common"},
                  "exclude": ["cutmod:tower"]}}""");
        assertEquals(json("""
                {"structures": {
                  "force": [{"structure": "bridged:keep", "x": 0, "z": 0}, {"structure": "bridged:keep", "x": 99, "z": 0}],
                  "terrainAdaptation": {"bridged:keep": "bury", "#minecraft:village": "none"},
                  "rarity": {"bridged:tower": "rare", "cutmod:keep": "common"},
                  "exclude": ["bridged:tower"]}}"""), r.json());
        assertEquals(1, r.removed().size());
        assertTrue(r.removed().getFirst().where().startsWith("structures.terrainAdaptation"));
        assertEquals(1, r.notApplied().size());
        IdMappingApplier.NotApplied not = r.notApplied().getFirst();
        assertEquals("cutmod:keep", not.id());
        assertTrue(not.reason().contains("structure sets"), not.reason());
    }

    @Test
    void portalBlocksItemsAndCloneTypesAreRewritten() {
        IdMappingApplier.Result r = apply("""
                {"generator": {"type": "clone", "of": "cutmod:world"},
                 "portals": [{"frameBlock": "cutmod:frame", "igniterItem": "cutmod:sapling"},
                             {"frameBlock": ["minecraft:obsidian", "cutmod:frame"]}]}""");
        assertEquals(json("""
                {"generator": {"type": "clone", "of": "adventure:the_aviary"},
                 "portals": [{"frameBlock": "minecraft:crying_obsidian", "igniterItem": "minecraft:lilac"},
                             {"frameBlock": ["minecraft:obsidian", "minecraft:crying_obsidian"]}]}"""), r.json());
        assertEquals(4, r.replaced().size());
    }

    @Test
    void wantsAndShunsNamesResolveThroughTheAliasTable() {
        IdMappingApplier.Result r = apply("""
                {"roll": {"wants": {"coliseum": {"min": 0, "max": 256}, "village": {"min": 0, "max": 99}},
                          "shuns": ["coliseum", "monument"]}}""");
        assertEquals(json("""
                {"roll": {"wants": {"bridged:keep": {"min": 0, "max": 256}, "village": {"min": 0, "max": 99}},
                          "shuns": ["bridged:keep", "monument"]}}"""), r.json());
        assertTrue(r.replaced().getFirst().where().contains("alias `coliseum`"));
    }

    @Test
    void anEmptyReplacementRemovesTheEntryOrKeyButNotAField() {
        IdMappingApplier.Result r = apply("""
                {"generator": {"type": "noise"},
                 "biomes": ["cutmod:gone", {"id": "cutmod:gone", "climate": {"weirdness": 0.1}}, "minecraft:plains"],
                 "materials": {"biomes": {"cutmod:gone": {"top": "minecraft:sand"}}},
                 "flat": {"biome": "cutmod:gone"}}""");
        assertEquals(json("""
                {"generator": {"type": "noise"}, "biomes": ["minecraft:plains"],
                 "materials": {"biomes": {}},
                 "flat": {"biome": "cutmod:gone"}}"""), r.json());
        assertEquals(List.of("biomes[0]", "biomes[1].id", "materials.biomes.cutmod:gone (key)"),
                r.removed().stream().map(IdMappingApplier.Removed::where).toList());
        assertEquals(1, r.notApplied().size());
        assertEquals("flat.biome", r.notApplied().getFirst().where());
    }

    @Test
    void aTargetTheCompilerDoesNotKnowIsNotApplied() {
        IdMappingApplier.Result r = apply("""
                {"biomes": ["cutmod:future"], "structures": {"force": [{"structure": "cutmod:camp", "x": 0, "z": 0}]}}""");
        assertFalse(r.changed());
        assertEquals(List.of("cutmod:future", "cutmod:camp"), r.notApplied().stream().map(IdMappingApplier.NotApplied::id).toList());
        assertTrue(r.notApplied().get(0).reason().contains("library biome"));
        assertTrue(r.notApplied().get(1).reason().contains("bridged structure"));
    }

    @Test
    void anIdFromAFlaggedNamespaceWithNoRowIsMissing() {
        IdMappingApplier.Result r = apply("""
                {"biomes": ["cutmod:unlisted", "keptmod:fine", "minecraft:plains", "unknownmod:x"],
                 "roll": {"wants": {"murkydungeon": {"min": 0, "max": 9}}},
                 "description": "Not an id: just prose."}""");
        assertFalse(r.changed());
        assertEquals(List.of("cutmod:unlisted"), r.missing().stream().map(IdMappingApplier.Missing::id).toList());
    }

    @Test
    void applyingTwiceChangesNothingTheSecondTime() {
        String source = """
                {"generator": {"type": "noise"},
                 "biomes": ["cutmod:field", "minecraft:plains", {"id": "cutmod:meadow", "climate": {"weirdness": 0.2}}, "cutmod:future"],
                 "structures": {"terrainAdaptation": {"cutmod:keep": "bury", "cutmod:castle": "none"}, "rarity": {"cutmod:keep": "rare"}},
                 "roll": {"wants": {"coliseum": {"min": 0, "max": 9}}}}""";
        IdMappingApplier.Result first = apply(source);
        assertTrue(first.changed());
        IdMappingApplier.Result second = new IdMappingApplier(MAPPING, REGISTER, TARGETS).apply("the_test.json", first.json());
        assertFalse(second.changed());
        assertEquals(first.json(), second.json());
        assertTrue(second.replaced().isEmpty() && second.removed().isEmpty());
        assertEquals(first.notApplied().size(), second.notApplied().size());
    }

    @Test
    void keyOrderAndNumberTextAreKept() {
        String source = """
                {
                  "schemaVersion": 2,
                  "seed": -7779457580041241280,
                  "biomes": [
                    {
                      "id": "cutmod:meadow",
                      "climate": {
                        "weirdness": [0.4804, 2.0]
                      }
                    }
                  ],
                  "difficulty": {
                    "mobMultiplier": 1.0
                  }
                }
                """;
        String written = JsonWriter.write(apply(source).json());
        assertEquals(source.replace("cutmod:meadow", "adventure:prairie"), written);
    }

    @Test
    void theTableRefusesChainsDuplicatesAndUnknownKinds(@TempDir Path dir) throws IOException {
        assertThrows(ConversionException.class, () -> new IdMapping(List.of(row("a:x", "biome", "a:y"), row("a:y", "biome", "minecraft:plains"))));
        assertThrows(ConversionException.class, () -> new IdMapping(List.of(row("a:x", "biome", "minecraft:plains"), row("a:x", "biome", "minecraft:desert"))));
        assertThrows(ConversionException.class, () -> new IdMapping(List.of(row("a:x", "dimension", "minecraft:plains"))));
        Path csv = dir.resolve(IdMapping.FILE);
        Files.writeString(csv, """
                id,kind,namespace,decision,files,uses,replacement,reason,changed
                cutmod:a,biome,cutmod,cut,1,1,minecraft:plains,"Open grass, flat",no
                cutmod:b,structure,cutmod,cut,1,1,,Nothing fits,no
                """);
        IdMapping read = IdMapping.read(csv);
        assertEquals(2, read.size());
        assertEquals("Open grass, flat", read.row("cutmod:a").orElseThrow().reason());
        assertTrue(read.row("cutmod:b").orElseThrow().removes());
    }
}
