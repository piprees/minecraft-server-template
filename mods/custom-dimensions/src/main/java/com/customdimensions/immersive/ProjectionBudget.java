package com.customdimensions.immersive;

/**
 * How much projection work one refresh pass is allowed to do.
 *
 * <p>Walking past a portal inverts the whole sightline mask at once: every
 * position the player could see needs a correction packet in the same pass —
 * on the order of a thousand {@code BlockUpdateS2CPacket}s in a single tick,
 * per viewer, per portal, enough to stall the server for several seconds.
 *
 * <p><b>The rule: restores outrank sends, always.</b> A fake block the
 * client is still showing after it should have gone is a visible defect —
 * the player collides with something that is not there. A fake block not
 * yet sent is merely absent. This matches the {@code lastSent} invariant:
 * nothing leaves {@code lastSent} without a correction packet having gone
 * out, so deferring a restore means carrying a known-stale entry for longer,
 * while deferring a send costs nothing but latency.
 *
 * <p>Pure arithmetic over plain ints — no packets, no world, no MC runtime.
 */
public final class ProjectionBudget {

    /**
     * Packets one viewer's pass may send.
     *
     * <p>Sized so a full mask flip spreads over ~5 passes (20 ticks, one
     * second at the default interval) rather than landing in one tick. Large
     * enough that steady-state deltas — a handful of positions entering and
     * leaving the view cone as the player walks — always fit and never
     * queue.
     */
    public static final int DEFAULT_MAX_PER_PASS = 192;

    private ProjectionBudget() {
    }

    /**
     * How many restores and sends this pass may perform.
     *
     * @param pendingRestores positions holding a fake block that must be
     *                        given their real state back
     * @param pendingSends    positions whose fake state has changed
     * @param maxPerPass      packet ceiling; {@code <= 0} means unlimited
     */
    public static Allowance allow(int pendingRestores, int pendingSends, int maxPerPass) {
        int restores = Math.max(0, pendingRestores);
        int sends = Math.max(0, pendingSends);
        if (maxPerPass <= 0) {
            return new Allowance(restores, sends);
        }
        // Restores first, and they may consume the whole budget.
        int allowedRestores = Math.min(restores, maxPerPass);
        int allowedSends = Math.min(sends, maxPerPass - allowedRestores);
        return new Allowance(allowedRestores, allowedSends);
    }

    /**
     * Is this pass carrying work over to the next one? Worth logging as a
     * COUNT — a projection that is permanently behind its budget is a real
     * problem (the slab is too big, or the refresh interval too tight), and
     * it looks identical to a healthy one without the number.
     */
    public static boolean isDeferring(int pendingRestores, int pendingSends, int maxPerPass) {
        Allowance a = allow(pendingRestores, pendingSends, maxPerPass);
        return a.restores() < Math.max(0, pendingRestores)
                || a.sends() < Math.max(0, pendingSends);
    }

    /**
     * Passes needed to drain this much work at {@code maxPerPass}, for the
     * "is the slab too big for the interval?" sanity check.
     */
    public static int passesToDrain(int pendingRestores, int pendingSends, int maxPerPass) {
        int total = Math.max(0, pendingRestores) + Math.max(0, pendingSends);
        if (maxPerPass <= 0) {
            return total == 0 ? 0 : 1;
        }
        return (total + maxPerPass - 1) / maxPerPass;
    }

    /** What one pass may do. */
    public record Allowance(int restores, int sends) {
        public int total() {
            return restores + sends;
        }
    }
}
