package com.customdimensions.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What lighting a portal costs the igniter.
 *
 * <p>Vanilla damages a flint and steel; it does not destroy it. An eye of
 * ender IS consumed, but because it is placed INTO an end portal frame — a
 * placement, not an ignition. So nothing is consumed here unless a dimension
 * asks for it.
 */
class IgniterSpendTest {

    @Test
    void aDamageableIgniterIsDamagedRatherThanDestroyed() {
        assertEquals(IgniterSpend.DAMAGE,
                IgniterSpend.of(true, false, false),
                "flint and steel is a stack of one — decrementing it destroys the tool");
    }

    @Test
    void aStackableIgniterIsLeftAlone() {
        assertEquals(IgniterSpend.NOTHING,
                IgniterSpend.of(false, false, false),
                "a diamond is not spent by lighting a portal");
    }

    @Test
    void aDimensionThatAsksForConsumptionGetsIt() {
        assertEquals(IgniterSpend.CONSUME,
                IgniterSpend.of(false, false, true),
                "eye-of-ender semantics are opt-in, and this dimension opted in");
    }

    @Test
    void consumptionBeatsDamageWhenBothWouldApply() {
        assertEquals(IgniterSpend.CONSUME,
                IgniterSpend.of(true, false, true),
                "a dimension that asked for the item wants the item, not its durability");
    }

    @Test
    void creativeSpendsNothingWhateverElseIsTrue() {
        assertEquals(IgniterSpend.NOTHING, IgniterSpend.of(true, true, false));
        assertEquals(IgniterSpend.NOTHING, IgniterSpend.of(false, true, true),
                "creative does not spend, even where a dimension asks for consumption");
    }
}
