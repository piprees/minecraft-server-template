package com.customdimensions.core.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.survey.PatchAnalysis.ColumnClass;
import com.customdimensions.core.survey.PatchAnalysis.Layer;
import com.customdimensions.core.survey.PatchGrid.BlockInfo;
import com.customdimensions.core.survey.PatchGrid.Kind;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurveyTest {
    static final BlockInfo STONE = new BlockInfo("minecraft:stone", Kind.SOLID, false, null, false, true, null);
    static final BlockInfo GRASS_BLOCK = new BlockInfo("minecraft:grass_block", Kind.SOLID, false, null, false, false, null);
    static final BlockInfo SNOW = new BlockInfo("minecraft:snow", Kind.PLANT, false, null, true, false, null);
    static final BlockInfo WATER = new BlockInfo("minecraft:water", Kind.FLUID, false, null, false, false, "water");
    static final BlockInfo LOG = new BlockInfo("minecraft:oak_log", Kind.FOLIAGE, true, null, false, false, null);
    static final BlockInfo LEAVES = new BlockInfo("minecraft:oak_leaves", Kind.FOLIAGE, false, null, false, false, null);
    static final BlockInfo GLOW = new BlockInfo("minecraft:glowstone", Kind.SOLID, false, null, false, false, null);
    static final BlockInfo SHORT_GRASS = new BlockInfo("minecraft:short_grass", Kind.PLANT, false, "grass", false, false, null);

    // ---- statistics -----------------------------------------------------------------------------

    @Test
    void percentilesAndNearestNeighbours() {
        double[] v = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(1, Stats.percentile(v, 5));
        assertEquals(5, Stats.percentile(v, 50));
        assertEquals(10, Stats.percentile(v, 100));
        assertTrue(Double.isNaN(Stats.percentile(new double[0], 50)));
        assertEquals(10, Stats.meanNearestNeighbour(List.of(new double[] {0, 0}, new double[] {10, 0}, new double[] {20, 0})));
        assertTrue(Double.isNaN(Stats.meanNearestNeighbour(List.of(new double[] {0, 0}))));
    }

    @Test
    void clustersAreTwentySixConnected() {
        int w = 10;
        BitSet cells = new BitSet();
        cells.set(idx(0, 0, 0, w));
        cells.set(idx(1, 1, 1, w));
        cells.set(idx(8, 8, 8, w));
        List<Stats.Cluster> found = Stats.clusters(cells, w, w, w);
        assertEquals(2, found.size());
        assertEquals(2, found.stream().mapToInt(Stats.Cluster::size).max().getAsInt());
    }

    private static int idx(int x, int y, int z, int w) {
        return (y * w + z) * w + x;
    }

    @Test
    void slopeClassesAndCliffs() {
        int side = 5;
        int[] flat = new int[side * side];
        assertEquals(9, Stats.slopes(flat, side).flat());
        int[] wall = new int[side * side];
        for (int z = 0; z < side; z++) for (int x = 3; x < side; x++) wall[z * side + x] = 20;
        Stats.Slopes s = Stats.slopes(wall, side);
        assertTrue(s.cliff() > 0);
        assertTrue(s.steep() > 0);
    }

    @Test
    void kMedoidsSeparatesTwoGroupsAndFlagsAnOutlier() {
        List<double[]> num = new ArrayList<>();
        List<String[]> cat = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            num.add(new double[] {i % 3 * 0.01, 0});
            cat.add(new String[] {"plains"});
        }
        for (int i = 0; i < 20; i++) {
            num.add(new double[] {1 + i % 3 * 0.01, 1});
            cat.add(new String[] {"forest"});
        }
        num.add(new double[] {0.5, 0.5});
        cat.add(new String[] {"crater"});
        double[][] d = KMedoids.gower(num.toArray(double[][]::new), cat.toArray(String[][]::new));
        KMedoids.Result r = KMedoids.cluster(d, 6);
        assertEquals(2, r.k());
        assertEquals(r.assignment()[0], r.assignment()[19]);
        assertTrue(r.assignment()[0] != r.assignment()[20]);
        assertTrue(r.silhouette() > 0.5);
        assertEquals(List.of(40), KMedoids.outliers(r).subList(0, 1));
    }

    @Test
    void aUniformSetIsOneType() {
        double[][] num = new double[12][1];
        String[][] cat = new String[12][1];
        for (int i = 0; i < 12; i++) cat[i][0] = "plains";
        assertEquals(1, KMedoids.cluster(KMedoids.gower(num, cat), 6).k());
    }

    // ---- plan and landforms ---------------------------------------------------------------------

    @Test
    void theStratifiedPlanIsReproducibleAndInsideTheBorder() {
        List<SurveyPlan.Patch> a = SurveyPlan.stratified(42, 1024, 24, 3);
        List<SurveyPlan.Patch> b = SurveyPlan.stratified(42, 1024, 24, 3);
        assertEquals(a, b);
        assertEquals(24, a.size());
        for (SurveyPlan.Patch p : a) {
            assertTrue(p.blockX() >= -1024 && p.blockX() + p.side() <= 1024, p.toString());
            assertTrue(p.blockZ() >= -1024 && p.blockZ() + p.side() <= 1024, p.toString());
        }
        assertFalse(a.equals(SurveyPlan.stratified(43, 1024, 24, 3)));
    }

    @Test
    void aFlatWorldIsPlainsAndAPeakIsAMountainWithAnAdaptivePatch() {
        int side = 64;
        int[] h = new int[side * side];
        byte[] kind = new byte[side * side];
        java.util.Arrays.fill(h, 64);
        Landforms flat = new Landforms(side, 16, -512, -512, h, kind);
        assertEquals(100.0, flat.share(Landforms.Form.PLAINS));
        int[] peak = h.clone();
        for (int z = 28; z < 36; z++) for (int x = 28; x < 36; x++) peak[z * side + x] = 64 + 200 - 10 * (Math.abs(x - 32) + Math.abs(z - 32));
        Landforms hilly = new Landforms(side, 16, -512, -512, peak, kind);
        assertTrue(hilly.share(Landforms.Form.MOUNTAINS) > 0);
        assertTrue(hilly.share(Landforms.Form.MOUNTAINS) < Landforms.RARE_SHARE);
        List<Landforms.Pick> picks = hilly.picks();
        assertTrue(picks.stream().anyMatch(p -> p.reason().equals("rare mountains")), picks.toString());
        List<SurveyPlan.Patch> plan = new ArrayList<>(SurveyPlan.stratified(1, 512, 4, 3));
        assertFalse(SurveyPlan.addAdaptive(plan, picks, 512, 3, 4).isEmpty());
        int[] v = hilly.vista();
        assertEquals(264, v[1]);
    }

    @Test
    void aSmallLandmassInWaterIsAnIsland() {
        int side = 40;
        int[] h = new int[side * side];
        byte[] kind = new byte[side * side];
        java.util.Arrays.fill(kind, (byte) 1);
        for (int z = 18; z < 22; z++) for (int x = 18; x < 22; x++) kind[z * side + x] = 0;
        Landforms l = new Landforms(side, 16, 0, 0, h, kind);
        assertEquals(Landforms.Form.ISLANDS, l.form(20, 20));
        assertEquals(Landforms.Form.WATER, l.form(0, 0));
    }

    @Test
    void voidHolesAreCountedAndSized() {
        int side = 64;
        int[] h = new int[side * side];
        byte[] kind = new byte[side * side];
        java.util.Arrays.fill(h, 80);
        for (int z = 10; z < 30; z++) for (int x = 10; x < 30; x++) kind[z * side + x] = 2;
        for (int z = 50; z < 52; z++) for (int x = 50; x < 52; x++) kind[z * side + x] = 2;
        com.google.gson.JsonObject v = new Landforms(side, 8, 0, 0, h, kind).voidHolesJson();
        assertEquals(2, v.get("count").getAsInt());
        assertEquals(1, v.get("countOver1Ha").getAsInt());
        assertEquals(99.0, v.get("largestPercentOfVoid").getAsDouble());
        assertEquals(181, v.get("largestDiameterBlocks").getAsInt());
        com.google.gson.JsonObject none = new Landforms(side, 8, 0, 0, h, new byte[side * side]).voidHolesJson();
        assertEquals(0, none.get("count").getAsInt());
        assertEquals(0.0, none.get("largestPercentOfVoid").getAsDouble());
    }

    @Test
    void aSheerLipFallsAwayToGentleGroundFarFromTheVoid() {
        int side = 64, step = 8;
        int[] h = new int[side * side];
        byte[] kind = new byte[side * side];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int i = z * side + x;
                if (x < 4) kind[i] = 2;
                else if (x < 8) h[i] = 200 - 20 * (x - 4);
                else h[i] = 120 - x;
            }
        }
        com.google.gson.JsonObject rim = new Landforms(side, step, 0, 0, h, kind).voidRimJson();
        assertTrue(rim.get("nearSteepPercent").getAsDouble() > 75, rim.toString());
        assertEquals(0.0, rim.get("farSteepPercent").getAsDouble(), rim.toString());
        assertTrue(rim.get("nearHeightP50").getAsDouble() > rim.get("farHeightP50").getAsDouble());
        assertEquals(0, new Landforms(side, step, 0, 0, new int[side * side], new byte[side * side]).voidRimJson().get("nearCells").getAsInt());
    }

    @Test
    void connectedRidgesAreOneRegionAndConesAreMany() {
        int side = 64;
        int[] ridges = new int[side * side], cones = new int[side * side];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                ridges[z * side + x] = (x % 8 == 0 || z % 8 == 0) ? 200 : 40;
                cones[z * side + x] = (x % 8 == 4 && z % 8 == 4) || (x % 8 == 5 && z % 8 == 4) ? 200 : 40;
            }
        }
        byte[] kind = new byte[side * side];
        com.google.gson.JsonObject web = new Landforms(side, 8, 0, 0, ridges, kind).highGroundJson().getAsJsonObject("p75");
        assertEquals(1, web.get("count").getAsInt(), web.toString());
        assertEquals(100.0, web.get("largestPercent").getAsDouble());
        com.google.gson.JsonObject spots = new Landforms(side, 8, 0, 0, cones, kind).highGroundJson(99);
        assertEquals(64, spots.get("count").getAsInt(), spots.toString());
        assertTrue(spots.get("largestPercent").getAsDouble() < 5, spots.toString());
        com.google.gson.JsonObject upper = new Landforms(side, 8, 0, 0, cones, kind).highGroundJson().getAsJsonObject("upperHalf");
        assertEquals(120.0, upper.get("thresholdY").getAsDouble(), upper.toString());
        assertEquals(64, upper.get("count").getAsInt(), upper.toString());
        assertEquals(80.0, new Landforms(side, 8, 0, 0, cones, kind).highGroundJson().getAsJsonObject("upperThreeQuarters").get("thresholdY").getAsDouble());
    }

    @Test
    void aCraterZoneSitsBelowItsRingWithOneTallSpire() {
        int side = 64, step = 8;
        int[] h = new int[side * side];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                double d = Math.hypot(x * step - 256, z * step - 256);
                h[z * side + x] = d < 2 ? 220 : d <= 128 ? 50 : 90;
            }
        }
        com.customdimensions.core.zone.ZoneSpec zone = com.customdimensions.core.zone.ZoneSpec.read("the_x", 0,
                com.google.gson.JsonParser.parseString("{\"shape\": \"circle\", \"centre\": [256, 256], \"radius\": 128}").getAsJsonObject(),
                new com.customdimensions.core.BlockPos(0, 64, 0));
        com.google.gson.JsonArray zones = new Landforms(side, step, 0, 0, h, new byte[side * side]).withZones(List.of(zone)).zonesJson();
        com.google.gson.JsonObject z0 = zones.get(0).getAsJsonObject();
        assertEquals(40.0, z0.get("depthBelowRing").getAsDouble(), z0.toString());
        assertEquals(170.0, z0.get("tallestRise").getAsDouble(), z0.toString());
        assertTrue(z0.get("risePercent").getAsDouble() < 1, z0.toString());
    }

    @Test
    void waterDepthIsMeasuredOverTheSurfaceBlock() {
        PatchAnalysis a = analyse(grid(0, false, (x, y, z) -> {
            int floor = x < 8 ? 59 : 50;
            if (y <= floor) return STONE;
            if (y <= 62) return WATER;
            return null;
        }));
        assertEquals(3, a.fluidDepth[a.column(2, 2)]);
        assertEquals(12, a.fluidDepth[a.column(12, 2)]);
        java.util.List<Integer> depths = new ArrayList<>();
        for (int i = 0; i < 9; i++) depths.add(2);
        depths.add(20);
        com.google.gson.JsonObject d = Survey.waterDepth(depths);
        assertEquals(10, d.get("columns").getAsInt());
        assertEquals(2.0, d.get("p50").getAsDouble());
        assertEquals(20, d.get("max").getAsInt());
        assertEquals(90.0, d.get("shallow1To4Percent").getAsDouble());
        assertEquals(10.0, d.get("over12Percent").getAsDouble());
        assertEquals(0, Survey.waterDepth(java.util.List.of()).get("columns").getAsInt());
    }

    // ---- patches --------------------------------------------------------------------------------

    /** A 16×16-column patch, 128 high from y 0, filled by {@code fill(x, y, z)} returning a block or null for air. */
    static PatchGrid grid(int index, boolean ceiling, Filler fill) {
        PatchGrid g = new PatchGrid(new SurveyPlan.Patch(index, index * 4, 0, 1, "stratified"), 0, 128, ceiling);
        int plains = g.biome("minecraft:plains");
        for (int qy = 0; qy < 32; qy++) for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) g.setBiome(qx, qy, qz, plains);
        for (int y = 0; y < 128; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockInfo b = fill.at(x, y, z);
            if (b != null) g.set(x, y, z, g.block(b));
        }
        return g;
    }

    interface Filler {
        BlockInfo at(int x, int y, int z);
    }

    static PatchAnalysis analyse(PatchGrid g) {
        return PatchAnalysis.of(g, Survey.COMMON, new int[] {40, 70});
    }

    @Test
    void aGrassPlainWithOneTreeIsASingleSurface() {
        PatchAnalysis a = analyse(grid(0, false, (x, y, z) -> {
            if (y < 63) return STONE;
            if (y == 63) return GRASS_BLOCK;
            if (x == 8 && z == 8 && y >= 64 && y < 69) return LOG;
            if (y >= 68 && y <= 70 && Math.abs(x - 8) <= 2 && Math.abs(z - 8) <= 2 && !(x == 8 && z == 8 && y < 69)) return LEAVES;
            if (y == 64 && x == 2) return SHORT_GRASS;
            return null;
        }));
        int c = a.column(3, 3);
        assertEquals(ColumnClass.SINGLE, ColumnClass.values()[a.columnClass[c]]);
        assertEquals(63, a.surface[c]);
        assertEquals("minecraft:grass_block", a.ground[c]);
        assertTrue(a.skyExposed[c]);
        assertEquals(1, a.trees.size());
        assertEquals("minecraft:oak_log", a.trees.get(0).species());
        assertEquals(Layer.SURFACE, a.trees.get(0).layer());
        int under = a.column(7, 8);
        assertTrue(a.canopy[under]);
        assertEquals("minecraft:oak_leaves", a.topBlock[under]);
        assertEquals("grass", a.plant[a.column(2, 5)]);
        assertTrue(a.cells.stream().anyMatch(cell -> cell.layer() == Layer.SURFACE && cell.camera() != null));
    }

    @Test
    void anIslandOverGroundIsStackedWithASkyLayer() {
        PatchAnalysis a = analyse(grid(0, false, (x, y, z) -> y <= 40 ? STONE : y >= 90 && y <= 95 && x < 8 && z >= 1 && z < 10 ? GRASS_BLOCK : null));
        int island = a.column(2, 2), open = a.column(12, 2);
        assertEquals(ColumnClass.STACKED, ColumnClass.values()[a.columnClass[island]]);
        assertEquals(40, a.surface[island]);
        assertEquals(95, a.skyTop[island]);
        assertFalse(a.skyExposed[island]);
        assertEquals(ColumnClass.SINGLE, ColumnClass.values()[a.columnClass[open]]);
        assertEquals(Layer.SKY, a.layer(2, 93, 2));
        assertTrue(a.cells.stream().anyMatch(cell -> cell.layer() == Layer.SKY));
    }

    @Test
    void aCeilingMakesARoofAndAnOverhangAndCaveAreFound() {
        PatchAnalysis roofed = analyse(grid(0, true, (x, y, z) -> y <= 30 || y >= 120 ? STONE : y == 116 && x == 4 ? GLOW : null));
        int c = roofed.column(5, 5);
        assertEquals(ColumnClass.ROOFED, ColumnClass.values()[roofed.columnClass[c]]);
        assertEquals(120, roofed.roofUnderside[c]);
        assertEquals(30, roofed.surface[c]);
        assertEquals(Layer.ROOF, roofed.layer(4, 116, 4));
        assertTrue(roofed.notable.get(Layer.ROOF).containsKey("minecraft:glowstone"));
        PatchAnalysis over = analyse(grid(0, false, (x, y, z) -> y <= 60 || (y >= 66 && y <= 70 && x < 8) ? STONE : y >= 20 && y <= 26 && x >= 8 ? null : null));
        assertTrue(over.overhang[over.column(2, 2)]);
        assertEquals(ColumnClass.OVERHANG, ColumnClass.values()[over.columnClass[over.column(2, 2)]]);
        PatchAnalysis cave = analyse(grid(0, false, (x, y, z) -> y <= 60 && !(y >= 20 && y <= 26) ? STONE : null));
        assertTrue(cave.cave[cave.column(5, 5)]);
        assertTrue(cave.undergroundAir[cave.column(5, 5)] >= 7);
        assertTrue(cave.cells.stream().anyMatch(cell -> cell.layer() == Layer.UNDERGROUND && cell.camera() != null));
        PatchAnalysis empty = analyse(grid(0, false, (x, y, z) -> null));
        assertEquals(ColumnClass.VOID, ColumnClass.values()[empty.columnClass[0]]);
    }

    @Test
    void aBigCaveUnderTheGroundIsNotASecondLayer() {
        PatchAnalysis a = analyse(grid(0, false, (x, y, z) -> y <= 70 && !(y >= 20 && y <= 45) ? STONE : null));
        int c = a.column(5, 5);
        assertFalse(a.stacked[c]);
        assertEquals(70, a.surface[c]);
        assertTrue(a.cave[c]);
        assertEquals(ColumnClass.CAVE, ColumnClass.values()[a.columnClass[c]]);
    }

    @Test
    void groundThatSpansThePatchOverACavernIsTheSurface() {
        PatchAnalysis a = analyse(grid(0, false, (x, y, z) -> y <= 20 || (y >= 60 && y <= 70 && x < 12) ? STONE : null));
        int c = a.column(4, 4);
        assertFalse(a.stacked[c]);
        assertEquals(70, a.surface[c]);
        PatchAnalysis sky = analyse(grid(0, false, (x, y, z) -> (y >= 10 && y <= 20) || (y >= 60 && y <= 70 && x < 12) ? STONE : null));
        assertTrue(sky.stacked[sky.column(4, 4)], "a wide island over another island, with void below both, is a sky layer");
        assertEquals(20, sky.surface[sky.column(4, 4)]);
    }

    @Test
    void aCaveOpenToTheSkyIsStillOneMassNotALayer() {
        PatchAnalysis a = analyse(grid(0, false, (x, y, z) -> y <= 70 && !(y >= 20 && y <= 45 && x >= 2) && !(x >= 12 && y > 20) ? STONE : null));
        int c = a.column(6, 6);
        assertFalse(a.stacked[c]);
        assertEquals(70, a.surface[c]);
    }

    @Test
    void notableClustersGetSpacingAndACamera() {
        PatchAnalysis a = analyse(grid(0, false, (x, y, z) -> {
            if (y <= 40) return STONE;
            if (y == 41 && ((x == 3 && z == 3) || (x == 12 && z == 12))) return GLOW;
            return null;
        }));
        List<PatchAnalysis.Found> glow = a.clusters.stream().filter(f -> f.id().equals("minecraft:glowstone")).toList();
        assertEquals(2, glow.size());
        assertNotNull(glow.get(0).camera());
    }

    // ---- dossier --------------------------------------------------------------------------------

    @Test
    void theDossierHasItsSectionsAndFlagsABiomeThatCannotExpressItself(@TempDir Path dir) throws Exception {
        List<PatchAnalysis> patches = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PatchGrid g = grid(i, false, (x, y, z) -> y < 50 ? STONE : y <= 62 ? WATER : null);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) g.setPrecipitation(x, z, 2);
            if (i == 0) {
                for (int x = 0; x < 16; x++) g.set(x, 30, 0, g.block(GLOW));
            }
            patches.add(analyse(g));
        }
        patches.add(analyse(grid(3, false, (x, y, z) -> y < 63 ? STONE : y == 63 ? GRASS_BLOCK : null)));
        List<SurveyPlan.Patch> plan = patches.stream().map(p -> p.patch).toList();
        Survey.Meta meta = new Survey.Meta("adventure:test", 1, 0, 128, 63, false, new JsonObject(), List.of("minecraft:plains", "minecraft:jungle"),
                Set.of(), plan, Map.of("total", 1L));
        JsonObject d = Survey.dossier(meta, null, patches, List.of(new Survey.Check(0, 0, 64, "minecraft:plains", 64, "minecraft:plains")));
        for (String s : List.of("vertical", "surface", "vegetation", "terrain", "underground", "notable", "clusters", "structures", "biomeExpression",
                "places", "crossCheck", "atmosphere")) {
            assertTrue(d.has(s), s);
        }
        JsonObject plains = d.getAsJsonObject("biomeExpression").getAsJsonObject("biomes").getAsJsonObject("minecraft:plains");
        assertTrue(plains.getAsJsonArray("flags").toString().contains("mostly water"), plains.toString());
        assertTrue(plains.getAsJsonArray("flags").toString().contains("snow cover under 5%"), plains.toString());
        assertEquals("[\"minecraft:jungle\"]", d.getAsJsonObject("biomeExpression").getAsJsonArray("listedNotFound").toString());
        assertEquals(75.0, d.getAsJsonObject("surface").getAsJsonObject("fluidCoverPercent").get("water").getAsDouble());
        assertEquals(0.0, d.getAsJsonObject("surface").get("snowIcePercent").getAsDouble());
        assertEquals(100.0, d.getAsJsonObject("crossCheck").get("heightWithin2Percent").getAsDouble());
        JsonObject byLayer = d.getAsJsonObject("vegetation").getAsJsonObject("treesPerHectareByLayer");
        assertEquals(0.0, byLayer.get("surface").getAsDouble(), byLayer.toString());
        assertEquals(4, byLayer.size());
        JsonObject depth = d.getAsJsonObject("surface").getAsJsonObject("waterDepth");
        assertEquals(13.0, depth.get("p50").getAsDouble(), depth.toString());
        Path json = Survey.write(dir, d, patches, null);
        String md = Files.readString(dir.resolve("dossier.md"));
        assertTrue(md.contains("## Summary") && md.contains("## Places") && md.contains("## Biome expression"), md);
        assertTrue(md.contains("water 75%"), md);
        assertTrue(Files.size(dir.resolve("layers/top.png")) > 0);
        assertTrue(Files.exists(dir.resolve("layers/slice_40.png")));
        assertTrue(Files.readString(json).contains("layerMaps"));
    }

    @Test
    void theDescriptionStatesNumbersOnly() {
        JsonObject dominant = new JsonObject();
        dominant.addProperty("biome", "minecraft:plains");
        String s = Survey.describe(List.of("height", "canopy"), new double[] {70, 0.8}, new double[] {70, 0.2}, new double[] {1, 0.3}, dominant, 5, 10);
        assertEquals("50.0% of cells; biome plains; height 70; canopy 80% (layer 20%)", s);
    }
}
