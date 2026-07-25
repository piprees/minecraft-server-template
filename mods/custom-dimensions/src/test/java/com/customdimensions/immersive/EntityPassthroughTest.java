package com.customdimensions.immersive;

import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure decision logic behind Phase 3's entity pass-through: which entity
 * types may cross, the volume the scan queries, and the swept "did it actually
 * go through the doorway" test.
 *
 * <p>Everything asserted here is world-free by construction — that is the
 * point of keeping it out of {@code ServerWorldMixin}. The live paths
 * ({@code tick}, {@code tryReturnFromArrivalPortal}) are exercised on the real
 * server via RCON; what CAN be pinned headlessly is pinned here.
 */
class EntityPassthroughTest {

    // ------------------------------------------------------------------
    // Eligibility
    // ------------------------------------------------------------------

    @Test
    void itemsProjectilesOrbsAndFallingBlocksMayCross() {
        assertTrue(EntityPassthrough.isPassthroughType(ItemEntity.class));
        assertTrue(EntityPassthrough.isPassthroughType(ArrowEntity.class));
        assertTrue(EntityPassthrough.isPassthroughType(EnderPearlEntity.class));
        assertTrue(EntityPassthrough.isPassthroughType(ExperienceOrbEntity.class));
        assertTrue(EntityPassthrough.isPassthroughType(FallingBlockEntity.class));
    }

    @Test
    void playersAndLivingEntitiesNeverCross() {
        // Players have their own teleport path in ServerWorldMixin; mobs and
        // villagers break on a cross-dimension recreate (AI, leash, spawn
        // tracking). Armour stands are excluded for free by being living.
        assertFalse(EntityPassthrough.isPassthroughType(ServerPlayerEntity.class));
        assertFalse(EntityPassthrough.isPassthroughType(ZombieEntity.class));
        assertFalse(EntityPassthrough.isPassthroughType(VillagerEntity.class));
        assertFalse(EntityPassthrough.isPassthroughType(ArmorStandEntity.class));
    }

    @Test
    void vehiclesAndBlockAttachedEntitiesNeverCross() {
        assertFalse(EntityPassthrough.isPassthroughType(BoatEntity.class));
        assertFalse(EntityPassthrough.isPassthroughType(ItemFrameEntity.class));
    }

    @Test
    void theAllowListIsAllowListNotDenyList() {
        // Anything not explicitly named — including a modded entity we have
        // never heard of — stays put.
        assertFalse(EntityPassthrough.isPassthroughType(net.minecraft.entity.Entity.class));
        assertFalse(EntityPassthrough.isPassthroughType(null));
    }

    @Test
    void nullEntityIsNotEligible() {
        assertFalse(EntityPassthrough.isEligible(null));
    }

    // ------------------------------------------------------------------
    // Scan volume
    // ------------------------------------------------------------------

    @Test
    void boundsCoverEveryInteriorBlockInFull() {
        // A 1x2 doorway: the box must span each block's whole cube, not just
        // its corner, or an entity in the upper half of a block is missed.
        Box box = EntityPassthrough.boundsOf(Set.of(
                new BlockPos(10, 64, 20), new BlockPos(10, 65, 20)));
        assertNotNull(box);
        assertEquals(10.0, box.minX);
        assertEquals(64.0, box.minY);
        assertEquals(20.0, box.minZ);
        assertEquals(11.0, box.maxX);
        assertEquals(66.0, box.maxY);
        assertEquals(21.0, box.maxZ);
    }

    @Test
    void boundsHandleNegativeAndIrregularInteriors() {
        Box box = EntityPassthrough.boundsOf(Set.of(
                new BlockPos(-5, 60, -5), new BlockPos(-3, 62, -4)));
        assertNotNull(box);
        assertEquals(-5.0, box.minX);
        assertEquals(60.0, box.minY);
        assertEquals(-5.0, box.minZ);
        assertEquals(-2.0, box.maxX);
        assertEquals(63.0, box.maxY);
        assertEquals(-3.0, box.maxZ);
    }

    @Test
    void boundsOfNothingIsNull() {
        assertNull(EntityPassthrough.boundsOf(Set.of()));
        assertNull(EntityPassthrough.boundsOf(null));
    }

    // ------------------------------------------------------------------
    // Swept crossing test
    // ------------------------------------------------------------------

    private static final Set<BlockPos> DOOR = Set.of(
            new BlockPos(0, 64, 0), new BlockPos(0, 65, 0));

    @Test
    void stationaryEntityInsideTheInteriorCounts() {
        // Previous == current: a dropped item resting in the doorway. This is
        // the case the RCON `summon` verification produces.
        assertTrue(EntityPassthrough.crossedInterior(DOOR, 0.5, 64.5, 0.5, 0.5, 64.5, 0.5));
    }

    @Test
    void stationaryEntityOutsideDoesNot() {
        assertFalse(EntityPassthrough.crossedInterior(DOOR, 0.5, 64.5, 5.5, 0.5, 64.5, 5.5));
        // One block in front of the frame — the case a naive box.expand(0.5)
        // would have swallowed.
        assertFalse(EntityPassthrough.crossedInterior(DOOR, 0.5, 64.5, 1.5, 0.5, 64.5, 1.5));
    }

    @Test
    void fastMoverThatWouldHaveTunnelledIsCaught() {
        // A bow arrow covers ~3 blocks a tick: at no single tick boundary is
        // it inside a one-block-thick portal, but its path went through.
        assertTrue(EntityPassthrough.crossedInterior(DOOR, 0.5, 64.5, -2.0, 0.5, 64.5, 2.0));
        assertTrue(EntityPassthrough.crossedInterior(DOOR, 0.5, 65.5, 1.6, 0.5, 65.5, -1.6));
    }

    @Test
    void pathPassingBesideTheDoorwayIsNotCaught() {
        // Same flight, five blocks to the side: inside the broad-phase query
        // box, nowhere near the interior.
        assertFalse(EntityPassthrough.crossedInterior(DOOR, 5.5, 64.5, -2.0, 5.5, 64.5, 2.0));
        // And directly through the frame row above the interior.
        assertFalse(EntityPassthrough.crossedInterior(DOOR, 0.5, 66.5, -2.0, 0.5, 66.5, 2.0));
    }

    @Test
    void irregularInteriorsAreCheckedBlockwiseNotAsABox() {
        // An L-shaped interior: the diagonal crosses the bounding box's empty
        // corner and must NOT count.
        Set<BlockPos> lShape = Set.of(
                new BlockPos(0, 64, 0), new BlockPos(0, 65, 0), new BlockPos(1, 64, 0));
        assertFalse(EntityPassthrough.crossedInterior(lShape, 1.5, 65.5, -1.0, 1.5, 65.5, 1.0));
        assertTrue(EntityPassthrough.crossedInterior(lShape, 1.5, 64.5, -1.0, 1.5, 64.5, 1.0));
    }

    @Test
    void emptyInteriorNeverCrosses() {
        assertFalse(EntityPassthrough.crossedInterior(Set.of(), 0.5, 64.5, 0.5, 0.5, 64.5, 0.5));
        assertFalse(EntityPassthrough.crossedInterior(null, 0.5, 64.5, 0.5, 0.5, 64.5, 0.5));
    }

    // ------------------------------------------------------------------
    // The transform is the player's transform
    // ------------------------------------------------------------------

    @Test
    void entityAtTheInteriorCentreLandsInThePlayersArrivalColumn() {
        // ServerWorldMixin sends a player to (arrivalX + 0.5, surfaceY,
        // arrivalZ + 0.5). Pass-through translates the entity's own position
        // by the same mapping, so an entity standing in the interior's centre
        // column must land in exactly that block — otherwise the preview, the
        // player and the thrown item would disagree about where "through" is.
        Set<BlockPos> interior = Set.of(new BlockPos(100, 64, 50), new BlockPos(100, 65, 50));
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.scaledMapping(interior, 8.0);

        assertEquals(800, mapping.arrivalX());
        assertEquals(400, mapping.arrivalZ());
        assertEquals(64, mapping.interiorMinY());

        double entityX = 100.5;
        double entityZ = 50.5;
        assertEquals(mapping.arrivalX() + 0.5, entityX + mapping.dx());
        assertEquals(mapping.arrivalZ() + 0.5, entityZ + mapping.dz());

        // An entity resting on the interior's bottom row lands ON the arrival
        // surface, which is where the player's landY puts them too.
        int arrivalSurfaceY = 91;
        double entityY = 64.0;
        assertEquals(arrivalSurfaceY, arrivalSurfaceY + (entityY - mapping.interiorMinY()));
    }

    @Test
    void anchorPortalsUseTheMinCornerTranslationTheirPlayerPathUses() {
        // teleportToAnchor translates the interior's MIN corner onto the
        // anchor, not its centre — pass-through must not quietly re-centre.
        Set<BlockPos> interior = Set.of(
                new BlockPos(100, 64, 50), new BlockPos(101, 64, 50), new BlockPos(100, 65, 50));
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.anchorMapping(interior, -20, 300);

        assertEquals(-20, mapping.arrivalX());
        assertEquals(300, mapping.arrivalZ());
        assertEquals(-120, mapping.dx());
        assertEquals(250, mapping.dz());
        assertEquals(-20 + 0.5, 100.5 + mapping.dx());
    }

    // ------------------------------------------------------------------
    // Headless safety
    // ------------------------------------------------------------------

    @Test
    void arrivalSideReturnIsANoOpWithoutAServerWorld() {
        // EntityTickPortalMixin calls this for every non-player entity every
        // tick, including on the client side. It must never throw, and must
        // report false so the caller leaves vanilla's callback alone.
        assertFalse(EntityPassthrough.tryReturnFromArrivalPortal(null, null));
    }

    @Test
    void clearIsSafeToCallRepeatedly() {
        assertDoesNotThrow(EntityPassthrough::clear);
        assertDoesNotThrow(EntityPassthrough::clear);
    }
}
