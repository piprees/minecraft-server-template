package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.facts.FactsEngine;
import com.customdimensions.roll.CandidateRender;
import com.customdimensions.roll.TerrainShape;
import com.customdimensions.tryout.TryOut;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Three heights for one column, measured on one grid: what the world holds,
 * what the facts say, and what the map draws.
 *
 * <p>A two-way comparison says two sources disagree and never which is
 * wrong. The banked facts read {@link SpikeSampler#sample} (a heightmap);
 * the map reads {@link CandidateRender}, which has TWO height sources of its
 * own and picks between them per dimension and seed
 * ({@link CandidateRender.HeightModel#heightSource()}); a player sees blocks.
 * Nothing forces the three to agree, and on
 * {@code the_wuthering_wisteria} seed {@code -8181123680324586121} they
 * reported 98%, 81% and 64% water respectively.
 *
 * <h2>How each side is read</h2>
 *
 * <table>
 *   <tr><td>{@code facts}</td><td>{@link SpikeSampler#sample} on the headless
 *       rig — {@code OCEAN_FLOOR_WG} for an open dimension,
 *       {@link ColumnScan} over {@code getColumnSample} for a ceilinged
 *       one. Both answer the block ABOVE the floor.</td></tr>
 *   <tr><td>{@code render}</td><td>{@link CandidateRender#surfaceAt} through
 *       the renderer's own {@code HeightModel}, so this is the renderer's own
 *       rule and not a re-derivation of it. Answers the floor block itself
 *       when it walks the density, and {@code round(128 * depth)} when the
 *       calibration says depth is a height. Measured at EVERY column here;
 *       the PNG measures on a coarser lattice and interpolates between, so
 *       this is the height the map is drawn from at a lattice point and the
 *       value it interpolates toward everywhere else.</td></tr>
 *   <tr><td>{@code world}</td><td>Real block states from the live try-out
 *       world, read with vanilla's own {@code OCEAN_FLOOR_WG} predicate (or
 *       the same {@link ColumnScan} the facts use, when ceilinged), so a
 *       convention gap cannot masquerade as a fault.</td></tr>
 * </table>
 *
 * <h2>Reading the world honestly</h2>
 *
 * <p>Chunks are requested at {@link ChunkStatus#SURFACE} — terrain, aquifers
 * and surface rules, before carvers and features. A tree blocks movement
 * exactly the way stone does, so a decorated chunk's floor is the top of the
 * canopy: measured on the overworld control, a mean +13 blocks against the
 * facts on 241 columns. A chunk already generated past FEATURES therefore has
 * its HEIGHT recorded absent with the reason, while its water and ground stay
 * measured — decoration moves the floor and nothing else, and dropping all
 * three instead moved that control's water from 35.3% to 43.5% because trees
 * grow on the land it excluded.
 *
 * <p>Chunks are requested ASYNCHRONOUSLY from {@code END_SERVER_TICK} and
 * never waited on, and the scan parks until each arrives. A synchronous
 * generate is how this wedged a server on 2026-08-14: a Lithostitched
 * template pool naming {@code minecraft:emptY} threw on an identifier path
 * that may not carry a capital letter, the chunk's future never completed,
 * and the main thread waited on it forever — RCON i/o-timeout with the
 * container still healthy, which is [K1]. A per-tick budget is no defence:
 * one bad chunk is enough, and the budget is only checked BETWEEN columns.
 * A chunk that has not arrived within {@link #CHUNK_WAIT_TICKS} is refused
 * and its columns recorded absent with the reason.
 *
 * <p>A column that could not be read is recorded as absent with its reason.
 * It is never counted as water and never counted as ground — a measurement
 * is exact or absent.
 */
public final class RenderCheck {

    /** Wall-clock a job may take from one tick. A quarter of the budget. */
    private static final long TICK_BUDGET_NANOS = 12_000_000L;

    /** Ticks a job waits for its try-out world before giving up. */
    private static final int WORLD_WAIT_TICKS = 20 * 60;

    /**
     * Rows written for disagreeing columns before the list is capped.
     *
     * <p>Above the grid's own column count, so in practice nothing is
     * dropped — the cap is a backstop against a future grid, not a budget.
     * Two hundred was the first value and it hid the diagnosis: rows are
     * recorded in scan order, so the cap filled with the top rows of the
     * grid and a dimension whose 608 worst columns sat further south read as
     * a mild ±1 offset in every row anybody could look at. A capped list is
     * honest about being capped; it is still the wrong instrument when the
     * question is WHERE the disagreement is.
     */
    private static final int ROW_CAP = 1500;

    /** Blocks either side of sea level that count as coastline. */
    private static final int COASTLINE_BAND = 2;

    /** What the world side reads. Recorded, because it is a real choice. */
    private static final ChunkStatus WORLD_CHUNK_STATUS = ChunkStatus.SURFACE;

    /**
     * Ticks a single chunk may take to generate before it is refused.
     *
     * <p>Generous — a heavily-modded chunk on a busy server is seconds of
     * work — but finite, because [K1] is a chunk whose upgrade throws and
     * whose future therefore never completes at all.
     */
    private static final int CHUNK_WAIT_TICKS = 20 * 30;

    /**
     * Ticks a whole job may run before it stops and reports what it has.
     *
     * <p>The per-chunk deadline bounds ONE chunk. It does not bound a scan:
     * a dimension where most chunks are slow-but-arriving could legitimately
     * run for columns × CHUNK_WAIT_TICKS, which is hours, with nothing to cut
     * it off. Nothing blocks the tick loop while that happens — but an
     * open-ended wait is against this repo's rules whether or not it wedges
     * anything, and a diagnostic with no ceiling of its own is a bad
     * instrument. On expiry the job FINISHES rather than fails: the columns
     * it did read are real measurements, and the artefact already states its
     * own coverage.
     */
    private static final int JOB_MAX_TICKS = 20 * 60 * 45;

    /** Why a column on an already-decorated chunk is not comparable. */
    private static final String DECORATED =
            "chunk was already generated past features; its floor is the top of a "
            + "tree or a structure, which neither the facts nor the render model";

    private RenderCheck() {
    }

    /** Whether the world side runs at all. */
    public enum Mode {
        /** world, facts and render. Needs a world with generated chunks. */
        FULL,
        /** facts and render only — no world, and therefore CI-affordable. */
        HEADLESS
    }

    public enum State {
        AWAITING_WORLD, SCANNING, DONE, FAILED
    }

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();

    private static String key(Identifier dimensionId, long seed) {
        return dimensionId + "|" + seed;
    }

    // ------------------------------------------------------------------ start

    /**
     * Starts a check, or returns the job already running or finished for this
     * (dimension, seed). Never blocks: the work happens on later ticks.
     */
    public static Job start(MinecraftServer server, Identifier dimensionId, long seed,
                            Mode mode, Integer radiusOverride) {
        String k = key(dimensionId, seed) + "|" + mode + "|" + radiusOverride;
        Job existing = JOBS.get(k);
        if (existing != null) {
            // Whatever its state. Polling a finished check must ANSWER it, not
            // silently start it again — the first version did, and a run that
            // completed off-screen came back as "301/1313 columns" with no way
            // to tell a restart from progress.
            return existing;
        }
        Job job = new Job(server, dimensionId, seed, mode, radiusOverride);
        JOBS.put(k, job);
        return job;
    }

    /** Drops every job so a check can be run again in the same session. */
    public static int reset() {
        int n = JOBS.size();
        JOBS.clear();
        return n;
    }

    /**
     * Advances at most one running job. Called from {@code END_SERVER_TICK}.
     *
     * <p>One at a time on purpose: two jobs would each take the tick budget,
     * and the budget is the whole reason this does not wedge the server.
     */
    public static void tick(MinecraftServer server) {
        // The FIRST job that can actually do something takes the turn. A job
        // parked on a chunk future is `SCANNING` for its whole life, so
        // stopping at the first incomplete one handed every tick to a job
        // that was only waiting on I/O — a second job then received not
        // "fewer" advances but ZERO, and its own AWAITING_WORLD timeout never
        // advanced either, because that counter only moves when the job runs.
        // "Resolving the world" forever, indistinguishable from a world that
        // genuinely will not build.
        for (Job job : JOBS.values()) {
            if (job.state != State.AWAITING_WORLD && job.state != State.SCANNING) {
                continue;
            }
            if (job.advance(server)) {
                return;   // did real work; one job per tick keeps the budget honest
            }
        }
    }

    /**
     * Drops every job at shutdown. A finished job holds its {@link ServerWorld}
     * and its rigs; leaving them behind keeps a closed world's chunk caches
     * reachable for as long as the JVM lives.
     */
    public static void clear() {
        JOBS.clear();
    }

    // -------------------------------------------------------------------- job

    /** One column's three readings. */
    public record Row(int x, int z, Integer worldY, Integer factsY, Integer renderY,
                      boolean worldWater, boolean factsWater, boolean renderWater,
                      String absent) {
    }

    public static final class Job {

        private final Identifier dimensionId;
        private final long seed;
        private final Mode mode;
        private final DimensionConfig def;

        private State state = State.AWAITING_WORLD;
        private String error;
        private Path artefact;
        private String summary;

        // Geometry — the facts' own grid, so a disagreement is about the
        // heights and never about two samplers looking at different places.
        private final int radius;
        private final int side = FactsEngine.GRID;
        private final int step;
        private final List<int[]> columns = new ArrayList<>();
        private int cursor;

        // World side
        private Identifier worldId;
        private String worldKind = "none";
        private ServerWorld world;
        private int waitedTicks;
        private boolean weRequestedTryOut;
        private int columnsDecorated;
        private int columnsUnread;
        private Chunk cachedChunk;
        private long cachedChunkPos = Long.MIN_VALUE;
        private java.util.concurrent.CompletableFuture<OptionalChunk<Chunk>> pending;
        private long pendingPos = Long.MIN_VALUE;
        private int pendingSinceTick;
        private int chunkFailures;
        private String firstChunkFailure;
        private int startedTick;
        private int abandonedAfterTicks;

        // Headless side
        private SpikeSampler.Base base;
        private SpikeSampler.Rig factsRig;
        private CandidateRender.HeightModel model;
        private SpikeSampler.Rig renderRig;
        private TerrainShape.Density renderDensity;
        private Integer factsSeaLevel;
        private boolean floodsVoid;
        private String defaultFluid = "unknown";
        private boolean hasCeiling;
        private int floorY;
        private int topY;

        // Tallies
        private int sampled;
        private int worldWater;
        private int factsWater;
        private int renderWater;
        private int worldGround;
        private int factsGround;
        private int renderGround;
        private int worldRead;
        private int disagreeing;
        private int pairWorldFacts;
        private int pairWorldRender;
        private int pairFactsRender;
        private int maxWorldFacts;
        private int maxWorldRender;
        private int maxFactsRender;
        private int bucketExact;
        private int bucket12;
        private int bucket38;
        private int bucketBig;
        private int bucketAbsent;
        private int renderAboveWorld;
        private int renderBelowWorld;
        private int factsAboveWorld;
        private int factsBelowWorld;
        private int coastline;
        private int coastlineDisagreeing;
        private int worldUnreadable;
        // The two shapes a REGRESSION has, as opposed to the scatter a cheap
        // sampler always carries. A render whose height never varies is the
        // ceiling fault (measured: y=191 for all 1313 nether columns); a
        // non-zero median signed delta is a convention fault (measured: −1 on
        // 114 of 200 overworld rows). Both are one number, and neither is
        // confused by a mountainous dimension's honest scatter.
        private int renderMinY = Integer.MAX_VALUE;
        private int renderMaxY = Integer.MIN_VALUE;
        private final List<Integer> factsRenderDeltas = new ArrayList<>();
        // What the excluded decorated columns would have said, kept so the
        // exclusion is evidenced rather than merely asserted.
        private int decoratedCompared;
        private long decoratedDeltaSum;
        private int decoratedDeltaMax = Integer.MIN_VALUE;
        private int decoratedDeltaMin = Integer.MAX_VALUE;
        private final List<Row> rows = new ArrayList<>();

        Job(MinecraftServer server, Identifier dimensionId, long seed, Mode mode,
            Integer radiusOverride) {
            this.dimensionId = dimensionId;
            this.seed = seed;
            this.mode = mode;

            DimensionConfig resolved =
                    MultiverseConfig.getInstance().getDimension(dimensionId.getPath());
            if (resolved == null) {
                resolved = MultiverseConfig.getInstance().getBaseWorld(dimensionId.toString());
            }
            this.def = resolved;
            int configured = resolved != null ? resolved.getPlayerBorderRadius() : 8192;
            this.radius = radiusOverride != null ? radiusOverride : Math.max(1, configured);
            this.step = Math.max(1, (this.radius * 2) / (this.side - 1));

            int half = this.side / 2;
            for (int gz = 0; gz < this.side; gz++) {
                for (int gx = 0; gx < this.side; gx++) {
                    int dx = (gx - half) * this.step;
                    int dz = (gz - half) * this.step;
                    if ((long) dx * dx + (long) dz * dz > (long) this.radius * this.radius) {
                        continue;   // outside the playable disc — never attempted
                    }
                    this.columns.add(new int[] {dx, dz});
                }
            }
        }

        public State state() {
            return this.state;
        }

        public Path artefact() {
            return this.artefact;
        }

        /** The one line this check answers with — progress, or the result. */
        public String line() {
            return switch (this.state) {
                case AWAITING_WORLD -> "render-check " + this.dimensionId + " " + this.seed
                        + ": resolving the world"
                        + (this.worldId == null ? "" : " (" + this.worldId + ", "
                            + this.waitedTicks + "/" + WORLD_WAIT_TICKS + " ticks)")
                        + " — run again to poll";
                case SCANNING -> "render-check " + this.dimensionId + " " + this.seed
                        + ": " + this.cursor + "/" + this.columns.size()
                        + " columns — run again to poll";
                case FAILED -> "render-check " + this.dimensionId + " " + this.seed
                        + ": FAILED — " + this.error;
                case DONE -> this.summary;
            };
        }

        // ------------------------------------------------------------ advance

        /** @return whether this call did real work, rather than only waiting. */
        boolean advance(MinecraftServer server) {
            try {
                if (this.state == State.AWAITING_WORLD) {
                    prepare(server);
                    // Resolving the world IS progress: the wait counter moved.
                    return true;
                }
                if (this.state == State.SCANNING) {
                    int before = this.cursor;
                    scan(server);
                    return this.cursor > before || this.state != State.SCANNING;
                }
            } catch (RuntimeException e) {
                fail(e.getClass().getSimpleName() + ": " + e.getMessage());
                MultiverseServer.LOGGER.error("render-check {} seed {} failed",
                        this.dimensionId, this.seed, e);
                return true;
            }
            return false;
        }

        private void fail(String why) {
            this.state = State.FAILED;
            this.error = why;
        }

        /**
         * Resolves the world and builds both headless rigs.
         *
         * <p>Base worlds use the LIVE world, never a try-out: their generator
         * is vanilla's own, read from the DIMENSION registry, and
         * {@code createDimensionOptions} would hand a try-out a MANAGED
         * generator built from the base world's family instead — which is
         * exactly the thing a control is supposed to rule out.
         */
        private void prepare(MinecraftServer server) {
            if (this.base == null) {
                this.base = SpikeSampler.base(server, this.dimensionId);
                if (!this.base.ok()) {
                    fail("the dimension's generator could not be built: " + this.base.error());
                    return;
                }
                this.floorY = this.base.heightLimit().getBottomY();
                this.topY = this.base.heightLimit().getTopY() - 1;
                this.hasCeiling = this.base.hasCeiling();
                if (this.base.generator() instanceof NoiseChunkGenerator noiseGen) {
                    var settings = noiseGen.getSettings().value();
                    this.factsSeaLevel = settings.seaLevel();
                    this.floodsVoid = !settings.defaultFluid().getFluidState().isEmpty();
                    this.defaultFluid = net.minecraft.registry.Registries.FLUID
                            .getId(settings.defaultFluid().getFluidState().getFluid()).toString();
                }
                this.factsRig = SpikeSampler.forSeed(server, this.base, this.seed);
                // The same coverage the detail render calibrates over, so the
                // verdict here is the verdict the PNG was drawn under.
                this.model = CandidateRender.heightModel(server, this.base,
                        this.seed, Math.max(512, this.radius * 2));
                this.renderRig = CandidateRender.rigFor(server, this.base, this.model, this.seed);
                this.renderDensity = CandidateRender.densityFor(this.model, this.renderRig);
            }

            if (this.mode == Mode.HEADLESS) {
                this.worldKind = "none";
                this.state = State.SCANNING;
                return;
            }

            boolean isBaseWorld =
                    MultiverseConfig.getInstance().getBaseWorld(this.dimensionId.toString()) != null;
            if (isBaseWorld) {
                ServerWorld live = server.getWorld(
                        RegistryKey.of(RegistryKeys.WORLD, this.dimensionId));
                if (live == null) {
                    fail("base world " + this.dimensionId + " is not loaded");
                    return;
                }
                if (live.getSeed() != this.seed) {
                    fail("base world " + this.dimensionId + " runs seed " + live.getSeed()
                            + ", not " + this.seed
                            + " — a base world has no try-out that keeps vanilla's generator, "
                            + "so the control must be run against the seed it actually has");
                    return;
                }
                this.world = live;
                this.worldId = this.dimensionId;
                this.worldKind = "live";
                this.state = State.SCANNING;
                return;
            }

            if (this.worldId == null) {
                this.worldId = TryOut.worldIdFor(this.dimensionId, this.seed);
                ServerWorld already = server.getWorld(
                        RegistryKey.of(RegistryKeys.WORLD, this.worldId));
                if (already == null) {
                    Identifier requested = TryOut.request(server, this.dimensionId, this.seed, null);
                    if (requested == null) {
                        fail("no configured dimension " + this.dimensionId
                                + ", so no try-out world can be built for it");
                        return;
                    }
                    this.weRequestedTryOut = true;
                }
            }
            ServerWorld ready = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, this.worldId));
            if (ready == null) {
                this.waitedTicks++;
                if (this.waitedTicks > WORLD_WAIT_TICKS) {
                    fail("try-out world " + this.worldId + " did not appear within "
                            + WORLD_WAIT_TICKS + " ticks");
                }
                return;
            }
            this.world = ready;
            this.worldKind = this.weRequestedTryOut ? "tryout (built by this check)" : "tryout";
            this.state = State.SCANNING;
        }

        // --------------------------------------------------------------- scan

        private void scan(MinecraftServer server) {
            if (this.startedTick == 0) {
                this.startedTick = server.getTicks();
            } else if (server.getTicks() - this.startedTick > JOB_MAX_TICKS) {
                this.abandonedAfterTicks = server.getTicks() - this.startedTick;
                MultiverseServer.LOGGER.warn(
                        "render-check {} seed {}: stopping at {}/{} columns after {} ticks",
                        this.dimensionId, this.seed, this.cursor, this.columns.size(),
                        this.abandonedAfterTicks);
                finish(server);
                return;
            }
            long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
            while (this.cursor < this.columns.size()) {
                int[] at = this.columns.get(this.cursor);
                // The chunk is resolved BEFORE the column is measured, and a
                // chunk that is not ready yet parks the whole job until the
                // next tick rather than being waited on. See ensureChunk.
                if (this.mode == Mode.FULL
                        && ensureChunk(server, at[0] >> 4, at[1] >> 4) == ChunkState.PENDING) {
                    return;
                }
                this.cursor++;
                measure(at[0], at[1]);
                this.sampled++;
                if (System.nanoTime() > deadline) {
                    break;
                }
            }
            if (this.world != null && this.worldId != null && !this.worldId.equals(this.dimensionId)) {
                TryOut.keepAlive(server, this.worldId);
            }
            if (this.cursor >= this.columns.size()) {
                finish(server);
            }
        }

        private void measure(int x, int z) {
            SpikeSampler.Sample factsSample = SpikeSampler.sample(this.factsRig, x, z);
            Integer factsRaw = factsSample.surfaceHeight();
            // FactsEngine's own ground rule, applied here so the three sources
            // say "no ground" the same way. Vanilla's getHeight is
            // sampleHeightmap(…).orElse(getBottomY()), so an EMPTY column
            // answers the world floor rather than nothing — and comparing that
            // raw floor against a world column that is honestly void reported
            // 1226 phantom presence disagreements over the End, which is 94%
            // void and which all three sources actually agree about.
            boolean factsHasGround = factsRaw != null && factsRaw > this.floorY;
            Integer factsY = factsHasGround ? factsRaw : null;
            // FactsEngine.waterFraction, per column: a column with ground is
            // submerged when its floor is at or under the sea. A column with
            // NO ground is submerged only when this generator's void can hold
            // fluid at all (floodsVoid) AND a probe of the generated column
            // actually finds some there — aquifers_enabled makes flooding
            // noise-driven per region, so floodsVoid alone is not a verdict.
            // FactsEngine's own rule, CALLED rather than mirrored. A second
            // copy of it drifted silently once already: it kept answering
            // height <= seaLevel for grounded columns after the engine had
            // stopped, so a landed fix read as having changed nothing.
            boolean factsWaterHere = FactsEngine.submergedAt(
                    this.factsRig, this.floodsVoid, this.factsSeaLevel,
                    factsY, this.floorY, x, z).submerged();

            // Sampled through the RENDER's own rig, not reused from the facts'.
            // The two carry different routers — the facts' is complete, the
            // render's keeps the climate chains and the final density and zeroes
            // the rest — and the climate values are only asserted equal by
            // `spike-compare`, never by construction. Borrowing one for the
            // other would make this measure what the render OUGHT to see.
            SpikeSampler.Sample renderSample = SpikeSampler.sample(this.renderRig, x, z);
            Integer renderY = renderSample.biome() == null ? null
                    : CandidateRender.surfaceAt(this.model, this.renderDensity, renderSample, x, z);
            boolean renderWaterHere = CandidateRender.waterAt(
                    this.model, this.renderRig, renderSample.biome(), renderY, x, z);

            WorldColumn wc = this.mode == Mode.FULL ? readWorld(x, z) : WorldColumn.NONE;

            if (factsHasGround) {
                this.factsGround++;
            }
            if (renderY != null) {
                this.renderGround++;
                this.renderMinY = Math.min(this.renderMinY, renderY);
                this.renderMaxY = Math.max(this.renderMaxY, renderY);
            }
            if (factsY != null && renderY != null) {
                this.factsRenderDeltas.add(renderY - factsY);
            }
            if (factsWaterHere) {
                this.factsWater++;
            }
            if (renderWaterHere) {
                this.renderWater++;
            }
            // Decoration moves the FLOOR and nothing else: a tree does not put
            // water in a column or take ground out of one, and the submerged
            // test is "the highest fluid is above the highest blocking block",
            // which a canopy only ever pushes toward dry. So water and ground
            // stay measured on a decorated column; only its height is absent.
            // Dropping all three instead moved the overworld control's water
            // from 35.3% to 43.5%, because trees grow on the land it excluded.
            if (wc.readable()) {
                this.worldRead++;
                if (wc.hasGround) {
                    this.worldGround++;
                }
                if (wc.water) {
                    this.worldWater++;
                }
            }
            if (wc.decoratedFloorY() != null && factsY != null) {
                int d = wc.decoratedFloorY() - factsY;
                this.decoratedCompared++;
                this.decoratedDeltaSum += d;
                this.decoratedDeltaMax = Math.max(this.decoratedDeltaMax, d);
                this.decoratedDeltaMin = Math.min(this.decoratedDeltaMin, d);
            }

            recordDisagreement(x, z, wc, factsY, renderY, factsWaterHere, renderWaterHere);
        }

        private void recordDisagreement(int x, int z, WorldColumn wc, Integer factsY,
                                        Integer renderY, boolean factsWaterHere,
                                        boolean renderWaterHere) {
            // A column the world could not READ is absent, never a
            // disagreement: counting it as one made every already-decorated
            // chunk look like a fault and buried the ones that are. A column
            // the world read and found EMPTY is the opposite — a measurement
            // of no ground, and comparable with the other two saying the same.
            boolean worldComparable = wc.readable() && wc.absent == null;
            Integer worldY = worldComparable ? wc.floorY() : null;

            int worldFacts = delta(worldY, factsY);
            int worldRender = delta(worldY, renderY);
            int factsRender = delta(factsY, renderY);

            boolean waterDiffers = factsWaterHere != renderWaterHere
                    || (wc.readable()
                        && (wc.water != factsWaterHere || wc.water != renderWaterHere));
            int worst = Math.max(Math.max(worldFacts, worldRender), factsRender);
            // "One answered a height and another answered nothing" is its own
            // kind of disagreement — an open-air column against a solid one.
            boolean oneSided = disagreesOnPresence(worldY, factsY, renderY, worldComparable);
            boolean differs = worst > 0 || waterDiffers || oneSided;

            if (worldFacts > 0) {
                this.pairWorldFacts++;
                this.maxWorldFacts = Math.max(this.maxWorldFacts, worldFacts);
            }
            if (worldRender > 0) {
                this.pairWorldRender++;
                this.maxWorldRender = Math.max(this.maxWorldRender, worldRender);
            }
            if (factsRender > 0) {
                this.pairFactsRender++;
                this.maxFactsRender = Math.max(this.maxFactsRender, factsRender);
            }

            if (!worldComparable && this.mode == Mode.FULL) {
                this.worldUnreadable++;
            }

            if (oneSided) {
                this.bucketAbsent++;
            } else if (worst == 0) {
                this.bucketExact++;
            } else if (worst <= 2) {
                this.bucket12++;
            } else if (worst <= 8) {
                this.bucket38++;
            } else {
                this.bucketBig++;
            }

            if (worldY != null) {
                if (renderY != null) {
                    if (renderY > worldY) {
                        this.renderAboveWorld++;
                    } else if (renderY < worldY) {
                        this.renderBelowWorld++;
                    }
                }
                if (factsY != null) {
                    if (factsY > worldY) {
                        this.factsAboveWorld++;
                    } else if (factsY < worldY) {
                        this.factsBelowWorld++;
                    }
                }
            }

            if (this.factsSeaLevel != null && nearSea(worldY, factsY, renderY)) {
                this.coastline++;
                if (differs) {
                    this.coastlineDisagreeing++;
                }
            }

            if (differs) {
                this.disagreeing++;
                if (this.rows.size() < ROW_CAP) {
                    this.rows.add(new Row(x, z, worldY, factsY, renderY,
                            wc.readable() && wc.water, factsWaterHere, renderWaterHere,
                            wc.absent));
                }
            }
        }

        /**
         * Whether the sources disagree about whether this column has ground at
         * all — one answered a height, another answered nothing. Absent world
         * readings are excluded: they are unmeasured, not empty.
         */
        private static boolean disagreesOnPresence(Integer worldY, Integer factsY,
                                                   Integer renderY, boolean worldComparable) {
            List<Integer> present = new ArrayList<>();
            if (worldComparable) {
                present.add(worldY);
            }
            present.add(factsY);
            present.add(renderY);
            boolean anyNull = present.stream().anyMatch(java.util.Objects::isNull);
            boolean anyValue = present.stream().anyMatch(java.util.Objects::nonNull);
            return anyNull && anyValue;
        }

        /** Whether any of the three puts this column within a hair of the sea. */
        private boolean nearSea(Integer worldY, Integer factsY, Integer renderY) {
            for (Integer h : new Integer[] {worldY, factsY, renderY}) {
                if (h != null && Math.abs(h - this.factsSeaLevel) <= COASTLINE_BAND) {
                    return true;
                }
            }
            return false;
        }

        private static int delta(Integer a, Integer b) {
            return a == null || b == null ? 0 : Math.abs(a - b);
        }

        // -------------------------------------------------------------- world

        /**
         * One column of real blocks, or the reason it could not be read.
         *
         * <p>{@code decoratedFloorY} carries the reading a decorated chunk DID
         * produce even though it is not comparable — dropping it entirely
         * would hide how far the canopy sits above the terrain, which is the
         * evidence that the column was excluded for the right reason.
         */
        private record WorldColumn(Integer floorY, boolean hasGround, boolean water,
                                   String fluidTop, String absent, Integer decoratedFloorY,
                                   boolean readable) {
            static final WorldColumn NONE =
                    new WorldColumn(null, false, false, null, "headless", null, false);
        }

        private WorldColumn readWorld(int x, int z) {
            Chunk chunk = this.cachedChunk;
            if (chunk == null) {
                this.columnsUnread++;
                return new WorldColumn(null, false, false, null,
                        this.firstChunkFailure != null ? this.firstChunkFailure
                                : "chunk (" + (x >> 4) + ", " + (z >> 4) + ") could not be read",
                        null, false);
            }
            boolean decorated = chunk.getStatus().isAtLeast(ChunkStatus.FEATURES);
            if (decorated) {
                this.columnsDecorated++;
            }

            Predicate<BlockState> blocksMovement =
                    Heightmap.Type.OCEAN_FLOOR_WG.getBlockPredicate();
            BlockPos.Mutable pos = new BlockPos.Mutable();
            int topBlocking = Integer.MIN_VALUE;
            int topFluid = Integer.MIN_VALUE;
            String fluidTop = null;
            int topOpaque = Integer.MIN_VALUE;
            for (int y = this.topY; y >= this.floorY; y--) {
                BlockState state = chunk.getBlockState(pos.set(x, y, z));
                if (topFluid == Integer.MIN_VALUE && !state.getFluidState().isEmpty()) {
                    topFluid = y;
                    fluidTop = state.getFluidState().isIn(FluidTags.WATER) ? "water"
                            : state.getFluidState().isIn(FluidTags.LAVA) ? "lava" : "other";
                }
                if (topOpaque == Integer.MIN_VALUE && state.isOpaque()) {
                    topOpaque = y;
                }
                if (topBlocking == Integer.MIN_VALUE && blocksMovement.test(state)) {
                    topBlocking = y;
                }
                if (topBlocking != Integer.MIN_VALUE && topFluid != Integer.MIN_VALUE
                        && topOpaque != Integer.MIN_VALUE) {
                    break;
                }
            }

            Integer floor;
            if (this.hasCeiling) {
                // The same scan the facts run, over real blocks rather than a
                // generated column sample — so a ceilinged dimension's two
                // sides differ only in what they read, never in how.
                ColumnScan.Result result = ColumnScan.scan(this.topY, this.floorY,
                        y -> chunk.getBlockState(pos.set(x, y, z)).isOpaque());
                floor = result.isPresent() ? result.floorY() : null;
                if (floor == null) {
                    return new WorldColumn(null, false,
                            topFluid != Integer.MIN_VALUE, fluidTop,
                            decorated ? DECORATED : null, null, true);
                }
            } else {
                // OCEAN_FLOOR_WG answers the block ABOVE the highest one that
                // blocks movement. Matching that exactly is what stops a
                // one-block convention gap being reported as a fault.
                floor = topBlocking == Integer.MIN_VALUE ? null : topBlocking + 1;
            }

            boolean hasGround = floor != null && floor > this.floorY;
            // Submerged means fluid sitting on the floor this column reports —
            // vanilla fills to seaLevel - 1, so probing seaLevel itself
            // answers "no water" on every column of a sea-level world.
            boolean water = topFluid != Integer.MIN_VALUE
                    && (floor == null || topFluid >= floor);

            if (decorated) {
                // A decorated chunk's floor is the top of whatever grew on it.
                // A spruce trunk blocks movement exactly the way stone does, so
                // vanilla's own OCEAN_FLOOR predicate answers the canopy — the
                // overworld control read a mean +13 blocks against the facts on
                // 18% of its columns before this was told apart from a fault.
                // Neither of the other two sources models a tree, so the column
                // is absent WITH ITS REASON, and the reading it did produce is
                // carried so the size of the effect stays visible.
                return new WorldColumn(null, hasGround, water, fluidTop, DECORATED, floor, true);
            }
            return new WorldColumn(floor, hasGround, water, fluidTop, null, null, true);
        }

        /** Whether the chunk a column needs is here, coming, or never coming. */
        private enum ChunkState { READY, PENDING, FAILED }

        /**
         * Asks for a chunk and NEVER waits for it.
         *
         * <p>The synchronous form of this — {@code getChunk(cx, cz, status,
         * true)} — wedged the server on 2026-08-14. A Lithostitched template
         * pool in {@code the_abyssal_shrine} names {@code minecraft:emptY},
         * with a capital Y, and an identifier path may not carry one: the
         * chunk's upgrade to {@code structure_starts} threw, its future never
         * completed, and the main thread waited on it forever. RCON went
         * i/o-timeout while {@code docker ps} still said healthy. That is
         * [K1], and a per-tick budget is no defence against it — one bad
         * chunk is enough, and the budget is only ever checked between
         * columns.
         *
         * <p>So the future is requested and polled. A chunk that has not
         * arrived within {@link #CHUNK_WAIT_TICKS} is refused, its columns are
         * recorded absent with the reason, and the scan carries on — which is
         * the same contract every other unreadable column already has: a
         * measurement is exact, or absent and says why.
         */
        private ChunkState ensureChunk(MinecraftServer server, int cx, int cz) {
            long packed = (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
            if (packed == this.cachedChunkPos) {
                return this.cachedChunk == null ? ChunkState.FAILED : ChunkState.READY;
            }
            if (this.pending == null || this.pendingPos != packed) {
                try {
                    this.pending = this.world.getChunkManager()
                            .getChunkFutureSyncOnMainThread(cx, cz, WORLD_CHUNK_STATUS, true);
                } catch (RuntimeException e) {
                    // Drop the previous chunk's future too: it is nobody's now,
                    // and leaving it in `pending` keeps a reference to a chunk
                    // this job has moved past.
                    this.pending = null;
                    this.pendingPos = Long.MIN_VALUE;
                    return refuseChunk(packed, cx, cz, "request threw " + e);
                }
                this.pendingPos = packed;
                this.pendingSinceTick = server.getTicks();
            }
            if (this.pending.isDone()) {
                Chunk chunk = null;
                try {
                    OptionalChunk<Chunk> result = this.pending.getNow(null);
                    chunk = result == null ? null : result.orElse(null);
                } catch (RuntimeException e) {
                    MultiverseServer.LOGGER.warn("render-check {}: chunk ({}, {}) failed: {}",
                            this.dimensionId, cx, cz, e.toString());
                }
                this.pending = null;
                if (chunk == null) {
                    return refuseChunk(packed, cx, cz, "the chunk loader returned no chunk");
                }
                this.cachedChunk = chunk;
                this.cachedChunkPos = packed;
                return ChunkState.READY;
            }
            if (server.getTicks() - this.pendingSinceTick > CHUNK_WAIT_TICKS) {
                this.pending = null;
                return refuseChunk(packed, cx, cz,
                        "did not generate within " + CHUNK_WAIT_TICKS + " ticks");
            }
            return ChunkState.PENDING;
        }

        private ChunkState refuseChunk(long packed, int cx, int cz, String why) {
            this.cachedChunk = null;
            this.cachedChunkPos = packed;
            this.chunkFailures++;
            if (this.firstChunkFailure == null) {
                this.firstChunkFailure = "chunk (" + cx + ", " + cz + ") " + why;
                MultiverseServer.LOGGER.warn("render-check {}: {}",
                        this.dimensionId, this.firstChunkFailure);
            }
            return ChunkState.FAILED;
        }

        // ------------------------------------------------------------- finish

        private void finish(MinecraftServer server) {
            double worldWaterFraction = this.worldRead == 0 ? Double.NaN
                    : this.worldWater / (double) this.worldRead;
            double factsWaterFraction = this.sampled == 0 ? Double.NaN
                    : this.factsWater / (double) this.sampled;
            double renderWaterFraction = this.sampled == 0 ? Double.NaN
                    : this.renderWater / (double) this.sampled;

            StringBuilder b = new StringBuilder(Artefacts.jsonHeader("render-check"));
            b.append(" \"dimension\": \"").append(this.dimensionId).append("\",\n");
            b.append(" \"seed\": ").append(this.seed).append(",\n");
            b.append(" \"mode\": \"").append(this.mode.name().toLowerCase(Locale.ROOT))
                    .append("\",\n");

            b.append(" \"world\": {\"id\": ")
                    .append(this.worldId == null ? "null" : "\"" + this.worldId + "\"")
                    .append(", \"kind\": \"").append(this.worldKind).append("\"")
                    .append(", \"chunkStatus\": \"surface\"")
                    .append(", \"columnsRead\": ").append(this.worldRead)
                    .append(", \"columnsUnread\": ").append(this.columnsUnread)
                    .append(", \"chunksRefused\": ").append(this.chunkFailures)
                    .append(", \"stoppedEarlyAfterTicks\": ")
                    .append(this.abandonedAfterTicks == 0 ? "null" : this.abandonedAfterTicks)
                    .append(", \"firstChunkFailure\": ")
                    .append(this.firstChunkFailure == null ? "null"
                            : "\"" + this.firstChunkFailure.replace("\"", "'") + "\"")
                    .append(", \"columnsDecorated\": ").append(this.columnsDecorated)
                    .append(", \"heightsComparable\": ")
                    .append(this.sampled - this.worldUnreadable)
                    .append(", \"coverage\": ")
                    .append(this.sampled == 0 ? "null"
                            : fmt(this.worldRead / (double) this.sampled))
                    .append(", \"decoratedFloorVsFacts\": {\"columns\": ")
                    .append(this.decoratedCompared)
                    .append(", \"meanDelta\": ")
                    .append(this.decoratedCompared == 0 ? "null"
                            : fmt(this.decoratedDeltaSum / (double) this.decoratedCompared))
                    .append(", \"minDelta\": ")
                    .append(this.decoratedCompared == 0 ? "null" : this.decoratedDeltaMin)
                    .append(", \"maxDelta\": ")
                    .append(this.decoratedCompared == 0 ? "null" : this.decoratedDeltaMax)
                    .append("}},\n");

            b.append(" \"geometry\": {\"side\": ").append(this.side)
                    .append(", \"step\": ").append(this.step)
                    .append(", \"playableRadius\": ").append(this.radius)
                    .append(", \"attempted\": ").append(this.side * this.side)
                    .append(", \"sampled\": ").append(this.sampled)
                    .append(", \"centre\": [0, 0]},\n");

            b.append(" \"generator\": {\"seaLevel\": ")
                    .append(this.factsSeaLevel == null ? "null" : this.factsSeaLevel)
                    .append(", \"defaultFluid\": \"").append(this.defaultFluid).append("\"")
                    .append(", \"floodsVoid\": ").append(this.floodsVoid)
                    .append(", \"hasCeiling\": ").append(this.hasCeiling)
                    .append(", \"floorY\": ").append(this.floorY)
                    .append(", \"topY\": ").append(this.topY)
                    .append(", \"band\": {\"bottomY\": ").append(this.model.band().bottomY())
                    .append(", \"topY\": ").append(this.model.band().topY())
                    .append(", \"cellHeight\": ").append(this.model.band().cellHeight())
                    .append(", \"rung\": ").append(this.model.band().rung()).append("}")
                    .append(", \"renderHeightSource\": \"").append(this.model.heightSource())
                    .append("\"")
                    .append(", \"renderSeaLevel\": ")
                    .append(this.model.seaLevel() == null ? "null" : this.model.seaLevel())
                    .append(", \"calibration\": {\"agreed\": ")
                    .append(this.model.calibration().agreed())
                    .append(", \"tested\": ").append(this.model.calibration().tested())
                    .append(", \"depthIsHeight\": ")
                    .append(this.model.calibration().depthIsHeight()).append("}")
                    .append(", \"factsHeightSource\": \"")
                    .append(this.hasCeiling ? "ColumnScan over getColumnSample" : "OCEAN_FLOOR_WG")
                    .append("\"},\n");

            b.append(" \"water\": {\"world\": ").append(fmt(worldWaterFraction))
                    .append(", \"facts\": ").append(fmt(factsWaterFraction))
                    .append(", \"render\": ").append(fmt(renderWaterFraction)).append("},\n");

            b.append(" \"ground\": {\"world\": ")
                    .append(this.worldRead == 0 ? "null"
                            : fmt(this.worldGround / (double) this.worldRead))
                    .append(", \"facts\": ")
                    .append(this.sampled == 0 ? "null"
                            : fmt(this.factsGround / (double) this.sampled))
                    .append(", \"render\": ")
                    .append(this.sampled == 0 ? "null"
                            : fmt(this.renderGround / (double) this.sampled)).append("},\n");

            b.append(" \"disagreement\": {\"columns\": ").append(this.disagreeing)
                    .append(", \"of\": ").append(this.sampled)
                    .append(", \"worldUnreadable\": ").append(this.worldUnreadable)
                    .append(", \"pairs\": {\"worldFacts\": ").append(this.pairWorldFacts)
                    .append(", \"worldRender\": ").append(this.pairWorldRender)
                    .append(", \"factsRender\": ").append(this.pairFactsRender).append("}")
                    .append(", \"maxDelta\": {\"worldFacts\": ").append(this.maxWorldFacts)
                    .append(", \"worldRender\": ").append(this.maxWorldRender)
                    .append(", \"factsRender\": ").append(this.maxFactsRender).append("}")
                    .append(", \"medianSignedRenderMinusFacts\": ")
                    .append(medianDelta() == null ? "null" : medianDelta())
                    .append(", \"renderHeightSpread\": ")
                    .append(this.renderMaxY == Integer.MIN_VALUE ? "null"
                            : (this.renderMaxY - this.renderMinY))
                    .append(", \"buckets\": {\"exact\": ").append(this.bucketExact)
                    .append(", \"blocks1to2\": ").append(this.bucket12)
                    .append(", \"blocks3to8\": ").append(this.bucket38)
                    .append(", \"blocks9plus\": ").append(this.bucketBig)
                    .append(", \"aHeightWasAbsent\": ").append(this.bucketAbsent).append("}")
                    .append(", \"signed\": {\"renderAboveWorld\": ").append(this.renderAboveWorld)
                    .append(", \"renderBelowWorld\": ").append(this.renderBelowWorld)
                    .append(", \"factsAboveWorld\": ").append(this.factsAboveWorld)
                    .append(", \"factsBelowWorld\": ").append(this.factsBelowWorld).append("}")
                    .append(", \"coastline\": {\"band\": ").append(COASTLINE_BAND)
                    .append(", \"columns\": ").append(this.coastline)
                    .append(", \"disagreeing\": ").append(this.coastlineDisagreeing).append("}")
                    .append("},\n");

            b.append(" \"rowsTotal\": ").append(this.disagreeing).append(",\n");
            b.append(" \"rowsRecorded\": ").append(this.rows.size()).append(",\n");
            b.append(" \"rowsCapped\": ").append(this.disagreeing > this.rows.size()).append(",\n");
            b.append(" \"rowColumns\": [\"x\", \"z\", \"worldY\", \"factsY\", \"renderY\","
                    + " \"worldWater\", \"factsWater\", \"renderWater\", \"absent\"],\n");
            b.append(" \"rowNote\": \"a null height means NO GROUND, measured — not"
                    + " unmeasured. An unmeasured column carries its reason in 'absent'."
                    + " factsY is normalised by FactsEngine's own ground rule (h > floorY),"
                    + " so vanilla getHeight's floor-for-an-empty-column reads as null here"
                    + " rather than as a surface sitting on bedrock.\",\n");
            b.append(" \"rows\": [");
            for (int i = 0; i < this.rows.size(); i++) {
                Row r = this.rows.get(i);
                if (i > 0) {
                    b.append(",\n  ");
                } else {
                    b.append("\n  ");
                }
                b.append('[').append(r.x()).append(", ").append(r.z()).append(", ")
                        .append(r.worldY() == null ? "null" : r.worldY()).append(", ")
                        .append(r.factsY() == null ? "null" : r.factsY()).append(", ")
                        .append(r.renderY() == null ? "null" : r.renderY()).append(", ")
                        .append(r.worldWater()).append(", ").append(r.factsWater()).append(", ")
                        .append(r.renderWater()).append(", ")
                        .append(r.absent() == null ? "null"
                                : "\"" + r.absent().replace("\"", "'") + "\"")
                        .append(']');
            }
            b.append("\n ]\n}\n");

            // The radius and the mode are part of the identity: two runs over
            // different discs are different measurements, and writing both to
            // one path lets the second silently replace the first.
            Path target = Artefacts.rollingDir().resolve("render-check")
                    .resolve(InputHash.of(this.def, server))
                    .resolve(this.dimensionId.getPath().replace('/', '_')
                            + "-" + this.seed + "-r" + this.radius
                            + (this.mode == Mode.HEADLESS ? "-headless" : "")
                            + ".json");
            try {
                Artefacts.write(target, b.toString());
                this.artefact = target;
            } catch (IOException e) {
                // NAME the exception. A FileSystemException's getMessage() is
                // the path and nothing else, so an unwritable mount reported
                // "could not write the artefact: /.seed-rolling/render-check"
                // and left the reason to be guessed at — it was an
                // AccessDeniedException, and saying so would have been the
                // whole diagnosis.
                fail("could not write the artefact: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
                return;
            }

            this.summary = "render-check " + this.dimensionId + " " + this.seed + ": "
                    + "world " + pct(worldWaterFraction) + " water, "
                    + "facts " + pct(factsWaterFraction) + ", "
                    + "render " + pct(renderWaterFraction) + " — "
                    + this.disagreeing + "/" + this.sampled + " columns disagree"
                    + " [±1-2 " + this.bucket12 + ", ±3-8 " + this.bucket38
                    + ", ±9+ " + this.bucketBig + ", absent " + this.bucketAbsent + "]"
                    + " (render height source: " + this.model.heightSource() + ")"
                    + " -> " + target;
            this.state = State.DONE;
            MultiverseServer.LOGGER.info("{}", this.summary);

            // A try-out this check built is ours to close. Each one generated
            // a grid of chunks over the whole playable disc; a matrix run
            // leaves dozens of them, and the idle sweep only reclaims them ten
            // minutes after the LAST touch. A try-out somebody else opened is
            // left alone — they are still in it.
            if (this.weRequestedTryOut && this.worldId != null) {
                TryOut.end(server, this.worldId);
            }
        }

        /**
         * The middle of {@code render - facts}. Zero means the two answer the
         * same question and disagree only by noise; anything else is a
         * convention that has drifted, and one number says so however much
         * scatter a steep dimension carries around it.
         */
        private Integer medianDelta() {
            if (this.factsRenderDeltas.isEmpty()) {
                return null;
            }
            List<Integer> sorted = new ArrayList<>(this.factsRenderDeltas);
            java.util.Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }

        private static String pct(double v) {
            return Double.isNaN(v) ? "n/a"
                    : String.format(Locale.ROOT, "%.1f%%", v * 100.0);
        }

        private static String fmt(double v) {
            return Double.isNaN(v) ? "null" : String.format(Locale.ROOT, "%.4f", v);
        }
    }
}
