package com.customdimensions.immersive;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure decisions behind when a projection refreshes and what a slab
 * rebuild hands to the restore path.
 */
class PlayerProjectionStateTest {

    // ---- 4c: refresh throttling ----------------------------------------

    @Test
    void aMovingPlayerRefreshesOnTheConfiguredInterval() {
        // Moved a full block since the last pass: base interval applies.
        assertFalse(PlayerProjectionState.shouldRefresh(103, 100, 1.0, 4));
        assertTrue(PlayerProjectionState.shouldRefresh(104, 100, 1.0, 4));
        assertTrue(PlayerProjectionState.shouldRefresh(140, 100, 1.0, 4));
    }

    @Test
    void aStationaryPlayerRefreshesFourTimesLessOften() {
        assertFalse(PlayerProjectionState.shouldRefresh(104, 100, 0.0, 4));
        assertFalse(PlayerProjectionState.shouldRefresh(115, 100, 0.0, 4));
        assertTrue(PlayerProjectionState.shouldRefresh(116, 100, 0.0, 4));
    }

    @Test
    void theStationaryThresholdIsHalfABlock() {
        // 0.25 squared distance == 0.5 blocks: still stationary.
        assertFalse(PlayerProjectionState.shouldRefresh(108, 100, 0.25, 4));
        // A hair beyond it counts as movement and takes the base interval.
        assertTrue(PlayerProjectionState.shouldRefresh(108, 100, 0.2501, 4));
    }

    @Test
    void aProjectionWithNoBaselineRefreshesImmediately() {
        // needsRefresh passes MAX_VALUE when there is no last eye position —
        // the base interval, never the stretched one.
        assertTrue(PlayerProjectionState.shouldRefresh(104, 100, Double.MAX_VALUE, 4));
        assertFalse(PlayerProjectionState.shouldRefresh(103, 100, Double.MAX_VALUE, 4));
    }

    @Test
    void aDegenerateIntervalStillMakesProgress() {
        // ImmersiveSettings clamps refreshInterval to >= 2, but a throttle
        // that could return false forever is not worth the risk.
        assertTrue(PlayerProjectionState.shouldRefresh(101, 100, 1.0, 0));
        assertTrue(PlayerProjectionState.shouldRefresh(104, 100, 0.0, 0));
        assertFalse(PlayerProjectionState.shouldRefresh(100, 100, 1.0, 0));
    }

    // ---- the aperture is an overlay, not part of the slab ---------------

    /** The arrival aperture in adventure:the_crucible, 2 wide. */
    private static final Set<BlockPos> APERTURE = Set.of(
            new BlockPos(59, 365, 50), new BlockPos(60, 365, 50));

    @Test
    void aSlabRebuildNeverCarriesTheAperture() {
        // An arrival's aperture cells hold REAL nether portal blocks. Carried
        // into staleOutsideVolume they are queued for restore, and the restore
        // hands the client back the purple the overlay exists to hide.
        Set<BlockPos> faked = new HashSet<>(APERTURE);
        faked.add(new BlockPos(59, 365, 51));
        faked.add(new BlockPos(60, 365, 51));

        Set<BlockPos> carried = PlayerProjectionState.slabCarryOver(faked, APERTURE);

        assertTrue(Collections.disjoint(carried, APERTURE),
                "the aperture must never reach the restore path");
        assertEquals(Set.of(new BlockPos(59, 365, 51), new BlockPos(60, 365, 51)), carried);
    }

    @Test
    void aSlabRebuildCarriesEverythingElse() {
        Set<BlockPos> faked = new HashSet<>();
        for (int z = 51; z <= 58; z++) {
            faked.add(new BlockPos(59, 365, z));
        }
        assertEquals(faked, PlayerProjectionState.slabCarryOver(faked, APERTURE));
    }

    @Test
    void aSourceZoneCarriesItsWholeSlab() {
        // A source portal's interior holds no portal blocks, so apertureState
        // leaves it alone and nothing is exempt from the carry-over.
        Set<BlockPos> faked = Set.of(new BlockPos(235, 145, 201), new BlockPos(236, 145, 201));
        assertEquals(faked, PlayerProjectionState.slabCarryOver(faked, Set.of()));
    }

    @Test
    void theCarryOverCopies() {
        // send() removes from lastSent while iterating the result; a view onto
        // the same set would be a ConcurrentModificationException.
        Set<BlockPos> faked = new HashSet<>(APERTURE);
        faked.add(new BlockPos(59, 365, 51));

        Set<BlockPos> carried = PlayerProjectionState.slabCarryOver(faked, APERTURE);
        carried.clear();

        assertEquals(3, faked.size());
        assertEquals(2, APERTURE.size());
    }
}
