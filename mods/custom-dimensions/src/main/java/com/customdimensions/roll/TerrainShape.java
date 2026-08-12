package com.customdimensions.roll;

/**
 * Where the ground is in a column, asked of the generator's own final
 * density rather than inferred from the climate point's depth.
 *
 * <p>{@code surface_Y = 128 * depth} holds for overworld-shaped graphs and
 * for nothing else. The End's router and the Nether's both carry a CONSTANT
 * depth, so that product is one number for every column in the world — which
 * renders an island world as solid ground with holes in it, and a nether as
 * an unbroken lava sea. Final density is the rule generation itself follows:
 * above zero is the settings' default block, at or below it is air or fluid.
 *
 * <p>Sampling it directly is affordable because it skips
 * {@code ChunkNoiseSampler} entirely — vanilla's {@code getHeight} rebuilds
 * one per column, which is what made a map minutes of work. The wrapper
 * markers a router carries ({@code interpolated}, {@code cache_2d},
 * {@code flat_cache}) delegate straight to what they wrap when sampled
 * outside that sampler, and hold no state, so a function is safe to sample
 * from several threads at once as long as each has its own
 * {@code NoiseConfig}.
 */
public final class TerrainShape {

    /** Density above which a column is the settings' default block. */
    private static final double SOLID = 0.0;

    private TerrainShape() {
    }

    /**
     * A generator's final density at one block. The seam exists so every
     * decision below is arithmetic over numbers rather than over Minecraft
     * types: {@code DensityFunction}'s static codecs need a registry
     * Bootstrap to initialise, which would put this logic out of reach of a
     * unit test. {@code CandidateRender} supplies the real router's function.
     */
    @FunctionalInterface
    public interface Density {
        double at(int x, int y, int z);
    }

    /**
     * The vertical band a generator can place terrain in, and how finely a
     * column through it is walked. Both come from the generator's own
     * {@code GenerationShapeConfig} — never from a constant, and never from
     * the dimension TYPE's height, which is routinely far taller than the
     * generator that fills it. Terralith, Tectonic, Nullscape and Incendium
     * each declare their own; asking them is the only way to be right about
     * all of them at once.
     *
     * <p>{@code cellHeight} is {@code verticalCellBlockCount()}, the spacing
     * at which the generator interpolates density up a column. Density is
     * linear between two cell corners, so a cell whose corners are both at or
     * below zero is solid nowhere in between — which makes a walk on that
     * spacing exact for "is there ground here", not an approximation that
     * might step over a thin island.
     */
    public record Band(int bottomY, int topY, int cellHeight) {

        public int span() {
            return Math.max(1, this.topY - this.bottomY);
        }

        /** Blocks between rungs, at least one. */
        public int rung() {
            return Math.max(1, Math.min(this.cellHeight, this.span()));
        }
    }

    /**
     * The highest solid block in a column, or null when the column is open
     * all the way down — a void, an open sky, or the air above an island.
     *
     * <p>Walks down on {@link Band#rung()} until it finds solid, then walks
     * back up one rung a block at a time. Worst case is one column of pure
     * air, which costs the full coarse pass and nothing more.
     */
    public static Integer surfaceY(Density finalDensity, Band band, int x, int z) {
        int rung = band.rung();
        int hit = Integer.MIN_VALUE;
        for (int y = band.topY(); y >= band.bottomY(); y -= rung) {
            if (isSolid(finalDensity, x, y, z)) {
                hit = y;
                break;
            }
        }
        if (hit == Integer.MIN_VALUE) {
            return null;
        }
        // The coarse rung landed inside the ground; the surface is the last
        // solid block below the first open one above it.
        int ceiling = Math.min(band.topY(), hit + rung);
        for (int y = hit + 1; y <= ceiling; y++) {
            if (!isSolid(finalDensity, x, y, z)) {
                return y - 1;
            }
        }
        return ceiling;
    }

    public static boolean isSolid(Density finalDensity, int x, int y, int z) {
        return finalDensity.at(x, y, z) > SOLID;
    }

    /**
     * Whether {@code 128 * depth} describes this generator's surface.
     *
     * <p>A verdict measured per dimension and seed, never a list of family
     * names: at the height depth claims, real ground has solid below it and
     * open air above. Columns that answer both are columns where the cheap
     * approximation is telling the truth, and a generator whose depth is a
     * constant fails on nearly all of them.
     *
     * <p>{@code agreed} and {@code tested} are carried out rather than
     * reduced to the boolean so a render can log what it measured — a
     * verdict with no numbers behind it is indistinguishable from a guess.
     */
    public record Calibration(int agreed, int tested, boolean depthIsHeight) {

        public double agreement() {
            return this.tested == 0 ? 0.0 : this.agreed / (double) this.tested;
        }
    }

    /**
     * Fraction of probed columns that must agree before the depth
     * approximation is trusted. Well short of unanimity: coastlines,
     * overhangs and aquifers each move a real surface a few blocks away from
     * where depth puts it, and an overworld graph still clears this
     * comfortably while a constant-depth graph scores near zero.
     */
    private static final double AGREEMENT = 0.6;

    /** How far above and below the claimed surface the probe checks. */
    private static final int MARGIN = 3;

    /**
     * Runs the calibration over columns the caller has already measured a
     * depth for. Costs two density samples per column and is run once per
     * render, not once per pixel.
     */
    public static Calibration calibrate(Density finalDensity, Band band,
                                        int[] xs, int[] zs, Integer[] claimedY) {
        int agreed = 0;
        int tested = 0;
        for (int i = 0; i < claimedY.length; i++) {
            if (claimedY[i] == null) {
                continue;
            }
            int y = claimedY[i];
            if (y - MARGIN < band.bottomY() || y + MARGIN > band.topY()) {
                continue;
            }
            tested++;
            if (isSolid(finalDensity, xs[i], y - MARGIN, zs[i])
                    && !isSolid(finalDensity, xs[i], y + MARGIN, zs[i])) {
                agreed++;
            }
        }
        return new Calibration(agreed, tested,
                tested > 0 && agreed / (double) tested >= AGREEMENT);
    }
}
