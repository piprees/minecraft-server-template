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

    /**
     * Cores a single render fans out over. Fixed rather than a fraction of
     * the machine: the roller takes what is left, so the two shares are
     * stated once in {@link com.customdimensions.web.RollPipeline} and here
     * rather than each guessing at the other.
     */
    public static final int RENDER_CORES = 8;

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
                              TerrainShape.Calibration calibration, boolean hasCeiling,
                              int cellHorizontal, int shapeMinimumY, boolean floodsVoid) {

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
        if (base.generator() instanceof NoiseChunkGenerator noiseGen) {
            var settings = noiseGen.getSettings().value();
            if (settings.defaultFluid().isOf(net.minecraft.block.Blocks.WATER)) {
                seaLevel = settings.seaLevel();
            }
        }
        // Broader than the WATER-specific seaLevel gate above: any non-empty
        // default fluid (lava included) means a groundless column is worth
        // probing rather than assumed dry — see waterAt.
        boolean floodsVoid = SpikeSampler.floodsVoid(base.generator());

        ChunkGeneratorSettings shapeSettings = climateAndShape(server, base);
        GenerationShapeConfig shape = shapeOf(shapeSettings, floorY, topY);
        TerrainShape.Band band = bandOf(shape, floorY, topY);
        TerrainShape.Calibration calibration = new TerrainShape.Calibration(0, 0, true);
        if (shapeSettings != null) {
            calibration = calibrate(shapeRig(server, base, shapeSettings, seed),
                    band, coverage, floorY, topY,
                    shape.horizontalCellBlockCount(), shape.minimumY());
        }
        // Only a generator with no density to walk falls back to depth.
        boolean useDepth = shapeSettings == null;
        return new HeightModel(band, useDepth, seaLevel, floorY, topY,
                shapeSettings, calibration, base.hasCeiling(),
                shape == null ? 4 : shape.horizontalCellBlockCount(),
                shape == null ? floorY : shape.minimumY(), floodsVoid);
    }

    /** One caller's own rig for a model — never shared between threads. */
    public static SpikeSampler.Rig rigFor(MinecraftServer server, SpikeSampler.Base base,
                                          HeightModel model, long seed) {
        return model.shapeSettings() == null
                ? SpikeSampler.forSeedClimate(server, base, seed)
                : shapeRig(server, base, model.shapeSettings(), seed);
    }

    /** The density a rig walks, or null when this model reads depth instead. */
    public static TerrainShape.Density densityFor(HeightModel model, SpikeSampler.Rig rig) {
        if (model.useDepth() || rig.noiseConfig() == null) {
            return null;
        }
        DensityFunction root = rig.noiseConfig().getNoiseRouter().finalDensity();
        InterpolateAtTheMarker rewrite = new InterpolateAtTheMarker(
                model.cellHorizontal(), model.band().cellHeight(), model.shapeMinimumY());
        DensityFunction rewritten = root.apply(rewrite);
        // The marker is matched by NAME, because its enum lives inside a
        // protected class. A name that stopped matching would leave the map
        // reading an un-interpolated density with nothing to say so — the
        // exact silent failure this codebase treats as its worst kind. Count
        // it and say so.
        if (rewrite.rewritten.get() == 0) {
            com.customdimensions.MultiverseServer.LOGGER.warn(
                    "render: no interpolated marker found in this router's final density — "
                    + "the map is reading an EXACT density where generation interpolates one");
        }
        return (x, y, z) -> rewritten.sample(new DensityFunction.UnblendedNoisePos(x, y, z));
    }

    /**
     * Puts the cell interpolation exactly where the generator puts it.
     *
     * <p>A router marks ONE sub-tree {@code minecraft:interpolated}, and
     * vanilla's {@code ChunkNoiseSampler} substitutes a real interpolating
     * sampler for that marker and nothing else — everything downstream of it
     * is evaluated per block, exactly. Terralith's, Tectonic's and this mod's
     * own overworld routers all read
     * {@code min(squeeze(0.64 * interpolated(...)), noodle)}: the shape core
     * is blended across the cell, and the squeeze clamp and the cave
     * subtraction are not.
     *
     * <p>Blending the WHOLE final density instead — which is what this did
     * first — is a different approximation, and the two agree only where the
     * downstream part happens to be linear across a cell. It is not linear on
     * a slope, and a hard clamp is about as far from linear as a density
     * function gets: measured, the error was unsigned, rose with local
     * relief, and was worst in the nether, whose roof clamp lives downstream
     * of the marker.
     *
     * <p>Generic on purpose. The marker is authored in the worldgen JSON, so
     * this reads the boundary out of whatever graph the pack actually loaded
     * rather than knowing anything about a generator family — the same rule
     * {@link TerrainShape#calibrate} follows, and for the same reason.
     */
    private static final class InterpolateAtTheMarker
            implements DensityFunction.DensityFunctionVisitor {

        private final int cellHorizontal;
        private final int cellVertical;
        private final int shapeMinimumY;
        final java.util.concurrent.atomic.AtomicInteger rewritten =
                new java.util.concurrent.atomic.AtomicInteger();

        InterpolateAtTheMarker(int cellHorizontal, int cellVertical, int shapeMinimumY) {
            this.cellHorizontal = cellHorizontal;
            this.cellVertical = cellVertical;
            this.shapeMinimumY = shapeMinimumY;
        }

        @Override
        public DensityFunction apply(DensityFunction function) {
            // The marker's enum type is nested inside a PROTECTED class, so
            // it cannot be named from here. Its identity is compared by name
            // instead, which covers both the enum constant (INTERPOLATED) and
            // the StringIdentifiable spelling (interpolated) without needing
            // an accessor mixin for one comparison.
            if (function instanceof DensityFunctionTypes.Wrapper wrapper
                    && "interpolated".equalsIgnoreCase(String.valueOf(wrapper.type()))) {
                this.rewritten.incrementAndGet();
                return new CellInterpolated(wrapper.wrapped(),
                        this.cellHorizontal, this.cellVertical, this.shapeMinimumY);
            }
            return function;
        }

        @Override
        public DensityFunction.Noise apply(DensityFunction.Noise noise) {
            return noise;
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
        private final TerrainShape.Density blended;

        CellInterpolated(DensityFunction wrapped, int cellHorizontal, int cellVertical,
                         int shapeMinimumY) {
            this.wrapped = wrapped;
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

        @Override
        public DensityFunction apply(DensityFunctionVisitor visitor) {
            return visitor.apply(this);
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

    /** The surface the render would paint at one column, or null for open air. */
    public static Integer surfaceAt(HeightModel model, TerrainShape.Density shape,
                                    SpikeSampler.Sample sample, int x, int z) {
        return shape == null
                ? heightFromDepth(sample.climate(), model.floorY(), model.topY())
                : TerrainShape.surfaceY(shape, model.band(), x, z, model.hasCeiling());
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
        final HeightModel model = heightModel(server, base, seed, coverage);
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

        // One rig per worker: a rig carries noise state a column read walks,
        // and SpikeSampler makes no promise about sharing one across threads.
        // A quarter of the machine. Rendering is a background nicety that
        // runs beside the search; taking every core made a map arrive sooner
        // and every measurement behind it arrive later, which is the wrong
        // trade when the search is what the shortlist is made of.
        int workers = Math.max(1, Math.min(RENDER_CORES, samples));
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
        try {
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                final int worker = w;
                final int stride = workers;
                tasks.add(pool.submit(() -> {
                    SpikeSampler.Rig own = rigFor(server, base, model, seed);
                    TerrainShape.Density shape = densityFor(model, own);
                    for (int gz = worker; gz < sideF; gz += stride) {
                        if (abandonIf.getAsBoolean()) {
                            throw new Abandoned();
                        }
                        for (int gx = 0; gx < sideF; gx++) {
                            int dx = centreXF + gridToWorldOffset(gx, stepF, halfF);
                            int dz = centreZF + gridToWorldOffset(gz, stepF, halfF);
                            int idx = gz * sideF + gx;
                            SpikeSampler.Sample s = SpikeSampler.sample(own, dx, dz);
                            sampled.incrementAndGet();
                            if (s.biome() == null) {
                                continue;
                            }
                            Integer surface = surfaceAt(model, shape, s, dx, dz);
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

        BufferedImage image = paint(samples, pixels, terrainColor, waterColor, height, known,
                water, seaLevel, minH.get(), maxH.get(), voidColourFor(def));
        writeImageAtomically(image, outputPath);
        long renderNanos = System.nanoTime() - renderStart;
        int n = Math.max(1, sampled.get());
        return new RenderResult(outputPath, pixels, step, renderNanos / n, renderNanos,
                sampled.get(), 0);
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
     * kept and everything else zeroed — one {@link NoiseConfig} that answers
     * both the biome and where the ground is.
     *
     * <p>Null for a generator with no settings of its own (a flat or void
     * fallback), which has no terrain to ask about either.
     */
    private static ChunkGeneratorSettings climateAndShape(MinecraftServer server,
                                                          SpikeSampler.Base base) {
        if (!(base.generator() instanceof NoiseChunkGenerator noiseGen)) {
            return null;
        }
        ChunkGeneratorSettings settings = noiseGen.getSettings().value();
        NoiseRouter r = settings.noiseRouter();
        DensityFunction zero = DensityFunctionTypes.zero();
        return new ChunkGeneratorSettings(
                settings.generationShapeConfig(), settings.defaultBlock(), settings.defaultFluid(),
                new NoiseRouter(zero, zero, zero, zero,
                        r.temperature(), r.vegetation(), r.continents(),
                        r.erosion(), r.depth(), r.ridges(),
                        zero, r.finalDensity(), zero, zero, zero),
                settings.surfaceRule(), settings.spawnTarget(), settings.seaLevel(),
                settings.mobGenerationDisabled(), settings.aquifers(), settings.oreVeins(),
                settings.usesLegacyRandom());
    }

    /**
     * One worker's rig over {@link #climateAndShape} settings. Each worker
     * builds its own: a {@code NoiseConfig} carries per-seed noise samplers
     * and nothing promises they are shared safely.
     */
    private static SpikeSampler.Rig shapeRig(MinecraftServer server, SpikeSampler.Base base,
                                             ChunkGeneratorSettings settings, long seed) {
        var lookup = server.getRegistryManager()
                .get(RegistryKeys.NOISE_PARAMETERS).getReadOnlyWrapper();
        return new SpikeSampler.Rig(base.generator(),
                NoiseConfig.create(settings, lookup, seed), base.heightLimit(),
                base.hasCeiling(), base.biomeSourceAcceptsWithSeed(), true, null);
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
                                                      int coverage, int floorY, int topY,
                                                      int cellHorizontal, int shapeMinimumY) {
        // Through the same cell interpolation the render reads, so the
        // recorded verdict describes what a map would actually have done.
        TerrainShape.Density raw = finalDensityOf(rig);
        TerrainShape.Density shape = raw == null ? null
                : TerrainShape.cellInterpolated(raw, cellHorizontal,
                        band.cellHeight(), shapeMinimumY);
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
    static int voidColourFor(DimensionConfig def) {
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
    public record Site(long x, long z, String structureId) {
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

        int radiusChunks = radius / 16;
        long dimensionSalt = DimensionStructures.saltOf(def.getName());
        Map<String, List<Site>> byGroup = new java.util.LinkedHashMap<>();
        for (var groupEntry : plan.groups().entrySet()) {
            String group = groupEntry.getKey();
            NoiseGroupPlan.Group settings = groupEntry.getValue();
            NoisePoolBuilder.Pool pool = pools.pools().get(group);
            if (pool == null || pool.entries().isEmpty()) {
                continue;
            }
            long noiseSeed = seed ^ dimensionSalt ^ DimensionStructures.saltOf(group);
            NoiseStructurePlacement placement = new NoiseStructurePlacement(
                    group, noiseSeed, settings.profile(), settings.exclusion(),
                    settings.radial(), radiusChunks, 0, 0, settings.clearSpawnChunks());
            // Same pool, same sort, same seed as DimensionStructures builds for
            // the live world, so a site's id here is the one that generates.
            List<StructurePick.PoolEntry> pickPool = new ArrayList<>();
            for (var weighted : pool.entries()) {
                weighted.structure().getKey().ifPresent(key -> pickPool.add(
                        new StructurePick.PoolEntry(key.getValue().toString(), weighted.weight())));
            }
            List<StructurePick.PoolEntry> sorted = StructurePick.sortedPool(pickPool);
            List<Site> positions = byGroup.computeIfAbsent(group, g -> new ArrayList<>());
            for (ChunkPos pos : placement.index().positions()) {
                positions.add(new Site(pos.x * 16L + 8, pos.z * 16L + 8,
                        StructurePick.assignedStructure(noiseSeed, pos.x, pos.z, sorted)));
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
