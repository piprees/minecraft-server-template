package com.customdimensions.dimension;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shape of a structure group's noise field: how coarse it is, how much of
 * it clears the bar, and how far apart the results must stay.
 *
 * A profile is a value, not a sampler — {@link #evaluate(long, int, int)}
 * binds it to a seed on demand, caching the permutation table per seed (the
 * key space is groups x dimensions, so a handful of entries).
 *
 * <pre>
 * natural   freq 0.025  thresh 0.68  excl 1.0x   even, slightly sparser than vanilla
 * dense     freq 0.040  thresh 0.45  excl 0.6x   packed
 * sparse    freq 0.015  thresh 0.85  excl 1.5x   an occasional structure is an event
 * cluster   dual layer               excl 0.4x   empty wastes, then a dense pocket
 * </pre>
 *
 * `none` is not a profile — it is the absence of one, represented by a null
 * profile for that group. Making it a record would mean every caller had to
 * remember to test for it before building a placement.
 *
 * MIRRORED in scripts/seed/structure_placement.py (PROFILES). The constants
 * here are the contract; change both together.
 */
public sealed interface NoiseProfile permits NoiseProfile.Simple, NoiseProfile.Cluster {

    /**
     * The radius a profile's frequencies are quoted for. Frequency scales as
     * {@code REFERENCE_RADIUS_CHUNKS / radiusChunks}, so every dimension sees
     * the same NUMBER of noise features whatever its size.
     *
     * Without this, `sparse`'s 0.015 frequency is a 67-chunk lattice period,
     * and a 1024-block dimension (64 chunks radius, 128 across) spans about
     * two periods: the noise over the whole world is one blob, and whether a
     * group gets anything at all is a coin flip on where its peak lands.
     * 512 chunks = 8192 blocks, the largest shipped border — the reference
     * radius must cover it, or the biggest dimension hits the same one-blob
     * failure this scaling exists to prevent.
     */
    int REFERENCE_RADIUS_CHUNKS = 512;

    static double frequencyScale(int radiusChunks) {
        if (radiusChunks <= 0) {
            return 1.0;
        }
        return (double) REFERENCE_RADIUS_CHUNKS / radiusChunks;
    }

    /** Config name (`natural`, `dense`, `sparse`, `cluster`). */
    String id();

    /** Score above which a chunk is a placement candidate. */
    double threshold();

    /** Scales the group's base exclusion radius. */
    double exclusionMultiplier();

    /** Primary layer frequency, before the radius scale. */
    double frequency();

    /** Coarse layer frequency for cluster, or 0 for single-layer profiles. */
    double coarseFrequency();

    /** Coarse layer threshold for cluster, or 0 for single-layer profiles. */
    double coarseThreshold();

    /** True when a second, coarse layer gates the primary one. */
    boolean isCluster();

    /**
     * Noise value at a chunk for this profile, in [0, 1]. The radial curve is
     * applied by the caller, not here — this is the raw field.
     *
     * Convenience for tests and one-off probes: it resolves the sampler
     * through a map on every call. {@link NoiseFieldIndex} binds the samplers
     * once instead — that map lookup in the per-chunk loop was most of a
     * 3.4-second world load.
     */
    double evaluate(long seed, int chunkX, int chunkZ);

    // Samplers are pure functions of their seed, so one cache serves every
    // profile and every world. Bounded by (dimensions x groups x 2).
    Map<Long, StructureNoise> SAMPLERS = new ConcurrentHashMap<>();

    static StructureNoise sampler(long seed) {
        return SAMPLERS.computeIfAbsent(seed, StructureNoise::new);
    }

    /** Decorrelates a cluster profile's fine layer from its coarse one. */
    long FINE_SALT = 0xDEADL;

    /** Single-layer profiles: natural, dense, sparse. */
    record Simple(String id, double frequency, double threshold,
                  double exclusionMultiplier) implements NoiseProfile {
        @Override
        public double coarseFrequency() {
            return 0.0;
        }

        @Override
        public double coarseThreshold() {
            return 0.0;
        }

        @Override
        public boolean isCluster() {
            return false;
        }

        @Override
        public double evaluate(long seed, int chunkX, int chunkZ) {
            return sampler(seed).sampleChunk(chunkX, chunkZ, frequency);
        }
    }

    /**
     * Two layers: a coarse field selects which regions are active at all, a
     * fine field places within them. Returns 0 outside an active region, so a
     * single threshold comparison downstream still works.
     */
    record Cluster(double coarseFrequency, double frequency,
                   double coarseThreshold, double threshold,
                   double exclusionMultiplier) implements NoiseProfile {

        @Override
        public String id() {
            return "cluster";
        }

        @Override
        public boolean isCluster() {
            return true;
        }

        @Override
        public double evaluate(long seed, int chunkX, int chunkZ) {
            double coarse = sampler(seed).sampleChunk(chunkX, chunkZ, coarseFrequency);
            if (coarse <= coarseThreshold) {
                return 0.0;
            }
            return sampler(seed ^ FINE_SALT).sampleChunk(chunkX, chunkZ, frequency);
        }
    }

    NoiseProfile NATURAL = new Simple("natural", 0.025, 0.68, 1.0);
    NoiseProfile DENSE = new Simple("dense", 0.040, 0.45, 0.6);
    NoiseProfile SPARSE = new Simple("sparse", 0.015, 0.85, 1.5);
    NoiseProfile CLUSTER = new Cluster(0.008, 0.05, 0.90, 0.40, 0.4);

    /**
     * Config string to profile. Returns null for `none`, for null/empty, and
     * for anything unrecognised — an unknown profile name suppressing the
     * group loudly is better than it silently becoming `natural`, and callers
     * already have to handle the `none` null.
     *
     * @param onUnknown invoked with the offending value when it is neither a
     *                  known profile nor `none`, so the caller can warn with
     *                  its own context (dimension and group names).
     */
    static NoiseProfile fromString(String value, java.util.function.Consumer<String> onUnknown) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "natural" -> NATURAL;
            case "dense" -> DENSE;
            case "sparse" -> SPARSE;
            case "cluster" -> CLUSTER;
            case "none" -> null;
            default -> {
                onUnknown.accept(value);
                yield null;
            }
        };
    }

    /** Convenience for callers with no context to add. */
    static NoiseProfile fromString(String value) {
        return fromString(value, ignored -> {
        });
    }
}
