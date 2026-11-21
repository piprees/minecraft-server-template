package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SP4 assemblies, things and re-skin through the compiler against the extracted vanilla data. */
class AssembliesCompilerTest {

    static final String GENERATOR = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, ";

    static String body(String elements) {
        return GENERATOR + "\"setPieces\": {\"yard\": {\"name\": \"The Yard\", \"size\": 40, \"elements\": [" + elements + "]}}";
    }

    static String refusal(String elements) {
        return assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("yard", body(elements))).getMessage();
    }

    static JsonObject piece(DatapackCompiler.Output out, String slug, String name) {
        String text = out.files().get(VanillaForms.runtimePath("data/" + Fixtures.NAMESPACE + "/worldgen/structure/set_piece/" + slug + "/" + name + ".json"));
        assertTrue(text != null, "no set piece " + slug + "/" + name);
        return JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("set_piece");
    }

    @Test
    void aMobEntityALootTableOrAProcessorListVanillaLacksStopsTheCompile() {
        assertTrue(refusal("{\"kind\": \"mob\", \"entity\": \"minecraft:dragonfly\"}")
                .contains("yard: setPieces.yard.elements[0]: entity `minecraft:dragonfly` is not a vanilla entity type"));
        assertTrue(refusal("{\"kind\": \"chest\", \"loot\": \"minecraft:chests/nowhere\"}").contains("loot `minecraft:chests/nowhere` is not a vanilla loot table"));
        assertTrue(refusal("{\"kind\": \"archaeology\", \"loot\": \"minecraft:archaeology/nowhere\"}").contains("is not a vanilla loot table"));
        assertTrue(refusal("{\"kind\": \"wreck\", \"id\": \"minecraft:end_city/ship\", \"processors\": \"minecraft:nowhere\"}")
                .contains("processors `minecraft:nowhere` is not a vanilla processor list"));
        assertTrue(refusal("{\"kind\": \"cell\", \"captive\": {\"entity\": \"minecraft:yeti\"}}").contains("minecraft:yeti"));
        assertTrue(refusal("{\"kind\": \"skeleton\", \"parts\": [\"minecraft:fossil/spine_99\"]}").contains("not a vanilla structure template"));
    }

    @Test
    void itemComponentsAreCheckedAgainstVanilla() {
        assertTrue(refusal("{\"kind\": \"stand\", \"equipment\": {\"head\": {\"id\": \"minecraft:iron_helmet\", \"trim\": {\"pattern\": \"minecraft:zigzag\", \"material\": \"minecraft:gold\"}}}}")
                .contains("trim pattern `minecraft:zigzag`"));
        assertTrue(refusal("{\"kind\": \"stand\", \"equipment\": {\"head\": {\"id\": \"minecraft:iron_helmet\", \"trim\": {\"pattern\": \"minecraft:snout\", \"material\": \"minecraft:tin\"}}}}")
                .contains("trim material `minecraft:tin`"));
        assertTrue(refusal("{\"kind\": \"banner\", \"color\": \"black\", \"patterns\": [{\"pattern\": \"minecraft:snout\", \"color\": \"yellow\"}]}")
                .contains("banner pattern `minecraft:snout`"));
        assertTrue(refusal("{\"kind\": \"frame\", \"item\": {\"id\": \"minecraft:filled_map\", \"map\": {\"decorations\": [{\"type\": \"minecraft:treasure\", \"x\": 0, \"z\": 0}]}}}")
                .contains("map decoration"));
        assertTrue(refusal("{\"kind\": \"mob\", \"entity\": \"minecraft:iron_golem\", \"attributes\": {\"minecraft:luck_of_it\": 1}}").contains("attribute"));
        DatapackCompiler.Output out = Fixtures.compile("yard", body("""
                {"kind": "stand", "equipment": {"head": {"id": "minecraft:golden_helmet", "trim": {"pattern": "minecraft:snout", "material": "minecraft:gold"}},
                  "chest": {"id": "minecraft:leather_chestplate", "dye": "blue", "trim": {"pattern": "minecraft:wayfinder", "material": "minecraft:copper"}}}},
                {"kind": "banner", "color": "black", "patterns": [{"pattern": "minecraft:piglin", "color": "yellow"}]},
                {"kind": "frame", "item": {"id": "minecraft:filled_map", "map": {"decorations": [{"type": "minecraft:red_x", "x": 0, "z": 0}]}}},
                {"kind": "mob", "entity": "minecraft:iron_golem", "name": "Old Iron", "attributes": {"max_health": 200, "scale": 1.5},
                  "effects": [{"id": "minecraft:resistance"}]},
                {"kind": "pot", "sherds": {"front": "minecraft:blade_pottery_sherd"}, "item": "minecraft:ender_pearl"},
                {"kind": "chest", "loot": "minecraft:chests/simple_dungeon", "arrangement": [{"slot": 13, "item": {"id": "minecraft:iron_block", "count": 4}}]},
                {"kind": "archaeology", "loot": "minecraft:archaeology/trail_ruins_common"}"""));
        assertEquals(7, piece(out, "yard", "yard").getAsJsonArray("elements").size());
    }

    @Test
    void aLecternOrANameNamingADimensionOrAPeopleStopsTheCompile() {
        Map<String, com.customdimensions.core.definition.Definition> two = Map.of(
                "yard", Fixtures.definition(body("{\"kind\": \"lectern\", \"lines\": [\"We came up out of the Gilded Pit.\"]}")),
                "the_gilded_pit", Fixtures.definition(GENERATOR.substring(0, GENERATOR.length() - 2)));
        String named = assertThrows(IllegalArgumentException.class,
                () -> DatapackCompiler.compileAll(Fixtures.NAMESPACE, two, Fixtures.vanilla(), VersionTable.V26_3)).getMessage();
        assertTrue(named.contains("yard: setPieces.yard.elements[0].lines[0]") && named.contains("the gilded pit") && named.contains("RB-43"), named);
        assertTrue(refusal("{\"kind\": \"lectern\", \"lines\": [\"The piglins built it.\"]}").contains("piglins"));
        assertTrue(refusal("{\"kind\": \"mob\", \"entity\": \"minecraft:iron_golem\", \"name\": \"Witch Hunter\"}").contains("witch"));
        assertTrue(refusal("{\"kind\": \"mob\", \"entity\": \"minecraft:iron_golem\", \"name\": \"Keeper of the Yard\"}").contains("Yard"),
                "its own dimension's slug counts");
    }

    @Test
    void anAttemptedGateTakesItsTargetsPortal() {
        Map<String, com.customdimensions.core.definition.Definition> two = Map.of(
                "yard", Fixtures.definition(body("{\"kind\": \"gate\", \"state\": \"attempt\", \"target\": \"bones\", \"study\": true}")),
                "bones", Fixtures.definition(GENERATOR + "\"portals\": [{\"frameBlock\": \"minecraft:bone_block\", \"igniterItem\": \"minecraft:flint_and_steel\","
                        + " \"aura\": {\"subsume\": \"everything\", \"palette\": [\"minecraft:soul_soil\"]}}]"));
        DatapackCompiler.Output out = DatapackCompiler.compileAll(Fixtures.NAMESPACE, two, Fixtures.vanilla(), VersionTable.V26_3);
        JsonObject gate = piece(out, "yard", "yard").getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals("minecraft:bone_block", gate.get("frameBlock").getAsString());
        assertEquals("minecraft:flint", gate.get("precursor").getAsString());
        assertEquals("minecraft:soul_soil", gate.getAsJsonArray("aura").get(0).getAsString());
        assertTrue(refusal("{\"kind\": \"gate\", \"state\": \"attempt\", \"target\": \"bones\"}").contains("not a dimension with a portal"));
    }

    @Test
    void aMarkerGateNeedsExitShrines() {
        assertTrue(refusal("{\"kind\": \"gate\", \"marker\": true}").contains("needs exitShrines enabled"));
        DatapackCompiler.Output out = Fixtures.compile("yard", body("{\"kind\": \"gate\", \"marker\": true}") + ", \"exitShrines\": {\"enabled\": true}");
        assertTrue(piece(out, "yard", "yard").getAsJsonArray("elements").get(0).getAsJsonObject().get("marker").getAsBoolean());
    }

    @Test
    void aWreckIsMeasuredWithItsTilt() {
        DatapackCompiler.Output out = Fixtures.compile("yard", body("{\"kind\": \"wreck\", \"id\": \"minecraft:end_city/ship\", \"tilt\": \"nose\", \"trench\": 8}"));
        JsonObject wreck = piece(out, "yard", "yard").getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals("z", wreck.get("long").getAsString(), "the ship lies along z");
        String small = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("yard",
                GENERATOR + "\"setPieces\": {\"yard\": {\"size\": 18, \"elements\": [{\"kind\": \"wreck\", \"id\": \"minecraft:end_city/ship\", \"tilt\": \"nose\"}]}}"))
                .getMessage();
        assertTrue(small.contains("(wreck) reaches") && small.contains("past its size 18"), small);
    }

    @Test
    void aReskinIsResolvedIntoTheControlFile() {
        DatapackCompiler.Output out = Fixtures.compile("yard", GENERATOR
                + "\"materials\": {\"filler\": \"minecraft:blackstone\"},"
                + "\"structures\": {\"reskin\": {\"#minecraft:village\": {\"swap\": {\"minecraft:cobblestone\": \"materials.filler\"}, \"age\": \"old\"}}}");
        JsonArray reskin = JsonParser.parseString(out.files().get(StructuresCompiler.path("yard"))).getAsJsonObject().getAsJsonArray("reskin");
        assertEquals(1, reskin.size());
        assertEquals("minecraft:blackstone", reskin.get(0).getAsJsonObject().getAsJsonObject("swap").get("minecraft:cobblestone").getAsString());
        assertEquals("old", reskin.get(0).getAsJsonObject().get("age").getAsString());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("yard", GENERATOR
                + "\"structures\": {\"reskin\": {\"minecraft:igloo\": {\"swap\": {\"minecraft:snow_block\": \"minecraft:snow_bricks\"}}}}"))
                .getMessage().contains("`minecraft:snow_bricks` is not a vanilla block"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("yard", GENERATOR
                + "\"structures\": {\"reskin\": {\"minecraft:igloo\": {\"swap\": {\"minecraft:snow_block\": \"materials.cliffs\"}}}}"))
                .getMessage().contains("materials.cliffs is not set"));
    }
}
