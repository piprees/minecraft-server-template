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
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.joml.Vector3f;

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
    // Keyed by the world the portal block lives IN, then position. A flat
    // BlockPos key collided across dimensions AND made the per-tick particle
    // pass getBlockState foreign-world positions — which synchronously loads
    // (and keeps re-loading) chunks in worlds the portal isn't even in.
    private static final Map<RegistryKey<World>, Map<BlockPos, PortalReturnTarget>> PORTAL_TARGETS = new HashMap<>();
    // Entries loaded from a pre-world-keyed portal_links.json: position only,
    // world unknown. Claimed into PORTAL_TARGETS on first return-trip lookup
    // (which knows the world the player is standing in), re-persisted with
    // their world from then on. Unclaimed entries survive restarts.
    private static final Map<BlockPos, PortalReturnTarget> LEGACY_PORTAL_TARGETS = new HashMap<>();
    private static final Map<RegistryKey<World>, List<PortalZone>> PORTAL_ZONES = new HashMap<>();
    private static final Map<String, Boolean> PLAYER_IN_ZONE = new HashMap<>();
    private static final Map<UUID, PlayerOrigin> PLAYER_ORIGINS = new HashMap<>();
    private static final Map<RegistryKey<World>, Map<BlockPos, Integer>> PORTAL_FRAMES = new HashMap<>();
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
        for (Map.Entry<RegistryKey<World>, Map<BlockPos, PortalReturnTarget>> worldEntry : PORTAL_TARGETS.entrySet()) {
            for (Map.Entry<BlockPos, PortalReturnTarget> entry : worldEntry.getValue().entrySet()) {
                Map<String, Object> link = linkJson(entry.getKey(), entry.getValue());
                link.put("portalWorld", worldEntry.getKey().getValue().toString());
                links.add(link);
            }
        }
        // Unclaimed legacy entries persist without portalWorld so they keep
        // round-tripping until a return trip claims them.
        for (Map.Entry<BlockPos, PortalReturnTarget> entry : LEGACY_PORTAL_TARGETS.entrySet()) {
            links.add(linkJson(entry.getKey(), entry.getValue()));
        }
        try {
            Files.createDirectories(portalLinksPath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(portalLinksPath)) {
                GSON.toJson(links, writer);
            }
        } catch (IOException ignored) {
        }
    }

    private static Map<String, Object> linkJson(BlockPos pos, PortalReturnTarget target) {
        HashMap<String, Object> link = new HashMap<>();
        link.put("x", pos.getX());
        link.put("y", pos.getY());
        link.put("z", pos.getZ());
        link.put("targetWorld", target.sourceWorld.getValue().toString());
        link.put("sourceY", target.sourceY);
        link.put("color", target.color);
        link.put("cooldown", target.cooldown);
        if (target.particleType != null) {
            link.put("particleType", target.particleType);
        }
        return link;
    }

    public static void loadPortalLinks() {
        PORTAL_TARGETS.clear();
        LEGACY_PORTAL_TARGETS.clear();
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
                int cooldown = link.containsKey("cooldown") ? ((Number) link.get("cooldown")).intValue() : 40;
                String particleType = (String) link.get("particleType");
                PortalReturnTarget target = new PortalReturnTarget(sourceWorld, sourceY, color, cooldown, particleType);
                String portalWorld = (String) link.get("portalWorld");
                if (portalWorld != null) {
                    RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(portalWorld));
                    PORTAL_TARGETS.computeIfAbsent(worldKey, k -> new HashMap<>()).put(new BlockPos(x, y, z), target);
                } else {
                    LEGACY_PORTAL_TARGETS.put(new BlockPos(x, y, z), target);
                }
            }
        } catch (IOException ignored) {
        }
    }

    public static void registerPortal(RegistryKey<World> portalWorld, BlockPos keyPos, RegistryKey<World> sourceWorld, int sourceY, int color, int cooldown, String particleType) {
        PORTAL_TARGETS.computeIfAbsent(portalWorld, k -> new HashMap<>()).put(keyPos, new PortalReturnTarget(sourceWorld, sourceY, color, cooldown, particleType));
    }

    public static PortalReturnTarget getPortalTarget(RegistryKey<World> portalWorld, BlockPos keyPos) {
        Map<BlockPos, PortalReturnTarget> targets = PORTAL_TARGETS.get(portalWorld);
        PortalReturnTarget target = targets != null ? targets.get(keyPos) : null;
        if (target != null) {
            return target;
        }
        target = LEGACY_PORTAL_TARGETS.remove(keyPos);
        if (target != null) {
            // Claim: the caller is standing in this portal, so its world is
            // now known — re-key and persist so the migration sticks.
            PORTAL_TARGETS.computeIfAbsent(portalWorld, k -> new HashMap<>()).put(keyPos, target);
            savePortalLinks();
        }
        return target;
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
            if (isPortalBlock(world.getBlockState(p))) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
        }
    }

    // Fallback arrival height for columns with no surface (void worlds);
    // createTargetPortal lays a floor when nothing solid is underneath.
    public static final int VOID_FALLBACK_Y = 64;

    // Absolute Y a player should stand at when arriving at (centerX, centerZ)
    // — one above the heightmap surface. The caller must pass the SCALED
    // target-world column, not source-portal coordinates. Forces generation
    // of the one target chunk because World.getTopY silently reports bottomY
    // for unloaded chunks, which would put the portal on bedrock.
    public static int findSurfaceY(ServerWorld world, int centerX, int centerZ) {
        int surfaceY = world.getChunk(centerX >> 4, centerZ >> 4)
                .sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, centerX & 15, centerZ & 15) + 1;
        if (surfaceY <= world.getBottomY() + 1) {
            return VOID_FALLBACK_Y;
        }
        // Leave headroom so tall portals never poke out of the build limit.
        return Math.min(surfaceY, world.getTopY() - 8);
    }

    public static void spawnParticles(ServerWorld world, PortalZone zone) {
        ParticleEffect effect = resolveParticleEffect(zone.definition);
        for (BlockPos p : zone.interior) {
            world.spawnParticles(effect,
                    p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                    2, 0.4, 0.4, 0.4, 0.01
            );
        }
    }

    public static void spawnTargetPortalParticles(ServerWorld level) {
        RegistryKey<World> worldKey = level.getRegistryKey();
        Map<BlockPos, PortalReturnTarget> targets = PORTAL_TARGETS.get(worldKey);
        if (targets != null) {
            for (Map.Entry<BlockPos, PortalReturnTarget> entry : targets.entrySet()) {
                BlockPos p = entry.getKey();
                // Never load chunks for particles: getBlockState on an
                // unloaded chunk loads it synchronously, and doing that every
                // tick kept portal chunks permanently hot. No loaded chunk =
                // no players near enough to see particles anyway.
                if (!level.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
                    continue;
                }
                PortalReturnTarget rt = entry.getValue();
                if (!isPortalBlock(level.getBlockState(p))) {
                    continue;
                }
                ParticleEffect effect = resolveParticleFromTarget(rt);
                level.spawnParticles(effect,
                        p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                        1, 0.3, 0.3, 0.3, 0.01
                );
            }
        }
        Map<BlockPos, Integer> frames = PORTAL_FRAMES.get(worldKey);
        if (frames != null) {
            for (Map.Entry<BlockPos, Integer> entry : frames.entrySet()) {
                BlockPos p = entry.getKey();
                if (!level.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
                    continue;
                }
                int color = entry.getValue();
                level.spawnParticles(
                        new DustParticleEffect(toDustColor(color), 1.5f),
                        p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                        1, 0.2, 0.2, 0.2, 0.01
                );
            }
        }
    }

    public static void createTargetPortal(ServerWorld targetWorld, Set<BlockPos> interior, Direction.Axis axis, PortalDefinition definition, RegistryKey<World> sourceWorld, int sourceY) {
        Identifier frameId = Identifier.of(definition.getFrameBlock());
        Block frameBlock = Registries.BLOCK.get(frameId);
        if (frameBlock == null) {
            frameBlock = Blocks.OBSIDIAN;
        }

        BlockState frameState = frameBlock.getDefaultState();
        BlockState portalState = axis == Direction.Axis.Y
            ? Blocks.END_PORTAL.getDefaultState()
            : Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, axis);

        // Suppress neighbour updates for ALL arrival-portal blocks (frame
        // AND portal). NOTIFY_NEIGHBORS cascades to adjacent pistons, and
        // Supplementaries' captureBeForPistonMove mixin NPEs when a
        // scheduled piston tick fires with movingPiston=null — crash on
        // every portal traversal near a piston mechanism. Frame blocks at
        // the arrival site are structural; they don't need the surrounding
        // terrain to react to their placement.
        int frameFlags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        HashSet<BlockPos> interiorSet = new HashSet<>(interior);
        Direction[] planeDirs = planeDirections(axis);
        for (BlockPos p : interior) {
            for (Direction dir : planeDirs) {
                BlockPos neighbor = p.offset(dir);
                if (!interiorSet.contains(neighbor)) {
                    targetWorld.setBlockState(neighbor, frameState, frameFlags);
                }
            }
        }

        if (axis == Direction.Axis.Y) {
            for (BlockPos p : interior) {
                BlockPos below = p.down();
                if (!targetWorld.getBlockState(below).isSolid()) {
                    targetWorld.setBlockState(below, frameState, frameFlags);
                }
            }
        }

        int portalFlags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        for (BlockPos pos : interior) {
            targetWorld.setBlockState(pos, portalState, portalFlags);
        }

        int color = parseColor(definition.getColor());
        int cooldown = definition.getCooldown();
        String particleType = definition.getParticleType();
        RegistryKey<World> portalWorld = targetWorld.getRegistryKey();
        for (BlockPos p : interior) {
            registerPortal(portalWorld, p, sourceWorld, sourceY, color, cooldown, particleType);
        }
        Map<BlockPos, Integer> frames = PORTAL_FRAMES.computeIfAbsent(portalWorld, k -> new HashMap<>());
        for (BlockPos p : interior) {
            for (Direction dir : planeDirs) {
                BlockPos neighbor = p.offset(dir);
                if (!interiorSet.contains(neighbor)) {
                    frames.put(neighbor, color);
                }
            }
        }

        savePortalLinks();
    }

    // radiusV is wider than radiusH so a portal built when the surface sat a
    // few blocks higher or lower (chunk regen, terrain edits) is still found
    // and reused instead of double-created.
    public static BlockPos findExistingPortal(ServerWorld world, int centerX, int centerY, int centerZ, int radiusH, int radiusV, Direction.Axis axis) {
        for (int dx = -radiusH; dx <= radiusH; dx++) {
            for (int dz = -radiusH; dz <= radiusH; dz++) {
                for (int dy = -radiusV; dy <= radiusV; dy++) {
                    BlockPos pos = new BlockPos(centerX + dx, centerY + dy, centerZ + dz);
                    BlockState state = world.getBlockState(pos);
                    if (axis == Direction.Axis.Y) {
                        if (state.isOf(Blocks.END_PORTAL)) {
                            return pos;
                        }
                        continue;
                    }
                    if (state.isOf(Blocks.NETHER_PORTAL) && state.contains(NetherPortalBlock.AXIS) && state.get(NetherPortalBlock.AXIS) == axis) {
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
        if (axis == Direction.Axis.Y) {
            return new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
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
        if (startState.isOf(Blocks.END_PORTAL)) {
            return collectHorizontalPortalArea(world, start);
        }
        if (!startState.isOf(Blocks.NETHER_PORTAL)) {
            return Collections.emptySet();
        }
        if (!startState.contains(NetherPortalBlock.AXIS)) {
            return Collections.emptySet();
        }

        Direction.Axis axis = getEffectiveAxis(world, start);
        if (axis == null) {
            axis = startState.get(NetherPortalBlock.AXIS);
        }

        Direction.Axis effectiveAxis = axis;
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
                if (!neighborState.isOf(Blocks.NETHER_PORTAL) || !neighborState.contains(NetherPortalBlock.AXIS)) {
                    continue;
                }
                Direction.Axis neighborAxis = neighborState.get(NetherPortalBlock.AXIS);
                if (effectiveAxis == Direction.Axis.Y) {
                    if (neighborAxis != Direction.Axis.X && neighborAxis != Direction.Axis.Z) {
                        continue;
                    }
                } else if (neighborAxis != effectiveAxis) {
                    continue;
                }
                visited.add(neighbor);
                queue.add(neighbor);
            }
        }
        return visited;
    }

    private static Set<BlockPos> collectHorizontalPortalArea(ServerWorld world, BlockPos start) {
        HashSet<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        Direction[] directions = planeDirections(Direction.Axis.Y);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            for (Direction dir : directions) {
                BlockPos neighbor = pos.offset(dir);
                if (visited.contains(neighbor)) {
                    continue;
                }
                if (!world.getBlockState(neighbor).isOf(Blocks.END_PORTAL)) {
                    continue;
                }
                visited.add(neighbor);
                queue.add(neighbor);
            }
        }
        return visited;
    }

    private static Direction.Axis getEffectiveAxis(ServerWorld world, BlockPos pos) {
        for (List<PortalZone> zones : PORTAL_ZONES.values()) {
            for (PortalZone zone : zones) {
                if (zone.axis == Direction.Axis.Y && zone.interior.contains(pos)) {
                    return Direction.Axis.Y;
                }
            }
        }
        return null;
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

    public static boolean isPortalBlock(BlockState state) {
        return state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.END_PORTAL);
    }

    private static ParticleEffect resolveParticleFromTarget(PortalReturnTarget target) {
        if (target.particleType != null && !target.particleType.isEmpty()) {
            ParticleEffect resolved = resolveParticleById(target.particleType);
            if (resolved != null) {
                return resolved;
            }
        }
        return new DustParticleEffect(toDustColor(target.color), 2.0f);
    }

    private static ParticleEffect resolveParticleById(String typeName) {
        Identifier particleId = Identifier.tryParse(typeName);
        if (particleId != null) {
            ParticleType<?> type = Registries.PARTICLE_TYPE.get(particleId);
            if (type instanceof ParticleEffect effect) {
                return effect;
            }
        }
        return null;
    }

    private static ParticleEffect resolveParticleEffect(PortalDefinition def) {
        String typeName = def.getParticleType();
        if (typeName != null && !typeName.isEmpty()) {
            ParticleEffect resolved = resolveParticleById(typeName);
            if (resolved != null) {
                return resolved;
            }
        }
        return new DustParticleEffect(toDustColor(parseColor(def.getColor())), 2.0f);
    }

    private static Vector3f toDustColor(int color) {
        return new Vector3f(
                ((color >> 16) & 0xFF) / 255.0f,
                ((color >> 8) & 0xFF) / 255.0f,
                (color & 0xFF) / 255.0f
        );
    }

    public static class PortalReturnTarget {
        public final RegistryKey<World> sourceWorld;
        public final int sourceY;
        public final int color;
        public final int cooldown;
        public final String particleType;

        public PortalReturnTarget(RegistryKey<World> sourceWorld, int sourceY, int color, int cooldown) {
            this(sourceWorld, sourceY, color, cooldown, null);
        }

        public PortalReturnTarget(RegistryKey<World> sourceWorld, int sourceY, int color, int cooldown, String particleType) {
            this.sourceWorld = sourceWorld;
            this.sourceY = sourceY;
            this.color = color;
            this.cooldown = cooldown;
            this.particleType = particleType;
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
