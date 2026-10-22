package com.customdimensions.compiler.convert;

import com.customdimensions.compiler.convert.NamespaceRegister.Owner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionConverterTest {

    private static final NamespaceRegister REGISTER = new NamespaceRegister(Map.of(
            "terralith", List.of(new Owner("terralith", "rebuilt by us")),
            "paradise_lost", List.of(new Owner("paradise-lost", "rebuilt by us")),
            "structory", List.of(new Owner("structory", "cut")),
            "create", List.of(new Owner("create", "kept"), new Owner("create-addon", "cut")),
            "minecraft", List.of(new Owner("vanilla", "cut")),
            "adventure", List.of(new Owner("custom-dimensions", "rebuilt by us"))));

    private final DefinitionConverter converter = new DefinitionConverter(REGISTER);

    private static JsonObject json(String text) {
        return JsonParser.parseString(text.replace('\'', '"')).getAsJsonObject();
    }

    private DefinitionConverter.Converted convert(String slug, String v1) {
        return converter.convert(slug, json(v1));
    }

    private static List<String> droppedFields(FileReport report) {
        return report.dropped().stream().map(FileReport.Dropped::field).toList();
    }

    private static List<String> categories(FileReport report) {
        return report.decisions().stream().map(FileReport.Decision::category).toList();
    }

    @ParameterizedTest
    @CsvSource({
            "overworld, noise, overworld",
            "multi_biome, noise, overworld",
            "large_biomes, noise, large_biomes",
            "amplified, noise, amplified",
            "cave, noise, caves",
            "sky_islands, noise, floating_islands",
            "nether, noise, nether",
            "end, noise, end",
            "nether_islands, noise, end",
            "void, void, ",
            "superflat, flat, ",
            "checkerboard, checkerboard, "})
    void typeTableRows(String v1Type, String type, String preset) {
        JsonObject gen = convert("the_x", "{'type': '" + v1Type + "'}").json().getAsJsonObject("generator");
        assertEquals(type, gen.get("type").getAsString());
        if (preset == null) {
            assertFalse(gen.has("preset"));
        } else {
            assertEquals(preset, gen.get("preset").getAsString());
        }
    }

    @Test
    void netherIslandsKeepsItsNetherBiomes() {
        JsonObject out = convert("the_x", "{'type': 'nether_islands', 'biomes': ['minecraft:crimson_forest']}").json();
        assertEquals("end", out.getAsJsonObject("generator").get("preset").getAsString());
        assertEquals("minecraft:crimson_forest", out.getAsJsonArray("biomes").get(0).getAsString());
    }

    @ParameterizedTest
    @CsvSource({"overworld, overworld", "the_nether, nether", "the_end, end", "paradise_lost, floating_islands"})
    void baseWorldsTakeTheirOwnPreset(String slug, String preset) {
        DefinitionConverter.Converted c = convert(slug, "{'seed': 1}");
        assertEquals("noise", c.json().getAsJsonObject("generator").get("type").getAsString());
        assertEquals(preset, c.json().getAsJsonObject("generator").get("preset").getAsString());
    }

    @Test
    void aMissingTypeOutsideTheBaseWorldsIsRefused() {
        assertThrows(ConversionException.class, () -> convert("the_x", "{'seed': 1}"));
    }

    @Test
    void checkerboardScaleBecomesCellScale() {
        JsonObject gen = convert("the_x", "{'type': 'checkerboard', 'checkerboardScale': 3}")
                .json().getAsJsonObject("generator");
        assertEquals(3, gen.get("cellScale").getAsInt());
    }

    @Test
    void superflatLayersAndBiomeMoveIntoTheGenerator() {
        DefinitionConverter.Converted c = convert("the_x", "{'type': 'superflat', 'flatBiome': 'minecraft:desert',"
                + " 'layers': [{'block': 'minecraft:bedrock', 'height': 1}]}");
        JsonObject gen = c.json().getAsJsonObject("generator");
        assertEquals("flat", gen.get("type").getAsString());
        assertEquals("minecraft:desert", gen.get("biome").getAsString());
        assertEquals(1, gen.getAsJsonArray("layers").size());
        assertFalse(c.json().has("layers"));
        assertEquals(List.of("type", "biome", "layers"), List.copyOf(gen.keySet()));
    }

    @Test
    void singleBiomeMovesItsOneBiomeIntoTheGenerator() {
        JsonObject out = convert("the_x", "{'type': 'single_biome', 'biomes': ['minecraft:desert']}").json();
        assertEquals("minecraft:desert", out.getAsJsonObject("generator").get("biome").getAsString());
        assertFalse(out.has("biomes"));
        assertThrows(ConversionException.class, () -> convert("the_x",
                "{'type': 'single_biome', 'biomes': ['minecraft:desert', 'minecraft:plains']}"));
    }

    @Test
    void generatorOnlyFieldsOnTheWrongTypeAreDropped() {
        FileReport report = convert("the_x", "{'type': 'multi_biome', 'checkerboardScale': 2, 'flatBiome': 'minecraft:plains'}")
                .report();
        assertEquals(List.of("checkerboardScale", "flatBiome"), droppedFields(report));
    }

    @Test
    void aCloneNamesWhatItCopiesAndAFlaggedOneIsAW8Decision() {
        DefinitionConverter.Converted c = convert("the_x", "{'type': 'paradise_lost:paradise_lost'}");
        JsonObject gen = c.json().getAsJsonObject("generator");
        assertEquals("clone", gen.get("type").getAsString());
        assertEquals("paradise_lost:paradise_lost", gen.get("of").getAsString());
        assertEquals(List.of("clone of a dimension from a flagged mod"), categories(c.report()));

        assertTrue(convert("the_x", "{'type': 'create:somewhere'}").report().decisions().isEmpty());
        assertThrows(ConversionException.class, () -> convert("the_x", "{'type': 'not_a_type'}"));
    }

    @Test
    void wideAndCompressedBecomeANullTerrainBaseForW8() {
        for (String noise : List.of("adventure:wide", "adventure:compressed")) {
            DefinitionConverter.Converted c = convert("the_x", "{'type': 'multi_biome', 'noiseSettings': '" + noise + "'}");
            assertTrue(c.json().getAsJsonObject("terrain").get("base").isJsonNull());
            assertEquals(List.of("terrain.base: null where v1 named a preset"), categories(c.report()));
        }
        assertFalse(convert("the_x", "{'type': 'multi_biome'}").json().has("terrain"));
    }

    @Test
    void voidNoiseSettingsBecomesTheVoidGenerator() {
        DefinitionConverter.Converted c = convert("the_x", "{'type': 'void', 'noiseSettings': 'adventure:void'}");
        assertEquals("void", c.json().getAsJsonObject("generator").get("type").getAsString());
        assertFalse(c.json().has("terrain"));
        assertTrue(c.report().decisions().isEmpty());
        DefinitionConverter.Converted retyped = convert("the_x", "{'type': 'end', 'noiseSettings': 'adventure:void'}");
        assertEquals("void", retyped.json().getAsJsonObject("generator").get("type").getAsString());
        assertEquals(1, retyped.report().decisions().size());
    }

    @Test
    void anUnknownNoiseSettingsIsRefused() {
        assertThrows(ConversionException.class,
                () -> convert("the_x", "{'type': 'multi_biome', 'noiseSettings': 'minecraft:overworld'}"));
    }

    @Test
    void biomeParametersBecomeClimate() {
        JsonObject out = convert("the_x", "{'type': 'multi_biome', 'biomes': ['minecraft:plains',"
                + " {'id': 'minecraft:desert', 'parameters': {'temperature': [0.5, 1.0]}}]}").json();
        assertEquals("minecraft:plains", out.getAsJsonArray("biomes").get(0).getAsString());
        JsonObject entry = out.getAsJsonArray("biomes").get(1).getAsJsonObject();
        assertEquals(List.of("id", "climate"), List.copyOf(entry.keySet()));
        assertEquals(1.0, entry.getAsJsonObject("climate").getAsJsonArray("temperature").get(1).getAsDouble());
    }

    @Test
    void environmentSplitsIntoDimensionTypeAtmosphereAndRules() {
        JsonObject out = convert("the_x", "{'type': 'nether', 'environment': {"
                + "'fogColor': '#000000', 'skyColor': '#111111', 'ambientLight': 0.2, 'fixedTime': 18000,"
                + " 'effects': 'minecraft:the_nether', 'hasCeiling': true, 'hasSkylight': false, 'minY': 0,"
                + " 'height': 256, 'logicalHeight': 128, 'infiniburn': '#minecraft:infiniburn_nether',"
                + " 'monsterSpawnLightLevel': 7, 'monsterSpawnBlockLightLimit': 15, 'bedWorks': false,"
                + " 'respawnAnchorWorks': true, 'piglinSafe': true, 'ultraWarm': true, 'natural': false,"
                + " 'hasRaids': false}}").json();
        assertFalse(out.has("environment"));
        assertEquals(List.of("minY", "height", "logicalHeight", "hasSkylight", "hasCeiling", "infiniburn",
                "monsterSpawnLightLevel", "monsterSpawnBlockLightLimit"), List.copyOf(out.getAsJsonObject("dimensionType").keySet()));
        assertEquals(List.of("skyColor", "fogColor", "ambientLight", "fixedTime", "effects"),
                List.copyOf(out.getAsJsonObject("atmosphere").keySet()));
        JsonObject rules = out.getAsJsonObject("rules");
        assertEquals(List.of("bedsWork", "respawnAnchorsWork", "piglinsZombify", "ultraWarm", "natural", "raids"),
                List.copyOf(rules.keySet()));
        assertFalse(rules.get("piglinsZombify").getAsBoolean(), "piglinsZombify is not piglinSafe");
        assertFalse(rules.get("bedsWork").getAsBoolean());
        assertTrue(rules.get("respawnAnchorsWork").getAsBoolean());
    }

    @Test
    void piglinSafeFalseMeansPiglinsZombify() {
        JsonObject rules = convert("the_x", "{'type': 'nether', 'environment': {'piglinSafe': false}}")
                .json().getAsJsonObject("rules");
        assertTrue(rules.get("piglinsZombify").getAsBoolean());
    }

    @Test
    void emptyLayersAreOmittedAndUnknownEnvironmentFieldsRefused() {
        JsonObject out = convert("the_x", "{'type': 'nether', 'environment': {'fogColor': '#000000'}}").json();
        assertTrue(out.has("atmosphere"));
        assertFalse(out.has("dimensionType"));
        assertFalse(out.has("rules"));
        assertThrows(ConversionException.class,
                () -> convert("the_x", "{'type': 'nether', 'environment': {'scale': 8}}"));
    }

    @Test
    void settingsOverridesSplitIntoLiquidsMaterialsAndGenerator() {
        JsonObject out = convert("the_x", "{'type': 'nether_islands', 'settingsOverrides': {'defaultBlock':"
                + " 'minecraft:netherrack', 'defaultFluid': 'minecraft:lava', 'seaLevel': -60, 'endIsland': false}}").json();
        assertEquals(List.of("seaLevel", "defaultFluid"), List.copyOf(out.getAsJsonObject("liquids").keySet()));
        assertEquals(-60, out.getAsJsonObject("liquids").get("seaLevel").getAsInt());
        assertEquals("minecraft:netherrack", out.getAsJsonObject("materials").get("defaultBlock").getAsString());
        assertFalse(out.getAsJsonObject("generator").get("endIsland").getAsBoolean());
        assertFalse(out.has("settingsOverrides"));
    }

    @Test
    void endIslandOffTheEndPresetIsDropped() {
        DefinitionConverter.Converted c = convert("the_x", "{'type': 'sky_islands', 'settingsOverrides': {'endIsland': false}}");
        assertFalse(c.json().getAsJsonObject("generator").has("endIsland"));
        assertEquals(List.of("settingsOverrides.endIsland"), droppedFields(c.report()));
        assertFalse(c.json().has("liquids"));
    }

    @Test
    void disableMobGenerationIsDroppedAndLeftForW8() {
        DefinitionConverter.Converted c = convert("the_x",
                "{'type': 'multi_biome', 'settingsOverrides': {'disableMobGeneration': true}}");
        assertEquals(List.of("settingsOverrides.disableMobGeneration"), droppedFields(c.report()));
        assertEquals(1, c.report().decisions().size());
        assertThrows(ConversionException.class,
                () -> convert("the_x", "{'type': 'multi_biome', 'settingsOverrides': {'aquifers': true}}"));
    }

    @Test
    void biomePatchesBecomeZonesThatSetBiomes() {
        JsonObject out = convert("the_x", "{'type': 'multi_biome', 'biomePatches': ["
                + "{'biome': 'minecraft:deep_dark', 'x': 1180, 'z': -860, 'radius': 200, 'blend': 40},"
                + "{'biome': 'minecraft:desert', 'x': 0, 'z': 0, 'radius': 400, 'blend': 0, 'shape': 'square'}]}").json();
        assertFalse(out.has("biomePatches"));
        JsonObject circle = out.getAsJsonArray("zones").get(0).getAsJsonObject();
        assertEquals(List.of("shape", "centre", "radius", "blend", "biomes"), List.copyOf(circle.keySet()));
        assertEquals("circle", circle.get("shape").getAsString());
        assertEquals(1180, circle.getAsJsonArray("centre").get(0).getAsInt());
        assertEquals(-860, circle.getAsJsonArray("centre").get(1).getAsInt());
        assertEquals("minecraft:deep_dark", circle.getAsJsonArray("biomes").get(0).getAsString());
        assertEquals("square", out.getAsJsonArray("zones").get(1).getAsJsonObject().get("shape").getAsString());
    }

    @Test
    void aPortalObjectBecomesAOneEntryArrayAndAnArrayStays() {
        JsonObject one = convert("the_x", "{'type': 'void', 'portal': {'frameBlock': 'minecraft:stone'}}").json();
        assertEquals(1, one.getAsJsonArray("portals").size());
        assertFalse(one.has("portal"));
        JsonObject two = convert("the_x", "{'type': 'void', 'portal': [{'frameBlock': 'minecraft:stone'},"
                + " {'frameBlock': 'minecraft:dirt'}]}").json();
        assertEquals("minecraft:dirt",
                two.getAsJsonArray("portals").get(1).getAsJsonObject().get("frameBlock").getAsString());
    }

    @Test
    void immersiveBecomesPreview() {
        JsonObject on = portal("true");
        assertFalse(on.has("preview"));
        assertFalse(on.has("immersive"));
        assertFalse(portal(null).has("preview"));
        assertFalse(portal("false").get("preview").getAsBoolean());
        JsonObject tuned = portal("{'particleDensity': 0}");
        assertEquals(0, tuned.getAsJsonObject("preview").get("particleDensity").getAsInt());
        assertEquals(List.of("frameBlock", "preview", "color"), List.copyOf(tuned.keySet()), "preview takes immersive's place");
    }

    private JsonObject portal(String immersive) {
        String field = immersive == null ? "" : "'immersive': " + immersive + ", ";
        return convert("the_x", "{'type': 'void', 'portal': {'frameBlock': 'minecraft:stone', " + field
                + "'color': 'FFFFFF'}}").json().getAsJsonArray("portals").get(0).getAsJsonObject();
    }

    @Test
    void seedRollBecomesRollWithoutItsInertFields() {
        DefinitionConverter.Converted c = convert("the_x", "{'type': 'multi_biome', 'seedRoll': {'mood': 'hard',"
                + " 'family': 'overworld', 'spawnRadius': 64, 'locateCap': 4000, 'allowEndgameNearSpawn': true,"
                + " 'spawnFilter': ['minecraft:plains'], 'shuns': ['village'], 'skip': false}}");
        JsonObject roll = c.json().getAsJsonObject("roll");
        assertFalse(c.json().has("seedRoll"));
        assertEquals(List.of("mood", "spawnFilter", "shuns", "skip"), List.copyOf(roll.keySet()));
        assertTrue(roll.get("shuns").isJsonArray());
        assertEquals(List.of("seedRoll.family", "seedRoll.spawnRadius", "seedRoll.locateCap",
                "seedRoll.allowEndgameNearSpawn"), droppedFields(c.report()));
    }

    @Test
    void legacyTopLevelFieldsAreDroppedAndReported() {
        DefinitionConverter.Converted c = convert("the_x", "{'type': 'void', 'dimensionId': 'adventure:x',"
                + " 'hostileSpawning': false, 'scale': 8, 'inputHash': 'abc'}");
        for (String key : List.of("dimensionId", "hostileSpawning", "scale", "inputHash")) {
            assertFalse(c.json().has(key), key);
        }
        assertEquals(List.of("dimensionId", "hostileSpawning", "scale", "inputHash"), droppedFields(c.report()));
    }

    @Test
    void structuresCarryWholeWithDensityFirstAndShunsWithoutDistances() {
        DefinitionConverter.Converted c = convert("the_x", "{'type': 'multi_biome', 'structures': {"
                + "'force': [], 'shuns': {'village': {}, 'monument': {'minDistance': 500}},"
                + " 'endgame': {'allow': true}}, 'structureDensity': 'dense'}");
        JsonObject structures = c.json().getAsJsonObject("structures");
        assertEquals(List.of("density", "force", "shuns"), List.copyOf(structures.keySet()));
        assertEquals("dense", structures.get("density").getAsString());
        assertTrue(structures.getAsJsonObject("shuns").getAsJsonObject("monument").isEmpty());
        assertFalse(c.json().has("structureDensity"));
        assertEquals(List.of("structures.shuns distance", "structures.endgame"), droppedFields(c.report()));
    }

    @Test
    void paradiseLostBecomesTheAviary() {
        DefinitionConverter.Converted c = convert("paradise_lost",
                "{'seedRoll': {'spawnFilter': ['paradise_lost:highlands']}}");
        assertEquals("the_aviary", c.slug());
        assertEquals("the_aviary.json", c.report().target());
        assertEquals("paradise_lost.json", c.report().source());
        assertEquals("floating_islands", c.json().getAsJsonObject("generator").get("preset").getAsString());
        assertEquals("paradise_lost:highlands",
                c.json().getAsJsonObject("roll").getAsJsonArray("spawnFilter").get(0).getAsString(), "biome ids are kept");
        assertEquals(List.of("paradise_lost:highlands"), c.report().flagged().stream().map(FileReport.Flagged::id).toList());
        assertEquals(List.of("base world paradise_lost converts to the_aviary"), categories(c.report()));
    }

    @Test
    void referencesToTheParadiseLostDimensionPointAtTheAviary() {
        DefinitionConverter.Converted c = convert("the_x", "{'type': 'void',"
                + " 'portal': {'anchor': {'pos': 'spawn', 'exit': {'dimension': 'adventure:paradise_lost'}}},"
                + " 'exits': {'void': {'action': 'teleport', 'target': {'dimension': 'adventure:paradise_lost'}}},"
                + " 'structures': {'list': ['adventure:paradise_lost']}}");
        assertEquals("adventure:the_aviary", c.json().getAsJsonArray("portals").get(0).getAsJsonObject()
                .getAsJsonObject("anchor").getAsJsonObject("exit").get("dimension").getAsString());
        assertEquals("adventure:the_aviary", c.json().getAsJsonObject("exits").getAsJsonObject("void")
                .getAsJsonObject("target").get("dimension").getAsString());
        assertEquals(List.of("structures.list[0]", "portals[0].anchor.exit.dimension", "exits.void.target.dimension"),
                c.report().rewrites().stream().map(FileReport.Rewrite::where).toList());
    }

    @Test
    void flaggingFollowsTheRegister() {
        FileReport report = convert("the_x", "{'type': 'multi_biome', 'biomes': ['terralith:a', 'terralith:a',"
                + " 'structory:b', 'create:c', 'minecraft:d', 'adventure:e', 'unknown:f', 'terralith:bad id'],"
                + " 'structures': {'rarity': {'structory:towers': 'common'}, 'list': ['#terralith:tag']}}").report();
        assertEquals(Map.of("terralith", 3, "structory", 2), report.flaggedCountByNamespace());
        FileReport.Flagged a = report.flagged().stream().filter(f -> f.id().equals("terralith:a")).findFirst().orElseThrow();
        assertEquals(List.of("biomes[0]", "biomes[1]"), a.where());
        assertTrue(report.flagged().stream().anyMatch(f -> f.where().contains("structures.rarity.structory:towers (key)")));
        assertTrue(report.flagged().stream().anyMatch(f -> f.id().equals("#terralith:tag")));
    }

    @Test
    void namespaceWithAnyKeptOwnerIsNotFlagged() {
        assertFalse(REGISTER.isFlagged("create"));
        assertTrue(REGISTER.isFlagged("structory"));
        assertFalse(REGISTER.isFlagged("minecraft"), "minecraft is never flagged");
        assertFalse(REGISTER.isFlagged("adventure"), "adventure is never flagged");
        assertFalse(REGISTER.isFlagged("absent"));
        assertEquals("kept, cut", REGISTER.decisions("create"));
    }

    @Test
    void outputFollowsTheSpecFieldOrder() {
        JsonObject out = convert("the_x", "{'seedRoll': {'mood': 'hard'}, 'portal': {'frameBlock': 'minecraft:stone'},"
                + " 'difficulty': {}, 'borders': {'player': 1, 'generation': 1}, 'environment': {'natural': true},"
                + " 'biomes': ['minecraft:plains'], 'type': 'multi_biome', 'spawn': [0, 64, 0], 'seed': 'env',"
                + " 'description': 'd', 'settingsOverrides': {'seaLevel': 1, 'defaultBlock': 'minecraft:stone'},"
                + " 'structureDensity': 'dense', 'exits': {}, 'exitPortal': {}, 'exitShrines': {}}").json();
        assertEquals(List.of("schemaVersion", "description", "seed", "spawn", "generator", "biomes", "liquids",
                "materials", "rules", "borders", "difficulty", "structures", "portals", "exitPortal", "exits",
                "exitShrines", "roll"), List.copyOf(out.keySet()));
        assertEquals(2, out.get("schemaVersion").getAsInt());
    }

    @Test
    void anUnknownV1FieldIsRefused() {
        ConversionException e = assertThrows(ConversionException.class,
                () -> convert("the_x", "{'type': 'void', 'weather': 'rain'}"));
        assertTrue(e.getMessage().contains("weather"));
    }

    @Test
    void settingsGainSchemaVersionAndMoveStructureDensity() {
        DefinitionConverter.Converted c = converter.convertSettings(json("{'namespace': 'adventure',"
                + " 'defaults': {'frameBlock': 'minecraft:stone', 'structureDensity': 'sparse', 'borders': {'player': 1}},"
                + " 'frames': {'nether': 'minecraft:obsidian'}}"));
        assertEquals("settings.json", c.fileName());
        assertEquals(List.of("schemaVersion", "namespace", "defaults", "frames"), List.copyOf(c.json().keySet()));
        JsonObject defaults = c.json().getAsJsonObject("defaults");
        assertEquals(List.of("frameBlock", "structures", "borders"), List.copyOf(defaults.keySet()));
        assertEquals("sparse", defaults.getAsJsonObject("structures").get("density").getAsString());
        assertNull(defaults.get("structureDensity"));
    }

    @Test
    void conversionIsDeterministic() {
        String v1 = "{'type': 'multi_biome', 'seed': 7826077224469180302, 'difficulty': {'mobMultiplier': 1.0},"
                + " 'description': 'A — dash', 'spawn': [0, 64, 0]}";
        String first = JsonWriter.write(convert("the_x", v1).json());
        String second = JsonWriter.write(convert("the_x", v1).json());
        assertEquals(first, second);
        assertTrue(first.endsWith("}\n"));
        assertTrue(first.contains("\"seed\": 7826077224469180302,"), "long seeds keep every digit");
        assertTrue(first.contains("\"mobMultiplier\": 1.0"), "numbers keep their text");
        assertTrue(first.contains("\"spawn\": [0, 64, 0],"), "number arrays stay on one line");
        assertTrue(first.contains("A — dash"));
        assertTrue(first.contains("\n  \"generator\": {\n    \"type\": \"noise\",\n"), "two-space indent");
    }
}
