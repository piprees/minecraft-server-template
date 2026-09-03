package com.customdimensions.portal;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.config.MultiverseConfig;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
    // Presentation zones: geometry for a vanillaManaged portal, held so the
    // immersive projector has a plane to draw through. Never persisted and
    // never a source zone — ownership, traversal and the End suppressions all
    // read PORTAL_ZONES, so vanilla keeps both ends of the portal.
    private static final Map<RegistryKey<World>, List<PortalZone>> PRESENTATION_ZONES = new HashMap<>();
    // Arrival zones: the frame this mod built in the destination, around the
    // same empty interior every portal has. PORTAL_TARGETS still carries the
    // per-cell return route; this carries the geometry, so an arrival is
    // validated and broken by its frame exactly like the source that built it.
    private static final Map<RegistryKey<World>, List<PortalZone>> ARRIVAL_ZONES = new HashMap<>();
    private static final Map<RegistryKey<World>, List<PortalZone>> PENDING_ARRIVAL_ZONES = new HashMap<>();
    private static final Map<String, Boolean> PLAYER_IN_ZONE = new HashMap<>();
    private static final Map<UUID, PlayerOrigin> PLAYER_ORIGINS = new HashMap<>();
    // Arrival-portal presence, keyed by entity UUID — see enteredArrivalPortal.
    // Concurrent because both the world tick and the entity tick write it.
    private static final Map<UUID, ArrivalPresence> ARRIVAL_PRESENCE = new java.util.concurrent.ConcurrentHashMap<>();
    private static int lastPresenceSweepTick = 0;
    private static final Map<RegistryKey<World>, Map<BlockPos, Integer>> PORTAL_FRAMES = new HashMap<>();
    private static Path portalLinksPath;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void setServer(MinecraftServer server) {
        setPortalLinksPath(server.getRunDirectory().resolve("config").resolve("portal_links.json"));
    }

    /** Where the persisted routes live. Null (no server yet) makes every save a no-op. */
    static void setPortalLinksPath(Path path) {
        portalLinksPath = path;
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
        for (List<PortalZone> zones : ARRIVAL_ZONES.values()) {
            for (PortalZone zone : zones) {
                links.add(StoredPortalZone.fromArrival(zone));
            }
        }
        for (List<PortalZone> zones : PENDING_ARRIVAL_ZONES.values()) {
            for (PortalZone zone : zones) {
                links.add(StoredPortalZone.fromArrival(zone));
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
        if (target.sourceX != null && target.sourceZ != null) {
            link.put("sourceX", target.sourceX);
            link.put("sourceZ", target.sourceZ);
        }
        return link;
    }

    public static void loadPortalLinks() {
        PORTAL_TARGETS.clear();
        LEGACY_PORTAL_TARGETS.clear();
        PORTAL_ZONES.clear();
        PENDING_ZONES.clear();
        PRESENTATION_ZONES.clear();
        ARRIVAL_ZONES.clear();
        PENDING_ARRIVAL_ZONES.clear();
        AURA_SITES.clear();
        // Deferred breaks are per-session: a boot re-reads the registry from
        // disk, and anything a break already deregistered is simply not in it.
        PENDING_BREAKS.clear();
        // Presence is per-session state about live entities, never persisted:
        // a boot starts with nobody standing anywhere.
        clearArrivalPresence();
        if (portalLinksPath == null || !Files.exists(portalLinksPath)) {
            return;
        }
        Set<String> knownDimensionIds = knownDimensionIds();
        try (BufferedReader reader = Files.newBufferedReader(portalLinksPath)) {
            List<JsonElement> links = GSON.fromJson(reader, new TypeToken<List<JsonElement>>() {}.getType());
            if (links == null) {
                return;
            }
            for (JsonElement element : links) {
                try {
                    JsonObject link = element.getAsJsonObject();
                    if (link.has("recordType")
                            && StoredPortalZone.ARRIVAL_RECORD.equals(link.get("recordType").getAsString())) {
                        StoredPortalZone stored = GSON.fromJson(link, StoredPortalZone.class);
                        List<String> failures = PortalStateValidator.validateZone(stored);
                        if (!failures.isEmpty()) {
                            System.err.println("[customdimensions] Dropping persisted arrival zone in "
                                    + stored.sourceWorld + ": " + String.join("; ", failures));
                            continue;
                        }
                        PortalZone zone = stored.toPortalZone();
                        PENDING_ARRIVAL_ZONES.computeIfAbsent(zone.sourceWorld, k -> new ArrayList<>()).add(zone);
                        continue;
                    }
                    if (link.has("recordType")
                            && StoredPortalZone.SOURCE_RECORD.equals(link.get("recordType").getAsString())) {
                        StoredPortalZone stored = GSON.fromJson(link, StoredPortalZone.class);
                        List<String> failures = PortalStateValidator.validateZone(stored);
                        if (!failures.isEmpty()) {
                            System.err.println("[customdimensions] Dropping persisted portal zone ("
                                    + stored.sourceWorld + " -> " + stored.targetWorld + "): "
                                    + String.join("; ", failures));
                            continue;
                        }
                        if (PortalStateValidator.isOrphanZone(stored, knownDimensionIds)) {
                            System.err.println("[customdimensions] Portal zone " + stored.sourceWorld
                                    + " -> " + stored.targetWorld + " names a dimension not in the "
                                    + "current config — the mod reconciles it as an orphan");
                        }
                        PortalZone zone = stored.toPortalZone();
                        PENDING_ZONES.computeIfAbsent(zone.sourceWorld, k -> new ArrayList<>()).add(zone);
                        continue;
                    }
                    if (link.has("recordType") && "aura-site-v1".equals(link.get("recordType").getAsString())) {
                        AuraSite site = GSON.fromJson(link, AuraSite.class);
                        List<String> failures = PortalStateValidator.validateAuraSite(site);
                        if (!failures.isEmpty()) {
                            System.err.println("[customdimensions] Dropping persisted aura site in "
                                    + site.world + ": " + String.join("; ", failures));
                            continue;
                        }
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
                        continue;
                    }
                    int x = link.get("x").getAsInt();
                    int y = link.get("y").getAsInt();
                    int z = link.get("z").getAsInt();
                    String targetWorldId = link.get("targetWorld").getAsString();
                    String portalWorldId = link.has("portalWorld") ? link.get("portalWorld").getAsString() : null;
                    String exitMode = link.has("exitMode") ? link.get("exitMode").getAsString() : null;
                    List<String> failures = PortalStateValidator.validateLegacyTarget(targetWorldId, portalWorldId, exitMode);
                    if (!failures.isEmpty()) {
                        System.err.println("[customdimensions] Dropping persisted portal target at ("
                                + x + "," + y + "," + z + "): " + String.join("; ", failures));
                        continue;
                    }
                    RegistryKey<World> sourceWorld = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(targetWorldId));
                    int sourceY = link.has("sourceY") ? link.get("sourceY").getAsInt() : y;
                    int color = link.has("color") ? link.get("color").getAsInt() : 0x8844FF;
                    int cooldown = link.has("cooldown") ? link.get("cooldown").getAsInt() : 40;
                    String particleType = link.has("particleType") ? link.get("particleType").getAsString() : null;
                    PortalReturnTarget target = new PortalReturnTarget(sourceWorld, sourceY, color, cooldown, particleType, exitMode);
                    if (link.has("sourceX") && link.has("sourceZ")) {
                        target.sourceX = link.get("sourceX").getAsInt();
                        target.sourceZ = link.get("sourceZ").getAsInt();
                    }
                    if (portalWorldId != null) {
                        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(portalWorldId));
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

    /** Every dimension id the current config can produce, plus the reserved dimensions — for orphan checks. */
    private static Set<String> knownDimensionIds() {
        Set<String> ids = new HashSet<>(DimensionConfig.RESERVED_DIMENSION_IDS);
        for (DimensionConfig config : MultiverseConfig.getInstance().getCustomDimensions()) {
            ids.add(config.getDimensionId());
        }
        return ids;
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

    /**
     * The registered portal block a teleport aimed at this column would land
     * on, or null if there is none in range.
     *
     * <p>An arrival has no blocks in it, so this registry read is the only
     * way one is found at all — by the player path, by the immersive preview
     * and by entity pass-through alike, which is what keeps the three landing
     * on the same portal. No block reads, no chunk access, no mutation: safe
     * from any tick path.
     *
     * <p><b>Search order is load-bearing.</b> dx, then dz, then dy, all
     * ascending, first hit wins — the lexicographic minimum by (x, z, y) over
     * the matches. Note it is NOT {@code BlockPos.compareTo}, whose order is
     * (y, z, x).
     *
     * <p>Legacy position-only records are deliberately excluded: their world
     * is unknown until a return trip claims them, so matching one here could
     * answer with a portal from another dimension.
     */
    public static BlockPos findRegisteredPortalNear(RegistryKey<World> portalWorld,
            int centerX, int centerY, int centerZ, int radiusH, int radiusV) {
        Map<BlockPos, PortalReturnTarget> targets = PORTAL_TARGETS.get(portalWorld);
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        BlockPos best = null;
        for (BlockPos pos : targets.keySet()) {
            if (Math.abs(pos.getX() - centerX) > radiusH
                    || Math.abs(pos.getZ() - centerZ) > radiusH
                    || Math.abs(pos.getY() - centerY) > radiusV) {
                continue;
            }
            if (best == null || comesFirstInScanOrder(pos, best)) {
                best = pos;
            }
        }
        return best;
    }

    /** (x, z, y) ascending — the scan order the three arrival paths share. */
    private static boolean comesFirstInScanOrder(BlockPos candidate, BlockPos current) {
        if (candidate.getX() != current.getX()) {
            return candidate.getX() < current.getX();
        }
        if (candidate.getZ() != current.getZ()) {
            return candidate.getZ() < current.getZ();
        }
        return candidate.getY() < current.getY();
    }

    /**
     * Record which source column an arrival belongs to, for every cell of it.
     * Called straight after {@link #createTargetPortal}; separate from
     * registration so the existing overloads stay untouched.
     */
    public static void setSourceColumn(RegistryKey<World> portalWorld, java.util.Collection<BlockPos> cells,
            int sourceX, int sourceZ) {
        Map<BlockPos, PortalReturnTarget> targets = PORTAL_TARGETS.get(portalWorld);
        if (targets == null) {
            return;
        }
        boolean changed = false;
        for (BlockPos cell : cells) {
            PortalReturnTarget t = targets.get(cell);
            if (t != null && (t.sourceX == null || t.sourceZ == null)) {
                t.sourceX = sourceX;
                t.sourceZ = sourceZ;
                changed = true;
            }
        }
        if (changed) {
            savePortalLinks();
        }
    }

    /**
     * A portal this mod owns: a registered cell, or any portal in a dimension
     * this mod configures — the reserved four included, where an obsidian
     * frame lit with flint and steel is OUR nether portal. Vanilla answers
     * "the Nether" for every portal it is asked about, so it is never asked.
     */
    public static boolean isManagedPortal(RegistryKey<World> portalWorld, BlockPos pos) {
        if (pos != null && getPortalTarget(portalWorld, pos) != null) {
            return true;
        }
        MultiverseConfig config = MultiverseConfig.getInstance();
        if (config.getDimension(portalWorld.getValue()) == null) {
            return false;
        }
        // With a vanillaManaged route configured, a cell in no source zone is
        // vanilla's own portal rather than one this mod declined and left
        // inert, so vanilla picks its destination.
        return !config.hasVanillaManagedPortals() || pos == null || isInSourceZone(portalWorld, pos);
    }

    private static boolean isInSourceZone(RegistryKey<World> portalWorld, BlockPos pos) {
        for (PortalZone zone : getSourceZones(portalWorld)) {
            if (zone.interior.contains(pos)) {
                return true;
            }
        }
        return false;
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
        // Absent means "not in a zone". Nothing else evicts this map, so
        // storing false would grow it by every (player, dimension) pair that
        // ever touched a portal.
        if (inZone) {
            PLAYER_IN_ZONE.put(key, true);
        } else {
            PLAYER_IN_ZONE.remove(key);
        }
    }

    /** Entries currently held — players standing in a source zone right now. */
    public static int trackedZoneOccupants() {
        return PLAYER_IN_ZONE.size();
    }

    // ------------------------------------------------------------------
    // Arrival-portal edge trigger
    // ------------------------------------------------------------------

    /** A record survives this many ticks without a sighting before it is swept. */
    private static final int PRESENCE_TTL_TICKS = 6000;   // 5 minutes
    /** How often the sweep is allowed to run. */
    private static final int PRESENCE_SWEEP_INTERVAL = 600;   // 30 seconds

    /**
     * Where we last saw an entity relative to our arrival portals. Mutated in
     * place rather than replaced so a sighting costs no allocation.
     */
    private static final class ArrivalPresence {
        RegistryKey<World> world;
        int tick;
        boolean inside;

        ArrivalPresence(RegistryKey<World> world, int tick, boolean inside) {
            this.world = world;
            this.tick = tick;
            this.inside = inside;
        }
    }

    /**
     * The entry edge for a RETURN trip out of an arrival portal, shared by the
     * player path ({@code EntityTickPortalMixin}) and the entity path
     * ({@code EntityPassthrough.tryReturnFromArrivalPortal}).
     *
     * <p>Gated on presence, not cooldown, because vanilla's {@code
     * Entity.tryUsePortal} calls {@code resetPortalCooldown()} every tick an
     * entity stands in a portal, re-pinning the cooldown to its default (10
     * for a player, 300 otherwise) before {@code tickPortalCooldown} can take
     * it back down — so it never reaches zero while standing inside, and
     * gating on {@code getPortalCooldown() == 0} would strand anyone who
     * lands in their own arrival portal. The gate is instead: has this entity
     * been somewhere else since it last stood in this portal? Same shape as
     * the source-zone trigger ({@link #wasPlayerInZone}/
     * {@link #setPlayerInZone}).
     *
     * <p>The cooldown is still load-bearing as a seed for a FIRST sighting: a
     * warm cooldown distinguishes "a teleport put me here" from "I
     * materialised here" (an item or mob spawned in the portal, which has no
     * cooldown) — without it, arriving through a portal would fire the return
     * on the very next tick.
     *
     * <p>The player path samples every tick (so {@code world} always follows
     * the player and arrival reads as a first sighting). The entity path
     * samples only while inside one of our portals, inferring "it left" from
     * a gap in sightings.
     *
     * @param world             the world the entity is in right now
     * @param id                entity UUID
     * @param insidePortal      standing in a portal block this tick
     * @param warmPortalCooldown {@code getPortalCooldown() > 0}
     * @param tick              {@code MinecraftServer.getTicks()}
     * @return true on the tick the entity entered the portal, and only then
     */
    public static boolean enteredArrivalPortal(RegistryKey<World> world, UUID id,
            boolean insidePortal, boolean warmPortalCooldown, int tick) {
        // Swept BEFORE the read, so `previous` can never be a record the sweep
        // has just detached from the map (mutating one of those would silently
        // lose the update).
        sweepArrivalPresence(tick);
        ArrivalPresence previous = ARRIVAL_PRESENCE.get(id);
        if (!insidePortal) {
            // Only ever refreshes a record that already exists: an entity that
            // has never been near an arrival portal costs one map lookup and
            // no memory at all.
            if (previous != null) {
                previous.world = world;
                previous.tick = tick;
                previous.inside = false;
            }
            return false;
        }
        boolean firstSightingHere = previous == null || !world.equals(previous.world);
        boolean stillStandingThere = !firstSightingHere && previous.inside && previous.tick >= tick - 1;
        markArrivedInPortal(world, id, tick);
        if (firstSightingHere) {
            return !warmPortalCooldown;
        }
        return !stillStandingThere;
    }

    /**
     * Records an entity as already standing in an arrival portal without
     * firing the edge — "wherever we just put you, you count as being there
     * already".
     *
     * <p>Called immediately after every teleport this mod performs, so a
     * destination that happens to be (or contain) a portal cannot bounce the
     * entity straight back out of it. Belt to {@link #enteredArrivalPortal}'s
     * cooldown braces: this holds even for a portal configured with
     * {@code "cooldown": 0}.
     */
    public static void markArrivedInPortal(RegistryKey<World> world, UUID id, int tick) {
        ArrivalPresence presence = ARRIVAL_PRESENCE.get(id);
        if (presence == null) {
            ARRIVAL_PRESENCE.put(id, new ArrivalPresence(world, tick, true));
            return;
        }
        presence.world = world;
        presence.tick = tick;
        presence.inside = true;
    }

    /**
     * Hands the entry edge back, so the next tick the entity is standing in
     * the portal reads as a fresh entry again.
     *
     * <p>The edge is one-shot: it fires once on entry, then stays quiet. Any
     * path that fires it and then declines to teleport (target world still
     * loading, a chain link that can't resolve yet) must give it back, or the
     * entity waits forever for a retry that was already spent. Consume it
     * only when a teleport actually happens — same rule as {@code
     * ci.cancel()} in {@code EntityTickPortalMixin}. Paths that can never
     * succeed (e.g. a player-only exit mode) keep the edge consumed rather
     * than re-testing every tick.
     */
    public static void rearmArrivalPortalEntry(RegistryKey<World> world, UUID id, int tick) {
        ArrivalPresence presence = ARRIVAL_PRESENCE.get(id);
        if (presence == null) {
            ARRIVAL_PRESENCE.put(id, new ArrivalPresence(world, tick, false));
            return;
        }
        presence.world = world;
        presence.tick = tick;
        presence.inside = false;
    }

    /** Drops one entity's presence record (teleported away for good, tests). */
    public static void forgetArrivalPresence(UUID id) {
        ARRIVAL_PRESENCE.remove(id);
    }

    /** Resets the whole map (boot, server shutdown, tests). */
    public static void clearArrivalPresence() {
        ARRIVAL_PRESENCE.clear();
        lastPresenceSweepTick = 0;
    }

    /** Records currently held — for tests asserting the map does not leak. */
    public static int arrivalPresenceCount() {
        return ARRIVAL_PRESENCE.size();
    }

    /**
     * Drops records nothing has looked at in {@link #PRESENCE_TTL_TICKS}.
     *
     * <p>This is what keeps the map bounded without a disconnect hook, and it
     * is safe to be aggressive about precisely because of the cooldown seed in
     * {@link #enteredArrivalPortal}: an evicted record makes the next sighting
     * a first sighting, and a first sighting is decided correctly by the
     * cooldown either way. Nothing that could still matter is ever old enough
     * to be swept — a record is refreshed every tick an entity is inside a
     * portal, and the longest cooldown vanilla pins is 300 ticks.
     *
     * <p>{@code abs} rather than a plain difference so records written before
     * a tick-counter reset are treated as stale rather than as far-future.
     */
    private static void sweepArrivalPresence(int tick) {
        if (Math.abs(tick - lastPresenceSweepTick) < PRESENCE_SWEEP_INTERVAL) {
            return;
        }
        lastPresenceSweepTick = tick;
        ARRIVAL_PRESENCE.values().removeIf(p -> Math.abs(tick - p.tick) > PRESENCE_TTL_TICKS);
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
        if (!addZoneIfAbsent(zone)) {
            return;
        }
        savePortalLinks();
    }

    /**
     * Adds a zone unless an equivalent one is already registered; returns
     * whether it was actually added.
     *
     * <p>Re-igniting an already-lit frame reuses the existing zone rather
     * than appending a second one over the same interior — without dedup,
     * each re-light would double the portal-particle emissions, the
     * immersive projection, the chunk-ticket holders, and the aura sites for
     * that interior, and the duplicate would survive restarts.
     *
     * <p>Identity is (target world, axis, interior positions) — the same
     * triple that makes two zones behave identically. The source world is
     * implicit in the map key.
     */
    private static boolean addZoneIfAbsent(PortalZone zone) {
        List<PortalZone> zones = PORTAL_ZONES.computeIfAbsent(zone.sourceWorld, k -> new ArrayList<>());
        for (PortalZone existing : zones) {
            if (existing.axis == zone.axis
                    && existing.targetWorld.equals(zone.targetWorld)
                    && existing.interior.equals(zone.interior)) {
                return false;
            }
        }
        zones.add(zone);
        return true;
    }

    public static void removeZone(PortalZone zone) {
        // Immersive previews are per-player fake blocks anchored to this
        // zone. Restore them BEFORE the zone leaves the list — afterwards
        // nothing knows the projection existed and it leaks on every
        // watching client until they relog. No-op for non-immersive zones
        // and outside a running server (unit tests).
        com.customdimensions.immersive.ImmersiveProjector.cleanupZone(zone);
        List<PortalZone> zones = PORTAL_ZONES.get(zone.sourceWorld);
        if (zones != null) {
            zones.remove(zone);
        }
    }

    public static List<PortalZone> getSourceZones(RegistryKey<World> world) {
        return PORTAL_ZONES.getOrDefault(world, Collections.emptyList());
    }

    /**
     * Registers presentation-only geometry for a portal vanilla owns; returns
     * whether one was added. Same (target, axis, interior) identity as
     * {@link #addZoneIfAbsent}. Nothing here reaches portal_links.json: a jar
     * predating presentation zones reads no record for them and so cannot
     * mistake one for a source zone and claim the traversal.
     */
    public static boolean registerPresentationZone(PortalZone zone) {
        List<PortalZone> zones = PRESENTATION_ZONES.computeIfAbsent(zone.sourceWorld, k -> new ArrayList<>());
        for (PortalZone existing : zones) {
            if (existing.axis == zone.axis
                    && existing.targetWorld.equals(zone.targetWorld)
                    && existing.interior.equals(zone.interior)) {
                return false;
            }
        }
        zones.add(zone);
        return true;
    }

    public static List<PortalZone> getPresentationZones(RegistryKey<World> world) {
        return PRESENTATION_ZONES.getOrDefault(world, Collections.emptyList());
    }

    /** Drops the record and its projections. Never touches a block. */
    public static void removePresentationZone(PortalZone zone) {
        com.customdimensions.immersive.ImmersiveProjector.cleanupZone(zone);
        List<PortalZone> zones = PRESENTATION_ZONES.get(zone.sourceWorld);
        if (zones != null) {
            zones.remove(zone);
        }
    }

    /** Everything the projector may draw through: source plus presentation. */
    public static List<PortalZone> getProjectionZones(RegistryKey<World> world) {
        List<PortalZone> presentation = getPresentationZones(world);
        if (presentation.isEmpty()) {
            return getSourceZones(world);
        }
        List<PortalZone> all = new ArrayList<>(getSourceZones(world));
        all.addAll(presentation);
        return all;
    }

    public static void clearPresentationZones() {
        PRESENTATION_ZONES.clear();
    }

    public static void restoreZones(ServerWorld world) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        restoreArrivalZones(world, worldKey);
        restorePendingZones(worldKey, zone -> isZoneValid(world, zone), zone -> clearInteriorPortals(world, zone));
    }

    /**
     * The source-zone half of the restore, over the two world reads it makes:
     * whether a persisted zone's frame still stands, and what a dropped zone
     * leaves standing in its interior.
     */
    static void restorePendingZones(RegistryKey<World> worldKey, Predicate<PortalZone> valid,
            Consumer<PortalZone> clearInterior) {
        List<PortalZone> pending = PENDING_ZONES.remove(worldKey);
        if (pending == null) {
            return;
        }
        for (PortalZone zone : pending) {
            // ImmersiveSettings is transient on PortalDefinition, never
            // serialised into portal_links.json, so the Gson-restored
            // definition's immersive field is always null here. Re-stamp
            // from the live config so a zone ignited before "immersive" was
            // set (or changed) still gets it applied — and so turning it
            // off in config takes effect for existing zones too.
            zone.definition.setImmersive(MultiverseConfig.getInstance().getImmersiveFor(zone.targetWorld));
            if (valid.test(zone)) {
                // Same dedupe as registerZone — an older portal_links.json
                // may still hold duplicate records; collapsing them here and
                // resaving below removes the duplicate for good.
                addZoneIfAbsent(zone);
            } else {
                // A dropped route takes its interior with it. Anything left
                // standing there is an orphan: no zone and no PORTAL_TARGETS
                // entry, which is what the gateway suppression gates on.
                clearInterior.accept(zone);
                System.err.println("[customdimensions] Dropped invalid persisted portal route in " + worldKey.getValue());
            }
        }
        savePortalLinks();
    }

    /**
     * Arrival zones stand in the world their frame is in, so they are claimed
     * on that world's first tick — the same lazy restore source zones get.
     * An arrival whose frame no longer bounds it is closed on the spot.
     */
    private static void restoreArrivalZones(ServerWorld world, RegistryKey<World> worldKey) {
        List<PortalZone> pending = PENDING_ARRIVAL_ZONES.remove(worldKey);
        if (pending == null) {
            return;
        }
        for (PortalZone zone : pending) {
            zone.definition.setImmersive(MultiverseConfig.getInstance().getImmersiveFor(zone.targetWorld));
            addArrivalZoneIfAbsent(zone);
        }
        savePortalLinks();
    }

    /** Arrival zones standing in this world; never the source-side list. */
    public static List<PortalZone> getArrivalZones(RegistryKey<World> world) {
        return ARRIVAL_ZONES.getOrDefault(world, Collections.emptyList());
    }

    /** The arrival zone covering this cell, or null. */
    public static PortalZone arrivalZoneAt(RegistryKey<World> world, BlockPos pos) {
        for (PortalZone zone : getArrivalZones(world)) {
            if (zone.interior.contains(pos)) {
                return zone;
            }
        }
        return null;
    }

    private static boolean addArrivalZoneIfAbsent(PortalZone zone) {
        List<PortalZone> zones = ARRIVAL_ZONES.computeIfAbsent(zone.sourceWorld, k -> new ArrayList<>());
        for (PortalZone existing : zones) {
            if (existing.interior.equals(zone.interior) && existing.axis == zone.axis) {
                return false;
            }
        }
        zones.add(zone);
        return true;
    }

    /**
     * Registers the arrival zone for a frame this mod built (or, on the reuse
     * path, for one it built before arrival zones were recorded). The cells
     * must already be in the return-target map — that is what makes them an
     * arrival rather than an ordinary hole in the ground.
     */
    public static PortalZone ensureArrivalZone(RegistryKey<World> arrivalWorld, Set<BlockPos> interior,
            Direction.Axis axis, PortalDefinition definition, RegistryKey<World> returnWorld) {
        if (interior.isEmpty() || definition == null || returnWorld == null) {
            return null;
        }
        PortalZone existing = arrivalZoneAt(arrivalWorld, interior.iterator().next());
        if (existing != null) {
            return existing;
        }
        PortalZone zone = new PortalZone(new HashSet<>(interior), definition, axis, arrivalWorld, returnWorld);
        zone.definition.setImmersive(MultiverseConfig.getInstance().getImmersiveFor(returnWorld));
        if (addArrivalZoneIfAbsent(zone)) {
            savePortalLinks();
        }
        return zone;
    }

    /**
     * The arrival cells around this one, grown over the REGISTRY rather than
     * over block states — an arrival interior is empty, so there is nothing
     * in the world to flood-fill.
     */
    public static Set<BlockPos> registeredAperture(RegistryKey<World> world, BlockPos seed, Direction.Axis axis) {
        Map<BlockPos, PortalReturnTarget> targets = PORTAL_TARGETS.get(world);
        if (targets == null || !targets.containsKey(seed)) {
            return Set.of();
        }
        Set<BlockPos> aperture = com.customdimensions.immersive.ProjectionVolume.collectAperture(
                seed, planeDirections(axis), targets::containsKey, MAX_PORTAL_BLOCKS);
        return aperture.isEmpty() ? Set.of(seed) : aperture;
    }

    /**
     * The registered arrival cell a player standing here is in — their own
     * block, then one above, then one below, matching the reach the block
     * probe had. Null when they are not in one of our arrivals.
     */
    public static BlockPos arrivalCellNear(RegistryKey<World> world, BlockPos pos) {
        if (isRegisteredPortalPosition(world, pos)) {
            return pos;
        }
        BlockPos up = pos.up();
        if (isRegisteredPortalPosition(world, up)) {
            return up;
        }
        BlockPos down = pos.down();
        return isRegisteredPortalPosition(world, down) ? down : null;
    }

    /**
     * Closes an arrival: deregisters every cell, drops the zone and its
     * projections, and closes the source zone that built it. Blocks are never
     * placed in an arrival, so there is nothing to clear except a fill left
     * by a jar that predates this rule.
     *
     * @return how many cells were deregistered
     */
    public static int closeArrival(ServerWorld world, PortalZone zone) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        Map<BlockPos, PortalReturnTarget> targets = PORTAL_TARGETS.get(worldKey);
        PortalReturnTarget broken = null;
        if (targets != null) {
            Map<BlockPos, Integer> frames = PORTAL_FRAMES.get(worldKey);
            for (BlockPos p : zone.interior) {
                PortalReturnTarget removed = targets.remove(p);
                if (broken == null) {
                    broken = removed;
                }
                if (frames != null) {
                    frames.remove(p);
                }
            }
        }
        clearInteriorPortals(world, zone);
        List<PortalZone> zones = ARRIVAL_ZONES.get(worldKey);
        if (zones != null) {
            zones.remove(zone);
        }
        com.customdimensions.immersive.ImmersiveProjector.cleanupZone(zone);
        int linked = breakLinkedSourceZone(world, broken);
        savePortalLinks();
        com.customdimensions.MultiverseServer.LOGGER.info(
                "Arrival portal broken in {} ({} cells deregistered, {} source zone(s) closed)",
                worldKey.getValue(), zone.interior.size(), linked);
        return zone.interior.size();
    }

    public static boolean isInsideZone(BlockPos pos, PortalZone zone) {
        return zone.interior.contains(pos);
    }

    /**
     * Whether a zone's own chunk is resident. Validating a cold zone would
     * sync-load it from a tick, and a portal nobody is near cannot have been
     * broken since the last time anybody was.
     */
    public static boolean isZoneChunkLoaded(ServerWorld world, PortalZone zone) {
        BlockPos any = zone.interior.isEmpty() ? null : zone.interior.iterator().next();
        return any != null
                && world.getChunkManager().isChunkLoaded(any.getX() >> 4, any.getZ() >> 4);
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

    /**
     * Clears anything left standing in a zone interior: vanilla's own blocks
     * in an adopted portal, or a fill written by a jar that predates the
     * frame-only rule. A portal this mod built has an empty interior, so this
     * is a no-op for one.
     */
    public static void clearInteriorPortals(ServerWorld world, PortalZone zone) {
        for (BlockPos p : zone.interior) {
            if (isVanillaPortalBlock(world.getBlockState(p))) {
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
        // containsId, not a null check: Registries.BLOCK is a DefaultedRegistry,
        // so get() answers minecraft:air for an id that does not exist. Air is
        // also a LEGITIMATE replacement (PortalDecay maps *_planks to it), so
        // only the registry can tell the two apart — and the rule that
        // synthesises "stripped_<log>" can name a block no mod ships. Without
        // this the whole frame is set to air and the portal breaks at both ends.
        Identifier replacementId = Identifier.tryParse(replacement);
        if (replacementId == null || !Registries.BLOCK.containsId(replacementId)) {
            return;
        }
        Block replacementBlock = Registries.BLOCK.get(replacementId);
        world.syncWorldEvent(2001, pos, Block.getRawIdFromState(state));
        world.setBlockState(pos, replacementBlock.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
    }

    // Fallback arrival height for columns with no surface (void worlds);
    // createTargetPortal lays a floor when nothing solid is underneath.
    public static final int VOID_FALLBACK_Y = 64;

    /**
     * Presentation for an arrival whose source world has no portal config —
     * a reserved dimension such as the overworld. Vanilla's portal violet, matching
     * {@link #parseColor}'s own fallback, so the way home reads as an
     * ordinary portal rather than as the dimension you are standing in.
     */
    public static final int NEUTRAL_PORTAL_COLOR = 0x8844FF;

    /** Ticket radius for an arrival column: the site scan and frame reach past its own chunk. */
    private static final int ARRIVAL_TICKET_RADIUS = 3;

    /**
     * The only chunk accessor a tick path may use.
     *
     * <p><b>{@code getWorldChunk(x, z, false)} is not a non-blocking probe.</b>
     * It delegates to {@code ServerChunkManager.getChunk(x, z, FULL, create)},
     * which reaches {@code MainThreadExecutor.runTasks(future::isDone)} and
     * {@code join()} on every path — {@code create} only decides which future
     * it waits on. Once a chunk ticket exists the holder is at a sufficient
     * level, the future is not done, and the call waits for the terrain to
     * generate. This two-argument form is a cache lookup, then
     * {@code getChunkHolder} and {@code getOrNull}: it returns null instead.
     *
     * @return the resident chunk, or null when it is not loaded right now
     */
    public static net.minecraft.world.chunk.WorldChunk residentChunk(ServerWorld world,
            int chunkX, int chunkZ) {
        return world.getChunkManager().getWorldChunk(chunkX, chunkZ);
    }

    /** Whether the chunk holding this block column is resident. Never waits. */
    public static boolean isColumnResident(ServerWorld world, int blockX, int blockZ) {
        return residentChunk(world, blockX >> 4, blockZ >> 4) != null;
    }

    /**
     * The arrival height for a column, or null when the footprint an arrival
     * needs is not resident. Reads nothing cold, and never waits.
     *
     * <p>The guard and the read are one call on purpose. Everything an arrival
     * does to a column blocks on a cold chunk, not just the heightmap:
     * {@code World.getBlockState} resolves through
     * {@code getChunk(..., create=true)} too, so {@code PortalSite}'s site scan
     * parks the main thread just as hard. That is [K1]/[K6] — the watchdog
     * calls the tick crashed and shuts the server down. Splitting them into
     * "check, then read" is what let one caller keep the check and another
     * forget it.
     *
     * <p>Null means NOT YET, never "no surface". It also means
     * {@link #VOID_FALLBACK_Y} can only ever come from a real void column,
     * never from a cold chunk answering {@code bottomY}.
     */
    public static Integer surfaceYIfResident(ServerWorld world, int centerX, int centerZ) {
        if (!arrivalFootprintResident(world, centerX, centerZ)) {
            return null;
        }
        return findSurfaceY(world, centerX, centerZ);
    }

    /**
     * {@link #surfaceYIfResident}, and a chunk ticket when the column is cold
     * so the caller's retry can eventually succeed. Without the ticket a
     * skipped arrival would never load and the player would wait forever.
     */
    public static Integer arrivalSurfaceY(ServerWorld world, int centerX, int centerZ) {
        Integer surfaceY = surfaceYIfResident(world, centerX, centerZ);
        if (surfaceY == null) {
            net.minecraft.util.math.ChunkPos column =
                    new net.minecraft.util.math.ChunkPos(centerX >> 4, centerZ >> 4);
            world.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.PORTAL,
                    column, ARRIVAL_TICKET_RADIUS, new BlockPos(centerX, 0, centerZ));
        }
        return surfaceY;
    }

    /**
     * Every chunk an arrival touches, not just the centre one: the standard
     * interior runs to {@code centre + STANDARD_PAD} and the egress carve one
     * further, so a centre near a chunk edge reads into its neighbour.
     */
    private static boolean arrivalFootprintResident(ServerWorld world, int centerX, int centerZ) {
        for (int chunkX = (centerX - 1) >> 4; chunkX <= (centerX + PortalSite.STANDARD_PAD) >> 4; chunkX++) {
            for (int chunkZ = (centerZ - 1) >> 4; chunkZ <= (centerZ + PortalSite.STANDARD_PAD) >> 4; chunkZ++) {
                if (residentChunk(world, chunkX, chunkZ) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    // Absolute Y a player should stand at when arriving at (centerX, centerZ)
    // — one above the heightmap surface. The caller must pass the SCALED
    // target-world column, not source-portal coordinates. Forces generation
    // of the one target chunk because World.getTopY silently reports bottomY
    // for unloaded chunks, which would put the portal on bedrock — so a
    // caller on a tick path must clear arrivalColumnReady first.
    public static int findSurfaceY(ServerWorld world, int centerX, int centerZ) {
        int surfaceY = world.getChunk(centerX >> 4, centerZ >> 4)
                .sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, centerX & 15, centerZ & 15) + 1;
        if (surfaceY <= world.getBottomY() + 1) {
            return VOID_FALLBACK_Y;
        }
        // Leave headroom so tall portals never poke out of the build limit.
        return Math.min(surfaceY, world.getTopY() - 8);
    }

    /**
     * The opening's own particles.
     *
     * <p>A source zone holds no blocks at all, so these ARE the portal —
     * which is why they must not fill the plane. {@link PortalAperture} plans
     * each pass: rim-weighted, density-scaled and capped, so the dust edges
     * the doorway rather than glazing it, and every particle leaves through
     * the plane instead of hanging in it. An immersive portal takes its
     * colour and brightness from the world on the other side, the visual half
     * of the biome ambience {@code ImmersiveProjector.tickAudio} already
     * leaks back.
     */
    public static void spawnParticles(ServerWorld world, PortalZone zone) {
        ImmersiveSettings immersive = zone.definition != null
                ? zone.definition.getImmersive() : null;
        int interval = immersive != null ? IMMERSIVE_PARTICLE_INTERVAL : PLAIN_PARTICLE_INTERVAL;
        long tick = world.getTime();
        if ((tick + particlePhase(zone, interval)) % interval != 0) {
            return;
        }
        // A plain portal has no projection behind it and no frame glow, so
        // its opening stays denser: the cap alone thins it, and the drift
        // still reads it as an opening rather than a pane.
        double density = immersive != null ? immersive.particleDensity() : PLAIN_DENSITY;
        double edgeBias = immersive != null ? immersive.edgeBias() : PLAIN_EDGE_BIAS;
        emitAperture(world, zone.interior, zone.axis,
                apertureEffect(zone, immersive), density, edgeBias, tick, interval);
    }

    /**
     * One pass of an opening's particles: the planned cells, each leaving
     * through the plane. Shared by both directions, so a source portal and
     * the arrival it built read the same from either side.
     */
    static void emitAperture(ServerWorld world, Set<BlockPos> interior, Direction.Axis axis,
            ParticleEffect effect, double density, double edgeBias, long tick, int interval) {
        List<BlockPos> cells = PortalAperture.emittingCells(interior, axis, tick, density, edgeBias);
        logApertureFill(world, interior, cells.size(), tick, interval);
        if (cells.isEmpty()) {
            return;
        }
        Direction.Axis normal = PortalAperture.normalAxis(axis);
        for (BlockPos p : cells) {
            emitThroughPlane(world, effect, p, normal, PortalAperture.driftSign(tick, p), tick);
        }
    }

    /**
     * Heartbeat for the one property that decides whether an opening reads as
     * a window: how much of the plane a pass filled. Particles leave no
     * server-side trace at all, so this line is the only headless evidence
     * the pass ran and the only way to see the fill share without a human at
     * a screen.
     */
    private static void logApertureFill(ServerWorld world, Set<BlockPos> interior, int emitted,
            long tick, int interval) {
        // A window one emission interval wide, not a single tick: a zone
        // emitting every sixth tick would otherwise only coincide with an
        // exact multiple of the cadence for some of its phase offsets, and
        // the heartbeat would look like a broken pass for the rest.
        if (interior.isEmpty() || tick % APERTURE_LOG_INTERVAL >= Math.max(1, interval)) {
            return;
        }
        BlockPos anchor = interior.iterator().next();
        com.customdimensions.MultiverseServer.LOGGER.debug(
                "aperture: emitted {} of {} cells ({}%) in {} at {}",
                emitted, interior.size(), (emitted * 100) / interior.size(),
                world.getRegistryKey().getValue(), anchor.toShortString());
    }

    /** Heartbeat cadence (10s) for the aperture fill line. */
    private static final int APERTURE_LOG_INTERVAL = 200;

    /**
     * One particle leaving the plane at {@code cell}.
     *
     * <p>Spawned with {@code count = 0}, which is what makes the packet's
     * offset triple a VELOCITY rather than a scatter (verified against
     * {@code ClientPlayNetworkHandler.onParticle} in the 1.21.1 jar), so the
     * dust drifts out of the opening towards whichever side the viewer is
     * on. The scatter that buys is replaced by {@link PortalAperture#jitter}.
     */
    private static void emitThroughPlane(ServerWorld world, ParticleEffect effect, BlockPos cell,
            Direction.Axis normal, int sign, long tick) {
        double x = cell.getX() + 0.5 + (normal == Direction.Axis.X
                ? PortalAperture.jitter(tick, cell, 0, PLANE_JITTER)
                : PortalAperture.jitter(tick, cell, 0, CELL_JITTER));
        double y = cell.getY() + 0.5 + (normal == Direction.Axis.Y
                ? PortalAperture.jitter(tick, cell, 1, PLANE_JITTER)
                : PortalAperture.jitter(tick, cell, 1, CELL_JITTER));
        double z = cell.getZ() + 0.5 + (normal == Direction.Axis.Z
                ? PortalAperture.jitter(tick, cell, 2, PLANE_JITTER)
                : PortalAperture.jitter(tick, cell, 2, CELL_JITTER));
        world.spawnParticles(effect, x, y, z, 0,
                normal == Direction.Axis.X ? sign : 0.0,
                normal == Direction.Axis.Y ? sign : DRIFT_LIFT,
                normal == Direction.Axis.Z ? sign : 0.0,
                DRIFT_SPEED);
    }

    /**
     * The opening's particle, tinted by the far side when there is one to
     * take a colour from. A configured {@code particleType} wins outright —
     * a named effect carries its own colour and is not ours to shade.
     */
    private static ParticleEffect apertureEffect(PortalZone zone, ImmersiveSettings immersive) {
        PortalDefinition def = zone.definition;
        String typeName = def != null ? def.getParticleType() : null;
        if (typeName != null && !typeName.isEmpty()) {
            ParticleEffect resolved = resolveParticleById(typeName);
            if (resolved != null) {
                return resolved;
            }
        }
        int colour = parseColor(def != null ? def.getColor() : null);
        if (immersive != null) {
            colour = com.customdimensions.immersive.ImmersiveProjector.glowFor(zone)
                    .applyTo(colour, immersive.destinationTint(), immersive.destinationLight());
        }
        return new DustParticleEffect(toDustColor(colour), APERTURE_PARTICLE_SCALE);
    }

    /**
     * The return direction's openings: an arrival aperture emitted the same
     * way its source portal is, tinted by the world it leads back to.
     *
     * <p>The colour comes from the arrival's own registered target rather
     * than a definition — an arrival has no portal config of its own — and
     * the density from the settings of the dimension the portal STANDS in,
     * which is what {@code ImmersiveProjector} already projects it with.
     */
    private static void emitImmersiveArrivals(ServerWorld level, RegistryKey<World> worldKey) {
        List<PortalZone> arrivals =
                com.customdimensions.immersive.ImmersiveProjector.immersiveArrivals(worldKey);
        if (arrivals.isEmpty()) {
            return;
        }
        ImmersiveSettings settings =
                com.customdimensions.immersive.ImmersiveProjector.arrivalSettings(worldKey);
        if (settings == null) {
            return;
        }
        long tick = level.getTime();
        for (PortalZone zone : arrivals) {
            if (zone.interior.isEmpty()
                    || (tick + particlePhase(zone, IMMERSIVE_PARTICLE_INTERVAL))
                            % IMMERSIVE_PARTICLE_INTERVAL != 0) {
                continue;
            }
            BlockPos any = zone.interior.iterator().next();
            if (!level.getChunkManager().isChunkLoaded(any.getX() >> 4, any.getZ() >> 4)) {
                continue;
            }
            PortalReturnTarget target = getPortalTarget(worldKey, any);
            int colour = target != null ? target.color : NEUTRAL_PORTAL_COLOR;
            colour = com.customdimensions.immersive.ImmersiveProjector
                    .glowForArrival(worldKey, any)
                    .applyTo(colour, settings.destinationTint(), settings.destinationLight());
            ParticleEffect effect = target != null && target.particleType != null
                    && !target.particleType.isEmpty()
                    ? resolveParticleFromTarget(target)
                    : new DustParticleEffect(toDustColor(colour), APERTURE_PARTICLE_SCALE);
            emitAperture(level, zone.interior, zone.axis, effect,
                    settings.particleDensity(), settings.edgeBias(), tick,
                    IMMERSIVE_PARTICLE_INTERVAL);
        }
    }

    /** Ticks between the thinned particle passes an immersive portal emits. */
    private static final int IMMERSIVE_PARTICLE_INTERVAL = 6;

    /** A plain portal's opening emits every tick; only the cap thins it. */
    private static final int PLAIN_PARTICLE_INTERVAL = 1;

    /** A plain portal asks for every cell and lets {@code emissionCap} decide. */
    private static final double PLAIN_DENSITY = 1.0;
    private static final double PLAIN_EDGE_BIAS = 1.0;

    /**
     * Dust scale for the opening. A dust particle's billboard is
     * {@code 0.75 * scale} of the base sprite and its lifetime scales with it
     * too (both read off {@code AbstractDustParticle}'s constructor), so one
     * number below 1.0 buys a smaller, shorter-lived mote — less of what is
     * behind it covered, for the same emission count.
     */
    private static final float APERTURE_PARTICLE_SCALE = 0.8f;

    /** Spread within a cell, in-plane and across the plane. */
    private static final double CELL_JITTER = 0.42;
    private static final double PLANE_JITTER = 0.12;

    /**
     * Outward drift. The client multiplies the packet's velocity by 0.1 and
     * then decays it 0.96 per tick, so this carries a mote roughly
     * {@code 2.5 * DRIFT_SPEED} blocks clear of the plane over its life.
     */
    private static final double DRIFT_SPEED = 0.25;

    /** A little lift on a vertical plane, so the dust rises out rather than hanging. */
    private static final double DRIFT_LIFT = 0.35;

    /**
     * Per-zone phase offset so several portals in view do not all pulse on
     * the same tick — the same trick the projector's edge particles use.
     */
    private static int particlePhase(PortalZone zone, int interval) {
        BlockPos any = zone.interior.iterator().next();
        return Math.floorMod(any.hashCode(), Math.max(1, interval));
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
                // A fill written by a jar that predates the frame-only rule.
                // An arrival interior is empty, so clear it the first time
                // anyone is near enough for its chunk to be loaded.
                if (isVanillaPortalBlock(level.getBlockState(p))) {
                    level.setBlockState(p, Blocks.AIR.getDefaultState(),
                            Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                }
                // An immersive arrival is an opening, not a set of loose
                // positions: emitApertures below plans the whole aperture at
                // once, rim-weighted and capped, so a per-position spawn here
                // would put back exactly the fill that pass exists to avoid.
                if (com.customdimensions.immersive.ImmersiveProjector.isImmersiveArrival(worldKey, p)) {
                    continue;
                }
                ParticleEffect effect = resolveParticleFromTarget(rt);
                level.spawnParticles(effect,
                        p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                        1, 0.3, 0.3, 0.3, 0.01
                );
            }
        }
        emitImmersiveArrivals(level, worldKey);
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

    // ------------------------------------------------------------------
    // Symmetric breaking — see PortalBreakLink for the rules
    // ------------------------------------------------------------------

    /**
     * Arrival broken -> close the source zone that built it.
     *
     * <p>The source frame is deliberately left standing. Deregistering the
     * zone is what closes the way: the frame becomes ordinary blocks the
     * player can mine, keep, or re-ignite. Breaking somebody's build because
     * they mined the other end of it would be a bigger surprise than the one
     * this fixes, and re-lighting it is a normal thing to want.
     *
     * @return how many source zones were closed (0 or 1 in practice)
     */
    private static int breakLinkedSourceZone(ServerWorld world, PortalReturnTarget broken) {
        if (broken == null || !PortalBreakLink.breaksSymmetrically(broken)) {
            return 0;
        }
        List<PortalZone> zones = PORTAL_ZONES.get(broken.sourceWorld);
        if (zones == null || zones.isEmpty()) {
            return 0;
        }
        RegistryKey<World> arrivalWorld = world.getRegistryKey();
        List<PortalZone> matched = new ArrayList<>();
        for (PortalZone zone : zones) {
            if (zone.definition != null && zone.definition.hasAnchor()) {
                continue;
            }
            if (PortalBreakLink.zoneMatchesColumn(zone.interior, zone.targetWorld, arrivalWorld,
                    broken.sourceX, broken.sourceZ)) {
                matched.add(zone);
            }
        }
        for (PortalZone zone : matched) {
            // The source world may not even be loaded, so never touch blocks
            // here — removeZone is pure memory plus the immersive teardown,
            // both of which are safe from any world's tick.
            removeZone(zone);
        }
        return matched.size();
    }

    /**
     * Source frame broken -> clear the arrival it built.
     *
     * <p>Called from the zone-validity loop, which is the one place that knows
     * a player has broken the frame. Deliberately NOT called from
     * {@link #removeZone}: single-use expiry goes through that too, and "the
     * way in crumbles behind you" must never crumble the way home.
     *
     * <p>The arrival is usually in a different world, and that world may be
     * unloaded or its chunk cold. Registration is dropped immediately (pure
     * memory, always correct) and the BLOCKS are cleared now if the chunk is
     * loaded, or queued for {@link #processPendingBreaks} if not. Never
     * sync-loads a chunk from a tick path — that is the c2me wedge.
     *
     * @return how many arrival cells were deregistered
     */
    public static int breakLinkedArrival(ServerWorld sourceWorld, PortalZone zone) {
        if (zone.definition != null && zone.definition.hasAnchor()) {
            // One arrival shared by every source into that dimension: one
            // player mining their own frame must not take everybody's home.
            return 0;
        }
        int[] centre = PortalBreakLink.centreColumn(zone.interior);
        if (centre == null) {
            return 0;
        }
        Map<BlockPos, PortalReturnTarget> targets = PORTAL_TARGETS.get(zone.targetWorld);
        Set<BlockPos> cells = PortalBreakLink.arrivalCellsFor(
                targets, zone.sourceWorld, centre[0], centre[1]);
        if (cells.isEmpty()) {
            return 0;
        }
        Map<BlockPos, Integer> frames = PORTAL_FRAMES.get(zone.targetWorld);
        for (BlockPos p : cells) {
            targets.remove(p);
            if (frames != null) {
                frames.remove(p);
            }
        }
        PortalZone arrivalZone = arrivalZoneAt(zone.targetWorld, cells.iterator().next());
        if (arrivalZone != null) {
            com.customdimensions.immersive.ImmersiveProjector.cleanupZone(arrivalZone);
            ARRIVAL_ZONES.get(zone.targetWorld).remove(arrivalZone);
        }
        ServerWorld targetWorld = sourceWorld.getServer() != null
                ? sourceWorld.getServer().getWorld(zone.targetWorld)
                : null;
        int cleared = targetWorld != null ? clearLoaded(targetWorld, cells) : 0;
        if (cleared < cells.size()) {
            // Whatever could not be reached now is remembered rather than
            // orphaned: an unregistered portal block left standing is a
            // doorway to nowhere that nothing will ever tidy up.
            PENDING_BREAKS.computeIfAbsent(zone.targetWorld, k -> new HashSet<>()).addAll(cells);
        }
        // Persist immediately. Destroying a portal is a deliberate,
        // player-visible act, and the zone-validity path this runs from has no
        // save of its own — without this the deregistration would live only
        // in memory until shutdown, and a crash in between would bring back a
        // portal the player had already broken.
        savePortalLinks();
        com.customdimensions.MultiverseServer.LOGGER.info(
                "Source portal broken in {} — closed its arrival in {} ({} cells, {} cleared now, {} deferred)",
                zone.sourceWorld.getValue(), zone.targetWorld.getValue(),
                cells.size(), cleared, cells.size() - cleared);
        return cells.size();
    }

    /** Portal blocks awaiting a loaded chunk before they can be cleared. */
    private static final Map<RegistryKey<World>, Set<BlockPos>> PENDING_BREAKS = new HashMap<>();

    /**
     * Clears whatever of {@code cells} sits in a loaded chunk, and reports how
     * many that was. A cold chunk is skipped, never loaded.
     */
    private static int clearLoaded(ServerWorld world, Collection<BlockPos> cells) {
        int cleared = 0;
        for (BlockPos p : cells) {
            if (!world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
                continue;
            }
            if (isVanillaPortalBlock(world.getBlockState(p))) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(),
                        Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
            // Counted either way: the chunk was loaded and we have now seen
            // this position, so there is nothing left to defer about it.
            cleared++;
        }
        return cleared;
    }

    /**
     * Drains deferred symmetric breaks for this world, one tick at a time.
     *
     * <p>The counterpart of a broken portal frequently lives in an unloaded
     * chunk — the whole point of the destination is that nobody is there. This
     * runs from the world tick and clears whatever has since loaded, so a
     * portal broken while its far side was cold still goes away the moment
     * anyone gets near it, instead of standing as an unregistered doorway.
     */
    public static void processPendingBreaks(ServerWorld world) {
        Set<BlockPos> pending = PENDING_BREAKS.get(world.getRegistryKey());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        Set<BlockPos> done = new HashSet<>();
        for (BlockPos p : pending) {
            if (!world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
                continue;
            }
            if (isVanillaPortalBlock(world.getBlockState(p))) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(),
                        Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
            done.add(p);
        }
        if (done.isEmpty()) {
            return;
        }
        pending.removeAll(done);
        if (pending.isEmpty()) {
            PENDING_BREAKS.remove(world.getRegistryKey());
        }
        com.customdimensions.MultiverseServer.LOGGER.info(
                "Cleared {} deferred portal cell(s) in {} ({} still waiting on cold chunks)",
                done.size(), world.getRegistryKey().getValue(), pending.size());
    }

    /** Deferred cells still outstanding — for tests asserting the queue drains. */
    public static int pendingBreakCount() {
        int total = 0;
        for (Set<BlockPos> cells : PENDING_BREAKS.values()) {
            total += cells.size();
        }
        return total;
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

        // PRESENTATION describes where a portal GOES; MATERIAL describes
        // where it is. The frame below is built from this dimension's blocks
        // so it is recognisable on arrival, but the colour and particles
        // belong to the world on the other side — this portal's job is to
        // take you back there, and it should say so. Without the lookup an
        // arrival inherits the presentation of the dimension you are trying
        // to leave, so the way home out of an ember dimension glowed ember
        // and read as another door deeper in.
        PortalDefinition presentation = MultiverseConfig.getInstance().getPortalFor(sourceWorld);
        // No config for the source world means a RESERVED dimension — the overworld,
        // in practice, which is where almost every portal comes from. It has
        // no portal of its own, so falling back to `definition` (the
        // DESTINATION's portal) would repeat the mistake described above.
        //
        // A neutral vanilla presentation is the honest answer: the way back to
        // the overworld should look like an ordinary portal, not like the
        // place you are leaving. A CHAINED dimension is unaffected — its
        // source has a config and getPortalFor returns it.
        int color = presentation != null
                ? parseColor(presentation.getColor())
                : NEUTRAL_PORTAL_COLOR;
        int cooldown = definition.getCooldown();
        String particleType = presentation != null ? presentation.getParticleType() : null;
        RegistryKey<World> portalWorld = targetWorld.getRegistryKey();

        // Register BEFORE any block is touched. Registration is a pure
        // in-memory map write with no world side effects, and every pass that
        // reads an arrival — particles, projection, the return trip, the aura
        // exclusion — asks the registry, so it must be true before the site
        // is disturbed.
        for (BlockPos p : interior) {
            registerPortal(portalWorld, p, sourceWorld, sourceY, color, cooldown, particleType, exitMode);
        }
        ensureArrivalZone(portalWorld, interior, axis, definition, sourceWorld);

        // Guarantee egress BEFORE the frame goes in: a portal you cannot step
        // out of is the worst failure this code has (see PortalSite).
        PortalSite.carveEgress(targetWorld, interior, axis);

        BlockState frameState = frameBlock.getDefaultState();

        // Suppress neighbour updates for the arrival frame. NOTIFY_NEIGHBORS
        // cascades to adjacent pistons, and Supplementaries'
        // captureBeForPistonMove mixin NPEs when a scheduled piston tick
        // fires with movingPiston=null — crash on every portal traversal
        // near a piston mechanism. Frame blocks at the arrival site are
        // structural; they don't need the surrounding terrain to react to
        // their placement.
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
        } else {
            // Floor under a VERTICAL arrival's bottom row. PortalSite.fits
            // requires solid support, so a site it chose already has one —
            // but PortalSite.findCarveY's second pass deliberately accepts an
            // unsupported site rather than refusing the traversal, and a
            // portal opening over a drop is the same trap as one with no
            // egress. Only the bottom row, and only where nothing solid is
            // already there, so a normal arrival writes no blocks at all.
            int floorY = Integer.MAX_VALUE;
            for (BlockPos p : interior) {
                floorY = Math.min(floorY, p.getY());
            }
            for (BlockPos p : interior) {
                if (p.getY() != floorY) {
                    continue;
                }
                BlockPos below = p.down();
                if (!targetWorld.getBlockState(below).isSolid()) {
                    targetWorld.setBlockState(below, frameState, frameFlags);
                }
            }
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

    /**
     * The vanilla portal cell a player standing here is in — their own block,
     * then one above, then one below. Only vanilla's portals have blocks, so
     * this is purely the adoption probe.
     */
    public static BlockPos vanillaPortalCellNear(ServerWorld world, BlockPos pos) {
        if (isVanillaPortalBlock(world.getBlockState(pos))) {
            return pos;
        }
        BlockPos up = pos.up();
        if (isVanillaPortalBlock(world.getBlockState(up))) {
            return up;
        }
        BlockPos down = pos.down();
        return isVanillaPortalBlock(world.getBlockState(down)) ? down : null;
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
        return floodFill(FrameView.of(world), start, frameMatcher, axis);
    }

    public static Set<BlockPos> floodFill(FrameView view, BlockPos start, FrameMatcher frameMatcher, Direction.Axis axis) {
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
                if (view.matches(neighbor, frameMatcher)) {
                    continue;
                }
                if (!view.isFillable(neighbor)) {
                    return Collections.emptySet();
                }
                visited.add(neighbor);
                queue.add(neighbor);
            }
        }
        return visited;
    }

    public static boolean isAreaBoundedByFrame(ServerWorld world, Set<BlockPos> portalArea, FrameMatcher frameMatcher, Direction.Axis axis) {
        return isAreaBoundedByFrame(FrameView.of(world), portalArea, frameMatcher, axis);
    }

    public static boolean isAreaBoundedByFrame(FrameView view, Set<BlockPos> portalArea, FrameMatcher frameMatcher, Direction.Axis axis) {
        Direction[] directions = planeDirections(axis);
        for (BlockPos pos : portalArea) {
            for (Direction dir : directions) {
                BlockPos neighbor = pos.offset(dir);
                if (portalArea.contains(neighbor) || view.matches(neighbor, frameMatcher)) {
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
        return isAreaBoundedByFrameParts(FrameView.of(world), portalArea, definition, axis);
    }

    public static boolean isAreaBoundedByFrameParts(FrameView view, Set<BlockPos> portalArea,
            PortalDefinition definition, Direction.Axis axis) {
        if (!definition.hasPartMaterials() || axis == Direction.Axis.Y) {
            return isAreaBoundedByFrame(view, portalArea, definition.resolveFrameMatcher(), axis);
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
                if (!view.matches(neighbor, definition.resolvePartMatcher(part))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The vanilla portal area around a real portal block — adoption's input.
     * Nothing this mod owns is found here: its interiors are empty.
     */
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

    /**
     * Vanilla's own portal blocks. This mod places none of them — a portal it
     * owns is a frame with an empty interior — so a hit here is a
     * player-built portal, a structure's, or a fill left by a jar that
     * predates that rule.
     */
    public static boolean isVanillaPortalBlock(BlockState state) {
        return state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.END_PORTAL)
                || state.isOf(Blocks.END_GATEWAY);
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
        // The COLUMN of the source portal this arrival came from; null on
        // records written before this existed. Without it, the immersive
        // preview would sample the return world at the arrival's own column
        // instead of the actual source portal — those coincide at scale 1
        // but diverge as scale increases, since a preview is never scaled.
        public Integer sourceX;
        public Integer sourceZ;

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
        public static final String SOURCE_RECORD = "source-zone-v1";
        /** Arrival-side geometry. Older jars log these as malformed and drop them. */
        public static final String ARRIVAL_RECORD = "arrival-zone-v1";

        String recordType = SOURCE_RECORD;
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

        static StoredPortalZone fromArrival(PortalZone zone) {
            StoredPortalZone stored = from(zone);
            stored.recordType = ARRIVAL_RECORD;
            return stored;
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
            // A definition names the world its portal LEADS TO, so lighting
            // one inside that world is a way back, not a loop — obsidian in
            // the Nether is vanilla's overworld portal. Normalised here so a
            // zone read back from disk is corrected too.
            this.targetWorld = sourceWorld.equals(targetWorld) ? World.OVERWORLD : targetWorld;
        }
    }
}
