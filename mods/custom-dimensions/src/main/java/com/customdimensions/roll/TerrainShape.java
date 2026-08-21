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
     * Open-dimension form. See {@link #surfaceY(Density, Band, int, int,
     * boolean)} — this is that method with no ceiling, kept because most
     * callers have no ceiling to declare.
     */
    public static Integer surfaceY(Density finalDensity, Band band, int x, int z) {
        return surfaceY(finalDensity, band, x, z, false);
    }

    /**
     * The playable surface in a column, or null when there is none.
     *
     * <p><b>The answer is the first open block ABOVE the ground</b>, matching
     * vanilla's {@code OCEAN_FLOOR_WG} heightmap and
     * {@link com.customdimensions.command.ColumnScan#findPlayableFloorY} —
     * both of which the facts read. This used to answer the ground block
     * itself, one lower, and the whole difference lands on the shoreline: a
     * column whose ground tops out exactly at sea level is dry land to the
     * facts ({@code seaLevel + 1 > seaLevel}) and sea to a render comparing
     * the ground block. Measured on the overworld control, that single block
     * was the disagreement on 114 of 200 recorded columns.
     *
     * <p><b>A ceilinged dimension has its ceiling taken off, and is then read
     * like any other world.</b> Vanilla's nether router forces solid at the
     * roof with a clamped gradient, and a clamped gradient keeps its top value
     * for every y above the clamp — so the density says "solid" from the roof
     * to infinity, and a plain highest-solid walk answers the top of the
     * generator's band for every column in the world. Measured on the nether
     * control: {@code y=191} for all 1313 columns, against a real range of
     * 27–182. So the roof has to be found and discarded; after that the
     * ordinary rule applies, and the height that comes out is real terrain
     * that shades on a map.
     *
     * <p>Mirrors {@code ColumnScan.scan} step for step and shares its
     * {@link com.customdimensions.command.ColumnScan#CEILING_CLIP} rather than
     * copying the number, on the generator's own rung rather than block by
     * block because a map samples this a quarter of a million times.
     */
    public static Integer surfaceY(Density finalDensity, Band band, int x, int z,
                                   boolean hasCeiling) {
        Integer top = highestSolid(finalDensity, band, band.topY(), x, z);
        if (top == null) {
            return null;
        }
        if (!hasCeiling) {
            return top + 1;
        }
        Integer underside = firstOpenBelow(finalDensity, band, top, x, z);
        if (underside == null) {
            return null;   // entombed: solid from the roof to the world floor
        }
        Integer ground = highestSolidExact(finalDensity, band,
                underside - com.customdimensions.command.ColumnScan.CEILING_CLIP, x, z);
        return ground == null ? null : ground + 1;
    }

    /**
     * The highest solid block at or below {@code from}, missing nothing, or
     * null.
     *
     * <p>{@link #highestSolid} samples on the rung and therefore cannot see a
     * solid layer thinner than one. Under a ceiling that is not a rare shape:
     * a nether's 3D noise leaves isolated blocks in open air, and the block
     * heightmap takes them because they are the highest opaque thing in the
     * column. Measured on the nether control at (-400, 25): a single solid
     * block at y=171 with air above and below it, sampled past by a coarse
     * walk stepping 182, 174, 166, and the render answered y=23 against the
     * blocks' 172 with both ladders agreeing about every y in between.
     *
     * <p>Exactness costs the distance walked, so this is used only where the
     * shape demands it. An open dimension keeps the coarse walk: its terrain
     * is thicker than a rung nearly everywhere, and the overworld control
     * disagrees on 7 of 1313 columns with it.
     */
    private static Integer highestSolidExact(Density finalDensity, Band band, int from,
                                             int x, int z) {
        for (int y = Math.min(from, band.topY()); y >= band.bottomY(); y--) {
            if (isSolid(finalDensity, x, y, z)) {
                return y;
            }
        }
        return null;
    }

    /**
     * The highest solid block at or below {@code from}, or null.
     *
     * <p>Walks down on {@link Band#rung()} until it finds solid, then walks
     * back up one rung a block at a time. Worst case is one column of pure
     * air, which costs the full coarse pass and nothing more.
     *
     * <p>Blind to a solid layer thinner than a rung that sits between two
     * samples — see {@link #highestSolidExact}, which is what a ceilinged
     * column uses for exactly that reason.
     */
    private static Integer highestSolid(Density finalDensity, Band band, int from,
                                        int x, int z) {
        int rung = band.rung();
        int hit = Integer.MIN_VALUE;
        int lowestTested = from + rung;
        for (int y = from; y >= band.bottomY(); y -= rung) {
            lowestTested = y;
            if (isSolid(finalDensity, x, y, z)) {
                hit = y;
                break;
            }
        }
        if (hit == Integer.MIN_VALUE) {
            // The coarse walk stops on the last rung at or above the floor, so
            // the final partial segment below it was never sampled — and that
            // segment is not an edge case, it is EVERY band. Vanilla requires
            // the generation shape's height to be a whole number of cells, so
            // `bandOf` produces a span of `height - 1` and the walk always
            // leaves `rung - 1` blocks untested at the bottom. Ground confined
            // there read as void, and `playableFloor` — which calls this on
            // every iteration — read a real floor near the world's bottom as
            // no floor at all, disagreeing with `ColumnScan` on a field whose
            // features are all thicker than a rung. `firstOpenBelow` has had
            // this tail since it was written; this is the same fallback.
            for (int y = lowestTested - 1; y >= band.bottomY(); y--) {
                if (isSolid(finalDensity, x, y, z)) {
                    return y;   // already the topmost solid: everything above was open
                }
            }
            return null;
        }
        // The coarse rung landed inside the ground; the surface is the last
        // solid block below the first open one above it. Clipped to `from`,
        // never to the band top — a ceilinged walk starts partway down and
        // must not climb back into the roof it just came through.
        int ceiling = Math.min(from, hit + rung);
        for (int y = hit + 1; y <= ceiling; y++) {
            if (!isSolid(finalDensity, x, y, z)) {
                return y - 1;
            }
        }
        return ceiling;
    }

    /**
     * The highest open block below the contiguous solid slab starting at
     * {@code from}, or null when the column is solid to the world floor.
     *
     * <p><b>Block by block, and it has to be.</b> This walked on
     * {@link Band#rung()} and fine-scanned only once a coarse sample landed
     * open — so an air gap THINNER than a rung fell between two solid samples
     * and the walk carried on down as though the roof went with it. Measured
     * on the nether control at (-150, 125): the roof slab ends at y=181, the
     * gap under it is y=176–180, and the rung is 8. Samples landed on 183 and
     * 175, both solid, and the underside came back 150 blocks too low — the
     * blocks said 176, the density walk said 24, and the two ladders agreed
     * about every block in between. That is the whole of the 225-column Nether
     * disagreement, and it can only ever read LOW, which is what
     * {@code max(render − facts) == 0} was saying all along.
     *
     * <p>The cost is bounded by the thickness of what roofs the column rather
     * than by the band: this starts inside a solid slab and stops the moment it
     * leaves it, so it is a handful of samples in the ordinary case and cheaper
     * than the coarse walk was in the failing one.
     */
    private static Integer firstOpenBelow(Density finalDensity, Band band, int from,
                                          int x, int z) {
        for (int y = from - 1; y >= band.bottomY(); y--) {
            if (!isSolid(finalDensity, x, y, z)) {
                return y;
            }
        }
        return null;
    }


    public static boolean isSolid(Density finalDensity, int x, int y, int z) {
        return finalDensity.at(x, y, z) > SOLID;
    }

    // ------------------------------------------------------- cell interpolation

    /**
     * The density generation actually fills blocks from.
     *
     * <p>A generator does not evaluate its density function per block. It
     * evaluates it at the corners of its own cell grid and INTERPOLATES —
     * bilinearly across the cell in x and z, linearly up it in y — and the
     * block a player stands on comes from the interpolated value. Sampling
     * the raw function at an arbitrary point is a different number, and the
     * two diverge exactly in proportion to how fast the terrain is changing:
     * measured against a live world over 1239 columns of a mountainous seed,
     * the flattest quartile differed by a median of 2 blocks and the steepest
     * by 20, monotonically across the four.
     *
     * <p>{@link Band}'s own contract has always said "density is linear
     * between two cell corners". This is what makes that true of the numbers
     * a walk actually reads.
     *
     * <p><b>Cost is four raw samples per cell LEVEL, not eight per block.</b>
     * A column's four horizontal corners are fixed, and every block inside a
     * cell reads the same two levels — so a descending walk pays once per
     * rung and the refine pass inside a rung pays nothing. That is the whole
     * reason the two most recent levels are cached.
     *
     * <p><b>Stateful: one per worker.</b> The cache makes this unsafe to
     * share, which matches the existing rule that each render worker builds
     * its own rig and its own density.
     *
     * @param raw            the generator's own final density
     * @param horizontalCell {@code horizontalCellBlockCount()} — the cell
     *                       width in x AND z. Always divides 16, so cells are
     *                       aligned to world coordinates as well as to chunks
     * @param verticalCell   {@code verticalCellBlockCount()}
     * @param shapeMinimumY  {@code minimumY()} of the same (trimmed) shape
     *                       config the cell counts came from — the vertical
     *                       lattice is measured from there, not from zero
     */
    public static Density cellInterpolated(Density raw, int horizontalCell,
                                           int verticalCell, int shapeMinimumY) {
        final int hx = Math.max(1, horizontalCell);
        final int vy = Math.max(1, verticalCell);
        return new Density() {
            // The cache holds exactly the two lattice levels of ONE cell: the
            // one the last query fell in. A two-slot LRU was the first design
            // and it had an eviction-order bug — resolving the two levels one
            // at a time lets the first miss evict what the second needs, so a
            // walk that changed direction paid eight raw samples per cell
            // instead of four. Holding the pair together removes the ordering
            // question rather than answering it, and a step to an ADJACENT
            // cell in either direction reuses the level the two cells share.
            private int columnX = Integer.MIN_VALUE;
            private int columnZ = Integer.MIN_VALUE;
            private int cellFloor = Integer.MIN_VALUE;
            private boolean loaded;
            private double atFloor;
            private double atCeiling;

            @Override
            public double at(int x, int y, int z) {
                int floor = Math.floorDiv(y - shapeMinimumY, vy) * vy + shapeMinimumY;
                if (!this.loaded || x != this.columnX || z != this.columnZ
                        || floor != this.cellFloor) {
                    load(x, z, floor);
                }
                return this.atFloor
                        + (this.atCeiling - this.atFloor) * ((y - floor) / (double) vy);
            }

            private void load(int x, int z, int floor) {
                boolean sameColumn = this.loaded && x == this.columnX && z == this.columnZ;
                double newFloor;
                double newCeiling;
                if (sameColumn && floor == this.cellFloor - vy) {
                    newCeiling = this.atFloor;              // stepped down one cell
                    newFloor = bilinear(x, floor, z);
                } else if (sameColumn && floor == this.cellFloor + vy) {
                    newFloor = this.atCeiling;              // stepped up one cell
                    newCeiling = bilinear(x, floor + vy, z);
                } else {
                    newFloor = bilinear(x, floor, z);
                    newCeiling = bilinear(x, floor + vy, z);
                }
                this.columnX = x;
                this.columnZ = z;
                this.cellFloor = floor;
                this.atFloor = newFloor;
                this.atCeiling = newCeiling;
                this.loaded = true;
            }

            /** The bilinear value across the cell at one lattice level. */
            private double bilinear(int x, int y, int z) {
                int x0 = Math.floorDiv(x, hx) * hx;
                int z0 = Math.floorDiv(z, hx) * hx;
                double tx = (x - x0) / (double) hx;
                double tz = (z - z0) / (double) hx;
                double north = lerp(tx, raw.at(x0, y, z0), raw.at(x0 + hx, y, z0));
                double south = lerp(tx, raw.at(x0, y, z0 + hx), raw.at(x0 + hx, y, z0 + hx));
                return lerp(tz, north, south);
            }
        };
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
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
