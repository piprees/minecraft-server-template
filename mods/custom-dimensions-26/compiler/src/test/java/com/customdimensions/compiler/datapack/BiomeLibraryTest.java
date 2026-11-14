package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BiomeLibraryTest {

    private static final String ASHEN = "adventure:ashen_woodland";
    private static final String CRYSTAL = "adventure:crystal_hollows";

    private static DatapackCompiler.Output compile(String slug, String body, BiomeLibrary library) {
        return DatapackCompiler.compileAll(Fixtures.NAMESPACE, Map.of(slug, Fixtures.definition(body)), Fixtures.vanilla(),
                VersionTable.V26_3, library);
    }

    private static JsonObject file(DatapackCompiler.Output out, String path) {
        String text = out.files().get(path);
        assertNotNull(text, path + " not emitted");
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String shippedText(String id) {
        try (InputStream in = BiomeLibrary.class.getClassLoader().getResourceAsStream(BiomeLibrary.RESOURCES + id + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The shipped ashen woodland with one change, as a library of its own. */
    private static BiomeLibrary ashenWith(Consumer<JsonObject> change) {
        JsonObject o = JsonParser.parseString(shippedText("ashen_woodland")).getAsJsonObject();
        change.accept(o);
        return BiomeLibrary.of(Map.of("ashen_woodland", o.toString()));
    }

    private static String refusal(BiomeLibrary library) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> compile("plain", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}", library));
        return e.getMessage();
    }

    private static JsonObject feature(JsonObject library, String name) {
        return library.getAsJsonObject("features").getAsJsonObject(name).getAsJsonObject("feature");
    }

    // ---- compile ----------------------------------------------------------------------------

    @Test
    void theShippedLibraryValidatesAndEveryBiomeIsWrittenWithItsFeatures() {
        BiomeLibrary library = BiomeLibrary.shipped();
        assertTrue(library.entries().keySet().containsAll(java.util.List.of("ashen_woodland", "crystal_hollows")));
        DatapackCompiler.Output out = compile("plain", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}", library);
        JsonObject ashen = file(out, "data/adventure/worldgen/biome/ashen_woodland.json");
        file(out, "data/adventure/worldgen/biome/crystal_hollows.json");
        JsonArray vegetal = ashen.getAsJsonArray("features").get(9).getAsJsonArray();
        assertTrue(vegetal.contains(new com.google.gson.JsonPrimitive("adventure:biome/ashen_woodland/burnt_birch")), vegetal.toString());
        JsonObject tree = file(out, "data/adventure/worldgen/feature/biome/ashen_woodland/burnt_birch.json");
        assertEquals("minecraft:tree", tree.get("type").getAsString());
        JsonObject placed = file(out, "data/adventure/worldgen/placed_feature/biome/ashen_woodland/burnt_birch.json");
        assertEquals("adventure:biome/ashen_woodland/burnt_birch", placed.get("feature").getAsString());
        JsonObject geodes = file(out, "data/adventure/worldgen/placed_feature/biome/crystal_hollows/open_geodes.json");
        assertEquals("minecraft:amethyst_geode", geodes.get("feature").getAsString(), "a feature naming a vanilla one writes only the placed feature");
        assertFalse(out.files().containsKey("data/adventure/worldgen/feature/biome/crystal_hollows/open_geodes.json"));
        out.files().forEach((path, text) -> assertFalse(text.contains("\"~"), path + " keeps an unresolved ~name"));
        assertEquals(library.entries().size(), out.report().totals(out.files()).get("Library biomes"));
        assertTrue(library.entries().size() >= 22, library.entries().keySet().toString());
    }

    @Test
    void theBiomesTakeThePacksNamespace() {
        DatapackCompiler.Output out = DatapackCompiler.compileAll("elfydd",
                Map.of("plain", Fixtures.definition("\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"biomes\": [\"elfydd:ashen_woodland\"]")),
                Fixtures.vanilla(), VersionTable.V26_3, BiomeLibrary.shipped());
        JsonObject ashen = file(out, "data/elfydd/worldgen/biome/ashen_woodland.json");
        assertTrue(ashen.toString().contains("elfydd:biome/ashen_woodland/burnt_birch"));
        assertTrue(out.report().entry("plain").skippedBiomes().isEmpty(), out.report().entry("plain").skippedBiomes().toString());
    }

    @Test
    void aDefinitionListsALibraryBiomeAndItsDefaultClimateIsUsed() {
        DatapackCompiler.Output out = compile("burnt", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"},"
                + " \"biomes\": [\"" + ASHEN + "\", \"" + CRYSTAL + "\", \"minecraft:plains\"]", BiomeLibrary.shipped());
        assertTrue(out.report().entry("burnt").skippedBiomes().isEmpty(), out.report().entry("burnt").skippedBiomes().toString());
        JsonArray entries = file(out, "data/customdimensions/lazy_dimension/burnt.json").getAsJsonObject("generator")
                .getAsJsonObject("biome_source").getAsJsonArray("biomes");
        JsonObject first = entries.get(0).getAsJsonObject();
        assertEquals(ASHEN, first.get("biome").getAsString());
        JsonObject p = first.getAsJsonObject("parameters");
        assertEquals("[0.2,0.55]", p.get("temperature").toString());
        assertEquals("[-2.0,0.2]", p.get("depth").toString());
        JsonObject crystal = entries.get(1).getAsJsonObject();
        assertEquals(CRYSTAL, crystal.get("biome").getAsString());
        assertEquals("[0.2,0.9]", crystal.getAsJsonObject("parameters").get("depth").toString());
    }

    @Test
    void aDefinitionsOwnClimateWinsOverTheLibrarys() {
        DatapackCompiler.Output out = compile("burnt", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"},"
                + " \"biomes\": [{\"id\": \"" + ASHEN + "\", \"climate\": {\"temperature\": [0.5, 1.0]}}, \"minecraft:plains\"]",
                BiomeLibrary.shipped());
        JsonObject p = file(out, "data/customdimensions/lazy_dimension/burnt.json").getAsJsonObject("generator")
                .getAsJsonObject("biome_source").getAsJsonArray("biomes").get(0).getAsJsonObject().getAsJsonObject("parameters");
        assertEquals("[0.5,1.0]", p.get("temperature").toString());
        assertEquals("[-2.0,2.0]", p.get("depth").toString());
    }

    @Test
    void zonesStampAndSwapToLibraryBiomes() {
        DatapackCompiler.Output out = compile("zoned", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"},"
                + " \"zones\": [{\"shape\": \"circle\", \"radius\": 500, \"biomes\": [\"" + ASHEN + "\"]},"
                + " {\"shape\": \"all\", \"swap\": {\"minecraft:dripstone_caves\": \"" + CRYSTAL + "\"}}]", BiomeLibrary.shipped());
        assertTrue(out.report().entry("zoned").skippedBiomes().isEmpty(), out.report().entry("zoned").skippedBiomes().toString());
        JsonObject source = file(out, "data/customdimensions/lazy_dimension/zoned.json").getAsJsonObject("generator")
                .getAsJsonObject("biome_source");
        assertTrue(source.toString().contains(ASHEN), source.toString());
        assertEquals(CRYSTAL, source.getAsJsonObject("swap").get("minecraft:dripstone_caves").getAsString());
        String rule = out.files().get("data/adventure/worldgen/material_rule/zoned.json");
        assertNotNull(rule, "a zone's library biome brings its palette");
        assertTrue(rule.contains(ASHEN) && rule.contains(CRYSTAL), rule);
    }

    @Test
    void aCheckerboardAndASingleBiomeWorldTakeLibraryBiomes() {
        DatapackCompiler.Output board = compile("board", "\"generator\": {\"type\": \"checkerboard\"}, \"biomes\": [\"" + ASHEN + "\", \"" + CRYSTAL + "\"]",
                BiomeLibrary.shipped());
        JsonArray biomes = file(board, "data/customdimensions/lazy_dimension/board.json").getAsJsonObject("generator")
                .getAsJsonObject("biome_source").getAsJsonArray("biomes");
        assertEquals("[\"" + ASHEN + "\",\"" + CRYSTAL + "\"]", biomes.toString());
        DatapackCompiler.Output single = compile("single", "\"generator\": {\"type\": \"single_biome\", \"biome\": \"" + ASHEN + "\"}",
                BiomeLibrary.shipped());
        assertEquals(ASHEN, file(single, "data/customdimensions/lazy_dimension/single.json").getAsJsonObject("generator")
                .getAsJsonObject("biome_source").get("biome").getAsString());
    }

    @Test
    void anUnknownIdInTheLibrarysNamespaceIsStillSkipped() {
        DatapackCompiler.Output out = compile("burnt", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"},"
                + " \"biomes\": [\"adventure:no_such_biome\", \"minecraft:plains\"]", BiomeLibrary.shipped());
        assertEquals(1, out.report().entry("burnt").skippedBiomes().size());
        assertTrue(out.report().entry("burnt").skippedBiomes().getFirst().contains(DatapackCompiler.UNKNOWN_BIOME));
    }

    // ---- materials --------------------------------------------------------------------------

    @Test
    void aLibraryPaletteAppliesUnderItsBiomeBeforeTheDimensionsPalette() {
        DatapackCompiler.Output out = compile("burnt", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"},"
                + " \"biomes\": [\"" + ASHEN + "\", \"minecraft:plains\"], \"materials\": {\"top\": \"minecraft:moss_block\"}",
                BiomeLibrary.shipped());
        JsonArray sequence = file(out, "data/adventure/worldgen/material_rule/burnt.json").getAsJsonArray("sequence");
        int library = -1;
        int dimension = -1;
        for (int i = 0; i < sequence.size(); i++) {
            String s = sequence.get(i).toString();
            if (s.contains(ASHEN) && s.contains("minecraft:coarse_dirt")) library = i;
            if (s.contains("minecraft:moss_block")) dimension = i;
        }
        assertTrue(library >= 0 && dimension > library, "library palette at " + library + ", dimension's at " + dimension);
        assertEquals("adventure:burnt", file(out, "data/customdimensions/lazy_dimension/burnt.json")
                .getAsJsonObject("generator").get("settings").getAsString());
    }

    @Test
    void aDefinitionsOwnPaletteForALibraryBiomeWins() {
        DatapackCompiler.Output out = compile("burnt", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"},"
                + " \"biomes\": [\"" + ASHEN + "\"], \"materials\": {\"biomes\": {\"" + ASHEN + "\": {\"top\": \"minecraft:red_sand\"}}}",
                BiomeLibrary.shipped());
        String rule = out.files().get("data/adventure/worldgen/material_rule/burnt.json");
        assertTrue(rule.contains("minecraft:red_sand"), rule);
        assertFalse(rule.contains("minecraft:coarse_dirt"), rule);
    }

    @Test
    void aDimensionWithoutLibraryBiomesTakesNoLibraryPalette() {
        DatapackCompiler.Output out = compile("plain", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}", BiomeLibrary.shipped());
        assertFalse(out.files().containsKey("data/adventure/worldgen/material_rule/plain.json"));
    }

    // ---- refusals ---------------------------------------------------------------------------

    @Test
    void anUnknownOwnFeatureIsRefused() {
        String message = refusal(ashenWith(o -> o.getAsJsonObject("biome").getAsJsonArray("features").get(9).getAsJsonArray().add("~no_such")));
        assertTrue(message.contains("ashen_woodland") && message.contains("~no_such"), message);
    }

    @Test
    void anUnknownOwnFeatureInsideAFeatureIsRefused() {
        String message = refusal(ashenWith(o -> o.getAsJsonObject("features").getAsJsonObject("fallen_birch").addProperty("feature", "~missing")));
        assertTrue(message.contains("ashen_woodland") && message.contains("~missing"), message);
    }

    @Test
    void anUnknownBlockIsRefused() {
        String message = refusal(ashenWith(o -> feature(o, "dead_bushes").getAsJsonObject("to_place").addProperty("id", "minecraft:ash_block")));
        assertTrue(message.contains("ashen_woodland") && message.contains("minecraft:ash_block") && message.contains("block"), message);
    }

    @Test
    void aBareBlockIdWhereAProviderGoesIsRefused() {
        String message = refusal(ashenWith(o -> feature(o, "dead_bushes").addProperty("to_place", "minecraft:dead_bush")));
        assertTrue(message.contains("dead_bushes") && message.contains("not a vanilla block state provider"), message);
        BiomeLibrary named = ashenWith(o -> feature(o, "dead_bushes").addProperty("to_place", "minecraft:flower_plain"));
        DatapackCompiler.Output out = compile("a", "\"generator\": {\"type\": \"single_biome\", \"biome\": \"" + ASHEN + "\"}", named);
        assertTrue(out.files().containsKey("data/adventure/worldgen/feature/biome/ashen_woodland/dead_bushes.json"), out.files().keySet().toString());
    }

    @Test
    void aHorizontalColumnPastTheWriteRadiusIsRefused() {
        Consumer<Integer> reach = max -> {
            BiomeLibrary library = ashenWith(o -> feature(o, "sunk_coal_logs_east").getAsJsonArray("layers").get(0).getAsJsonObject()
                    .getAsJsonObject("height").addProperty("max_inclusive", max));
            compile("a", "\"generator\": {\"type\": \"single_biome\", \"biome\": \"" + ASHEN + "\"}", library);
        };
        reach.accept(BiomeLibrary.HORIZONTAL_REACH);
        String message = refusal(ashenWith(o -> feature(o, "sunk_coal_logs_east").getAsJsonArray("layers").get(0).getAsJsonObject()
                .add("height", new com.google.gson.JsonPrimitive(BiomeLibrary.HORIZONTAL_REACH + 1))));
        assertTrue(message.contains("sunk_coal_logs_east") && message.contains("block_column east reaches 17"), message);
        BiomeLibrary vertical = ashenWith(o -> {
            JsonObject column = feature(o, "sunk_coal_logs_east");
            column.addProperty("direction", "up");
            column.getAsJsonArray("layers").get(0).getAsJsonObject().addProperty("height", 64);
        });
        compile("b", "\"generator\": {\"type\": \"single_biome\", \"biome\": \"" + ASHEN + "\"}", vertical);
    }

    @Test
    void anUnknownPaletteBlockIsRefused() {
        String message = refusal(ashenWith(o -> o.getAsJsonObject("palette").addProperty("top", "minecraft:ash")));
        assertTrue(message.contains("ashen_woodland") && message.contains("palette.top"), message);
    }

    @Test
    void anUnknownFeatureTypeIsRefused() {
        String message = refusal(ashenWith(o -> feature(o, "ash_gravel").addProperty("type", "minecraft:random_patch")));
        assertTrue(message.contains("ashen_woodland") && message.contains("minecraft:random_patch") && message.contains("feature type"), message);
    }

    @Test
    void anUnknownPlacedFeatureIsRefused() {
        String message = refusal(ashenWith(o -> o.getAsJsonObject("biome").getAsJsonArray("features").get(9).getAsJsonArray().add("minecraft:trees_ash")));
        assertTrue(message.contains("ashen_woodland") && message.contains("minecraft:trees_ash"), message);
    }

    @Test
    void anUnknownPlacementModifierIsRefused() {
        String message = refusal(ashenWith(o -> o.getAsJsonObject("features").getAsJsonObject("ash_gravel").getAsJsonArray("placement")
                .get(0).getAsJsonObject().addProperty("type", "minecraft:spread_out")));
        assertTrue(message.contains("ashen_woodland") && message.contains("placement modifier"), message);
    }

    @Test
    void anUnknownParticleIsRefused() {
        String message = refusal(ashenWith(o -> o.getAsJsonObject("biome").getAsJsonObject("attributes")
                .getAsJsonObject("minecraft:visual/ambient_particles").getAsJsonArray("argument").get(0).getAsJsonObject()
                .getAsJsonObject("particle").addProperty("type", "minecraft:grey_ash")));
        assertTrue(message.contains("ashen_woodland") && message.contains("minecraft:grey_ash") && message.contains("particle"), message);
    }

    @Test
    void anUnknownSoundIsRefused() {
        String message = refusal(ashenWith(o -> o.getAsJsonObject("biome").getAsJsonObject("attributes")
                .getAsJsonObject("minecraft:audio/background_music").getAsJsonObject("default").addProperty("sound", "minecraft:music.overworld.ashen")));
        assertTrue(message.contains("ashen_woodland") && message.contains("minecraft:music.overworld.ashen") && message.contains("sound"), message);
    }

    @Test
    void anUnknownAttributeAndEntityAreRefused() {
        String attribute = refusal(ashenWith(o -> o.getAsJsonObject("biome").getAsJsonObject("attributes").addProperty("minecraft:visual/ash_color", "#ffffff")));
        assertTrue(attribute.contains("ashen_woodland") && attribute.contains("environment attribute"), attribute);
        String entity = refusal(ashenWith(o -> o.getAsJsonObject("biome").getAsJsonObject("attributes")
                .getAsJsonObject("minecraft:gameplay/natural_mob_spawns").getAsJsonObject("argument").getAsJsonObject("spawns_by_category")
                .getAsJsonArray("creature").get(0).getAsJsonObject().addProperty("type", "minecraft:ash_rabbit")));
        assertTrue(entity.contains("ashen_woodland") && entity.contains("entity type"), entity);
    }

    @Test
    void anIdFromAnotherModIsRefused() {
        String message = refusal(ashenWith(o -> feature(o, "dead_bushes").getAsJsonObject("to_place").addProperty("id", "regions_unexplored:ash")));
        assertTrue(message.contains("ashen_woodland") && message.contains("regions_unexplored:ash"), message);
    }

    @Test
    void anUnknownVanillaReferenceIsRefused() {
        String message = refusal(ashenWith(o -> feature(o, "ash_gravel").getAsJsonObject("target").add("blocks",
                JsonParser.parseString("[\"minecraft:coarse_dirt\", \"minecraft:ashen_dirt\"]"))));
        assertTrue(message.contains("ashen_woodland") && message.contains("minecraft:ashen_dirt"), message);
    }

    @Test
    void missingRealmInspirationOrDifferenceIsRefused() {
        for (String field : java.util.List.of("realm", "inspiredBy", "differs")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ashenWith(o -> o.remove(field)));
            assertTrue(e.getMessage().contains("ashen_woodland") && e.getMessage().contains(field + " is required"), e.getMessage());
        }
        IllegalArgumentException realm = assertThrows(IllegalArgumentException.class, () -> ashenWith(o -> o.addProperty("realm", "aether")));
        assertTrue(realm.getMessage().contains("ashen_woodland") && realm.getMessage().contains("aether"), realm.getMessage());
    }

    @Test
    void aMalformedBiomeObjectIsRefused() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ashenWith(o -> o.getAsJsonObject("biome").remove("temperature")));
        assertTrue(missing.getMessage().contains("biome.temperature is required"), missing.getMessage());
        IllegalArgumentException extra = assertThrows(IllegalArgumentException.class,
                () -> ashenWith(o -> o.getAsJsonObject("biome").addProperty("precipitation", "rain")));
        assertTrue(extra.getMessage().contains("biome.precipitation"), extra.getMessage());
        IllegalArgumentException climate = assertThrows(IllegalArgumentException.class,
                () -> ashenWith(o -> o.getAsJsonObject("climate").addProperty("height", 1)));
        assertTrue(climate.getMessage().contains("ashen_woodland") && climate.getMessage().contains("height"), climate.getMessage());
    }

    /** The tide pools lay gravel, sand or clay round and under their water; the rim moss and pool life must stand on it. */
    @Test
    void rockPoolRimsAndLifeStandOnTheGroundThePoolsLay() {
        JsonObject o = JsonParser.parseString(shippedText("rock_pools")).getAsJsonObject();
        java.util.Set<String> laid = new java.util.TreeSet<>();
        for (var e : feature(o, "tide_pools").getAsJsonObject("ground_state").getAsJsonArray("entries")) {
            laid.add(e.getAsJsonObject().getAsJsonObject("data").get("id").getAsString());
        }
        for (String name : java.util.List.of("rim_moss", "pool_life")) {
            JsonArray placement = o.getAsJsonObject("features").getAsJsonObject(name).getAsJsonArray("placement");
            String filter = placement.get(placement.size() - 1).toString();
            for (String block : laid) assertTrue(filter.contains(block), name + " does not stand on " + block + ": " + filter);
        }
        String life = o.getAsJsonObject("features").getAsJsonObject("pool_life").toString();
        assertFalse(life.contains("[0,1,0]"), "a tide pool's water is one block deep; pool life cannot ask for water above it");
    }

    @Test
    void theShippedBiomesMeetTheSchema() throws IOException {
        com.networknt.schema.Schema schema = com.networknt.schema.SchemaRegistry
                .withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(java.nio.file.Files.readString(java.nio.file.Path.of("../schema/biome.schema.json")),
                        com.networknt.schema.InputFormat.JSON);
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (String id : BiomeLibrary.shipped().entries().keySet()) {
            schema.validate(shippedText(id), com.networknt.schema.InputFormat.JSON).forEach(error -> failures.add(id + ": " + error));
        }
        assertEquals(java.util.List.of(), failures);
    }

    /**
     * Any library biome can share a dimension with any vanilla or library biome, so all of them
     * together keep one feature order per step: Minecraft's FeatureSorter throws on a cycle.
     */
    @Test
    void everyBiomeTogetherKeepsOneFeatureOrder() {
        Map<String, JsonArray> biomes = new java.util.TreeMap<>();
        VanillaSource source = Fixtures.vanilla().source();
        for (String name : source.list("data/minecraft/worldgen/biome")) {
            if (!name.endsWith(".json")) continue;
            String text = source.read("data/minecraft/worldgen/biome/" + name).orElseThrow();
            biomes.put("minecraft:" + name, JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("features"));
        }
        BiomeLibrary.shipped().files(Fixtures.NAMESPACE).forEach((path, json) -> {
            if (path.contains("/worldgen/biome/")) biomes.put(path, json.getAsJsonArray("features"));
        });
        Map<String, java.util.Set<String>> next = new java.util.HashMap<>();
        Map<String, java.util.Set<String>> from = new java.util.HashMap<>();
        biomes.forEach((biome, steps) -> {
            String previous = null;
            for (int step = 0; step < steps.size(); step++) {
                for (var feature : steps.get(step).getAsJsonArray()) {
                    String node = feature.getAsString() + " @" + step;
                    if (previous != null) {
                        next.computeIfAbsent(previous, k -> new java.util.TreeSet<>()).add(node);
                        from.computeIfAbsent(previous + " -> " + node, k -> new java.util.TreeSet<>()).add(biome);
                    }
                    previous = node;
                }
            }
        });
        Map<String, Integer> state = new java.util.HashMap<>();
        for (String start : new java.util.TreeSet<>(next.keySet())) {
            java.util.Deque<String> path = new java.util.ArrayDeque<>();
            java.util.Deque<java.util.Iterator<String>> todo = new java.util.ArrayDeque<>();
            if (state.containsKey(start)) continue;
            state.put(start, 1);
            path.push(start);
            todo.push(next.getOrDefault(start, java.util.Set.of()).iterator());
            while (!todo.isEmpty()) {
                if (!todo.peek().hasNext()) {
                    state.put(path.pop(), 2);
                    todo.pop();
                    continue;
                }
                String node = todo.peek().next();
                Integer seen = state.get(node);
                if (seen == null) {
                    state.put(node, 1);
                    path.push(node);
                    todo.push(next.getOrDefault(node, java.util.Set.of()).iterator());
                } else if (seen == 1) {
                    fail("feature order cycle through " + node + ", edge from " + path.peek() + " in " + from.get(path.peek() + " -> " + node));
                }
            }
        }
        assertTrue(biomes.size() > 60);
    }
}
