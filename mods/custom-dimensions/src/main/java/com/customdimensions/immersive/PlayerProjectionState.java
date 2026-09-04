package com.customdimensions.immersive;

import com.customdimensions.MultiverseServer;
import com.customdimensions.companion.CompanionNetwork;
import com.customdimensions.companion.CompanionPayloads;
import com.customdimensions.companion.ProjectionStream;
import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One player's fake-block projection of one immersive portal zone: the slab
 * of source positions currently overwritten on that client, the last state
 * sent for each of them (so refreshes send deltas only), and the packet
 * plumbing to establish and tear it down.
 *
 * <h2>Why every packet is a {@code BlockUpdateS2CPacket}</h2>
 * {@code ChunkDeltaUpdateS2CPacket} looks like the batched answer, but its
 * only 1.21.1 constructor reads block states out of a REAL chunk section of
 * the world it describes, so it cannot express fake states at all.
 *
 * <h2>The pass is budgeted — {@link ProjectionBudget}</h2>
 * Walking past a portal inverts the WHOLE mask at once, so everything the
 * player could see needs a correction packet in the same pass — on the order
 * of a thousand {@code BlockUpdateS2CPacket}s in one tick, per viewer, per
 * portal, at the default 4-tick interval. {@link #send} therefore CLASSIFIES
 * the whole volume first and only then spends a per-pass ceiling, restores
 * before sends. Steady state — the few positions entering and leaving the
 * view cone as a player walks — is far under the ceiling and never queues.
 *
 * <h2>Player identity</h2>
 * The live {@link ServerPlayerEntity} is passed in on every call rather than
 * held: a respawn replaces the entity while keeping the UUID, and a cached
 * reference would leave the projection tracking a removed entity's position.
 *
 * <h2>The sightline mask, and the invariant that makes it safe</h2>
 * {@link ProjectionVolume#computeSourcePositions} returns a rectangular slab,
 * most of which sits behind the frame WALL rather than behind the opening.
 * Every position is therefore filtered through {@link
 * ProjectionVolume#seesThroughOpening} against this player's eye, on every
 * send — the mask is a property of where they are standing, not of the zone.
 *
 * <p>That makes fake blocks come and go while a player walks, which rests on
 * one invariant:
 *
 * <blockquote><b>{@code lastSent} is exactly the set of positions this client
 * is currently showing a fake block at, and nothing leaves it without a
 * correction packet having been sent.</b></blockquote>
 *
 * Three properties hold it up:
 * <ul>
 *   <li>{@code lastSent} only ever gains positions drawn from {@link #volume},
 *       and every rebuild of {@code volume} is preceded by {@link #restore}
 *       plus a clear;</li>
 *   <li>a position that becomes masked-out is restored and removed on the
 *       same pass, in the send loop — skipping it is what would leave a
 *       trail of stuck fake blocks behind a player who walks around a
 *       portal;</li>
 *   <li>the removal is conditional on the correction actually going out. An
 *       unloaded source chunk (or a player who has left the world) keeps the
 *       position in {@code lastSent} so a later pass can retry.</li>
 * </ul>
 *
 * <h2>The one exemption: the aperture</h2>
 * The opening's own cells bypass the mask and are painted whenever the
 * projection is active — {@code Blocks.LIGHT} at the portal's configured
 * {@code lightLevel}, or plain air when it has none and the aperture is an
 * arrival portal whose purple swirl needs hiding.
 *
 * <p>It has to be the APERTURE rather than the slab layer behind it: a
 * view-DEPENDENT set of light sources is a view-dependent amount of light,
 * relighting the area every time the set changes. The zone's first slab
 * layer still has a SIDE that flips as a player walks round the frame; the
 * aperture is the one piece of geometry that is the same set of cells from
 * everywhere. {@code LIGHT} is invisible, so an aperture cell hidden behind
 * the frame wall leaks no geometry.
 *
 * <p>These positions still go through {@code lastSent} and are restored by
 * the same {@link #restore}, so the invariant above is untouched.
 *
 * <h2>Three block states, not two</h2>
 * Everything this class reads from the target world is one of THREE things:
 * <ul>
 *   <li><b>air</b> — a loaded chunk says there is nothing there;</li>
 *   <li><b>solid</b> — a loaded chunk says there is something there;</li>
 *   <li><b>unknown</b> — the chunk is not loaded, so there is no evidence
 *       either way.</li>
 * </ul>
 * Unknown is the NORMAL state for the first tick or two after a zone takes
 * its chunk ticket. Any heuristic over projected content must keep the three
 * apart; treating unknown as air is how you conclude "empty dimension" from a
 * chunk that simply had not arrived.
 *
 * <h2>Withdrawn: depth auto-scaling — do not re-add it</h2>
 * A prior version shrank the preview to 2 blocks when most of the first depth
 * layer sampled as air, aiming to stop "a portal to a void dimension shows
 * void" from looking like a bug. The question was wrong: {@code
 * ArrivalResolver} lands the interior's floor row on the destination
 * SURFACE, so the first depth layer is the slab immediately above the
 * destination's terrain — air almost everywhere that is not a cave. "First
 * layer is mostly air" is the healthy case for a portal onto open terrain,
 * not a void-dimension signal, so whether a portal ran deep or shallow came
 * down to an arbitrary coin flip on how much padding landed in a hillside.
 * The depth is now always {@code settings.previewDepth()}, and a portal to a
 * void dimension previews void — an honest result that needs no rescuing.
 */
public final class PlayerProjectionState {

    /**
     * Shallowest slab that can carry a 4a light layer: with only one block of
     * depth, replacing it with invisible LIGHT would leave nothing to look at.
     */
    static final int LIGHT_LAYER_MIN_DEPTH = 2;

    /**
     * The invisible light source painted over the positions directly behind
     * the opening, so a preview of a dark destination is not a black
     * rectangle.
     *
     * <p>A method and not a constant, deliberately: {@code Blocks.LIGHT}
     * resolves through the block registry, so touching it from a static
     * initialiser makes this whole class unloadable outside a bootstrapped
     * game. {@code getDefaultState()} returns the interned instance every
     * time, so calling it per position is a field read.
     *
     * <p>Still level 15: the light source lives on the aperture (a handful of
     * cells) rather than the padded slab layer behind it (dozens of cells),
     * which already cuts total light hard. Block light decrements one per
     * step and does not pass opaque blocks, so the visible surfaces at the
     * far, lateral edge of an 8-deep preview are already dim at level 15;
     * dropping the level further would black out that periphery with no
     * neighbouring light left to make up the difference. Lower it with
     * {@code .with(Properties.LEVEL_15, n)} here and nowhere else if needed.
     */
    private static BlockState lightState(int level) {
        return Blocks.LIGHT.getDefaultState().with(Properties.LEVEL_15, level);
    }

    /**
     * 4c: squared movement below which a player counts as stationary
     * (0.5 blocks). Generous enough to catch a walking player, tight enough
     * to filter the jitter of standing still.
     */
    static final double STATIONARY_EPSILON_SQ = 0.25;

    /** 4c: refresh interval multiplier applied while stationary. */
    static final int STATIONARY_MULTIPLIER = 4;

    private final UUID playerId;
    private final String playerName;
    private final PortalHelper.PortalZone zone;
    private final RegistryKey<World> sourceWorldKey;

    /** Last state sent per source position — the delta baseline. */
    /**
     * Blocks of clearance kept around every body — covers the step a player
     * takes between refresh passes (4 ticks by default); with no padding the
     * projection still paints into the cell they are walking into.
     */
    private static final int BODY_PAD = 1;

    private final Map<BlockPos, BlockState> lastSent = new HashMap<>();

    /**
     * Positions the client is STILL showing a fake block at, which have left
     * {@link #volume} — a side flip rebuilds the slab on the other side of the
     * plane, so the old slab's positions are no longer reachable by iterating
     * the volume.
     *
     * <p>Restoring them all in one unbudgeted burst inside {@link #send}
     * would reproduce the packet spike {@link ProjectionBudget} exists to
     * prevent, just triggered by walking ROUND a portal rather than past it.
     * Carried here instead and drained under the budget, ahead of the
     * volume's own restores, since nothing else will ever revisit them.
     *
     * <p>The {@code lastSent} invariant extends over this map unchanged —
     * still "exactly what the client is showing", just in two buckets. Every
     * teardown path drains both ({@link #restore}), {@link #forget} clears
     * both, and a position re-entering the volume is removed from here the
     * moment {@code lastSent} takes responsibility for it again.
     */
    private final Map<BlockPos, BlockState> staleOutsideVolume = new HashMap<>();
    /** Side of the portal plane the slab currently sits on (null = none yet). */
    private Direction normal;
    private List<BlockPos> volume = List.of();
    /** Depth the current {@link #volume} was built with (0 = none yet). */
    private int builtDepth;
    /**
     * How far past the aperture the occluder probe looks, on top of
     * {@code previewRadius}. A block's shadow can spill a cell or two beyond
     * the slab's own footprint at a grazing angle, and a wall the probe never
     * looked at reads as see-through — which hides blocks it was covering.
     */
    private static final int OCCLUDER_MARGIN = 3;

    /**
     * Multiplier on {@code refreshInterval} for a companion client's rebuild.
     *
     * <p>The description is view-INDEPENDENT — there is no sightline mask to
     * follow a walking player — so it only needs re-sampling when the
     * destination itself changes. Slower than the fake path by design.
     */
    private static final int COMPANION_REBUILD_MULTIPLIER = 5;

    /** Last description sent to a companion client, or null. */
    private CompanionPayloads.Projection lastCompanionPayload;
    private long lastCompanionBuildTick;

    /**
     * Last geometry sent to a client drawing the destination itself. Resent
     * only when it changes: an opening does not move, so a portal's frame is
     * one packet for the life of the projection.
     */
    private CompanionPayloads.PortalFrame lastCompanionFrame;

    /** 4c: player EYE position and server tick at the last send. */
    private Vec3d lastRefreshEye;
    private long lastRefreshTick;
    /** 4c: last logged cadence, so only CHANGES of pace produce a line. */
    private boolean lastStationary;

    PlayerProjectionState(ServerPlayerEntity player, PortalHelper.PortalZone zone) {
        this.playerId = player.getUuid();
        this.playerName = player.getName().getString();
        this.zone = zone;
        this.sourceWorldKey = zone.sourceWorld;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public String playerName() {
        return this.playerName;
    }

    public RegistryKey<World> sourceWorldKey() {
        return this.sourceWorldKey;
    }

    /**
     * Number of positions currently faked on this client — BOTH buckets.
     *
     * <p>Counting only {@code lastSent} would under-report after a side flip,
     * when positions the client is still showing have been carried into
     * {@code staleOutsideVolume} pending a budgeted restore. Any leak check
     * built on this number has to see them, or a carried-over backlog reads
     * as "nothing projected".
     */
    public int projectedCount() {
        return this.lastSent.size() + this.staleOutsideVolume.size();
    }

    /** Positions carried over from a previous slab, awaiting restore. */
    public int pendingCarryOverCount() {
        return this.staleOutsideVolume.size();
    }

    /** Initial activation: (re)build the slab and send every position. */
    public void sendFull(ServerPlayerEntity player, ServerWorld sourceWorld, ServerWorld targetWorld,
            ImmersiveSettings settings, ProjectionVolume.TargetMapping mapping, int arrivalY, long tick) {
        send(player, sourceWorld, targetWorld, settings, mapping, arrivalY, tick, true);
    }

    /** Periodic refresh: send only positions whose target block changed. */
    public void sendDelta(ServerPlayerEntity player, ServerWorld sourceWorld, ServerWorld targetWorld,
            ImmersiveSettings settings, ProjectionVolume.TargetMapping mapping, int arrivalY, long tick) {
        send(player, sourceWorld, targetWorld, settings, mapping, arrivalY, tick, false);
    }

    /**
     * The destination's nearby entities, on the feed's OWN cadence rather than
     * the slab's.
     *
     * <p>A viewer standing still is throttled to a quarter rate by {@link
     * #needsRefresh}, which is right for blocks and wrong for entities: the
     * whole point of the far side is that it moves while the viewer does not.
     * So this is driven every tick and gated by
     * {@link com.customdimensions.companion.DestinationEntityFeed#due} instead.
     *
     * <p>Silent for a player still being sent a slab: that path has no way to
     * draw an entity, and the frame is what says otherwise.
     */
    public void feedEntities(ServerPlayerEntity player, ServerWorld targetWorld,
            ProjectionVolume.TargetMapping mapping, int arrivalY, long tick) {
        if (player == null || targetWorld == null || mapping == null
                || this.lastCompanionFrame == null) {
            return;
        }
        com.customdimensions.companion.DestinationEntityFeed.feed(player, targetWorld,
                mapping.arrivalX() + 0.5, arrivalY, mapping.arrivalZ() + 0.5, tick);
    }

    private void send(ServerPlayerEntity player, ServerWorld sourceWorld, ServerWorld targetWorld,
            ImmersiveSettings settings, ProjectionVolume.TargetMapping mapping, int arrivalY,
            long tick, boolean full) {
        ServerPlayNetworkHandler handler = handlerFor(player);
        if (handler == null) {
            return;
        }
        if (CompanionNetwork.isCompanion(this.playerId)) {
            sendCompanion(player, handler, sourceWorld, targetWorld, settings, mapping, arrivalY, tick, full);
            return;
        }
        Vec3d eye = player.getEyePos();
        // Restores triggered by the mask below address coordinates in
        // sourceWorld; if the player is somehow no longer there those packets
        // would paint source blocks into another dimension (the same reason
        // restore() checks). Positions then simply stay in lastSent.
        boolean sameWorld = sourceWorld != null
                && sourceWorld.getRegistryKey().equals(player.getServerWorld().getRegistryKey());
        // Chunk lookups are cached per pass; nulls are cached too, so an
        // unloaded target chunk costs one lookup per pass, not one per block.
        Map<Long, WorldChunk> chunks = new HashMap<>();

        Direction wanted = ProjectionVolume.viewerFarSide(
                this.zone.interior, this.zone.axis, player.getBlockPos(), this.normal);
        boolean sideFlip = wanted != this.normal;
        // Always the configured depth. There is deliberately no heuristic
        // here any more — see "Withdrawn: depth auto-scaling" in the class
        // comment before adding one back.
        int depth = settings.previewDepth();

        if (full || sideFlip || depth != this.builtDepth || this.volume.isEmpty()) {
            // Restore the old slab before building the new one, or blocks
            // that leave the volume stay faked until the player relogs. The
            // usual reason is a side flip (the player walked round the
            // frame); a depth change now only happens if config is re-read
            // under a live projection.
            // The slab's faked positions carry over rather than restoring now
            // — see staleOutsideVolume. The aperture is excluded: it is an
            // overlay for the projection's whole life, not part of the slab.
            for (BlockPos carried : slabCarryOver(this.lastSent.keySet(), this.zone.interior)) {
                this.staleOutsideVolume.put(carried, this.lastSent.remove(carried));
            }
            this.normal = wanted;
            this.builtDepth = depth;
            this.volume = ProjectionVolume.computeSourcePositions(this.zone.interior, this.zone.axis,
                    wanted, depth, settings.previewRadius());
        }

        // What actually blocks sight in the portal's plane, measured rather
        // than assumed — a frame's corner blocks and the wall it is set into
        // are both occluders, and neither is derivable from the aperture's
        // shape. Rebuilt per pass so breaking the wall widens the window on
        // the next refresh; the probe never loads a chunk (Rule 1), and an
        // unloaded cell counts as see-through, which only ever hides more.
        Set<BlockPos> occluders = sourceWorld == null
                ? ProjectionVolume.frameRing(this.zone.interior, this.zone.axis)
                : ProjectionVolume.occluders(this.zone.interior, this.zone.axis,
                        settings.previewRadius() + OCCLUDER_MARGIN,
                        probePos -> sourceWorld.getChunkManager()
                                .isChunkLoaded(probePos.getX() >> 4, probePos.getZ() >> 4)
                                && sourceWorld.getBlockState(probePos)
                                        .isOpaqueFullCube(sourceWorld, probePos));

        // The positions directly behind the opening are sent as invisible
        // LIGHT instead of their sampled block, so the preview is lit by its
        // own front face rather than the SOURCE dimension's sky. View-
        // INDEPENDENT: derived from the zone's own geometry, never the mask.
        Direction.Axis normalAxis = wanted.getAxis();

        // Per-player sightline mask. Resolved once per pass: the plane never
        // moves, and the probe is a single Mutable reused across the volume
        // so the mask costs no allocation per position.
        int planeCoord = ProjectionVolume.planeCoord(this.zone.interior, normalAxis);
        BlockPos.Mutable probe = new BlockPos.Mutable();
        int masked = 0;
        int unmasked = 0;
        int lights = 0;
        int bodies = 0;

        // Every cell any player's body occupies (padded by one for the step
        // they take between passes). A fake block here is an unmineable wall
        // only that player can see. The viewer is included: their own
        // preview must not wall them in either.
        Set<BlockPos> occupied = new HashSet<>();
        for (ServerPlayerEntity nearby : sourceWorld.getPlayers()) {
            net.minecraft.util.math.Box box = nearby.getBoundingBox();
            occupied.addAll(ProjectionVolume.occupiedCells(
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, BODY_PAD));
        }

        int bottomY = targetWorld.getBottomY();
        int topY = targetWorld.getTopY();
        // CLASSIFY first, ACT second — the pass is budgeted (ProjectionBudget).
        // Acting inside the classification loop would let ITERATION ORDER
        // decide what fits the budget; restores must outrank sends (a fake
        // block still showing is a wall the player collides with, one not
        // yet sent is merely absent), which can only be honoured by knowing
        // both totals before spending. The sightline probe — the expensive
        // part — still runs exactly once per position.
        List<BlockPos> pendingRestores = new ArrayList<>();
        List<BlockPos> pendingSendPos = new ArrayList<>();
        List<BlockState> pendingSendState = new ArrayList<>();

        // Carried-over positions go FIRST: they are outside the current
        // volume, so no later pass would reach them by iteration. A masked
        // position, by contrast, is re-queued every pass until it drains.
        if (sameWorld) {
            pendingRestores.addAll(this.staleOutsideVolume.keySet());
        }

        for (BlockPos pos : this.volume) {
            BlockState state;
            {
                if (occupied.contains(pos)) {
                    // A body is here. Same bookkeeping as the sightline mask
                    // below, and for the same reason: a position that becomes
                    // suppressed because somebody walked into it must be
                    // RESTORED and dropped from lastSent on this pass, or the
                    // fake block is stranded there until they relog. Skipping
                    // that is exactly what strands fake blocks.
                    bodies++;
                    if (sameWorld && this.lastSent.containsKey(pos)) {
                        pendingRestores.add(pos);
                    }
                    continue;
                }
                if (!ProjectionVolume.seesThroughOpening(eye, pos, normalAxis, planeCoord,
                        this.zone.interior, occluders, probe)) {
                    // Behind the frame wall from where this player is standing.
                    // A position that WAS visible and no longer is must be given
                    // its real block back here and now: the player walked, the
                    // frustum swung away from it, and nothing downstream would
                    // ever revisit it — that is precisely how a walk around a
                    // portal leaves a trail of stuck fake blocks. It only leaves
                    // lastSent once the correction has actually gone out, so an
                    // unloaded source chunk means "retry next pass", not "drop
                    // the record".
                    masked++;
                    if (sameWorld && this.lastSent.containsKey(pos)) {
                        pendingRestores.add(pos);
                    }
                    continue;
                }
                BlockPos targetPos = ProjectionVolume.toTarget(pos, mapping, arrivalY);
                if (targetPos.getY() < bottomY || targetPos.getY() >= topY) {
                    continue;
                }
                state = sample(targetWorld, targetPos, chunks);
                if (state == null) {
                    // Target chunk not loaded. A position never sent keeps its
                    // real source block; one already faked holds its last known
                    // state rather than flickering back and forth as the chunk
                    // comes and goes. Documented graceful degradation — see
                    // ImmersiveProjector for why we never load it ourselves.
                    continue;
                }
            }
            BlockState previous = this.lastSent.get(pos);
            if (previous == state) {
                continue;
            }
            pendingSendPos.add(pos);
            pendingSendState.add(state);
        }

        // SPEND. Restores first; sends take what is left.
        //
        // Deferral is safe under the two existing invariants and needs no
        // extra bookkeeping:
        //   - a deferred RESTORE keeps its lastSent entry, which is exactly
        //     what "lastSent is what the client is showing" requires, and the
        //     next pass re-queues it because the position is still masked;
        //   - a deferred SEND leaves lastSent stale, so the next pass sees
        //     previous != state and queues it again.
        ProjectionBudget.Allowance allowance = ProjectionBudget.allow(
                pendingRestores.size(), pendingSendPos.size(), ProjectionBudget.DEFAULT_MAX_PER_PASS);
        int deferred = (pendingRestores.size() - allowance.restores())
                + (pendingSendPos.size() - allowance.sends());

        for (int i = 0; i < allowance.restores(); i++) {
            BlockPos pos = pendingRestores.get(i);
            // Still conditional: an unloaded source chunk means "retry next
            // pass", not "drop the record".
            if (restoreOne(handler, sourceWorld, pos)) {
                this.lastSent.remove(pos);
                this.staleOutsideVolume.remove(pos);
                unmasked++;
            }
        }
        for (int i = 0; i < allowance.sends(); i++) {
            BlockPos pos = pendingSendPos.get(i);
            BlockState state = pendingSendState.get(i);
            handler.sendPacket(new BlockUpdateS2CPacket(pos, state));
            this.lastSent.put(pos, state);
            // lastSent is responsible for this position again; leaving it in
            // the stale bucket would restore a block that is currently faked.
            this.staleOutsideVolume.remove(pos);
        }

        // THE APERTURE, in both directions — the light layer and the
        // swirl-killer, which turn out to be the same pass.
        //
        // The aperture is the one part of the geometry that has no side, so
        // light emitted there cannot flip as a player walks round the frame
        // (unlike the first slab layer, which sits on whichever side the
        // slab currently is).
        //
        // Colour is not available: vanilla block light is white, and tinting
        // it needs a shader.
        // Re-sent every pass, never diffed against lastSent: any block update
        // the server broadcasts at an aperture cell repaints the client's copy
        // with the real block, and lastSent cannot see that happen.
        BlockState apertureState = apertureState();
        if (apertureState != null) {
            for (BlockPos pos : this.zone.interior) {
                lights++;
                handler.sendPacket(new BlockUpdateS2CPacket(pos, apertureState));
                this.lastSent.put(pos, apertureState);
            }
        }

        if (full || unmasked > 0) {
            // Counts, not events (mods/AGENTS.md): "activated" alone looked
            // perfectly healthy in all three of this feature's silent
            // failures. The visible/maskable ratio is the headless evidence
            // that the mask is doing something, the restored count is the
            // headless evidence that walking away from a sightline puts real
            // blocks back rather than stranding them, and the light count is
            // the headless evidence that the 4a layer is a fixed size — it
            // must not move as the player does.
            int maskable = this.volume.size();
            MultiverseServer.LOGGER.debug(
                    "immersive: sightline mask for {} at zone {} -> {} of {} maskable visible, "
                            + "{} restored, {} aperture cells overlaid, {} suppressed by bodies, "
                            + "{} deferred to the next pass",
                    this.playerName, this.sourceWorldKey.getValue(),
                    maskable - masked - bodies, maskable, unmasked, lights, bodies, deferred);
        }

        // The EYE, not the feet: the mask is a function of eye position, so
        // that is what "has this player moved enough to need a new mask?"
        // has to measure.
        this.lastRefreshEye = eye;
        this.lastRefreshTick = tick;
    }

    /**
     * The companion path: describe the destination instead of painting it.
     *
     * <p>The two are mutually exclusive per player. A client rendering the
     * destination itself must not also be shown fake blocks describing the
     * same space, so anything a previous vanilla-shaped pass left on this
     * client is handed back on the first companion pass.
     *
     * <p>The aperture overlay stays: it is what hides an arrival portal's
     * purple swirl and carries the portal's own light, and it is a handful of
     * cells rather than a slab.
     */
    private void sendCompanion(ServerPlayerEntity player, ServerPlayNetworkHandler handler,
            ServerWorld sourceWorld, ServerWorld targetWorld, ImmersiveSettings settings,
            ProjectionVolume.TargetMapping mapping, int arrivalY, long tick, boolean full) {
        boolean sameWorld = sourceWorld != null
                && sourceWorld.getRegistryKey().equals(player.getServerWorld().getRegistryKey());
        if (sameWorld) {
            for (BlockPos pos : slabCarryOver(this.lastSent.keySet(), this.zone.interior)) {
                if (restoreOne(handler, sourceWorld, pos)) {
                    this.lastSent.remove(pos);
                }
            }
            for (BlockPos pos : new ArrayList<>(this.staleOutsideVolume.keySet())) {
                if (restoreOne(handler, sourceWorld, pos)) {
                    this.staleOutsideVolume.remove(pos);
                }
            }
        }
        this.volume = List.of();
        this.builtDepth = 0;

        Direction wanted = ProjectionVolume.viewerFarSide(
                this.zone.interior, this.zone.axis, player.getBlockPos(), this.normal);
        boolean sideFlip = wanted != this.normal;
        this.normal = wanted;

        // A destination whose DimensionType is not a registered entry cannot be
        // stood up on a client at all, so that portal keeps its block slab
        // however the player set the toggle. Per portal, not per player.
        boolean declaredLocal = !CompanionNetwork.streamsSlab(this.playerId);
        CompanionPayloads.PortalFrame frame = declaredLocal
                ? ProjectionStream.frame(this.zone, wanted, targetWorld, mapping, arrivalY)
                : null;
        boolean streamsSlab = streamsSlab(declaredLocal, frame != null);

        if (frame != null) {
            if (!frame.equals(this.lastCompanionFrame)) {
                CompanionNetwork.sendPortalFrame(player, frame);
                this.lastCompanionFrame = frame;
            }
            // The destination's own chunks, in the wedge through the opening,
            // nearest first and a few per pass. Warms up outward rather than
            // stalling the tick on one client's whole view.
            com.customdimensions.companion.DestinationFeed.feed(player, targetWorld,
                    this.zone.interior, wanted.getAxis(), frame,
                    CompanionNetwork.portalView(this.playerId).maxRenderDistance(),
                    com.customdimensions.companion.DestinationFeed.DEFAULT_BUDGET);
        }
        if (companionPayloadStale(streamsSlab, this.lastCompanionPayload != null)) {
            // This client has taken over drawing the far side. What it already
            // holds describes the same space, so it goes now — leaving it would
            // draw the destination twice, once live and once as of a moment ago.
            CompanionNetwork.clearProjection(player, this.lastCompanionPayload.apertureOrigin());
            this.lastCompanionPayload = null;
            this.lastCompanionBuildTick = 0;
        }

        long cadence = Math.max(1, settings.refreshInterval()) * (long) COMPANION_REBUILD_MULTIPLIER;
        if (companionRebuildDue(streamsSlab, full, sideFlip, this.lastCompanionPayload == null,
                tick, this.lastCompanionBuildTick, cadence)) {
            this.lastCompanionBuildTick = tick;
            CompanionPayloads.Projection built = ProjectionStream.build(
                    this.zone, wanted, targetWorld, settings, mapping, arrivalY);
            if (built != null && !ProjectionStream.sameContent(built, this.lastCompanionPayload)) {
                CompanionNetwork.sendProjection(player, built);
                this.lastCompanionPayload = built;
            }
        }

        BlockState apertureState = apertureState();
        if (apertureState != null) {
            for (BlockPos pos : this.zone.interior) {
                handler.sendPacket(new BlockUpdateS2CPacket(pos, apertureState));
                this.lastSent.put(pos, apertureState);
            }
        }

        this.lastRefreshEye = player.getEyePos();
        this.lastRefreshTick = tick;
    }

    /**
     * Is this projection due a delta pass?
     *
     * <p>A stationary player is looking at a view that only changes when the
     * far side does, so their refresh interval is stretched by {@link
     * #STATIONARY_MULTIPLIER}. Nothing is skipped: {@code lastSent} stays
     * the authoritative baseline and the next pass sends every position that
     * has changed since, whenever that pass happens.
     *
     * <p>This is also the sightline mask's update rate, which is why the
     * movement test measures the EYE and not the feet. A MOVING player is
     * back on the configured interval immediately, so the frustum follows
     * them and positions it leaves behind are restored on the same pass.
     */
    public boolean needsRefresh(ServerPlayerEntity player, long tick, ImmersiveSettings settings) {
        double movedSq = this.lastRefreshEye == null
                ? Double.MAX_VALUE
                : player.getEyePos().squaredDistanceTo(this.lastRefreshEye);
        boolean due = shouldRefresh(tick, this.lastRefreshTick, movedSq, settings.refreshInterval());
        if (due) {
            // Evaluated only when a refresh is actually authorised: between
            // refreshes movedSq climbs towards the threshold and would flip
            // back the moment a send reset the baseline, so logging on every
            // call would produce two lines per interval for a walking player.
            boolean stationary = movedSq <= STATIONARY_EPSILON_SQ;
            if (stationary != this.lastStationary) {
                this.lastStationary = stationary;
                MultiverseServer.LOGGER.debug(
                        "immersive: refresh cadence for {} at zone {} -> every {} ticks ({})",
                        this.playerName, this.sourceWorldKey.getValue(),
                        stationary ? settings.refreshInterval() * STATIONARY_MULTIPLIER
                                : settings.refreshInterval(),
                        stationary ? "stationary" : "moving");
            }
        }
        return due;
    }

    /**
     * What to paint over the aperture, or null to leave it alone.
     *
     * <p>{@code LIGHT} at the portal's configured {@code lightLevel} — the
     * portal lighting itself, from a set of cells that has no side and so
     * cannot flip as a player walks round. Every portal's aperture is empty,
     * so a dimension with {@code lightLevel: 0} wants nothing painted there
     * at all.
     */
    private BlockState apertureState() {
        int level = apertureLightLevel(this.zone.definition);
        return level > 0 ? lightState(level) : null;
    }

    /**
     * The level the aperture pass paints for a portal; 0 paints nothing.
     *
     * <p>Public because a diagnostic has to answer from the SAME function the
     * pass paints from — a second copy of the clamp reports a light nobody is
     * painting. {@code Properties.LEVEL_15} throws outside 0..15, and a
     * definition that has left the config paints nothing rather than a
     * default.
     */
    public static int apertureLightLevel(com.customdimensions.config.PortalDefinition definition) {
        return definition == null ? 0 : Math.max(0, Math.min(15, definition.getLightLevel()));
    }

    /**
     * Whether this portal still owes this client a block slab.
     *
     * <p>Two conditions, and the second is per PORTAL rather than per player:
     * a destination whose {@code DimensionType} is not a registered entry
     * yields no frame, and a client cannot stand a world up for one it has no
     * type for. That portal keeps its slab however the player set the toggle.
     */
    static boolean streamsSlab(boolean declaredLocal, boolean frameBuilt) {
        return !declaredLocal || !frameBuilt;
    }

    /**
     * Whether this pass owes the client a fresh description of the far side.
     *
     * <p>{@code streamsSlab} outranks every other reason: a client rendering
     * the destination itself is sent no description of it on any pass, so a
     * full pass and a side flip both stay silent.
     */
    static boolean companionRebuildDue(boolean streamsSlab, boolean full, boolean sideFlip,
            boolean nothingSent, long tick, long lastBuildTick, long cadence) {
        if (!streamsSlab) {
            return false;
        }
        return full || sideFlip || nothingSent || tick - lastBuildTick >= cadence;
    }

    /**
     * Whether a description already on the client now describes a space that
     * client is drawing for itself, and has to be withdrawn.
     */
    static boolean companionPayloadStale(boolean streamsSlab, boolean anySent) {
        return !streamsSlab && anySent;
    }

    /** Pure 4c predicate: elapsed ticks against the movement-scaled interval. */
    static boolean shouldRefresh(long tick, long lastRefreshTick, double movedSq, int refreshInterval) {
        long interval = Math.max(1, refreshInterval);
        if (movedSq <= STATIONARY_EPSILON_SQ) {
            interval *= STATIONARY_MULTIPLIER;
        }
        return tick - lastRefreshTick >= interval;
    }

    /**
     * Which faked positions a slab rebuild hands to the restore path: the
     * slab's own, never the aperture's. A restored aperture cell repaints
     * plain air over the light the aperture is carrying.
     */
    static Set<BlockPos> slabCarryOver(Set<BlockPos> faked, Set<BlockPos> aperture) {
        Set<BlockPos> carried = new HashSet<>(faked);
        carried.removeAll(aperture);
        return carried;
    }

    /**
     * Restore the real source-dimension blocks and forget the slab. Safe to
     * call for a disconnected player or one who has changed world — it sends
     * nothing in either case.
     */
    public void cleanup(ServerPlayerEntity player, ServerWorld sourceWorld) {
        if (player != null) {
            // Both are keyed by the opening's minimum corner and the client
            // holds them in one store, so whichever was last sent is dropped
            // by the same clear.
            if (this.lastCompanionPayload != null) {
                CompanionNetwork.clearProjection(player, this.lastCompanionPayload.apertureOrigin());
            } else if (this.lastCompanionFrame != null) {
                CompanionNetwork.clearProjection(player, this.lastCompanionFrame.apertureOrigin());
            }
        }
        // The client drops its copy of the destination on the same teardown,
        // so the record of what it holds goes with it: a delta against
        // entities the client no longer has would leave them missing.
        com.customdimensions.companion.DestinationEntityFeed.forget(
                this.playerId, this.zone.targetWorld.getValue());
        restore(player, sourceWorld);
        forget();
    }

    /** Drop all tracking without sending anything. */
    public void forget() {
        this.lastCompanionPayload = null;
        this.lastCompanionFrame = null;
        this.lastCompanionBuildTick = 0;
        this.lastSent.clear();
        this.staleOutsideVolume.clear();
        this.volume = List.of();
        this.normal = null;
        this.builtDepth = 0;
        this.lastRefreshEye = null;
        this.lastRefreshTick = 0;
        this.lastStationary = false;
    }

    /**
     * Teardown restore — deliberately UNBUDGETED, unlike the per-pass one.
     *
     * <p>Every cleanup path calls this and then {@link #forget}, which drops
     * the state. A deferred teardown restore has nothing left to revisit it,
     * so the fake blocks would persist on that client until they relog. A
     * one-off burst on a rare event (disconnect, out of range, zone removed,
     * world change, world unload) is the right trade; the budget exists for
     * the per-pass path, which runs at 5Hz forever.
     */
    private void restore(ServerPlayerEntity player, ServerWorld sourceWorld) {
        if (this.lastSent.isEmpty() && this.staleOutsideVolume.isEmpty()) {
            return;
        }
        ServerPlayNetworkHandler handler = handlerFor(player);
        if (handler == null || sourceWorld == null) {
            return;
        }
        // Never paint into the wrong dimension: if the player has moved on,
        // these coordinates now address a different world on their client.
        // The dimension change resends every chunk anyway.
        if (!sourceWorld.getRegistryKey().equals(player.getServerWorld().getRegistryKey())) {
            return;
        }
        for (BlockPos pos : this.lastSent.keySet()) {
            restoreOne(handler, sourceWorld, pos);
        }
        // Both buckets are "what the client is showing" — see
        // staleOutsideVolume. Missing these is a leak on every teardown that
        // follows a side flip.
        for (BlockPos pos : this.staleOutsideVolume.keySet()) {
            restoreOne(handler, sourceWorld, pos);
        }
    }

    /**
     * Hand one position's REAL block back to the client, never a hardcoded
     * AIR: a projection position that overlaps a real portal block (anchor
     * portals) must come back as the portal block.
     *
     * <p>Returns false when nothing was sent because the source chunk is not
     * loaded — reading its state would load it, which the projector must
     * never do (Rule 1). The mask keeps the position in {@code lastSent} so
     * a later pass retries rather than stranding a fake block.
     */
    private static boolean restoreOne(ServerPlayNetworkHandler handler, ServerWorld sourceWorld, BlockPos pos) {
        if (sourceWorld == null
                || !sourceWorld.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        handler.sendPacket(new BlockUpdateS2CPacket(pos, sourceWorld.getBlockState(pos)));
        return true;
    }

    private static BlockState sample(ServerWorld targetWorld, BlockPos targetPos, Map<Long, WorldChunk> cache) {
        int cx = targetPos.getX() >> 4;
        int cz = targetPos.getZ() >> 4;
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        WorldChunk chunk;
        if (cache.containsKey(key)) {
            chunk = cache.get(key);
        } else {
            // residentChunk, never getWorldChunk(cx, cz, false): the
            // three-argument form waits for a ticketed chunk to finish
            // generating, which is a watchdog kill from this pass.
            chunk = PortalHelper.residentChunk(targetWorld, cx, cz);
            cache.put(key, chunk);
        }
        return chunk != null ? chunk.getBlockState(targetPos) : null;
    }

    private static ServerPlayNetworkHandler handlerFor(ServerPlayerEntity player) {
        if (player == null) {
            return null;
        }
        ServerPlayNetworkHandler handler = player.networkHandler;
        return handler != null && handler.isConnectionOpen() ? handler : null;
    }
}
