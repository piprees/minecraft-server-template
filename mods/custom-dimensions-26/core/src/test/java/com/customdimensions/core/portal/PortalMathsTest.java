package com.customdimensions.core.portal;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.Direction;
import com.customdimensions.core.config.PortalDefinition;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Decay, scaling, arrival sites and the links file. */
class PortalMathsTest {

    @Test
    void decayMapResolution() {
        assertEquals("minecraft:crying_obsidian", PortalDecay.resolve("minecraft:obsidian", null));
        assertEquals("minecraft:gold_block", PortalDecay.resolve("minecraft:obsidian", Map.of("minecraft:obsidian", "minecraft:gold_block")));
        assertEquals("minecraft:air", PortalDecay.resolve("minecraft:oak_planks", null));
        assertEquals("minecraft:stripped_oak_log", PortalDecay.resolve("minecraft:oak_log", null));
        assertNull(PortalDecay.resolve("minecraft:stripped_oak_log", null));
        assertNull(PortalDecay.resolve("minecraft:cobblestone", null));
        assertNull(PortalDecay.resolve(null, null));
    }

    @Test
    void partialPicksAreStableAndDistinct() {
        long seed = PortalDecay.partialSeed(1, 64, -3);
        List<Integer> a = PortalDecay.pickPartialIndices(10, seed);
        assertEquals(a, PortalDecay.pickPartialIndices(10, seed));
        assertTrue(a.size() >= 1 && a.size() <= 2);
        assertEquals(a.size(), a.stream().distinct().count());
        assertEquals(List.of(0), PortalDecay.pickPartialIndices(1, seed));
        assertTrue(PortalDecay.pickPartialIndices(0, seed).isEmpty());
    }

    @Test
    void scalingDividesOnTheWayInAndMultipliesOut() {
        assertEquals(236, PortalScaling.scale(1888, 1.0, 8.0));
        assertEquals(1888, PortalScaling.scale(236, 8.0, 1.0));
        assertEquals(-125, PortalScaling.scale(-1000, 1.0, 8.0));
        assertEquals(500, PortalScaling.scale(1000, 4.0, 8.0));
        assertEquals(77, PortalScaling.scale(77, 2.0, 2.0));
        Set<BlockPos> interior = ArrivalSite.standardInterior(800, 64, -160, Direction.Axis.X);
        assertArrayEquals(new int[]{100, -20}, PortalScaling.arrivalColumn(interior, 1.0, 8.0));
    }

    /** A column of solid ground up to {@code ground}, with an optional roof from {@code roofFrom} up. */
    record Columnar(int ground, int roofFrom, int bedrockTo) implements ArrivalSite.Probe {
        @Override
        public boolean isClear(BlockPos pos) {
            return pos.getY() > ground && pos.getY() < roofFrom;
        }

        @Override
        public boolean isOpaque(BlockPos pos) {
            return !isClear(pos);
        }

        @Override
        public boolean isCarveable(BlockPos pos) {
            return pos.getY() > bedrockTo;
        }
    }

    @Test
    void openSiteSitsOnTheGround() {
        ArrivalSite.Column column = new ArrivalSite.Column(-64, 320, false);
        assertEquals(71, ArrivalSite.findOpenY(column, 0, 0, Direction.Axis.X, 71, new Columnar(70, 1000, -64)));
    }

    @Test
    void ceilingedColumnsSearchUnderTheRoof() {
        ArrivalSite.Column column = new ArrivalSite.Column(0, 256, true);
        // Ground to 40, open to 120, roof above: the heightmap would say 256.
        int y = ArrivalSite.findOpenY(column, 0, 0, Direction.Axis.Z, 256, new Columnar(40, 120, 0));
        assertEquals(41, y);
    }

    @Test
    void carveWhenNothingIsOpenAndRefuseOnBedrock() {
        ArrivalSite.Column column = new ArrivalSite.Column(0, 128, true);
        Columnar solid = new Columnar(200, 200, 4);
        assertEquals(ArrivalSite.NO_SITE, ArrivalSite.findOpenY(column, 0, 0, Direction.Axis.X, 120, solid));
        assertNotEquals(ArrivalSite.NO_SITE, ArrivalSite.findCarveY(column, 0, 0, Direction.Axis.X, 120, solid));
        Columnar bedrock = new Columnar(200, 200, 1000);
        assertEquals(ArrivalSite.NO_SITE, ArrivalSite.findCarveY(column, 0, 0, Direction.Axis.X, 120, bedrock));
    }

    @Test
    void egressIsBothSidesOfTheOpening() {
        Set<BlockPos> interior = ArrivalSite.standardInterior(0, 64, 0, Direction.Axis.X);
        Set<BlockPos> egress = ArrivalSite.egressCells(interior, Direction.Axis.X, 1);
        assertEquals(12, egress.size());
        assertTrue(egress.contains(new BlockPos(0, 64, 1)) && egress.contains(new BlockPos(1, 66, -1)));
        assertEquals(18, ArrivalSite.arrivalCells(interior, Direction.Axis.X).size());
        assertTrue(ArrivalSite.isClear(false, true, false));
        assertFalse(ArrivalSite.isClear(true, false, true));
    }

    @Test
    void linksFileRoundTripsAndKeepsForeignRecords(@TempDir Path dir) throws Exception {
        PortalDefinition def = new PortalDefinition("the_ashgrove", "minecraft:tuff", "minecraft:amethyst_shard",
                "adventure:the_ashgrove", "7B8B6B", 11);
        def.setSingleUse(true);
        Set<BlockPos> interior = ArrivalSite.standardInterior(4, 70, 9, Direction.Axis.Z);
        PortalLinksFile.Contents contents = new PortalLinksFile.Contents(
                List.of(new PortalLinksFile.ReturnLink("adventure:the_ashgrove", new BlockPos(1, 2, 3),
                        "minecraft:overworld", 70, 0x8844FF, 40, null, "bed", 4, 9)),
                List.of(new PortalLinksFile.Zone(false, "minecraft:overworld", "adventure:the_ashgrove",
                        Direction.Axis.Z, def, interior, 120)),
                List.of(JsonParser.parseString("{\"recordType\":\"future-record-v1\",\"world\":\"minecraft:overworld\"}")),
                List.of());
        Path file = dir.resolve("portal_links.json");
        PortalLinksFile.write(file, contents);
        PortalLinksFile.Contents back = PortalLinksFile.read(file);
        assertEquals(contents.links(), back.links());
        assertEquals(1, back.zones().size());
        PortalLinksFile.Zone zone = back.zones().get(0);
        assertEquals(interior, zone.interior());
        assertEquals(120, zone.singleUseTicksLeft());
        assertTrue(zone.definition().isSingleUse());
        assertEquals("minecraft:tuff", zone.definition().getFrameBlock());
        assertEquals(1, back.other().size());
        assertTrue(back.dropped().isEmpty());
    }

    @Test
    void aFileSavedByTheOneTwentyOneModLoads() {
        String saved = """
                [
                  {"x": 10, "y": 70, "z": -4, "targetWorld": "minecraft:overworld", "sourceY": 68, "color": 8930559,
                   "cooldown": 40, "portalWorld": "adventure:the_boneyard", "sourceX": 80, "sourceZ": -32},
                  {"x": 1, "y": 2, "z": 3, "targetWorld": "minecraft:overworld"},
                  {"recordType": "source-zone-v1", "sourceWorld": "minecraft:overworld",
                   "targetWorld": "adventure:the_boneyard", "axis": "X",
                   "definition": {"id": "the_boneyard", "frameBlock": "minecraft:bone_block",
                                  "igniterItem": "minecraft:flint_and_steel", "targetDimension": "adventure:the_boneyard",
                                  "color": "D8C8A0", "lightLevel": 11, "scale": 8.0, "cooldown": 40},
                   "interior": [{"x": 80, "y": 68, "z": -32}, {"x": 81, "y": 68, "z": -32}]},
                  {"recordType": "source-zone-v1", "sourceWorld": "not an id!", "targetWorld": "a:b", "axis": "X",
                   "definition": {}, "interior": [{"x": 0, "y": 0, "z": 0}]},
                  {"recordType": "future-record-v1", "world": "minecraft:overworld"}
                ]""";
        PortalLinksFile.Contents c = PortalLinksFile.parse(JsonParser.parseString(saved));
        assertEquals(2, c.links().size());
        assertNull(c.links().get(1).portalWorld());
        assertEquals(68, c.links().get(0).sourceY());
        assertEquals(1, c.zones().size());
        assertEquals(8.0, c.zones().get(0).definition().getScale());
        assertEquals(1, c.dropped().size());
        assertEquals(1, c.other().size());
    }

    @Test
    void anUnparseableFileReadsAsEmptyWithAReason(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("portal_links.json");
        Files.writeString(file, "{ not json");
        PortalLinksFile.Contents c = PortalLinksFile.read(file);
        assertTrue(c.zones().isEmpty() && c.links().isEmpty());
        assertEquals(1, c.dropped().size());
        assertTrue(PortalLinksFile.read(dir.resolve("absent.json")).dropped().isEmpty());
    }
}
