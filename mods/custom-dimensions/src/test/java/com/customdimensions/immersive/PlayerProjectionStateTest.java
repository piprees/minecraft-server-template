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

    // ---- what the aperture pass paints ----------------------------------

    /**
     * The one definition of the level painted over an opening. A diagnostic
     * that recomputed it could report a light the projector is not painting.
     */
    @Test
    void theApertureCarriesTheConfiguredLevel() {
        assertEquals(11, PlayerProjectionState.apertureLightLevel(portal(11)));
        assertEquals(15, PlayerProjectionState.apertureLightLevel(portal(15)));
    }

    /** Zero paints nothing at all — the aperture is left as it stands. */
    @Test
    void zeroPaintsNothing() {
        assertEquals(0, PlayerProjectionState.apertureLightLevel(portal(0)));
    }

    /** A zone whose definition has left the config paints nothing, never a default. */
    @Test
    void aZoneWithNoDefinitionPaintsNothing() {
        assertEquals(0, PlayerProjectionState.apertureLightLevel(null));
    }

    /** {@code Properties.LEVEL_15} throws outside 0..15, so the clamp is load-bearing. */
    @Test
    void theLevelIsClampedToWhatALightBlockCanHold() {
        assertEquals(15, PlayerProjectionState.apertureLightLevel(portal(99)));
        assertEquals(0, PlayerProjectionState.apertureLightLevel(portal(-4)));
    }

    private static com.customdimensions.config.PortalDefinition portal(int lightLevel) {
        return new com.customdimensions.config.PortalDefinition("the_crimson_nexus",
                "minecraft:nether_bricks", "minecraft:flint_and_steel",
                "adventure:the_crimson_nexus", "AF2B2B", lightLevel);
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

    // ---- the companion fork: who describes the far side ------------------

    /**
     * A client drawing the destination itself is sent no description of it, on
     * any pass. Every other reason to rebuild is subordinate to this one — a
     * full pass and a side flip both stay silent.
     */
    @Test
    void aClientDrawingItsOwnFarSideIsNeverSentOne() {
        assertFalse(PlayerProjectionState.companionRebuildDue(false, true, true, true, 500, 0, 20));
        assertFalse(PlayerProjectionState.companionRebuildDue(false, false, false, false, 500, 0, 20));
    }

    @Test
    void aClientTakingTheSlabIsSentOneOnEveryUsualTrigger() {
        assertTrue(PlayerProjectionState.companionRebuildDue(true, true, false, false, 0, 0, 20),
                "a full pass did not rebuild");
        assertTrue(PlayerProjectionState.companionRebuildDue(true, false, true, false, 0, 0, 20),
                "a side flip did not rebuild");
        assertTrue(PlayerProjectionState.companionRebuildDue(true, false, false, true, 0, 0, 20),
                "a client holding nothing was left holding nothing");
        assertTrue(PlayerProjectionState.companionRebuildDue(true, false, false, false, 20, 0, 20),
                "the cadence elapsed and nothing was rebuilt");
    }

    @Test
    void betweenCadencesNothingIsRebuilt() {
        assertFalse(PlayerProjectionState.companionRebuildDue(true, false, false, false, 19, 0, 20));
    }

    /**
     * Toggling the local view ON leaves a description already on that client,
     * describing the same space it is now drawing itself. It has to be
     * withdrawn, and only while there is one to withdraw.
     */
    /**
     * A destination the client has no {@code DimensionType} for cannot be
     * stood up there, so that portal keeps its slab whatever the player asked
     * for. Per portal, not per player: the same client's other portals are
     * unaffected.
     */
    @Test
    void aPortalWithNoFrameKeepsItsSlabHoweverTheToggleIsSet() {
        assertTrue(PlayerProjectionState.streamsSlab(true, false),
                "a portal that could not be described was silenced anyway");
        assertFalse(PlayerProjectionState.streamsSlab(true, true));
        assertTrue(PlayerProjectionState.streamsSlab(false, true));
        assertTrue(PlayerProjectionState.streamsSlab(false, false));
    }

    @Test
    void aDescriptionAlreadySentIsWithdrawnWhenTheClientTakesOver() {
        assertTrue(PlayerProjectionState.companionPayloadStale(false, true));
        assertFalse(PlayerProjectionState.companionPayloadStale(false, false),
                "a clear was sent for a projection the client never had");
        assertFalse(PlayerProjectionState.companionPayloadStale(true, true));
    }
}
