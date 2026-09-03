package com.customdimensions.client.dev;

/**
 * Judges a held walk from the positions it is fed, one per client tick.
 *
 * <p>Distance is horizontal displacement from the start, so a fall is not
 * travel and a player falling while forward is held reads as stopped. STALLED
 * is the verdict that carries information — it names a walk that is being
 * driven and going nowhere, and the position it stopped at.
 */
public final class WalkTracker {

    public enum Verdict { CONTINUE, ARRIVED, STALLED, TIMED_OUT }

    /** Below this, a change of position is jitter rather than walking. */
    private static final double MOVEMENT_EPSILON = 0.05;

    private final double startX;
    private final double startZ;
    private final int startTick;
    private final double blocks;
    private final int stallTicks;
    private final int timeoutTicks;

    private Verdict verdict = Verdict.CONTINUE;
    private double travelled;
    private int lastTick;

    private double anchorX;
    private double anchorZ;
    private int anchorTick;

    private double[] stalledAt;

    public WalkTracker(double startX, double startY, double startZ, int startTick,
                       double blocks, int stallTicks, int timeoutTicks) {
        this.startX = startX;
        this.startZ = startZ;
        this.startTick = startTick;
        this.blocks = blocks;
        this.stallTicks = stallTicks;
        this.timeoutTicks = timeoutTicks;
        this.anchorX = startX;
        this.anchorZ = startZ;
        this.anchorTick = startTick;
        this.lastTick = startTick;
    }

    public static int ticksFromMillis(long millis) {
        long ticks = Math.round(millis / 50.0);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, ticks));
    }

    /** Arrival first, then the stall, then the clock. */
    public Verdict accept(double x, double y, double z, int tick) {
        if (this.verdict != Verdict.CONTINUE) {
            return this.verdict;
        }
        this.lastTick = tick;
        this.travelled = Math.hypot(x - this.startX, z - this.startZ);

        if (this.travelled >= this.blocks) {
            return this.verdict = Verdict.ARRIVED;
        }
        if (Math.hypot(x - this.anchorX, z - this.anchorZ) > MOVEMENT_EPSILON) {
            this.anchorX = x;
            this.anchorZ = z;
            this.anchorTick = tick;
        }
        if (tick - this.anchorTick >= this.stallTicks) {
            this.stalledAt = new double[] {x, y, z};
            return this.verdict = Verdict.STALLED;
        }
        if (tick - this.startTick >= this.timeoutTicks) {
            return this.verdict = Verdict.TIMED_OUT;
        }
        return Verdict.CONTINUE;
    }

    public Verdict verdict() {
        return this.verdict;
    }

    public boolean arrived() {
        return this.verdict == Verdict.ARRIVED;
    }

    public boolean stalled() {
        return this.verdict == Verdict.STALLED;
    }

    public double travelled() {
        return this.travelled;
    }

    public int ticks() {
        return this.lastTick - this.startTick;
    }

    /** Where it stopped, or null while it has not stopped. */
    public double[] stalledAt() {
        return this.stalledAt;
    }

    public double stalledX() {
        return this.stalledAt == null ? 0 : this.stalledAt[0];
    }

    public double stalledY() {
        return this.stalledAt == null ? 0 : this.stalledAt[1];
    }

    public double stalledZ() {
        return this.stalledAt == null ? 0 : this.stalledAt[2];
    }

    public String reason() {
        switch (this.verdict) {
            case ARRIVED:
                return "arrived";
            case STALLED:
                return "position unchanged for " + this.stallTicks
                        + " ticks while forward was held";
            case TIMED_OUT:
                return "timed out after " + ticks() + " ticks having travelled "
                        + Json.number(this.travelled) + " of "
                        + Json.number(this.blocks) + " blocks";
            default:
                return "walking";
        }
    }
}
