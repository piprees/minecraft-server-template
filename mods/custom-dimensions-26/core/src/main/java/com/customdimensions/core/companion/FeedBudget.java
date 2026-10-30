package com.customdimensions.core.companion;

/**
 * One viewer's feed budget: at most {@link #chunksPerPass} chunks a pass, and
 * a byte bucket refilled at {@link #bytesPerSecond} that holds at most one
 * second's worth. A chunk goes only when the bucket covers it; a clear always
 * goes and is charged anyway, since a stale far side the client keeps drawing
 * is a defect and a chunk not yet sent is only latency.
 */
public final class FeedBudget {
    public static final int DEFAULT_CHUNKS_PER_PASS = 4;
    public static final int DEFAULT_BYTES_PER_SECOND = 256 * 1024;
    public static final int TICKS_PER_SECOND = 20;

    private final int chunksPerPass;
    private final int bytesPerSecond;
    private long tokens;
    private long lastTick = Long.MIN_VALUE;
    private int chunksThisPass;
    private long spent;

    public FeedBudget(int chunksPerPass, int bytesPerSecond) {
        this.chunksPerPass = Math.max(1, chunksPerPass);
        this.bytesPerSecond = Math.max(1, bytesPerSecond);
        this.tokens = this.bytesPerSecond;
    }

    public static FeedBudget standard() {
        return new FeedBudget(DEFAULT_CHUNKS_PER_PASS, DEFAULT_BYTES_PER_SECOND);
    }

    public int chunksPerPass() {
        return chunksPerPass;
    }

    public int bytesPerSecond() {
        return bytesPerSecond;
    }

    /** Starts a pass at {@code tick}: refills the bucket for the ticks since the last and resets the chunk count. */
    public void startPass(long tick) {
        if (lastTick != Long.MIN_VALUE && tick > lastTick) {
            long refill = (tick - lastTick) * (long) bytesPerSecond / TICKS_PER_SECOND;
            tokens = Math.min(bytesPerSecond, tokens + refill);
        }
        lastTick = tick;
        chunksThisPass = 0;
    }

    /** Whether a chunk of {@code bytes} may go this pass; charges it when it may. */
    public boolean trySpendChunk(int bytes) {
        if (chunksThisPass >= chunksPerPass || bytes > tokens) {
            return false;
        }
        chunksThisPass++;
        charge(bytes);
        return true;
    }

    /** Charges a payload that is never deferred: a frame, a clear, an entity snapshot. */
    public void charge(int bytes) {
        tokens -= Math.max(0, bytes);
        spent += Math.max(0, bytes);
    }

    public long tokens() {
        return tokens;
    }

    /** Bytes charged since this budget was made. */
    public long spent() {
        return spent;
    }

    /**
     * The most a viewer can be sent over {@code ticks} ticks from a full
     * bucket: one second's burst plus the refill. Undeferred payloads may
     * exceed it by what they were charged.
     */
    public static long ceiling(int bytesPerSecond, long ticks) {
        return bytesPerSecond + Math.max(0, ticks) * (long) bytesPerSecond / TICKS_PER_SECOND;
    }
}
