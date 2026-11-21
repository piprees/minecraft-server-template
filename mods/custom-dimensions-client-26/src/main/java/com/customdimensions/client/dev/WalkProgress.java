package com.customdimensions.client.dev;

/**
 * One walk's verdict, tick by tick: how far the player got from where it
 * started (horizontally), and why it stopped. {@code arrived} and
 * {@code stalled} are independent: a walk that runs out of time neither
 * arrived nor stalled. A stall is {@link #STALL_TICKS} ticks without
 * {@link #STALL_PROGRESS} blocks of progress. No Minecraft types.
 */
public final class WalkProgress {
    static final int STALL_TICKS = 20;
    static final double STALL_PROGRESS = 0.05;

    private final double blocks;
    private final int timeoutTicks;
    private final double x0;
    private final double z0;
    private double best;
    private double bestAtCheck;
    private int ticks;
    private int sinceCheck;
    private double lastX;
    private double lastY;
    private double lastZ;
    private String reason;
    private boolean arrived;
    private boolean stalled;

    public WalkProgress(double blocks, int timeoutTicks, double x, double z) {
        if (!(blocks > 0) || timeoutTicks <= 0) {
            throw new IllegalArgumentException("a walk needs a positive distance and timeout");
        }
        this.blocks = blocks;
        this.timeoutTicks = timeoutTicks;
        this.x0 = x;
        this.z0 = z;
        this.lastX = x;
        this.lastZ = z;
    }

    /** One tick at this position; answers whether the walk is over. */
    public boolean tick(double x, double y, double z) {
        if (reason != null) {
            return true;
        }
        ticks++;
        sinceCheck++;
        lastX = x;
        lastY = y;
        lastZ = z;
        best = Math.max(best, travelled());
        if (best >= blocks) {
            arrived = true;
            reason = "arrived";
            return true;
        }
        if (sinceCheck >= STALL_TICKS) {
            if (best - bestAtCheck < STALL_PROGRESS) {
                stalled = true;
                reason = "no progress for " + STALL_TICKS + " ticks";
                return true;
            }
            bestAtCheck = best;
            sinceCheck = 0;
        }
        if (ticks >= timeoutTicks) {
            reason = "timed out after " + ticks + " ticks";
            return true;
        }
        return false;
    }

    /** The world changed under the walk (a portal crossing): it ends there, neither arrived nor stalled. */
    public void interrupt(String why) {
        if (reason == null) {
            reason = why;
        }
    }

    public double travelled() {
        double dx = lastX - x0;
        double dz = lastZ - z0;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public int ticks() {
        return ticks;
    }

    public boolean arrived() {
        return arrived;
    }

    public boolean stalled() {
        return stalled;
    }

    public String reason() {
        return reason;
    }

    /** Where the walk stalled, or null. */
    public double[] stalledAt() {
        return stalled ? new double[] {lastX, lastY, lastZ} : null;
    }
}
