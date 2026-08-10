package com.customdimensions.roll;

import com.customdimensions.command.InputHash;
import com.customdimensions.command.SpikeSampler;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionStructures;
import com.customdimensions.dimension.NoiseGroupPlan;
import com.customdimensions.dimension.NoisePoolBuilder;
import com.customdimensions.dimension.NoiseStructurePlacement;
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
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

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
 * <p>Colour comes from each sampled biome's own {@link BiomeEffects}: grass
 * or foliage where the biome carries one, its fog/sky blend where it does not
 * (every nether/end biome, and any overworld biome with no explicit tint) —
 * never a hash-derived palette. Water uses the biome's own water colour.
 * Terrain is shaded by the height actually sampled at that column, relative
 * to the minimum and maximum this render measured — never a value borrowed
 * from a different pass or a different resolution.
 *
 * <p>Lowres and highres get their pixels two different ways. {@code
 * FactsEngine} already samples a biome+height grid to measure a candidate
 * and persists it in the candidate's own {@code SeedFacts.grid} — lowres
 * reads that back, so it costs nothing beyond a file read and works for a
 * candidate measured before this render feature existed. That grid is
 * {@code FactsEngine.GRID} on a side (41, as of this writing) — coarser than
 * the budget-chosen size a sampled lowres used to produce (typically 51-53),
 * and worth it: a free render at the resolution a measurement already paid
 * for beats a slower one at a resolution nobody actually chose. Highres wants
 * finer detail than that measurement grid carries, so it samples its own, at
 * a side chosen from a real timing of this dimension's own per-column cost —
 * a cheap flat dimension and {@code the_gauntlet} do not deserve the same
 * pixel count for the same time budget.
 *
 * <p>Highres runs on the calling thread for its whole duration, the same as
 * {@link Roller#rollDimension} — it occupies the server's main thread for
 * close to its 60s budget when called from RCON. Lowres has no such cost and
 * is wired into {@link Roller}'s own scoring loop; nothing triggers a
 * highres render automatically. This platform's own {@code docker-compose.yml}
 * sets {@code MAX_TICK_TIME: -1} for the {@code mc} service in every
 * profile, local and cloud, so a stock deployment has no tick watchdog for a
 * long main-thread block to trip. A highres call still refuses outright on
 * any server where the watchdog IS armed — a consumer that overrides the
 * compose file, or a build running outside it entirely — because no
 * per-column timing bound here can promise staying under an arbitrary
 * configured limit.
 */
public final class CandidateRender {

    private CandidateRender() {
    }

    public enum Resolution {
        LOWRES(Duration.ofSeconds(5).toNanos()),
        HIGHRES(Duration.ofSeconds(60).toNanos());

        private final long budgetNanos;

        Resolution(long budgetNanos) {
            this.budgetNanos = budgetNanos;
        }
    }

    /**
     * Below this side the picture is a handful of pixels, not a decision
     * aid; above it, memory and PNG size stop being worth the extra
     * resolution. The floor stays low on purpose: it exists only to guard a
     * pathologically expensive dimension against a degenerate image, and a
     * floor high enough to matter for an ordinary per-column cost would blow
     * every ordinary render's time budget to protect against the extreme
     * one. Both bounds are stated, never silent — a render that hits either
     * says so in its result.
     */
    static final int MIN_SIDE = 33;
    static final int MAX_SIDE = 2049;

    /**
     * Calibration spends real wall-clock time, not a fixed sample count, so
     * its own cost stays a small, bounded slice of the budget whether a
     * column costs a microsecond or fifty milliseconds.
     */
    private static final long CALIBRATION_BUDGET_NANOS = Duration.ofMillis(200).toNanos();
    private static final int MIN_CALIBRATION_SAMPLES = 8;
    private static final long CALIBRATION_SALT = 0x43414C4942L;

    /** Groups the render marks distinctly — the same split {@code FactsEngine} scores separately. */
    private static final Set<String> HOSTILE_GROUPS = Set.of("dungeons", "endgame");

    /**
     * The only groups a lowres render marks. At thumbnail scale, hundreds of
     * {@code deco} sites own the picture and the biome layout underneath
     * disappears — these three are what a person actually cites when judging
     * whether a world is worth visiting; a highres render, with room to
     * spare, marks every group.
     */
    private static final Set<String> LOWRES_STRUCTURE_GROUPS = Set.of("landmarks", "endgame", "dungeons");

    // A column that could not be measured — never confused with a real biome tint.
    private static final int UNKNOWN_COLOR = 0xFF00FF;
    // Outside the playable border: no biome ever produced this pixel.
    private static final int VOID_COLOR = 0x101018;
    // Annotations over the terrain, not facts about it.
    private static final int BORDER_COLOR = 0xFFFF00;
    private static final int STRUCTURE_COLOR = 0xFFFFFF;
    private static final int HOSTILE_STRUCTURE_COLOR = 0xB22222;
    private static final int SPAWN_COLOR = 0x00E5FF;
    // A biome boundary, tinted rather than opaque so it reads as a line, not a wall.
    private static final int BIOME_BOUNDARY_COLOR = 0xD8D8D8;
    private static final double BOUNDARY_STRENGTH = 0.55;

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
     * Renders one (dimension, seed) to {@code outputPath}. Lowres reads the
     * candidate's own persisted grid ({@link #renderFromGrid}); highres
     * samples its own, finer one ({@link #renderBySampling}).
     */
    public static RenderResult render(MinecraftServer server, Identifier dimensionId,
                                      DimensionConfig def, long seed, Resolution resolution,
                                      Path outputPath) throws IOException {
        Objects.requireNonNull(def, "render needs a resolved dimension config");
        return resolution == Resolution.LOWRES
                ? renderFromGrid(server, dimensionId, def, seed, outputPath)
                : renderBySampling(server, dimensionId, def, seed, outputPath);
    }

    /**
     * Reads the biome+height grid {@code FactsEngine} already persisted for
     * this candidate and paints it — no sampling, no calibration, no time
     * budget, and it renders a seed measured before this feature existed
     * exactly as well as one measured a second ago.
     *
     * <p>A grid cell is {@code null} in {@link SeedFacts.Grid#biome} or
     * {@link SeedFacts.Grid#height} both for a column outside the playable
     * disc and for one inside it that answered nothing — the writer does not
     * distinguish them. This method still draws them differently, because it
     * can recompute which is which from {@code side}/{@code step}/{@code
     * radius} alone — the same geometry {@code FactsEngine.sampleGrid} used
     * to decide which cells to attempt in the first place — not by guessing.
     */
    private static RenderResult renderFromGrid(MinecraftServer server, Identifier dimensionId,
                                               DimensionConfig def, long seed,
                                               Path outputPath) throws IOException {
        long renderStart = System.nanoTime();
        String dimension = dimensionId.toString();
        String inputHash = InputHash.of(def, server);
        Path candidatePath = SeedBank.candidatePath(inputHash, dimension, seed);
        if (!Files.isRegularFile(candidatePath)) {
            throw new IOException("no banked candidate for " + dimensionId + " seed=" + seed
                    + " at " + candidatePath + " — run /customdim roll first, or render highres "
                    + "to sample fresh without banking");
        }
        JsonObject root = JsonParser.parseString(Files.readString(candidatePath)).getAsJsonObject();
        SeedFacts facts;
        try {
            facts = SeedFactsCodec.read(root.getAsJsonObject("facts").toString());
        } catch (RuntimeException e) {
            // Whichever field the codec reaches first, a record this codec
            // cannot parse in full is from an older schema, not a partially
            // readable one — this platform keeps no backwards compatibility.
            throw new IOException("candidate " + candidatePath + " is from an older schema and "
                    + "should be re-rolled (" + e.getMessage() + ")");
        }
        if (!facts.grid().isPresent()) {
            throw new IOException("candidate " + candidatePath + " has no grid: " + facts.grid().reason());
        }
        SeedFacts.Grid grid = facts.grid().orThrow();

        int radius = Math.max(1, def.getPlayerBorderRadius());
        int side = grid.side();
        int step = Math.max(1, (radius * 2) / (side - 1));
        int half = side / 2;

        SpikeSampler.Base base = SpikeSampler.base(server, dimensionId);
        if (!base.ok()) {
            throw new IOException("cannot render " + dimensionId + ": " + base.error());
        }
        Integer seaLevel = base.generator() instanceof NoiseChunkGenerator noiseGen
                ? noiseGen.getSettings().value().seaLevel() : null;
        Registry<Biome> biomeRegistry = server.getRegistryManager().get(RegistryKeys.BIOME);
        // The palette is a handful of ids for the whole grid — resolved once
        // each, not once per cell.
        List<BiomeColors> palette = new ArrayList<>();
        for (String id : grid.biomeIds()) {
            palette.add(biomeColors(biomeRegistry, id));
        }

        int cells = side * side;
        int[] terrainColor = new int[cells];
        int[] waterColor = new int[cells];
        int[] height = new int[cells];
        int[] biomeId = new int[cells];
        boolean[] known = new boolean[cells];
        boolean[] inDisc = new boolean[cells];
        Arrays.fill(biomeId, -1);

        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                int dx = gridToWorldOffset(gx, step, half);
                int dz = gridToWorldOffset(gz, step, half);
                if ((long) dx * dx + (long) dz * dz > (long) radius * radius) {
                    continue;   // recomputed geometry, not the grid's own null — see the javadoc above
                }
                int idx = gz * side + gx;
                inDisc[idx] = true;
                Integer paletteIndex = grid.biome().get(idx);
                Integer h = grid.height().get(idx);
                if (paletteIndex == null || h == null) {
                    continue;
                }
                BiomeColors colors = palette.get(paletteIndex);
                if (colors == null) {
                    continue;   // an id the live registry no longer knows — unmeasurable, not invented
                }
                known[idx] = true;
                terrainColor[idx] = colors.terrain();
                waterColor[idx] = colors.water();
                height[idx] = h;
                biomeId[idx] = paletteIndex;
                minHeight = Math.min(minHeight, h);
                maxHeight = Math.max(maxHeight, h);
            }
        }

        BufferedImage image = paintTerrain(side, step, half, radius, terrainColor, waterColor,
                height, biomeId, known, inDisc, seaLevel, minHeight, maxHeight);
        int markers = paintStructuresAndSpawn(image, server, def, seed, radius, side, step, half,
                base, LOWRES_STRUCTURE_GROUPS);

        writeImageAtomically(image, outputPath);
        long renderNanos = System.nanoTime() - renderStart;
        return new RenderResult(outputPath, side, step, 0L, renderNanos, grid.sampled(), markers);
    }

    /**
     * Samples its own grid via {@link SpikeSampler} at a side chosen from a
     * real timing of this dimension's own per-column cost. Never reads a
     * candidate file — highres wants finer detail than {@code
     * FactsEngine}'s measurement grid carries, so a fresh, denser sample is
     * the only way to get it, whether or not this seed has ever been banked.
     */
    private static RenderResult renderBySampling(MinecraftServer server, Identifier dimensionId,
                                                  DimensionConfig def, long seed,
                                                  Path outputPath) throws IOException {
        long renderStart = System.nanoTime();
        int radius = Math.max(1, def.getPlayerBorderRadius());

        SpikeSampler.Base base = SpikeSampler.base(server, dimensionId);
        if (!base.ok()) {
            throw new IOException("cannot render " + dimensionId + ": " + base.error());
        }
        SpikeSampler.Rig rig = SpikeSampler.forSeed(server, base, seed);

        long perColumnNanos = timePerColumn(rig, radius, seed);
        int side = chooseSide(perColumnNanos, Resolution.HIGHRES.budgetNanos, MIN_SIDE, MAX_SIDE);
        int step = Math.max(1, (radius * 2) / (side - 1));
        int half = side / 2;

        Integer seaLevel = rig.generator() instanceof NoiseChunkGenerator noiseGen
                ? noiseGen.getSettings().value().seaLevel() : null;
        Registry<Biome> biomeRegistry = server.getRegistryManager().get(RegistryKeys.BIOME);
        Map<String, BiomeColors> colorCache = new HashMap<>();
        Map<String, Integer> biomeIndex = new HashMap<>();

        int cells = side * side;
        int[] terrainColor = new int[cells];
        int[] waterColor = new int[cells];
        int[] height = new int[cells];
        // The biome IDENTITY, not its colour — two biomes can resolve to a
        // near-identical hue (the fog/sky fallback clusters hard in a
        // nether/end family), and a boundary line must still separate them.
        int[] biomeId = new int[cells];
        boolean[] known = new boolean[cells];
        boolean[] inDisc = new boolean[cells];
        Arrays.fill(biomeId, -1);

        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        int sampled = 0;
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                int dx = gridToWorldOffset(gx, step, half);
                int dz = gridToWorldOffset(gz, step, half);
                if ((long) dx * dx + (long) dz * dz > (long) radius * radius) {
                    continue;
                }
                int idx = gz * side + gx;
                inDisc[idx] = true;
                SpikeSampler.Sample s = SpikeSampler.sample(rig, dx, dz);
                sampled++;
                if (s.biome() == null || s.surfaceHeight() == null) {
                    continue;   // stays "known[idx] == false" — painted as unknown, never as air
                }
                BiomeColors colors = colorCache.computeIfAbsent(s.biome(),
                        id -> biomeColors(biomeRegistry, id));
                if (colors == null) {
                    continue;   // an id the live registry no longer knows — unmeasurable, not invented
                }
                known[idx] = true;
                terrainColor[idx] = colors.terrain();
                waterColor[idx] = colors.water();
                height[idx] = s.surfaceHeight();
                biomeId[idx] = biomeIndex.computeIfAbsent(s.biome(), id -> biomeIndex.size());
                minHeight = Math.min(minHeight, s.surfaceHeight());
                maxHeight = Math.max(maxHeight, s.surfaceHeight());
            }
        }

        BufferedImage image = paintTerrain(side, step, half, radius, terrainColor, waterColor,
                height, biomeId, known, inDisc, seaLevel, minHeight, maxHeight);
        int markers = paintStructuresAndSpawn(image, server, def, seed, radius, side, step, half,
                base, null);   // highres has room for every group

        writeImageAtomically(image, outputPath);
        long renderNanos = System.nanoTime() - renderStart;
        return new RenderResult(outputPath, side, step, perColumnNanos, renderNanos, sampled, markers);
    }

    /**
     * The terrain fill, biome-boundary tint and border ring — everything
     * that depends only on a biome/height grid, not on how that grid was
     * obtained. Shared by {@link #renderFromGrid} and {@link
     * #renderBySampling} so the two pixel sources produce the same picture
     * language.
     */
    private static BufferedImage paintTerrain(int side, int step, int half, int radius,
                                              int[] terrainColor, int[] waterColor, int[] height,
                                              int[] biomeId, boolean[] known, boolean[] inDisc,
                                              Integer seaLevel, int minHeight, int maxHeight) {
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                int idx = gz * side + gx;
                int color;
                if (!inDisc[idx]) {
                    color = VOID_COLOR;
                } else if (!known[idx]) {
                    color = UNKNOWN_COLOR;
                } else if (seaLevel != null && height[idx] <= seaLevel && minHeight <= maxHeight) {
                    color = waterColorAt(waterColor[idx], height[idx], seaLevel, minHeight);
                } else {
                    color = shade(terrainColor[idx], heightFactor(height[idx], minHeight, maxHeight));
                }
                if (known[idx] && bordersADifferentBiome(biomeId, known, side, gx, gz)) {
                    color = blend(color, BIOME_BOUNDARY_COLOR, BOUNDARY_STRENGTH);
                }
                image.setRGB(gx, gz, color);
            }
        }

        // The border ring is an annotation over the terrain, drawn as its own
        // pass so it reads as a clean circle rather than fighting the fill
        // loop's early-continue for out-of-disc cells.
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                int dx = gridToWorldOffset(gx, step, half);
                int dz = gridToWorldOffset(gz, step, half);
                double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (nearBorder(dist, radius, step)) {
                    image.setRGB(gx, gz, BORDER_COLOR);
                }
            }
        }
        return image;
    }

    /**
     * Structure markers and the spawn marker — the two overlays that need a
     * live server (structure placement, the dimension's declared spawn)
     * rather than anything in the grid.
     */
    private static int paintStructuresAndSpawn(BufferedImage image, MinecraftServer server,
                                               DimensionConfig def, long seed, int radius,
                                               int side, int step, int half, SpikeSampler.Base base,
                                               Set<String> allowedGroups) {
        // A structure marker is at most one grid cell wide until the image is
        // large enough to spare more: hundreds of sites on a small grid would
        // otherwise paint over most of the terrain a low-res render exists to
        // show, and a single pixel per site is still a real, findable mark.
        int structureMarkerRadius = Math.max(0, side / 400);
        List<long[]> hostilePositions = new ArrayList<>();
        List<long[]> structures = structurePositions(
                server, def, base, seed, radius, allowedGroups, hostilePositions);
        // Identity, not value, equality — every hostile position is the exact
        // same array instance structurePositions() also put in `structures`,
        // so reference-identity membership is correct, not merely convenient.
        Set<long[]> hostileSet = new HashSet<>(hostilePositions);
        int markers = 0;
        for (long[] pos : structures) {
            int gx = worldToGrid((int) pos[0], step, half);
            int gz = worldToGrid((int) pos[1], step, half);
            if (paintMarker(image, side, gx, gz, structureMarkerRadius,
                    hostileSet.contains(pos) ? HOSTILE_STRUCTURE_COLOR : STRUCTURE_COLOR)) {
                markers++;
            }
        }

        int[] spawn = def.getSpawn();
        if (spawn != null && spawn.length >= 3) {
            // One marker, always visible regardless of grid size — unlike a
            // structure site, there is only ever one, so it can afford to be
            // a few pixels wide even on a small image.
            int spawnMarkerRadius = Math.max(1, side / 150);
            int gx = worldToGrid(spawn[0], step, half);
            int gz = worldToGrid(spawn[2], step, half);
            paintMarker(image, side, gx, gz, spawnMarkerRadius, SPAWN_COLOR);
        }
        return markers;
    }

    /**
     * The dedicated server's own configured tick-watchdog timeout in
     * milliseconds ({@code server.properties}' {@code max-tick-time}, this
     * platform's {@code MAX_TICK_TIME} env var), or a non-positive number
     * when the watchdog is off — vanilla's own convention, since a
     * non-positive value disables the check. A non-dedicated server (never
     * this platform's deployment target) reports {@code -1}: there is no
     * watchdog to ask.
     */
    public static long watchdogTimeoutMillis(MinecraftServer server) {
        return server instanceof MinecraftDedicatedServer dedicated ? dedicated.getMaxTickTime() : -1;
    }

    // ------------------------------------------------------------ calibration

    /**
     * Measures this rig's own per-column cost by sampling for a bounded
     * WALL-CLOCK slice rather than a fixed sample count, so calibration
     * itself stays a small fraction of the budget whether a column is cheap
     * or expensive — {@code the_gauntlet} and a flat dimension both get a
     * fair, short calibration pass rather than one sized for the other.
     */
    static long timePerColumn(SpikeSampler.Rig rig, int radius, long seed) {
        long deadline = System.nanoTime() + CALIBRATION_BUDGET_NANOS;
        long start = System.nanoTime();
        int i = 0;
        while (i < MIN_CALIBRATION_SAMPLES || System.nanoTime() < deadline) {
            int[] p = SpikeSampler.probe(i, radius, seed ^ CALIBRATION_SALT);
            SpikeSampler.sample(rig, p[0], p[1]);
            i++;
        }
        return Math.max(1, (System.nanoTime() - start) / i);
    }

    /**
     * The square grid side whose in-disc sample count fits {@code budgetNanos}
     * at {@code perColumnNanos} per column, clamped to {@code [minSide,
     * maxSide]}. Pure: a JUnit test drives this with synthetic timings and no
     * Minecraft Bootstrap. The budget bounds the SAMPLING pass only — PNG
     * encoding is additional and, at these pixel counts, small next to it.
     */
    static int chooseSide(long perColumnNanos, long budgetNanos, int minSide, int maxSide) {
        long affordableColumns = Math.max(1, budgetNanos / Math.max(1, perColumnNanos));
        // Only pi/4 of a bounding square lies inside the disc the sampling loop keeps.
        int side = (int) Math.sqrt(affordableColumns / (Math.PI / 4.0));
        if (side % 2 == 0) {
            side++;   // odd, so the centre column (the origin) is a sampled cell
        }
        return Math.max(minSide, Math.min(maxSide, side));
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
        Integer grass = effects.getGrassColor().orElse(null);
        Integer foliage = effects.getFoliageColor().orElse(null);
        int terrain = terrainBaseColor(grass, foliage, effects.getFogColor(), effects.getSkyColor());
        return new BiomeColors(terrain, effects.getWaterColor());
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

    /** Where a height sits between this render's own min and max, as a shading multiplier. */
    static double heightFactor(int height, int minHeight, int maxHeight) {
        if (maxHeight <= minHeight) {
            return 1.0;
        }
        double t = (height - minHeight) / (double) (maxHeight - minHeight);
        return MIN_SHADE + (MAX_SHADE - MIN_SHADE) * t;
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

    /** Within one grid step of the border radius — a ring with constant pixel thickness. */
    static boolean nearBorder(double distanceFromCentre, int radius, int step) {
        return Math.abs(distanceFromCentre - radius) <= step;
    }

    /** True when any in-bounds, measured neighbour carries a different biome identity. */
    static boolean bordersADifferentBiome(int[] biomeId, boolean[] known, int side, int gx, int gz) {
        int here = biomeId[gz * side + gx];
        return differingNeighbour(biomeId, known, side, gx - 1, gz, here)
                || differingNeighbour(biomeId, known, side, gx + 1, gz, here)
                || differingNeighbour(biomeId, known, side, gx, gz - 1, here)
                || differingNeighbour(biomeId, known, side, gx, gz + 1, here);
    }

    private static boolean differingNeighbour(int[] biomeId, boolean[] known, int side,
                                              int gx, int gz, int here) {
        if (gx < 0 || gx >= side || gz < 0 || gz >= side) {
            return false;
        }
        int idx = gz * side + gx;
        return known[idx] && biomeId[idx] != here;
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
     * @param allowedGroups when non-null, only these groups are drawn — the
     *                      lowres restriction to the groups that decide
     *                      whether a world is worth visiting. Null marks
     *                      every active group, which highres has room for.
     */
    private static List<long[]> structurePositions(MinecraftServer server, DimensionConfig def,
                                                    SpikeSampler.Base base, long seed, int radius,
                                                    Set<String> allowedGroups, List<long[]> hostileOut) {
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(def);
        if (plan.isSuppressed()) {
            return List.of();
        }
        var setRegistry = server.getRegistryManager().get(RegistryKeys.STRUCTURE_SET);
        Set<String> exclude = new HashSet<>(NoisePoolBuilder.lowerSet(
                def.getStructures() == null ? null : def.getStructures().exclude));
        exclude.addAll(NoisePoolBuilder.lowerSet(
                MultiverseConfig.getInstance().getSuppressedStructureSets()));
        NoisePoolBuilder.Result pools = NoisePoolBuilder.build(
                def, setRegistry.getIndexedEntries(), base.generator().getBiomeSource(), plan, exclude);

        int radiusChunks = radius / 16;
        long dimensionSalt = DimensionStructures.saltOf(def.getName());
        List<long[]> positions = new ArrayList<>();
        for (var groupEntry : plan.groups().entrySet()) {
            String group = groupEntry.getKey();
            if (allowedGroups != null && !allowedGroups.contains(group)) {
                continue;
            }
            NoiseGroupPlan.Group settings = groupEntry.getValue();
            NoisePoolBuilder.Pool pool = pools.pools().get(group);
            if (pool == null || pool.entries().isEmpty()) {
                continue;
            }
            long noiseSeed = seed ^ dimensionSalt ^ DimensionStructures.saltOf(group);
            NoiseStructurePlacement placement = new NoiseStructurePlacement(
                    group, noiseSeed, settings.profile(), settings.exclusion(),
                    settings.radial(), radiusChunks, 0, 0, settings.clearSpawnChunks());
            boolean hostile = HOSTILE_GROUPS.contains(group);
            for (ChunkPos pos : placement.index().positions()) {
                long[] block = {pos.x * 16L + 8, pos.z * 16L + 8};
                positions.add(block);
                if (hostile) {
                    hostileOut.add(block);
                }
            }
        }
        return positions;
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
