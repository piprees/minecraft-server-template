package com.customdimensions.immersive;

import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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
 */
public final class PlayerProjectionState {

    private final UUID playerId;
    private final String playerName;
    private final PortalHelper.PortalZone zone;
    private final RegistryKey<World> sourceWorldKey;

    /** Last state sent per source position — the delta baseline. */
    private final Map<BlockPos, BlockState> lastSent = new HashMap<>();
    /** Side of the portal plane the slab currently sits on (null = none yet). */
    private Direction normal;
    private List<BlockPos> volume = List.of();

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
            ImmersiveSettings settings, ProjectionVolume.TargetMapping mapping, int arrivalY) {
        send(player, sourceWorld, targetWorld, settings, mapping, arrivalY, true);
    }

    /** Periodic refresh: send only positions whose target block changed. */
    public void sendDelta(ServerPlayerEntity player, ServerWorld sourceWorld, ServerWorld targetWorld,
            ImmersiveSettings settings, ProjectionVolume.TargetMapping mapping, int arrivalY) {
        send(player, sourceWorld, targetWorld, settings, mapping, arrivalY, false);
    }

    private void send(ServerPlayerEntity player, ServerWorld sourceWorld, ServerWorld targetWorld,
            ImmersiveSettings settings, ProjectionVolume.TargetMapping mapping, int arrivalY, boolean full) {
        ServerPlayNetworkHandler handler = handlerFor(player);
        if (handler == null) {
            return;
        }
        Direction wanted = ProjectionVolume.viewerFarSide(
                this.zone.interior, this.zone.axis, player.getBlockPos(), this.normal);
        if (full || wanted != this.normal || this.volume.isEmpty()) {
            // Side flip (the player walked round the frame): restore the old
            // slab before building the new one, or the blocks behind them
            // stay faked until they relog.
            restore(player, sourceWorld);
            this.lastSent.clear();
            this.normal = wanted;
            this.volume = ProjectionVolume.computeSourcePositions(this.zone.interior, this.zone.axis,
                    wanted, settings.previewDepth(), settings.previewRadius());
        }

        // Chunk lookups are cached per pass; nulls are cached too, so an
        // unloaded target chunk costs one lookup per pass, not one per block.
        Map<Long, WorldChunk> chunks = new HashMap<>();
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
            BlockState previous = this.lastSent.get(pos);
            if (previous == state) {
                continue;
            }
            handler.sendPacket(new BlockUpdateS2CPacket(pos, state));
            this.lastSent.put(pos, state);
        }
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
