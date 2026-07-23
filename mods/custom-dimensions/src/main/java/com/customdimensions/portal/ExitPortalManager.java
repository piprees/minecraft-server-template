package com.customdimensions.portal;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mod-built exit portals ("exitPortal" in the dimension config): a frame at
 * a deterministic offset from dimension spawn, registered as a permanent
 * return zone (exit-mode "bed" by default) and rebuilt whenever it is found
 * broken. This is the guaranteed way home for anchor/single-use dimensions.
 *
 * Like the rest of the portal config this is re-read every boot — enabling
 * an exitPortal on an existing dimension takes effect without a world wipe.
 */
public final class ExitPortalManager {

    // Offset from spawn so the exit frame never fights the anchor arrival
    // portal (whose default pos is also spawn) for the same blocks.
    private static final int SPAWN_OFFSET_X = 8;
    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final int INTERIOR_WIDTH = 2;
    private static final int INTERIOR_HEIGHT = 3;

    private static final Map<RegistryKey<World>, Set<BlockPos>> INTERIORS = new HashMap<>();

    private ExitPortalManager() {
    }

    /** Per-world tick hook (ServerWorldMixin): cheap check, occasional rebuild. */
    public static void tick(ServerWorld world) {
        if (world.getTime() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        DimensionConfig config = MultiverseConfig.getInstance()
                .getDimension(world.getRegistryKey().getValue().getPath());
        if (config == null || !config.hasExitPortal()
                || !MultiverseConfig.getInstance().isManagedNamespace(world.getRegistryKey().getValue().getNamespace())) {
            return;
        }
        ensure(world, config);
    }

    static void ensure(ServerWorld world, DimensionConfig config) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        Set<BlockPos> interior = INTERIORS.get(worldKey);
        if (interior != null) {
            BlockPos any = interior.iterator().next();
            if (!world.getChunkManager().isChunkLoaded(any.getX() >> 4, any.getZ() >> 4)) {
                // Never sync-load a chunk just to inspect the portal; a
                // broken frame in an unloaded chunk gets rebuilt when a
                // player next loads the area.
                return;
            }
            boolean intact = true;
            for (BlockPos p : interior) {
                if (!PortalHelper.isPortalBlock(world.getBlockState(p))) {
                    intact = false;
                    break;
                }
            }
            if (intact) {
                return;
            }
            MultiverseServer.LOGGER.info("Exit portal in {} is broken — rebuilding", worldKey.getValue());
        }

        int[] explicit = config.getExitPortal().getExplicitPos();
        int baseX;
        int baseZ;
        if (explicit != null) {
            baseX = explicit[0];
            baseZ = explicit[2];
        } else {
            int[] spawn = config.getSpawn();
            baseX = (spawn != null ? spawn[0] : 0) + SPAWN_OFFSET_X;
            baseZ = spawn != null ? spawn[2] : 0;
        }
        // Forces generation of the one target chunk (findSurfaceY contract).
        int surfaceY = PortalHelper.findSurfaceY(world, baseX, baseZ);

        // Adopt an existing intact portal near the site before building —
        // otherwise every boot would stack a fresh frame on the old one.
        BlockPos existing = PortalHelper.findExistingPortal(world, baseX, surfaceY, baseZ, 3, 16, Direction.Axis.X);
        Set<BlockPos> newInterior;
        if (existing != null) {
            newInterior = interiorFrom(existing, world);
        } else {
            newInterior = buildFrame(world, config, baseX, surfaceY, baseZ);
            MultiverseServer.LOGGER.info("Built exit portal in {} at ({}, {}, {})",
                    worldKey.getValue(), baseX, surfaceY, baseZ);
        }
        registerExit(world, config, newInterior);
        INTERIORS.put(worldKey, newInterior);
    }

    private static Set<BlockPos> interiorFrom(BlockPos anyPortalBlock, ServerWorld world) {
        Set<BlockPos> collected = PortalHelper.collectPortalArea(world, anyPortalBlock);
        return collected.isEmpty() ? Set.of(anyPortalBlock) : collected;
    }

    // Standard 2x3 X-axis frame from the dimension's own frame block.
    // Frames first, portal blocks last with NOTIFY_LISTENERS | FORCE_STATE —
    // NOTIFY_ALL makes custom-framed portals self-destruct during placement.
    private static Set<BlockPos> buildFrame(ServerWorld world, DimensionConfig config, int x, int y, int z) {
        Block frameBlock = resolveFrameBlock(config);
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        Set<BlockPos> interior = new HashSet<>();
        for (int dx = 0; dx < INTERIOR_WIDTH; dx++) {
            for (int dy = 0; dy < INTERIOR_HEIGHT; dy++) {
                interior.add(new BlockPos(x + dx, y + dy, z));
            }
        }
        for (int dx = -1; dx <= INTERIOR_WIDTH; dx++) {
            for (int dy = -1; dy <= INTERIOR_HEIGHT; dy++) {
                boolean isInterior = dx >= 0 && dx < INTERIOR_WIDTH && dy >= 0 && dy < INTERIOR_HEIGHT;
                if (!isInterior) {
                    world.setBlockState(new BlockPos(x + dx, y + dy, z), frameBlock.getDefaultState(), flags);
                }
            }
        }
        net.minecraft.block.BlockState portalState =
                Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, Direction.Axis.X);
        for (BlockPos p : interior) {
            world.setBlockState(p, portalState, flags);
        }
        return interior;
    }

    private static void registerExit(ServerWorld world, DimensionConfig config, Set<BlockPos> interior) {
        String exitMode = config.getExitPortal().getTargetMode();
        int color = PortalHelper.parseColor(config.hasPortal() ? config.getPortal().color : null);
        int cooldown = config.hasPortal() && config.getPortal().cooldown != null ? config.getPortal().cooldown : 40;
        for (BlockPos p : interior) {
            PortalHelper.registerPortal(world.getRegistryKey(), p, World.OVERWORLD, p.getY(),
                    color, cooldown, config.hasPortal() ? config.getPortal().particleType : null, exitMode);
        }
        PortalHelper.savePortalLinks();
    }

    private static Block resolveFrameBlock(DimensionConfig config) {
        // Building needs a concrete block: framePlaceBlock for tag/list/
        // colour-group frames, the plain frameBlock otherwise (accepting is
        // not placing — see FrameMatcher).
        String id = config.hasPortal() ? config.getPortal().resolvePlacementBlockId() : null;
        Identifier frameId = id != null ? Identifier.tryParse(id) : null;
        Block block = frameId != null ? Registries.BLOCK.get(frameId) : null;
        return block != null && block != Blocks.AIR ? block : Blocks.CRYING_OBSIDIAN;
    }
}
