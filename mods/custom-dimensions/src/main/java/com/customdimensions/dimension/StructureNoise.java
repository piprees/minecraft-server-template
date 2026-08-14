package com.customdimensions.dimension;

/**
 * Ken Perlin's improved 2D noise over a seed-derived permutation table, used
 * to decide where structures may generate ({@link NoiseStructurePlacement}).
 *
 * <h2>Why not vanilla's PerlinNoiseSampler</h2>
 *
 * Every placement position must be reproducible exactly by the Python mirror.
 * Vanilla's sampler derives its permutation from {@code
 * net.minecraft.util.math.random.Random}, whose two implementations and their
 * seed scrambling would all have to be mirrored, and it drags Bootstrap-bound
 * static init into unit tests — the same reason {@code
 * FixedStructurePlacement.Index} exists as a separate pure class.
 *
 * This implementation is deliberately small and mechanical so the Python side
 * can be a line-for-line transcription:
 *
 * <ul>
 * <li>the permutation is a Fisher-Yates shuffle of 0..255 driven by a
 *     SplitMix64 stream seeded from the noise seed — no library RNG;</li>
 * <li>every intermediate is a {@code double}. Java {@code float} would round
 *     differently from Python's (always-double) floats, for the sake of
 *     nothing;</li>
 * <li>one octave. Extra octaves add sampling cost and three more constants to
 *     keep in sync across two languages, for variation the radial curve and
 *     the per-group salts already provide.</li>
 * </ul>
 *
 * <h2>Python parity notes</h2>
 *
 * Java longs wrap on overflow and {@code >>>} is an unsigned shift; Python
 * integers are unbounded and {@code >>} is arithmetic. The mirror must mask
 * every SplitMix64 step to 64 bits. {@link #sample} takes the floor of a
 * double, which matches Python's {@code math.floor} for all inputs here.
 */
public final class StructureNoise {

    /** 2D improved-Perlin peaks at sqrt(2)/2; scaling by sqrt(2) gives [-1, 1]. */
    private static final double NORMALISE = 1.4142135623730951;

    /**
     * Irrational offsets applied before sampling, so a chunk coordinate can
     * never land exactly on the noise lattice.
     *
     * Perlin is exactly 0 at every lattice point — which normalises to 0.5
     * for EVERY seed. A frequency like 0.025 is 1/40, so without an offset
     * every 40th chunk on both axes would score precisely 0.5 no matter what
     * world you generated: a fixed, seed-independent grid of candidates, and
     * under the `dense` profile (threshold 0.45) every one of them would
     * place. That is the exact artefact this whole system exists to remove,
     * and it would have been invisible in a "does it look random" eyeball
     * test.
     *
     * Irrational offsets cannot produce an integer from a rational input, so
     * this holds for any frequency, not just the four shipped ones.
     * 1/pi and the Euler-Mascheroni constant, chosen for being unremarkable.
     */
    private static final double ORIGIN_X = 0.31830988618379067;
    private static final double ORIGIN_Z = 0.5772156649015329;

    private final int[] permutation = new int[512];

    public StructureNoise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        // Fisher-Yates, high index down, drawing from a SplitMix64 stream.
        long state = seed;
        for (int i = 255; i > 0; i--) {
            state = state + 0x9E3779B97F4A7C15L;
            long r = mix64(state);
            // Unsigned remainder: the top bit of `r` must not make this
            // negative, or the shuffle diverges from the Python mirror.
            int j = (int) Long.remainderUnsigned(r, i + 1);
            int swap = p[i];
            p[i] = p[j];
            p[j] = swap;
        }
        for (int i = 0; i < 512; i++) {
            permutation[i] = p[i & 255];
        }
    }

    /** SplitMix64 finaliser. Every step wraps to 64 bits — see the class note. */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Noise for a chunk at a given frequency, normalised to [0, 1].
     *
     * The one entry point callers should use — it owns the lattice offset, so
     * there is a single place for the Python mirror to match rather than one
     * per profile.
     */
    public double sampleChunk(int chunkX, int chunkZ, double frequency) {
        return sample(chunkX * frequency + ORIGIN_X, chunkZ * frequency + ORIGIN_Z);
    }

    /**
     * Noise at (x, z), normalised to [0, 1]. Deterministic for a given seed.
     *
     * Raw — callers wanting chunk semantics want {@link #sampleChunk}, which
     * applies the lattice offset.
     */
    public double sample(double x, double z) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        double xf = x - xi;
        double zf = z - zi;
        int gx = xi & 255;
        int gz = zi & 255;

        double u = fade(xf);
        double v = fade(zf);

        int a = permutation[gx] + gz;
        int b = permutation[gx + 1] + gz;

        double n = lerp(v,
                lerp(u, grad(permutation[a], xf, zf),
                        grad(permutation[b], xf - 1.0, zf)),
                lerp(u, grad(permutation[a + 1], xf, zf - 1.0),
                        grad(permutation[b + 1], xf - 1.0, zf - 1.0)));

        double normalised = (n * NORMALISE + 1.0) * 0.5;
        // The theoretical bound is exact, but clamp anyway: a value outside
        // [0, 1] would silently break every threshold comparison downstream.
        if (normalised < 0.0) {
            return 0.0;
        }
        return normalised > 1.0 ? 1.0 : normalised;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /**
     * 2D gradient: the low 3 bits pick one of eight vectors — the four
     * diagonals plus the four axes, every one of length sqrt(2) so the
     * NORMALISE bound above stays exact.
     *
     * Eight rather than the minimal four because with four the value at a
     * cell depends on just 2 bits from each of 4 permutation entries: 256
     * possible outcomes, so two unrelated seeds agree at roughly 1 cell in
     * 256. Harmless for placement, but it makes "did the field actually
     * change" untestable, and eight costs nothing.
     */
    private static double grad(int hash, double x, double z) {
        return switch (hash & 7) {
            case 0 -> x + z;
            case 1 -> -x + z;
            case 2 -> x - z;
            case 3 -> -x - z;
            case 4 -> x * NORMALISE;
            case 5 -> -x * NORMALISE;
            case 6 -> z * NORMALISE;
            default -> -z * NORMALISE;
        };
    }
}
