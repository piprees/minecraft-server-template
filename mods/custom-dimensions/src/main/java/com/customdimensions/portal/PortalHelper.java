package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PortalHelper {
    private static final int MAX_PORTAL_BLOCKS = 128;
    private static final Map<BlockPos, PortalReturnTarget> PORTAL_TARGETS = new HashMap<>();
    private static final Map<RegistryKey<World>, List<PortalZone>> PORTAL_ZONES = new HashMap<>();
    private static final Map<String, Boolean> PLAYER_IN_ZONE = new HashMap<>();
    private static final Map<UUID, PlayerOrigin> PLAYER_ORIGINS = new HashMap<>();
    private static final Map<BlockPos, Integer> PORTAL_FRAMES = new HashMap<>();
    private static Path portalLinksPath;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void setServer(MinecraftServer server) {
        portalLinksPath = server.getRunDirectory().resolve("config").resolve("portal_links.json");
    }

    public static void savePortalLinks() {
        if (portalLinksPath == null) {
            return;
        }
        ArrayList<Map<String, Object>> links = new ArrayList<>();
        for (Map.Entry<BlockPos, PortalReturnTarget> entry : PORTAL_TARGETS.entrySet()) {
            BlockPos pos = entry.getKey();
            PortalReturnTarget target = entry.getValue();
            HashMap<String, Object> link = new HashMap<>();
            link.put("x", pos.getX());
            link.put("y", pos.getY());
            link.put("z", pos.getZ());
            link.put("targetWorld", target.sourceWorld.getValue().toString());
            link.put("sourceY", target.sourceY);
            link.put("color", target.color);
            links.add(link);
        }
        try {
            Files.createDirectories(portalLinksPath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(portalLinksPath)) {
                GSON.toJson(links, writer);
            }
        } catch (IOException ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    public static void loadPortalLinks() {
        PORTAL_TARGETS.clear();
        if (portalLinksPath == null || !Files.exists(portalLinksPath)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(portalLinksPath)) {
            List<Map<String, Object>> links = GSON.fromJson(reader, new TypeToken<List<Map<String, Object>>>() {}.getType());
            if (links == null) {
                return;
            }
            for (Map<String, Object> link : links) {
                int x = ((Number) link.get("x")).intValue();
                int y = ((Number) link.get("y")).intValue();
                int z = ((Number) link.get("z")).intValue();
                RegistryKey<World> sourceWorld = RegistryKey.of(RegistryKeys.WORLD, Identifier.of((String) link.get("targetWorld")));
                int sourceY = link.containsKey("sourceY") ? ((Number) link.get("sourceY")).intValue() : y;
                int color = link.containsKey("color") ? ((Number) link.get("color")).intValue() : 0x8844FF;
                PORTAL_TARGETS.put(new BlockPos(x, y, z), new PortalReturnTarget(sourceWorld, sourceY, color));
            }
        } catch (IOException ignored) {
        }
    }

    public static void registerPortal(BlockPos keyPos, RegistryKey<World> sourceWorld, int sourceY, int color) {
        PORTAL_TARGETS.put(keyPos, new PortalReturnTarget(sourceWorld, sourceY, color));
    }

    public static PortalReturnTarget getPortalTarget(BlockPos keyPos) {
        return PORTAL_TARGETS.get(keyPos);
    }

    public static void unregisterPortal(BlockPos keyPos) {
        PORTAL_TARGETS.remove(keyPos);
    }

    public static boolean wasPlayerInZone(String key) {
        return PLAYER_IN_ZONE.getOrDefault(key, false);
    }

    public static void setPlayerInZone(String key, boolean inZone) {
        PLAYER_IN_ZONE.put(key, inZone);
    }

    public static void setPlayerOrigin(UUID playerUuid, RegistryKey<World> sourceWorld, BlockPos sourcePos) {
        if (sourceWorld != null) {
            PLAYER_ORIGINS.put(playerUuid, new PlayerOrigin(sourceWorld, sourcePos));
        }
    }

    public static PlayerOrigin getPlayerOrigin(UUID playerUuid) {
        return PLAYER_ORIGINS.get(playerUuid);
    }

    public static void registerZone(PortalZone zone) {
        PORTAL_ZONES.computeIfAbsent(zone.sourceWorld, k -> new ArrayList<>()).add(zone);
    }

    public static void removeZone(PortalZone zone) {
        List<PortalZone> zones = PORTAL_ZONES.get(zone.sourceWorld);
        if (zones != null) {
            zones.remove(zone);
        }
    }

    public static List<PortalZone> getSourceZones(RegistryKey<World> world) {
        return PORTAL_ZONES.getOrDefault(world, Collections.emptyList());
    }

    public static boolean isInsideZone(BlockPos pos, PortalZone zone) {
        return zone.interior.contains(pos);
    }

    public static boolean isZoneValid(ServerWorld world, PortalZone zone) {
        Identifier frameId = Identifier.of(zone.definition.getFrameBlock());
        Block frameBlock = Registries.BLOCK.get(frameId);
        if (frameBlock == null) {
            return false;
        }
        return isAreaBoundedByFrame(world, zone.interior, frameBlock, zone.axis);
    }

    public static void clearInteriorPortals(ServerWorld world, PortalZone zone) {
        for (BlockPos p : zone.interior) {
            if (world.getBlockState(p).isOf(Blocks.NETHER_PORTAL)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
            }
        }
    }

    public static int findSafeYOffset(ServerWorld world, Set<BlockPos> templateInterior) {
        int centerX = 0;
        int centerZ = 0;
        int minY = Integer.MAX_VALUE;
        for (BlockPos p : templateInterior) {
            centerX += p.getX();
            centerZ += p.getZ();
            minY = Math.min(minY, p.getY());
        }
        int count = templateInterior.size();
        if (count == 0) {
            return 0;
        }
        centerX /= count;
        centerZ /= count;

        for (int y = minY; y <= world.getBottomY() + world.getHeight() - 1; y++) {
            if (world.getBlockState(new BlockPos(centerX, y, centerZ)).isSolid()) {
                return y + 1 - minY;
            }
        }
        for (int y = minY - 1; y >= world.getBottomY(); y--) {
            if (world.getBlockState(new BlockPos(centerX, y, centerZ)).isSolid()) {
                return y + 1 - minY;
            }
        }
        return 0 - world.getBottomY();
    }

    public static void spawnParticles(ServerWorld world, PortalZone zone) {
        int color = parseColor(zone.definition.getColor());
        for (BlockPos p : zone.interior) {
            world.spawnParticles(
                    new DustParticleEffect(color, 2.0f),
                    p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                    2, 0.4, 0.4, 0.4, 0.01
            );
        }
    }

    public static void spawnTargetPortalParticles(ServerWorld level) {
        for (Map.Entry<BlockPos, PortalReturnTarget> entry : PORTAL_TARGETS.entrySet()) {
            BlockPos p = entry.getKey();
            int color = entry.getValue().color;
            if (!level.getBlockState(p).isOf(Blocks.NETHER_PORTAL)) {
                continue;
            }
            level.spawnParticles(
                    new DustParticleEffect(color, 2.0f),
                    p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                    1, 0.3, 0.3, 0.3, 0.01
            );
        }
        for (Map.Entry<BlockPos, Integer> entry : PORTAL_FRAMES.entrySet()) {
            BlockPos p = entry.getKey();
            int color = entry.getValue();
            level.spawnParticles(
                    new DustParticleEffect(color, 1.5f),
                    p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                    1, 0.2, 0.2, 0.2, 0.01
            );
        }
    }

    public static void createTargetPortal(ServerWorld targetWorld, Set<BlockPos> interior, Direction.Axis axis, PortalDefinition definition, RegistryKey<World> sourceWorld, int sourceY) {
        Identifier frameId = Identifier.of(definition.getFrameBlock());
        Block frameBlock = Registries.BLOCK.get(frameId);
        if (frameBlock == null) {
            frameBlock = Blocks.OBSIDIAN;
        }

        BlockState frameState = frameBlock.getDefaultState();
        BlockState portalState = Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, axis);

        HashSet<BlockPos> interiorSet = new HashSet<>(interior);
        for (BlockPos pos : interior) {
            targetWorld.setBlockState(pos, portalState, 3);
        }

        Direction[] planeDirs = planeDirections(axis);
        for (BlockPos p : interior) {
            for (Direction dir : planeDirs) {
                BlockPos neighbor = p.offset(dir);
                if (!interiorSet.contains(neighbor)) {
                    targetWorld.setBlockState(neighbor, frameState, 3);
                }
            }
        }

        int color = parseColor(definition.getColor());
        for (BlockPos p : interior) {
            registerPortal(p, sourceWorld, sourceY, color);
        }
        for (BlockPos p : interior) {
            for (Direction dir : planeDirs) {
                BlockPos neighbor = p.offset(dir);
                if (!interiorSet.contains(neighbor)) {
                    PORTAL_FRAMES.put(neighbor, color);
                }
            }
        }

        savePortalLinks();
    }

    public static BlockPos findExistingPortal(ServerWorld world, int centerX, int centerY, int centerZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    BlockPos pos = new BlockPos(centerX + dx, centerY + dy, centerZ + dz);
                    if (world.getBlockState(pos).isOf(Blocks.NETHER_PORTAL)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    public static Direction[] planeDirections(Direction.Axis axis) {
        if (axis == Direction.Axis.X) {
            return new Direction[]{Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN};
        }
        return new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN};
    }

    public static boolean isPortalFillable(BlockState state) {
        return state.isAir() || state.isOf(Blocks.CAVE_AIR) || state.isOf(Blocks.LIGHT);
    }

    public static Set<BlockPos> floodFill(ServerWorld world, BlockPos start, Block frameBlock, Direction.Axis axis) {
        HashSet<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        Direction[] directions = planeDirections(axis);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (visited.size() > MAX_PORTAL_BLOCKS) {
                return Collections.emptySet();
            }
            for (Direction dir : directions) {
                BlockPos neighbor = pos.offset(dir);
                if (visited.contains(neighbor)) {
                    continue;
                }
                BlockState state = world.getBlockState(neighbor);
                if (state.getBlock() == frameBlock) {
                    continue;
                }
                if (!isPortalFillable(state)) {
                    return Collections.emptySet();
                }
                visited.add(neighbor);
                queue.add(neighbor);
            }
        }
        return visited;
    }

    public static boolean isAreaBoundedByFrame(ServerWorld world, Set<BlockPos> portalArea, Block frameBlock, Direction.Axis axis) {
        Direction[] directions = planeDirections(axis);
        for (BlockPos pos : portalArea) {
            for (Direction dir : directions) {
                BlockPos neighbor = pos.offset(dir);
                if (portalArea.contains(neighbor) || world.getBlockState(neighbor).getBlock() == frameBlock) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    public static Set<BlockPos> collectPortalArea(ServerWorld world, BlockPos start) {
        BlockState startState = world.getBlockState(start);
        if (!startState.isOf(Blocks.NETHER_PORTAL)) {
            return Collections.emptySet();
        }
        if (!startState.contains(NetherPortalBlock.AXIS)) {
            return Collections.emptySet();
        }

        Direction.Axis axis = startState.get(NetherPortalBlock.AXIS);
        HashSet<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        Direction[] directions = planeDirections(axis);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            for (Direction dir : directions) {
                BlockPos neighbor = pos.offset(dir);
                if (visited.contains(neighbor)) {
                    continue;
                }
                BlockState neighborState = world.getBlockState(neighbor);
                if (!neighborState.isOf(Blocks.NETHER_PORTAL) || !neighborState.contains(NetherPortalBlock.AXIS) || neighborState.get(NetherPortalBlock.AXIS) != axis) {
                    continue;
                }
                visited.add(neighbor);
                queue.add(neighbor);
            }
        }
        return visited;
    }

    public static int parseColor(String hexColor) {
        if (hexColor == null || hexColor.isEmpty()) {
            return 0x8844FF;
        }
        String hex = hexColor.startsWith("#") ? hexColor.substring(1) : hexColor;
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return 0x8844FF;
        }
    }

    public static class PortalReturnTarget {
        public final RegistryKey<World> sourceWorld;
        public final int sourceY;
        public final int color;

        public PortalReturnTarget(RegistryKey<World> sourceWorld, int sourceY, int color) {
            this.sourceWorld = sourceWorld;
            this.sourceY = sourceY;
            this.color = color;
        }
    }

    public static class PlayerOrigin {
        public final RegistryKey<World> world;
        public final BlockPos pos;

        public PlayerOrigin(RegistryKey<World> world, BlockPos pos) {
            this.world = world;
            this.pos = pos;
        }
    }

    public static class PortalZone {
        public final Set<BlockPos> interior;
        public final PortalDefinition definition;
        public final Direction.Axis axis;
        public final RegistryKey<World> sourceWorld;
        public final RegistryKey<World> targetWorld;

        public PortalZone(Set<BlockPos> interior, PortalDefinition definition, Direction.Axis axis, RegistryKey<World> sourceWorld, RegistryKey<World> targetWorld) {
            this.interior = interior;
            this.definition = definition;
            this.axis = axis;
            this.sourceWorld = sourceWorld;
            this.targetWorld = targetWorld;
        }
    }
}
