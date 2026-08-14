package com.customdimensions.portal;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The arrival-portal entry edge — {@code PortalHelper.enteredArrivalPortal}
 * and {@code markArrivedInPortal} — driven as the state machine it is.
 *
 * <p>Arrival portals are real portal blocks, so vanilla's {@code
 * Entity.tryUsePortal} re-pins the portal cooldown to {@code
 * getDefaultPortalCooldown()} (10 for a player, 300 for everything else) on
 * every tick an entity stands in one — it never reaches zero while standing
 * still, so gating the return on cooldown-zero would trap anyone who arrives
 * inside their own arrival portal.
 *
 * <p>Every test here therefore keeps {@code warmCooldown} TRUE while the entity
 * stands in the portal, exactly as vanilla would. A rule that only works with a
 * cooldown of zero is the bug, not the fix.
 *
 * <p>No world, no registries, no entities: {@code RegistryKey} and
 * {@code UUID} are plain values, and the map is process-global static state
 * reset in {@link #reset()}.
 */
class ArrivalPortalEdgeTest {

    private static final RegistryKey<World> GARDENS =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_blossom_gardens"));
    private static final RegistryKey<World> OVERWORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));

    /** Vanilla pins a player's cooldown at 10 while they stand in a portal. */
    private static final boolean WARM = true;
    private static final boolean COLD = false;

    private UUID player;

    @BeforeEach
    void reset() {
        PortalHelper.clearArrivalPresence();
        player = UUID.randomUUID();
    }

    /** One tick of the player path: sampled whether in a portal or not. */
    private boolean sample(RegistryKey<World> world, boolean inPortal, boolean warm, int tick) {
        return PortalHelper.enteredArrivalPortal(world, player, inPortal, warm, tick);
    }

    // ------------------------------------------------------------------
    // Arriving inside the arrival portal must not bounce the player back
    // ------------------------------------------------------------------

    @Test
    void aPlayerWhoArrivesInsideTheArrivalPortalIsNeverTrapped() {
        // Ticks 1-2: walking towards the source portal in the overworld.
        assertFalse(sample(OVERWORLD, false, COLD, 1));
        assertFalse(sample(OVERWORLD, false, COLD, 2));

        // Tick 3: ServerWorldMixin teleports them into the gardens, landing
        // them inside the arrival portal with a fresh cooldown. This must NOT
        // fire, or they bounce straight home again.
        assertFalse(sample(GARDENS, true, WARM, 3),
                "arriving inside the arrival portal must not fire the return");

        // Ticks 4-250: standing still. Vanilla holds the cooldown at 10 the
        // whole time.
        for (int tick = 4; tick <= 250; tick++) {
            assertFalse(sample(GARDENS, true, WARM, tick),
                    "standing still must not fire the return (tick " + tick + ")");
        }

        // Tick 251: one step out of the portal block.
        assertFalse(sample(GARDENS, false, WARM, 251));

        // Tick 252: step straight back in — cooldown still warm, because they
        // were only out for a single tick. The edge must fire regardless.
        assertTrue(sample(GARDENS, true, WARM, 252),
                "stepping back in must fire the return even with a warm cooldown");
    }

    @Test
    void anEntityThatArrivedInAnArrivalPortalIsNeverTrappedEither() {
        // The entity path samples ONLY while inside a portal, so "it left" is
        // inferred from the gap. Vanilla's re-pin for a non-player is 300, so
        // the cooldown is warm throughout — without this check, a dropped
        // item or spawned mob would never leave the arrival.
        UUID cow = UUID.randomUUID();
        PortalHelper.markArrivedInPortal(GARDENS, cow, 10);

        for (int tick = 11; tick <= 400; tick++) {
            assertFalse(PortalHelper.enteredArrivalPortal(GARDENS, cow, true, WARM, tick),
                    "a standing entity must not fire (tick " + tick + ")");
        }

        // Wanders off for two ticks (no samples at 401, 402), wanders back.
        assertTrue(PortalHelper.enteredArrivalPortal(GARDENS, cow, true, WARM, 403));
    }

    // ------------------------------------------------------------------
    // Ping-pong must stay impossible
    // ------------------------------------------------------------------

    @Test
    void markingAnArrivalSuppressesTheEdgeEvenWithNoCooldownAtAll() {
        // Every teleport this mod performs calls markArrivedInPortal, which is
        // what makes a portal configured with "cooldown": 0 safe.
        PortalHelper.markArrivedInPortal(GARDENS, player, 100);
        assertFalse(sample(GARDENS, true, COLD, 101));
        assertFalse(sample(GARDENS, true, COLD, 102));
    }

    @Test
    void aSecondVisitAfterLeavingByAnotherRouteDoesNotBounce() {
        // Visit one: arrive, stand, step out, wander off.
        assertFalse(sample(GARDENS, true, WARM, 10));
        assertFalse(sample(GARDENS, false, WARM, 11));

        // Left the gardens some other way (an exit shrine, /spawn, death) and
        // spent a while in the overworld. Those ticks are what move the
        // record's world across, and they are why the player path samples when
        // NOT in a portal.
        for (int tick = 12; tick <= 60; tick++) {
            assertFalse(sample(OVERWORLD, false, COLD, tick));
        }

        // Visit two, through the source portal again: a first sighting in the
        // gardens, so the warm cooldown correctly reads as "a teleport put me
        // here" and the return stays quiet.
        assertFalse(sample(GARDENS, true, WARM, 61),
                "a second visit must not fire on arrival");
        assertFalse(sample(GARDENS, true, WARM, 62));
    }

    @Test
    void everyTeleportWeMakeSeedsTheDestinationSoNoPathCanBounce() {
        // markArrivedInPortal is called after each of the six teleports this
        // mod performs (four in EntityTickPortalMixin, two in
        // EntityPassthrough). Whatever the destination is, and whatever
        // cooldown it carries, the next sighting reads as "already standing
        // there" rather than as an entry — including a chain hop that lands
        // deliberately ON another dimension's arrival portal.
        PortalHelper.markArrivedInPortal(OVERWORLD, player, 500);
        assertFalse(sample(OVERWORLD, true, COLD, 500), "same tick as the teleport");
        assertFalse(sample(OVERWORLD, true, COLD, 501), "the tick after");
    }

    @Test
    void anUnobservedWorldChangeIsTheOneKnownGapAndItIsBenign() {
        // Documented limitation of the entity path, pinned so it stays a
        // choice rather than a surprise. Entities are sampled ONLY while in
        // one of our portals, so an entity that leaves a world by a route we
        // never see keeps a stale record for it. If something outside this mod
        // then teleports it back INTO that world's arrival portal, the stale
        // record reads as a re-entry and it crosses.
        //
        // It is benign: the entity goes through a portal it is standing in,
        // which is what portals do, and it happens once rather than in a loop
        // (the far side seeds via markArrivedInPortal). Players cannot reach
        // this at all — the player path samples every tick, so their record's
        // world always follows them, which is what
        // aSecondVisitAfterLeavingByAnotherRouteDoesNotBounce proves.
        UUID cow = UUID.randomUUID();
        PortalHelper.markArrivedInPortal(GARDENS, cow, 10);
        assertTrue(PortalHelper.enteredArrivalPortal(GARDENS, cow, true, WARM, 200));
    }

    // ------------------------------------------------------------------
    // Things that SHOULD still cross
    // ------------------------------------------------------------------

    @Test
    void walkingInWithNoCooldownFiresImmediately() {
        // A player who walked to an arrival portal from elsewhere in the same
        // dimension has no cooldown and no history here.
        assertTrue(sample(GARDENS, true, COLD, 7));
    }

    @Test
    void somethingThatMaterialisesInAPortalStillCrosses() {
        // An item dropped into an arrival portal, or a mob spawned in one, has
        // never teleported and so has no cooldown — it must cross on its
        // first tick, which is what "first sighting is decided by the
        // cooldown" guarantees.
        UUID item = UUID.randomUUID();
        assertTrue(PortalHelper.enteredArrivalPortal(GARDENS, item, true, COLD, 42));
    }

    @Test
    void returningAfterAGenuineTripAwayFires() {
        assertFalse(sample(GARDENS, true, WARM, 10));       // arrived
        for (int tick = 11; tick <= 400; tick++) {           // explored
            assertFalse(sample(GARDENS, false, tick < 20, tick));
        }
        assertTrue(sample(GARDENS, true, COLD, 401),         // came back
                "arrive, walk away, come back later, step in, go home");
    }

    // ------------------------------------------------------------------
    // Handing the edge back
    // ------------------------------------------------------------------

    @Test
    void anEdgeThatDidNotTeleportIsRetriedOnEveryFollowingTick() {
        // A chain hop into a dimension that is still loading, or a return whose
        // target world has been idle-unloaded: the edge fires, nothing
        // teleports, and the caller hands it back. Without that the player
        // stands in the portal forever — the edge is a one-shot and it was
        // already spent.
        assertTrue(sample(GARDENS, true, COLD, 10), "stepped in");
        for (int tick = 11; tick <= 14; tick++) {
            PortalHelper.rearmArrivalPortalEntry(GARDENS, player, tick - 1);
            assertTrue(sample(GARDENS, true, COLD, tick),
                    "target still unavailable — must retry (tick " + tick + ")");
        }
        // The world finally loads and the teleport happens, so nothing is
        // handed back: the retry loop stops.
        PortalHelper.markArrivedInPortal(OVERWORLD, player, 15);
        assertFalse(sample(OVERWORLD, true, WARM, 16));
    }

    @Test
    void rearmingDoesNotResurrectTheEdgeForSomeoneWhoNeverStepppedIn() {
        // The retry loop can only ever start after a genuine entry, so a
        // player who ARRIVED by teleport never enters it — which is what keeps
        // the trap fixed rather than traded for a bounce.
        assertFalse(sample(OVERWORLD, false, COLD, 1));
        assertFalse(sample(GARDENS, true, WARM, 2), "arrived");
        for (int tick = 3; tick <= 40; tick++) {
            assertFalse(sample(GARDENS, true, WARM, tick));
        }
    }

    // ------------------------------------------------------------------
    // Bookkeeping
    // ------------------------------------------------------------------

    @Test
    void anEntityThatNeverTouchesAPortalIsNeverRecorded() {
        // The not-in-a-portal branch only ever REFRESHES an existing record.
        // Anything else would put an entry in the map for every entity the
        // player path sees, which is the leak this is written to avoid.
        assertEquals(0, PortalHelper.arrivalPresenceCount());
        assertFalse(sample(OVERWORLD, false, COLD, 1));
        assertFalse(sample(OVERWORLD, false, COLD, 2));
        assertEquals(0, PortalHelper.arrivalPresenceCount());
    }

    @Test
    void staleRecordsAreSweptSoTheMapCannotGrowForever() {
        // 40 entities each stand in an arrival portal once and are never seen
        // again — a disconnected player, a despawned mob, a collected item.
        for (int i = 0; i < 40; i++) {
            PortalHelper.enteredArrivalPortal(GARDENS, UUID.randomUUID(), true, COLD, 100);
        }
        assertEquals(40, PortalHelper.arrivalPresenceCount());

        // Any later sighting past the TTL sweeps them.
        PortalHelper.enteredArrivalPortal(GARDENS, player, true, WARM, 100_000);
        assertEquals(1, PortalHelper.arrivalPresenceCount(),
                "only the live record should survive the sweep");
    }

    @Test
    void aSweptRecordStillDecidesCorrectlyOnTheNextSighting() {
        // Eviction is only safe because a first sighting falls back to the
        // cooldown. Prove both branches after the record is gone.
        PortalHelper.enteredArrivalPortal(GARDENS, player, true, WARM, 100);
        PortalHelper.forgetArrivalPresence(player);
        assertTrue(sample(GARDENS, true, COLD, 200), "walked in → crosses");

        PortalHelper.forgetArrivalPresence(player);
        assertFalse(sample(GARDENS, true, WARM, 300), "teleported in → does not");
    }

    @Test
    void clearingResetsEverything() {
        PortalHelper.markArrivedInPortal(GARDENS, player, 10);
        assertEquals(1, PortalHelper.arrivalPresenceCount());
        PortalHelper.clearArrivalPresence();
        assertEquals(0, PortalHelper.arrivalPresenceCount());
        // A cleared map is indistinguishable from a fresh boot.
        assertTrue(sample(GARDENS, true, COLD, 11));
    }

    @Test
    void twoEntitiesInTheSamePortalDoNotShareState() {
        UUID other = UUID.randomUUID();
        assertFalse(sample(GARDENS, true, WARM, 10));                                    // arrived
        assertTrue(PortalHelper.enteredArrivalPortal(GARDENS, other, true, COLD, 10));   // walked in
        assertFalse(sample(GARDENS, true, WARM, 11));
        assertFalse(PortalHelper.enteredArrivalPortal(GARDENS, other, true, COLD, 11));
    }
}
