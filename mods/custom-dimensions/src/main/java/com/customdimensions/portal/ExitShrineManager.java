package com.customdimensions.portal;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exit shrines ("exitShrines" config): adventure:exit_shrine jigsaw ruins
 * carry a BEACON buried under a crying-obsidian portal frame. Chunk-load
 * detection finds the beacon (block entities are a cheap per-chunk map),
 * verifies the frame ring in whichever of the four jigsaw rotations it
 * stands, then — from the world tick, never the load event — lights the
 * interior and registers it as a permanent exit zone with the configured
 * target (default "bed", ExitTarget grammar: any dimension works).
 *
 * Registration is idempotent: re-detecting a lit shrine re-registers the
 * same interior positions over themselves and re-lights no-op states.
 * Template contract (scripts/gen-exit-shrine.py): beacon under the first
 * interior column, second column one step along the frame axis, interior
 * 2 wide x 3 tall, rails at beacon+1 and beacon+5.
 */
public final class ExitShrineManager {

    /** worldKey -> beacon positions awaiting main-tick processing. */
    private static final Map<RegistryKey<World>, Set<BlockPos>> PENDING = new ConcurrentHashMap<>();

    private ExitShrineManager() {
    }

    /** CHUNK_LOAD hook: detect beacons cheaply, queue for the tick pass. */
    public static void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        RegistryKey<World> key = world.getRegistryKey();
        if (!MultiverseConfig.getInstance().isManagedNamespace(key.getValue().getNamespace())) {
            return;
        }
        DimensionConfig def = DimensionManager.getInstance().resolveDefinition(key.getValue().getPath());
        if (def == null || !def.hasExitShrines()) {
            return;
        }
        for (BlockPos pos : chunk.getBlockEntityPositions()) {
            if (chunk.getBlockState(pos).isOf(Blocks.BEACON)) {
                PENDING.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(pos.toImmutable());
            }
        }
    }

    /** World-tick drain (ServerWorldMixin): light + register queued shrines. */
    public static void processQueued(ServerWorld world) {
        Set<BlockPos> pending = PENDING.remove(world.getRegistryKey());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        DimensionConfig def = DimensionManager.getInstance()
                .resolveDefinition(world.getRegistryKey().getValue().getPath());
        if (def == null || !def.hasExitShrines()) {
            return;
        }
        for (BlockPos beacon : pending) {
            registerShrineAt(world, def, beacon);
        }
    }

    private static void registerShrineAt(ServerWorld world, DimensionConfig def, BlockPos beacon) {
        if (!world.getBlockState(beacon).isOf(Blocks.BEACON)) {
            return;  // broken/stale between detection and drain
        }
        // The template's frame axis rotates with jigsaw placement: the second
        // interior column sits one step along ±X or ±Z from the beacon.
        for (Direction dir : new Direction[]{Direction.EAST, Direction.WEST,
                                             Direction.SOUTH, Direction.NORTH}) {
            Set<BlockPos> interior = new HashSet<>();
            for (int dy = 2; dy <= 4; dy++) {
                interior.add(beacon.up(dy));
                interior.add(beacon.up(dy).offset(dir));
            }
            if (!frameRingIntact(world, beacon, dir)) {
                continue;
            }
            Block frame = world.getBlockState(beacon.up()).getBlock();
            int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
            net.minecraft.block.BlockState portalState = Blocks.NETHER_PORTAL.getDefaultState()
                    .with(NetherPortalBlock.AXIS, dir.getAxis());
            for (BlockPos p : interior) {
                world.setBlockState(p, portalState, flags);
            }
            String exitMode = def.getExitShrines().getTargetMode();
            int color = PortalHelper.parseColor(def.hasPortal() ? def.getPortal().color : null);
            int cooldown = def.hasPortal() && def.getPortal().cooldown != null
                    ? def.getPortal().cooldown : 40;
            for (BlockPos p : interior) {
                PortalHelper.registerPortal(world.getRegistryKey(), p, World.OVERWORLD, p.getY(),
                        color, cooldown, def.hasPortal() ? def.getPortal().particleType : null, exitMode);
            }
            PortalHelper.savePortalLinks();
            MultiverseServer.LOGGER.info("Exit shrine registered in {} at {} (frame {}, axis {}) -> {}",
                    world.getRegistryKey().getValue(), beacon.toShortString(),
                    net.minecraft.registry.Registries.BLOCK.getId(frame), dir.getAxis(), exitMode);
            return;
        }
        MultiverseServer.LOGGER.warn(
                "Exit shrine beacon in {} at {} has no intact frame in any rotation — not registered "
                + "(broken ruin, or a player-placed beacon; harmless)",
                world.getRegistryKey().getValue(), beacon.toShortString());
    }

    // Ring for the rotation where the second interior column is at beacon+dir:
    // rails at y+1/y+5 under and over both columns, side columns at -dir and
    // +2*dir for y+2..4. All must be the same (template frame) block and
    // portal-frame-capable — crying obsidian from the shipped template, but
    // any solid block survives a resource-pack retexture era.
    private static boolean frameRingIntact(ServerWorld world, BlockPos beacon, Direction dir) {
        Block frame = world.getBlockState(beacon.up()).getBlock();
        if (frame == Blocks.AIR || frame == Blocks.NETHER_PORTAL) {
            return false;
        }
        for (int step = 0; step <= 1; step++) {
            BlockPos col = beacon.offset(dir, step);
            if (!world.getBlockState(col.up(1)).isOf(frame) || !world.getBlockState(col.up(5)).isOf(frame)) {
                return false;
            }
        }
        for (int dy = 2; dy <= 4; dy++) {
            if (!world.getBlockState(beacon.offset(dir, -1).up(dy)).isOf(frame)
                    || !world.getBlockState(beacon.offset(dir, 2).up(dy)).isOf(frame)) {
                return false;
            }
        }
        return true;
    }
}
