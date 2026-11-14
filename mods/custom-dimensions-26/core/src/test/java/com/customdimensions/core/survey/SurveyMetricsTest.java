package com.customdimensions.core.survey;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link SurveyMetrics}: the structure census, spawn relief, ground by height band and colour luminance. */
class SurveyMetricsTest {

    @Test
    void theCensusCountsSitesInsideTheBorderByRingFromSpawn() {
        List<SurveyMetrics.Site> sites = List.of(
                new SurveyMetrics.Site("minecraft:village_plains", 8, 8),
                new SurveyMetrics.Site("minecraft:village_plains", 300, 0),
                new SurveyMetrics.Site("minecraft:village_plains", 0, -950),
                new SurveyMetrics.Site("minecraft:village_plains", 990, 990),
                new SurveyMetrics.Site("minecraft:village_plains", 2000, 0),
                new SurveyMetrics.Site("minecraft:stronghold", -500, 0));
        JsonObject c = SurveyMetrics.census(sites, 0, 0, 1000);
        JsonObject v = c.getAsJsonObject("minecraft:village_plains");
        assertEquals(4, v.get("count").getAsInt(), "the site past the border is left out");
        assertEquals(11, v.get("nearestBlocks").getAsInt());
        assertEquals("[1,1,0,0,2]", v.get("byRing").toString(), "a corner site past the last ring counts in it");
        assertEquals(Math.round((Math.hypot(8, 8) + 300 + 950 + Math.hypot(990, 990)) / 4), v.get("meanBlocks").getAsLong());
        assertEquals("[0,0,1,0,0]", c.getAsJsonObject("minecraft:stronghold").get("byRing").toString());
        JsonObject moved = SurveyMetrics.census(sites, 300, 0, 1000);
        assertEquals(0, moved.getAsJsonObject("minecraft:village_plains").get("nearestBlocks").getAsInt(), "distances are from spawn");
        assertEquals(List.of(200, 400, 600, 800, 1000), SurveyMetrics.ringBounds(1000));
    }

    @Test
    void reliefIsTheSurfaceAboveTheMedianWithTheVoidLeftOut() {
        double[] h = {60, 70, 80, Double.NaN, Double.NaN};
        assertEquals(10.0, SurveyMetrics.heightAboveMedian(80, h));
        assertEquals(-10.0, SurveyMetrics.heightAboveMedian(60, h));
        assertNull(SurveyMetrics.heightAboveMedian(Double.NaN, h));
        assertNull(SurveyMetrics.heightAboveMedian(60, new double[]{Double.NaN}));

        Placeness p = new Placeness(3, 16, -16, -16);
        double[] heights = {64, 64, 64, 64, 90, 64, 64, 64, Double.NaN};
        JsonObject placeness = p.toJson(new int[]{0, 0, 0, 0, 0, 0, 0, 0, -1}, k -> "minecraft:plains", new int[9], k -> "plains",
                heights, null, new int[]{16, 16});
        p.addRelief(placeness, heights);
        assertEquals(26.0, placeness.getAsJsonObject("at").getAsJsonObject("centre").get("heightAboveMedianBlocks").getAsDouble());
        assertFalse(placeness.getAsJsonObject("at").getAsJsonObject("spawn").has("heightAboveMedianBlocks"), "a void cell has no relief");
    }

    @Test
    void groundIsSharedOutByHeightThirds() {
        int[] heights = new int[90];
        String[] grounds = new String[90];
        for (int i = 0; i < 90; i++) {
            heights[i] = i;
            grounds[i] = i < 30 ? "minecraft:sand" : i < 60 ? (i % 2 == 0 ? "minecraft:grass_block" : "minecraft:dirt") : "minecraft:stone";
        }
        JsonObject b = SurveyMetrics.groundByBand(heights, grounds, 8);
        assertEquals(100.0, b.getAsJsonObject("low").get("minecraft:sand").getAsDouble(), 2);
        assertEquals(50.0, b.getAsJsonObject("middle").get("minecraft:grass_block").getAsDouble(), 4);
        assertEquals(100.0, b.getAsJsonObject("high").get("minecraft:stone").getAsDouble(), 2);
        assertEquals(2, b.getAsJsonArray("bounds").size());
        assertEquals(0, SurveyMetrics.groundByBand(new int[0], new String[0], 8).size());
    }

    @Test
    void luminanceRunsFromBlackToWhite() {
        assertEquals(1.0, SurveyMetrics.luminance("#ffffff"));
        assertEquals(0.0, SurveyMetrics.luminance("000000"));
        assertEquals(0.216, SurveyMetrics.luminance("#808080"), 0.001);
        assertEquals(0.0722, SurveyMetrics.luminance("#0000ff"), 0.001);
        assertEquals(1.0, SurveyMetrics.luminance("#80ffffff"), "an alpha byte is ignored");
        assertNull(SurveyMetrics.luminance("red"));
        assertNull(SurveyMetrics.luminance(null));
    }
}
