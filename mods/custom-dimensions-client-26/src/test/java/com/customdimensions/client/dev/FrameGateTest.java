package com.customdimensions.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameGateTest {
    private static FrameGate.Measures good() {
        return new FrameGate.Measures(60, "minecraft:grass_block", 3, 30, 0, 0, 0, 110, 10);
    }

    private static String only(FrameGate.Measures m, boolean empty) {
        List<String> r = FrameGate.reasons(m, empty);
        assertEquals(1, r.size(), r.toString());
        return r.get(0);
    }

    @Test
    void aClearDaylitFramePasses() {
        assertTrue(FrameGate.reasons(good(), false).isEmpty());
    }

    @Test
    void eachRuleRejectsOnItsOwnAndNamesItsNumbers() {
        assertTrue(only(new FrameGate.Measures(1.5, "minecraft:cobweb", 80, 0, 0, 0, 0, 140, 0), false).startsWith("occluded: median ray 1.5"));
        assertTrue(only(new FrameGate.Measures(9, "minecraft:stone", 46, 0, 0, 0, 0, 90, 0), false).startsWith("occluded: minecraft:stone 46.0%"));
        assertTrue(only(new FrameGate.Measures(512, "none", 0, 70, 15, 0, 0, 90, 0), false).startsWith("empty: sky 70.0% + void 15.0%"));
        assertTrue(only(new FrameGate.Measures(200, "none", 0, 10, 0, 0, 0, 90, 61), false).startsWith("fog wall: 61.0%"));
        assertTrue(only(new FrameGate.Measures(60, "none", 0, 0, 0, 0, 0, 29.9, 0), false).startsWith("dark: mean luma 29.9"));
        assertTrue(only(new FrameGate.Measures(200, "none", 0, 30, 0, 0, 2.1, 120, 0), false).startsWith("border: 2.1%"));
    }

    @Test
    void thresholdsAreExclusive() {
        assertTrue(FrameGate.reasons(new FrameGate.Measures(4, "minecraft:stone", 45, 75, 0, 0, 2, 30, 60), false).isEmpty());
    }

    @Test
    void anEmptyViewIsSparedOnlyWhenMeantToBeOne() {
        FrameGate.Measures m = new FrameGate.Measures(512, "none", 0, 40, 50, 0, 0, 100, 0);
        assertEquals(1, FrameGate.reasons(m, false).size());
        assertTrue(FrameGate.reasons(m, true).isEmpty());
    }

    @Test
    void aVoidFeatureMayBeMostlyVoidButMustHitBlocks() {
        FrameGate.Measures crater = new FrameGate.Measures(400, "none", 0, 20, 60, 0, 0, 100, 0);
        assertTrue(only(crater, false).startsWith("empty: sky 20.0% + void 60.0%"));
        assertTrue(FrameGate.reasons(crater, false, true).isEmpty());
        List<String> bare = FrameGate.reasons(new FrameGate.Measures(500, "none", 0, 10, 80, 0, 0, 100, 0), false, true);
        assertEquals(List.of("void: 10.0% of rays hit blocks < 15% (void 80.0%)"), bare);
        assertTrue(FrameGate.reasons(new FrameGate.Measures(500, "none", 0, 5, 80, 0, 0, 100, 0), false, true).isEmpty());
    }

    @Test
    void aVoidFeatureStillCountsSkyAndUnloadedAsEmpty() {
        List<String> r = FrameGate.reasons(new FrameGate.Measures(512, "none", 0, 70, 10, 10, 0, 100, 0), false, true);
        assertEquals(List.of("empty: sky 70.0% + unloaded 10.0% > 75%"), r);
    }

    @Test
    void aPairIsKeptWhenEitherFramePasses() {
        assertTrue(FrameGate.keepsPair(List.of("dark: mean luma 9.0 < 30"), List.of()));
        assertTrue(FrameGate.keepsPair(List.of(), List.of("fog wall: 82.0% of pixels within 24 of the fog colour")));
        assertTrue(FrameGate.keepsPair(List.of(), List.of()));
        assertTrue(!FrameGate.keepsPair(List.of("dark: mean luma 9.0 < 30"), List.of("fog wall: 82.0%")));
    }

    @Test
    void pairAndVoidFeatureAreReadAndInherited(@TempDir File dir) throws Exception {
        File list = new File(dir, "list.json");
        Files.writeString(list.toPath(), "[{\"dimension\":\"adventure:a\",\"name\":\"a-t1\",\"x\":1,\"y\":\"70\",\"z\":2,"
                + "\"pair\":true,\"voidFeature\":true,\"alternates\":[{\"yaw\":90}]},"
                + "{\"dimension\":\"adventure:a\",\"name\":\"a-t2\",\"x\":1,\"y\":\"70\",\"z\":2}]");
        List<BiomeShots.Shot> shots = BiomeShots.read(list);
        assertTrue(shots.get(0).pair() && shots.get(0).voidFeature());
        assertTrue(shots.get(0).alternates().get(0).pair() && shots.get(0).alternates().get(0).voidFeature());
        assertTrue(!shots.get(1).pair() && !shots.get(1).voidFeature());
    }

    @Test
    void imageMeasuresLumaAndFogShare() {
        int w = 320, h = 180;
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            px[i] = i % w < w / 2 ? 0xFFC03020 : 0xFF000000;
        }
        double[] m = FrameGate.image(px, w, h, 0xC83228);
        assertEquals(50, m[1], 1);
        assertEquals((0.2126 * 0xC0 + 0.7152 * 0x30 + 0.0722 * 0x20) / 2, m[0], 1);
        assertEquals(0, FrameGate.image(px, w, h, 0x00FF00)[1], 0.01);
    }

    @Test
    void borderDistanceFollowsTheRayToTheNearestEdge() {
        assertEquals(24, FrameGate.borderDistance(1000, 0, 1, 0, 0, 0, 1024), 1e-9);
        assertEquals(2024, FrameGate.borderDistance(1000, 0, -1, 0, 0, 0, 1024), 1e-9);
        assertEquals(Math.sqrt(2) * 24, FrameGate.borderDistance(1000, 1000, Math.sqrt(0.5), Math.sqrt(0.5), 0, 0, 1024), 1e-6);
        assertEquals(0, FrameGate.borderDistance(2000, 0, 1, 0, 0, 0, 1024), 1e-9);
    }

    @Test
    void alternatesInheritTheEntryAndReplaceItsCamera(@TempDir File dir) throws Exception {
        File list = new File(dir, "list.json");
        Files.writeString(list.toPath(), "[{\"dimension\":\"adventure:a\",\"name\":\"a-far\",\"x\":1,\"y\":\"surface+40\",\"z\":2,"
                + "\"at\":[9,9,9],\"radius\":24,\"hold\":45,\"empty\":true,"
                + "\"alternates\":[{\"x\":5,\"z\":6,\"yaw\":90,\"pitch\":30},{\"at\":[0,64,0]}]}]");
        List<BiomeShots.Shot> shots = BiomeShots.read(list);
        assertEquals(1, shots.size());
        BiomeShots.Shot shot = shots.get(0);
        assertEquals(2, shot.alternates().size());
        BiomeShots.Shot alt = shot.alternates().get(0);
        assertEquals("a-far", alt.name());
        assertEquals(5, alt.x());
        assertEquals("surface+40", alt.y());
        assertEquals(90, alt.yaw());
        assertEquals(null, alt.at());
        assertEquals(24, alt.radius());
        assertTrue(alt.empty());
        assertEquals(0, shot.alternates().get(1).at()[1] - 64, 0);
        assertEquals(1, shot.alternates().get(1).x());
    }
}
