package com.customdimensions.facts;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.Artefacts;
import com.customdimensions.command.SpikeSampler;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionStructures;
import com.customdimensions.dimension.FixedStructurePlacement;
import com.customdimensions.dimension.NoiseGroupPlan;
import com.customdimensions.dimension.NoisePoolBuilder;
import com.customdimensions.dimension.NoiseStructurePlacement;
import com.customdimensions.dimension.StructurePick;
import com.customdimensions.dimension.StructureWants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Measures one (dimension, seed) with no {@code ServerWorld}, using the mod's
 * own worldgen classes rather than a mirror of them.
 *
 * <p>Everything here is a measurement. Nothing here is a judgement — no
 * weights, no targets, no verdicts. The separation is what keeps "this seed is
 * poor", "this config is broken" and "this could not be measured" three
 * distinct answers instead of one number.
 *
 * <p>Sampling is a square grid clipped to the playable disc. The step is
 * derived from the radius so a small dimension is not measured more coarsely
 * than a large one in relative terms, and the grid resolution is recorded so a
 * consumer can tell a 400-sample fact from a 40-sample one.
 */
public final class FactsEngine {

    /**
     * Block spacing for the nine columns probed around spawn. One chunk, so
     * "local relief" is relief a standing player could see, not a hillside
     * measured across a fifth of the world.
     */
    private static final int SPAWN_PROBE_STEP = 16;

    /** Grid samples across the diameter. Odd, so spawn is a sample. */
    public static final int GRID = 41;

    /**
     * The patch around spawn the mosaic reading is taken over: odd, so spawn
     * is its centre cell, and 9 wide so it spans a walk rather than a glance.
     */
    static final int MOSAIC_SIDE = 9;

    /**
     * Blocks between mosaic samples. FIXED, and that is the whole point of
     * the fact: the playable grid's step is a function of the border, so the
     * same statistic taken there measures the ratio of sampling step to biome
     * size and ranks an 8192-block world above a 1024-block one for a reason
     * belonging to neither. 48 blocks puts the patch's edge at 192 from spawn
     * — a render distance, the ground a player actually sees on arrival.
     */
    static final int MOSAIC_STEP = 48;

    /**
     * Candidate spawn columns across the lattice, per side. Odd, so the
     * dimension's own spawn column is the centre one and the existing spawn
     * facts are that cell of the same pass rather than a second probe of it.
     */
    static final int SPAWN_LATTICE_SIDE = 5;

    /**
     * Blocks between candidate spawn columns.
     *
     * <p>The picker writes the position you were standing in as the
     * dimension's spawn, so the question a spawn-safety judgement can actually
     * answer is "does a safe column exist near here", not "is the origin one".
     * 128 blocks is far enough apart that two lattice columns are not the same
     * hillside and close enough that the whole 5x5 spans 512 blocks — a walk,
     * not an expedition.
     */
    static final int SPAWN_LATTICE_STEP = 128;

    /**
     * Local relief above which a column is a cliff rather than a slope. The
     * threshold the spawn-safety gate carried before it was graded.
     */
    static final double SPAWN_CLIFF_RELIEF = 32.0;

    /** Groups whose placements count as hostile for the challenge facts. */
    private static final java.util.Set<String> HOSTILE_GROUPS =
            java.util.Set.of("dungeons", "endgame");

    private FactsEngine() {
    }

    public static SeedFacts measure(MinecraftServer server, Identifier dimensionId, long seed) {
        DimensionConfig def = MultiverseConfig.getInstance().getDimension(dimensionId.getPath());
        if (def == null) {
            def = MultiverseConfig.getInstance().getBaseWorld(dimensionId.toString());
        }
        int radius = def != null ? def.getPlayerBorderRadius() : 8192;

        SpikeSampler.Base base = SpikeSampler.base(server, dimensionId);
        String fingerprint = def != null ? String.valueOf(def.getBiomePatchesFingerprint()) : "";
        if (!base.ok()) {
            String why = "the dimension's generator could not be built: " + base.error();
            return unmeasurable(dimensionId, seed, radius, fingerprint, why);
        }
        SpikeSampler.Rig rig = SpikeSampler.forSeed(server, base, seed);

        // One grid pass feeds spawn, biome and terrain facts. Sampling three
        // times over the same columns would be three chances to disagree with
        // itself.
        Grid grid = sampleGrid(rig, radius);

        SeedFacts.Column spawnAt = spawnColumn(def);
        SeedFacts.SpawnFacts spawn = spawnFacts(rig, spawnAt, radius);
        SeedFacts.TerrainFacts terrain = terrainFacts(grid, rig);
        MultiverseServer.LOGGER.debug(
                "facts {} seed={}: spawn.surfaceHeight={} terrain.relief={} grain={} min={} max={}",
                dimensionId, seed, spawn.surfaceHeight(), terrain.relief(), terrain.grain(),
                terrain.minHeight(), terrain.maxHeight());
        if (rig.hasCeiling()) {
            MultiverseServer.LOGGER.debug("facts {} seed={} ceiling-diagnostic: {}",
                    dimensionId, seed, ceilingDiagnostic(rig, spawnAt.x(), spawnAt.z()));
        }

        return new SeedFacts(
                Artefacts.stackVersion(),
                dimensionId.toString(), seed, Instant.now().toString(), fingerprint, radius,
                spawn,
                biomeFacts(grid, edgeDensity(mosaic(rig, spawnAt, radius), MOSAIC_SIDE)),
                terrain,
                structureFacts(server, def, base, seed, radius),
                Measured.of(persistedGrid(grid)));
    }

    /**
     * A cheap subset of {@link #measure}, for screening a large pool of seeds
     * before the full grid is worth paying for. Two facts, both obtainable
     * with no per-seed terrain router:
     *
     * <ul>
     *   <li>{@code structures} — the real {@link #structureFacts}, unchanged.
     *       It reads {@code base} alone, never a {@code NoiseConfig}: placement
     *       is {@code seed ^ salt} arithmetic through {@link
     *       com.customdimensions.dimension.NoiseStructurePlacement} and
     *       vanilla's own random-spread grid, so this is exact, not an
     *       estimate — a seed rejected here is rejected for the same reason
     *       {@link #measure} would reject it, from the same fields.</li>
     *   <li>{@code spawn.biome} and {@code biomes.edgeDensityNearSpawn} — from
     *       a CLIMATE-ONLY rig ({@link SpikeSampler#forSeedClimate}), which
     *       skips the terrain, aquifer and ore density functions {@link
     *       SpikeSampler#climateOnly} exists to avoid paying for. Read at the
     *       same spawn column and the same {@link #MOSAIC_SIDE} patch {@link
     *       #measure} uses, through the same {@link #mosaic}/{@link
     *       #edgeDensity} helpers, so a later full measurement of the same
     *       seed reads identically here.</li>
     * </ul>
     *
     * <p>Everything else — height, relief, ground, the full biome mosaic
     * share, every terrain fact — reads {@link Measured#absent} with a
     * reason. That is deliberate, not a shortcut taken and hidden: {@link
     * com.customdimensions.score.Scorer} already excludes an absent fact's
     * criterion from both the achieved total and the ceiling, so scoring
     * this partial record with the dimension's REAL criteria gives an honest
     * coarse rank over exactly what was cheap to measure — never a guess
     * standing in for the rest.
     *
     * @param base the seed-independent half, built once per roll and handed
     *             in rather than rebuilt per seed — see {@link
     *             SpikeSampler.Base}'s own javadoc for why.
     */
    public static SeedFacts measureCheap(MinecraftServer server, Identifier dimensionId,
                                         DimensionConfig def, SpikeSampler.Base base, long seed) {
        int radius = def != null ? def.getPlayerBorderRadius() : 8192;
        String fingerprint = def != null ? String.valueOf(def.getBiomePatchesFingerprint()) : "";
        String why = "tier-1 screen: only structures and near-spawn biome were measured for this seed";

        SeedFacts.StructureFacts structures = def == null
                ? absentStructures("no dimension config, so its structure plan cannot be resolved")
                : structureFacts(server, def, base, seed, radius);

        SeedFacts.SpawnFacts spawn;
        SeedFacts.BiomeFacts biomes;
        if (!base.ok()) {
            spawn = new SeedFacts.SpawnFacts(Measured.absent(why), Measured.absent(why),
                    Measured.absent(why), Measured.absent(why), Measured.absent(why),
                    Measured.absent(why), Measured.absent(why));
            biomes = new SeedFacts.BiomeFacts(Measured.absent(why), Measured.absent(why),
                    Measured.absent(why), Measured.absent(why));
        } else {
            SpikeSampler.Rig rig = SpikeSampler.forSeedClimate(server, base, seed);
            SeedFacts.Column at = spawnColumn(def);
            String spawnBiome = SpikeSampler.spawnBiome(rig, at.x(), at.z());
            Measured<String> biome = spawnBiome == null
                    ? Measured.absent("the biome source answered nothing at spawn")
                    : Measured.of(spawnBiome);
            spawn = new SeedFacts.SpawnFacts(Measured.of(at), biome,
                    Measured.absent(why), Measured.absent(why), Measured.absent(why),
                    Measured.absent(why), Measured.absent(why));
            biomes = new SeedFacts.BiomeFacts(Measured.absent(why), Measured.absent(why),
                    Measured.absent(why), edgeDensity(mosaic(rig, at, radius), MOSAIC_SIDE));
        }

        return new SeedFacts(Artefacts.stackVersion(), dimensionId.toString(), seed,
                Instant.now().toString(), fingerprint, radius, spawn, biomes,
                new SeedFacts.TerrainFacts(Measured.absent(why), Measured.absent(why),
                        Measured.absent(why), Measured.absent(why), Measured.absent(why),
                        Measured.absent(why)),
                structures, Measured.absent(why));
    }

    /**
     * The biomes on a fixed-step patch centred on spawn, row-major over
     * {@link #MOSAIC_SIDE}, with nulls where the biome source answered
     * nothing.
     *
     * <p>The step shrinks only when {@link #MOSAIC_STEP} would push the patch
     * past the playable border: sampling ground a player cannot reach would
     * describe somewhere else. Costs {@code MOSAIC_SIDE^2} biome lookups —
     * the cheap half of a sample, and 81 of them against the grid pass's
     * ~1300 columns.
     */
    private static String[] mosaic(SpikeSampler.Rig rig, SeedFacts.Column at, int radius) {
        int half = MOSAIC_SIDE / 2;
        int step = Math.max(1, Math.min(MOSAIC_STEP, radius / half));
        String[] out = new String[MOSAIC_SIDE * MOSAIC_SIDE];
        for (int gz = 0; gz < MOSAIC_SIDE; gz++) {
            for (int gx = 0; gx < MOSAIC_SIDE; gx++) {
                out[gz * MOSAIC_SIDE + gx] = SpikeSampler.spawnBiome(rig,
                        at.x() + (gx - half) * step, at.z() + (gz - half) * step);
            }
        }
        return out;
    }

    /**
     * The internal per-run {@link Grid} as the record a candidate file
     * carries: biome ids collapsed to indices into {@code biomeIds}. A null
     * cell in {@code biome}/{@code height} is unmeasured — outside the
     * playable disc, or sampled without an answer — the same distinction
     * {@link #sampleGrid} already carries in its own null cells.
     */
    private static SeedFacts.Grid persistedGrid(Grid grid) {
        List<String> biomeIds = new ArrayList<>();
        Map<String, Integer> biomeIndex = new LinkedHashMap<>();
        List<Integer> biome = new ArrayList<>(grid.biome().length);
        List<Integer> height = new ArrayList<>(grid.height().length);
        int heightMeasured = 0;

        for (int i = 0; i < grid.biome().length; i++) {
            String b = grid.biome()[i];
            if (b == null) {
                biome.add(null);
            } else {
                Integer idx = biomeIndex.get(b);
                if (idx == null) {
                    idx = biomeIds.size();
                    biomeIds.add(b);
                    biomeIndex.put(b, idx);
                }
                biome.add(idx);
            }
            height.add(grid.height()[i]);
            if (grid.height()[i] != null) {
                heightMeasured++;
            }
        }

        return new SeedFacts.Grid(grid.side(), biomeIds, biome, height, grid.sampled(), heightMeasured);
    }

    // ------------------------------------------------------------------ grid

    /**
     * One pass over the playable disc. Nulls mark cells that answered
     * nothing — outside the disc (never attempted) and sampled-but-absent
     * are not distinguished here; a consumer of the persisted record cares
     * only that the cell has no value. {@code submerged} carries a verdict for
     * EVERY sampled column, groundless or not: whether that column's aquifer
     * actually left fluid there, probed once at sample time rather than
     * inferred from the generator's default fluid or from the sea line — see
     * {@link #sampleGrid}. Package-private so the pure
     * grid computations below can be pinned against hand-built layouts — the
     * alternative is verifying relief, grain, shares, edge density and water
     * only through a live parity run, which tells you the whole pipeline
     * agrees with itself and nothing about whether the arithmetic is what
     * was intended.
     */
    record Grid(String[] biome, Integer[] height, boolean[] submerged, int side, int step, int sampled) {
    }

    /**
     * One column's wetness verdict, and whether it had to be inferred.
     *
     * @param fellBack the probe could not read the column, so the sea line
     *                 answered instead — a degraded reading, not a failed one
     */
    public record Wetness(boolean submerged, boolean fellBack) {
    }

    /**
     * Whether a column counts as submerged: the ONE definition {@link
     * #sampleGrid} banks and {@code render-check} reports.
     *
     * <p>It lives in one place because two copies of it are two rules, and
     * this pair has already drifted once — the diagnostic went on reporting
     * {@code height <= seaLevel} for grounded columns after the engine stopped
     * using it, so a fix that had landed correctly read as having done nothing
     * at all.
     *
     * <p>Nothing here is inferred from height alone. {@code aquifers_enabled}
     * makes the fluid level noise-driven per region, so neither "no floor" nor
     * "floor under the sea line" settles it; both cases ask the generated
     * column. The sea line survives only as the fallback for a column that
     * could not be read, because the old rule is wrong on roughly a tenth of
     * columns while assuming dry is wrong on every column that held water.
     */
    public static Wetness submergedAt(SpikeSampler.Rig rig, boolean floodsVoid, Integer seaLevel,
                               Integer height, int floorY, int x, int z) {
        if (!floodsVoid || seaLevel == null) {
            return new Wetness(false, false);
        }
        if (height == null || height <= floorY) {
            return new Wetness(SpikeSampler.groundlessHoldsFluid(rig, x, z), false);
        }
        Boolean probed = SpikeSampler.surfaceHoldsFluid(rig, x, z, height);
        return probed != null
                ? new Wetness(probed, false)
                : new Wetness(height <= seaLevel, true);
    }

    private static Grid sampleGrid(SpikeSampler.Rig rig, int radius) {
        int side = GRID;
        int step = Math.max(1, (radius * 2) / (side - 1));
        String[] biome = new String[side * side];
        Integer[] height = new Integer[side * side];
        boolean[] submerged = new boolean[side * side];
        // The cheap gate: a generator whose default fluid is air (the End)
        // never floods any column, so none of it is worth the cost below.
        // Computed once per grid, not once per column.
        boolean floods = SpikeSampler.floodsVoid(rig.generator());
        Integer seaLevel = rig.generator() instanceof NoiseChunkGenerator noiseGen
                ? noiseGen.getSettings().value().seaLevel() : null;
        int floorY = rig.heightLimit().getBottomY();
        int sampled = 0;
        int fellBack = 0;
        int half = side / 2;
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                int dx = (gx - half) * step;
                int dz = (gz - half) * step;
                if ((long) dx * dx + (long) dz * dz > (long) radius * radius) {
                    continue;   // outside the playable disc — never attempted
                }
                int i = gz * side + gx;
                SpikeSampler.Sample s = SpikeSampler.sample(rig, dx, dz);
                biome[i] = s.biome();
                height[i] = s.surfaceHeight();
                Wetness wetness = submergedAt(rig, floods, seaLevel, height[i], floorY, dx, dz);
                submerged[i] = wetness.submerged();
                if (wetness.fellBack()) {
                    fellBack++;
                }
                sampled++;
            }
        }
        if (fellBack > 0) {
            // The probe fails on properties of the RIG, not of one column — a
            // broken generator, a null NoiseConfig, a throwing getColumnSample —
            // so this is all-or-nothing in practice, and a silent per-column
            // default would hide a whole dimension measured by the old rule.
            MultiverseServer.LOGGER.warn(
                    "facts: {} of {} grounded columns could not be probed for fluid and fell back "
                    + "to height <= seaLevel, which is wrong on roughly a tenth of columns. The "
                    + "water fraction for this seed is an estimate, not a reading.",
                    fellBack, sampled);
        }
        return new Grid(biome, height, submerged, side, step, sampled);
    }

    // ----------------------------------------------------------------- spawn

    /**
     * Where a player arrives, as {@code {x, z}}.
     *
     * <p>Not the origin: {@code ExitTarget} resolves an arrival to the
     * dimension's declared {@code spawn}, so that is the only column whose
     * biome, height and relief describe what a player meets.
     */
    private static SeedFacts.Column spawnColumn(DimensionConfig def) {
        int[] spawn = def != null ? def.getSpawn() : null;
        return spawn != null && spawn.length >= 3
                ? new SeedFacts.Column(spawn[0], spawn[2], true)
                : new SeedFacts.Column(0, 0, false);
    }

    private static SeedFacts.SpawnFacts spawnFacts(
            SpikeSampler.Rig rig, SeedFacts.Column at0, int radius) {
        SpikeSampler.Sample at = SpikeSampler.sample(rig, at0.x(), at0.z());
        Measured<Integer> h = at.surfaceHeight() == null
                ? Measured.absent(at.heightAbsent() != null ? at.heightAbsent()
                        : "the generator answered no surface height at spawn")
                : Measured.of(at.surfaceHeight());

        Neighbourhood here = neighbourhood(rig, at0.x(), at0.z());
        Measured<Double> relief = here.heights().size() < 2
                ? Measured.absent("fewer than two columns near spawn answered a height")
                : Measured.of(here.relief());
        Measured<List<SeedFacts.GroundKind>> nearbyGround = here.ground().isEmpty()
                ? Measured.absent("no column near spawn answered both a height and a ground read")
                : Measured.of(here.ground());

        Measured<Boolean> aboveSea;
        if (at.surfaceHeight() == null) {
            aboveSea = Measured.absent("no surface height at spawn to compare with sea level");
        } else if (!(rig.generator() instanceof NoiseChunkGenerator noiseGen)) {
            aboveSea = Measured.absent(
                    "a flat generator has no sea level to compare against");
        } else {
            aboveSea = Measured.of(at.surfaceHeight() > noiseGen.getSettings().value().seaLevel());
        }

        return new SeedFacts.SpawnFacts(
                Measured.of(at0),
                at.biome() == null
                        ? Measured.absent(at.biomeAbsent() != null ? at.biomeAbsent()
                                : "the biome source answered nothing at spawn")
                        : Measured.of(at.biome()),
                h, relief, aboveSea, nearbyGround,
                safeColumnFraction(rig, at0, radius, here));
    }

    /**
     * What the nine columns around one position answered.
     *
     * <p>Sampled at {@link #SPAWN_PROBE_STEP} rather than reused from the
     * coarse grid, whose neighbours are {@code (radius * 2) / (GRID - 1)}
     * apart — 204 blocks at a 4096 radius, where a 34-block rise is a gentle
     * slope, not a cliff.
     */
    private record Neighbourhood(List<Integer> heights, List<SeedFacts.GroundKind> ground) {

        double relief() {
            return java.util.Collections.max(heights) - java.util.Collections.min(heights);
        }

        /**
         * Whether a player put here could start playing: no cliff, nothing
         * that kills on contact, and somewhere to stand.
         *
         * <p>The three conditions the two spawn-safety gates carried between
         * them, in one place now that one criterion asks all of it. A column
         * that answered too little to judge is not safe — an unmeasured
         * column is not evidence of safety.
         */
        boolean safe() {
            if (heights().size() < 2 || ground().isEmpty()) {
                return false;
            }
            if (relief() > SPAWN_CLIFF_RELIEF) {
                return false;
            }
            long solid = 0;
            for (SeedFacts.GroundKind g : ground()) {
                if (g == SeedFacts.GroundKind.HAZARDOUS_FLUID) {
                    return false;
                }
                if (g == SeedFacts.GroundKind.SOLID) {
                    solid++;
                }
            }
            return solid * 2 >= ground().size();
        }
    }

    private static Neighbourhood neighbourhood(SpikeSampler.Rig rig, int cx, int cz) {
        List<Integer> heights = new ArrayList<>();
        List<SeedFacts.GroundKind> ground = new ArrayList<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = cx + dx * SPAWN_PROBE_STEP;
                int z = cz + dz * SPAWN_PROBE_STEP;
                Integer v = SpikeSampler.sample(rig, x, z).surfaceHeight();
                if (v != null) {
                    heights.add(v);
                    try {
                        ground.add(classifyGround(rig, x, z, v));
                    } catch (Exception e) {
                        // Same column, a different question: a height answer
                        // does not guarantee a block-state read succeeds too.
                        // Dropped like a null height would be — the count
                        // this column contributes to just shrinks by one.
                    }
                }
            }
        }
        return new Neighbourhood(heights, ground);
    }

    /**
     * How much of the lattice around spawn a player could be put down in.
     *
     * <p>The spawn column is not fixed — picking a candidate writes the
     * position you were standing in as the dimension's spawn — so whether the
     * ORIGIN happens to be safe rejects worlds that could simply be given a
     * safe spawn. This asks the question that survives that: is there
     * anywhere here to arrive.
     *
     * <p>Columns outside the playable border are not candidates and are never
     * probed, so the fraction is over the lattice cells that are actually
     * inside the world.
     */
    private static Measured<Double> safeColumnFraction(
            SpikeSampler.Rig rig, SeedFacts.Column at0, int radius, Neighbourhood centre) {
        int half = SPAWN_LATTICE_SIDE / 2;
        int inside = 0;
        int safe = 0;
        for (int gz = -half; gz <= half; gz++) {
            for (int gx = -half; gx <= half; gx++) {
                int x = at0.x() + gx * SPAWN_LATTICE_STEP;
                int z = at0.z() + gz * SPAWN_LATTICE_STEP;
                if ((long) x * x + (long) z * z > (long) radius * radius) {
                    continue;
                }
                inside++;
                Neighbourhood n = gx == 0 && gz == 0 ? centre : neighbourhood(rig, x, z);
                if (n.safe()) {
                    safe++;
                }
            }
        }
        return inside == 0
                ? Measured.absent("no candidate spawn column sits inside the playable border")
                : Measured.of(safe / (double) inside);
    }

    /**
     * What a player standing at this column's floor would be standing in.
     * Checked at both {@code surfaceHeight} (the player's feet) and
     * {@code surfaceHeight - 1} (the ground under them): an ocean column
     * reports its water at the feet position and rock or nothing below it,
     * while a fluid pooled on solid ground in a ceilinged dimension reports
     * the reverse — {@code ColumnScan}'s floor is the first OPAQUE block
     * walking down, and lava is not opaque, so a lava-floored pocket's
     * reported floor sits at the top of the lava, not beneath it.
     */
    private static SeedFacts.GroundKind classifyGround(
            SpikeSampler.Rig rig, int x, int z, int surfaceHeight) {
        VerticalBlockSample column =
                rig.generator().getColumnSample(x, z, rig.heightLimit(), rig.noiseConfig());
        int bottom = rig.heightLimit().getBottomY();
        int top = rig.heightLimit().getTopY() - 1;
        if (isHazard(column, surfaceHeight, bottom, top)
                || isHazard(column, surfaceHeight - 1, bottom, top)) {
            return SeedFacts.GroundKind.HAZARDOUS_FLUID;
        }
        if (isWater(column, surfaceHeight, bottom, top)
                || isWater(column, surfaceHeight - 1, bottom, top)) {
            return SeedFacts.GroundKind.OPEN_WATER;
        }
        return SeedFacts.GroundKind.SOLID;
    }

    private static boolean isHazard(VerticalBlockSample column, int y, int bottom, int top) {
        if (y < bottom || y > top) {
            return false;
        }
        BlockState state = column.getState(y);
        return state.isOf(Blocks.FIRE) || state.getFluidState().isIn(FluidTags.LAVA);
    }

    private static boolean isWater(VerticalBlockSample column, int y, int bottom, int top) {
        if (y < bottom || y > top) {
            return false;
        }
        return column.getState(y).getFluidState().isIn(FluidTags.WATER);
    }

    /** Dumps the spawn column's opaque/open runs top to bottom, DEBUG-gated. */
    private static String ceilingDiagnostic(SpikeSampler.Rig rig, int spawnX, int spawnZ) {
        int top = rig.heightLimit().getTopY() - 1;
        int bottom = rig.heightLimit().getBottomY();
        var column = rig.generator().getColumnSample(spawnX, spawnZ, rig.heightLimit(), rig.noiseConfig());

        StringBuilder runs = new StringBuilder();
        Boolean runOpaque = null;
        int runStart = top;
        for (int y = top; y >= bottom; y--) {
            boolean opaque = column.getState(y).isOpaque();
            if (runOpaque == null) {
                runOpaque = opaque;
                runStart = y;
            } else if (opaque != runOpaque) {
                runs.append(runOpaque ? "solid" : "open").append('[').append(y + 1)
                        .append("..").append(runStart).append("] ");
                runOpaque = opaque;
                runStart = y;
            }
        }
        runs.append(runOpaque != null && runOpaque ? "solid" : "open")
                .append('[').append(bottom).append("..").append(runStart).append(']');
        return "spawn column " + runs;
    }

    // ---------------------------------------------------------------- biomes

    /**
     * @param edges the mosaic reading from the fixed-step patch around spawn.
     *              The shares, count and headline come from the playable grid
     *              — they are statements about the whole world — while the
     *              mosaic reading is a statement about one place at one
     *              scale, so it is measured elsewhere and handed in.
     */
    static SeedFacts.BiomeFacts biomeFacts(Grid grid, Measured<Double> edges) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (String b : grid.biome()) {
            if (b != null) {
                counts.merge(b, 1, Integer::sum);
                total++;
            }
        }
        if (total == 0) {
            String why = "no column in the playable disc answered a biome";
            return new SeedFacts.BiomeFacts(Measured.absent(why), Measured.absent(why),
                    Measured.absent(why), edges);
        }
        Map<String, Double> shares = new TreeMap<>();
        double max = 0.0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            double share = e.getValue() / (double) total;
            shares.put(e.getKey(), share);
            max = Math.max(max, share);
        }
        return new SeedFacts.BiomeFacts(
                Measured.of(shares),
                Measured.of(counts.size()),
                Measured.of(max),
                edges);
    }

    /**
     * Adjacent sample pairs whose biomes differ, over a square layout.
     *
     * <p>The fact that separates a mosaic from two hemispheres — a biome
     * COUNT cannot, because both give the same count. Absent rather than
     * zero when no pair exists at all: zero is a measurement saying the place
     * is uniform, which is a different claim from having nothing to compare.
     */
    static Measured<Double> edgeDensity(String[] cells, int side) {
        int pairs = 0;
        int edges = 0;
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                String here = cells[gz * side + gx];
                if (here == null) {
                    continue;
                }
                if (gx + 1 < side) {
                    String east = cells[gz * side + gx + 1];
                    if (east != null) {
                        pairs++;
                        if (!here.equals(east)) {
                            edges++;
                        }
                    }
                }
                if (gz + 1 < side) {
                    String south = cells[(gz + 1) * side + gx];
                    if (south != null) {
                        pairs++;
                        if (!here.equals(south)) {
                            edges++;
                        }
                    }
                }
            }
        }
        return pairs == 0
                ? Measured.absent("no two adjacent columns near spawn both answered a biome")
                : Measured.of(edges / (double) pairs);
    }

    // --------------------------------------------------------------- terrain

    private static SeedFacts.TerrainFacts terrainFacts(Grid grid, SpikeSampler.Rig rig) {
        Integer sea = null;
        if (rig.generator() instanceof NoiseChunkGenerator noiseGen) {
            sea = noiseGen.getSettings().value().seaLevel();
        }
        // Whether a column with no floor is OCEAN or open sky. The End's
        // default fluid is air, so its void is empty however far below sea
        // level it sits; an overworld-shaped preset fills the same void with
        // water instead — but only where its aquifer actually left fluid, not
        // unconditionally, which is why sampleGrid already probed each
        // groundless column rather than this reading alone deciding the
        // answer. The same gate {@link Grid#submerged} was populated under.
        boolean floods = SpikeSampler.floodsVoid(rig.generator());
        return terrainFacts(grid, sea, floods, rig.heightLimit().getBottomY());
    }

    /**
     * @param seaLevel the generator's sea level, or null when it has none — a
     *                 flat generator's water fraction is absent, not zero.
     * @param floodsVoid whether this generator's default fluid is a fluid at
     *                 all — the cheap gate for "could a groundless column
     *                 hold fluid", never a promise that every one does.
     * @param floorY   the world floor. A column answering exactly this has no
     *                 ground: vanilla's {@code NoiseChunkGenerator.getHeight}
     *                 is {@code sampleHeightmap(…).orElse(getBottomY())}
     *                 (read off the 1.21.1 bytecode, not inferred), so an
     *                 empty column answers the floor rather than nothing. A
     *                 real surface sitting exactly on the floor is
     *                 indistinguishable from an empty column and counted as
     *                 void — a one-block shelf on bedrock is not ground
     *                 anybody stands on, so the two readings mean the same
     *                 thing here.
     */
    /**
     * How much of the playable disc is under the sea.
     *
     * <p>Over every SAMPLED column, not just the ones with ground — a column
     * with no floor can still be submerged, and a world that is mostly void
     * has most of its wetness there. {@code the_abyssal_shrine} reads 4.5%
     * wet over its land and 30% over its disc; only the second is an answer
     * to what {@code seedRoll.water} asked.
     *
     * <p><b>No column is unconditionally wet, whatever its height.</b>
     * {@code aquifers_enabled} makes the fluid level noise-driven per region,
     * so neither "no floor" nor "floor under the sea line" settles it.
     * Measured on {@code the_catalyst_maw}: 348 of 893 groundless columns held
     * no fluid at all, and at sea level 63, 116 grounded columns sat dry with
     * a floor below it while 11 sat wet with a floor above it. {@code wet} is
     * the count {@link #sampleGrid} probed per column; this only sums it,
     * gated by {@code floodsVoid} so a generator whose default fluid is air
     * (the End) never counts one regardless of a stray probe result.
     */
    private static Measured<Double> waterFraction(Grid grid, Integer seaLevel, boolean floodsVoid,
                                                  long wet) {
        if (seaLevel == null) {
            return Measured.absent(
                    "a flat generator has no sea level, so a water fraction has no meaning");
        }
        if (grid.sampled() <= 0) {
            return Measured.absent("the grid pass attempted no column inside the playable disc");
        }
        return Measured.of((floodsVoid ? wet : 0) / (double) grid.sampled());
    }

    static SeedFacts.TerrainFacts terrainFacts(Grid grid, Integer seaLevel,
                                              boolean floodsVoid, int floorY) {
        // Void columns are not terrain, and every fact below but
        // groundFraction is about terrain. Treating a floor reading as a
        // surface height reports an island world's relief as flat (the
        // interquartile range of a mostly-void grid is zero) and a void's
        // water fraction as total (every column sits below sea level).
        Integer[] ground = new Integer[grid.height().length];
        int withGround = 0;
        long wet = 0;
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < ground.length; i++) {
            Integer h = grid.height()[i];
            if (h != null && h > floorY) {
                ground[i] = h;
                heights.add(h);
                withGround++;
            }
            // Set only inside the disc and only by a probe, so this counts
            // attempted columns whether or not they carry ground.
            if (grid.submerged()[i]) {
                wet++;
            }
        }
        Measured<Double> groundFraction = grid.sampled() <= 0
                ? Measured.absent("the grid pass attempted no column inside the playable disc")
                : Measured.of(withGround / (double) grid.sampled());

        if (heights.size() < 2) {
            String why = withGround == 0
                    ? "no column in the playable disc carries ground, so there is no "
                            + "terrain to measure"
                    : "fewer than two columns in the playable disc carry ground";
            return new SeedFacts.TerrainFacts(groundFraction,
                    Measured.absent(why), Measured.absent(why),
                    waterFraction(grid, seaLevel, floodsVoid, wet),
                    Measured.absent(why), Measured.absent(why));
        }
        int min = java.util.Collections.min(heights);
        int max = java.util.Collections.max(heights);

        // Relief: interquartile range, not max - min. In a ceilinged
        // dimension max sits within a couple of blocks of the roof in
        // nearly every seed — some column among a thousand-plus samples
        // finds a shallow near-ceiling pocket almost by construction — so
        // max - min mostly tracks the depth of the single deepest hole
        // (min), inflated by a max that is not describing the terrain at
        // all. The IQR describes the middle half of the column, unmoved by
        // either saturating extreme.
        List<Integer> sorted = new ArrayList<>(heights);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        double relief = sorted.get((3 * (n - 1)) / 4) - sorted.get((n - 1) / 4);

        // Grain: mean absolute step between adjacent samples. Relief says how
        // tall the world is, grain says how choppy — a plateau and a spike
        // field can share a relief and read nothing alike.
        long grainSum = 0;
        int grainPairs = 0;
        int side = grid.side();
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                Integer here = ground[gz * side + gx];
                if (here == null) {
                    continue;
                }
                if (gx + 1 < side) {
                    Integer east = ground[gz * side + gx + 1];
                    if (east != null) {
                        grainSum += Math.abs(here - east);
                        grainPairs++;
                    }
                }
                if (gz + 1 < side) {
                    Integer south = ground[(gz + 1) * side + gx];
                    if (south != null) {
                        grainSum += Math.abs(here - south);
                        grainPairs++;
                    }
                }
            }
        }

        Measured<Double> water = waterFraction(grid, seaLevel, floodsVoid, wet);

        return new SeedFacts.TerrainFacts(
                groundFraction,
                Measured.of(relief),
                grainPairs == 0
                        ? Measured.absent("no two adjacent columns both carry ground")
                        : Measured.of(grainSum / (double) grainPairs),
                water,
                Measured.of(min),
                Measured.of(max));
    }

    // ------------------------------------------------------------ structures

    /**
     * The full noise census for an arbitrary seed, built from the mod's own
     * pool builder, noise field and pick — the same three classes generation
     * runs, so this is not a model of the placement, it is the placement.
     */
    private static SeedFacts.StructureFacts structureFacts(
            MinecraftServer server, DimensionConfig def, SpikeSampler.Base base,
            long seed, int radius) {
        if (def == null) {
            String why = "no dimension config, so its structure plan cannot be resolved";
            return absentStructures(why);
        }
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(def);
        if (plan.isSuppressed()) {
            String why = "this dimension places no noise-managed structures ("
                    + plan.reason() + ")";
            return absentStructures(why);
        }

        // Vanilla's own StructurePlacementCalculator.create drops a SET whose
        // structures' valid biomes miss the biome source, BEFORE the mod's pool
        // builder ever sees it — so a live world's pool is built from a
        // prefiltered list, not the full registry. Skipping that step produces
        // a pool that is a strict superset of the live one: identical positions
        // (the field does not depend on the pool) but a different weighted pick,
        // since the extra structures compete for probability mass a live
        // world's pick would never have given them. The same rule is applied
        // here, then the wanted sets are re-admitted exactly as
        // DimensionStructures re-admits them.
        var setRegistry = server.getRegistryManager().get(RegistryKeys.STRUCTURE_SET);
        java.util.Set<Identifier> dimensionBiomes =
                NoisePoolBuilder.biomeIds(base.generator().getBiomeSource());
        java.util.Set<String> wanted = NoisePoolBuilder.wantedStructureIds(def);
        List<RegistryEntry<StructureSet>> sets = new ArrayList<>();
        for (var e : setRegistry.getIndexedEntries()) {
            if (NoisePoolBuilder.survivesVanillaPrefilter(e, dimensionBiomes, wanted)) {
                sets.add(e);
            }
        }
        java.util.Set<String> exclude = new java.util.HashSet<>(NoisePoolBuilder.lowerSet(
                def.getStructures() == null ? null : def.getStructures().exclude));
        exclude.addAll(NoisePoolBuilder.lowerSet(
                MultiverseConfig.getInstance().getSuppressedStructureSets()));

        NoisePoolBuilder.Result pools = NoisePoolBuilder.build(
                def, sets, base.generator().getBiomeSource(), plan, exclude, null, wanted);

        // Independent of the noise pools above: a set NoisePoolBuilder does not
        // absorb still generates on its own grid, and its grid is computable
        // from the same registry entries — see passThroughFacts.
        PassThrough passThrough = passThroughFacts(sets, def, exclude, seed, radius);
        Measured<Map<String, Integer>> passThroughByStructure = Measured.of(passThrough.byStructure());
        Measured<Map<String, Double>> passThroughNearestByStructure = Measured.of(passThrough.nearest());
        Measured<List<String>> passThroughUnmodelledSets = Measured.of(passThrough.unmodelledSets());

        int radiusChunks = radius / 16;
        long dimensionSalt = DimensionStructures.saltOf(def.getName());

        Map<String, Integer> poolWeights = new TreeMap<>();
        Map<String, Integer> byGroup = new TreeMap<>();
        Map<String, Integer> byStructure = new TreeMap<>();
        Map<String, Double> nearest = new TreeMap<>();
        Map<String, List<long[]>> positionsByGroup = new TreeMap<>();
        List<long[]> allPositions = new ArrayList<>();
        List<long[]> hostilePositions = new ArrayList<>();
        int total = 0;

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

            List<StructurePick.PoolEntry> pickPool = new ArrayList<>();
            for (var weighted : pool.entries()) {
                weighted.structure().getKey().ifPresent(k -> pickPool.add(
                        new StructurePick.PoolEntry(k.getValue().toString(), weighted.weight())));
            }
            for (StructurePick.PoolEntry pe : pickPool) {
                poolWeights.merge(pe.structureId(), pe.weight(), Integer::sum);
            }
            List<StructurePick.PoolEntry> sorted = StructurePick.sortedPool(pickPool);

            int count = 0;
            List<long[]> groupPositions = new ArrayList<>();
            positionsByGroup.put(group, groupPositions);
            for (ChunkPos pos : placement.index().positions()) {
                String assigned = StructurePick.assignedStructure(
                        noiseSeed, pos.x, pos.z, sorted);
                count++;
                total++;
                groupPositions.add(new long[] {pos.x, pos.z});
                allPositions.add(new long[] {pos.x, pos.z});
                if (HOSTILE_GROUPS.contains(group)) {
                    hostilePositions.add(new long[] {pos.x, pos.z});
                }
                if (assigned != null) {
                    byStructure.merge(assigned, 1, Integer::sum);
                    double blocks = Math.hypot(pos.x * 16.0, pos.z * 16.0);
                    nearest.merge(assigned, blocks, Math::min);
                }
            }
            byGroup.put(group, count);
        }

        Measured<Map<String, List<String>>> tagMembers = tagMembers(server, def);

        if (total == 0) {
            String why = "every enabled group produced an empty pool or an empty field";
            return new SeedFacts.StructureFacts(
                    Measured.of(poolWeights), Measured.of(byGroup), Measured.of(byStructure),
                    Measured.of(nearest), Measured.absent(why),
                    Measured.absent(why), Measured.absent(why), Measured.of(0), tagMembers,
                    passThroughByStructure, passThroughNearestByStructure, passThroughUnmodelledSets);
        }

        return new SeedFacts.StructureFacts(
                Measured.of(poolWeights),
                Measured.of(byGroup),
                Measured.of(byStructure),
                Measured.of(nearest),
                clusteringByGroup(positionsByGroup, radiusChunks),
                clustering(allPositions, radiusChunks),
                hostilePositions.isEmpty()
                        ? Measured.absent("this dimension places no dungeons or endgame structures")
                        : Measured.of(nearestDistanceBlocks(hostilePositions)),
                Measured.of(total),
                tagMembers,
                passThroughByStructure, passThroughNearestByStructure, passThroughUnmodelledSets);
    }

    /**
     * Grid positions for structure sets {@link NoisePoolBuilder} does not
     * absorb — {@code betterfortresses:fortress} and its siblings on this
     * stack. These still generate on vanilla's random-spread grid; nothing
     * here changes that, it only reads it.
     *
     * <p>Positions come from the REAL vanilla method, called on the actual
     * placement object drawn from the registry —
     * {@link RandomSpreadStructurePlacement#getStartChunk} and
     * {@link StructurePlacement#applyFrequencyReduction} are both public and
     * are not reimplemented here, so this is exact by construction rather
     * than by a arithmetic transcription that could drift from a future MC
     * version. {@code structureSeed} is the candidate {@code seed} itself —
     * the same convention {@link NoiseStructurePlacement}'s own salting
     * already uses for a world that does not exist yet.
     *
     * <p>Walks the SAME prefiltered set list the noise pool above is built
     * from, and applies the SAME {@code structures.mode}/{@code exclude}
     * filter {@code DimensionStructures}'s live pass-through loop applies —
     * one shared {@code keepSet}, so a set the live world would drop never
     * shows up here as placed.
     *
     * <p>What this does NOT model: {@code applyExclusionZone} (needs a live
     * {@code StructurePlacementCalculator}, which a headless seed-roll has
     * none of — a cross-set exclusion a live world would honour can only
     * make a computed position here an OVERCOUNT, never an undercount) and
     * vanilla's retry-on-terrain-rejection for a multi-structure set (see
     * {@link #pickStructure} — the same simplification
     * {@code StructurePick.assignedStructure} already makes for
     * noise-managed positions). "Placed" here means "the grid assigns a site
     * and passes frequency reduction", not "the terrain accepted it" — the
     * same promise {@code NoisePoolBuilder}'s own javadoc already makes for
     * noise-managed positions.
     */
    private static PassThrough passThroughFacts(
            Iterable<RegistryEntry<StructureSet>> sets, DimensionConfig def,
            java.util.Set<String> exclude, long structureSeed, int radius) {
        DimensionConfig.Structures structBlock = def.getStructures();
        String mode = DimensionStructures.normalizedMode(def.getName(), structBlock);
        java.util.Set<String> modeList = structBlock != null && structBlock.list != null
                ? new java.util.HashSet<>(structBlock.list) : java.util.Set.of();
        int radiusChunks = radius / 16;

        Map<String, Integer> byStructure = new TreeMap<>();
        Map<String, Double> nearest = new TreeMap<>();
        java.util.Set<String> unmodelled = new java.util.TreeSet<>();

        for (RegistryEntry<StructureSet> entry : sets) {
            String setId = entry.getKey().map(k -> k.getValue().toString()).orElse(null);
            if (setId == null || "adventure:exit_shrines".equals(setId)) {
                continue;   // infrastructure, handled elsewhere, belongs to no group
            }
            StructureSet set = entry.value();
            StructurePlacement placement = set.placement();
            if (NoisePoolBuilder.noiseManaged(placement)) {
                continue;   // already counted by the noise pool above
            }
            if (!DimensionStructures.keepSet(setId, mode, modeList, exclude)) {
                continue;   // the live world drops this set too
            }
            if (!(placement instanceof RandomSpreadStructurePlacement rsp)
                    || placement instanceof FixedStructurePlacement) {
                unmodelled.add(setId);   // concentric rings, or another shape entirely
                continue;
            }
            int spacing = rsp.getSpacing();
            if (spacing <= 0) {
                unmodelled.add(setId);   // no grid to walk
                continue;
            }
            List<StructureSet.WeightedEntry> weighted = set.structures();
            int lowRegion = Math.floorDiv(-radiusChunks, spacing);
            int highRegion = Math.floorDiv(radiusChunks, spacing);
            for (int rx = lowRegion; rx <= highRegion; rx++) {
                for (int rz = lowRegion; rz <= highRegion; rz++) {
                    // Any chunk coordinate in the region resolves to the same
                    // start chunk — getStartChunk floor-divides by spacing
                    // internally — so the region's own anchor is as good as
                    // any other point in it.
                    ChunkPos pos = rsp.getStartChunk(structureSeed, rx * spacing, rz * spacing);
                    double blocks = Math.hypot(pos.x * 16.0, pos.z * 16.0);
                    if (blocks > radius) {
                        continue;   // outside the playable disc
                    }
                    if (!placement.applyFrequencyReduction(pos.x, pos.z, structureSeed)) {
                        continue;   // this region's start chunk does not fire
                    }
                    String structureId = pickStructure(weighted, structureSeed, pos.x, pos.z);
                    if (structureId == null) {
                        continue;
                    }
                    byStructure.merge(structureId, 1, Integer::sum);
                    nearest.merge(structureId, blocks, Math::min);
                }
            }
        }
        return new PassThrough(byStructure, nearest, new ArrayList<>(unmodelled));
    }

    /**
     * Vanilla's weighted pick among a set's structures at one start chunk —
     * read off the 1.21.1 {@code ChunkGenerator} bytecode, not reimplemented
     * from memory: a {@code ChunkRandom} seeded with
     * {@code setCarverSeed(structureSeed, chunkX, chunkZ)}, then one
     * {@code nextInt(totalWeight)} draw walks the weighted list exactly as
     * vanilla's own selection does. Vanilla RETRIES against a shrinking pool
     * when a draw's {@code Structure.createStructureStart} declines the
     * position (biome or terrain mismatch); that retry needs the same
     * terrain checks {@code NoisePoolBuilder}'s weighted pick already
     * declines to make (see its own "wanted" javadoc), so this is always
     * vanilla's FIRST draw — the structure the grid assigns, not a promise
     * the terrain accepts it.
     */
    private static String pickStructure(List<StructureSet.WeightedEntry> weighted,
                                        long structureSeed, int chunkX, int chunkZ) {
        if (weighted.isEmpty()) {
            return null;
        }
        if (weighted.size() == 1) {
            return weighted.get(0).structure().getKey()
                    .map(k -> k.getValue().toString()).orElse(null);
        }
        int totalWeight = 0;
        for (StructureSet.WeightedEntry w : weighted) {
            totalWeight += w.weight();
        }
        if (totalWeight <= 0) {
            return null;
        }
        ChunkRandom random = new ChunkRandom(new CheckedRandom(0L));
        random.setCarverSeed(structureSeed, chunkX, chunkZ);
        int roll = random.nextInt(totalWeight);
        for (StructureSet.WeightedEntry w : weighted) {
            roll -= w.weight();
            if (roll < 0) {
                return w.structure().getKey().map(k -> k.getValue().toString()).orElse(null);
            }
        }
        return null;   // unreachable: roll < totalWeight by construction
    }

    /** What {@link #passThroughFacts} found, before wrapping as {@link Measured}. */
    private record PassThrough(Map<String, Integer> byStructure, Map<String, Double> nearest,
                               List<String> unmodelledSets) {
    }

    /**
     * The structures each {@code #tag} this config names actually holds.
     *
     * <p>Only the tags the config references, because the whole registry's tag
     * table would ride in every candidate file to answer a question nobody
     * asked of it. A tag that resolves to nothing is recorded as an empty list
     * rather than dropped: "this tag holds no structures here" is a fact about
     * the mod stack, and a missing key would read as "not looked up".
     */
    private static Measured<Map<String, List<String>>> tagMembers(
            MinecraftServer server, DimensionConfig def) {
        java.util.Set<String> tags = StructureWants.referencedTags(def);
        if (tags.isEmpty()) {
            return Measured.absent("this dimension's wants and shuns name no tags");
        }
        var registry = server.getRegistryManager().get(RegistryKeys.STRUCTURE);
        Map<String, List<String>> out = new TreeMap<>();
        for (String tag : tags) {
            Identifier id = Identifier.tryParse(tag.substring(1));
            List<String> members = new ArrayList<>();
            if (id != null) {
                var key = net.minecraft.registry.tag.TagKey.of(RegistryKeys.STRUCTURE, id);
                registry.getEntryList(key).ifPresent(entries -> {
                    for (RegistryEntry<net.minecraft.world.gen.structure.Structure> e : entries) {
                        e.getKey().ifPresent(k -> members.add(k.getValue().toString()));
                    }
                });
            }
            java.util.Collections.sort(members);
            out.put(tag, members);
        }
        return Measured.of(out);
    }

    /**
     * Clark-Evans for each group on its own.
     *
     * <p>The pooled figure cannot answer whether a group forms pockets. Each
     * group is an independent point process — its own noise field, frequency and
     * exclusion radius — and superimposing several independent processes drives
     * the combined statistic toward that of a random scatter whatever the parts
     * look like.
     *
     * <p>A group with fewer than two placements contributes no entry rather than
     * a filler value — the map states what it could measure.
     */
    static Measured<Map<String, Double>> clusteringByGroup(
            Map<String, List<long[]>> positionsByGroup, int radiusChunks) {
        Map<String, Double> out = new TreeMap<>();
        for (Map.Entry<String, List<long[]>> e : positionsByGroup.entrySet()) {
            Measured<Double> c = clustering(e.getValue(), radiusChunks);
            if (c.isPresent()) {
                out.put(e.getKey(), c.orThrow());
            }
        }
        return out.isEmpty()
                ? Measured.absent("no group has two placements, so no group has a spacing "
                        + "to characterise")
                : Measured.of(out);
    }

    private static SeedFacts.StructureFacts absentStructures(String why) {
        return new SeedFacts.StructureFacts(
                Measured.absent(why), Measured.absent(why), Measured.absent(why),
                Measured.absent(why), Measured.absent(why), Measured.absent(why),
                Measured.absent(why), Measured.absent(why), Measured.absent(why),
                Measured.absent(why), Measured.absent(why), Measured.absent(why));
    }

    private static double nearestDistanceBlocks(List<long[]> positions) {
        double best = Double.MAX_VALUE;
        for (long[] p : positions) {
            best = Math.min(best, Math.hypot(p[0] * 16.0, p[1] * 16.0));
        }
        return best;
    }

    /**
     * Mean nearest-neighbour distance over the distance a uniform scatter of
     * the same count in the same area would give (Clark-Evans).
     *
     * <p>Below 1 means the placements sit in pockets — a PLACE a player finds
     * and explores. At or above 1 means an even spread — scenery. The radial
     * curve cannot answer this: a curve describes density against distance from
     * spawn, and both a clustered and an even layout can share one.
     *
     * <p>Every placement is measured; there is no subsample. Thinning a
     * clustered pattern removes members of each pocket, so its mean
     * nearest-neighbour distance rises toward the random expectation — a
     * systematic error toward "evenly spread", worst on the dimensions with the
     * most placements. The nearest-neighbour search is therefore bucketed into a
     * uniform grid rather than capped: expected linear in the number of
     * placements, and exact.
     */
    static Measured<Double> clustering(List<long[]> positions, int radiusChunks) {
        int n = positions.size();
        if (n < 2) {
            return Measured.absent(
                    "fewer than two placements, so there is no spacing to characterise");
        }
        double sum = 0.0;
        NearestNeighbours nn = new NearestNeighbours(positions);
        for (int i = 0; i < n; i++) {
            sum += Math.sqrt(nn.nearestSquared(i));
        }
        double observed = sum / n;
        double area = Math.PI * (double) radiusChunks * radiusChunks;
        if (area <= 0.0) {
            return Measured.absent("the playable radius is zero, so density is undefined");
        }
        double expected = 0.5 / Math.sqrt(n / area);
        if (expected <= 0.0) {
            return Measured.absent("the expected spacing for this density is not positive");
        }
        return Measured.of(observed / expected);
    }

    /**
     * Exact nearest-neighbour distances over a set of chunk positions, bucketed
     * into a uniform grid so the whole set can be measured rather than a
     * subsample of it.
     *
     * <p>Duplicates are kept and matched by index, so two groups placing in the
     * same chunk answer zero. Dropping one would report a spacing the world does
     * not have.
     */
    private static final class NearestNeighbours {

        private final long[] xs;
        private final long[] zs;
        private final int cell;
        private final long minX;
        private final long minZ;
        private final int cols;
        private final int rows;
        /** CSR: cellStart[c]..cellStart[c+1] indexes into {@link #byCell}. */
        private final int[] cellStart;
        private final int[] byCell;

        NearestNeighbours(List<long[]> positions) {
            int n = positions.size();
            this.xs = new long[n];
            this.zs = new long[n];
            long lowX = Long.MAX_VALUE;
            long lowZ = Long.MAX_VALUE;
            long highX = Long.MIN_VALUE;
            long highZ = Long.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                long[] p = positions.get(i);
                xs[i] = p[0];
                zs[i] = p[1];
                lowX = Math.min(lowX, p[0]);
                lowZ = Math.min(lowZ, p[1]);
                highX = Math.max(highX, p[0]);
                highZ = Math.max(highZ, p[1]);
            }
            this.minX = lowX;
            this.minZ = lowZ;
            // Roughly one point per cell: the ring search then examines a
            // handful of cells per query whatever the density.
            double span = (double) (highX - lowX + 1) * (double) (highZ - lowZ + 1);
            this.cell = Math.max(1, (int) Math.ceil(Math.sqrt(span / n)));
            this.cols = (int) ((highX - lowX) / cell) + 1;
            this.rows = (int) ((highZ - lowZ) / cell) + 1;

            int[] counts = new int[cols * rows + 1];
            for (int i = 0; i < n; i++) {
                counts[cellOf(i) + 1]++;
            }
            for (int c = 0; c < cols * rows; c++) {
                counts[c + 1] += counts[c];
            }
            this.cellStart = counts;
            this.byCell = new int[n];
            int[] cursor = new int[cols * rows];
            for (int i = 0; i < n; i++) {
                int c = cellOf(i);
                byCell[cellStart[c] + cursor[c]++] = i;
            }
        }

        private int cellOf(int i) {
            int cx = (int) ((xs[i] - minX) / cell);
            int cz = (int) ((zs[i] - minZ) / cell);
            return cz * cols + cx;
        }

        /**
         * Squared distance from point {@code i} to the nearest other point, in
         * chunks. Exact: rings of cells are examined outward until every
         * unexamined cell is provably farther than the best found so far.
         */
        double nearestSquared(int i) {
            int cx = (int) ((xs[i] - minX) / cell);
            int cz = (int) ((zs[i] - minZ) / cell);
            double best = Double.MAX_VALUE;
            int maxRing = Math.max(cols, rows);
            for (int r = 0; r <= maxRing; r++) {
                for (int gz = cz - r; gz <= cz + r; gz++) {
                    if (gz < 0 || gz >= rows) {
                        continue;
                    }
                    boolean edgeRow = gz == cz - r || gz == cz + r;
                    for (int gx = cx - r; gx <= cx + r; gx++) {
                        if (gx < 0 || gx >= cols) {
                            continue;
                        }
                        // Interior cells belong to a smaller ring.
                        if (!edgeRow && gx != cx - r && gx != cx + r) {
                            continue;
                        }
                        int c = gz * cols + gx;
                        for (int k = cellStart[c]; k < cellStart[c + 1]; k++) {
                            int j = byCell[k];
                            if (j == i) {
                                continue;
                            }
                            double dx = xs[i] - xs[j];
                            double dz = zs[i] - zs[j];
                            double d = dx * dx + dz * dz;
                            if (d < best) {
                                best = d;
                            }
                        }
                    }
                }
                // A point in an unexamined cell sits at Chebyshev ring r+1 or
                // beyond, so no closer than r cells away.
                double bound = (double) r * cell;
                if (best <= bound * bound) {
                    return best;
                }
            }
            return best;
        }
    }

    // ------------------------------------------------------------ unmeasured

    private static SeedFacts unmeasurable(Identifier id, long seed, int radius,
                                          String fingerprint, String why) {
        return new SeedFacts(Artefacts.stackVersion(),
                id.toString(), seed, Instant.now().toString(), fingerprint, radius,
                new SeedFacts.SpawnFacts(Measured.absent(why), Measured.absent(why),
                        Measured.absent(why), Measured.absent(why), Measured.absent(why),
                        Measured.absent(why), Measured.absent(why)),
                new SeedFacts.BiomeFacts(Measured.absent(why), Measured.absent(why),
                        Measured.absent(why), Measured.absent(why)),
                new SeedFacts.TerrainFacts(Measured.absent(why), Measured.absent(why),
                        Measured.absent(why), Measured.absent(why), Measured.absent(why),
                        Measured.absent(why)),
                absentStructures(why),
                Measured.absent(why));
    }
}
