package com.customdimensions.portal;

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
    // Arrival-portal presence, keyed by entity UUID — see enteredArrivalPortal.
    // Concurrent because both the world tick and the entity tick write it.
    private static final Map<UUID, ArrivalPresence> ARRIVAL_PRESENCE = new java.util.concurrent.ConcurrentHashMap<>();
    private static int lastPresenceSweepTick = 0;
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
                    if (link.has("sourceX") && link.has("sourceZ")) {
                        target.sourceX = link.get("sourceX").getAsInt();
                        target.sourceZ = link.get("sourceZ").getAsInt();
                    }
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

    /**
     * The registered portal block a teleport aimed at this column would land
     * on, or null if there is none in range.
     *
     * <p>This is {@link #findExistingPortal}'s answer, read out of the
     * in-memory registry instead of the world. It exists because the immersive
     * preview and entity pass-through must agree with where a player actually
     * lands, and the player path lands at an EXISTING arrival portal whenever
     * one is found — but those two callers may not touch an unloaded chunk,
     * and {@code findExistingPortal} reads up to 11x11x33 real block states.
     * No block reads, no chunk access, no mutation: safe from any tick path.
     *
     * <p><b>Search order is load-bearing.</b> {@code findExistingPortal}
     * iterates dx, then dz, then dy, all ascending, and returns its FIRST hit
     * — which is the lexicographic minimum by (x, z, y) over the matches. This
     * reproduces that ordering exactly, so the two pick the same portal when
     * several are in range. Note it is NOT {@code BlockPos.compareTo}, whose
     * order is (y, z, x).
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

    /** (x, z, y) ascending — the order findExistingPortal's loops visit. */
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
     * <p><b>Why an edge and not a cooldown.</b> Arrival portals are real
     * {@code NETHER_PORTAL}/{@code END_PORTAL} blocks, so vanilla's
     * {@code Entity.tryUsePortal} runs against them every tick an entity
     * stands inside one — and its first act, when the entity already has a
     * cooldown, is {@code resetPortalCooldown()}, which re-pins the value to
     * {@code getDefaultPortalCooldown()} (10 for a player, 300 for everything
     * else). {@code tickPortalCooldown} then takes one off, and the re-pin
     * puts it back. The cooldown therefore NEVER reaches zero while an entity
     * stands in a portal. Both return paths used to gate on
     * {@code getPortalCooldown() == 0}, so a player who arrived by portal —
     * and so landed INSIDE the arrival portal — could not go home without
     * first walking fully out, waiting out the drain, and walking back in.
     * Measured live 2026-07-25: {@code PortalCooldown=10} at t=2s, t=6s and
     * t=12s on a player standing still in the arrival portal.
     *
     * <p>So the gate is presence, not cooldown: an entity must have been
     * somewhere OTHER than this portal since it last stood in it. That is the
     * same shape as the source-zone trigger above
     * ({@link #wasPlayerInZone}/{@link #setPlayerInZone}), which is why it
     * lives here beside it rather than in a parallel mechanism of its own.
     *
     * <p><b>The cooldown is still load-bearing</b>, just demoted from gate to
     * seed. On a FIRST sighting in a world we have no history to reason from,
     * and a warm cooldown is exactly what distinguishes "a teleport put me
     * here" (our own return teleports and {@code ServerWorldMixin}'s outbound
     * one all set one) from "I materialised here" (an item dropped into the
     * portal, a mob spawned in it — neither has a cooldown, and both should
     * cross, as they did before this change). Without it, arriving through a
     * portal would fire the return on the very next tick, forever.
     *
     * <p>Callers sample at different rates and both are correct:
     * <ul>
     *   <li>The player path samples EVERY tick, {@code insidePortal} true or
     *       false. That keeps {@code world} following the player, so arriving
     *       in a dimension always reads as a first sighting there — which is
     *       what makes the outbound teleport in {@code ServerWorldMixin} safe
     *       without a hook in it.</li>
     *   <li>The entity path samples only while the entity is in one of our
     *       portals, so the map is untouched for the thousands of entities
     *       that are not. "It left" is inferred from the gap in sightings
     *       instead, which needs the entity to be away for at least one full
     *       tick.</li>
     * </ul>
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
     * <p>The edge is a one-shot by design: it fires on the tick something
     * steps in and then stays quiet however long it stands there. Any path
     * that fires it and then declines to teleport — a target world that has
     * not finished loading, a chain link that cannot resolve yet — has to give
     * it back, or the entity stands in the portal forever waiting for a retry
     * that was already spent. That was the level-check behaviour the old
     * cooldown gate got for free, and it has to be explicit now.
     *
     * <p>The rule is the same one that governs {@code ci.cancel()} in
     * {@code EntityTickPortalMixin}: consume it only when you actually
     * teleported. Paths that can never succeed (an entity meeting a
     * player-only exit mode) keep the edge consumed rather than re-testing
     * every tick — the entity IS inside, and nothing will change that.
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
     * <p>Re-igniting an already-lit frame used to append a SECOND zone over
     * the same interior. Nothing deduplicated, and both copies persisted, so
     * the duplicate survived restarts and bred further copies on each
     * re-light. Every per-zone pass then ran twice: two portal-particle
     * emissions (the visible symptom — a player reported the effect as far
     * too strong), two immersive projections of the same 336 blocks to the
     * same client, two chunk-ticket holders, and two aura sites. Found live
     * 2026-07-25 with two byte-identical source-zone-v1 records in
     * portal_links.json.
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

    public static void restoreZones(ServerWorld world) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        List<PortalZone> pending = PENDING_ZONES.remove(worldKey);
        if (pending == null) {
            return;
        }
        for (PortalZone zone : pending) {
            // ImmersiveSettings is transient on PortalDefinition (never
            // serialised into portal_links.json — Gotcha #9), so the
            // Gson-restored definition's immersive field is always null
            // here. Re-stamp from the live config so "immersive" stays
            // boot-re-read for zones ignited before the setting existed
            // (or before it last changed) — without this, every
            // already-ignited immersive portal silently stops being
            // immersive on the next restart. Stamping null is correct
            // too: it's how turning "immersive" off in config takes
            // effect for existing zones.
            zone.definition.setImmersive(MultiverseConfig.getInstance().getImmersiveFor(zone.targetWorld));
            if (isZoneValid(world, zone)) {
                // Same dedupe as registerZone: a portal_links.json written
                // before that guard existed can still hold duplicate records,
                // and restoring both would resurrect the double-particle,
                // double-projection behaviour on every boot. This collapses
                // them on first load; the save at the end of this method
                // rewrites the file without the duplicate.
                addZoneIfAbsent(zone);
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
        // Frameless gateway zones: valid while the gateway block exists
        // (there is no frame to check and the matcher may be empty).
        if (com.customdimensions.portal.PortalShape.END_GATEWAY.equals(zone.definition.getShape())) {
            BlockPos p = zone.interior.iterator().next();
            return world.getBlockState(p).isOf(Blocks.END_GATEWAY);
        }
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

    /**
     * Presentation for an arrival whose source world has no portal config —
     * a base world such as the overworld. Vanilla's portal violet, matching
     * {@link #parseColor}'s own fallback, so the way home reads as an
     * ordinary portal rather than as the dimension you are standing in.
     */
    public static final int NEUTRAL_PORTAL_COLOR = 0x8844FF;

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
        // Immersive gateway zones get a denser cloud from the projector
        // instead of this one (Phase 4d) — spawning both would just muddle
        // the effect. True for no other zone, immersive or not.
        if (com.customdimensions.immersive.ImmersiveProjector.suppliesParticlesFor(zone)) {
            return;
        }
        ParticleEffect effect = resolveParticleEffect(zone.definition);
        boolean immersive = zone.definition != null && zone.definition.getImmersive() != null;
        if (immersive) {
            // An immersive portal thins its interior fill rather than losing
            // it. The full 2-per-cell-per-tick fill is exactly what you
            // cannot see through, and suppressing it entirely was the first
            // fix — but that left "a perfectly hollow box", with nothing to
            // say the doorway is anything but a hole until the client-side
            // work lands. A twelfth of the density reads as dust drifting
            // out of the opening while leaving the view clear.
            if ((world.getTime() + particlePhase(zone)) % IMMERSIVE_PARTICLE_INTERVAL != 0) {
                return;
            }
            for (BlockPos p : zone.interior) {
                world.spawnParticles(effect,
                        p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                        1, 0.25, 0.25, 0.25, 0.005
                );
            }
            return;
        }
        // Non-immersive portals are untouched: the fill is their only visual.
        for (BlockPos p : zone.interior) {
            world.spawnParticles(effect,
                    p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                    2, 0.4, 0.4, 0.4, 0.01
            );
        }
    }

    /** Ticks between the thinned particle passes an immersive portal emits. */
    private static final int IMMERSIVE_PARTICLE_INTERVAL = 6;

    /**
     * Per-zone phase offset so several portals in view do not all pulse on
     * the same tick — the same trick the projector's edge particles use.
     */
    private static int particlePhase(PortalZone zone) {
        BlockPos any = zone.interior.iterator().next();
        return Math.floorMod(any.hashCode(), IMMERSIVE_PARTICLE_INTERVAL);
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
                    // No portal block here any more, and it is deliberately
                    // NOT restored.
                    //
                    // This used to heal the gap (healPortalHole), on the
                    // reasoning that a holed arrival strands whoever is
                    // standing in it. Together with NetherPortalProtectionMixin
                    // that made a portal genuinely indestructible: a player in
                    // creative could swing at a pane all day and watch it come
                    // straight back, because the heal ran on the next particle
                    // pass. Reported in game 2026-07-25 — "not being able to
                    // escape a portal, in creative, or even damage it or delete
                    // it, is a massive problem".
                    //
                    // Being able to destroy a portal you built outranks the
                    // stranding case, which exit portals, exit shrines and the
                    // configured exit modes already cover (owner decision,
                    // 2026-07-25). Neighbour-update protection stays — that
                    // compensates for a non-obsidian frame and never resists a
                    // player.
                    continue;
                }
                // An immersive arrival is a window too — the projector fakes
                // its portal blocks away so the swirl and vanilla's particles
                // stop, and a full-rate dust emission here would just put the
                // haze straight back. Same thinning, same reasoning, as the
                // source side in spawnParticles.
                if (com.customdimensions.immersive.ImmersiveProjector.isImmersiveArrival(worldKey, p)
                        && (level.getTime() + Math.floorMod(p.hashCode(), IMMERSIVE_PARTICLE_INTERVAL))
                                % IMMERSIVE_PARTICLE_INTERVAL != 0) {
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

    /**
     * A player mined a block; if it was part of one of our arrival portals,
     * take the whole portal down with it.
     *
     * <p>Vanilla behaviour, restored deliberately. Breaking one pane of a
     * nether portal pops every pane connected to it, and a player who swings
     * at a portal expects it gone — but our portals are protected from the
     * neighbour updates that would normally cascade
     * ({@code NetherPortalProtectionMixin}, which compensates for a frame that
     * is not obsidian and so would otherwise fail vanilla's re-validation).
     * That protection is deliberately blind to intent, so this is the only
     * place that can tell a player's pick from a stray block update.
     *
     * <p>There was also a {@code healPortalHole} pass that refilled a missing
     * pane from a surviving neighbour. Between the two, a portal was
     * indestructible — in creative you could swing at a pane and watch it
     * reappear on the next particle tick. It was removed on 2026-07-25; being
     * able to destroy a portal outranks the stranding case, which exit
     * portals, exit shrines and the configured exit modes already cover.
     *
     * <p>Deregistering comes FIRST. The heal is keyed on a position still
     * being in the return-target map, so clearing blocks while they were
     * still registered would race the particle pass into rebuilding what this
     * is trying to remove.
     *
     * <p>Only touches REGISTERED positions, so a player-built vanilla portal
     * keeps vanilla's own rules, and a source zone — which has no portal
     * blocks at all — is unaffected. Source frames are still policed by
     * {@code isZoneValid} on the world tick.
     */
    public static void onPlayerBrokePortalBlock(ServerWorld world, BlockPos pos) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        if (!isRegisteredPortalPosition(worldKey, pos)) {
            return;
        }
        Map<BlockPos, PortalReturnTarget> targets = PORTAL_TARGETS.get(worldKey);
        if (targets == null) {
            return;
        }
        // Read the link BEFORE the aperture is deregistered below — this is
        // the only record of which source portal built this arrival, and the
        // removal loop is about to delete it.
        PortalReturnTarget brokenTarget = targets.get(pos);
        // Grow over the REGISTRY rather than over block states: the block the
        // player just broke is already gone, so a state-based flood fill would
        // stop at the hole and leave the rest of the portal standing.
        Set<BlockPos> aperture = com.customdimensions.immersive.ProjectionVolume.collectAperture(
                pos, planeDirections(Direction.Axis.X), targets::containsKey, MAX_PORTAL_BLOCKS);
        if (aperture.isEmpty()) {
            aperture = new HashSet<>(Set.of(pos));
        }
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.Y, Direction.Axis.Z}) {
            aperture.addAll(com.customdimensions.immersive.ProjectionVolume.collectAperture(
                    pos, planeDirections(axis), targets::containsKey, MAX_PORTAL_BLOCKS));
        }

        Map<BlockPos, Integer> frames = PORTAL_FRAMES.get(worldKey);
        for (BlockPos p : aperture) {
            targets.remove(p);
            if (frames != null) {
                frames.remove(p);
            }
        }
        for (BlockPos p : aperture) {
            if (!world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
                continue;
            }
            if (isPortalBlock(world.getBlockState(p))) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(),
                        Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
        }
        int linked = breakLinkedSourceZone(world, brokenTarget);
        savePortalLinks();
        com.customdimensions.MultiverseServer.LOGGER.info(
                "Arrival portal broken by a player in {} at {} ({} blocks removed, {} source zone(s) closed)",
                worldKey.getValue(), pos.toShortString(), aperture.size(), linked);
    }

    // ------------------------------------------------------------------
    // Symmetric breaking (Phase 9c) — see PortalBreakLink for the rules
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
        // save of its own — so without this the deregistration lived only in
        // memory until shutdown, and a crash in between would bring back a
        // portal the player had already broken. Verified stale on 2026-07-26:
        // the log reported the break while portal_links.json still listed
        // every cell of it.
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
            if (isPortalBlock(world.getBlockState(p))) {
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
            if (isPortalBlock(world.getBlockState(p))) {
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
        // Frameless gateway arrivals: a single END_GATEWAY block, no frame
        // ring, no floor dance — vanilla gateways float, ours may too.
        if (com.customdimensions.portal.PortalShape.END_GATEWAY.equals(definition.getShape())) {
            BlockPos gatewayPos = interior.iterator().next();
            targetWorld.setBlockState(gatewayPos, Blocks.END_GATEWAY.getDefaultState(),
                    Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            registerPortal(targetWorld.getRegistryKey(), gatewayPos, sourceWorld, sourceY,
                    parseColor(definition.getColor()), definition.getCooldown(),
                    definition.getParticleType(), exitMode);
            savePortalLinks();
            return;
        }
        // Building needs a CONCRETE block: framePlaceBlock (tag/list/group
        // configs), else the plain frameBlock, else obsidian. Accepting is
        // not placing.
        String placeId = definition.getFramePlaceBlock();
        Identifier frameId = placeId != null ? Identifier.tryParse(placeId) : null;
        Block frameBlock = frameId != null ? Registries.BLOCK.get(frameId) : null;
        if (frameBlock == null || frameBlock == Blocks.AIR) {
            frameBlock = Blocks.OBSIDIAN;
        }

        // Guarantee egress BEFORE the frame goes in: a portal you cannot step
        // out of is the worst failure this code has (see PortalSite).
        PortalSite.carveEgress(targetWorld, interior, axis);

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

        // PRESENTATION describes where a portal GOES; MATERIAL describes
        // where it is. The frame above is built from this dimension's blocks
        // so it is recognisable on arrival, but the colour and particles
        // belong to the world on the other side — this portal's job is to
        // take you back there, and it should say so. Without the lookup an
        // arrival inherits the presentation of the dimension you are trying
        // to leave, so the way home out of an ember dimension glowed ember
        // and read as another door deeper in.
        PortalDefinition presentation = MultiverseConfig.getInstance().getPortalFor(sourceWorld);
        // No config for the source world means a BASE world — the overworld,
        // in practice, which is where almost every portal comes from. It has
        // no portal block of its own, so there is nothing to describe it with.
        //
        // Falling back to `definition` here was the bug: `definition` is the
        // DESTINATION's portal, so the way home out of an ember dimension
        // glowed ember and read as another door deeper in. Reported in game
        // 2026-07-25 as "still seeing nether styles on the return portal".
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

        // Register BEFORE placing the portal blocks, not after.
        // NetherPortalProtectionMixin only defends positions already present
        // in the return-target map, so registering afterwards leaves the
        // whole placement loop unprotected: vanilla
        // NetherPortalBlock.getStateForNeighborUpdate re-validates against an
        // OBSIDIAN frame and pops anything else. Found live 2026-07-25 — an
        // arrival portal came up 5 blocks out of 6, and the hole made the
        // return trip look broken to the player standing in it.
        // Registration is a pure in-memory map write with no world side
        // effects, so doing it first is safe and strictly better.
        for (BlockPos p : interior) {
            registerPortal(portalWorld, p, sourceWorld, sourceY, color, cooldown, particleType, exitMode);
        }

        int portalFlags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        for (BlockPos pos : interior) {
            targetWorld.setBlockState(pos, portalState, portalFlags);
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

    /** Nearest END_GATEWAY block in the search box, else null (gateway reuse). */
    public static BlockPos findExistingGateway(ServerWorld world, int centerX, int centerY, int centerZ, int radiusH, int radiusV) {
        for (int dx = -radiusH; dx <= radiusH; dx++) {
            for (int dz = -radiusH; dz <= radiusH; dz++) {
                for (int dy = -radiusV; dy <= radiusV; dy++) {
                    BlockPos pos = new BlockPos(centerX + dx, centerY + dy, centerZ + dz);
                    if (world.getBlockState(pos).isOf(Blocks.END_GATEWAY)) {
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
        if (startState.isOf(Blocks.END_GATEWAY)) {
            return Set.of(start); // gateways are always exactly one block
        }
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
        // The COLUMN of the source portal this arrival came from. Null on
        // records written before this existed.
        //
        // Without it an arrival knows the Y it should return to but not the
        // X/Z, which is why the immersive preview had a SECOND mapping that
        // translated by zero — it sampled the return world at the arrival's
        // own column instead of at the portal you actually came from. At
        // scale 1 those are the same place and it looked fine; at scale 8 it
        // sampled ~8x away, hit unvisited chunks, and painted nothing but the
        // aperture (the "(12 blocks)" arrivals, 2026-07-25).
        //
        // A preview is never scaled — N blocks is N blocks. Both directions
        // are a rigid translation; this is the missing half of the one going
        // back.
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
