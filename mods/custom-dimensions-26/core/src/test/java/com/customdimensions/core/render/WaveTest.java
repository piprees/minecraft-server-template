package com.customdimensions.core.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WaveTest {
    @Test
    void radiusIsTheLargerBorderRoundedToSixteen() {
        assertEquals(4096, WavePlan.radius(4096, 2048, 1024));
        assertEquals(2048, WavePlan.radius(256, 2048, 1024));
        assertEquals(688, WavePlan.radius(683, 683, 1024));
        assertEquals(1024, WavePlan.radius(null, null, 1024));
        assertEquals(512, WavePlan.radius(null, 512, 1024));
        assertEquals(RenderGrid.WIDE_MAX_RADIUS, WavePlan.radius(40000, null, 1024));
        assertEquals(RenderGrid.MIN_RADIUS, WavePlan.radius(3, null, 1024));
    }

    @Test
    void passesHalveTheStrideFromAbout512Pixels() {
        WavePlan p = WavePlan.of(8192, 2048);
        assertEquals(List.of(new WavePlan.Pass(1, 32, 512), new WavePlan.Pass(2, 16, 1024), new WavePlan.Pass(3, 8, 2048)), p.passes());
        assertEquals(512L * 512 + 1024L * 1024 + 2048L * 2048, p.columns());
        WavePlan q = WavePlan.of(4096, 2048);
        assertEquals(List.of(new WavePlan.Pass(1, 16, 512), new WavePlan.Pass(2, 8, 1024), new WavePlan.Pass(3, 4, 2048)), q.passes());
    }

    @Test
    void oddBordersRoundTheStrideUpAndStopAtOne() {
        WavePlan p = WavePlan.of(688, 2048);
        assertEquals(List.of(new WavePlan.Pass(1, 3, 459), new WavePlan.Pass(2, 2, 688), new WavePlan.Pass(3, 1, 1376)), p.passes());
        WavePlan small = WavePlan.of(16, 2048);
        assertEquals(List.of(new WavePlan.Pass(1, 1, 32)), small.passes());
        WavePlan odd = WavePlan.of(1008, 4096);
        assertEquals(4, odd.passes().getFirst().stride());
        assertEquals(504, odd.passes().getFirst().side());
        assertEquals(1, odd.passes().getLast().stride());
        for (WavePlan.Pass pass : odd.passes()) assertTrue(pass.side() * pass.stride() >= 2 * 1008);
    }

    @Test
    void noPassPassesTheWideSide() {
        WavePlan p = WavePlan.of(16384, 8192);
        for (WavePlan.Pass pass : p.passes()) assertTrue(pass.side() <= RenderGrid.WIDE_MAX_SIDE);
        assertEquals(8, p.passes().getLast().stride());
        assertThrows(IllegalArgumentException.class, () -> WavePlan.of(8, 2048));
        assertThrows(IllegalArgumentException.class, () -> WavePlan.of(1024, 2048, 512));
    }

    private static long[] histogram(int lo, int hi, int[][] spans, long count) {
        long[] h = new long[hi - lo + 1];
        for (int[] s : spans) for (int y = s[0]; y <= s[1]; y++) h[y - lo] += count;
        return h;
    }

    private static List<String> names(List<SliceChoice.Slice> slices) {
        return slices.stream().map(s -> s.name() + "@" + s.y()).toList();
    }

    @Test
    void aSurfaceWorldGetsSeaLevelCavesAndDeepCaves() {
        var p = new SliceChoice.Profile(-64, 319, 63, 1000, 1000, 70, 0, 0, null, histogram(-64, 319, new int[][] {{-64, 70}}, 1000));
        assertEquals(List.of("sea-level@62", "caves@11", "deep-caves@-48"), names(SliceChoice.choose(p)));
    }

    @Test
    void aShallowSurfaceWorldGetsNoCaveSlices() {
        var p = new SliceChoice.Profile(0, 127, 32, 100, 100, 40, 0, 0, null, histogram(0, 127, new int[][] {{0, 40}}, 100));
        assertEquals(List.of("sea-level@31"), names(SliceChoice.choose(p)));
    }

    @Test
    void aRoofedWorldGetsCeilingCavesAndDeepCaves() {
        var p = new SliceChoice.Profile(0, 255, 32, 1000, 1000, 90, 0.9, 0, 200,
                histogram(0, 255, new int[][] {{0, 90}, {200, 255}}, 900));
        assertEquals(List.of("ceiling@200", "caves@145", "deep-caves@16", "sea-level@31").stream().sorted().toList(),
                names(SliceChoice.choose(p)).stream().sorted().toList());
        assertEquals("ceiling", SliceChoice.choose(p).getFirst().name());
    }

    @Test
    void aTieredSkyWorldGetsEachBandsMiddleTopFirst() {
        long[] h = histogram(0, 255, new int[][] {{40, 60}, {120, 150}, {200, 210}}, 300);
        var p = new SliceChoice.Profile(0, 255, -64, 1000, 600, 150, 0, 0.6, null, h);
        List<SliceChoice.Band> bands = SliceChoice.bands(p);
        assertEquals(3, bands.size());
        assertEquals(210, bands.getFirst().top());
        assertEquals(List.of("sky-1@205", "sky-2@135", "sky-3@50"), names(SliceChoice.choose(p)));
    }

    @Test
    void smallGapsJoinABandAndThinBandsAreDropped() {
        long[] h = histogram(0, 255, new int[][] {{100, 110}, {113, 130}}, 300);
        h[20] = 4;
        var p = new SliceChoice.Profile(0, 255, 0, 1000, 300, 130, 0, 0.3, null, h);
        List<SliceChoice.Band> bands = SliceChoice.bands(p);
        assertEquals(1, bands.size());
        assertEquals(100, bands.getFirst().bottom());
        assertEquals(130, bands.getFirst().top());
    }

    @Test
    void touchingTiersSplitAtTheirValley() {
        long[] h = new long[256];
        for (int y = 70; y <= 190; y++) h[y] = 30 + Math.max(0, 300 - 10 * Math.abs(y - 100)) + Math.max(0, 300 - 10 * Math.abs(y - 160));
        var p = new SliceChoice.Profile(0, 255, 0, 1000, 400, 150, 0, 0.4, null, h);
        List<SliceChoice.Band> bands = SliceChoice.bands(p);
        assertEquals(2, bands.size());
        assertTrue(Math.abs(bands.get(0).middle() - 160) <= 3, bands.toString());
        assertTrue(Math.abs(bands.get(1).middle() - 100) <= 3, bands.toString());
        assertTrue(bands.get(1).top() < bands.get(0).bottom());
    }

    @Test
    void runMiddlesSeparateTiersThatSolidCountsSmear() {
        long[] solid = new long[256];
        for (int y = 40; y <= 250; y++) solid[y] = 2000;
        long[] middles = new long[256];
        for (int y = 120; y <= 150; y++) middles[y] = 80 - 4L * Math.abs(y - 135);
        for (int y = 190; y <= 215; y++) middles[y] = 50 - 3L * Math.abs(y - 202);
        middles[60] = 1;
        var p = new SliceChoice.Profile(0, 255, 0, 10000, 8000, 180, 0, 0.5, null, solid, middles);
        assertEquals(List.of("sky-1@202", "sky-2@135"), names(SliceChoice.choose(p)));
        var flat = new SliceChoice.Profile(0, 255, 0, 10000, 8000, 180, 0, 0.5, null, solid);
        assertEquals(1, SliceChoice.bands(flat).size());
    }

    @Test
    void aVoidGetsOnlySeaLevelWhenItIsInRange() {
        var p = new SliceChoice.Profile(0, 255, 63, 1000, 0, null, 0, 0, null, new long[256]);
        assertEquals(List.of("sea-level@62"), names(SliceChoice.choose(p)));
        var q = new SliceChoice.Profile(0, 255, 0, 1000, 0, null, 0, 0, null, new long[256]);
        assertTrue(SliceChoice.choose(q).isEmpty());
    }

    @Test
    void nearbyHeightsKeepTheFirstChosen() {
        var p = new SliceChoice.Profile(-64, 319, -45, 1000, 1000, 70, 0, 0, null, histogram(-64, 319, new int[][] {{-64, 70}}, 1000));
        assertEquals(List.of("caves@11", "sea-level@-46"), names(SliceChoice.choose(p)));
    }

    @Test
    void theSheetHoldsEveryPanelAndTheLegend() {
        int[] red = new int[64 * 64];
        java.util.Arrays.fill(red, 0xFF0000);
        var sheet = ContactSheet.compose("test", List.of(new ContactSheet.Panel("map", 64, red), new ContactSheet.Panel("b", 64, red)),
                List.of(new ContactSheet.Entry(0x00FF00, "x"), new ContactSheet.Entry(-1, "y")));
        assertEquals(sheet.width() * sheet.height(), sheet.rgb().length);
        int x = ContactSheet.GAP + 10;
        int y = ContactSheet.GAP + 2 * ContactSheet.TITLE + 10;
        assertEquals(0xFF0000, sheet.rgb()[y * sheet.width() + x]);
        int[] scaled = ContactSheet.scale(new int[] {0x000000, 0xFEFEFE, 0x000000, 0xFEFEFE}, 2, 1);
        assertEquals(0x7F7F7F, scaled[0]);
        assertEquals(TinyFont.width("ab", 2), 22);
    }
}
