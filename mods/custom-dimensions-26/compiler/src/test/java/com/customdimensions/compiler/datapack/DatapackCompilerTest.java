package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.Generator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One test per mapping from a definition field to the datapack. */
class DatapackCompilerTest {

    private static final String OVERWORLD = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}";

    private static JsonObject file(DatapackCompiler.Output out, String path) {
        String text = out.files().get(path);
        if (text == null) {
            throw new AssertionError(path + " not emitted; emitted " + out.files().keySet());
        }
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject type(DatapackCompiler.Output out, String slug) {
        return file(out, "data/adventure/dimension_type/" + slug + ".json");
    }

    private static JsonObject stem(DatapackCompiler.Output out, String slug) {
        return file(out, "data/customdimensions/lazy_dimension/" + slug + ".json");
    }

    private static JsonObject attributes(JsonObject type) {
        return type.getAsJsonObject("attributes");
    }

    private static JsonObject biomeSource(DatapackCompiler.Output out, String slug) {
        return stem(out, slug).getAsJsonObject("generator").getAsJsonObject("biome_source");
    }

    private static Set<String> biomesOf(JsonObject source) {
        Set<String> out = new java.util.TreeSet<>();
        for (JsonElement e : source.getAsJsonArray("biomes")) {
            out.add(e.isJsonObject() ? e.getAsJsonObject().get("biome").getAsString() : e.getAsString());
        }
        return out;
    }

    // ---- dimension type ------------------------------------------------------------------------

    @Test
    void baseDimensionTypeFollowsTheGenerator() {
        assertEquals("overworld", DatapackCompiler.baseDimensionType(Generator.noise("overworld")));
        assertEquals("overworld", DatapackCompiler.baseDimensionType(Generator.noise("floating_islands")));
        assertEquals("overworld_caves", DatapackCompiler.baseDimensionType(Generator.noise("caves")));
        assertEquals("the_nether", DatapackCompiler.baseDimensionType(Generator.noise("nether")));
        assertEquals("the_end", DatapackCompiler.baseDimensionType(Generator.noise("end")));
        for (String type : List.of("flat", "void", "checkerboard", "single_biome")) {
            assertEquals("overworld", DatapackCompiler.baseDimensionType(new Generator(type, null, null, null, null, null, null)));
        }
    }

    @Test
    void anUntouchedDefinitionCopiesTheVanillaType() {
        JsonObject type = type(Fixtures.compile("plain", OVERWORLD), "plain");
        assertEquals(Fixtures.vanilla().dimensionType("overworld"), type);
    }

    @Test
    void dimensionTypeFieldsBecomeSnakeCase() {
        JsonObject type = type(Fixtures.compile("t", OVERWORLD + ", \"dimensionType\": {\"minY\": -32, \"height\": 256, "
                + "\"logicalHeight\": 200, \"hasSkylight\": false, \"hasCeiling\": true, \"infiniburn\": "
                + "\"#minecraft:infiniburn_nether\", \"monsterSpawnLightLevel\": 15, \"monsterSpawnBlockLightLimit\": 4}"), "t");
        assertEquals(-32, type.get("min_y").getAsInt());
        assertEquals(256, type.get("height").getAsInt());
        assertEquals(200, type.get("logical_height").getAsInt());
        assertFalse(type.get("has_skylight").getAsBoolean());
        assertTrue(type.get("has_ceiling").getAsBoolean());
        assertEquals("#minecraft:infiniburn_nether", type.get("infiniburn").getAsString());
        assertEquals(15, type.get("monster_spawn_light_level").getAsInt());
        assertEquals(4, type.get("monster_spawn_block_light_limit").getAsInt());
    }

    @Test
    void monsterSpawnLightLevelKeepsAnIntProvider() {
        JsonObject type = type(Fixtures.compile("t", OVERWORLD + ", \"dimensionType\": {\"monsterSpawnLightLevel\": "
                + "{\"type\": \"minecraft:uniform\", \"min_inclusive\": 0, \"max_inclusive\": 3}}"), "t");
        assertEquals(3, type.getAsJsonObject("monster_spawn_light_level").get("max_inclusive").getAsInt());
    }

    @Test
    void atmosphereColoursAndLightBecomeAttributes() {
        JsonObject type = type(Fixtures.compile("t", OVERWORLD
                + ", \"atmosphere\": {\"skyColor\": \"#4A2C6B\", \"fogColor\": \"#2A1A3E\", \"ambientLight\": 0.3}"), "t");
        assertEquals("#4a2c6b", attributes(type).get("minecraft:visual/sky_color").getAsString());
        assertEquals("#2a1a3e", attributes(type).get("minecraft:visual/fog_color").getAsString());
        assertEquals(0.3, type.get("ambient_light").getAsDouble());
    }

    @Test
    void endEffectsBringTheEndSkyAndNoClouds() {
        JsonObject type = type(Fixtures.compile("t", OVERWORLD + ", \"atmosphere\": {\"effects\": \"minecraft:the_end\"}"), "t");
        assertEquals("end", type.get("skybox").getAsString());
        assertFalse(attributes(type).has("minecraft:visual/cloud_height"));
        assertFalse(attributes(type).has("minecraft:visual/cloud_color"));
    }

    @Test
    void netherEffectsBringNoSkyCardinalLightAndFog() {
        JsonObject type = type(Fixtures.compile("t", OVERWORLD + ", \"atmosphere\": {\"effects\": \"minecraft:the_nether\"}"), "t");
        assertEquals("none", type.get("skybox").getAsString());
        assertEquals("nether", type.get("cardinal_light").getAsString());
        assertEquals(96.0, attributes(type).get("minecraft:visual/fog_end_distance").getAsDouble());
        assertFalse(attributes(type).has("minecraft:visual/cloud_height"));
    }

    @Test
    void overworldEffectsOnANetherBaseRestoreTheOverworldSky() {
        JsonObject type = type(Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"atmosphere\": {\"effects\": \"minecraft:overworld\"}"), "t");
        assertFalse(type.has("skybox"));
        assertFalse(type.has("cardinal_light"));
        assertTrue(attributes(type).has("minecraft:visual/cloud_height"));
        assertFalse(attributes(type).has("minecraft:visual/fog_end_distance"));
    }

    @Test
    void unknownEffectsAreReported() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD + ", \"atmosphere\": {\"effects\": \"mod:sky\"}");
        assertTrue(out.report().entry("t").unsupported().stream().anyMatch(u -> u.startsWith("`atmosphere.effects`")));
    }

    @Test
    void fixedTimeFreezesTheTimelines() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD + ", \"atmosphere\": {\"fixedTime\": 18000}");
        JsonObject type = type(out, "t");
        assertTrue(type.get("has_fixed_time").getAsBoolean());
        JsonArray timelines = type.getAsJsonArray("timelines");
        List<String> ids = timelines.asList().stream().map(JsonElement::getAsString).toList();
        assertEquals(List.of("adventure:fixed_time/villager_schedule_18000", "adventure:fixed_time/day_18000",
                "adventure:fixed_time/moon_18000", "minecraft:early_game"), ids);
        JsonObject day = file(out, "data/adventure/timeline/fixed_time/day_18000.json");
        assertEquals(1, day.getAsJsonObject("tracks").getAsJsonObject("minecraft:visual/sun_angle")
                .getAsJsonArray("keyframes").size());
        assertTrue(out.report().entry("t").emitted().contains("data/adventure/timeline/fixed_time/day_18000.json"));
    }

    @Test
    void fixedTimeWrapsIntoTheDay() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD + ", \"atmosphere\": {\"fixedTime\": 42000}");
        assertTrue(out.files().containsKey("data/adventure/timeline/fixed_time/day_18000.json"));
    }

    // ---- rules ---------------------------------------------------------------------------------

    @Test
    void bedsTakeTheOverworldOrNetherRule() {
        JsonObject off = attributes(type(Fixtures.compile("t", OVERWORLD + ", \"rules\": {\"bedsWork\": false}"), "t"));
        assertEquals(attributes(Fixtures.vanilla().dimensionType("the_nether")).get("minecraft:gameplay/bed_rule"),
                off.get("minecraft:gameplay/bed_rule"));
        JsonObject on = attributes(type(Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"rules\": {\"bedsWork\": true}"), "t"));
        assertEquals(attributes(Fixtures.vanilla().dimensionType("overworld")).get("minecraft:gameplay/bed_rule"),
                on.get("minecraft:gameplay/bed_rule"));
        assertEquals(attributes(Fixtures.vanilla().dimensionType("overworld")).get("minecraft:gameplay/straw_bed_rule"),
                on.get("minecraft:gameplay/straw_bed_rule"));
    }

    @Test
    void booleanRulesSetTheirAttributes() {
        JsonObject a = attributes(type(Fixtures.compile("t", OVERWORLD + ", \"rules\": {\"respawnAnchorsWork\": true, "
                + "\"piglinsZombify\": false, \"ultraWarm\": true, \"raids\": false, \"natural\": false}"), "t"));
        assertTrue(a.get("minecraft:gameplay/respawn_anchor_works").getAsBoolean());
        assertFalse(a.get("minecraft:gameplay/piglins_zombify").getAsBoolean());
        assertTrue(a.get("minecraft:gameplay/water_evaporates").getAsBoolean());
        assertTrue(a.get("minecraft:gameplay/fast_lava").getAsBoolean());
        assertFalse(a.get("minecraft:gameplay/can_start_raid").getAsBoolean());
        assertFalse(a.get("minecraft:gameplay/nether_portal_spawns_piglin").getAsBoolean());
    }

    @Test
    void coolingANetherTypeTurnsUltraWarmOff() {
        JsonObject a = attributes(type(Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"rules\": {\"ultraWarm\": false}"), "t"));
        assertFalse(a.get("minecraft:gameplay/water_evaporates").getAsBoolean());
        assertFalse(a.get("minecraft:gameplay/fast_lava").getAsBoolean());
    }

    // ---- noise settings ------------------------------------------------------------------------

    @Test
    void withoutLiquidsTheGeneratorNamesTheVanillaSettings() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"amplified\"}");
        assertEquals("minecraft:amplified", stem(out, "t").getAsJsonObject("generator").get("settings").getAsString());
        assertTrue(out.files().keySet().stream().noneMatch(p -> p.contains("noise_settings")));
    }

    @Test
    void liquidsAndDefaultBlockWriteNoiseSettingsFromThePreset() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"caves\"}, "
                + "\"liquids\": {\"seaLevel\": 12, \"defaultFluid\": \"minecraft:lava\"}, "
                + "\"materials\": {\"defaultBlock\": \"minecraft:blackstone\"}");
        assertEquals("adventure:t", stem(out, "t").getAsJsonObject("generator").get("settings").getAsString());
        JsonObject settings = file(out, "data/adventure/worldgen/noise_settings/t.json");
        JsonObject vanilla = Fixtures.vanilla().noiseSettings("caves");
        assertEquals(12, settings.get("sea_level").getAsInt());
        assertEquals("minecraft:lava", settings.get("default_fluid").getAsString());
        assertEquals("minecraft:blackstone", settings.get("default_block").getAsString());
        assertEquals(vanilla.get("noise_router"), settings.get("noise_router"));
        assertEquals(vanilla.keySet(), settings.keySet());
    }

    @Test
    void seaLevelAloneKeepsTheVanillaFluidAndBlock() {
        JsonObject settings = file(Fixtures.compile("t", OVERWORLD + ", \"liquids\": {\"seaLevel\": 90}"),
                "data/adventure/worldgen/noise_settings/t.json");
        assertEquals(90, settings.get("sea_level").getAsInt());
        assertEquals("minecraft:water", settings.get("default_fluid").getAsString());
        assertEquals("minecraft:stone", settings.get("default_block").getAsString());
    }

    @Test
    void minecraft264OmitsDefaultBlockAndMakesItTheMaterialRulesFallback() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD
                + ", \"liquids\": {\"seaLevel\": 50}, \"materials\": {\"defaultBlock\": \"minecraft:deepslate\"}", VersionTable.V26_4);
        JsonObject settings = file(out, "data/adventure/worldgen/noise_settings/t.json");
        assertFalse(settings.has("default_block"));
        JsonObject rule = settings.getAsJsonObject("material_rule");
        assertEquals("minecraft:sequence", rule.get("type").getAsString());
        JsonArray sequence = rule.getAsJsonArray("sequence");
        assertEquals("minecraft:overworld", sequence.get(0).getAsString());
        assertEquals("minecraft:deepslate", sequence.get(1).getAsJsonObject().get("result_state").getAsString());
        assertTrue(out.report().entry("t").unsupported().isEmpty(), out.report().entry("t").unsupported().toString());
    }

    // ---- biome sources -------------------------------------------------------------------------

    @Test
    void overworldPresetWithoutBiomesUsesTheVanillaPreset() {
        JsonObject source = biomeSource(Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"large_biomes\"}"), "t");
        assertEquals("minecraft:multi_noise", source.get("type").getAsString());
        assertEquals("minecraft:overworld", source.get("preset").getAsString());
    }

    @Test
    void biomesFilterTheVanillaOverworldEntries() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD
                + ", \"biomes\": [\"minecraft:desert\", \"terralith:shield\", \"minecraft:basalt_deltas\", "
                + "{\"id\": \"minecraft:cherry_grove\", \"climate\": {\"temperature\": [0, 1]}}]");
        JsonObject source = biomeSource(out, "t");
        assertEquals(Set.of("minecraft:desert", "minecraft:cherry_grove"), biomesOf(source));
        long vanillaDesert = Fixtures.vanilla().biomeParameters("overworld").asList().stream()
                .filter(e -> e.getAsJsonObject().get("biome").getAsString().equals("minecraft:desert")).count();
        long keptDesert = source.getAsJsonArray("biomes").asList().stream()
                .filter(e -> e.getAsJsonObject().get("biome").getAsString().equals("minecraft:desert")).count();
        assertEquals(vanillaDesert, keptDesert);
        CompileReport.Entry entry = out.report().entry("t");
        assertEquals(List.of("`terralith:shield` (no vanilla biome)",
                "`minecraft:basalt_deltas` (not in vanilla's overworld parameter list)"), entry.skippedBiomes());
        assertTrue(entry.unsupported().stream().anyMatch(u -> u.startsWith("`biomes[minecraft:cherry_grove].climate`")));
    }

    @Test
    void nothingLeftFallsBackToPlains() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD + ", \"biomes\": [\"terralith:shield\"]");
        JsonArray biomes = biomeSource(out, "t").getAsJsonArray("biomes");
        assertEquals(1, biomes.size());
        assertEquals(MultiNoise.fullRange("minecraft:plains"), biomes.get(0));
        assertTrue(out.report().entry("t").notes().stream().anyMatch(n -> n.startsWith(DatapackCompiler.FALLBACK_NOTE)));
    }

    @Test
    void netherPresetFiltersTheNetherList() {
        JsonObject source = biomeSource(Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"biomes\": [\"minecraft:crimson_forest\", \"minecraft:soul_sand_valley\", \"incendium:ash_barrens\"]"), "t");
        assertEquals(Set.of("minecraft:crimson_forest", "minecraft:soul_sand_valley"), biomesOf(source));
        JsonObject preset = biomeSource(Fixtures.compile("u", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}"), "u");
        assertEquals("minecraft:nether", preset.get("preset").getAsString());
    }

    @Test
    void endPresetUsesTheEndSourceAndReportsAList() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, "
                + "\"biomes\": [\"minecraft:end_highlands\"]");
        assertEquals("minecraft:the_end", biomeSource(out, "t").get("type").getAsString());
        assertEquals("minecraft:end", stem(out, "t").getAsJsonObject("generator").get("settings").getAsString());
        List<String> unsupported = out.report().entry("t").unsupported();
        assertTrue(unsupported.stream().anyMatch(u -> u.startsWith("`biomes`")));
    }

    @Test
    void netherIslandsFilterTheNetherListInEndTerrain() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, "
                + "\"biomes\": [\"minecraft:basalt_deltas\", \"minecraft:end_highlands\", \"minecraft:warped_forest\"]");
        JsonObject source = biomeSource(out, "t");
        assertEquals("minecraft:multi_noise", source.get("type").getAsString());
        assertEquals(Set.of("minecraft:basalt_deltas", "minecraft:warped_forest"), biomesOf(source));
        assertTrue(out.report().entry("t").notes().stream().anyMatch(n -> n.contains("W2")));
        assertTrue(out.report().entry("t").skippedBiomes().contains(
                "`minecraft:end_highlands` (not in vanilla's nether parameter list)"));
    }

    @Test
    void checkerboardTakesVanillaBiomesAndCellScale() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"checkerboard\", \"cellScale\": 3}, "
                + "\"biomes\": [\"minecraft:desert\", \"betterend:neon_oasis\", \"minecraft:the_end\", \"minecraft:desert\"]");
        JsonObject source = biomeSource(out, "t");
        assertEquals("minecraft:checkerboard", source.get("type").getAsString());
        assertEquals(List.of("minecraft:desert", "minecraft:the_end"),
                source.getAsJsonArray("biomes").asList().stream().map(JsonElement::getAsString).toList());
        assertEquals(3, source.get("scale").getAsInt());
        assertEquals("minecraft:overworld", stem(out, "t").getAsJsonObject("generator").get("settings").getAsString());
        JsonObject defaults = biomeSource(Fixtures.compile("u", "\"generator\": {\"type\": \"checkerboard\"}"), "u");
        assertEquals(DatapackCompiler.DEFAULT_CHECKERBOARD_SCALE, defaults.get("scale").getAsInt());
        assertEquals("minecraft:plains", defaults.getAsJsonArray("biomes").get(0).getAsString());
    }

    @Test
    void singleBiomeIsAFixedSource() {
        JsonObject source = biomeSource(Fixtures.compile("t", "\"generator\": {\"type\": \"single_biome\", "
                + "\"biome\": \"minecraft:mushroom_fields\"}"), "t");
        assertEquals("minecraft:fixed", source.get("type").getAsString());
        assertEquals("minecraft:mushroom_fields", source.get("biome").getAsString());
    }

    @Test
    void flatTakesItsLayersAndBiome() {
        JsonObject generator = stem(Fixtures.compile("t", "\"generator\": {\"type\": \"flat\", \"biome\": \"minecraft:desert\", "
                + "\"layers\": [{\"block\": \"minecraft:bedrock\", \"height\": 1}, {\"block\": \"minecraft:sand\", \"height\": 3}]}"),
                "t").getAsJsonObject("generator");
        assertEquals("minecraft:flat", generator.get("type").getAsString());
        JsonObject settings = generator.getAsJsonObject("settings");
        assertEquals("minecraft:desert", settings.get("biome").getAsString());
        assertEquals(2, settings.getAsJsonArray("layers").size());
        assertEquals("minecraft:sand", settings.getAsJsonArray("layers").get(1).getAsJsonObject().get("block").getAsString());
    }

    @Test
    void voidIsFlatWithNoLayersAndItsFirstVanillaBiome() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"void\"}, "
                + "\"biomes\": [\"nullscape:void_barrens\", \"minecraft:small_end_islands\", \"minecraft:end_barrens\"], "
                + "\"liquids\": {\"defaultFluid\": \"minecraft:lava\"}");
        JsonObject settings = stem(out, "t").getAsJsonObject("generator").getAsJsonObject("settings");
        assertEquals(0, settings.getAsJsonArray("layers").size());
        assertEquals("minecraft:small_end_islands", settings.get("biome").getAsString());
        assertTrue(out.report().entry("t").unsupported().stream().anyMatch(u -> u.startsWith("`liquids`")));
        JsonObject empty = stem(Fixtures.compile("u", "\"generator\": {\"type\": \"void\"}"), "u")
                .getAsJsonObject("generator").getAsJsonObject("settings");
        assertEquals("minecraft:the_void", empty.get("biome").getAsString());
    }

    // ---- level stems and the report ------------------------------------------------------------

    @Test
    void baseWorldsGoWhereVanillaLoadsThem() {
        assertTrue(Fixtures.compile("overworld", OVERWORLD).files().containsKey("data/minecraft/dimension/overworld.json"));
        assertTrue(Fixtures.compile("the_nether", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}").files()
                .containsKey("data/minecraft/dimension/the_nether.json"));
        assertTrue(Fixtures.compile("the_end", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}").files()
                .containsKey("data/minecraft/dimension/the_end.json"));
        DatapackCompiler.Output aviary = Fixtures.compile("the_aviary", "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}");
        JsonObject stem = file(aviary, "data/adventure/dimension/the_aviary.json");
        assertEquals("adventure:the_aviary", stem.get("type").getAsString());
        assertEquals(CompileReport.Kind.BASE_WORLD, aviary.report().entry("the_aviary").kind());
    }

    @Test
    void otherDimensionsAreLazy() {
        DatapackCompiler.Output out = Fixtures.compile("the_gauntlet", OVERWORLD);
        assertEquals("adventure:the_gauntlet", stem(out, "the_gauntlet").get("type").getAsString());
        assertEquals(CompileReport.Kind.LAZY, out.report().entry("the_gauntlet").kind());
        assertTrue(out.files().keySet().stream().noneMatch(p -> p.contains("/dimension/")));
    }

    @Test
    void aCloneOfAnUnprovidedDimensionIsReportedNotCompiled() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"clone\", \"of\": \"paradise_lost:paradise_lost\"}");
        assertTrue(out.files().isEmpty());
        assertEquals(CompileReport.Kind.CLONE, out.report().entry("t").kind());
        assertTrue(out.reportText().contains("`paradise_lost:paradise_lost`"));
    }

    @Test
    void runtimeAndLaterFieldsAreReported() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"seed\": 5, \"spawn\": [0, 64, 0], " + OVERWORLD
                + ", \"borders\": {\"player\": 4096}, \"zones\": [{\"shape\": \"circle\"}], \"terrain\": {\"base\": \"adventure:x\"}, "
                + "\"materials\": {\"palette\": {}}");
        List<String> unsupported = out.report().entry("t").unsupported();
        for (String field : List.of("borders", "seed", "spawn", "zones", "terrain.base", "materials.palette")) {
            assertTrue(unsupported.stream().anyMatch(u -> u.startsWith("`" + field + "`")), field + " in " + unsupported);
        }
    }

    @Test
    void packMetaCarriesTheVersionsFormat() {
        JsonObject pack = DatapackCompiler.packMeta(VersionTable.V26_3, 82).getAsJsonObject("pack");
        assertEquals(121, pack.get("min_format").getAsInt());
        assertEquals(121, pack.get("max_format").getAsInt());
        assertEquals(125, DatapackCompiler.packMeta(VersionTable.V26_4, 82).getAsJsonObject("pack").get("max_format").getAsInt());
    }
}
