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
        // end_exit dimensions build (and search for) horizontal portals.
        Direction.Axis axis = PortalShape.END_EXIT.equals(shapeOf(config))
                ? Direction.Axis.Y : Direction.Axis.X;
        BlockPos existing = PortalShape.END_GATEWAY.equals(shapeOf(config))
                ? PortalHelper.findExistingGateway(world, baseX, surfaceY, baseZ, 3, 16)
                : PortalHelper.findExistingPortal(world, baseX, surfaceY, baseZ, 3, 16, axis);
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

    // Frame in the dimension's own frame block and portal shape ("door" =
    // 1x2, "doorway"/"standard" = 2x3, "end_exit" = horizontal 3x3 ring).
    // Frames first, portal blocks last with NOTIFY_LISTENERS | FORCE_STATE —
    // NOTIFY_ALL makes custom-framed portals self-destruct during placement.
    private static Set<BlockPos> buildFrame(ServerWorld world, DimensionConfig config, int x, int y, int z) {
        Block frameBlock = resolveFrameBlock(config);
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        String shape = shapeOf(config);
        if (PortalShape.END_EXIT.equals(shape)) {
            return buildHorizontalFrame(world, frameBlock, x, y, z, flags);
        }
        if (PortalShape.END_GATEWAY.equals(shape)) {
            // A single floating gateway — nothing to frame, nothing to decay.
            BlockPos gatewayPos = new BlockPos(x, y, z);
            world.setBlockState(gatewayPos, Blocks.END_GATEWAY.getDefaultState(), flags);
            return Set.of(gatewayPos);
        }
        int width = PortalShape.DOOR.equals(shape) ? 1 : INTERIOR_WIDTH;
        int height = PortalShape.DOOR.equals(shape) ? 2 : INTERIOR_HEIGHT;
        Set<BlockPos> interior = new HashSet<>();
        for (int dx = 0; dx < width; dx++) {
            for (int dy = 0; dy < height; dy++) {
                interior.add(new BlockPos(x + dx, y + dy, z));
            }
        }
        // Per-part materials build the exit frame in kind: the row below
        // the interior is "bottom", above it "top", the rest "sides"
        // (corners follow their row). Uniform frames keep one block.
        com.customdimensions.config.PortalDefinition def =
                config.hasPortal() ? config.toPortalDefinition() : null;
        boolean perPart = def != null && def.hasPartMaterials();
        for (int dx = -1; dx <= width; dx++) {
            for (int dy = -1; dy <= height; dy++) {
                boolean isInterior = dx >= 0 && dx < width && dy >= 0 && dy < height;
                if (!isInterior) {
                    Block block = frameBlock;
                    if (perPart) {
                        String part = dy < 0 ? "bottom" : dy >= height ? "top" : "sides";
                        block = resolvePartBlock(def, part, frameBlock);
                    }
                    world.setBlockState(new BlockPos(x + dx, y + dy, z), block.getDefaultState(), flags);
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

    // Horizontal 3x3 END_PORTAL pad with a frame ring at the same level and
    // a solid floor beneath (same floor rule as createTargetPortal). No
    // centreBlock pedestal here: the intact-check requires every interior
    // cell to be a portal block, and a pedestal cell would read as "broken"
    // and trigger a rebuild loop.
    private static Set<BlockPos> buildHorizontalFrame(ServerWorld world, Block frameBlock,
            int x, int y, int z, int flags) {
        Set<BlockPos> interior = new HashSet<>();
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                interior.add(new BlockPos(x + dx, y, z + dz));
            }
        }
        for (int dx = -1; dx <= 3; dx++) {
            for (int dz = -1; dz <= 3; dz++) {
                boolean isInterior = dx >= 0 && dx < 3 && dz >= 0 && dz < 3;
                if (!isInterior) {
                    world.setBlockState(new BlockPos(x + dx, y, z + dz), frameBlock.getDefaultState(), flags);
                }
            }
        }
        for (BlockPos p : interior) {
            BlockPos below = p.down();
            if (!world.getBlockState(below).isSolid()) {
                world.setBlockState(below, frameBlock.getDefaultState(), flags);
            }
        }
        for (BlockPos p : interior) {
            world.setBlockState(p, Blocks.END_PORTAL.getDefaultState(), flags);
        }
        return interior;
    }

    /**
     * The dimension's portal shape preset ("standard" when unset —
     * pattern-object shapes also build the standard 2x3 exit frame).
     */
    private static String shapeOf(DimensionConfig config) {
        return PortalShape.normalise(config.hasPortal() ? config.getPortal().getShapeName() : null);
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

    private static Block resolvePartBlock(com.customdimensions.config.PortalDefinition def,
            String part, Block fallback) {
        String id = def.getPartPlaceBlock(part);
        Identifier blockId = id != null ? Identifier.tryParse(id) : null;
        Block block = blockId != null ? Registries.BLOCK.get(blockId) : null;
        return block != null && block != Blocks.AIR ? block : fallback;
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
