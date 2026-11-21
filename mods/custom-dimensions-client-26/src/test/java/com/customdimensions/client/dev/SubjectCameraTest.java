package com.customdimensions.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class SubjectCameraTest {
    /** A column solid in the given inclusive ranges. */
    private static IntPredicate column(int[]... solid) {
        return y -> {
            for (int[] r : solid) {
                if (y >= r[0] && y <= r[1]) {
                    return true;
                }
            }
            return false;
        };
    }

    @Test
    void underARoofTheCameraStandsInTheCavernNotOnTheRoof() {
        // floor 0..40, cavern 41..99, roof 100..127: the heightmap would put the camera over 127.
        IntPredicate nether = column(new int[] {0, 40}, new int[] {100, 127});
        assertEquals(47, SubjectCamera.open(nether, 41, 0, 255, 6));
        assertEquals(97, SubjectCamera.open(nether, 41, 0, 255, 80), "kept under the roof");
    }

    @Test
    void aBaseInsideTerrainRisesToTheFirstSpaceWithRoom() {
        // the base height is inside a ledge 30..50; a two-block gap at 51..52 has no room; open cavern from 53.
        IntPredicate ledge = column(new int[] {0, 50}, new int[] {53, 53}, new int[] {90, 127});
        assertEquals(60, SubjectCamera.open(ledge, 35, 0, 255, 6));
    }

    @Test
    void underIslandsTheCameraStaysUnderTheIslandOverTheBasin() {
        // basin floor 0..60, water is not solid, an island 150..170 overhead.
        IntPredicate sky = column(new int[] {0, 60}, new int[] {150, 170});
        assertEquals(69, SubjectCamera.open(sky, 61, 0, 319, 8));
    }

    @Test
    void overVoidOrAChasmTheCameraKeepsToTheSubjectsHeight() {
        IntPredicate voidColumn = column(new int[] {300, 310});
        assertEquals(130, SubjectCamera.open(voidColumn, 120, 0, 319, 10));
        IntPredicate chasm = column(new int[] {0, 17}, new int[] {120, 127});
        assertEquals(86, SubjectCamera.open(chasm, 80, 0, 255, 6));
    }

    @Test
    void openSkyHasNoCeiling() {
        assertEquals(84, SubjectCamera.open(column(new int[] {-64, 63}), 64, -64, 319, 20));
    }

    @Test
    void solidAllTheWayIsNoPlace() {
        assertEquals(Integer.MIN_VALUE, SubjectCamera.open(column(new int[] {0, 255}), 40, 0, 255, 6));
    }

    @Test
    void raysCountWhenTheyStopAtTheBoxOrMeetNothing() {
        int[] box = {0, 40, 0, 15, 55, 15};
        List<double[]> points = SubjectCamera.points(box);
        assertEquals(9, points.size());
        assertEquals(8.0, points.get(0)[0]);
        assertEquals(48.0, points.get(0)[1]);
        assertTrue(SubjectCamera.reaches(null, box));
        assertTrue(SubjectCamera.reaches(new double[] {-1.0, 45, 3}, box), "stopped on the wall");
        assertFalse(SubjectCamera.reaches(new double[] {-10, 45, 3}, box), "stopped by a ridge in front");
        assertEquals(40.0 + 16.0 / 3.0, SubjectCamera.aim(box)[1], 1e-9);
    }
}
