package com.customdimensions.core.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SurfaceGroundTest {
    private static String block(String id) {
        return "{\"type\": \"minecraft:block\", \"result_state\": {\"Name\": \"" + id + "\"}}";
    }

    private static String when(String test, String then) {
        return "{\"type\": \"minecraft:condition\", \"if_true\": " + test + ", \"then_run\": " + then + "}";
    }

    private static String seq(String... rules) {
        return "{\"type\": \"minecraft:sequence\", \"sequence\": [" + String.join(", ", rules) + "]}";
    }

    private static String biome(String... ids) {
        return "{\"type\": \"minecraft:biome\", \"biome_is\": [\"" + String.join("\", \"", ids) + "\"]}";
    }

    private static final String FLOOR = "{\"type\": \"minecraft:stone_depth\", \"offset\": 0, \"surface_type\": \"floor\", \"add_surface_depth\": false, \"secondary_depth_range\": 0}";
    private static final String UNDER = "{\"type\": \"minecraft:stone_depth\", \"offset\": 0, \"surface_type\": \"floor\", \"add_surface_depth\": true, \"secondary_depth_range\": 0}";
    private static final String DEEPER = "{\"type\": \"minecraft:stone_depth\", \"offset\": 1, \"surface_type\": \"floor\", \"add_surface_depth\": false, \"secondary_depth_range\": 0}";
    private static final String NOISE = "{\"type\": \"minecraft:noise_threshold\", \"noise\": \"minecraft:calcite\", \"min_threshold\": -0.01, \"max_threshold\": 0.01}";

    /** Vanilla-shaped: peaks of stone with calcite patches, a high snow cap, sand deserts, badlands bands, grass elsewhere. */
    private static final JsonElement RULE = JsonParser.parseString(seq(
            when("{\"type\": \"minecraft:vertical_gradient\", \"random_name\": \"minecraft:bedrock_floor\", \"true_at_and_below\": {\"above_bottom\": 0}, \"false_at_and_above\": {\"above_bottom\": 5}}", block("minecraft:bedrock")),
            when("{\"type\": \"minecraft:above_preliminary_surface\"}", seq(
                    when(biome("minecraft:stony_peaks"), seq(when(NOISE, block("minecraft:calcite")), block("minecraft:stone"))),
                    when(biome("minecraft:badlands"), when(FLOOR, "{\"type\": \"minecraft:bandlands\"}")),
                    when(biome("minecraft:frozen_peaks"), when("{\"type\": \"minecraft:y_above\", \"anchor\": {\"absolute\": 200}, \"surface_depth_multiplier\": 0, \"add_stone_depth\": false}", block("minecraft:snow_block"))),
                    when("{\"type\": \"minecraft:not\", \"invert\": " + biome("minecraft:nether_wastes") + "}", seq(
                            when(FLOOR, seq(when(biome("minecraft:desert"), block("minecraft:sand")),
                                    when("{\"type\": \"minecraft:water\", \"offset\": -1, \"surface_depth_multiplier\": 0, \"add_stone_depth\": false}", block("minecraft:grass_block")))),
                            when(DEEPER, block("minecraft:dirt")),
                            when(UNDER, block("minecraft:dirt"))))))));

    @Test
    void theTopOfOpenLandFollowsTheBiomesBranch() {
        assertEquals("minecraft:grass_block", SurfaceGround.top(RULE, "minecraft:plains", "minecraft:stone", 71, -64, 384));
        assertEquals("minecraft:sand", SurfaceGround.top(RULE, "minecraft:desert", "minecraft:stone", 71, -64, 384));
        assertEquals("minecraft:stone", SurfaceGround.top(RULE, "minecraft:stony_peaks", "minecraft:stone", 71, -64, 384), "a noise patch is not the ground");
        assertEquals("minecraft:terracotta", SurfaceGround.top(RULE, "minecraft:badlands", "minecraft:stone", 71, -64, 384));
        assertEquals("minecraft:netherrack", SurfaceGround.top(RULE, "minecraft:nether_wastes", "minecraft:netherrack", 71, -64, 384), "no block: the default");
    }

    @Test
    void heightTestsReadTheColumn() {
        assertEquals("minecraft:grass_block", SurfaceGround.top(RULE, "minecraft:frozen_peaks", "minecraft:stone", 71, -64, 384));
        assertEquals("minecraft:snow_block", SurfaceGround.top(RULE, "minecraft:frozen_peaks", "minecraft:stone", 210, -64, 384));
    }

    @Test
    void rockBiomesAreThoseWhoseGroundIsBare() {
        Set<String> bare = Set.of("minecraft:stone", "minecraft:netherrack");
        assertEquals(Set.of("minecraft:stony_peaks", "minecraft:nether_wastes"), SurfaceGround.rockBiomes(RULE, SurfaceGround.Named.NONE,
                List.of("minecraft:plains", "minecraft:desert", "minecraft:stony_peaks", "minecraft:badlands", "minecraft:nether_wastes"),
                "minecraft:netherrack", 63, -64, 384, bare::contains));
    }

    @Test
    void aGradientIsTrueBelowItsBandSoARoofsNotGradientLaysNothingOnTheFloor() {
        String roof = "{\"type\": \"minecraft:vertical_gradient\", \"random_name\": \"minecraft:bedrock_roof\", \"true_at_and_below\": {\"below_top\": 5}, \"false_at_and_above\": {\"below_top\": 0}}";
        JsonElement nether = JsonParser.parseString(seq(
                when("{\"type\": \"minecraft:vertical_gradient\", \"random_name\": \"minecraft:bedrock_floor\", \"true_at_and_below\": {\"above_bottom\": 0}, \"false_at_and_above\": {\"above_bottom\": 5}}", block("minecraft:bedrock")),
                when("{\"type\": \"minecraft:not\", \"invert\": " + roof + "}", block("minecraft:bedrock")),
                when(biome("minecraft:basalt_deltas"), when(FLOOR, block("minecraft:blackstone")))));
        Set<String> bare = Set.of("minecraft:netherrack", "minecraft:blackstone");
        assertEquals(Set.of("minecraft:nether_wastes", "minecraft:basalt_deltas"), SurfaceGround.rockBiomesAt(nether, SurfaceGround.Named.NONE,
                List.of("minecraft:nether_wastes", "minecraft:basalt_deltas"), "minecraft:netherrack", 40, 0, 128, bare::contains));
        assertEquals("minecraft:bedrock", SurfaceGround.top(nether, "minecraft:nether_wastes", "minecraft:netherrack", 125, 0, 128), "over the roof's band");
        assertEquals("minecraft:netherrack", SurfaceGround.top(nether, "minecraft:nether_wastes", "minecraft:netherrack", 120, 0, 128), "under the roof's band");
        assertEquals("minecraft:bedrock", SurfaceGround.top(nether, "minecraft:nether_wastes", "minecraft:netherrack", 0, 0, 128), "the floor's bedrock");
    }

    @Test
    void anEndBiomesGroundIsTheEndStoneItsRuleLaysOnItsIslands() {
        JsonElement end = JsonParser.parseString(seq(
                when(biome("adventure:umbral_wastes"), when("\"minecraft:on_floor\"", block("minecraft:sculk"))),
                when("{\"type\": \"minecraft:y_above\", \"anchor\": {\"absolute\": 40}, \"surface_depth_multiplier\": 0, \"add_stone_depth\": false}",
                        "{\"type\": \"minecraft:block\", \"result_state\": \"minecraft:end_stone\"}")));
        SurfaceGround.Named named = new SurfaceGround.Named(java.util.Map.of(), java.util.Map.of("minecraft:on_floor", JsonParser.parseString(FLOOR)));
        Set<String> bare = Set.of("minecraft:end_stone");
        List<String> biomes = List.of("minecraft:end_highlands", "adventure:umbral_wastes");
        assertEquals(Set.of(), SurfaceGround.rockBiomes(end, named, biomes, "minecraft:air", 0, 0, 256, bare::contains),
                "read at sea level 0 + 8, under the islands, the End stone is not reached");
        assertEquals(64, SurfaceGround.groundY(0, true));
        assertEquals(71, SurfaceGround.groundY(63, false));
        assertEquals(Set.of("minecraft:end_highlands"), SurfaceGround.rockBiomesAt(end, named, biomes, "minecraft:air",
                SurfaceGround.groundY(0, true), 0, 256, bare::contains));
    }

    @Test
    void anUnknownRuleOrTestPlacesNothing() {
        JsonElement odd = JsonParser.parseString(seq("{\"type\": \"other:rule\"}", when("{\"type\": \"other:test\"}", block("minecraft:dirt"))));
        assertEquals("minecraft:stone", SurfaceGround.top(odd, "minecraft:plains", "minecraft:stone", 71, -64, 384));
        assertEquals("minecraft:stone", SurfaceGround.top(null, "minecraft:plains", "minecraft:stone", 71, -64, 384));
    }

    @Test
    void namedRulesAndTestsAreLookedUp() {
        JsonElement rule = JsonParser.parseString(seq(when("\"minecraft:on_floor\"", "\"minecraft:overworld/biome_surface\"")));
        SurfaceGround.Named named = new SurfaceGround.Named(
                java.util.Map.of("minecraft:overworld/biome_surface", JsonParser.parseString(seq(
                        when("{\"type\": \"minecraft:biome\", \"biome_is\": \"minecraft:stony_shore\"}", block("minecraft:stone")),
                        block("minecraft:grass_block")))),
                java.util.Map.of("minecraft:on_floor", JsonParser.parseString(FLOOR)));
        assertEquals("minecraft:stone", SurfaceGround.top(rule, named, "minecraft:stony_shore", "minecraft:dirt", 71, -64, 384));
        assertEquals("minecraft:grass_block", SurfaceGround.top(rule, named, "minecraft:plains", "minecraft:dirt", 71, -64, 384));
        assertEquals("minecraft:dirt", SurfaceGround.top(rule, "minecraft:plains", "minecraft:dirt", 71, -64, 384), "unresolved names place nothing");
        JsonElement loop = JsonParser.parseString("\"x:loop\"");
        assertEquals("minecraft:dirt", SurfaceGround.top(loop, new SurfaceGround.Named(java.util.Map.of("x:loop", loop), java.util.Map.of()),
                "minecraft:plains", "minecraft:dirt", 71, -64, 384));
    }
}
