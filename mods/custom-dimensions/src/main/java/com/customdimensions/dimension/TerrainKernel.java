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
 * ignores them) and their density contribution is computed here, applied
 * on top of the chunk's final density by {@link KernelDensity}.
 *
 * Density conventions follow vanilla's: positive fills terrain, negative
 * carves it. Magnitudes start from the ±0.8 band the vanilla beards use but
 * may exceed it where the shape needs authority over terrain (the moat's
 * channel carve). All maths is pure (box + query position) so the shapes
 * are unit-testable without Bootstrap.
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
    MOAT,

    /**
     * A dry pocket: the structure's box (plus a margin) is excluded from the
     * aquifer's fluid placement, so a dungeon below the water table
     * generates drained — "dungeon at the bottom of a lake". Contributes no
     * density itself; the aquifer's own barrier noise seals the pocket's
     * boundary with stone wherever it meets water. See
     * {@link #wrapFluidSampler}.
     */
    DRAIN;

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
            case "drain" -> DRAIN;
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
                // a walkable motte, not a sheer plinth. 2.5 at the core:
                // the pedestal is a GUARANTEE (the hill is made, not
                // searched for), so it must dominate the density deficit of
                // a real water column, not just a dry dip.
                double radius = 2.0 + depth * 0.75;
                yield MathHelper.clampedMap(dxz - radius, 0.0, 8.0, 2.5, 0.0);
            }
            case PLATFORM_SKIRT -> {
                if (depth <= 0 || depth > SKIRT_DEPTH) {
                    yield 0.0;
                }
                // Flat within the apron; beyond it the edge slopes out half
                // a block per block of depth. 2.0 core for the same reason
                // as the pedestal's 2.5: the terrace is guaranteed, and a
                // sub-1.0 fill loses to any real water column.
                double edge = SKIRT_APRON + depth * 0.5;
                yield MathHelper.clampedMap(dxz - edge, 0.0, 5.0, 2.0, 0.0);
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
                // -1.2 exceeds the vanilla ±0.8 beard band deliberately:
                // at full strength the carve must beat near-surface density
                // a few blocks under a rise, or the ring fades into any
                // modest hillside.
                yield -1.2 * ring * vert;
            }
            // Density-neutral: the drain works through the aquifer's fluid
            // levels, not the terrain shape.
            case DRAIN -> 0.0;
        };
    }

    /** Horizontal reach of a drain pocket beyond the piece box. Sized so the
     *  aquifer's 16-block-cell sample points inside the pocket all read dry;
     *  the barrier noise then seals the boundary against neighbouring water. */
    static final int DRAIN_MARGIN_XZ = 12;
    static final int DRAIN_MARGIN_UP = 6;
    static final int DRAIN_MARGIN_DOWN = 2;

    /** Expanded dry boxes for the DRAIN pieces in a list (empty when none). */
    public static List<BlockBox> drainBoxes(List<Piece> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return List.of();
        }
        List<BlockBox> out = new ObjectArrayList<>();
        for (Piece piece : pieces) {
            if (piece.kernel() != DRAIN) {
                continue;
            }
            BlockBox b = piece.box();
            out.add(new BlockBox(
                    b.getMinX() - DRAIN_MARGIN_XZ, b.getMinY() - DRAIN_MARGIN_DOWN,
                    b.getMinZ() - DRAIN_MARGIN_XZ, b.getMaxX() + DRAIN_MARGIN_XZ,
                    b.getMaxY() + DRAIN_MARGIN_UP, b.getMaxZ() + DRAIN_MARGIN_XZ));
        }
        return out;
    }

    /**
     * Wraps a chunk's fluid-level sampler so every query inside a DRAIN
     * piece's expanded box answers "dry". Sample points feeding the aquifer
     * read air there, the pocket generates unflooded, and the aquifer's own
     * barrier noise builds the stone shell where dry cells meet wet ones —
     * the vanilla machinery doing exactly what it does between any two
     * mismatched aquifer cells. Returns the original sampler untouched when
     * the piece list has no drains, so drain-free chunks pay nothing.
     */
    public static net.minecraft.world.gen.chunk.AquiferSampler.FluidLevelSampler
            wrapFluidSampler(
                    List<Piece> pieces,
                    net.minecraft.world.gen.chunk.AquiferSampler.FluidLevelSampler original) {
        List<BlockBox> boxes = drainBoxes(pieces);
        if (boxes.isEmpty()) {
            return original;
        }
        net.minecraft.world.gen.chunk.AquiferSampler.FluidLevel dry =
                new net.minecraft.world.gen.chunk.AquiferSampler.FluidLevel(
                        Integer.MIN_VALUE,
                        net.minecraft.block.Blocks.AIR.getDefaultState());
        return (x, y, z) -> {
            for (BlockBox box : boxes) {
                if (box.contains(x, y, z)) {
                    return dry;
                }
            }
            return original.getFluidLevel(x, y, z);
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
     * Pieces collected at the factory's HEAD, waiting for the constructor
     * hook to attach them. Moog's Structures REPLACES the factory's return
     * value from a cancellable RETURN callback (it builds a fresh sampler
     * via the public constructor and setReturnValue's it), and a cancel
     * skips every callback inserted after it — so no RETURN-side attach can
     * ever reach the instance the noise fill actually samples. The
     * constructor is the one point every instance passes through, vanilla's
     * original and Moog's rebuild alike; the stash bridges the two hooks on
     * the generation thread. Cleared at the next factory HEAD.
     */
    private static final ThreadLocal<List<Piece>> PENDING = new ThreadLocal<>();

    public static void setPending(List<Piece> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            PENDING.remove();
        } else {
            PENDING.set(pieces);
        }
    }

    public static List<Piece> pending() {
        return PENDING.get();
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
