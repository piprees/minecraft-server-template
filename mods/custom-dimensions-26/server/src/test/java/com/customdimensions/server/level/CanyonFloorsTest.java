package com.customdimensions.server.level;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The terrain self-test's canyon floor measures over hand-made base-height scans. */
class CanyonFloorsTest {

    /** A 13 by 13 scan of land at {@code land} with a channel along z at column 6 whose floor steps from y 88 down to {@code low}. */
    private static TerrainSelfTest.Scan stepped(int land, int low) {
        int n = 13;
        int[][] h = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                h[i][j] = land;
            }
        }
        for (int j = 0; j < n; j++) {
            h[6][j] = Math.max(low, 88 - 8 * (j / 3));
        }
        return new TerrainSelfTest.Scan(96, 16, h, 0, -64);
    }

    @Test
    void floorsArePointsWellBelowBothNeighboursAcrossOneAxis() {
        List<int[]> floors = TerrainSelfTest.floorPoints(stepped(110, 64), 3);
        assertEquals(List.of(80, 80, 80, 72, 72, 72, 64), floors.stream().map(p -> p[2]).toList(),
                "the channel's points 3 or more scan steps from the edge");
        assertEquals(List.of(0, -48), floors.stream().limit(1).flatMap(p -> java.util.stream.Stream.of(p[0], p[1])).toList(),
                "each point carries its block x and z");
    }

    @Test
    void shallowCutsAndOpenOceanAreNotFloors() {
        assertEquals(List.of(64), TerrainSelfTest.floorPoints(stepped(76, 64), 3).stream().map(p -> p[2]).toList(),
                "cuts of 4 blocks or less are not floors; 12 is");
        int[][] sea = new int[13][13];
        for (int[] row : sea) java.util.Arrays.fill(row, 40);
        sea[6][6] = 20;
        assertEquals(0, TerrainSelfTest.floorPoints(new TerrainSelfTest.Scan(96, 16, sea, 0, -64), 3).size(), "no land around it");
    }

    @Test
    void dryShareCountsTheFloorsWaterDoesNotCover() {
        assertEquals(0.75, TerrainSelfTest.dryShare(List.of(new TerrainSelfTest.Floor(64, false), new TerrainSelfTest.Floor(63, false),
                new TerrainSelfTest.Floor(63, true), new TerrainSelfTest.Floor(90, false))));
        assertEquals(0, TerrainSelfTest.dryShare(List.of()));
    }

    @Test
    void histogramListsEachHeightWithItsCount() {
        assertEquals("63:1 64:2 72:1", TerrainSelfTest.histogram(List.of(64, 72, 63, 64)));
        assertEquals("", TerrainSelfTest.histogram(List.of()));
    }

    @Test
    void floorLevelsAreTheCrowdedHeightsSteppedApart() {
        List<Integer> floors = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            for (int r = 0; r < 20; r++) floors.add(64 + 8 * k);
        }
        floors.add(70);
        floors.add(91);
        assertEquals(List.of(63, 71, 79, 87), TerrainSelfTest.floorLevels(floors), "a level is the lowest of its equal windows");
        List<Integer> sloped = new ArrayList<>();
        for (int y = 64; y < 96; y++) sloped.add(y);
        assertEquals(List.of(), TerrainSelfTest.floorLevels(sloped), "an even slope has no levels");
        assertEquals(List.of(), TerrainSelfTest.floorLevels(List.of()));
    }
}
