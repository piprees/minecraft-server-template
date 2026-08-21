package com.customdimensions.immersive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The packet ceiling that stops a mask flip landing in one tick.
 */
class ProjectionBudgetTest {

    private static final int MAX = ProjectionBudget.DEFAULT_MAX_PER_PASS;

    // === the priority rule ==============================================

    @Test
    void restoresTakePriorityOverSends() {
        // The load-bearing rule. A fake block still showing after it should
        // have gone is a defect the player collides with; a fake block not
        // yet sent is merely absent.
        var a = ProjectionBudget.allow(500, 500, 100);

        assertEquals(100, a.restores(), "restores consume the budget first");
        assertEquals(0, a.sends(), "sends wait");
    }

    @Test
    void sendsGetWhateverRestoresDoNotUse() {
        var a = ProjectionBudget.allow(30, 500, 100);

        assertEquals(30, a.restores());
        assertEquals(70, a.sends());
        assertEquals(100, a.total());
    }

    @Test
    void sendsAreNotStarvedWhenThereIsNothingToRestore() {
        var a = ProjectionBudget.allow(0, 500, 100);

        assertEquals(0, a.restores());
        assertEquals(100, a.sends());
    }

    // === the live regression ============================================

    @Test
    void theLiveMaskFlipIsSpreadNotDumped() {
        // A full mask flip sends 984 restore packets in one tick, per
        // viewer, per portal — the lag spike this budget caps.
        var a = ProjectionBudget.allow(984, 0, MAX);

        assertEquals(MAX, a.total(), "capped, not dumped");
        assertTrue(a.total() < 984);
        assertEquals(6, ProjectionBudget.passesToDrain(984, 0, MAX),
                "spread over ~6 passes (24 ticks) instead of one");
    }

    @Test
    void theOtherLivePassAlsoFitsTheCeiling() {
        // The inverse flip: mostly sends, a few restores.
        var a = ProjectionBudget.allow(8, 972, MAX);

        assertEquals(8, a.restores(), "the few restores always go");
        assertEquals(MAX - 8, a.sends());
    }

    // === steady state must never queue ==================================

    @Test
    void steadyStateDeltasAlwaysFitInOnePass() {
        // A walking player moves a handful of positions in and out of the
        // view cone each pass. If the ceiling ever throttled THAT, the
        // preview would visibly lag behind the player.
        assertFalse(ProjectionBudget.isDeferring(4, 12, MAX));
        assertFalse(ProjectionBudget.isDeferring(0, 0, MAX));
        assertEquals(0, ProjectionBudget.passesToDrain(0, 0, MAX));
    }

    @Test
    void aFullPassExactlyAtTheCeilingDoesNotDefer() {
        assertFalse(ProjectionBudget.isDeferring(MAX, 0, MAX));
        assertTrue(ProjectionBudget.isDeferring(MAX + 1, 0, MAX));
    }

    // === edges ==========================================================

    @Test
    void anUnlimitedBudgetPassesEverythingThrough() {
        var a = ProjectionBudget.allow(984, 972, 0);

        assertEquals(984, a.restores());
        assertEquals(972, a.sends());
        assertFalse(ProjectionBudget.isDeferring(984, 972, 0));
    }

    @Test
    void negativeCountsAreTreatedAsZero() {
        var a = ProjectionBudget.allow(-5, -5, MAX);

        assertEquals(0, a.total());
    }

    @Test
    void passesToDrainRoundsUp() {
        assertEquals(1, ProjectionBudget.passesToDrain(1, 0, MAX));
        assertEquals(1, ProjectionBudget.passesToDrain(MAX, 0, MAX));
        assertEquals(2, ProjectionBudget.passesToDrain(MAX + 1, 0, MAX));
    }

    @Test
    void theCeilingIsBigEnoughToBeInvisibleInSteadyState() {
        // Guard on the constant itself: if someone tunes it below a typical
        // walking delta, every player gets a preview that trails them.
        assertTrue(MAX >= 64, "too small: normal movement would queue");
        assertTrue(MAX <= 512, "too large: a mask flip lands in one tick again");
    }
}
