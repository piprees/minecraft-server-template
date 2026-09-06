package com.customdimensions.client.realtime;

import com.customdimensions.client.CompanionPayloads;
import com.customdimensions.client.CustomDimensionsClient;
import com.customdimensions.client.config.RealtimeControls;
import com.customdimensions.client.config.RealtimeSettings;
import com.customdimensions.client.render.ProjectionStore;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the far side from the destination world this client holds, instead of
 * from a slab the server described.
 *
 * <h2>Where the camera is</h2>
 * The geometry is read at {@code destination = source + offset} and drawn at
 * the SOURCE position, so the player's own eye is already the camera on the
 * far side — a rigid translation and nothing else. The portal's scale was
 * spent server-side deriving that offset ({@link PortalCamera}); spending it
 * again here would be right at scale 1 and wrong at every other.
 *
 * <h2>The clock</h2>
 * A destination {@code ClientWorld} is built with no time in it and vanilla's
 * time packet updates the player's own world only, so an unfed destination
 * renders at time 0 forever. One save-wide time serves every dimension
 * server-side, so the player's own world is the clock.
 */
public final class RealtimeView {

    /** Grepped in the client log to prove a local projection was built. */
    public static final String BUILD_MARKER = "companion-client:local-projection";

    /**
     * How far past the opening the local view reaches, in blocks. An eye below
     * the opening's top edge sees floor all the way to here, so this is how far
     * the ground runs before the window ends. The setting's default; the live
     * value is {@code RealtimeSettings.viewDepth}, and the server sizes the
     * chunk core it feeds from the same number.
     */
    public static final int DEPTH = RealtimeSettings.DEFAULT_VIEW_DEPTH;

    /**
     * The eye-distance-to-opening-half-width ratio the box holds the sightline
     * cone for. At depth {@code d} the cone spans the half-width times
     * {@code (e + d) / e}, so it stays inside the box while {@code d <= r * e / w}.
     */
    public static final int CONE_RATIO = 3;

    /**
     * How far the box is widened on the two in-plane axes — the cone's width at
     * the far edge, so the array holds every cell the shape can want.
     */
    public static final int RADIUS = radiusFor(DEPTH);

    /** The cone's half-width at the far edge of a box {@code depth} deep. */
    public static int radiusFor(int depth) {
        return (depth + CONE_RATIO - 1) / CONE_RATIO;
    }

    /**
     * Half-width held at full width regardless of depth. The cone is narrow
     * near the opening and an eye pressed against it sees wider than the cone
     * allows, so the first {@code NEAR_RADIUS * CONE_RATIO} blocks keep the
     * whole box. Below this the near field would lose ground at the edges.
     */
    public static final int NEAR_RADIUS = 16;

    /**
     * Wall clock a slice may spend on END_CLIENT_TICK. This is the frame
     * guarantee: a unit costs between 27ns and 710ns depending on whether it
     * skips, reads an unloaded chunk or reads a populated one, so a count
     * cannot bound a duration and only the clock can.
     */
    public static final long SLICE_BUDGET_NANOS = 1_500_000L;

    /** Units between clock reads. Power of two; nanoTime is not free per unit. */
    private static final int CLOCK_EVERY = 256;

    /**
     * The hard cap on units a slice may read however cheap they are. The clock
     * is what normally stops a slice; this stops one whose every unit skips.
     */
    public static final int UNITS_PER_TICK = 8_192;

    /**
     * Indices a slice may step over per budgeted read before it yields. A cell
     * outside the cone costs a bounds check, so the product with the budget is
     * what bounds a slice that reads almost nothing.
     */
    private static final int SKIP_CAP = 8;

    /**
     * The destination revision at the last build, per opening. Keying on the
     * chunk COUNT instead freezes the view on a resend: a replaced
     * chunk leaves the count identical, so the walk never restarts.
     */
    private static final Map<BlockPos, Integer> BUILT_AT = new HashMap<>();

    /** Walks in progress, one per opening, resumed a slice at a time. */
    private static final Map<BlockPos, Scan> SCANS = new HashMap<>();

    private static volatile int slices;
    private static volatile long sliceNanos;
    private static volatile long peakSliceNanos;
    private static volatile long leastSliceNanos = Long.MAX_VALUE;
    private static volatile int lastCells;

    /**
     * What the box walk has cost on END_CLIENT_TICK since the last read, as
     * {@code slices=N min=Lus avg=Aus peak=Pus cells=C scans=S}. Reading clears
     * the window. {@code peak} is the frame impact, and min against peak is the
     * spread — one sample has none, so read over several.
     */
    public static String buildCost() {
        int spanSlices = slices;
        long spanNanos = sliceNanos;
        long spanPeak = peakSliceNanos;
        long spanLeast = leastSliceNanos;
        slices = 0;
        sliceNanos = 0;
        peakSliceNanos = 0;
        leastSliceNanos = Long.MAX_VALUE;
        if (spanSlices == 0) {
            return "slices=0 min=n/a avg=n/a peak=n/a cells=" + lastCells
                    + " scans=" + SCANS.size();
        }
        return "slices=" + spanSlices
                + " min=" + (spanLeast / 1000) + "us"
                + " avg=" + (spanNanos / spanSlices / 1000) + "us"
                + " peak=" + (spanPeak / 1000) + "us"
                + " cells=" + lastCells
                + " scans=" + SCANS.size();
    }

    private RealtimeView() {}

    public static void clear() {
        rebuildAll();
    }

    /**
     * Drops every opening's build bookmark so the next tick walks the box
     * again, without touching the projections themselves — the held view keeps
     * drawing while the rebuild runs. Returns the openings dropped.
     *
     * <p>The measurement seam for {@code meshBuildUs}: a rebuild otherwise
     * needs the chunk feed to move, which needs the player to.
     */
    public static int rebuildAll() {
        int dropped = BUILT_AT.size();
        BUILT_AT.clear();
        SCANS.clear();
        return dropped;
    }

    /** Forces one opening's next tick to rebuild, its held view having gone. */
    public static void forget(BlockPos apertureOrigin) {
        if (apertureOrigin != null) {
            BUILT_AT.remove(apertureOrigin);
            SCANS.remove(apertureOrigin);
        }
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null
                || !RealtimeControls.settings().renderClientSidePortals()) {
            return;
        }
        for (Identifier destination : DestinationWorlds.loadedCounts().keySet()) {
            syncClock(client.world, DestinationWorlds.get(destination));
        }
        for (CompanionPayloads.PortalFrame frame : PortalFrames.all()) {
            rebuildIfFed(frame);
        }
    }

    /** The destination runs the player's own clock and weather. */
    static void syncClock(ClientWorld source, ClientWorld destination) {
        if (source == null || destination == null) {
            return;
        }
        destination.setTime(source.getTime());
        destination.setTimeOfDay(source.getTimeOfDay());
        destination.setRainGradient(source.getRainGradient(1.0f));
        destination.setThunderGradient(source.getThunderGradient(1.0f));
    }

    /**
     * Advances one opening's walk when its destination has taken any chunk it
     * did not hold at the last build — a new one or a resent one — a slice per
     * tick. A walk already running is resumed rather than restarted; only when
     * it finishes does the projection change.
     */
    private static void rebuildIfFed(CompanionPayloads.PortalFrame frame) {
        int held = DestinationChunks.count(frame.destination());
        if (held == 0) {
            return;
        }
        int revision = DestinationChunks.revision(frame.destination());
        BlockPos key = frame.apertureOrigin();
        Scan scan = SCANS.get(key);
        if (scan == null) {
            Integer builtAt = BUILT_AT.get(key);
            if (builtAt != null && builtAt == revision) {
                return;
            }
            scan = Scan.start(frame, revision);
            if (scan == null) {
                return;
            }
            SCANS.put(key, scan);
        }
        ClientWorld destination = DestinationWorlds.get(frame.destination());
        if (destination == null) {
            // The world went while the walk was in flight; what is half-read
            // describes nothing. The held projection keeps drawing.
            SCANS.remove(key);
            return;
        }

        long startedAt = System.nanoTime();
        CompanionPayloads.Projection built = scan.advance(destination, UNITS_PER_TICK);
        long elapsed = System.nanoTime() - startedAt;
        slices++;
        sliceNanos += elapsed;
        peakSliceNanos = Math.max(peakSliceNanos, elapsed);
        leastSliceNanos = Math.min(leastSliceNanos, elapsed);
        if (built == null) {
            return;
        }
        SCANS.remove(key);
        lastCells = built.states().length;
        BUILT_AT.put(key, scan.revisionAtStart);
        ProjectionStore.accept(built);
        CustomDimensionsClient.LOGGER.info(
                "{} dimension={} aperture={} cells={} chunks={} revision={}",
                BUILD_MARKER, frame.destination(), key.toShortString(),
                built.states().length, held, scan.revisionAtStart);
    }

    /**
     * One opening's box read out of the world this client holds, a slice of it
     * per tick, and expressed in source coordinates — the shape the render path
     * already takes from the server.
     *
     * <p>Every read is on the client thread. The destination world is mutated
     * there too, by {@code DestinationWorlds.load}, so a walk on another thread
     * would race chunk insertion and the light swap it does.
     */
    private static final class Scan {

        private final CompanionPayloads.PortalFrame frame;
        private final int revisionAtStart;
        private final int[] origin;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final int ordinalA;
        private final int ordinalB;
        private final int normalOrdinal;
        private final int leadA;
        private final int leadB;
        private final int spanA;
        private final int spanB;
        private final int sizeN;
        private final boolean towardsHigh;
        private final int[] states;
        private final byte[] light;
        private final CompanionPayloads.Projection.TintGrid tints;
        private final BlockPos.Mutable at = new BlockPos.Mutable();
        private BoxScan progress;

        private Scan(CompanionPayloads.PortalFrame frame, int revisionAtStart,
                int[] origin, int sizeX, int sizeY, int sizeZ,
                int ordinalA, int ordinalB, int normalOrdinal,
                int leadA, int leadB, int spanA, int spanB, int sizeN, boolean towardsHigh) {
            this.frame = frame;
            this.revisionAtStart = revisionAtStart;
            this.origin = origin;
            this.ordinalA = ordinalA;
            this.ordinalB = ordinalB;
            this.normalOrdinal = normalOrdinal;
            this.leadA = leadA;
            this.leadB = leadB;
            this.spanA = spanA;
            this.spanB = spanB;
            this.sizeN = sizeN;
            this.towardsHigh = towardsHigh;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.states = new int[sizeX * sizeY * sizeZ];
            this.light = new byte[this.states.length];
            this.tints = new CompanionPayloads.Projection.TintGrid(sizeX, sizeZ);
            this.progress = BoxScan.of(sizeX, sizeY, sizeZ);
        }

        /** The box's shape and its arrays. Reads no blocks. */
        static Scan start(CompanionPayloads.PortalFrame frame, int revisionAtStart) {
            if (frame.aperture().isEmpty()) {
                return null;
            }
            Direction normal = Direction.values()[frame.normal()];
            Direction.Axis normalAxis = normal.getAxis();
            Direction.Axis axisA = normalAxis == Direction.Axis.X
                    ? Direction.Axis.Y : Direction.Axis.X;
            Direction.Axis axisB = normalAxis == Direction.Axis.Z
                    ? Direction.Axis.Y : Direction.Axis.Z;

            int minA = Integer.MAX_VALUE;
            int maxA = Integer.MIN_VALUE;
            int minB = Integer.MAX_VALUE;
            int maxB = Integer.MIN_VALUE;
            int plane = 0;
            for (BlockPos cell : frame.aperture()) {
                minA = Math.min(minA, on(cell, axisA));
                maxA = Math.max(maxA, on(cell, axisA));
                minB = Math.min(minB, on(cell, axisB));
                maxB = Math.max(maxB, on(cell, axisB));
                plane = on(cell, normalAxis);
            }
            boolean towardsHigh =
                    normal.getOffsetX() + normal.getOffsetY() + normal.getOffsetZ() > 0;
            int depth = RealtimeControls.settings().viewDepth();
            LocalVolume volume = LocalVolume.of(minA, maxA, minB, maxB, plane, towardsHigh,
                    depth, radiusFor(depth));

            int[] origin = new int[3];
            int[] size = new int[3];
            origin[axisA.ordinal()] = volume.originA();
            size[axisA.ordinal()] = volume.sizeA();
            origin[axisB.ordinal()] = volume.originB();
            size[axisB.ordinal()] = volume.sizeB();
            origin[normalAxis.ordinal()] = volume.originN();
            size[normalAxis.ordinal()] = volume.sizeN();
            return new Scan(frame, revisionAtStart, origin, size[0], size[1], size[2],
                    axisA.ordinal(), axisB.ordinal(), normalAxis.ordinal(),
                    minA - volume.originA(), minB - volume.originB(),
                    maxA - minA + 1, maxB - minB + 1, volume.sizeN(), towardsHigh);
        }

        /**
         * Reads until the slice's time is spent, or {@code budget} cells are
         * read, or the walk ends. A cell outside the shape costs a bounds
         * check rather than a read, so a slice skips over far corners cheaply;
         * the clock is what stops one whose cells are dense.
         */
        CompanionPayloads.Projection advance(ClientWorld destination, int budget) {
            int cells = this.progress.cells();
            int units = this.progress.units();
            int cap = Math.max(1, budget) * SKIP_CAP;
            long deadline = System.nanoTime() + SLICE_BUDGET_NANOS;
            int reads = 0;
            int stepped = 0;
            int unit = this.progress.cursor();
            while (unit < units && reads < budget && stepped < cap) {
                if (unit < cells) {
                    if (readCell(destination, unit)) {
                        reads++;
                    }
                } else {
                    readColumn(destination, unit, cells);
                    reads++;
                }
                unit++;
                stepped++;
                if ((stepped & (CLOCK_EVERY - 1)) == 0 && System.nanoTime() >= deadline) {
                    break;
                }
            }
            this.progress = this.progress.advancedTo(unit);
            return this.progress.done() ? payload(destination) : null;
        }

        /** False when the cell is outside the cone, which leaves it air. */
        private boolean readCell(ClientWorld destination, int index) {
            int lx = BoxScan.localX(index, this.sizeY, this.sizeZ);
            int ly = BoxScan.localY(index, this.sizeY);
            int lz = BoxScan.localZ(index, this.sizeY, this.sizeZ);
            if (!inShape(lx, ly, lz)) {
                return false;
            }
            this.at.set(this.origin[0] + lx + this.frame.dx(),
                    this.origin[1] + ly + this.frame.dy(),
                    this.origin[2] + lz + this.frame.dz());
            this.states[index] = Block.getRawIdFromState(destination.getBlockState(this.at));
            this.light[index] = (byte)
                    ((destination.getLightLevel(LightType.SKY, this.at) << 4)
                            | destination.getLightLevel(LightType.BLOCK, this.at));
            return true;
        }

        /**
         * The three local indices against the cone at their own depth. The
         * normal axis carries the depth; the other two carry the width.
         */
        private boolean inShape(int lx, int ly, int lz) {
            int[] local = {lx, ly, lz};
            int depthIndex = this.towardsHigh
                    ? local[this.normalOrdinal]
                    : this.sizeN - 1 - local[this.normalOrdinal];
            int half = ViewShape.halfWidthAt(depthIndex, NEAR_RADIUS, CONE_RATIO);
            return ViewShape.withinAxis(local[this.ordinalA], this.leadA, this.spanA, half)
                    && ViewShape.withinAxis(local[this.ordinalB], this.leadB, this.spanB, half);
        }

        private void readColumn(ClientWorld destination, int unit, int cells) {
            // Columns follow the cells, so a column with no read cell under it
            // has no top surface and answers below.
            int lx = BoxScan.columnX(unit, cells, this.sizeZ);
            int lz = BoxScan.columnZ(unit, cells, this.sizeZ);
            int airId = Block.getRawIdFromState(Blocks.AIR.getDefaultState());
            int top = CompanionPayloads.Projection.topSolid(
                    this.states, this.sizeY, this.sizeZ, lx, lz, airId);
            if (top < 0) {
                return;
            }
            this.at.set(this.origin[0] + lx + this.frame.dx(),
                    this.origin[1] + top + this.frame.dy(),
                    this.origin[2] + lz + this.frame.dz());
            Biome biome = destination.getBiome(this.at).value();
            this.tints.set(lx, lz, biome.getGrassColorAt(this.at.getX(), this.at.getZ()),
                    biome.getFoliageColor(), biome.getWaterColor());
        }

        private CompanionPayloads.Projection payload(ClientWorld destination) {
            return new CompanionPayloads.Projection(
                    this.frame.destination(), this.frame.apertureOrigin(), this.frame.aperture(),
                    this.frame.portalAxis(), this.frame.normal(),
                    new BlockPos(this.origin[0], this.origin[1], this.origin[2]),
                    this.sizeX, this.sizeY, this.sizeZ,
                    this.states, this.light,
                    this.frame.skyColor(), this.frame.fogColor(),
                    this.tints.palette(), this.tints.columns(),
                    destination.getDimension().ambientLight());
        }
    }

    private static int on(BlockPos pos, Direction.Axis axis) {
        switch (axis) {
            case X:
                return pos.getX();
            case Y:
                return pos.getY();
            default:
                return pos.getZ();
        }
    }
}
