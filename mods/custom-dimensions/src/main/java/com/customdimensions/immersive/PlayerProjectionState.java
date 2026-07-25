package com.customdimensions.immersive;

import com.customdimensions.MultiverseServer;
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
 * only 1.21.1 constructor is
 * {@code (ChunkSectionPos, ShortSet, net.minecraft.world.chunk.ChunkSection)}
 * — it reads the block states out of a REAL chunk section of the world it is
 * describing, so it cannot express fake states at all. Verified against the
 * Yarn-mapped 1.21.1 jar; do not "optimise" back to it.
 *
 <h2>The pass is budgeted — {@link ProjectionBudget}</h2>
 * This class used to claim batching was unnecessary: "336 CANDIDATE positions
 * … the sightline mask sends well under half of those". Both numbers were
 * falsified in game on 2026-07-25. {@code previewRadius} went 2 -&gt; 4 in a
 * later session, taking the slab to 1056, and a single pass sent 984 packets:
 *
 * <pre>
 *   immersive: sightline mask ... 0 of 1056 maskable visible, 984 restored
 * </pre>
 *
 * Walking past a portal inverts the WHOLE mask at once, so everything the
 * player could see needs a correction packet in the same pass — ~1000
 * {@code BlockUpdateS2CPacket}s in one tick, per viewer, per portal, at the
 * default 4-tick interval. Reported as a multi-second stall with fake blocks
 * lingering afterwards.
 *
 * {@link #send} therefore CLASSIFIES the whole volume first and only then
 * spends a per-pass ceiling, restores before sends. Steady state — the few
 * positions entering and leaving the view cone as a player walks — is far
 * under the ceiling and never queues.
 *
 * <h2>Player identity</h2>
 * The live {@link ServerPlayerEntity} is passed in on every call rather than
 * held: a respawn replaces the entity while keeping the UUID, and a cached
 * reference would leave the projection tracking a removed entity's position.
 *
 * <h2>The sightline mask, and the invariant that makes it safe</h2>
 * {@link ProjectionVolume#computeSourcePositions} returns a rectangular slab,
 * most of which sits behind the frame WALL rather than behind the opening.
 * Sending all of it put destination terrain beside and above the frame for
 * anyone looking in the portal's general direction. Every position is
 * therefore filtered through {@link ProjectionVolume#seesThroughOpening}
 * against this player's eye, on every send — the mask is a property of where
 * they are standing, not of the zone, so it cannot be computed once and
 * cached on the volume.
 *
 * That makes fake blocks come and go while a player walks, which puts all the
 * weight on one invariant:
 *
 * <blockquote><b>{@code lastSent} is exactly the set of positions this client
 * is currently showing a fake block at, and nothing leaves it without a
 * correction packet having been sent.</b></blockquote>
 *
 * Three properties hold it up:
 * <ul>
 *   <li>{@code lastSent} only ever gains positions drawn from {@link #volume},
 *       and every rebuild of {@code volume} is preceded by {@link #restore}
 *       plus a clear — so iterating {@code volume} always reaches every faked
 *       position;</li>
 *   <li>a position that becomes masked-out is restored and removed on the
 *       same pass, in the send loop, rather than being silently skipped —
 *       skipping it is what would leave a trail of stuck fake blocks behind a
 *       player who walks around a portal;</li>
 *   <li>the removal is conditional on the correction actually going out. An
 *       unloaded source chunk (or a player who has left the world) keeps the
 *       position in {@code lastSent} so a later pass — or the teardown — can
 *       retry, instead of forgetting a block that is still faked.</li>
 * </ul>
 *
 * <h2>The one exemption: the aperture</h2>
 * The opening's own cells bypass the mask and are painted whenever the
 * projection is active — {@code Blocks.LIGHT} at the portal's configured
 * {@code lightLevel}, or plain air when it has none and the aperture is an
 * arrival portal whose purple swirl needs hiding.
 *
 * <p>It has to bypass the mask, and it has to be the APERTURE rather than the
 * slab layer behind it. A view-DEPENDENT set of light sources is a
 * view-dependent amount of light — the client relights the area every time the
 * set changes. Phase 4a fixed the first half of that by deriving the layer
 * from the zone instead of the mask, but the layer was still the first slab
 * layer, and the slab has a SIDE: {@code viewerFarSide} flips it when a player
 * walks round the frame, so the light flipped too ("the light seems to flip
 * sides when I move around the portal"). The aperture is the one piece of the
 * geometry that is the same set of cells from everywhere.
 *
 * <p>{@code LIGHT} is invisible, so an aperture cell that sits behind the
 * frame wall from this player's angle leaks no geometry — the mask exists to
 * keep VISIBLE blocks inside the opening, and this is not one of those.
 *
 * The exemption is deliberately narrow: these positions still go through
 * {@code lastSent} and are restored by the same {@link #restore}, so there is
 * one bookkeeping path and the invariant above is untouched. Everything else
 * in the volume is masked exactly as before.
 *
 * <h2>Phase 4: three block states, not two</h2>
 * Everything this class reads from the target world is one of THREE things,
 * and collapsing them to two is a bug this feature kept rediscovering:
 * <ul>
 *   <li><b>air</b> — a loaded chunk says there is nothing there;</li>
 *   <li><b>solid</b> — a loaded chunk says there is something there;</li>
 *   <li><b>unknown</b> — the chunk is not loaded (or the position is outside
 *       the target world's height range), so there is no evidence either
 *       way.</li>
 * </ul>
 * Unknown is the NORMAL state for the first tick or two after a zone takes
 * its chunk ticket — measured on the live server, the initial full send
 * routinely covers 294 of 336 positions because the far chunk is still on
 * its way in. Any future heuristic over projected content must keep the three
 * apart; treating unknown as air is how you conclude "empty dimension" from a
 * chunk that simply had not arrived.
 *
 * <h2>Withdrawn: 4e's depth auto-scaling — do not re-add it</h2>
 * Phase 4e shrank the preview to 2 blocks when more than 80% of the first
 * depth layer sampled as air, the stated aim being that "a portal to a void
 * dimension shows void" looks like a bug. It was built carefully — three-state
 * counting, a 75%-known quorum before deciding, the air ratio measured over
 * known samples only — and it was still wrong, because the QUESTION was wrong:
 *
 * <p>{@code ArrivalResolver} lands the interior's floor row on the destination
 * SURFACE. The first depth layer is therefore the slab immediately above the
 * destination's terrain, which is air almost everywhere that is not a cave.
 * Only the {@code previewRadius} padding rows, which map below the floor,
 * catch solid ground. "First layer is mostly air" is the healthy case for a
 * portal onto open terrain, not a void-dimension signal — so whether a portal
 * ran at full depth or half depth came down to how much padding happened to
 * land in a hillside. One fixture measured 30 air / 12 solid (71%, full
 * depth); a tester's portal a few hundred blocks away landed the other side of
 * the threshold and ran at {@code 6 x 7 x 2 = 84} blocks, which he correctly
 * reported as "no window effect at all".
 *
 * <p>Raising the threshold would only move the coin flip. The depth is now
 * always {@code settings.previewDepth()}, and a portal to a void dimension
 * previews void — an honest result that needs no rescuing. If someone wants
 * emptiness handled again, it needs a question about the DESTINATION (its
 * generator, its configured type) rather than about one slab of blocks.
 */
public final class PlayerProjectionState {

    /**
     * Shallowest slab that can carry a 4a light layer: with only one block of
     * depth, replacing it with invisible LIGHT would leave nothing to look at.
     * (Previously {@code SHALLOW_DEPTH}, the target of the withdrawn 4e
     * shrink — this threshold is all that survives of it.)
     */
    static final int LIGHT_LAYER_MIN_DEPTH = 2;

    /**
     * 4a: the invisible light source painted over the positions directly
     * behind the opening, so a preview of a dark destination is not a black
     * rectangle.
     *
     * <b>A method and not a constant, deliberately:</b> {@code Blocks.LIGHT}
     * resolves through the block registry, so touching it from a static
     * initialiser makes this whole class unloadable outside a bootstrapped
     * game — every unit test over {@link #shouldRefresh} dies with
     * {@code ExceptionInInitializerError}.
     * {@code getDefaultState()} returns the interned instance every time, so
     * calling it per position is a field read and the identity comparison in
     * the delta pass still short-circuits.
     *
     * <h2>Why still level 15</h2>
     * The tester's bright-forest destination read hot, and the obvious lever
     * is {@code Blocks.LIGHT}'s level property. It is the wrong one to pull,
     * because the same change that fixed the flicker already cut the light
     * hard in two better ways:
     * <ul>
     *   <li><b>7x fewer sources.</b> The layer was the padded first slab layer
     *       (42 positions for the default doorway); it is now the aperture
     *       itself (6).</li>
     *   <li><b>Aimed through the hole.</b> The padded positions sat behind the
     *       frame WALL, lighting the real world from inside it — which is
     *       literally "light coming out of the portal". What is left shines
     *       out of the doorway, which is what a portal to somewhere bright
     *       should do.</li>
     * </ul>
     * Block light decrements one per step and does not pass opaque blocks, so
     * the visible surfaces at the far, lateral edge of an 8-deep preview are
     * 10-12 steps from the nearest source: light 3-5 at level 15, and 0-2 at
     * level 12. Dropping the level would black out exactly the deep periphery
     * 4a exists to rescue, and with 7x fewer sources there is no longer a
     * neighbouring light to make up the difference.
     *
     * If it still reads hot in-game after this change, the level IS the next
     * lever — that cost is the reason it is not this one. Lower it with
     * {@code .with(Properties.LEVEL_15, n)} here and nowhere else.
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
     * Phase 8c: blocks of clearance kept around every body. One covers the
     * step a player takes between refresh passes (4 ticks by default) — with
     * no padding the projection still paints into the cell they are walking
     * into, which collides just the same.
     */
    private static final int BODY_PAD = 1;

    private final Map<BlockPos, BlockState> lastSent = new HashMap<>();
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

    /** Number of positions currently faked on this client. */
    public int projectedCount() {
        return this.lastSent.size();
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

    private void send(ServerPlayerEntity player, ServerWorld sourceWorld, ServerWorld targetWorld,
            ImmersiveSettings settings, ProjectionVolume.TargetMapping mapping, int arrivalY,
            long tick, boolean full) {
        ServerPlayNetworkHandler handler = handlerFor(player);
        if (handler == null) {
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
        // here any more — see "Withdrawn: 4e's depth auto-scaling" in the
        // class comment before adding one back.
        int depth = settings.previewDepth();

        if (full || sideFlip || depth != this.builtDepth || this.volume.isEmpty()) {
            // Restore the old slab before building the new one, or blocks
            // that leave the volume stay faked until the player relogs. The
            // usual reason is a side flip (the player walked round the
            // frame); a depth change now only happens if config is re-read
            // under a live projection.
            restore(player, sourceWorld);
            this.lastSent.clear();
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

        // 4a: the positions directly behind the opening are sent as invisible
        // LIGHT instead of their sampled block, so the preview is lit by its
        // own front face rather than by whatever the SOURCE dimension's sky
        // happens to be doing. Skipped for a one-block-deep slab, where it
        // would leave nothing to look at.
        //
        // View-INDEPENDENT, and that is the whole point: derived from the
        // zone's own geometry, never from the mask. See lightPositions.
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

        // Phase 8c: every cell any player's body occupies (padded by one for
        // the step they take between passes). A fake block here is an
        // unmineable wall only that player can see — see
        // ProjectionVolume.occupiedCells. The viewer is included: their own
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
        //
        // Walking past a portal inverts the whole sightline mask, so every
        // position the player could see needs a correction packet in the SAME
        // pass. Measured live 2026-07-25: "0 of 1056 maskable visible, 984
        // restored" — ~1000 BlockUpdateS2CPackets in one tick, per viewer, per
        // portal, at the default 4-tick interval. That is the multi-second
        // stall, and the backlog is why fake blocks appeared to linger.
        //
        // Acting inside the classification loop would let ITERATION ORDER
        // decide what fits the budget. The rule is that restores outrank
        // sends (a fake block still showing is a wall the player collides
        // with; one not yet sent is merely absent), and that can only be
        // honoured by knowing both totals before spending. The sightline
        // probe — the expensive part — still runs exactly once per position.
        List<BlockPos> pendingRestores = new ArrayList<>();
        List<BlockPos> pendingSendPos = new ArrayList<>();
        List<BlockState> pendingSendState = new ArrayList<>();

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
                unmasked++;
            }
        }
        for (int i = 0; i < allowance.sends(); i++) {
            BlockPos pos = pendingSendPos.get(i);
            BlockState state = pendingSendState.get(i);
            handler.sendPacket(new BlockUpdateS2CPacket(pos, state));
            this.lastSent.put(pos, state);
        }

        // THE APERTURE, in both directions — the light layer and the
        // swirl-killer, which turn out to be the same pass.
        //
        // 4a used to light the preview from the first slab layer BEHIND the
        // opening. That layer sits on whichever side the slab is on, and
        // viewerFarSide flips the slab when a player walks round the frame —
        // so the light flipped with it. Reported in game: "the light seems
        // to flip sides when I move around the portal". Deriving it from the
        // zone (rather than from the mask) fixed an earlier flicker but not
        // this, because the SIDE is still a property of the viewer.
        //
        // The aperture is the one part of the geometry that has no side. It
        // is the same set of cells from everywhere, so light emitted there
        // cannot flip, and it is where a player would say the light is
        // coming from anyway — the portal is the light source.
        //
        // It also solves the arrival side's purple swirl for free. An
        // arrival aperture is real NETHER_PORTAL blocks, so it kept vanilla's
        // texture AND its client-side particle storm in front of the preview;
        // an invisible LIGHT over the top removes both, because a client that
        // believes it is looking at LIGHT has nothing to draw and no
        // randomDisplayTick to run. The real block is untouched, traversal is
        // unaffected, and restore() hands the portal block back (Gotcha #8).
        //
        // Colour is not available: vanilla block light is white, and tinting
        // it needs a shader. Noted for the client mod (PHASE-5 5d).
        BlockState apertureState = apertureState();
        if (apertureState != null) {
            for (BlockPos pos : this.zone.interior) {
                lights++;
                BlockState previous = this.lastSent.get(pos);
                if (previous == apertureState) {
                    continue;
                }
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
     * 4c: is this projection due a delta pass?
     *
     * A stationary player is looking at a view that only changes when the
     * far side does, so their refresh interval is stretched by {@link
     * #STATIONARY_MULTIPLIER} — roughly 75% fewer passes for someone AFK
     * next to a hub portal. Nothing is skipped by doing so: {@code lastSent}
     * stays the authoritative baseline and the next pass sends every
     * position that has changed since, whenever that pass happens.
     *
     * This is also the sightline mask's update rate, which is why the
     * movement test measures the EYE and not the feet — the mask is computed
     * from the eye, and a stretched interval must only ever apply to a
     * viewer whose sightlines have not moved. A MOVING player is back on the
     * configured interval immediately, so the frustum follows them and
     * positions it leaves behind are restored on the same pass.
     *
     * A projection with no baseline yet refreshes at the full rate, so one
     * still waiting on its arrival chunks fills in within a few ticks rather
     * than a few seconds.
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
     * Is this the ARRIVAL direction, whose aperture is made of real portal
     * blocks that would otherwise show through the preview?
     *
     * <p>Decided from the one structural difference between the two
     * directions rather than from a flag: only an arrival aperture's cells
     * are registered portal positions. A source zone's interior carries no
     * portal blocks at all — that invariant is load-bearing elsewhere in the
     * mod (mods/AGENTS.md § Portal system) and is what makes source zones
     * invisible in the first place.
     */
    private boolean overlaysPlane() {
        if (this.zone.interior.isEmpty()) {
            return false;
        }
        return PortalHelper.isRegisteredPortalPosition(
                this.sourceWorldKey, this.zone.interior.iterator().next());
    }

    /**
     * What to paint over the aperture, or null to leave it alone.
     *
     * <p>{@code LIGHT} at the portal's configured {@code lightLevel} — the
     * portal lighting itself, from a set of cells that has no side and so
     * cannot flip as a player walks round. A dimension with
     * {@code lightLevel: 0} wants no glow, and then the only reason left to
     * touch the aperture is an ARRIVAL portal's purple swirl: plain air
     * hides that without adding light. A source zone with no light
     * configured is left entirely alone, which is exactly the old
     * behaviour.
     */
    private BlockState apertureState() {
        int level = this.zone.definition != null
                ? Math.max(0, Math.min(15, this.zone.definition.getLightLevel()))
                : 0;
        if (level > 0) {
            return lightState(level);
        }
        return overlaysPlane() ? Blocks.AIR.getDefaultState() : null;
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
     * Restore the real source-dimension blocks and forget the slab. Safe to
     * call for a disconnected player or one who has changed world — it sends
     * nothing in either case.
     */
    public void cleanup(ServerPlayerEntity player, ServerWorld sourceWorld) {
        restore(player, sourceWorld);
        forget();
    }

    /** Drop all tracking without sending anything. */
    public void forget() {
        this.lastSent.clear();
        this.volume = List.of();
        this.normal = null;
        this.builtDepth = 0;
        this.lastRefreshEye = null;
        this.lastRefreshTick = 0;
        this.lastStationary = false;
    }

    private void restore(ServerPlayerEntity player, ServerWorld sourceWorld) {
        if (this.lastSent.isEmpty()) {
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
    }

    /**
     * Hand one position's REAL block back to the client, never a hardcoded
     * AIR: a projection position that overlaps a real portal block (anchor
     * portals) must come back as the portal block (PLAN.md Gotcha #8).
     *
     * Returns false when nothing was sent because the source chunk is not
     * loaded — reading its state would load it, which the projector must
     * never do (Rule 1). Callers that are dropping the position anyway
     * ignore that; the mask keeps the position in {@code lastSent} so a
     * later pass retries rather than stranding a fake block.
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
            // create=false: returns null for an unloaded chunk instead of
            // synchronously generating it. NEVER pass true here — see
            // ImmersiveProjector's class comment.
            chunk = targetWorld.getChunkManager().getWorldChunk(cx, cz, false);
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
