package com.customdimensions.client.render;

import com.customdimensions.client.realtime.RealtimeView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two cost lines the rig is read through. They are windowed: a read
 * reports what happened since the last one and clears it, so a measurement is
 * zero the window, act, read.
 *
 * <p>Only the shape is covered. Nothing here can put a sample in a window —
 * both are written from the render and tick paths — so that a read CLEARS the
 * window is checked on the rig, not here.
 */
class CostLineTest {

    @Test
    void anEmptyMeshWindowSaysSoRatherThanReportingZeroTime() {
        ClientProjection.meshCost();
        assertEquals("meshes=0 min=n/a avg=n/a peak=n/a", ClientProjection.meshCost());
    }

    @Test
    void anEmptyBuildWindowSaysSoRatherThanReportingZeroTime() {
        RealtimeView.clear();
        RealtimeView.buildCost();
        String line = RealtimeView.buildCost();
        assertTrue(line.startsWith("slices=0 min=n/a avg=n/a peak=n/a"), line);
    }

    /** Both lines carry min beside peak: one reading has to show its own spread. */
    @Test
    void bothLinesReportSpreadNotJustAnAverage() {
        ClientProjection.meshCost();
        RealtimeView.clear();
        RealtimeView.buildCost();
        for (String line : new String[] {ClientProjection.meshCost(), RealtimeView.buildCost()}) {
            assertTrue(line.contains("min="), line);
            assertTrue(line.contains("avg="), line);
            assertTrue(line.contains("peak="), line);
        }
    }
}
