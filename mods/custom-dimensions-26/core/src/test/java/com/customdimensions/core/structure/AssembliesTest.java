package com.customdimensions.core.structure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SP4 assemblies and things: their checks, their expansion, and the words check, against a stand-in of vanilla's data. */
class AssembliesTest {

    /** Vanilla's sizes for the templates used here; entities, loot tables and processor lists by a short list. */
    static final SetPieceSpec.Catalogue VANILLA = new SetPieceSpec.Catalogue() {
        public int[] templateSize(String id) {
            return switch (id) {
                case "minecraft:end_city/ship" -> new int[] {13, 24, 29};
                case "minecraft:fossil/spine_1", "minecraft:fossil/spine_2", "minecraft:fossil/spine_3", "minecraft:fossil/spine_4" -> new int[] {7, 4, 13};
                case "minecraft:fossil/skull_1" -> new int[] {6, 5, 7};
                case "minecraft:pillager_outpost/feature_tent1", "minecraft:pillager_outpost/feature_tent2" -> new int[] {6, 4, 7};
                default -> null;
            };
        }

        public boolean block(String id) {
            return !id.equals("minecraft:unobtainium");
        }

        public boolean entity(String id) {
            return Set.of("minecraft:enderman", "minecraft:iron_golem", "minecraft:skeleton").contains(id);
        }

        public boolean lootTable(String id) {
            return id.startsWith("minecraft:chests/") || id.startsWith("minecraft:archaeology/");
        }

        public boolean processorList(String id) {
            return id.equals("minecraft:zombie_plains");
        }

        public boolean known(String registry, String id) {
            return switch (registry) {
                case "minecraft:item" -> !id.equals("minecraft:unobtainium");
                case "minecraft:trim_pattern" -> Set.of("minecraft:snout", "minecraft:wayfinder", "minecraft:sentry").contains(id);
                case "minecraft:trim_material" -> Set.of("minecraft:gold", "minecraft:copper").contains(id);
                case "minecraft:attribute" -> Set.of("minecraft:max_health", "minecraft:scale", "minecraft:attack_damage").contains(id);
                case "minecraft:mob_effect" -> id.equals("minecraft:resistance");
                case "minecraft:banner_pattern" -> Set.of("minecraft:piglin", "minecraft:border").contains(id);
                case "minecraft:map_decoration_type" -> id.equals("minecraft:red_x");
                default -> true;
            };
        }

        public Set<String> dimensions() {
            return Set.of("the_gilded_pit", "the_aviary", "the_end", "the_boneyard");
        }

        public JsonObject portal(String slug) {
            return slug.equals("the_boneyard") ? JsonParser.parseString("""
                    {"frameBlock": "minecraft:bone_block", "igniterItem": "minecraft:flint_and_steel",
                     "aura": {"subsume": "everything", "palette": ["minecraft:soul_soil", "minecraft:basalt"]}}""").getAsJsonObject() : null;
        }
    };

    static SetPieceSpec spec(String elements) {
        return spec(elements, "");
    }

    static SetPieceSpec spec(String elements, String extra) {
        String json = "{\"size\": 64, " + extra + "\"elements\": [" + elements + "]}";
        return SetPieceSpec.resolve("the_test", "piece", JsonParser.parseString(json).getAsJsonObject(), Map.of(), null, VANILLA);
    }

    static String refusal(String elements) {
        return assertThrows(IllegalArgumentException.class, () -> spec(elements)).getMessage();
    }

    /** Every block written by the piece's assemblies with seed {@code seed}, by position {x, y, z}, on flat ground at 64. */
    static Map<List<Integer>, String> blocks(SetPieceSpec spec, long seed) {
        Map<List<Integer>, String> out = new HashMap<>();
        for (SetPiecePlan.Placement p : flatten(spec, seed)) {
            if (p.block() == null) continue;
            out.put(List.of(p.dx(), 64 + p.dy(), p.dz()), SetPiecePlan.blockAt(p, p.dx(), 64 + p.dy(), p.dz()));
        }
        return out;
    }

    static List<SetPiecePlan.Placement> flatten(SetPieceSpec spec, long seed) {
        List<SetPiecePlan.Placement> out = new ArrayList<>();
        for (SetPiecePlan.Placement p : SetPiecePlan.plan(spec, seed, 0)) {
            if (Assemblies.expands(p.kind())) out.addAll(Assemblies.expand(spec, p));
            else out.add(p);
        }
        return out;
    }

    static String id(String state) {
        return state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
    }

    // ---- unit acceptance --------------------------------------------------------------------

    @Test
    void aBurnedCampSwapsItsWoodForItsBurnedPalette() {
        SetPieceSpec living = spec("{\"kind\": \"camp\", \"state\": \"living\", \"tents\": 2, \"rotation\": \"none\"}");
        long wood = blocks(living, 7).values().stream().filter(b -> Assemblies.wooden(id(b))).count();
        assertTrue(wood > 0, "a living camp has its seats and woodpile");
        assertTrue(flatten(living, 7).stream().noneMatch(p -> p.source().params().has("burned")), "a living camp's tents are not burned");

        SetPieceSpec burned = spec("{\"kind\": \"camp\", \"state\": \"burned\", \"tents\": 2, \"rotation\": \"none\"}");
        Map<List<Integer>, String> b = blocks(burned, 7);
        assertTrue(b.values().stream().noneMatch(s -> Assemblies.wooden(id(s))), "no wood is left: " + b.values());
        assertTrue(b.values().stream().anyMatch(s -> Assemblies.BURNED.contains(id(s))), "wood became the burned palette");
        assertTrue(b.values().stream().anyMatch(s -> Assemblies.ASH.contains(id(s))), "ash lies round it");
        assertTrue(b.containsValue("minecraft:fire"), "and fire in its pit");
        List<SetPiecePlan.Placement> tents = flatten(burned, 7).stream().filter(p -> p.kind().equals("template")).toList();
        assertEquals(2, tents.size());
        assertTrue(tents.stream().allMatch(p -> p.source().params().has("burned")), "its tents are burned by the runtime's burn processor");

        SetPieceSpec own = spec("{\"kind\": \"camp\", \"state\": \"burned\", \"tents\": 0, \"rotation\": \"none\"}",
                "\"palette\": {\"burned\": [\"minecraft:deepslate\"]}, ");
        assertTrue(blocks(own, 7).values().stream().filter(s -> !Assemblies.ASH.contains(id(s))).anyMatch(s -> id(s).equals("minecraft:deepslate")),
                "a spec's burned palette wins");
    }

    @Test
    void burningKeepsAnAxisAndLosesCloth() {
        assertEquals("minecraft:basalt[axis=z]", Assemblies.burn("minecraft:spruce_log[axis=z]", 0.95, List.of("minecraft:basalt"), null));
        assertNull(Assemblies.burn("minecraft:red_wool", 0.9, Assemblies.BURNED, null));
        assertEquals("minecraft:cobblestone", Assemblies.burn("minecraft:cobblestone", 0.1, Assemblies.BURNED, null));
        assertFalse(Assemblies.wooden("minecraft:crimson_nylium"), "nylium is ground, not wood");
        assertTrue(Assemblies.wooden("minecraft:oak_fence"));
        assertEquals(Age.AIR, Assemblies.burnBlock("minecraft:white_wool", 0.5));
    }

    @Test
    void aFarmsCropsStandOnBlocksThatHoldThem() {
        Map<String, String> soil = Map.of("nether_wart", "minecraft:soul_sand", "wheat", "minecraft:farmland", "crimson_fungus", "minecraft:crimson_nylium",
                "mushroom", "minecraft:mycelium", "beetroots", "minecraft:farmland");
        Map<String, Set<String>> crops = Map.of("nether_wart", Set.of("minecraft:nether_wart"), "wheat", Set.of("minecraft:wheat"),
                "crimson_fungus", Set.of("minecraft:crimson_fungus"), "mushroom", Set.of("minecraft:red_mushroom", "minecraft:brown_mushroom"),
                "beetroots", Set.of("minecraft:beetroots"));
        for (String crop : soil.keySet()) {
            Map<List<Integer>, String> b = blocks(spec("{\"kind\": \"farm\", \"crop\": \"" + crop + "\", \"plots\": 3, \"rotation\": \"none\"}"), 3);
            int planted = 0;
            for (Map.Entry<List<Integer>, String> e : b.entrySet()) {
                if (!crops.get(crop).contains(id(e.getValue()))) continue;
                planted++;
                List<Integer> p = e.getKey();
                String below = b.get(List.of(p.get(0), p.get(1) - 1, p.get(2)));
                assertEquals(soil.get(crop), below == null ? null : id(below), crop + " at " + p + " stands on " + below);
            }
            assertTrue(planted >= 30, crop + ": " + planted + " planted");
        }
        Map<List<Integer>, String> wart = blocks(spec("{\"kind\": \"farm\", \"crop\": \"nether_wart\", \"rotation\": \"none\"}"), 3);
        assertTrue(wart.containsValue("minecraft:lava"), "a wart farm is irrigated with lava by default");
        assertTrue(wart.values().stream().anyMatch(s -> s.startsWith("minecraft:nether_brick_fence[")), "and fenced in nether brick");
    }

    @Test
    void aMarkerGateMeetsTheExitShrineContract() {
        SetPieceSpec gate = spec("{\"kind\": \"gate\", \"frame\": \"custom\", \"blocks\": [\"minecraft:crying_obsidian\"], \"marker\": true, \"rotation\": \"none\"}");
        Map<List<Integer>, String> b = blocks(gate, 11);
        assertEquals("minecraft:beacon", b.get(List.of(0, 64, 0)));
        for (int step = 0; step <= 1; step++) {
            assertEquals("minecraft:crying_obsidian", b.get(List.of(step, 65, 0)), "bottom rail");
            assertEquals("minecraft:crying_obsidian", b.get(List.of(step, 69, 0)), "top rail");
            for (int y = 66; y <= 68; y++) assertEquals("minecraft:air", b.get(List.of(step, y, 0)), "the opening");
        }
        for (int y = 66; y <= 68; y++) {
            assertEquals("minecraft:crying_obsidian", b.get(List.of(-1, y, 0)));
            assertEquals("minecraft:crying_obsidian", b.get(List.of(2, y, 0)));
        }
        assertTrue(refusal("{\"kind\": \"gate\", \"marker\": true, \"width\": 3}").contains("2 wide"));
    }

    @Test
    void anAttemptedGateTakesItsTargetsFrameAndCarriesAStudy() {
        SetPieceSpec gate = spec("{\"kind\": \"gate\", \"state\": \"attempt\", \"target\": \"the_boneyard\", \"study\": true, \"rotation\": \"none\"}");
        JsonObject params = gate.elements().getFirst().params();
        assertEquals("minecraft:bone_block", params.get("frameBlock").getAsString());
        assertEquals("minecraft:flint", params.get("precursor").getAsString(), "the igniter's precursor, never the igniter");
        assertEquals(2, params.getAsJsonArray("aura").size());
        List<SetPiecePlan.Placement> parts = flatten(gate, 5);
        Map<List<Integer>, String> b = blocks(gate, 5);
        assertTrue(b.containsValue("minecraft:bone_block"), "a partial frame of the target's block");
        assertTrue(b.containsValue("minecraft:bedrock"), "on a bedrock plinth");
        assertTrue(b.values().stream().anyMatch(s -> s.startsWith("minecraft:end_rod")));
        assertTrue(b.containsValue("minecraft:brewing_stand") && b.containsValue("minecraft:enchanting_table") && b.containsValue("minecraft:bookshelf"));
        assertTrue(b.values().stream().anyMatch(s -> s.equals("minecraft:soul_soil") || s.equals("minecraft:basalt")), "the ground part-turned to the aura");
        long frame = b.values().stream().filter(s -> s.equals("minecraft:bone_block")).count();
        assertTrue(frame < 10 + 2, "partial: " + frame + " frame blocks of a ring of 10 and its plinth");
        SetPiecePlan.Placement lectern = parts.stream().filter(p -> p.kind().equals("lectern")).findFirst().orElseThrow();
        assertTrue(lectern.source().params().has("pool"));
        SetPiecePlan.Placement chest = parts.stream().filter(p -> p.kind().equals("chest")).findFirst().orElseThrow();
        assertEquals(13, chest.source().params().getAsJsonArray("arrangement").get(0).getAsJsonObject().get("slot").getAsInt());
        assertTrue(refusal("{\"kind\": \"gate\", \"target\": \"the_nowhere\"}").contains("not a dimension with a portal"));
        assertTrue(refusal("{\"kind\": \"gate\", \"study\": true}").contains("attempt"));
        for (String line : Assemblies.STUDY_LINES) assertNull(Words.named(line, VANILLA.dimensions()), line);
    }

    @Test
    void aCellRingsItsCaptiveInBarsAndHoldsIt() {
        SetPieceSpec cell = spec("{\"kind\": \"cell\", \"count\": 2, \"holder\": \"minecart\", \"captive\": {\"entity\": \"minecraft:enderman\"}, \"rotation\": \"none\"}");
        List<SetPiecePlan.Placement> parts = flatten(cell, 9);
        List<SetPiecePlan.Placement> mobs = parts.stream().filter(p -> p.kind().equals("mob")).toList();
        assertEquals(2, mobs.size(), "one captive a cell");
        assertEquals("minecart", mobs.getFirst().source().params().get("holder").getAsString());
        Map<List<Integer>, String> b = blocks(cell, 9);
        for (SetPiecePlan.Placement m : mobs) {
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
                for (int y = 64; y < 68; y++) {
                    String s = b.get(List.of(m.dx() + dx, y, m.dz() + dz));
                    assertTrue(s != null && s.startsWith("minecraft:iron_bars["), "bars round the captive at " + dx + "," + dz + ": " + s);
                }
            }
        }
        assertEquals(1, cell.elements().getFirst().count(), "a cell's count is its cells, not copies");
    }

    @Test
    void aWreckCarriesItsTemplatesShapeAndItsOwnDebris() {
        SetPieceSpec wreck = spec("{\"kind\": \"wreck\", \"id\": \"minecraft:end_city/ship\", \"tilt\": \"nose\", \"trench\": 6, \"debris\": 10}");
        JsonObject params = wreck.elements().getFirst().params();
        assertEquals("z", params.get("long").getAsString());
        assertEquals(14, params.get("half").getAsInt());
        List<SetPiecePlan.Placement> parts = flatten(wreck, 2);
        assertEquals(1, parts.stream().filter(p -> p.kind().equals("template")).count());
        assertEquals("nose", parts.stream().filter(p -> p.kind().equals("template")).findFirst().orElseThrow().source().string("tilt", ""));
        assertEquals(10, parts.stream().filter(p -> Assemblies.TEMPLATE_BLOCK.equals(p.block())).count(), "debris drawn from the ship's own blocks");
        assertTrue(parts.stream().anyMatch(p -> "minecraft:air".equals(p.block()) && p.source().params().has("follow")), "a trench is carved");
        assertTrue(refusal("{\"kind\": \"wreck\", \"id\": \"minecraft:end_city/ship\", \"tilt\": \"upside\"}").contains("tilt"));
        assertTrue(refusal("{\"kind\": \"wreck\", \"id\": \"minecraft:end_city/ship\", \"processors\": \"minecraft:no_such_list\"}").contains("processor list"));
        int[] reach = SetPieceSpec.reach(wreck.elements().getFirst(), VANILLA);
        assertTrue(reach[1] > 24, "a nose tilt reaches higher and lower than the level ship: " + reach[1]);
        String tooSmall = assertThrows(IllegalArgumentException.class, () -> SetPieceSpec.resolve("the_test", "ship",
                JsonParser.parseString("{\"size\": 16, \"elements\": [{\"kind\": \"wreck\", \"id\": \"minecraft:end_city/ship\", \"tilt\": \"nose\"}]}")
                        .getAsJsonObject(), Map.of(), null, VANILLA)).getMessage();
        assertTrue(tooSmall.contains("the_test: setPieces.ship") && tooSmall.contains("past its size 16"), tooSmall);
    }

    @Test
    void aSkeletonChainsSpinesToASkull() {
        SetPieceSpec bones = spec("{\"kind\": \"skeleton\", \"length\": 3, \"curve\": 15}");
        List<SetPiecePlan.Placement> t = flatten(bones, 4).stream().filter(p -> p.kind().equals("template")).toList();
        assertEquals(4, t.size());
        assertEquals("minecraft:fossil/skull_1", t.getLast().source().string("id", ""));
        assertTrue(t.stream().allMatch(p -> p.source().params().has("follow")), "each bone lies on its own ground");
    }

    @Test
    void hovelsAndWorksBuildFromTheirPalettes() {
        SetPieceSpec hovel = spec("{\"kind\": \"hovel\", \"width\": 2, \"state\": \"living\", \"rotation\": \"none\"}",
                "\"palette\": {\"walls\": [\"minecraft:blackstone\"], \"roof\": [\"minecraft:nether_wart_block\"]}, ");
        Map<List<Integer>, String> b = blocks(hovel, 1);
        assertTrue(b.containsValue("minecraft:blackstone") && b.containsValue("minecraft:nether_wart_block"));
        assertTrue(b.values().stream().anyMatch(s -> s.startsWith("minecraft:spruce_door[")), "a living hovel has its door");
        SetPieceSpec abandoned = spec("{\"kind\": \"hovel\", \"state\": \"abandoned\", \"rotation\": \"none\"}");
        assertTrue(blocks(abandoned, 1).values().stream().noneMatch(s -> s.contains("_door")), "an abandoned one has lost it");
        SetPieceSpec works = spec("{\"kind\": \"works\", \"columns\": 2, \"height\": 9, \"lines\": 1, \"machines\": 3, \"slag\": 4, \"rotation\": \"none\"}");
        Map<List<Integer>, String> w = blocks(works, 1);
        assertEquals(2, w.values().stream().filter(s -> s.startsWith("minecraft:campfire[lit=true,signal_fire=true")).count(), "a smoking hearth a chimney");
        assertTrue(w.containsValue("minecraft:cut_copper") && w.containsValue("minecraft:water"), "a pipe run and a quench pool");
        assertTrue(w.values().stream().anyMatch(Assemblies.SLAG::contains), "a slag field");
    }

    @Test
    void theSameSeedExpandsTheSameAndAnotherDiffers() {
        SetPieceSpec camp = spec("{\"kind\": \"camp\", \"state\": \"abandoned\", \"tents\": 3}");
        assertEquals(blocks(camp, 41), blocks(camp, 41));
        assertNotEquals(blocks(camp, 41), blocks(camp, 42));
    }

    @Test
    void rotatedStatesTurnTheirFacingAxisAndSides() {
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", Assemblies.rotateState("minecraft:oak_stairs[facing=north,half=bottom]", 1));
        assertEquals("minecraft:oak_log[axis=z]", Assemblies.rotateState("minecraft:oak_log[axis=x]", 3));
        assertEquals("minecraft:white_banner[rotation=8]", Assemblies.rotateState("minecraft:white_banner[rotation=4]", 1));
        String fence = Assemblies.rotateState("minecraft:oak_fence[north=true,east=false,south=false,west=false]", 1);
        assertTrue(fence.contains("east=true") && fence.contains("north=false"), fence);
    }

    // ---- things and items -------------------------------------------------------------------

    @Test
    void thingsAreCheckedAgainstTheGameData() {
        assertTrue(refusal("{\"kind\": \"mob\", \"entity\": \"minecraft:dragonfly\"}").contains("not a vanilla entity type"));
        assertTrue(refusal("{\"kind\": \"mob\", \"entity\": \"minecraft:iron_golem\", \"attributes\": {\"luck_of_the_draw\": 3}}").contains("attribute"));
        assertTrue(refusal("{\"kind\": \"mob\", \"entity\": \"minecraft:iron_golem\", \"effects\": [{\"id\": \"minecraft:flying\"}]}").contains("mob effect"));
        assertTrue(refusal("{\"kind\": \"chest\", \"loot\": \"minecraft:chests/simple_dungeon\", \"arrangement\": [{\"slot\": 27, \"item\": \"minecraft:compass\"}]}").contains("slot"));
        assertTrue(refusal("{\"kind\": \"archaeology\", \"loot\": \"minecraft:nothing/here\"}").contains("not a vanilla loot table"));
        assertTrue(refusal("{\"kind\": \"camp\", \"stores\": [{\"loot\": \"minecraft:nothing/here\"}]}").contains("not a vanilla loot table"));
        assertTrue(refusal("{\"kind\": \"banner\", \"color\": \"mauve\"}").contains("colour"));
        assertTrue(refusal("{\"kind\": \"banner\", \"color\": \"black\", \"patterns\": [{\"pattern\": \"minecraft:snout\", \"color\": \"yellow\"}]}").contains("banner pattern"));
        assertTrue(refusal("{\"kind\": \"stand\", \"equipment\": {\"hat\": \"minecraft:golden_helmet\"}}").contains("hat"));
        assertTrue(refusal("{\"kind\": \"frame\", \"item\": \"minecraft:unobtainium\"}").contains("not an item"));
        assertTrue(refusal("{\"kind\": \"pot\", \"sherds\": {\"front\": \"minecraft:stick\"}}").contains("sherd"));
        assertTrue(refusal("{\"kind\": \"farm\", \"crop\": \"rice\"}").contains("crop"));
        assertTrue(refusal("{\"kind\": \"dragon\"}").contains("kind"));
        SetPieceSpec ok = spec("""
                {"kind": "mob", "entity": "minecraft:iron_golem", "name": "Old Iron", "count": 1, "attributes": {"max_health": 200, "scale": 1.5}},
                {"kind": "remains", "pose": "seated", "wearer": "skeleton", "gear": {"head": {"id": "minecraft:golden_helmet", "trim": {"pattern": "snout", "material": "gold"}}}},
                {"kind": "chest", "arrangement": [{"slot": 4, "item": {"id": "minecraft:candle", "count": 3}}]}""");
        assertEquals(3, ok.elements().size());
        List<SetPiecePlan.Placement> parts = flatten(ok, 1);
        assertTrue(parts.stream().anyMatch(p -> p.kind().equals("mob") && p.source().params().get("entity").getAsString().equals("minecraft:skeleton")
                && p.source().params().get("persistent").getAsBoolean()), "remains worn by a persistent skeleton");
        assertEquals(ok.toJson(), SetPieceSpec.fromJson(ok.toJson()).toJson(), "the spec round-trips with its things");
    }

    @Test
    void itemsCarryTheirComponentsCheckedAtCompile() {
        ItemSpec helmet = ItemSpec.read("x", JsonParser.parseString(
                "{\"id\": \"minecraft:iron_helmet\", \"trim\": {\"pattern\": \"sentry\", \"material\": \"copper\"}}"), VANILLA::known);
        assertEquals("minecraft:sentry", helmet.trimPattern());
        assertEquals(0x3355AA, ItemSpec.read("x", JsonParser.parseString("{\"id\": \"leather_boots\", \"dye\": \"#3355AA\"}"), VANILLA::known).dye());
        assertEquals(0xB02E26, ItemSpec.read("x", JsonParser.parseString("{\"id\": \"leather_boots\", \"dye\": \"red\"}"), VANILLA::known).dye());
        ItemSpec map = ItemSpec.read("x", JsonParser.parseString(
                "{\"id\": \"minecraft:filled_map\", \"map\": {\"scale\": 1, \"decorations\": [{\"type\": \"red_x\", \"x\": 5, \"z\": -9}]}}"), VANILLA::known);
        assertEquals(1, map.decorations().size());
        assertEquals(map, ItemSpec.fromJson(map.toJson()));
        assertEquals(helmet, ItemSpec.fromJson(helmet.toJson()));
        ItemSpec.Ids ids = VANILLA::known;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ItemSpec.read("x", JsonParser.parseString(
                "{\"id\": \"minecraft:stick\", \"trim\": {\"pattern\": \"snout\", \"material\": \"gold\"}}"), ids)).getMessage().contains("armour"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ItemSpec.read("x", JsonParser.parseString(
                "{\"id\": \"minecraft:iron_helmet\", \"trim\": {\"pattern\": \"zigzag\", \"material\": \"gold\"}}"), ids)).getMessage().contains("trim pattern"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ItemSpec.read("x", JsonParser.parseString(
                "{\"id\": \"minecraft:iron_helmet\", \"dye\": \"red\"}"), ids)).getMessage().contains("leather"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ItemSpec.read("x", JsonParser.parseString(
                "{\"id\": \"minecraft:compass\", \"map\": {}}"), ids)).getMessage().contains("filled_map"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ItemSpec.read("x", JsonParser.parseString(
                "{\"id\": \"minecraft:filled_map\", \"map\": {\"decorations\": [{\"type\": \"mansion\", \"x\": 0, \"z\": 0}]}}"), ids)).getMessage().contains("decoration"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ItemSpec.read("x", JsonParser.parseString(
                "{\"id\": \"minecraft:compass\", \"name\": \"The Way\"}"), ids)).getMessage().contains("not an item field"), "items carry no names");
    }

    // ---- the words check (G20) --------------------------------------------------------------

    @Test
    void theWordsCheckRefusesDimensionsAndPeoples() {
        Set<String> slugs = VANILLA.dimensions();
        assertEquals("the gilded pit", Words.named("Down past the Gilded Pit, I think", slugs));
        assertEquals("the_aviary", Words.named("see the_aviary notes", slugs));
        assertEquals("aviary", Words.named("birds of an aviary", slugs));
        assertEquals("piglins", Words.named("The piglins came at dusk.", slugs));
        assertEquals("witch", Words.named("a WITCH lived here", slugs));
        assertEquals("endermen", Words.named("endermen watched", slugs));
        assertNull(Words.named("It was the end of it.", slugs), "the End's core is a common word and is not refused");
        assertEquals("End", Words.named("Beyond the End, they said.", slugs), "but capitalised it names the dimension");
        assertNull(Words.named("The frame holds, but it will not wake.", slugs));
        assertNull(Words.named("humanity", slugs), "whole words only");
        assertTrue(refusal("{\"kind\": \"lectern\", \"lines\": [\"The villagers left it here.\"]}").contains("RB-43"));
        assertTrue(refusal("{\"kind\": \"lectern\", \"title\": \"Of the Boneyard\", \"lines\": [\"quiet\"]}").contains("boneyard"));
        assertTrue(refusal("{\"kind\": \"lectern\", \"pool\": [\"fine\", \"the illagers knew\"], \"pick\": 1}").contains("illagers"));
        assertTrue(refusal("{\"kind\": \"mob\", \"entity\": \"minecraft:iron_golem\", \"name\": \"Guard of the Gilded Pit\"}").contains("gilded pit"));
        assertTrue(refusal("{\"kind\": \"gate\", \"state\": \"attempt\", \"study\": {\"lines\": [\"humans built this\"]}}").contains("humans"));
        assertTrue(refusal("{\"kind\": \"lectern\", \"lines\": []}").contains("1 to"));
    }

    // ---- tilt and re-skin -------------------------------------------------------------------

    @Test
    void aNoseTiltLowersTheFarEndAndLeavesNoGaps() {
        Tilt t = Tilt.of("nose", null, 13, 24, 29);
        assertEquals(35, t.angle());
        int[] nose = t.target(6, 0, 28);
        int[] tail = t.target(6, 0, 0);
        assertTrue(nose[1] < tail[1] - 10, "the nose drops: " + nose[1] + " against " + tail[1]);
        int[] b = t.bounds();
        int filled = 0;
        for (int x = 0; x < 13; x++) for (int y = 0; y < 24; y++) for (int z = 0; z < 29; z++) {
            int[] p = t.target(x, y, z);
            assertTrue(p[0] >= b[0] && p[1] >= b[1] && p[2] >= b[2] && p[0] <= b[3] && p[1] <= b[4] && p[2] <= b[5]);
            int[] back = t.source(p[0], p[1], p[2]);
            if (back != null && Math.abs(back[0] - x) + Math.abs(back[1] - y) + Math.abs(back[2] - z) <= 1) filled++;
        }
        assertTrue(filled > 13 * 24 * 29 * 0.95, filled + " of " + 13 * 24 * 29 + " blocks map back to themselves or a neighbour");
        Tilt side = Tilt.of("side", 25.0, 13, 24, 29);
        assertEquals(28, side.target(0, 0, 28)[2], "a side tilt keeps the long axis");
        assertTrue(side.target(12, 0, 5)[1] < side.target(0, 0, 5)[1], "and lowers its +x side");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Tilt.of("flip", null, 1, 1, 1)).getMessage().contains("tilt"));
    }

    @Test
    void aReskinSwapsByIdTagAndMaterialsAndWeathers() {
        JsonObject materials = JsonParser.parseString("{\"filler\": \"minecraft:blackstone\", \"top\": \"minecraft:crimson_nylium\"}").getAsJsonObject();
        List<Reskin> rules = Reskin.read("the_test", JsonParser.parseString("""
                {"#minecraft:village": {"swap": {"minecraft:cobblestone": "materials.filler", "#minecraft:logs": "minecraft:crimson_stem"}, "age": "old"},
                 "landmarks": {"swap": {"minecraft:oak_planks": "minecraft:crimson_planks"}}}"""), materials, id -> !id.equals("minecraft:unobtainium"));
        assertEquals(2, rules.size());
        Reskin village = rules.getFirst();
        assertEquals("minecraft:blackstone", village.swap().get("minecraft:cobblestone"));
        assertEquals("minecraft:blackstone", village.apply("minecraft:cobblestone", (tag, id) -> false, 0.99, 0.99));
        assertEquals("minecraft:crimson_stem", village.apply("minecraft:oak_log", (tag, id) -> tag.equals("minecraft:logs"), 0.99, 0.99));
        assertEquals("minecraft:mossy_stone_bricks", village.apply("minecraft:stone_bricks", (tag, id) -> false, 0.0, 0.99), "old weathering");
        assertEquals(rules, rules.stream().map(r -> Reskin.fromJson(r.toJson())).toList());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Reskin.read("the_test", JsonParser.parseString(
                "{\"minecraft:igloo\": {\"swap\": {\"minecraft:snow_block\": \"materials.cliffs\"}}}"), materials, id -> true)).getMessage().contains("not set"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Reskin.read("the_test", JsonParser.parseString(
                "{\"minecraft:igloo\": {\"swap\": {\"minecraft:snow_block\": \"minecraft:unobtainium\"}}}"), materials, id -> !id.equals("minecraft:unobtainium")))
                .getMessage().contains("not a vanilla block"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Reskin.read("the_test", JsonParser.parseString(
                "{\"minecraft:igloo\": {}}"), materials, id -> true)).getMessage().contains("swap or an age"));
    }

    @Test
    void anAgesArchaeologyRisesWithItsDepth() {
        assertEquals(0, Age.RECENT.archaeology());
        assertTrue(Age.OLD.archaeology() < Age.ANCIENT.archaeology() && Age.ANCIENT.archaeology() < Age.DEEP.archaeology());
        assertEquals("minecraft:archaeology/trail_ruins_rare", Age.DEEP.archaeologyLoot());
    }
}
