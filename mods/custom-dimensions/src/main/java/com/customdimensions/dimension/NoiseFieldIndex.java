package com.customdimensions.dimension;

import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a structure group's placements land, and nothing else.
 *
 * Split out of {@link NoiseStructurePlacement} for the same reason
 * {@code FixedStructurePlacement.Index} is split out: {@code
 * StructurePlacement} drags registry-bound static init that only exists after
 * Minecraft's Bootstrap, so the interesting logic would otherwise be
 * untestable without booting a server. Only {@link ChunkPos} is touched here,
 * which is a plain data class.
 *
 * <h2>The rule</h2>
 *
 * <pre>
 * eligible(c) := radial(c) &gt; 0 AND noise(c) &gt; threshold
 * placed(c)   := eligible(c)
 *                AND no eligible c' within `exclusion(radial(c))` chunks
 *                    outranks c
 *                    (rank = a white-noise priority per chunk; ties on the
 *                     chunk key, so the order is total)
 * </pre>
 *
 * Three knobs, three jobs. The threshold decides what fraction of the world is
 * candidate material, uniformly. The radial curve scales the exclusion radius,
 * which sets how densely the candidates pack — see {@link #exclusionFor}, and
 * note that the curve value IS the relative density multiplier. The priority
 * then thins the candidates to a Poisson-disc set at that separation.
 *
 * <h3>Why the radial curve is not part of the eligibility test</h3>
 *
 * Multiplying the curve into the noise before the threshold would make it a
 * binary gate: a chunk whose weight is below the threshold could never place
 * however the noise falls, so every authored taper would become a hard wall
 * wherever it crosses the profile's threshold. The exclusion radius — not the
 * curve — is what controls packing density, so a curve folded into
 * eligibility can only decide where a group exists, never how much of it
 * there is.
 *
 * Weight 0.0 still suppresses outright, so a hard edge is still expressible;
 * it is opt-in rather than an accidental consequence of a taper.
 *
 * <h3>Why not the local maximum of the noise itself</h3>
 *
 * Local maxima of a <em>smooth</em> field occur about once per noise
 * feature, so their density is set by the frequency and nothing else: the
 * threshold barely matters (a peak is usually well clear of it) and the
 * exclusion radius is inert — a small pocket dimension can end up with a
 * single structure in the entire world, with the exclusion radius powerless
 * to raise that count. Ranking by white noise instead keeps every
 * above-threshold chunk in the running, so both dials work.
 *
 * <h3>Why not the spike's greedy spiral</h3>
 *
 * The spike keeps a candidate unless something already accepted sits too
 * close. Same density behaviour as this, but the answer depends on the visit
 * order, so the Python mirror would have to reproduce the traversal chunk for
 * chunk to pass the parity gate (F4). Ranking is the order-free form of the
 * same idea — the standard parallel formulation of dart throwing — so parity
 * becomes a set comparison rather than a walk.
 *
 * The tie-break on the chunk key exists because two chunks CAN rank
 * identically, and without it a tied pair would each see a neighbour that is
 * "not strictly higher" and both would place, breaking the separation
 * guarantee.
 *
 * MIRRORED in scripts/seed/noise_placement.py — change both together, then
 * re-run test_noise_parity.py, which diffs this against a live
 * {@code /customdim structure-census} dump with zero tolerance.
 */
public final class NoiseFieldIndex {

    /**
     * Cap on the chunk radius that gets scanned. A dimension with a huge
     * border would otherwise scan its whole area at world load; 8192 blocks
     * (512 chunks, ~1M chunks scanned) is the largest border any shipped
     * dimension uses, and is the spike's stated performance target.
     */
    public static final int MAX_RADIUS_CHUNKS = 512;

    /**
     * How far the radial curve may push the exclusion radius above a group's
     * base, and the weight at which that cap bites. Reciprocal squares of each
     * other; both spelled out as literals rather than derived so the Java and
     * Python sides cannot drift by a rounding step. Without the cap, a weight
     * approaching zero would grow the neighbourhood scan without bound.
     */
    public static final double MAX_EXCLUSION_SCALE = 4.0;
    public static final double MIN_RADIAL_WEIGHT = 0.0625;

    private final Set<Long> placements;
    private final Map<Long, ChunkPos> byRegion;
    private final List<ChunkPos> ordered;
    private final int spacing;
    // Kept only so /customdim structure-census can report the RESOLVED
    // inputs. The parity check rebuilds the field from these rather than
    // re-deriving them from config, so a failure means the maths diverged
    // and nothing else.
    private final String profileId;
    private final long noiseSeed;
    private final int exclusion;
    private final int radiusChunks;
    private final int spawnChunkX;
    private final int spawnChunkZ;
    private final double[] radial;

    /**
     * @param noiseSeed   world seed ^ dimension salt ^ group salt
     * @param profile     the group's noise shape
     * @param exclusion   minimum chunks between two placements, >= 1
     * @param radial      10-point curve, spawn -> border; null means uniform.
     *                    double[], not float[]: the Python mirror uses doubles
     *                    throughout and 1.3f != 1.3 (F4 parity).
     * @param radiusChunks playable radius in chunks (clamped to MAX_RADIUS_CHUNKS)
     * @param spawnChunkX  centre of the radial curve, in chunks
     * @param spawnChunkZ  centre of the radial curve, in chunks
     */
    public NoiseFieldIndex(long noiseSeed, NoiseProfile profile, int exclusion,
                           double[] radial, int radiusChunks, int spawnChunkX, int spawnChunkZ) {
        int r = Math.min(Math.max(radiusChunks, 0), MAX_RADIUS_CHUNKS);
        int excl = Math.max(1, exclusion);
        // The locate cell has to be sized from the SMALLEST separation the
        // curve can ask for, not the base: a cell built for the base would hold
        // two placements wherever the weight peaks, and byRegion keeps only the
        // first, so locate would silently stop finding the rest. A uniform
        // curve peaks at 1.0, which reproduces `excl * 2` exactly.
        this.spacing = Math.max(2, Math.max(1, exclusionFor(excl, maxWeight(radial))) * 2);
        this.profileId = profile.id();
        this.noiseSeed = noiseSeed;
        this.exclusion = excl;
        this.radiusChunks = r;
        this.spawnChunkX = spawnChunkX;
        this.spawnChunkZ = spawnChunkZ;
        this.radial = radial == null ? null : radial.clone();

        // Samplers are bound ONCE here rather than looked up per call.
        // NoiseProfile.evaluate resolves its sampler through a map; that
        // lookup runs once per chunk per group, ~1M times for a large
        // dimension, so binding it up front instead of on every call keeps
        // world load fast.
        double scale = NoiseProfile.frequencyScale(r);
        double frequency = profile.frequency() * scale;
        double coarseFrequency = profile.coarseFrequency() * scale;
        double coarseThreshold = profile.coarseThreshold();
        boolean cluster = profile.isCluster();
        StructureNoise primary = new StructureNoise(noiseSeed);
        StructureNoise fine = cluster
                ? new StructureNoise(noiseSeed ^ NoiseProfile.FINE_SALT) : null;

        // One pass to mark eligibility over the bounding box, so the
        // neighbourhood test below reads arrays instead of re-evaluating the
        // noise ~excl^2 times per candidate.
        int side = r * 2 + 1;
        boolean[] eligible = new boolean[side * side];
        // Ranks are cached alongside eligibility. Every eligible chunk is read
        // once as a candidate and up to `disc size` times as a neighbour, so
        // recomputing the hash on each read would cost tens of millions of
        // mix64 calls on a large dense dimension. Only eligible entries are
        // ever read.
        long[] ranks = new long[side * side];
        double rSquared = (double) r * r;
        double threshold = profile.threshold();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                double distSq = (double) dx * dx + (double) dz * dz;
                if (distSq > rSquared) {
                    continue;   // outside the world, stays false
                }
                // Weight 0.0 is the author's explicit hard suppression, and the
                // only reason the curve can rule a chunk out. Everything else
                // is candidate material at the profile's own rate, and the
                // weight decides the packing instead (see exclusionFor).
                // Tested before the noise so a suppressed band costs no Perlin
                // work; the samples are independent per chunk, so skipping one
                // cannot change another.
                if (radialWeight(radial, Math.sqrt(distSq), r) <= 0.0) {
                    continue;
                }
                int cx = spawnChunkX + dx;
                int cz = spawnChunkZ + dz;
                double noise;
                if (cluster) {
                    double coarse = primary.sampleChunk(cx, cz, coarseFrequency);
                    noise = coarse <= coarseThreshold
                            ? 0.0 : fine.sampleChunk(cx, cz, frequency);
                } else {
                    noise = primary.sampleChunk(cx, cz, frequency);
                }
                if (noise > threshold) {
                    int idx = (dz + r) * side + (dx + r);
                    eligible[idx] = true;
                    ranks[idx] = priority(noiseSeed, cx, cz);
                }
            }
        }

        // Single O(r^2) pass. Walking outward ring by ring for spawn-first
        // ordering would scan each ring's whole square and skip its interior,
        // making it O(r^3) — 1.8e8 iterations at radius 512. Order is
        // restored by sorting afterwards instead, which is both cheaper and
        // a stronger guarantee — nearest-first rather than ring-first.
        List<ChunkPos> orderedPositions = new ArrayList<>();
        for (int dz = -r; dz <= r; dz++) {
            int row = (dz + r) * side;
            for (int dx = -r; dx <= r; dx++) {
                if (!eligible[row + (dx + r)]) {
                    continue;
                }
                int cx = spawnChunkX + dx;
                int cz = spawnChunkZ + dz;
                // Each candidate is judged against ITS OWN exclusion radius,
                // which makes the relation asymmetric: a chunk in a low-weight
                // band has to beat competitors a high-weight chunk never looks
                // at. That asymmetry IS the density gradient. It stays
                // order-free — every decision reads only the eligibility and
                // rank arrays, never another decision — so parity with the
                // Python mirror is still a set comparison.
                int candidateExcl = exclusionFor(excl,
                        radialWeight(radial, Math.sqrt((double) dx * dx + (double) dz * dz), r));
                if (!outranksNeighbours(eligible, ranks, side, r, dx, dz, candidateExcl,
                        cx, cz, spawnChunkX, spawnChunkZ)) {
                    continue;
                }
                orderedPositions.add(new ChunkPos(cx, cz));
            }
        }

        // Nearest-first, ties broken on the chunk key so the order is total
        // and identical in the Python mirror. The order decides which position
        // represents a locate cell, so it has to be deterministic, not merely
        // stable.
        orderedPositions.sort((a, b) -> {
            long da = distSqFrom(a, spawnChunkX, spawnChunkZ);
            long db = distSqFrom(b, spawnChunkX, spawnChunkZ);
            if (da != db) {
                return Long.compare(da, db);
            }
            return Long.compare(a.toLong(), b.toLong());
        });

        Set<Long> found = new HashSet<>(orderedPositions.size() * 2);
        Map<Long, ChunkPos> regions = new HashMap<>();
        for (ChunkPos pos : orderedPositions) {
            found.add(pos.toLong());
            regions.putIfAbsent(regionKey(
                    Math.floorDiv(pos.x, spacing), Math.floorDiv(pos.z, spacing)), pos);
        }

        this.placements = Set.copyOf(found);
        this.ordered = List.copyOf(orderedPositions);
        this.byRegion = Collections.unmodifiableMap(regions);
    }

    private static long distSqFrom(ChunkPos pos, int spawnChunkX, int spawnChunkZ) {
        long dx = pos.x - (long) spawnChunkX;
        long dz = pos.z - (long) spawnChunkZ;
        return dx * dx + dz * dz;
    }

    /**
     * A chunk's rank among competing candidates: white noise, deliberately
     * uncorrelated with the placement field so that ranking thins candidates
     * without re-expressing the same shape.
     *
     * Compared as UNSIGNED — a signed comparison would systematically favour
     * whichever half of the range came out negative.
     */
    static long priority(long noiseSeed, int chunkX, int chunkZ) {
        long h = noiseSeed
                ^ (chunkX * 0x9E3779B97F4A7C15L)
                ^ (chunkZ * 0xC2B2AE3D27D4EB4FL);
        return StructureNoise.mix64(h);
    }

    /**
     * True when no other eligible chunk within the exclusion disc outranks
     * this one. Ties fall back to the chunk key so the relation is a strict
     * total order and exactly one of a tied pair can win.
     */
    private static boolean outranksNeighbours(boolean[] eligible, long[] ranks, int side, int r,
                                              int dx, int dz, int exclusion,
                                              int cx, int cz, int spawnChunkX, int spawnChunkZ) {
        long key = ChunkPos.toLong(cx, cz);
        long rank = ranks[(dz + r) * side + (dx + r)];
        int exclSq = exclusion * exclusion;
        for (int oz = -exclusion; oz <= exclusion; oz++) {
            for (int ox = -exclusion; ox <= exclusion; ox++) {
                if (ox == 0 && oz == 0) {
                    continue;
                }
                if (ox * ox + oz * oz > exclSq) {
                    continue;
                }
                int nx = dx + ox;
                int nz = dz + oz;
                if (nx < -r || nx > r || nz < -r || nz > r) {
                    continue;   // outside the world: nothing can be there
                }
                int nIdx = (nz + r) * side + (nx + r);
                if (!eligible[nIdx]) {
                    continue;
                }
                int cmp = Long.compareUnsigned(ranks[nIdx], rank);
                if (cmp > 0) {
                    return false;
                }
                if (cmp == 0 && ChunkPos.toLong(spawnChunkX + nx, spawnChunkZ + nz) < key) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Samples the 10-point radial curve at a distance, linearly interpolated.
     * A null curve, or a zero-radius world, is uniform weight 1.
     */
    static double radialWeight(double[] radial, double distChunks, int radiusChunks) {
        if (radial == null || radial.length == 0) {
            return 1.0;
        }
        if (radiusChunks <= 0) {
            return radial[0];
        }
        double fraction = distChunks / radiusChunks;
        if (fraction < 0.0) {
            fraction = 0.0;
        }
        if (fraction >= 1.0) {
            return radial[radial.length - 1];
        }
        double scaled = fraction * (radial.length - 1);
        int lo = (int) scaled;
        int hi = Math.min(lo + 1, radial.length - 1);
        double t = scaled - lo;
        return radial[lo] + t * (radial[hi] - radial[lo]);
    }

    /**
     * The minimum separation a chunk of this radial weight asks for.
     *
     * A Poisson-disc set with minimum separation d has density proportional to
     * 1/d^2, so d = base / sqrt(weight) makes the placement density directly
     * proportional to the weight. That is what lets the config say "1.35" and
     * mean "35% denser here than this group's own rate" — the curve value IS
     * the density multiplier, and a curve is a density profile.
     *
     * Weight 0.0 returns 0, which the caller reads as ineligible rather than as
     * a separation. {@link #MIN_RADIAL_WEIGHT} caps how large the disc can get,
     * because the neighbourhood scan is O(d^2) per candidate.
     *
     * MIRRORED in scripts/seed/noise_placement.exclusion_for — Math.round is
     * floor(x + 0.5), which is NOT Python's banker's rounding, so the mirror
     * uses its own _java_round rather than round().
     */
    static int exclusionFor(int baseExclusion, double weight) {
        if (weight <= 0.0) {
            return 0;
        }
        double w = weight > MIN_RADIAL_WEIGHT ? weight : MIN_RADIAL_WEIGHT;
        return Math.max(1, (int) Math.round(baseExclusion / Math.sqrt(w)));
    }

    /** The curve's peak, i.e. the densest weight it can ask for. */
    static double maxWeight(double[] radial) {
        if (radial == null || radial.length == 0) {
            return 1.0;
        }
        double max = radial[0];
        for (double v : radial) {
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    /** Chunk-cell size vanilla's locate walks; one placement per cell at most. */
    public int spacing() {
        return spacing;
    }

    public boolean isPlacement(int chunkX, int chunkZ) {
        return placements.contains(ChunkPos.toLong(chunkX, chunkZ));
    }

    /**
     * The placement vanilla's locate should consider for the cell containing
     * this chunk. Mirrors {@code FixedStructurePlacement.Index.startFor}: an
     * empty cell answers with its origin, which can never pass
     * {@link #isPlacement}.
     */
    public ChunkPos startFor(int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, spacing);
        int regionZ = Math.floorDiv(chunkZ, spacing);
        ChunkPos hit = byRegion.get(regionKey(regionX, regionZ));
        return hit != null ? hit : new ChunkPos(regionX * spacing, regionZ * spacing);
    }

    /** Every placement, spawn-outward. Used by the census command and tests. */
    public List<ChunkPos> positions() {
        return ordered;
    }

    public int size() {
        return ordered.size();
    }

    // --- resolved inputs, for the census dump -----------------------------

    public String profileId() {
        return profileId;
    }

    public long noiseSeed() {
        return noiseSeed;
    }

    public int exclusion() {
        return exclusion;
    }

    /** Clamped to MAX_RADIUS_CHUNKS, i.e. what was actually scanned. */
    public int radiusChunks() {
        return radiusChunks;
    }

    public int spawnChunkX() {
        return spawnChunkX;
    }

    public int spawnChunkZ() {
        return spawnChunkZ;
    }

    public double[] radial() {
        return radial == null ? null : radial.clone();
    }
}
