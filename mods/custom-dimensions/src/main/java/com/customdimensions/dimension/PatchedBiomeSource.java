package com.customdimensions.dimension;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A biome source wrapper for the "biomePatches" config (precision
 * placement). Three modes per patch:
 *
 * - STAMP (no replace): the circle claims every column.
 * - CLIPPED SWAP (replace set, scope "clip"): within the circle, only
 *   columns the delegate resolves to the target biome are substituted —
 *   the natural blob keeps its organic shape ("*" matches any biome).
 * - GLOBAL SWAP (scope "global"): dimension-wide wholesale replacement,
 *   shape preserved everywhere. An explicit replace id swaps that biome;
 *   no replace (or "*") makes the circle a SELECTOR — at first resolution
 *   the delegate is sampled across it and every distinct biome touching
 *   the circle is swapped globally. There is no per-blob identity in a
 *   biome source (worldgen is local and pure), so "replace that mesa past
 *   the radius" necessarily means ALL instances of that biome.
 *
 * Edge blending: `blend` (blocks) jitters the effective radius of stamp
 * and clipped-swap circles with smooth deterministic value noise (4-quart
 * lattice, smoothstep interpolation, splitmix-style hash salted by patch
 * index), so borders wobble organically instead of reading as compass
 * circles. blend 0 = razor edge. Global mode has no edge.
 *
 * Coordinates are QUARTS (block >> 2) at query time; config values are
 * BLOCKS, converted once. Precedence: local patches in config order
 * (a non-matching clipped swap falls through), then global rules on the
 * delegate's answer.
 *
 * Mirrored by the roller's PatchedBiomeSampler — keep the containment
 * test, jitter formula, and precedence in sync.
 *
 * Serialisation: the CODEC round-trips delegate + patch list. Selector
 * resolution is lazy but deterministic, so a level.dat round-trip
 * reproduces the same layout without persisting the resolved set.
 */
public final class PatchedBiomeSource extends BiomeSource {

    public static final int DEFAULT_BLEND = 8;
    /** Selector circles are sampled at boot; cap the sweep, not the config. */
    public static final int SELECTOR_SAMPLE_CAP_BLOCKS = 256;

    public record Patch(RegistryEntry<Biome> biome, int centerX, int centerZ, int radius,
                        java.util.Optional<String> replace, int blend, String scope, String shape) {
        public static final Codec<Patch> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Biome.REGISTRY_CODEC.fieldOf("biome").forGetter(Patch::biome),
                Codec.INT.optionalFieldOf("x", 0).forGetter(Patch::centerX),
                Codec.INT.optionalFieldOf("z", 0).forGetter(Patch::centerZ),
                Codec.intRange(1, 65536).optionalFieldOf("radius", 1).forGetter(Patch::radius),
                Codec.STRING.optionalFieldOf("replace").forGetter(Patch::replace),
                Codec.intRange(0, 64).optionalFieldOf("blend", DEFAULT_BLEND).forGetter(Patch::blend),
                Codec.STRING.optionalFieldOf("scope", "clip").forGetter(Patch::scope),
                Codec.STRING.optionalFieldOf("shape", "circle").forGetter(Patch::shape))
                .apply(instance, Patch::new));

        public boolean isGlobal() {
            return "global".equals(this.scope());
        }

        public boolean isSquare() {
            return "square".equals(this.shape());
        }
    }

    public static final MapCodec<PatchedBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("delegate").forGetter(s -> s.delegate),
            Patch.CODEC.listOf().fieldOf("patches").forGetter(s -> s.patches))
            .apply(instance, PatchedBiomeSource::new));

    private final BiomeSource delegate;
    private final List<Patch> patches;
    private final List<Patch> localPatches;
    private final List<Patch> globalSelectors;
    // Quart-space geometry per local patch: centreQX, centreQZ, radiusQ, blendQ, salt.
    private final long[] localGeometry;
    // targetBiomeId -> replacement. Explicit rules land at construction;
    // selector results are added once by resolveGlobalSelectors.
    private final Map<String, RegistryEntry<Biome>> globalMap =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean selectorsResolved;

    public PatchedBiomeSource(BiomeSource delegate, List<Patch> patches) {
        this.delegate = delegate;
        this.patches = List.copyOf(patches);
        this.localPatches = new ArrayList<>();
        this.globalSelectors = new ArrayList<>();
        for (int i = 0; i < this.patches.size(); i++) {
            Patch p = this.patches.get(i);
            if (p.isGlobal()) {
                String target = p.replace().orElse("*");
                if ("*".equals(target)) {
                    this.globalSelectors.add(p);
                } else {
                    this.globalMap.put(target, p.biome());
                }
            } else {
                this.localPatches.add(p);
            }
        }
        this.selectorsResolved = this.globalSelectors.isEmpty();
        this.localGeometry = new long[this.localPatches.size() * 6];
        for (int i = 0; i < this.localPatches.size(); i++) {
            Patch p = this.localPatches.get(i);
            this.localGeometry[i * 6] = p.centerX() >> 2;
            this.localGeometry[i * 6 + 1] = p.centerZ() >> 2;
            this.localGeometry[i * 6 + 2] = Math.max(1, p.radius() >> 2);
            this.localGeometry[i * 6 + 3] = p.blend() > 0 ? Math.max(1, p.blend() >> 2) : 0;
            this.localGeometry[i * 6 + 4] = i;  // jitter salt: stable config order
            this.localGeometry[i * 6 + 5] = p.isSquare() ? 1 : 0;
        }
    }

    @Override
    protected MapCodec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    @Override
    protected Stream<RegistryEntry<Biome>> biomeStream() {
        return Stream.concat(this.delegate.getBiomes().stream(),
                this.patches.stream().map(Patch::biome));
    }

    @Override
    public RegistryEntry<Biome> getBiome(int x, int y, int z, MultiNoiseUtil.MultiNoiseSampler noise) {
        RegistryEntry<Biome> resolved = null;  // delegate answer, computed at most once
        for (int i = 0; i < this.localPatches.size(); i++) {
            long dx = x - this.localGeometry[i * 6];
            long dz = z - this.localGeometry[i * 6 + 1];
            long radiusQ = this.localGeometry[i * 6 + 2];
            long blendQ = this.localGeometry[i * 6 + 3];
            double effective = blendQ == 0 ? radiusQ
                    : radiusQ + jitterNoise(x, z, this.localGeometry[i * 6 + 4]) * blendQ;
            boolean inside = this.localGeometry[i * 6 + 5] == 1
                    ? Math.max(Math.abs(dx), Math.abs(dz)) <= effective          // square (Chebyshev)
                    : (double) (dx * dx + dz * dz) <= effective * effective;      // circle
            if (!inside) {
                continue;
            }
            Patch patch = this.localPatches.get(i);
            if (patch.replace().isEmpty() || "*".equals(patch.replace().get())) {
                return patch.biome();  // stamp
            }
            if (resolved == null) {
                resolved = this.delegate.getBiome(x, y, z, noise);
            }
            if (idOf(resolved).equals(patch.replace().get())) {
                return patch.biome();  // clipped swap, shape preserved
            }
        }
        if (!this.selectorsResolved) {
            this.resolveGlobalSelectors(noise);
        }
        if (!this.globalMap.isEmpty()) {
            if (resolved == null) {
                resolved = this.delegate.getBiome(x, y, z, noise);
            }
            RegistryEntry<Biome> replacement = this.globalMap.get(idOf(resolved));
            if (replacement != null) {
                return replacement;
            }
        }
        return resolved != null ? resolved : this.delegate.getBiome(x, y, z, noise);
    }

    // Selector circles: sample the delegate across each circle once and swap
    // every distinct biome that touches it. Deterministic (delegate is
    // seed-pure), so lazy resolution survives save/load identically.
    private synchronized void resolveGlobalSelectors(MultiNoiseUtil.MultiNoiseSampler noise) {
        if (this.selectorsResolved) {
            return;
        }
        for (Patch p : this.globalSelectors) {
            long cqx = p.centerX() >> 2;
            long cqz = p.centerZ() >> 2;
            long qr = Math.max(1, Math.min(p.radius(), SELECTOR_SAMPLE_CAP_BLOCKS) >> 2);
            String own = idOf(p.biome());
            for (long dz = -qr; dz <= qr; dz++) {
                for (long dx = -qr; dx <= qr; dx++) {
                    if (!p.isSquare() && dx * dx + dz * dz > qr * qr) {
                        continue;
                    }
                    RegistryEntry<Biome> found = this.delegate.getBiome(
                            (int) (cqx + dx), 16, (int) (cqz + dz), noise);
                    String id = idOf(found);
                    if (!id.isEmpty() && !id.equals(own)) {
                        this.globalMap.putIfAbsent(id, p.biome());
                    }
                }
            }
            com.customdimensions.MultiverseServer.LOGGER.info(
                    "biomePatches selector at ({}, {}) r{}: {} biome(s) now swap to {}",
                    p.centerX(), p.centerZ(), p.radius(), this.globalMap.size(), own);
        }
        this.selectorsResolved = true;
    }

    private static String idOf(RegistryEntry<Biome> entry) {
        return entry.getKey().map(k -> k.getValue().toString()).orElse("");
    }

    /**
     * Smooth deterministic value noise in [-1, 1]: 4-quart lattice,
     * smoothstep-interpolated corner hashes. Mirrored bit-for-bit by the
     * roller's PatchedBiomeSampler — change BOTH or neither.
     */
    static double jitterNoise(long qx, long qz, long salt) {
        long lx = qx >> 2;
        long lz = qz >> 2;
        double fx = (qx & 3) / 4.0;
        double fz = (qz & 3) / 4.0;
        double v00 = hashUnit(lx, lz, salt);
        double v10 = hashUnit(lx + 1, lz, salt);
        double v01 = hashUnit(lx, lz + 1, salt);
        double v11 = hashUnit(lx + 1, lz + 1, salt);
        double sx = fx * fx * (3.0 - 2.0 * fx);
        double sz = fz * fz * (3.0 - 2.0 * fz);
        double a = v00 + (v10 - v00) * sx;
        double b = v01 + (v11 - v01) * sx;
        return a + (b - a) * sz;
    }

    static double hashUnit(long x, long z, long salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x100000001B3L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53 * 2.0 - 1.0;
    }
}
