package com.customdimensions.dimension;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.StructurePiece;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.StructureWeightSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;

import java.util.List;

/**
 * Custom terrain-adaptation kernels — the shapes vanilla's
 * {@code StructureTerrainAdaptation} enum cannot express ("castle on a
 * hill", "dungeon under a lake"). Never an enum extension: structures
 * carrying a kernel read as {@code NONE} to vanilla (so its Beardifier
 * ignores them) and their density contribution is computed here, added on
 * top of the vanilla sampler by {@link Sampler}.
 *
 * Density conventions follow vanilla's: positive fills terrain, negative
 * carves it, and per-piece magnitudes sit in the ±0.8 band the vanilla
 * beards use. All maths is pure (box + query position) so the shapes are
 * unit-testable without Bootstrap.
 *
 * Generation-affecting: kernel names flow through the same
 * {@code structures.terrainAdaptation} strings the fingerprint already
 * captures (dimension_profiles.generation_payload fingerprints the INPUTS),
 * so adopting a kernel re-rolls that dimension with no payload change.
 */
public enum TerrainKernel {

    /**
     * A hill under the structure: solid support inside the footprint and a
     * cone widening with depth below the grounded base — the motte a castle
     * stands on. Never carves.
     */
    PEDESTAL,

    /**
     * A flat platform under the footprint plus a short apron, with edges
     * that slope out as they descend — a terrace rather than a hill.
     */
    PLATFORM_SKIRT,

    /**
     * An annular depression around the footprint at base level — the ring a
     * moat fills (with the dimension's fluid where it dips below sea
     * level). Leaves the footprint itself untouched.
     */
    MOAT;

    /** Depth below the base a pedestal keeps building support for. */
    static final int PEDESTAL_DEPTH = 64;
    static final int SKIRT_DEPTH = 48;
    static final int SKIRT_APRON = 4;
    static final int MOAT_INNER = 2;
    static final int MOAT_OUTER = 9;
    static final int MOAT_FLOOR = 7;
    static final int MOAT_RIM = 8;

    public static TerrainKernel parse(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "pedestal" -> PEDESTAL;
            case "platform_skirt" -> PLATFORM_SKIRT;
            case "moat" -> MOAT;
            default -> null;
        };
    }

    /**
     * This kernel's density contribution at block (i, j, k) for a piece
     * whose grounded base is {@code baseY}. {@code dx}/{@code dz} are the
     * XZ distances outside the box (0 inside), vanilla's {@code m}/{@code n}.
     */
    double contribution(int dx, int dz, int j, int baseY, BlockBox box) {
        double dxz = MathHelper.magnitude(dx, 0.0, dz);
        int depth = baseY - j;
        return switch (this) {
            case PEDESTAL -> {
                if (depth <= 0 || depth > PEDESTAL_DEPTH) {
                    yield 0.0;
                }
                // The cone's radius grows three blocks out per four down —
                // a walkable motte, not a sheer plinth.
                double radius = 2.0 + depth * 0.75;
                yield MathHelper.clampedMap(dxz - radius, 0.0, 6.0, 0.85, 0.0);
            }
            case PLATFORM_SKIRT -> {
                if (depth <= 0 || depth > SKIRT_DEPTH) {
                    yield 0.0;
                }
                // Flat within the apron; beyond it the edge slopes out half
                // a block per block of depth.
                double edge = SKIRT_APRON + depth * 0.5;
                yield MathHelper.clampedMap(dxz - edge, 0.0, 4.0, 0.8, 0.0);
            }
            case MOAT -> {
                if (dxz < MOAT_INNER || dxz > MOAT_OUTER + 6) {
                    yield 0.0;
                }
                int below = baseY - j;      // positive under base level
                int above = j - baseY;      // positive over it
                if (below > MOAT_FLOOR || above > MOAT_RIM) {
                    yield 0.0;
                }
                // Ring falloff: full strength across the channel, easing
                // over the outer bank; vertical falloff eases toward the
                // floor so the channel bottom is rounded.
                double ring = MathHelper.clampedMap(dxz - MOAT_OUTER, 0.0, 6.0, 1.0, 0.0);
                double vert = below > 0
                        ? MathHelper.clampedMap(below, 0.0, MOAT_FLOOR, 1.0, 0.15)
                        : MathHelper.clampedMap(above, 0.0, MOAT_RIM, 1.0, 0.0);
                yield -0.8 * ring * vert;
            }
        };
    }

    /** One kernel-tagged piece: box + kernel + grounded base offset. */
    public record Piece(BlockBox box, TerrainKernel kernel, int groundLevelDelta) {

        double sample(int i, int j, int k) {
            BlockBox b = box();
            int dx = Math.max(0, Math.max(b.getMinX() - i, i - b.getMaxX()));
            int dz = Math.max(0, Math.max(b.getMinZ() - k, k - b.getMaxZ()));
            int baseY = b.getMinY() + groundLevelDelta();
            return kernel().contribution(dx, dz, j, baseY, b);
        }
    }

    /**
     * Duck interface the StructureWeightSampler mixin implements so kernel
     * pieces ride ON the vanilla instance instead of a wrapper. A wrapper
     * subclass NPE'd live: Moog's Structures ducks the same class for its
     * own EnhancedBeardifier, and replacing the factory's return value left
     * Moog's per-instance state split across two objects (its handler read
     * a null iterator on the bare delegate). Augment in place — the pattern
     * that mod itself proves compatible.
     */
    public interface Carrier {
        void customdimensions$setKernelPieces(List<Piece> pieces);

        List<Piece> customdimensions$getKernelPieces();
    }

    // WIP diagnostics for the open sample-time bug (worklog 2026-08-01
    // 21:2x) — debug level, removed when the kernel path is proven.
    private static final java.util.concurrent.atomic.AtomicInteger ATTACHES =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger SAMPLES =
            new java.util.concurrent.atomic.AtomicInteger();

    public static void debugAttach(net.minecraft.util.math.ChunkPos pos, int n, int id) {
        int c = ATTACHES.incrementAndGet();
        boolean window = pos.x >= 0 && pos.x <= 4 && pos.z >= -12 && pos.z <= -7;
        if (window) {
            com.customdimensions.MultiverseServer.LOGGER.info(
                    "KERNELDBG win chunk {} pieces={} id={} thread={} (call #{})",
                    pos, n, id, Thread.currentThread().getName(), c);
        }
        if (n > 0) {
            com.customdimensions.MultiverseServer.LOGGER.debug(
                    "KERNELDBG attach chunk {} pieces={} id={} (call #{})", pos, n, id, c);
        }
    }

    public static void debugSample(int id, boolean withPieces) {
        debugSample(id, withPieces, null);
    }

    public static void debugSample(int id, boolean withPieces, Object self) {
        int c = SAMPLES.incrementAndGet();
        if (withPieces && c < 1_000_000) {
            com.customdimensions.MultiverseServer.LOGGER.debug(
                    "KERNELDBG sample WITH pieces id={} (count {})", id, c);
            SAMPLES.set(1_000_000);
        } else if (c == 1 || c == 500) {
            com.customdimensions.MultiverseServer.LOGGER.info(
                    "KERNELDBG sample no-pieces id={} class={} (count {})",
                    id, self == null ? "?" : self.getClass().getName(), c);
        }
    }

    /** Kernel density sum at (i, j, k) for a piece list (null/empty = 0). */
    public static double sampleAll(List<Piece> pieces, DensityFunction.NoisePos pos) {
        if (pieces == null || pieces.isEmpty()) {
            return 0.0;
        }
        double d = 0.0;
        int i = pos.blockX();
        int j = pos.blockY();
        int k = pos.blockZ();
        for (Piece piece : pieces) {
            d += piece.sample(i, j, k);
        }
        return d;
    }

    /**
     * Kernel-tagged pieces for this chunk, mirroring the collection rules of
     * {@code createStructureWeightSampler}: intersecting pieces within the
     * 12-block margin, RIGID projection only for pool pieces, ground level
     * delta from the pool piece. Empty when nothing armed matches.
     */
    public static List<Piece> collect(StructureAccessor world, ChunkPos pos) {
        List<Piece> out = new ObjectArrayList<>();
        world.getStructureStarts(pos,
                        structure -> TerrainAdaptationOverride.armedKernel(structure) != null)
                .forEach(start -> {
                    TerrainKernel kernel =
                            TerrainAdaptationOverride.armedKernel(start.getStructure());
                    if (kernel == null) {
                        return;
                    }
                    for (StructurePiece piece : start.getChildren()) {
                        if (!piece.intersectsChunk(pos, 12)) {
                            continue;
                        }
                        if (piece instanceof PoolStructurePiece pool) {
                            if (pool.getPoolElement().getProjection()
                                    == net.minecraft.structure.pool.StructurePool.Projection.RIGID) {
                                out.add(new Piece(pool.getBoundingBox(), kernel,
                                        pool.getGroundLevelDelta()));
                            }
                        } else {
                            out.add(new Piece(piece.getBoundingBox(), kernel, 0));
                        }
                    }
                });
        return out;
    }
}
