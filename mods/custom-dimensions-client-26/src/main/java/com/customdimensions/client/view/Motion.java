package com.customdimensions.client.view;

/**
 * Where a fed entity is drawn between the server's snapshots, in ticks and
 * destination coordinates. No Minecraft types.
 *
 * <p>A snapshot arrives at most every {@link #INTERVAL} ticks. A mob is drawn
 * at its snapshot, with the gap from where it was drawn when the snapshot came
 * closing over one interval. A projectile is carried on from its snapshot along
 * its velocity the way vanilla's arrow tick moves it (move, then drag, then
 * gravity), for at most {@link #REACH} ticks, with the same closing gap.
 */
public final class Motion {
    /** Ticks between the server's entity snapshots ({@code EntitySnapshot.INTERVAL}). */
    public static final int INTERVAL = 4;
    /** The longest a projectile is carried past its snapshot. */
    public static final int REACH = 2 * INTERVAL;
    /** A projectile's velocity kept per tick in air. */
    public static final double DRAG = 0.99;
    /** A jump longer than this between drawn and fed positions is a teleport, drawn at once. */
    public static final double SNAP = 8.0;

    private Motion() {
    }

    /** A projectile's position {@code ticks} after it stood at {@code p} moving {@code v} blocks a tick. */
    public static double[] projectile(double[] p, double[] v, double ticks, double gravity, double drag) {
        double t = Math.max(0, Math.min(ticks, REACH));
        double[] at = p.clone();
        double[] vel = v.clone();
        int whole = (int) Math.floor(t);
        for (int i = 0; i < whole; i++) {
            step(at, vel, gravity, drag);
        }
        double frac = t - whole;
        if (frac > 0) {
            for (int k = 0; k < 3; k++) {
                at[k] += vel[k] * frac;
            }
        }
        return at;
    }

    private static void step(double[] at, double[] vel, double gravity, double drag) {
        for (int k = 0; k < 3; k++) {
            at[k] += vel[k];
            vel[k] *= drag;
        }
        vel[1] -= gravity;
    }

    /** The weight left on a gap {@code age} ticks after a snapshot: 1 at receipt, 0 from one interval on. */
    public static double gapWeight(double age) {
        if (age <= 0) {
            return 1;
        }
        return age >= INTERVAL ? 0 : 1 - age / INTERVAL;
    }

    /** The shortest turn from {@code from} to {@code to}, {@code t} of the way, in degrees. */
    public static float lerpAngle(float from, float to, double t) {
        double d = ((to - from) % 360 + 540) % 360 - 180;
        return (float) (from + d * Math.max(0, Math.min(1, t)));
    }

    /** One entity's drawn track: its last snapshot and the gap still closing from the one before. */
    public static final class Track {
        private final boolean projectile;
        private double[] snap;
        private double[] velocity;
        private double gravity;
        private double since;
        private double[] gap = {0, 0, 0};
        private float yaw;
        private float yawFrom;
        private float pitch;
        private float pitchFrom;
        private float head;
        private float headFrom;

        public Track(boolean projectile) {
            this.projectile = projectile;
        }

        /** Takes a new snapshot at tick {@code now}; the first one, or a teleport, is drawn where it is. */
        public void update(double[] pos, double[] vel, double gravity, float yaw, float pitch, float head, double now) {
            if (snap == null) {
                gap = new double[] {0, 0, 0};
                yawFrom = yaw;
                pitchFrom = pitch;
                headFrom = head;
            } else {
                double[] drawn = position(now);
                double[] g = {drawn[0] - pos[0], drawn[1] - pos[1], drawn[2] - pos[2]};
                gap = g[0] * g[0] + g[1] * g[1] + g[2] * g[2] > SNAP * SNAP ? new double[] {0, 0, 0} : g;
                yawFrom = yaw(now);
                pitchFrom = pitch(now);
                headFrom = head(now);
            }
            snap = pos.clone();
            velocity = vel.clone();
            this.gravity = gravity;
            this.yaw = yaw;
            this.pitch = pitch;
            this.head = head;
            since = now;
        }

        /** Whether a snapshot has been taken. */
        public boolean started() {
            return snap != null;
        }

        /** The drawn position at tick {@code now}. */
        public double[] position(double now) {
            double age = now - since;
            double[] base = projectile ? projectile(snap, velocity, age, gravity, DRAG) : snap.clone();
            double w = gapWeight(age);
            return new double[] {base[0] + gap[0] * w, base[1] + gap[1] * w, base[2] + gap[2] * w};
        }

        public float yaw(double now) {
            return lerpAngle(yawFrom, yaw, (now - since) / INTERVAL);
        }

        public float pitch(double now) {
            return lerpAngle(pitchFrom, pitch, (now - since) / INTERVAL);
        }

        public float head(double now) {
            return lerpAngle(headFrom, head, (now - since) / INTERVAL);
        }
    }
}
