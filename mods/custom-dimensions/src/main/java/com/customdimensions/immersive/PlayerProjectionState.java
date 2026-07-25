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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * The budget is fine without batching: the worst-case initial send for the
 * default 2x3 doorway at depth 8 / radius 2 is 336 positions x ~14 bytes
 * ~= 5 KB, once per activation. Steady state is near zero because the delta
 * pass only sends positions whose target block actually changed.
 *
 * <h2>Player identity</h2>
 * The live {@link ServerPlayerEntity} is passed in on every call rather than
 * held: a respawn replaces the entity while keeping the UUID, and a cached
 * reference would leave the projection tracking a removed entity's position.
 *
 * <h2>Phase 4: three block states, not two</h2>
 * Everything this class reads from the target world is one of THREE things,
 * and collapsing them to two is the bug this feature keeps rediscovering:
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
 * its way in. {@link #decideDepth} therefore never counts unknown as air,
 * and declines to decide at all until most of the layer is known.
 */
public final class PlayerProjectionState {

    /**
     * 4e: depth used when the far side turns out to be mostly empty. Deep
     * previews of a void dimension read as a bug ("the portal shows
     * nothing"); two blocks reads as a boundary.
     */
    static final int SHALLOW_DEPTH = 2;

    /**
     * 4c: squared movement below which a player counts as stationary
     * (0.5 blocks). Generous enough to catch a walking player, tight enough
     * to filter the jitter of standing still.
     */
    static final double STATIONARY_EPSILON_SQ = 0.25;

    /** 4c: refresh interval multiplier applied while stationary. */
    static final int STATIONARY_MULTIPLIER = 4;

    /**
     * 4e outcome. PENDING is not a failure — it means "not enough of the
     * first layer is loaded to judge", and the caller must leave the
     * configured depth alone and ask again on a later pass.
     */
    public enum DepthDecision {
        PENDING,
        SHALLOW,
        FULL
    }

    private final UUID playerId;
    private final String playerName;
    private final PortalHelper.PortalZone zone;
    private final RegistryKey<World> sourceWorldKey;

    /** Last state sent per source position — the delta baseline. */
    private final Map<BlockPos, BlockState> lastSent = new HashMap<>();
    /** Side of the portal plane the slab currently sits on (null = none yet). */
    private Direction normal;
    private List<BlockPos> volume = List.of();
    /** Depth the current {@link #volume} was built with (0 = none yet). */
    private int builtDepth;

    /** 4e: true once the first layer could actually be judged. Sticky. */
    private boolean depthDecided;
    /** 4e: the judged depth. Only meaningful while {@link #depthDecided}. */
    private int decidedDepth;
    /** 4e: one "undecided" log line per projection, not one per pass. */
    private boolean pendingLogged;

    /** 4c: player position and server tick at the last send. */
    private Vec3d lastRefreshPos;
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
        // Chunk lookups are cached per pass; nulls are cached too, so an
        // unloaded target chunk costs one lookup per pass, not one per block.
        // Shared with the 4e depth sampling below, which reads the same
        // columns.
        Map<Long, WorldChunk> chunks = new HashMap<>();

        Direction wanted = ProjectionVolume.viewerFarSide(
                this.zone.interior, this.zone.axis, player.getBlockPos(), this.normal);
        boolean sideFlip = wanted != this.normal;
        if (full || sideFlip) {
            // A different side samples a different column of the target
            // world, so its emptiness is a different question. Re-ask it.
            this.depthDecided = false;
            this.pendingLogged = false;
        }
        int depth = resolveDepth(targetWorld, settings, mapping, arrivalY, wanted, chunks);

        if (full || sideFlip || depth != this.builtDepth || this.volume.isEmpty()) {
            // Restore the old slab before building the new one, or blocks
            // that leave the volume stay faked until the player relogs. Two
            // ways to leave it: a side flip (the player walked round the
            // frame) and a depth change (4e shrinking a mostly-empty view
            // once its first layer finally loaded).
            restore(player, sourceWorld);
            this.lastSent.clear();
            this.normal = wanted;
            this.builtDepth = depth;
            this.volume = ProjectionVolume.computeSourcePositions(this.zone.interior, this.zone.axis,
                    wanted, depth, settings.previewRadius());
        }

        // 4a: the layer nearest the plane is sent as invisible LIGHT instead
        // of its sampled block, so the preview is lit by its own front face
        // rather than by whatever the SOURCE dimension's sky happens to be
        // doing. Skipped for a one-block-deep slab, where it would leave
        // nothing to look at.
        boolean lightLayer = depth >= SHALLOW_DEPTH;
        Direction.Axis normalAxis = wanted.getAxis();
        int firstLayer = ProjectionVolume.firstLayerCoord(this.zone.interior, wanted);

        int bottomY = targetWorld.getBottomY();
        int topY = targetWorld.getTopY();
        for (BlockPos pos : this.volume) {
            BlockPos targetPos = ProjectionVolume.toTarget(pos, mapping, arrivalY);
            if (targetPos.getY() < bottomY || targetPos.getY() >= topY) {
                continue;
            }
            BlockState state = sample(targetWorld, targetPos, chunks);
            if (state == null) {
                // Target chunk not loaded. A position never sent keeps its
                // real source block; one already faked holds its last known
                // state rather than flickering back and forth as the chunk
                // comes and goes. Documented graceful degradation — see
                // ImmersiveProjector for why we never load it ourselves.
                continue;
            }
            if (lightLayer && ProjectionVolume.coordOn(pos, normalAxis) == firstLayer) {
                // Fake, like every other position here: never placed in the
                // world, so no neighbour updates and no piston crash class
                // (PLAN.md Gotcha #2). It IS recorded in lastSent below, so
                // cleanup restores the real block (Gotcha #8). getDefaultState
                // returns the interned level-15 state, so the identity
                // comparison below still short-circuits the delta pass.
                state = Blocks.LIGHT.getDefaultState();
            }
            BlockState previous = this.lastSent.get(pos);
            if (previous == state) {
                continue;
            }
            handler.sendPacket(new BlockUpdateS2CPacket(pos, state));
            this.lastSent.put(pos, state);
        }

        this.lastRefreshPos = player.getPos();
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
     * While 4e's depth question is still open the projection refreshes at
     * the full rate regardless, so a preview waiting on its arrival chunks
     * resolves in a few ticks instead of a few seconds.
     */
    public boolean needsRefresh(ServerPlayerEntity player, long tick, ImmersiveSettings settings) {
        double movedSq = (this.lastRefreshPos == null || !this.depthDecided)
                ? Double.MAX_VALUE
                : player.getPos().squaredDistanceTo(this.lastRefreshPos);
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

    /** Pure 4c predicate: elapsed ticks against the movement-scaled interval. */
    static boolean shouldRefresh(long tick, long lastRefreshTick, double movedSq, int refreshInterval) {
        long interval = Math.max(1, refreshInterval);
        if (movedSq <= STATIONARY_EPSILON_SQ) {
            interval *= STATIONARY_MULTIPLIER;
        }
        return tick - lastRefreshTick >= interval;
    }

    /**
     * 4e: how deep this projection should actually go, sampling the first
     * layer once it is knowable and sticking with the answer thereafter.
     *
     * The decision is deliberately re-asked on every pass until it can be
     * MADE, because "no block here" during the first ticks of a projection
     * means "chunk still loading", not "void dimension". Deciding early on
     * that evidence would shrink every portal on the server to {@link
     * #SHALLOW_DEPTH} the moment it activated, and — since the outcome is
     * sticky — never grow one back. There would be no error, no exception
     * and no failing test: just shallow previews everywhere.
     */
    private int resolveDepth(ServerWorld targetWorld, ImmersiveSettings settings,
            ProjectionVolume.TargetMapping mapping, int arrivalY, Direction normal,
            Map<Long, WorldChunk> chunks) {
        if (this.depthDecided) {
            return this.decidedDepth;
        }
        List<BlockPos> firstLayer = ProjectionVolume.computeSourcePositions(
                this.zone.interior, this.zone.axis, normal, 1, settings.previewRadius());
        int air = 0;
        int solid = 0;
        int unknown = 0;
        int bottomY = targetWorld.getBottomY();
        int topY = targetWorld.getTopY();
        for (BlockPos pos : firstLayer) {
            BlockPos targetPos = ProjectionVolume.toTarget(pos, mapping, arrivalY);
            if (targetPos.getY() < bottomY || targetPos.getY() >= topY) {
                // Outside the target world's height range: no block, but no
                // evidence of emptiness either — the projection skips these
                // positions entirely.
                unknown++;
                continue;
            }
            BlockState state = sample(targetWorld, targetPos, chunks);
            if (state == null) {
                // Chunk not loaded. NOT air. See the class comment.
                unknown++;
            } else if (state.isAir()) {
                air++;
            } else {
                solid++;
            }
        }

        DepthDecision decision = decideDepth(air, solid, unknown);
        if (decision == DepthDecision.PENDING) {
            if (!this.pendingLogged) {
                this.pendingLogged = true;
                MultiverseServer.LOGGER.debug(
                        "immersive: depth undecided for {} at zone {} (air {}, solid {}, unknown {} of {}) "
                                + "— keeping configured {}",
                        this.playerName, this.sourceWorldKey.getValue(), air, solid, unknown,
                        firstLayer.size(), settings.previewDepth());
            }
            return settings.previewDepth();
        }
        this.depthDecided = true;
        this.decidedDepth = decision == DepthDecision.SHALLOW
                ? Math.min(SHALLOW_DEPTH, settings.previewDepth())
                : settings.previewDepth();
        MultiverseServer.LOGGER.debug(
                "immersive: depth decision for {} at zone {} -> {} ({}, configured {}; air {}, solid {}, unknown {} of {})",
                this.playerName, this.sourceWorldKey.getValue(), this.decidedDepth, decision,
                settings.previewDepth(), air, solid, unknown, firstLayer.size());
        return this.decidedDepth;
    }

    /**
     * Pure 4e decision over one layer's air/solid/unknown counts.
     *
     * Two rules, both of which exist to keep an unloaded chunk from reading
     * as an empty dimension:
     * <ol>
     *   <li>at least three quarters of the layer must be KNOWN before any
     *       decision is made — anything less is PENDING, and PENDING means
     *       the caller keeps the configured depth and asks again later;</li>
     *   <li>the &gt;80% air threshold is measured against the KNOWN samples
     *       only. Unknown is never air.</li>
     * </ol>
     * Both thresholds are biased towards PENDING/FULL on purpose: an
     * over-deep preview of an empty dimension is a cosmetic disappointment,
     * while a wrongly shallow one is a silent, sticky, server-wide
     * degradation of the whole feature.
     */
    static DepthDecision decideDepth(int air, int solid, int unknown) {
        int known = air + solid;
        int total = known + unknown;
        if (known <= 0 || total <= 0) {
            return DepthDecision.PENDING;
        }
        if (known * 4 < total * 3) {
            return DepthDecision.PENDING;
        }
        return air * 5 > known * 4 ? DepthDecision.SHALLOW : DepthDecision.FULL;
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
        // The 4e answer belongs to a projection, not to a zone: a state
        // that is being torn down must not hand a stale "that dimension is
        // empty" verdict to whatever rebuilds next.
        this.depthDecided = false;
        this.decidedDepth = 0;
        this.pendingLogged = false;
        this.lastRefreshPos = null;
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
            // Reading a real state must never load a chunk either.
            if (!sourceWorld.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            // Restore the REAL state, never a hardcoded AIR: a projection
            // position that overlaps a real portal block (anchor portals)
            // must come back as the portal block (PLAN.md Gotcha #8).
            handler.sendPacket(new BlockUpdateS2CPacket(pos, sourceWorld.getBlockState(pos)));
        }
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
