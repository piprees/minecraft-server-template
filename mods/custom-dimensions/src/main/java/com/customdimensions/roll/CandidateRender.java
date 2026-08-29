package com.customdimensions.roll;

import com.customdimensions.command.InputHash;
import com.customdimensions.command.SpikeSampler;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionStructures;
import com.customdimensions.dimension.NoiseGroupPlan;
import com.customdimensions.dimension.NoisePoolBuilder;
import com.customdimensions.dimension.NoiseStructurePlacement;
import com.customdimensions.dimension.StructurePick;
import com.customdimensions.facts.SeedFacts;
import com.customdimensions.facts.SeedFactsCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.MapCodec;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.MinecraftDedicatedServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeEffects;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.GenerationShapeConfig;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Draws one candidate's biome layout, terrain shape, structure sites, spawn
 * and playable border to a PNG — the picture a person actually judges a seed
 * from, which nothing in the mod produces otherwise.
 *
 * <p>Every pixel is a direct {@link BufferedImage#setRGB} write — no
 * {@code Graphics2D}, no font, no AWT toolkit, because a headless dedicated
 * server must never be able to reach a display toolkit just because a render
 * was requested.
 *
 * <p>Nothing is drawn OVER the terrain here. Structure markers, the spawn
 * dot and the border ring all lived in the pixels once and made a thumbnail
 * unreadable — the viewer overlays them as SVG, where they can be toggled
 * and where the border toggle in the nav can actually reach them.
 *
 * <p>Colour is the map colour of the block a biome's surface rule places
 * ({@link SurfacePalette}) — netherrack dark red, grass green, end stone
 * bone. Water uses the biome's own water colour, darkened by depth. Terrain
 * is shaded the way vanilla's own maps are: each cell brighter, level with,
 * or darker than the cell to its NORTH, with a directional hillshade off the
 * local gradient mixed in. Both are local comparisons — a shade spread over
 * the dimension's whole height range put maximum contrast between two cells
 * a few blocks apart and rendered a nether world as static.
 *
 */
public final class CandidateRender {

    private CandidateRender() {
    }

    /**
     * How densely a map is sampled and how big its file is — two separate
     * numbers, the way the renderer this replaced had them. A wall-clock
     * budget used to decide the sample count, which made the picture's
     * resolution depend on what the machine happened to be doing.
     */
    /**
     * The two views, which differ in how much world they cover — not in
     * fidelity. Both are one block per pixel; the thumbnail is the 512 blocks
     * around spawn, the detail view is 2048 blocks of it.
     */
    public enum Resolution {
        /** The 512 blocks around spawn, one block a pixel. */
        LOWRES,
        /** The whole world, edge to edge of its player border. */
        HIGHRES
    }

    /**
     * Blocks the thumbnail covers. A close look at spawn, at one block a
     * pixel — the question it answers is "what is it like where I would land",
     * not "what shape is this world".
     */
    private static final int THUMBNAIL_BLOCKS = 512;

    /**
     * Widest picture either view produces. One block a pixel stops being
     * possible well before the biggest border here: a 16,384-block world
     * would be a 16,384px image, and a {@code BufferedImage} that size is a
     * gigabyte of heap on a server with six. Past this the scale drops to
     * several blocks a pixel instead, which is the right trade — the fine
     * detail belongs to the structure overlays, not the pixels.
     */
    private static final int MAX_PIXELS = 2048;

    /**
     * Blocks between samples. Four, because biome and climate are both
     * defined at quarter resolution ({@code qx = x >> 2}) — sampling finer
     * asks the same question four times, and upscaling a quarter-resolution
     * grid back to one block per pixel loses nothing.
     */
    private static final int BLOCKS_PER_SAMPLE = 4;

    /**
     * Sample points per height measurement, on each axis.
     *
     * <p>Biome and climate are quarter-resolution facts and are read at every
     * point of the grid; HEIGHT is not, and it is the expensive one — a
     * column walk asks the generator's density function tens of times, where
     * a biome asks the climate chains once. So the height field is measured
     * on its own coarser lattice and interpolated back up, which costs this
     * squared fewer column walks.
     *
     * <p>One means "measure every point", and is exactly the old behaviour —
     * the interpolation degenerates to reading the corner it sits on. Four is
     * what this ships: at a thumbnail's four blocks per sample that is a
     * height every sixteen blocks, under a biome layout still drawn at four,
     * for a sixteenth of the column walks.
     */
    private static final int HEIGHT_SAMPLE_FACTOR = 4;

    /**
     * Columns a two-dimensional cache marker's claim is tested over before it
     * is believed. Off-lattice, because Perlin is exactly zero on its lattice
     * and a lattice-aligned pair would agree with the noise field unread.
     */
    private static final int Y_INDEPENDENCE_PROBES = 6;

    /** Whether a y-reading two-dimensional marker has been reported this run. */
    private static final java.util.concurrent.atomic.AtomicBoolean REFUSAL_REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean();




    /** Stands in for "this biome has no colour" in a map that cannot hold nulls. */
    private static final BiomeColors MISSING = new BiomeColors(0, 0);




    // Outside the playable border: no biome ever produced this pixel.
    private static final int VOID_COLOR = 0x101018;
    /**
     * Inside the border with no floor under it. A floating-island dimension
     * is mostly this, so it is terrain rather than a gap in the measurement
     * — {@link #UNKNOWN_COLOR} painted 87% of {@code the_burning_archipelago}
     * magenta before the two were told apart.
     */
    private static final int OPEN_AIR_COLOR = 0x171B26;
    /**
     * What a column with no floor reads as, by family — you are looking down
     * into something, and which something it is says a lot about the world.
     * A single grey for all of them made an end void and a nether lava sea
     * the same picture.
     */
    private static final int VOID_END = 0x08050F;        // near-black, the end's own nothing
    private static final int VOID_NETHER = 0x3C1405;     // the lava sea under the nether
    private static final int VOID_SKY = 0xA0BEDC;        // pale sky, for skyland worlds
    private static final int VOID_CAVE = 0x14120F;       // unlit rock
    /** Blocks between contour lines — the strongest cue that a picture is terrain. */
    private static final int CONTOUR_INTERVAL = 20;

    /**
     * Columns the depth calibration probes before the map is drawn. Ninety-six
     * costs under two hundred density samples once per render — nothing beside
     * the picture itself — and is enough that a coastline or two disagreeing
     * cannot flip the verdict.
     */
    private static final int CALIBRATION_COLUMNS = 96;

    /** Whether a roll is under way, so the two share the machine rather than fight for it. */
    private static final java.util.concurrent.atomic.AtomicBoolean ROLLING =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Told by {@code web/RollPipeline} when a run starts and when it ends. */
    public static void rolling(boolean rolling) {
        ROLLING.set(rolling);
    }

    /**
     * Cores a single render fans out over.
     *
     * <p>A quarter of the machine while a roll is running — the search is what
     * makes anything worth drawing, so it keeps the majority — and nearly all
     * of it when nothing is rolling, because a detail map is then the only
     * work there is and leaving three quarters of the cores idle just makes
     * somebody wait. Read per render rather than fixed at class load, so the
     * split follows what the machine is actually doing.
     *
     * <p>A fraction rather than a fixed count, because a fixed one silently
     * inverts the split on a smaller machine: eight was more than half of a
     * sixteen-core container once the server thread and its two reserved
     * cores came out, so drawing pictures outran the search.
     */
    public static int renderCores() {
        int cpus = Runtime.getRuntime().availableProcessors();
        return ROLLING.get() ? Math.max(1, cpus / 4) : Math.max(1, cpus - 2);
    }

    // Vanilla's map shading: a cell is brighter, level with, or darker than
    // the one to its north, and nothing else. It is what makes a Minecraft
    // map read as terrain rather than as a biome chart, and it is relative
    // to a NEIGHBOUR — the old shade spread the dimension's whole height
    // range over every cell independently, so a nether roof and its floor
    // put maximum contrast between two cells a few blocks apart and the
    // result was noise.
    private static final double MC_SHADE_DARK = 0.71 / 0.86;
    private static final double MC_SHADE_LEVEL = 1.0;
    private static final double MC_SHADE_BRIGHT = 1.0 / 0.86;
    /** How much of the final shade comes from a directional light rather than the vanilla step. */
    private static final double HILLSHADE_MIX = 0.4;
    private static final double HILLSHADE_GAIN = 0.12;

    // A wide range: this family's biomes carry no grass tint and cluster in
    // fog/sky hue, so relief has to come from brightness doing real work
    // rather than a subtle nudge on an already-narrow palette.
    private static final double MIN_SHADE = 0.35;
    private static final double MAX_SHADE = 1.6;

    /** What a render actually did, for the command's one-line answer and the log. */
    public record RenderResult(Path path, int side, int step, long perColumnNanos,
                               long renderNanos, int sampled, int structureMarkers) {
    }

    /**
     * Where a render's time went, split so the answer to "is it paying for
     * its columns or for its setup" is measured rather than argued about.
     *
     * <p>{@code rigTotal} is core time summed across workers and {@code
     * loopWall} is wall time, so the two are not comparable directly —
     * {@code rigMax} is the one that lands on the critical path, since every
     * worker builds its rig before it samples anything.
     */
    private record Timings(long modelNanos, long rigTotalNanos, long rigMaxNanos,
                           long loopWallNanos, long climateNanos, long surfaceNanos,
                           long paintNanos, long densityProbes, int heightColumns,
                           int heightStride) {
    }

    /**
     * Everything a render decides about where the ground is and where the
     * water is, before it paints a pixel.
     *
     * <p>It exists so a diagnostic can ASK the renderer what it would answer
     * for a column rather than reimplement it. Two height sources for one
     * question is how {@code survivesVanillaPrefilter} and
     * {@code PortalBreakLink.centreColumn} both went wrong; the render's
     * answer is defined once, here, and {@link #draw} and
     * {@code command/RenderCheck} read the same definition.
     *
     * <p>{@code useDepth} survives only for a generator with no settings of
     * its own — a flat or void fallback, which has no density to walk. Every
     * other dimension walks the final density. {@code 128 * depth} used to be
     * the path an overworld-shaped graph took, on the grounds that the
     * climate point was already being read for the biome; measured against
     * the world on the overworld control it was a systematic block low at the
     * shoreline with a tail out to ±35, so what it saved was never the
     * question. {@link TerrainShape#calibrate} is still run and its verdict
     * still recorded, because it is the one number that says whether the
     * cheap answer WOULD have been close — but nothing reads it to decide any
     * more.
     */
    public record HeightModel(TerrainShape.Band band, boolean useDepth, Integer seaLevel,
                              int floorY, int topY, ChunkGeneratorSettings shapeSettings,
                              TerrainShape.Calibration calibration,
                              boolean hasCeiling, int cellHorizontal, int shapeMinimumY,
                              boolean floodsVoid) {

        /** Which of the two height sources this render uses, by name. */
        public String heightSource() {
            return this.useDepth ? "depth" : "finalDensity";
        }
    }

    /**
     * The height and water model a render of this (dimension, seed) uses.
     *
     * <p>{@code coverage} is the width in blocks the calibration probes over
     * — the same number {@link #draw} derives from the resolution, and the
     * only thing the calibration's column spread depends on.
     */
    public static HeightModel heightModel(MinecraftServer server, SpikeSampler.Base base,
                                          long seed, int coverage) {
        int floorY = base.heightLimit().getBottomY();
        int topY = base.heightLimit().getTopY() - 1;

        Integer seaLevel = null;
        ChunkGeneratorSettings raw = null;
        if (base.generator() instanceof NoiseChunkGenerator noiseGen) {
            raw = noiseGen.getSettings().value();
            if (raw.defaultFluid().isOf(net.minecraft.block.Blocks.WATER)) {
                seaLevel = raw.seaLevel();
            }
        }
        // Broader than the WATER-specific seaLevel gate above: any non-empty
        // default fluid (lava included) means a groundless column is worth
        // probing rather than assumed dry — see waterAt.
        boolean floodsVoid = SpikeSampler.floodsVoid(base.generator());

        // The cell geometry the rewrite needs is read off the generator's own
        // shape config, which climateAndShape copies through untouched — so it
        // is the same answer either side of the rewrite.
        GenerationShapeConfig shape = shapeOf(raw, floorY, topY);
        TerrainShape.Band band = bandOf(shape, floorY, topY);
        int cellHorizontal = shape == null ? 4 : shape.horizontalCellBlockCount();
        int shapeMinimumY = shape == null ? floorY : shape.minimumY();

        boolean trivialDensity = raw != null && isConstant(raw.noiseRouter().finalDensity());
        MarkerCounts counts = new MarkerCounts(trivialDensity);
        ChunkGeneratorSettings shapeSettings = climateAndShape(raw,
                new WrapperRewrite(counts, cellHorizontal, band.cellHeight(), shapeMinimumY));
        TerrainShape.Calibration calibration = new TerrainShape.Calibration(0, 0, true);
        if (shapeSettings != null) {
            // Seeding is what resolves the two-dimensional markers, so the
            // counts are only complete once a NoiseConfig has been built.
            SpikeSampler.Rig rig = rigOver(base, shapeConfigFor(server, shapeSettings, seed));
            counts.report();
            calibration = calibrate(rig, band, coverage, floorY, topY);
        }
        // Only a generator with no density to walk falls back to depth.
        boolean useDepth = shapeSettings == null;
        return new HeightModel(band, useDepth, seaLevel, floorY, topY,
                shapeSettings, calibration, base.hasCeiling(),
                cellHorizontal, shapeMinimumY, floodsVoid);
    }

    /**
     * One caller's rig for a model, with a {@link NoiseConfig} of its own.
     *
     * <p>Not shared: building the config is what seeds the router, and seeding
     * rebuilds the stateful nodes {@link #climateAndShape} planted in the final
     * density. One config per rig is therefore one interpolation cache and one
     * set of column caches per rig, which is the rule those nodes have always
     * been written to.
     */
    public static SpikeSampler.Rig rigFor(MinecraftServer server, SpikeSampler.Base base,
                                          HeightModel model, long seed) {
        return model.shapeSettings() == null
                ? SpikeSampler.forSeedClimate(server, base, seed)
                : rigOver(base, shapeConfigFor(server, model.shapeSettings(), seed));
    }

    /**
     * The density a rig walks, or null when this model reads depth instead.
     *
     * <p>A plain read: the interpolation the map needs is already inside the
     * rig's router, put there before the router was seeded.
     */
    public static TerrainShape.Density densityFor(HeightModel model, SpikeSampler.Rig rig) {
        return model.useDepth() ? null : finalDensityOf(rig);
    }

    /**
     * What one rewrite of a router did, counted across the rewrite itself and
     * the seeding pass that follows it.
     *
     * <p>Two halves, because the two decisions are made at different moments:
     * the interpolated marker is substituted while the graph is still the
     * datapack's own template, and a two-dimensional marker's claim can only
     * be tested once seeding has bound its noise leaves.
     */
    private static final class MarkerCounts {

        final java.util.concurrent.atomic.AtomicInteger interpolated =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger columnCached =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger columnRefused =
                new java.util.concurrent.atomic.AtomicInteger();
        /**
         * A node registered under a marker id ({@code interpolated}/{@code
         * flat_cache}/{@code cache_2d}) but not {@code instanceof Wrapper} — a
         * marker genuinely exists here, this rewrite just cannot safely reach
         * into an unrecognised class to extract its wrapped child.
         */
        final java.util.concurrent.atomic.AtomicInteger unrecognisedMarker =
                new java.util.concurrent.atomic.AtomicInteger();
        /** Whether the whole final density is a bare constant — nothing can ever interpolate across it. */
        final boolean trivialDensity;

        MarkerCounts(boolean trivialDensity) {
            this.trivialDensity = trivialDensity;
        }

        /** Says what the rewrite found, once the first seeding has resolved it. */
        void report() {
            // The marker is matched by NAME, because its enum lives inside a
            // package-private class. A name that stopped matching would leave
            // the map reading an un-interpolated density with nothing to say
            // so — the exact silent failure this codebase treats as its worst
            // kind. Count it and say so — unless there is genuinely nothing to
            // find (a constant final density, e.g. the void generator) or a
            // marker was found by registry id but not by class, in which case
            // the WARN below would itself be the lie.
            if (this.interpolated.get() == 0) {
                if (this.trivialDensity) {
                    com.customdimensions.MultiverseServer.LOGGER.debug(
                            "render: this router's final density is a bare constant — nothing to "
                            + "interpolate, exact sampling is already exact");
                } else if (this.unrecognisedMarker.get() > 0) {
                    com.customdimensions.MultiverseServer.LOGGER.warn(
                            "render: {} interpolated marker(s) found by registry id, but this "
                            + "renderer's class-based rewrite does not recognise their type — "
                            + "falling back to exact sampling for them", this.unrecognisedMarker.get());
                } else {
                    com.customdimensions.MultiverseServer.LOGGER.warn(
                            "render: no interpolated marker found in this router's final density — "
                            + "the map is reading an EXACT density where generation interpolates one");
                }
            }
            // Said out loud once, because it is the difference between a cache
            // and a changed picture: caching a marker that reads y freezes
            // whichever height happened to be probed first, and which one that
            // is depends on how the grid was split across workers. Measured on
            // this pack, six of ~37,800 markers do read it.
            if (this.columnRefused.get() > 0 && REFUSAL_REPORTED.compareAndSet(false, true)) {
                com.customdimensions.MultiverseServer.LOGGER.info(
                        "render: {} of {} two-dimensional cache marker(s) in this router read y "
                        + "and are left uncached", this.columnRefused.get(),
                        this.columnRefused.get() + this.columnCached.get());
            }
            com.customdimensions.MultiverseServer.LOGGER.debug(
                    "render: rewrote {} interpolated marker(s); cached {} two-dimensional "
                    + "marker(s) and left {} uncached for reading y",
                    this.interpolated.get(), this.columnCached.get(),
                    this.columnRefused.get());
        }
    }

    /** {@code Registries.DENSITY_FUNCTION_TYPE} lookup for {@code minecraft:<id>}. */
    private static MapCodec<? extends DensityFunction> markerCodec(String id) {
        return Registries.DENSITY_FUNCTION_TYPE.get(Identifier.ofVanilla(id));
    }

    /**
     * Whether a node is registered under an interpolation/cache marker id even
     * though it is not {@code instanceof DensityFunctionTypes.Wrapper} — the
     * class backing that id in THIS router is not vanilla's own {@code
     * Wrapping} record. Matched by codec identity ({@code isEndIslands}'s
     * pattern in {@code DimensionManager}), because the record classes behind
     * these ids are package-private and cannot be named from here.
     */
    private static boolean isMarkerByRegistryId(DensityFunction function) {
        try {
            MapCodec<? extends DensityFunction> codec = function.getCodecHolder().codec();
            return codec == markerCodec("interpolated") || codec == markerCodec("flat_cache")
                    || codec == markerCodec("cache_2d");
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    /** Whether a node is exactly a constant — nothing can ever interpolate across it. */
    private static boolean isConstant(DensityFunction function) {
        try {
            return function.getCodecHolder().codec() == markerCodec("constant");
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * Makes the router's own wrapper markers mean something outside a
     * {@code ChunkNoiseSampler}.
     *
     * <p>Every marker a router carries — {@code interpolated},
     * {@code flat_cache}, {@code cache_2d} — is a no-op when the tree is
     * sampled directly: vanilla's {@code Wrapping} delegates straight to what
     * it wraps, and the real behaviour lives in the sampler this render
     * deliberately does not build. So the map re-derives the whole graph at
     * every probe, including the two-dimensional half a generator evaluates
     * once per column.
     *
     * <p><b>Interpolation.</b> A router marks ONE sub-tree
     * {@code minecraft:interpolated}, and vanilla substitutes a real
     * interpolating sampler for that marker and nothing else — everything
     * downstream of it is evaluated per block, exactly. Terralith's,
     * Tectonic's and this mod's own overworld routers all read
     * {@code min(squeeze(0.64 * interpolated(...)), noodle)}: the shape core
     * is blended across the cell, and the squeeze clamp and the cave
     * subtraction are not. Blending the WHOLE final density instead is a
     * different approximation, and the two agree only where the downstream
     * part happens to be linear across a cell. It is not linear on a slope,
     * and a hard clamp is about as far from linear as a density function
     * gets: measured, the error was unsigned, rose with local relief, and was
     * worst in the nether, whose roof clamp lives downstream of the marker.
     *
     * <p><b>Column caching.</b> {@code flat_cache} and {@code cache_2d} both
     * declare their sub-tree to be a function of {@code (x, z)} alone —
     * vanilla keys the first on the quart and the second on the exact block,
     * and neither passes {@code y} down in a way that could matter.
     * {@link ColumnCache} keys on the exact block column, which is the finer
     * of the two, so a cached answer is the same number the uncached
     * delegation produced and the picture cannot move.
     *
     * <p>Generic on purpose. The markers are authored in the worldgen JSON,
     * so this reads them out of whatever graph the pack actually loaded
     * rather than knowing anything about a generator family — the same rule
     * {@link TerrainShape#calibrate} follows, and for the same reason.
     */
    private static final class WrapperRewrite
            implements DensityFunction.DensityFunctionVisitor {

        private final MarkerCounts counts;
        private final int cellHorizontal;
        private final int cellVertical;
        private final int shapeMinimumY;

        WrapperRewrite(MarkerCounts counts, int cellHorizontal, int cellVertical,
                       int shapeMinimumY) {
            this.counts = counts;
            this.cellHorizontal = cellHorizontal;
            this.cellVertical = cellVertical;
            this.shapeMinimumY = shapeMinimumY;
        }

        @Override
        public DensityFunction apply(DensityFunction function) {
            if (!(function instanceof DensityFunctionTypes.Wrapper wrapper)) {
                if (isMarkerByRegistryId(function)) {
                    this.counts.unrecognisedMarker.incrementAndGet();
                }
                return function;
            }
            switch (markerName(wrapper)) {
                case "interpolated" -> {
                    this.counts.interpolated.incrementAndGet();
                    return new CellInterpolated(wrapper.wrapped(),
                            this.cellHorizontal, this.cellVertical, this.shapeMinimumY);
                }
                case "flatcache", "cache2d" -> {
                    // The marker keeps its wrapper: whether the claim holds is
                    // a question about a SEEDED sub-tree, and this graph has
                    // not been seeded yet, so ColumnCache asks it when the
                    // seeding pass rebuilds the node.
                    return new ColumnCache(function, this.counts);
                }
                default -> {
                    return function;
                }
            }
        }

        /**
         * The marker's name, spelling-agnostic.
         *
         * <p>The enum lives inside a package-private class and cannot be named
         * from here, so it is compared as text — and the two spellings differ:
         * {@code toString()} gives the constant ({@code CACHE2D}) while
         * {@code asString()} gives the registry id ({@code cache_2d}).
         * Lower-casing and dropping underscores collapses both onto one key.
         */
        private static String markerName(DensityFunctionTypes.Wrapper wrapper) {
            return String.valueOf(wrapper.type())
                    .toLowerCase(java.util.Locale.ROOT).replace("_", "");
        }

        @Override
        public DensityFunction.Noise apply(DensityFunction.Noise noise) {
            return noise;
        }
    }

    /**
     * A sub-tree the router declares to be two-dimensional, evaluated once
     * per block column instead of once per probe.
     *
     * <p>A surface walk asks its column's four cell corners at every rung it
     * descends, and the two-dimensional half of a modded router — the
     * continent, erosion and ridge chains and every spline stacked on them —
     * answers identically each time. Measured on this pack before it was
     * cached: one probe of {@code the_crystal_vale}'s final density cost 283
     * microseconds, and a thumbnail spent all but a fraction of a percent of
     * its time inside that walk.
     *
     * <p>Direct-mapped rather than a single slot, because the access pattern
     * is four columns interleaved, not one: vanilla's own {@code cache_2d}
     * holds one entry and would miss on every corner. Sixteen slots hold a
     * walk's four corners and its neighbours' with room to spare; a conflict
     * costs a recomputation and never a wrong answer. Small on purpose —
     * a modded overworld router carries tens of thousands of these markers,
     * so every slot is paid for that many times over, per worker.
     *
     * <p><b>Stateful: one per rig</b>, like the interpolation it sits beside,
     * and for the same reason. {@link #apply} is what delivers that — every
     * seeding pass rebuilds the node, and a rig is one seeding pass.
     */
    private static final class ColumnCache implements DensityFunction {

        private static final int SLOTS = 16;
        private static final int HASH_SHIFT = 64 - Integer.numberOfTrailingZeros(SLOTS);

        private final DensityFunction wrapped;
        private final MarkerCounts counts;
        private final long[] columns = new long[SLOTS];
        private final boolean[] filled = new boolean[SLOTS];
        private final double[] values = new double[SLOTS];

        ColumnCache(DensityFunction wrapped, MarkerCounts counts) {
            this.wrapped = wrapped;
            this.counts = counts;
        }

        @Override
        public double sample(NoisePos pos) {
            long column = ((long) pos.blockX() << 32) ^ (pos.blockZ() & 0xFFFFFFFFL);
            int slot = slotOf(column);
            if (this.filled[slot] && this.columns[slot] == column) {
                return this.values[slot];
            }
            double value = this.wrapped.sample(pos);
            this.columns[slot] = column;
            this.values[slot] = value;
            this.filled[slot] = true;
            return value;
        }

        /** Fibonacci hashing: the top bits, which mix both coordinates. */
        private static int slotOf(long column) {
            return (int) ((column * 0x9E3779B97F4A7C15L) >>> HASH_SHIFT);
        }

        @Override
        public void fill(double[] densities, EachApplier applier) {
            applier.fill(densities, this);
        }

        /**
         * Rebuilds around the visited sub-tree, and decides here whether to
         * keep the cache at all.
         *
         * <p>The visitor that matters is the one {@code NoiseConfig}'s
         * constructor runs, which binds every {@code Noise} leaf beneath this
         * node. Sub-trees are visited before their parents, so by the time
         * this runs the child is fully seeded — which is the only state in
         * which the y-independence question can be answered. Asked of an
         * unseeded graph every leaf reads zero and every sub-tree looks
         * two-dimensional.
         */
        @Override
        public DensityFunction apply(DensityFunctionVisitor visitor) {
            DensityFunction seeded = this.wrapped.apply(visitor);
            if (!ignoresY(seeded)) {
                this.counts.columnRefused.incrementAndGet();
                return seeded;
            }
            this.counts.columnCached.incrementAndGet();
            return visitor.apply(new ColumnCache(seeded, this.counts));
        }

        /**
         * Whether a marked sub-tree really does ignore {@code y}.
         *
         * <p>{@code flat_cache} and {@code cache_2d} are a router AUTHOR's
         * claim, and vanilla acts on it without checking — its flat cache
         * samples the sub-tree at {@code y = 0} and reuses that answer for a
         * whole quart column. Sampled raw, as this render does, the real
         * {@code y} goes down instead, so a sub-tree that reads {@code y}
         * answers differently for every rung of a walk. Caching such a
         * sub-tree on {@code (x, z)} would freeze whichever rung happened to
         * be probed first, which depends on how the grid was split across
         * workers.
         *
         * <p>So the claim is measured, not taken: the sub-tree is asked at two
         * heights over several off-lattice columns, and only a sub-tree that
         * answers identically is cached. One that does not is left exactly as
         * it was, and the picture cannot move either way.
         */
        private static boolean ignoresY(DensityFunction wrapped) {
            for (int i = 0; i < Y_INDEPENDENCE_PROBES; i++) {
                int[] at = SpikeSampler.probe(i, 4096);
                double low = wrapped.sample(
                        new DensityFunction.UnblendedNoisePos(at[0], -48, at[1]));
                double high = wrapped.sample(
                        new DensityFunction.UnblendedNoisePos(at[0], 176, at[1]));
                // compare, not !=, so two NaNs count as agreeing rather than
                // as evidence of a y-dependence that is not there.
                if (Double.compare(low, high) != 0) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public double minValue() {
            return this.wrapped.minValue();
        }

        @Override
        public double maxValue() {
            return this.wrapped.maxValue();
        }

        @Override
        public net.minecraft.util.dynamic.CodecHolder<? extends DensityFunction> getCodecHolder() {
            // Never serialised: this lives only inside a render's private
            // rewritten tree. Loud rather than silently wrong if that changes.
            throw new UnsupportedOperationException(
                    "ColumnCache is a render-local rewrite and has no codec");
        }
    }

    /**
     * The marker's sub-tree, blended across the generator's cell — the
     * arithmetic lives in {@link TerrainShape#cellInterpolated} so it stays
     * unit-testable without a registry Bootstrap.
     *
     * <p>Stateful, like every wrapper it replaces: one per rig, and a rig is
     * one per worker.
     */
    private static final class CellInterpolated implements DensityFunction {

        private final DensityFunction wrapped;
        private final int cellHorizontal;
        private final int cellVertical;
        private final int shapeMinimumY;
        private final TerrainShape.Density blended;

        CellInterpolated(DensityFunction wrapped, int cellHorizontal, int cellVertical,
                         int shapeMinimumY) {
            this.wrapped = wrapped;
            this.cellHorizontal = cellHorizontal;
            this.cellVertical = cellVertical;
            this.shapeMinimumY = shapeMinimumY;
            this.blended = TerrainShape.cellInterpolated(
                    (x, y, z) -> wrapped.sample(new DensityFunction.UnblendedNoisePos(x, y, z)),
                    cellHorizontal, cellVertical, shapeMinimumY);
        }

        @Override
        public double sample(NoisePos pos) {
            return this.blended.at(pos.blockX(), pos.blockY(), pos.blockZ());
        }

        @Override
        public void fill(double[] densities, EachApplier applier) {
            applier.fill(densities, this);
        }

        /**
         * Visits the sub-tree and rebuilds, the contract vanilla's own
         * {@code Wrapping} follows.
         *
         * <p>Load-bearing in both directions. This node is planted before the
         * router is seeded, so a visitor that stopped here would leave every
         * {@code Noise} leaf beneath it unbound — and the rebuild is also what
         * gives each rig its own interpolation cache.
         */
        @Override
        public DensityFunction apply(DensityFunctionVisitor visitor) {
            return visitor.apply(new CellInterpolated(this.wrapped.apply(visitor),
                    this.cellHorizontal, this.cellVertical, this.shapeMinimumY));
        }

        @Override
        public double minValue() {
            return this.wrapped.minValue();
        }

        @Override
        public double maxValue() {
            return this.wrapped.maxValue();
        }

        @Override
        public net.minecraft.util.dynamic.CodecHolder<? extends DensityFunction> getCodecHolder() {
            // Never serialised: this lives only inside a render's private
            // rewritten tree. Loud rather than silently wrong if that changes.
            throw new UnsupportedOperationException(
                    "CellInterpolated is a render-local rewrite and has no codec");
        }
    }

    /**
     * The surface the render would paint at one column, or null for open air.
     *
     * <p>{@code sample} is read only by the depth fallback, so a caller that
     * walks the density — which is every caller with a {@code shape} — may
     * pass null rather than measuring a climate point it has no other use
     * for. Defined once here so {@link #draw}'s height pass and
     * {@code render-check} cannot drift apart.
     */
    public static Integer surfaceAt(HeightModel model, TerrainShape.Density shape,
                                    SpikeSampler.Sample sample, int x, int z) {
        if (shape != null) {
            return TerrainShape.surfaceY(shape, model.band(), x, z, model.hasCeiling());
        }
        return sample == null ? null
                : heightFromDepth(sample.climate(), model.floorY(), model.topY());
    }

    /**
     * The height at a fine-grid point, read off the coarser height lattice.
     *
     * <p>Bilinear where the cell's four measured corners all found ground,
     * and the NEAREST corner where they did not. A blend across a shore or an
     * island edge would invent a floor in the gap and move the coastline half
     * a cell inward; taking the nearest measured column instead keeps the
     * edge where it was measured, at the lattice's own resolution.
     *
     * <p>With a stride of one this returns the corner it sits on exactly, so
     * the whole mechanism is a no-op rather than a rounding error.
     */
    static Integer heightAt(int[] coarseHeight, boolean[] coarseKnown, int coarseSide,
                            int gx, int gz, int stride) {
        int cx = gx / stride;
        int cz = gz / stride;
        double tx = (gx - cx * stride) / (double) stride;
        double tz = (gz - cz * stride) / (double) stride;
        int i00 = cz * coarseSide + cx;
        int i10 = i00 + 1;
        int i01 = i00 + coarseSide;
        int i11 = i01 + 1;
        if (coarseKnown[i00] && coarseKnown[i10] && coarseKnown[i01] && coarseKnown[i11]) {
            double north = coarseHeight[i00] + (coarseHeight[i10] - coarseHeight[i00]) * tx;
            double south = coarseHeight[i01] + (coarseHeight[i11] - coarseHeight[i01]) * tx;
            return (int) Math.round(north + (south - north) * tz);
        }
        int nearest = (tz >= 0.5 ? cz + 1 : cz) * coarseSide + (tx >= 0.5 ? cx + 1 : cx);
        return coarseKnown[nearest] ? coarseHeight[nearest] : null;
    }

    /**
     * Whether the render would paint this column as water — the same rule
     * {@link #paint} applies. {@link #paint} still treats a groundless
     * column as void rather than sea (there is no floor to shade), so this
     * only feeds the reported water fraction — {@code render-check}'s
     * {@code renderWaterHere}, and nothing in the PNG itself.
     *
     * <p>A groundless column is not unconditionally dry either: the old rule
     * answered false for every one, which made the render's own water
     * statistic disagree with a live world that actually holds fluid there.
     * {@code aquifers_enabled} means flooding is never unconditional even
     * where {@link HeightModel#floodsVoid()} is true, so the column is
     * probed rather than assumed — gated on {@code floodsVoid} so a dry-void
     * generator (the End) never pays for one.
     */
    public static boolean waterAt(HeightModel model, SpikeSampler.Rig rig, String biome,
                                  Integer surface, int x, int z) {
        if (surface == null) {
            return model.floodsVoid() && SpikeSampler.groundlessHoldsFluid(rig, x, z);
        }
        return SurfacePalette.isWater(biome)
                || (model.seaLevel() != null && surface <= model.seaLevel());
    }

    /** Thrown when {@code abandonIf} asks a render to give up its cores. */
    public static final class Abandoned extends RuntimeException {
        public Abandoned() {
            super("render abandoned for higher-priority work", null, false, false);
        }
    }

    /**
     * Renders one (dimension, seed) to {@code outputPath}.
     */
    public static RenderResult render(MinecraftServer server, Identifier dimensionId,
                                      DimensionConfig def, long seed, Resolution resolution,
                                      Path outputPath) throws IOException {
        return draw(server, dimensionId, def, seed, resolution, outputPath, () -> false);
    }

    /**
     * The same, abandoning partway when {@code abandonIf} turns true.
     *
     * <p>A detail map of a big world is minutes of work and the queue has one
     * consumer, so a thumbnail queued behind one waits for all of it — priority
     * cannot preempt the job already running. Checked once per sampled row:
     * often enough to give the cores up promptly, far too rare to measure.
     * Nothing is written on the way out, so an abandoned render leaves no
     * partial PNG and simply runs again later.
     */
    public static RenderResult render(MinecraftServer server, Identifier dimensionId,
                                      DimensionConfig def, long seed, Resolution resolution,
                                      Path outputPath, java.util.function.BooleanSupplier abandonIf)
            throws IOException {
        return draw(server, dimensionId, def, seed, resolution, outputPath, abandonIf);
    }

    /**
     * Samples a square of the world and writes it at a fixed output size.
     *
     * <p>Square, because the playable area is: {@code WorldBorderManager}
     * sets a vanilla {@code WorldBorder}, which spans the radius on each axis
     * — a disc threw away the four corners, a fifth of every world.
     *
     * <p>Sample density and output size are separate. The sampler answers a
     * grid of {@code samples} on a side; every pixel of the {@code pixels}
     * -wide image reads the sample under it, so the file is the size a person
     * wants to look at whatever the sampling cost allowed.
     */
    private static RenderResult draw(MinecraftServer server, Identifier dimensionId,
                                     DimensionConfig def, long seed, Resolution resolution,
                                     Path outputPath, java.util.function.BooleanSupplier abandonIf)
            throws IOException {
        long renderStart = System.nanoTime();
        int radius = Math.max(1, def.getPlayerBorderRadius());


        SpikeSampler.Base base = SpikeSampler.base(server, dimensionId);
        if (!base.ok()) {
            throw new IOException("cannot render " + dimensionId + ": " + base.error());
        }
        // Every dimension reads the climate point. The real terrain router
        // answers one column at a time and rebuilds a ChunkNoiseSampler to do
        // it, which made one map minutes of work; at map scale it buys
        // nothing the eye reads, and detail comes from sampling more columns
        // rather than from a more exact height per column. Depth also has no
        // roof problem — the heightmap reading a nether ceiling is what made
        // ceilinged worlds need a column scan in the first place.
        // The thumbnail is a fixed window on spawn; the detail view is the
        // whole world, however big that is. Neither samples finer than the
        // image it produces — asking the same question several times per
        // pixel and throwing the answers away is where the cost used to go.
        int coverage = resolution == Resolution.HIGHRES
                ? Math.max(THUMBNAIL_BLOCKS, radius * 2) : THUMBNAIL_BLOCKS;
        int pixels = Math.min(coverage, MAX_PIXELS);
        int step = Math.max(BLOCKS_PER_SAMPLE, coverage / pixels);
        int samples = Math.max(1, coverage / step);

        int half = samples / 2;
        // The thumbnail is centred on SPAWN, not the origin. A dimension can
        // declare its spawn anywhere — paradise_lost measures at (-192,-512)
        // — and centring on 0,0 meant the picture showed one place while the
        // sidebar reported the biome of another. The border view stays on the
        // origin, because that is where the border is centred.
        int[] configured = def.getSpawn();
        int centreX = 0;
        int centreZ = 0;
        if (resolution == Resolution.LOWRES && configured != null && configured.length >= 3) {
            centreX = configured[0];
            centreZ = configured[2];
        }

        Registry<Biome> biomeRegistry = server.getRegistryManager().get(RegistryKeys.BIOME);

        int cells = samples * samples;
        int[] terrainColor = new int[cells];
        int[] waterColor = new int[cells];
        int[] height = new int[cells];
        boolean[] known = new boolean[cells];
        boolean[] water = new boolean[cells];

        // The thumbnail asks the climate sampler for a height instead of the
        // terrain router. vanilla's getHeight builds a fresh ChunkNoiseSampler
        // per call, which is what made a map cost minutes; the climate point
        // is already being read for the biome, and its depth carries the same
        // landmass and coastline the eye is actually reading at this size.
        // Not for a ceilinged or island world: there, "is there ground here at
        // all" is the whole picture, and depth cannot answer it.
        // The shape half of the router, kept beside the climate half so one
        // NoiseConfig answers both the biome and where the ground is.
        long modelStart = System.nanoTime();
        final HeightModel model = heightModel(server, base, seed, coverage);
        long modelNanos = System.nanoTime() - modelStart;
        final Integer seaLevel = model.seaLevel();
        com.customdimensions.MultiverseServer.LOGGER.debug(
                "render {} seed={}: height source {} ({}/{} columns agreed depth is a height)",
                dimensionId, seed, model.heightSource(),
                model.calibration().agreed(), model.calibration().tested());

        Map<String, BiomeColors> sharedColors = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicInteger sampled = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger minH =
                new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
        java.util.concurrent.atomic.AtomicInteger maxH =
                new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);

        // A quarter of the machine. Rendering is a background nicety that
        // runs beside the search; taking every core made a map arrive sooner
        // and every measurement behind it arrive later, which is the wrong
        // trade when the search is what the shortlist is made of.
        int workers = Math.max(1, Math.min(renderCores(), samples));
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(workers, r -> {
                    Thread t = new Thread(r, "customdim-render");
                    t.setDaemon(true);
                    return t;
                });
        final int centreXF = centreX;
        final int centreZF = centreZ;
        final int sideF = samples;
        final int stepF = step;
        final int halfF = half;
        java.util.concurrent.atomic.AtomicLong rigTotal = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong rigMax = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong climateNanos = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong surfaceNanos = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong densityProbes = new java.util.concurrent.atomic.AtomicLong();

        // The height field, on its own lattice. Biome and climate are read at
        // every grid point below; a column walk is a hundred times dearer
        // than either and does not need that density to draw relief, so it is
        // measured every HEIGHT_SAMPLE_FACTOR points and interpolated back up
        // (heightAt). A generator with no density to walk keeps the depth
        // fallback, which is free — the climate point is already being read —
        // and so takes no lattice at all.
        final boolean walkDensity = !model.useDepth();
        final int heightStride = walkDensity ? Math.max(1, HEIGHT_SAMPLE_FACTOR) : 1;
        final int coarseSide = (samples - 1) / heightStride + 2;
        final int[] coarseHeight = new int[coarseSide * coarseSide];
        final boolean[] coarseKnown = new boolean[coarseSide * coarseSide];

        long loopStart = System.nanoTime();
        try {
            if (walkDensity) {
                List<java.util.concurrent.Future<?>> heights = new ArrayList<>();
                for (int w = 0; w < workers; w++) {
                    final int worker = w;
                    final int stride = workers;
                    heights.add(pool.submit(() -> {
                        long rigStart = System.nanoTime();
                        // Per-worker and it must stay so: the rig's own
                        // NoiseConfig carries this worker's interpolation and
                        // column caches, which are state.
                        SpikeSampler.Rig own = rigFor(server, base, model, seed);
                        TerrainShape.Density built = densityFor(model, own);
                        // Counted per worker and summed once at the end: an
                        // atomic per probe would contend on the one number the
                        // render is busiest producing, and measure the counter.
                        long[] probes = new long[1];
                        TerrainShape.Density shape = built == null ? null
                                : (x, y, z) -> {
                                    probes[0]++;
                                    return built.at(x, y, z);
                                };
                        long rigCost = System.nanoTime() - rigStart;
                        rigTotal.addAndGet(rigCost);
                        rigMax.accumulateAndGet(rigCost, Math::max);
                        long surfaceAcc = 0;
                        for (int cz = worker; cz < coarseSide; cz += stride) {
                            if (abandonIf.getAsBoolean()) {
                                surfaceNanos.addAndGet(surfaceAcc);
                                densityProbes.addAndGet(probes[0]);
                                throw new Abandoned();
                            }
                            for (int cx = 0; cx < coarseSide; cx++) {
                                int dx = centreXF
                                        + gridToWorldOffset(cx * heightStride, stepF, halfF);
                                int dz = centreZF
                                        + gridToWorldOffset(cz * heightStride, stepF, halfF);
                                long t0 = System.nanoTime();
                                Integer y = surfaceAt(model, shape, null, dx, dz);
                                surfaceAcc += System.nanoTime() - t0;
                                if (y != null) {
                                    int i = cz * coarseSide + cx;
                                    coarseKnown[i] = true;
                                    coarseHeight[i] = y;
                                }
                            }
                        }
                        surfaceNanos.addAndGet(surfaceAcc);
                        densityProbes.addAndGet(probes[0]);
                    }));
                }
                for (java.util.concurrent.Future<?> t : heights) {
                    t.get();
                }
            }

            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                final int worker = w;
                final int stride = workers;
                tasks.add(pool.submit(() -> {
                    // No density here: this pass reads the biome and the
                    // climate point, and takes its height off the lattice the
                    // pass above measured.
                    long rigStart = System.nanoTime();
                    SpikeSampler.Rig own = rigFor(server, base, model, seed);
                    long rigCost = System.nanoTime() - rigStart;
                    rigTotal.addAndGet(rigCost);
                    rigMax.accumulateAndGet(rigCost, Math::max);
                    long climateAcc = 0;
                    for (int gz = worker; gz < sideF; gz += stride) {
                        if (abandonIf.getAsBoolean()) {
                            climateNanos.addAndGet(climateAcc);
                            throw new Abandoned();
                        }
                        for (int gx = 0; gx < sideF; gx++) {
                            int dx = centreXF + gridToWorldOffset(gx, stepF, halfF);
                            int dz = centreZF + gridToWorldOffset(gz, stepF, halfF);
                            int idx = gz * sideF + gx;
                            long t0 = System.nanoTime();
                            SpikeSampler.Sample s = SpikeSampler.sample(own, dx, dz);
                            climateAcc += System.nanoTime() - t0;
                            sampled.incrementAndGet();
                            if (s.biome() == null) {
                                continue;
                            }
                            Integer surface = walkDensity
                                    ? heightAt(coarseHeight, coarseKnown, coarseSide,
                                            gx, gz, heightStride)
                                    : surfaceAt(model, null, s, dx, dz);
                            BiomeColors colors = sharedColors.computeIfAbsent(s.biome(), id -> {
                                BiomeColors c = biomeColors(biomeRegistry, id);
                                return c == null ? MISSING : c;
                            });
                            if (colors == MISSING) {
                                continue;
                            }
                            terrainColor[idx] = colors.terrain();
                            waterColor[idx] = colors.water();
                            if (surface == null) {
                                // A biome with no floor under it is open air —
                                // most of a floating-island world, and terrain
                                // rather than a failed measurement. paint reads
                                // water[] only where known[] is set, so asking
                                // waterAt here would buy a ChunkNoiseSampler per
                                // pixel for an answer nothing reads — 2048x2048
                                // of them on a wide dimension.
                                continue;
                            }
                            water[idx] = waterAt(model, own, s.biome(), surface, dx, dz);
                            known[idx] = true;
                            height[idx] = surface;
                            minH.accumulateAndGet(surface, Math::min);
                            maxH.accumulateAndGet(surface, Math::max);
                        }
                    }
                    climateNanos.addAndGet(climateAcc);
                }));
            }
            for (java.util.concurrent.Future<?> t : tasks) {
                t.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("render interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Abandoned abandoned) {
                throw abandoned;
            }
            throw new IOException("render sampling failed: " + e.getCause(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
        long loopWallNanos = System.nanoTime() - loopStart;

        long paintStart = System.nanoTime();
        BufferedImage image = paint(samples, pixels, terrainColor, waterColor, height, known,
                water, seaLevel, minH.get(), maxH.get(), voidColourFor(def));
        writeImageAtomically(image, outputPath);
        long paintNanos = System.nanoTime() - paintStart;
        long renderNanos = System.nanoTime() - renderStart;
        int n = Math.max(1, sampled.get());
        // Per-column cost is the number that says whether a render is paying
        // for its columns or for its setup: each of the `workers` threads
        // builds its own NoiseConfig before it samples anything, so a cheap
        // grid split many ways can spend most of a render on rigs.
        com.customdimensions.MultiverseServer.LOGGER.info(
                "render {} seed={} {}: {}x{} grid, {} columns, {} workers, {} ms ({} us/column)",
                dimensionId, seed, resolution, samples, samples, sampled.get(), workers,
                renderNanos / 1_000_000L, renderNanos / n / 1_000L);
        logTimings(dimensionId, seed, resolution, workers, n,
                new Timings(modelNanos, rigTotal.get(), rigMax.get(), loopWallNanos,
                        climateNanos.get(), surfaceNanos.get(), paintNanos,
                        densityProbes.get(),
                        walkDensity ? coarseSide * coarseSide : 0, heightStride));
        return new RenderResult(outputPath, pixels, step, renderNanos / n, renderNanos,
                sampled.get(), 0);
    }

    /**
     * Where the render's time went, as one line.
     *
     * <p>The parts are not the same kind of number and saying so is the point:
     * the model and the paint are wall time on this thread, the rigs and the
     * two loop halves are core time summed across workers, and the loop's own
     * figure is wall. Reporting them undifferentiated is how "four workers
     * cost the same as eight" gets read as a conclusion rather than as a
     * measurement taken under contention.
     *
     * <p>The probe count is what separates the two ways a surface walk can be
     * expensive — too many probes down a column, or too costly a density
     * behind each one. A time with no count behind it cannot tell them apart.
     */
    private static void logTimings(Identifier dimensionId, long seed, Resolution resolution,
                                   int workers, int columns, Timings t) {
        long probes = Math.max(1, t.densityProbes());
        long heightColumns = Math.max(1, t.heightColumns());
        com.customdimensions.MultiverseServer.LOGGER.info(
                "render {} seed={} {} split: heightModel {} ms (wall, calibrate included), "
                + "rigs {} ms core over {} workers (slowest {} ms), loop {} ms wall = "
                + "climate {} ms over {} columns + surface {} ms over {} columns "
                + "(1 height per {}x{} grid points), paint {} ms; "
                + "{} density probes ({} per height column, {} us each)",
                dimensionId, seed, resolution,
                t.modelNanos() / 1_000_000L,
                t.rigTotalNanos() / 1_000_000L, workers, t.rigMaxNanos() / 1_000_000L,
                t.loopWallNanos() / 1_000_000L,
                t.climateNanos() / 1_000_000L, columns,
                t.surfaceNanos() / 1_000_000L, t.heightColumns(),
                t.heightStride(), t.heightStride(),
                t.paintNanos() / 1_000_000L,
                t.densityProbes(), t.densityProbes() / heightColumns,
                t.surfaceNanos() / probes / 1_000L);
    }

    /**
     * Surface height from the climate point's depth.
     *
     * <p>{@code surface_Y = 128 * depth} is the relation {@code customdim
     * sample-noise} documents as generation ground truth for these graphs.
     * It is an approximation of the real router's answer — good enough for
     * the landmass, the coastline and the shading a thumbnail is read for,
     * and never used for a measurement.
     */
    static Integer heightFromDepth(double[] climate, int floorY, int topY) {
        if (climate == null || climate.length < 5) {
            return null;
        }
        int y = (int) Math.round(128.0 * climate[4]);
        return Math.max(floorY, Math.min(topY, y));
    }

    /**
     * The dimension's settings with the climate chains and the final density
     * kept, everything else zeroed, and the final density's wrapper markers
     * rewritten — one {@link NoiseConfig} that answers both the biome and
     * where the ground is, and answers the second the way generation does.
     *
     * <p><b>The rewrite happens here, on the datapack's own template, and not
     * on a built router.</b> Seeding and rewriting are independent transforms
     * of the same graph and they commute: vanilla's constructor binds
     * {@code Noise} leaves and leaves wrappers alone, and
     * {@link WrapperRewrite} substitutes at wrappers and leaves leaves alone.
     * Doing ours first means our nodes enter a graph nothing else has
     * instrumented, and that nothing is ever asked to rebuild a graph it
     * already compiled — which is what a router taken off a live
     * {@code NoiseConfig} would be.
     *
     * <p>Null for a generator with no settings of its own (a flat or void
     * fallback), which has no terrain to ask about either.
     */
    private static ChunkGeneratorSettings climateAndShape(ChunkGeneratorSettings settings,
                                                          WrapperRewrite rewrite) {
        if (settings == null) {
            return null;
        }
        NoiseRouter r = settings.noiseRouter();
        DensityFunction zero = DensityFunctionTypes.zero();
        DensityFunction interpolated = r.finalDensity().apply(rewrite);
        return new ChunkGeneratorSettings(
                settings.generationShapeConfig(), settings.defaultBlock(), settings.defaultFluid(),
                new NoiseRouter(zero, zero, zero, zero,
                        r.temperature(), r.vegetation(), r.continents(),
                        r.erosion(), r.depth(), r.ridges(),
                        zero, interpolated, zero, zero, zero),
                settings.surfaceRule(), settings.spawnTarget(), settings.seaLevel(),
                settings.mobGenerationDisabled(), settings.aquifers(), settings.oreVeins(),
                settings.usesLegacyRandom());
    }

    /** A rig over an already-built config — no per-seed work of its own. */
    private static SpikeSampler.Rig rigOver(SpikeSampler.Base base, NoiseConfig config) {
        return new SpikeSampler.Rig(base.generator(), config, base.heightLimit(),
                base.hasCeiling(), base.biomeSourceAcceptsWithSeed(), true, null);
    }

    /**
     * The {@link NoiseConfig} for one (settings, seed).
     *
     * <p><b>Never shared.</b> Building it is what seeds the router, and the
     * seeding pass rebuilds the stateful nodes {@link #climateAndShape}
     * planted in the final density — so one config is one interpolation cache
     * and one set of column caches, and two threads sampling one config would
     * be two threads sharing them. Each render worker builds its own for the
     * same reason it has always held its own rewritten tree.
     */
    private static NoiseConfig shapeConfigFor(MinecraftServer server,
                                              ChunkGeneratorSettings settings, long seed) {
        var lookup = server.getRegistryManager()
                .get(RegistryKeys.NOISE_PARAMETERS).getReadOnlyWrapper();
        return NoiseConfig.create(settings, lookup, seed);
    }

    /**
     * The rig's final density as {@link TerrainShape}'s plain seam. Sampling
     * it outside a {@code ChunkNoiseSampler} is what makes it affordable —
     * vanilla's own {@code getHeight} rebuilds one of those per column.
     */
    private static TerrainShape.Density finalDensityOf(SpikeSampler.Rig rig) {
        if (rig.noiseConfig() == null) {
            return null;
        }
        DensityFunction fd = rig.noiseConfig().getNoiseRouter().finalDensity();
        return (x, y, z) -> fd.sample(new DensityFunction.UnblendedNoisePos(x, y, z));
    }

    /**
     * The band terrain can occupy, and the spacing a column through it is
     * walked at — both read off the generator's own shape config, clipped to
     * the dimension type's height limit.
     *
     * <p>A dimension type is routinely taller than the generator that fills
     * it (the End's type is 256 blocks and its generator places nothing above
     * 128), and every worldgen mod in this pack declares its own shape, so
     * neither number can be assumed.
     */
    private static GenerationShapeConfig shapeOf(ChunkGeneratorSettings settings,
                                                 int floorY, int topY) {
        if (settings == null) {
            return null;
        }
        return settings.generationShapeConfig().trimHeight(
                new net.minecraft.world.HeightLimitView() {
                    @Override
                    public int getHeight() {
                        return topY - floorY + 1;
                    }

                    @Override
                    public int getBottomY() {
                        return floorY;
                    }
                });
    }

    private static TerrainShape.Band bandOf(GenerationShapeConfig shape, int floorY, int topY) {
        if (shape == null) {
            return new TerrainShape.Band(floorY, topY, 8);
        }
        return new TerrainShape.Band(
                Math.max(floorY, shape.minimumY()),
                Math.min(topY, shape.minimumY() + shape.height() - 1),
                shape.verticalCellBlockCount());
    }

    /**
     * Whether {@code 128 * depth} describes this generator's surface, decided
     * by asking the final density at the height depth claims.
     *
     * <p>The columns are the same deterministic off-lattice spread the spike
     * probes use — a grid-aligned sample would land on the noise lattice,
     * where Perlin is exactly zero, and agree for the wrong reason.
     */
    private static TerrainShape.Calibration calibrate(SpikeSampler.Rig rig, TerrainShape.Band band,
                                                      int coverage, int floorY, int topY) {
        // The rig's own final density, which already blends at the router's
        // interpolated marker — so the recorded verdict describes what a map
        // would actually have done, blended once and at the right place.
        TerrainShape.Density shape = finalDensityOf(rig);
        if (shape == null) {
            return new TerrainShape.Calibration(0, 0, true);
        }
        int[] xs = new int[CALIBRATION_COLUMNS];
        int[] zs = new int[CALIBRATION_COLUMNS];
        Integer[] claimed = new Integer[CALIBRATION_COLUMNS];
        for (int i = 0; i < CALIBRATION_COLUMNS; i++) {
            int[] at = SpikeSampler.probe(i, Math.max(1, coverage / 2));
            xs[i] = at[0];
            zs[i] = at[1];
            claimed[i] = heightFromDepth(SpikeSampler.sample(rig, at[0], at[1]).climate(),
                    floorY, topY);
        }
        return TerrainShape.calibrate(shape, band, xs, zs, claimed);
    }

    /** What this dimension's empty columns are looking down into. */
    public static int voidColourFor(DimensionConfig def) {
        String type = def.getType() == null ? "" : def.getType().toLowerCase(java.util.Locale.ROOT);
        if (type.startsWith("nether")) {
            return VOID_NETHER;
        }
        if (type.startsWith("end") || type.equals("sky_islands")) {
            return VOID_END;
        }
        if (type.equals("cave")) {
            return VOID_CAVE;
        }
        if (type.contains("paradise_lost")) {
            return VOID_SKY;
        }
        return OPEN_AIR_COLOR;
    }

    /**
     * Turns the sampled grid into pixels.
     *
     * <p>Nothing is drawn over the terrain — no structure markers, no spawn
     * dot, no border ring. Those were a fifth of every thumbnail's pixels and
     * read as confetti; the viewer draws them as SVG layers over the image,
     * where they can be toggled and where they cost the map nothing.
     */
    private static BufferedImage paint(int samples, int pixels, int[] terrainColor,
                                       int[] waterColor, int[] height, boolean[] known,
                                       boolean[] water, Integer seaLevel,
                                       int minHeight, int maxHeight, int voidColor) {
        BufferedImage image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB);
        int range = Math.max(1, maxHeight - minHeight);
        for (int py = 0; py < pixels; py++) {
            int gz = Math.min(py * samples / pixels, samples - 1);
            for (int px = 0; px < pixels; px++) {
                int gx = Math.min(px * samples / pixels, samples - 1);
                int idx = gz * samples + gx;
                int color;
                if (!known[idx]) {
                    color = voidColor;
                } else if (water[idx]) {
                    // Either the biome is water-surfaced, or the column sits
                    // under a water sea — a lake in a plains biome is water
                    // without the biome ever saying so. The rule itself is
                    // {@link #waterAt}, applied when the grid was sampled.
                    color = waterColorAt(waterColor[idx], height[idx],
                            seaLevel == null ? height[idx] : seaLevel, minHeight);
                } else {
                    int base = terrainColor[idx];
                    // Higher ground reads slightly cooler and lighter, the way
                    // a hypsometric map does — it separates a plateau from a
                    // valley of the same biome.
                    double lift = (height[idx] - minHeight) / (double) range;
                    base = shade(base, 0.94 + 0.12 * lift);
                    // Contour lines every CONTOUR_INTERVAL blocks: cheap, and
                    // the single strongest cue that a picture is terrain.
                    int band = Math.floorMod(height[idx], CONTOUR_INTERVAL);
                    if (band < 1 || band > CONTOUR_INTERVAL - 2) {
                        base = shade(base, 0.88);
                    }
                    color = shade(base, relief(height, known, samples, gx, gz));
                }
                image.setRGB(px, py, color);
            }
        }
        return image;
    }

    // ----------------------------------------------------------------- colour

    /** One biome's terrain tint and water colour, both real {@link BiomeEffects} fields. */
    private record BiomeColors(int terrain, int water) {
    }

    private static BiomeColors biomeColors(Registry<Biome> registry, String biomeId) {
        Identifier id = Identifier.tryParse(biomeId);
        Biome biome = id == null ? null : registry.get(id);
        if (biome == null) {
            return null;
        }
        BiomeEffects effects = biome.getEffects();
        // The GROUND, not the air. A biome's fog and sky are the colour you
        // see looking through a world, and the modded nether packs set them
        // to vivid pinks — a map painted with them carries no information
        // about the terrain. SurfacePalette answers with the map colour of
        // the block the surface rule actually places.
        return new BiomeColors(SurfacePalette.colourOf(biomeId), effects.getWaterColor());
    }

    /**
     * Grass override, then foliage override, then a fog/sky blend for a biome
     * with neither — every nether/end biome, and any overworld biome that
     * relies on vanilla's temperature/downfall grass gradient rather than an
     * explicit tint. That gradient is a client-side texture lookup with no
     * server-side counterpart, so reproducing it would mean guessing pixel
     * values; the fog/sky blend is not a guess, it is the other two colours
     * every biome's {@link BiomeEffects} always carries.
     */
    static int terrainBaseColor(Integer grassOverride, Integer foliageOverride, int fogColor, int skyColor) {
        if (grassOverride != null) {
            return grassOverride;
        }
        if (foliageOverride != null) {
            return foliageOverride;
        }
        return blend(fogColor, skyColor, 0.5);
    }

    /**
     * How lit a cell is, from the terrain around it.
     *
     * <p>Vanilla's three-step comparison against the cell to the north
     * carries the shape of the land; a directional hillshade off the local
     * gradient is mixed in on top so a slope reads as a slope rather than as
     * a stack of terraces. Both are LOCAL — a cell's colour says how it sits
     * relative to its neighbours, never where it sits in the dimension's
     * whole height range, which is what turned a nether world into static.
     */
    static double relief(int[] height, boolean[] known, int side, int gx, int gz) {
        int here = height[gz * side + gx];
        int north = sampleHeight(height, known, side, gx, gz - 1, here);
        double step = here > north ? MC_SHADE_BRIGHT : here < north ? MC_SHADE_DARK : MC_SHADE_LEVEL;

        int west = sampleHeight(height, known, side, gx - 1, gz, here);
        int east = sampleHeight(height, known, side, gx + 1, gz, here);
        int south = sampleHeight(height, known, side, gx, gz + 1, here);
        double dzdx = (east - west) * HILLSHADE_GAIN;
        double dzdy = (south - north) * HILLSHADE_GAIN;
        // Light from the north-west. The dzdy term is PLUS: z grows southward,
        // so ground rising to the south faces north, toward the light — the
        // same cell vanilla's step calls bright. Negating it put the two in
        // opposition and the directional won, so a slope rising away from the
        // viewer rendered darker than one falling away.
        double light = (-dzdx + dzdy) / Math.sqrt(dzdx * dzdx + dzdy * dzdy + 2.0);
        double directional = 0.75 + 0.35 * clamp(light, -1.0, 1.0);
        return clamp(step * (1.0 - HILLSHADE_MIX) + directional * HILLSHADE_MIX,
                MIN_SHADE, MAX_SHADE);
    }

    /** A neighbour's height, or this cell's own where the grid has nothing (edge, outside the disc). */
    private static int sampleHeight(int[] height, boolean[] known, int side, int gx, int gz, int fallback) {
        if (gx < 0 || gz < 0 || gx >= side || gz >= side) {
            return fallback;
        }
        int idx = gz * side + gx;
        return known[idx] ? height[idx] : fallback;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** Deeper water reads darker, floored so a deep column is still recognisably water, not black. */
    static int waterColorAt(int waterColor, int height, int seaLevel, int minHeight) {
        int span = Math.max(1, seaLevel - minHeight);
        double depthT = clamp01((seaLevel - height) / (double) span);
        return shade(waterColor, 1.0 - 0.4 * depthT);
    }

    /** Linear interpolation between two packed RGB ints, {@code t} in [0, 1]. */
    static int blend(int a, int b, double t) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = (int) Math.round(ar + (br - ar) * t);
        int g = (int) Math.round(ag + (bg - ag) * t);
        int bl = (int) Math.round(ab + (bb - ab) * t);
        return (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(bl);
    }

    /** Multiplies every channel by {@code factor}, clamped to a valid byte. */
    static int shade(int color, double factor) {
        int r = (int) Math.round(((color >> 16) & 0xFF) * factor);
        int g = (int) Math.round(((color >> 8) & 0xFF) * factor);
        int b = (int) Math.round((color & 0xFF) * factor);
        return (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ------------------------------------------------------------- geometry

    /** World block offset from the centre for a grid index — the inverse of {@link #worldToGrid}. */
    static int gridToWorldOffset(int gridIndex, int step, int half) {
        return (gridIndex - half) * step;
    }

    /** The nearest grid index for a world block offset from the centre. */
    static int worldToGrid(int worldOffset, int step, int half) {
        return (int) Math.round(worldOffset / (double) step) + half;
    }

    private static boolean paintMarker(BufferedImage image, int side, int gx, int gz, int radius, int color) {
        boolean painted = false;
        for (int dz = -radius; dz <= radius; dz++) {
            int y = gz + dz;
            if (y < 0 || y >= side) {
                continue;
            }
            for (int dx = -radius; dx <= radius; dx++) {
                int x = gx + dx;
                if (x < 0 || x >= side) {
                    continue;
                }
                image.setRGB(x, y, color);
                painted = true;
            }
        }
        return painted;
    }

    // ---------------------------------------------------------- structures

    /** Groups the overlays mark as dangerous — the split the scorer uses too. */
    private static final Set<String> HOSTILE_GROUPS = Set.of("dungeons", "endgame");

    /**
     * One noise-managed site: where it is, and which structure the pick
     * assigned there. The id is the real assignment
     * ({@link com.customdimensions.dimension.StructurePick}), not a guess from
     * the group — the sidebar names a structure, so it has to be the one that
     * generates.
     */
    public record Site(long x, long z, String structureId,
                       List<StructurePick.PoolEntry> chain) {

        public Site(long x, long z, String structureId) {
            this(x, z, structureId, List.of());
        }
    }

    /**
     * Every noise-managed structure site, block-centred, for groups whose
     * pool actually has something in it — an empty-pool group would draw
     * markers where nothing can ever generate. Mirrors {@code
     * FactsEngine.structureFacts}'s group/pool resolution but skips the
     * vanilla biome-prefilter it applies before weighting: that prefilter
     * only sharpens which structure a site is ASSIGNED, and this render never
     * shows an assignment, only that a site exists — the same field
     * positions either way.
     *
     * <p>Keyed by group, because that is how a person reads them — landmarks
     * and settlements are different questions, and drawing all of them at
     * once is an opaque blob rather than a map. Nothing draws these into the
     * PNG; the viewer overlays the group a person selects.
     */
    public static Map<String, List<Site>> structurePositions(
            MinecraftServer server, DimensionConfig def, SpikeSampler.Base base,
            long seed, int radius) {
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(def);
        if (plan.isSuppressed()) {
            return Map.of();
        }
        var setRegistry = server.getRegistryManager().get(RegistryKeys.STRUCTURE_SET);
        Set<String> exclude = new HashSet<>(NoisePoolBuilder.lowerSet(
                def.getStructures() == null ? null : def.getStructures().exclude));
        exclude.addAll(NoisePoolBuilder.lowerSet(
                MultiverseConfig.getInstance().getSuppressedStructureSets()));
        // The same prefiltered set list FactsEngine measures from. Positions do
        // not depend on the pool, but the ASSIGNMENT does — an unfiltered pool
        // is a strict superset whose extra structures take probability mass a
        // live world would never give them, and the sidebar would then name a
        // structure that cannot generate here. The check is a live one: every
        // id/count in /census must equal the candidate's banked byStructure.
        Set<net.minecraft.util.Identifier> dimensionBiomes =
                NoisePoolBuilder.biomeIds(base.generator().getBiomeSource());
        Set<String> wanted = NoisePoolBuilder.wantedStructureIds(def);
        List<net.minecraft.registry.entry.RegistryEntry<
                net.minecraft.structure.StructureSet>> sets = new ArrayList<>();
        for (var e : setRegistry.getIndexedEntries()) {
            if (NoisePoolBuilder.survivesVanillaPrefilter(e, dimensionBiomes, wanted)) {
                sets.add(e);
            }
        }
        NoisePoolBuilder.Result pools = NoisePoolBuilder.build(
                def, sets, base.generator().getBiomeSource(), plan, exclude, null, wanted);
        Set<String> admitted = NoisePoolBuilder.admittedStructureIds(def, sets);

        int radiusChunks = radius / 16;
        long dimensionSalt = DimensionStructures.saltOf(def.getName());
        // The occupant pass needs a biome; the climate-only rig is the cheap
        // way to get one. Null leaves the pass off rather than diverging loudly.
        SpikeSampler.Rig climateRig = SpikeSampler.forSeedClimate(server, base, seed);
        net.minecraft.world.gen.noise.NoiseConfig climate =
                climateRig.ok() ? climateRig.noiseConfig() : null;
        Map<String, List<Site>> byGroup = new java.util.LinkedHashMap<>();
        for (var groupEntry : plan.groups().entrySet()) {
            String group = groupEntry.getKey();
            NoiseGroupPlan.Group settings = groupEntry.getValue();
            NoisePoolBuilder.Pool pool = pools.pools().get(group);
            if (pool == null || pool.entries().isEmpty()) {
                continue;
            }
            long noiseSeed = seed ^ dimensionSalt ^ DimensionStructures.saltOf(group);
            // Same pool, same sort, same seed as DimensionStructures builds for
            // the live world, so a site's id here is the one that generates —
            // and resolved BEFORE the placement, because the field asks the
            // pool how much ground each site claims.
            List<StructurePick.PoolEntry> pickPool = new ArrayList<>();
            for (var weighted : pool.entries()) {
                weighted.structure().getKey().ifPresent(key -> pickPool.add(
                        new StructurePick.PoolEntry(key.getValue().toString(), weighted.weight(),
                                admitted.contains(key.getValue().toString()), weighted.structure(),
                                pools.wanted().contains(key.getValue().toString()),
                                pools.shunned().contains(key.getValue().toString()))));
            }
            List<StructurePick.PoolEntry> sorted = StructurePick.sortedPool(pickPool);
            NoiseStructurePlacement placement = NoiseStructurePlacement.forGroup(
                    def.getDimensionIdentifier().toString(), group, noiseSeed, settings.profile(), settings.exclusion(),
                    settings.radial(), radiusChunks, settings.clearSpawnChunks(),
                    sorted, base.generator().getBiomeSource(), climate);
            List<Site> positions = byGroup.computeIfAbsent(group, g -> new ArrayList<>());
            for (ChunkPos pos : placement.index().positions()) {
                // The chain the mixin will walk, not just its head: a site is
                // occupied by the first candidate its biome accepts.
                List<StructurePick.PoolEntry> chain = StructurePick.candidates(sorted,
                        StructurePick.pickValue(noiseSeed, pos.x, pos.z),
                        StructurePick.MAX_CANDIDATES);
                positions.add(new Site(pos.x * 16L + 8, pos.z * 16L + 8,
                        chain.isEmpty() ? null : chain.get(0).structureId(), chain));
            }
        }
        return byGroup;
    }

    /** Whether a group is one the overlays mark as dangerous. */
    public static boolean isHostileGroup(String group) {
        return HOSTILE_GROUPS.contains(group);
    }


    // ---------------------------------------------------------------- write

    /** Same tmp-file-plus-atomic-rename shape {@code Artefacts.write} uses, for a binary body. */
    private static void writeImageAtomically(BufferedImage image, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        if (!ImageIO.write(image, "png", tmp.toFile())) {
            throw new IOException("no PNG writer registered for this JVM");
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
