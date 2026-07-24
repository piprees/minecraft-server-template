package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
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
    // Source zones are restored lazily: a persisted route can be parsed at
    // boot without forcing its source world to load. ServerWorldMixin claims
    // and validates the route on the world's first tick.
    private static final Map<RegistryKey<World>, List<PortalZone>> PENDING_ZONES = new HashMap<>();
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
        ArrayList<Object> links = new ArrayList<>();
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
        for (List<PortalZone> zones : PORTAL_ZONES.values()) {
            for (PortalZone zone : zones) {
                links.add(StoredPortalZone.from(zone));
            }
        }
        for (List<PortalZone> zones : PENDING_ZONES.values()) {
            for (PortalZone zone : zones) {
                links.add(StoredPortalZone.from(zone));
            }
        }
        for (Map.Entry<RegistryKey<World>, Map<BlockPos, AuraSite>> worldSites : AURA_SITES.entrySet()) {
            for (AuraSite site : worldSites.getValue().values()) {
                site.world = worldSites.getKey().getValue().toString();
                links.add(site);
            }
        }
        try {
            Files.createDirectories(portalLinksPath.getParent());
            Path temporary = portalLinksPath.resolveSibling("." + portalLinksPath.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(links, writer);
            }
            try {
                Files.move(temporary, portalLinksPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, portalLinksPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[customdimensions] Failed to save portal links: " + e.getMessage());
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
        if (target.exitMode != null) {
            link.put("exitMode", target.exitMode);
        }
        return link;
    }

    public static void loadPortalLinks() {
        PORTAL_TARGETS.clear();
        LEGACY_PORTAL_TARGETS.clear();
        PORTAL_ZONES.clear();
        PENDING_ZONES.clear();
        AURA_SITES.clear();
        if (portalLinksPath == null || !Files.exists(portalLinksPath)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(portalLinksPath)) {
            List<JsonElement> links = GSON.fromJson(reader, new TypeToken<List<JsonElement>>() {}.getType());
            if (links == null) {
                return;
            }
            for (JsonElement element : links) {
                try {
                    JsonObject link = element.getAsJsonObject();
                    if (link.has("recordType") && "source-zone-v1".equals(link.get("recordType").getAsString())) {
                        StoredPortalZone stored = GSON.fromJson(link, StoredPortalZone.class);
                        PortalZone zone = stored.toPortalZone();
                        PENDING_ZONES.computeIfAbsent(zone.sourceWorld, k -> new ArrayList<>()).add(zone);
                        continue;
                    }
                    if (link.has("recordType") && "aura-site-v1".equals(link.get("recordType").getAsString())) {
                        AuraSite site = GSON.fromJson(link, AuraSite.class);
                        if (site.world != null && site.interior != null && !site.interior.isEmpty()) {
                            RegistryKey<World> worldKey =
                                    RegistryKey.of(RegistryKeys.WORLD, Identifier.of(site.world));
                            BlockPos key = site.interior.get(0).toBlockPos();
                            for (StoredPosition p : site.interior) {
                                BlockPos pos = p.toBlockPos();
                                if (pos.compareTo(key) < 0) {
                                    key = pos;
                                }
                            }
                            AURA_SITES.computeIfAbsent(worldKey, k -> new HashMap<>()).put(key, site);
                        }
                        continue;
                    }
                    int x = link.get("x").getAsInt();
                    int y = link.get("y").getAsInt();
                    int z = link.get("z").getAsInt();
                    RegistryKey<World> sourceWorld = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(link.get("targetWorld").getAsString()));
                    int sourceY = link.has("sourceY") ? link.get("sourceY").getAsInt() : y;
                    int color = link.has("color") ? link.get("color").getAsInt() : 0x8844FF;
                    int cooldown = link.has("cooldown") ? link.get("cooldown").getAsInt() : 40;
                    String particleType = link.has("particleType") ? link.get("particleType").getAsString() : null;
                    String exitMode = link.has("exitMode") ? link.get("exitMode").getAsString() : null;
                    PortalReturnTarget target = new PortalReturnTarget(sourceWorld, sourceY, color, cooldown, particleType, exitMode);
                    String portalWorld = link.has("portalWorld") ? link.get("portalWorld").getAsString() : null;
                    if (portalWorld != null) {
                        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(portalWorld));
                        PORTAL_TARGETS.computeIfAbsent(worldKey, k -> new HashMap<>()).put(new BlockPos(x, y, z), target);
                    } else {
                        LEGACY_PORTAL_TARGETS.put(new BlockPos(x, y, z), target);
                    }
                } catch (RuntimeException e) {
                    System.err.println("[customdimensions] Ignoring malformed portal record: " + e.getMessage());
                }
            }
        } catch (IOException | JsonParseException e) {
            System.err.println("[customdimensions] Failed to load portal links; preserving file for repair: " + e.getMessage());
        }
    }

    public static void registerPortal(RegistryKey<World> portalWorld, BlockPos keyPos, RegistryKey<World> sourceWorld, int sourceY, int color, int cooldown, String particleType) {
        registerPortal(portalWorld, keyPos, sourceWorld, sourceY, color, cooldown, particleType, null);
    }

    public static void registerPortal(RegistryKey<World> portalWorld, BlockPos keyPos, RegistryKey<World> sourceWorld, int sourceY, int color, int cooldown, String particleType, String exitMode) {
        PORTAL_TARGETS.computeIfAbsent(portalWorld, k -> new HashMap<>()).put(keyPos, new PortalReturnTarget(sourceWorld, sourceY, color, cooldown, particleType, exitMode));
    }

    /**
     * Pure lookup: is this position a REGISTERED custom portal block
     * (arrival, anchor, exit-portal, or shrine frame)? Unlike
     * getPortalTarget this never claims legacy entries or saves — it is
     * called from NetherPortalProtectionMixin on every portal-block
     * neighbour update and must stay side-effect free.
     */
    public static boolean isRegisteredPortalPosition(RegistryKey<World> portalWorld, BlockPos pos) {
        Map<BlockPos, PortalReturnTarget> targets = PORTAL_TARGETS.get(portalWorld);
        return (targets != null && targets.containsKey(pos)) || LEGACY_PORTAL_TARGETS.containsKey(pos);
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

    // "bed"/"worldSpawn" exits drop the stored origin so a later
    // "origin"-mode dimension can't resurrect a stale one.
    public static void clearPlayerOrigin(UUID playerUuid) {
        PLAYER_ORIGINS.remove(playerUuid);
    }

    public static void registerZone(PortalZone zone) {
        PORTAL_ZONES.computeIfAbsent(zone.sourceWorld, k -> new ArrayList<>()).add(zone);
        savePortalLinks();
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

    public static void restoreZones(ServerWorld world) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        List<PortalZone> pending = PENDING_ZONES.remove(worldKey);
        if (pending == null) {
            return;
        }
        for (PortalZone zone : pending) {
            if (isZoneValid(world, zone)) {
                PORTAL_ZONES.computeIfAbsent(worldKey, k -> new ArrayList<>()).add(zone);
            } else {
                System.err.println("[customdimensions] Dropped invalid persisted portal route in " + worldKey.getValue());
            }
        }
        savePortalLinks();
    }

    public static boolean isInsideZone(BlockPos pos, PortalZone zone) {
        return zone.interior.contains(pos);
    }

    public static boolean isZoneValid(ServerWorld world, PortalZone zone) {
        // The zone's persisted definition carries the accept forms it was
        // ignited with — validation uses those, not the current config
        // (zones are immutable snapshots of their ignition-time config).
        FrameMatcher matcher = zone.definition.resolveFrameMatcher();
        if (matcher.isEmpty()) {
            return false;
        }
        return isAreaBoundedByFrameParts(world, zone.interior, zone.definition, zone.axis);
    }

    public static void clearInteriorPortals(ServerWorld world, PortalZone zone) {
        for (BlockPos p : zone.interior) {
            if (isPortalBlock(world.getBlockState(p))) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
        }
    }

    // First traversal of a single-use zone: arm the countdown once and
    // persist immediately, so a restart mid-countdown resumes from at most
    // the full delay (shutdown re-saves the exact remaining ticks).
    public static void startSingleUseCountdown(PortalZone zone) {
        if (zone.definition == null || !zone.definition.isSingleUse() || zone.singleUseTicksLeft >= 0) {
            return;
        }
        zone.singleUseTicksLeft = zone.definition.getSingleUseDelayTicks();
        savePortalLinks();
    }

    // EntityTickPortalMixin path: a teleport out of a portal block that sits
    // inside a source zone counts as a traversal of that zone too.
    public static void startSingleUseCountdownAt(ServerWorld world, BlockPos pos) {
        for (PortalZone zone : getSourceZones(world.getRegistryKey())) {
            if (zone.interior.contains(pos)) {
                startSingleUseCountdown(zone);
                return;
            }
        }
    }

    /** Countdown hit zero: clear the interior, break the frame per breakMode. */
    public static void expireSingleUse(ServerWorld world, PortalZone zone) {
        clearInteriorPortals(world, zone);
        removeZone(zone);
        List<BlockPos> frame = collectFramePositions(zone);
        String mode = zone.definition.getSingleUseBreakMode();
        if (frame.isEmpty()) {
            savePortalLinks();
            return;
        }
        if ("partial".equals(mode)) {
            // Seeded from the zone's min corner so the same frame always
            // crumbles the same blocks; the rest stays repairable.
            BlockPos min = frame.get(0);
            long seed = min.getX() * 341873128712L + min.getY() * 132897987541L + min.getZ();
            for (int index : PortalDecay.pickPartialIndices(frame.size(), seed)) {
                decayFrameBlock(world, frame.get(index), zone.definition, true);
            }
        } else if ("destroy".equals(mode)) {
            for (BlockPos p : frame) {
                world.breakBlock(p, false);
            }
        } else {
            for (BlockPos p : frame) {
                decayFrameBlock(world, p, zone.definition, false);
            }
        }
        savePortalLinks();
        com.customdimensions.MultiverseServer.LOGGER.info("Single-use portal expired in {} ({} mode)",
                zone.sourceWorld.getValue(), mode);
    }

    // In-plane frame ring, sorted for deterministic partial picks.
    private static List<BlockPos> collectFramePositions(PortalZone zone) {
        Set<BlockPos> frame = new HashSet<>();
        Direction[] planeDirs = planeDirections(zone.axis);
        for (BlockPos p : zone.interior) {
            for (Direction dir : planeDirs) {
                BlockPos neighbor = p.offset(dir);
                if (!zone.interior.contains(neighbor)) {
                    frame.add(neighbor);
                }
            }
        }
        List<BlockPos> sorted = new ArrayList<>(frame);
        sorted.sort(null);
        return sorted;
    }

    // Swap one frame block for its decayed form. Unmapped blocks are removed
    // in "partial" mode (the pick must visibly break) and left alone in
    // "decay" mode. The 2001 world event gives break particles + sound.
    private static void decayFrameBlock(ServerWorld world, BlockPos pos, PortalDefinition definition, boolean removeUnmapped) {
        BlockState state = world.getBlockState(pos);
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        String replacement = PortalDecay.resolve(blockId, definition.getSingleUseDecayMap());
        if (replacement == null) {
            if (removeUnmapped) {
                world.breakBlock(pos, false);
            }
            return;
        }
        Identifier replacementId = Identifier.tryParse(replacement);
        Block replacementBlock = replacementId != null ? Registries.BLOCK.get(replacementId) : null;
        if (replacementBlock == null) {
            return;
        }
        world.syncWorldEvent(2001, pos, Block.getRawIdFromState(state));
        world.setBlockState(pos, replacementBlock.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
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
        createTargetPortal(targetWorld, interior, axis, definition, sourceWorld, sourceY, null);
    }

    public static void createTargetPortal(ServerWorld targetWorld, Set<BlockPos> interior, Direction.Axis axis, PortalDefinition definition, RegistryKey<World> sourceWorld, int sourceY, String exitMode) {
        // Building needs a CONCRETE block: framePlaceBlock (tag/list/group
        // configs), else the plain frameBlock, else obsidian. Accepting is
        // not placing.
        String placeId = definition.getFramePlaceBlock();
        Identifier frameId = placeId != null ? Identifier.tryParse(placeId) : null;
        Block frameBlock = frameId != null ? Registries.BLOCK.get(frameId) : null;
        if (frameBlock == null || frameBlock == Blocks.AIR) {
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
        // Per-part materials build the arrival frame in kind: top/sides/
        // bottom resolve their own placement block (vertical portals only —
        // horizontal frames stay uniform, same rule as validation).
        boolean perPart = definition.hasPartMaterials() && axis != Direction.Axis.Y;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        if (perPart) {
            for (BlockPos p : interior) {
                minY = Math.min(minY, p.getY());
                maxY = Math.max(maxY, p.getY());
            }
        }
        for (BlockPos p : interior) {
            for (Direction dir : planeDirs) {
                BlockPos neighbor = p.offset(dir);
                if (!interiorSet.contains(neighbor)) {
                    BlockState state = perPart
                            ? partFrameState(definition, classifyFramePart(neighbor, minY, maxY), frameState)
                            : frameState;
                    targetWorld.setBlockState(neighbor, state, frameFlags);
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
            registerPortal(portalWorld, p, sourceWorld, sourceY, color, cooldown, particleType, exitMode);
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

    // Placement state for one frame part: the part's own place block when
    // it resolves, else the definition-wide fallback the caller computed.
    private static BlockState partFrameState(PortalDefinition definition, String part, BlockState fallback) {
        String id = definition.getPartPlaceBlock(part);
        Identifier blockId = id != null ? Identifier.tryParse(id) : null;
        Block block = blockId != null ? Registries.BLOCK.get(blockId) : null;
        return block != null && block != Blocks.AIR ? block.getDefaultState() : fallback;
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

    public static Set<BlockPos> floodFill(ServerWorld world, BlockPos start, FrameMatcher frameMatcher, Direction.Axis axis) {
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
                if (frameMatcher.matches(state)) {
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

    public static boolean isAreaBoundedByFrame(ServerWorld world, Set<BlockPos> portalArea, FrameMatcher frameMatcher, Direction.Axis axis) {
        Direction[] directions = planeDirections(axis);
        for (BlockPos pos : portalArea) {
            for (Direction dir : directions) {
                BlockPos neighbor = pos.offset(dir);
                if (portalArea.contains(neighbor) || frameMatcher.matches(world.getBlockState(neighbor))) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Which frame part a ring position belongs to, for per-part material
     * checks: below the interior's lowest row = "bottom", above its
     * highest = "top", everything else (side columns, and any frame block
     * level with the interior of an irregular shape) = "sides".
     */
    public static String classifyFramePart(BlockPos framePos, int interiorMinY, int interiorMaxY) {
        if (framePos.getY() < interiorMinY) {
            return "bottom";
        }
        if (framePos.getY() > interiorMaxY) {
            return "top";
        }
        return "sides";
    }

    /**
     * Per-part frame validation (frameMaterials): every ring position must
     * satisfy the matcher for ITS part. Uniform definitions and horizontal
     * (Y-axis) portals fall back to the union check — per-part top/bottom/
     * sides has no meaning on a flat ring (v1 decision).
     */
    public static boolean isAreaBoundedByFrameParts(ServerWorld world, Set<BlockPos> portalArea,
            PortalDefinition definition, Direction.Axis axis) {
        if (!definition.hasPartMaterials() || axis == Direction.Axis.Y) {
            return isAreaBoundedByFrame(world, portalArea, definition.resolveFrameMatcher(), axis);
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos p : portalArea) {
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
        }
        Direction[] directions = planeDirections(axis);
        for (BlockPos pos : portalArea) {
            for (Direction dir : directions) {
                BlockPos neighbor = pos.offset(dir);
                if (portalArea.contains(neighbor)) {
                    continue;
                }
                String part = classifyFramePart(neighbor, minY, maxY);
                if (!definition.resolvePartMatcher(part).matches(world.getBlockState(neighbor))) {
                    return false;
                }
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
        // "origin" | "bed" | "worldSpawn"; null keeps the legacy behaviour
        // (origin tracking with sourceWorld/sourceY as the fallback).
        public final String exitMode;

        public PortalReturnTarget(RegistryKey<World> sourceWorld, int sourceY, int color, int cooldown) {
            this(sourceWorld, sourceY, color, cooldown, null, null);
        }

        public PortalReturnTarget(RegistryKey<World> sourceWorld, int sourceY, int color, int cooldown, String particleType) {
            this(sourceWorld, sourceY, color, cooldown, particleType, null);
        }

        public PortalReturnTarget(RegistryKey<World> sourceWorld, int sourceY, int color, int cooldown, String particleType, String exitMode) {
            this.sourceWorld = sourceWorld;
            this.sourceY = sourceY;
            this.color = color;
            this.cooldown = cooldown;
            this.particleType = particleType;
            this.exitMode = exitMode;
        }
    }

    public static class StoredPosition {
        int x;
        int y;
        int z;

        StoredPosition() {
        }

        StoredPosition(BlockPos position) {
            this.x = position.getX();
            this.y = position.getY();
            this.z = position.getZ();
        }

        BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    public static class StoredPortalZone {
        String recordType = "source-zone-v1";
        String sourceWorld;
        String targetWorld;
        String axis;
        PortalDefinition definition;
        List<StoredPosition> interior;
        // Remaining single-use countdown ticks at save time; absent/null when
        // the zone has never been traversed. Written at countdown start and
        // again at shutdown so a restart resumes rather than resets.
        Integer singleUseTicksLeft;
        // Aura palettes + budget (plain block ids/ints — downgrade rule).
        List<String> auraPalette;
        List<String> auraFlora;
        List<String> auraTrees;
        List<String> auraFluids;
        Integer auraBudgetSpent;

        StoredPortalZone() {
        }

        static StoredPortalZone from(PortalZone zone) {
            StoredPortalZone stored = new StoredPortalZone();
            stored.sourceWorld = zone.sourceWorld.getValue().toString();
            stored.targetWorld = zone.targetWorld.getValue().toString();
            stored.axis = zone.axis.name();
            stored.definition = zone.definition;
            stored.interior = zone.interior.stream().map(StoredPosition::new).toList();
            if (zone.singleUseTicksLeft >= 0) {
                stored.singleUseTicksLeft = zone.singleUseTicksLeft;
            }
            if (zone.auraPalette != null) {
                stored.auraPalette = zone.auraPalette;
                stored.auraFlora = zone.auraFlora;
                stored.auraTrees = zone.auraTrees;
                stored.auraFluids = zone.auraFluids;
                stored.auraBudgetSpent = zone.auraBudgetSpent > 0 ? zone.auraBudgetSpent : null;
            }
            return stored;
        }

        PortalZone toPortalZone() {
            if (sourceWorld == null || targetWorld == null || axis == null || definition == null || interior == null || interior.isEmpty()) {
                throw new IllegalArgumentException("missing source route fields");
            }
            Set<BlockPos> blocks = new HashSet<>();
            for (StoredPosition position : interior) {
                blocks.add(position.toBlockPos());
            }
            if (blocks.size() > MAX_PORTAL_BLOCKS) {
                throw new IllegalArgumentException("source route exceeds portal size limit");
            }
            PortalZone zone = new PortalZone(
                    blocks,
                    definition,
                    Direction.Axis.valueOf(axis),
                    RegistryKey.of(RegistryKeys.WORLD, Identifier.of(sourceWorld)),
                    RegistryKey.of(RegistryKeys.WORLD, Identifier.of(targetWorld))
            );
            if (singleUseTicksLeft != null && singleUseTicksLeft >= 0) {
                zone.singleUseTicksLeft = singleUseTicksLeft;
            }
            if (auraPalette != null) {
                zone.auraPalette = auraPalette;
                zone.auraFlora = auraFlora;
                zone.auraTrees = auraTrees;
                zone.auraFluids = auraFluids;
                zone.auraBudgetSpent = auraBudgetSpent != null ? auraBudgetSpent : 0;
            }
            return zone;
        }
    }

    /**
     * Arrival-side aura state ("aura-site-v1" records): the source's
     * sampled nature plus the settings snapshot it was linked with. Older
     * jars log these as malformed records and drop them on their next save
     * — noisy but non-fatal (the aura just stops; nothing crashes).
     */
    public static class AuraSite {
        String recordType = "aura-site-v1";
        String world;
        List<StoredPosition> interior;
        public List<String> palette;
        public List<String> flora;
        public List<String> trees;
        public List<String> fluids;
        public PortalDefinition.AuraSettings settings;
        public int budgetSpent;

        public void setInterior(java.util.Collection<BlockPos> positions) {
            this.interior = positions.stream().map(StoredPosition::new).toList();
        }

        public Set<BlockPos> interiorPositions() {
            Set<BlockPos> out = new HashSet<>();
            if (this.interior != null) {
                for (StoredPosition p : this.interior) {
                    out.add(p.toBlockPos());
                }
            }
            return out;
        }
    }

    private static final Map<RegistryKey<World>, Map<BlockPos, AuraSite>> AURA_SITES = new HashMap<>();

    public static Map<RegistryKey<World>, Map<BlockPos, AuraSite>> getAuraSites() {
        return AURA_SITES;
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
        // Single-use countdown: -1 = never traversed, >0 = ticking down
        // (decremented by ServerWorldMixin), 0 = expire this tick.
        public int singleUseTicksLeft = -1;
        // Aura palettes leaked FROM the target side, sampled once at link
        // time (null = not linked yet / aura off). Plain block ids only —
        // the downgrade-parseability rule applies to these fields too.
        public List<String> auraPalette;
        public List<String> auraFlora;
        public List<String> auraTrees;
        public List<String> auraFluids;
        // Lifetime aura conversions spent on the source side (persisted:
        // restarts resume, never re-burn).
        public int auraBudgetSpent;

        public PortalZone(Set<BlockPos> interior, PortalDefinition definition, Direction.Axis axis, RegistryKey<World> sourceWorld, RegistryKey<World> targetWorld) {
            this.interior = interior;
            this.definition = definition;
            this.axis = axis;
            this.sourceWorld = sourceWorld;
            this.targetWorld = targetWorld;
        }
    }
}
