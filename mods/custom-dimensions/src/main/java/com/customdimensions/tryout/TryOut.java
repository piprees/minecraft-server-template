package com.customdimensions.tryout;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.portal.PortalHelper;
import com.google.gson.Gson;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flying around a candidate seed before choosing it.
 *
 * <p>A try-out is a throwaway world built from a real dimension's own
 * generator with a candidate's seed on it, under an id no real dimension
 * uses ({@code <ns>:tryout/<slug>/<seed>}). Its worldgen is the real
 * dimension's, byte for byte: the options come from
 * {@link DimensionManager#buildOptionsHeadless} against the REAL config, so
 * the biome source, noise settings and dimension type are the same objects
 * the real world would get. Only the seed differs, and the seed is applied
 * at world construction, never baked into the generator.
 *
 * <p>Nothing is added to the DIMENSION registry. That registry is what
 * vanilla encodes into {@code level.dat} on save, so a try-out that never
 * enters it needs no {@code level.dat} scrub to remove — closing the world
 * and deleting its region directory is the whole cleanup. The runtime
 * DEFINITION is registered, because that is what
 * {@code ServerWorldSeedMixin}, {@code DimensionStructures} and
 * {@code DifficultyManager} resolve a world's config from.
 *
 * <p>Sessions linger so two candidates can be compared by hopping between
 * them, and expire once nobody has been in one for {@link #IDLE_MINUTES}.
 */
public final class TryOut {

    /** Every try-out world's id starts here, so one directory holds them all. */
    public static final String PATH_PREFIX = "tryout/";

    /** Minutes with no player inside before a try-out world is closed and deleted. */
    public static final int IDLE_MINUTES = 10;

    private static final Gson GSON = new Gson();

    /** A live try-out world. */
    public record Session(String dimension, long seed, Identifier worldId, long createdTick,
                          long lastPresenceTick) {
    }

    /** Where a player was before they stepped into a try-out. */
    private record Origin(RegistryKey<World> worldKey, double x, double y, double z,
                          float yaw, float pitch, GameMode gameMode, float flySpeed) {
    }

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Origin> ORIGINS = new ConcurrentHashMap<>();
    /** Requested try-outs waiting for a safe point to build their world. */
    private static final Map<String, PendingStart> PENDING = new ConcurrentHashMap<>();

    private record PendingStart(String dimension, long seed, UUID player) {
    }

    private TryOut() {
    }

    // ------------------------------------------------------------------ ids

    /** The world id a (dimension, seed) try-out uses. */
    public static Identifier worldIdFor(Identifier dimensionId, long seed) {
        return Identifier.of(dimensionId.getNamespace(),
                PATH_PREFIX + dimensionId.getPath() + "/" + seed);
    }

    private static String pathOf(Identifier worldId) {
        return worldId.getPath();
    }

    // ------------------------------------------------------------------ start

    /**
     * Asks for a try-out world. Returns the world id immediately; the world
     * itself appears on a later tick.
     *
     * <p>World creation mutates the server's worlds map and fires
     * {@code ServerWorldEvents.LOAD}, so it never runs from a request thread
     * or a world tick — {@link #tick} drains this queue from
     * {@code END_SERVER_TICK}, the same rule
     * {@code DimensionManager.requestWorldLoad} follows.
     */
    public static Identifier request(MinecraftServer server, Identifier dimensionId, long seed,
                                     UUID player) {
        DimensionConfig real = MultiverseConfig.getInstance().getDimension(dimensionId.getPath());
        if (real == null) {
            return null;
        }
        Identifier worldId = worldIdFor(dimensionId, seed);
        String path = pathOf(worldId);
        if (SESSIONS.containsKey(path)) {
            touch(server, path);
            return worldId;
        }
        PENDING.put(path, new PendingStart(dimensionId.toString(), seed, player));
        return worldId;
    }

    /** Whether a try-out world is built and ready to be entered. */
    public static boolean isReady(MinecraftServer server, Identifier worldId) {
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, worldId)) != null;
    }

    // ------------------------------------------------------------------ tick

    /** Drains one pending start and expires idle sessions. Call from END_SERVER_TICK. */
    public static void tick(MinecraftServer server) {
        if (!PENDING.isEmpty()) {
            String path = PENDING.keySet().iterator().next();
            PendingStart start = PENDING.remove(path);
            if (start != null) {
                build(server, start);
            }
        }
        if (server.getTicks() % 100 != 0) {
            return;
        }
        long idleTicks = (long) IDLE_MINUTES * 60 * 20;
        long now = server.getTicks();
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            ServerWorld world = server.getWorld(
                    RegistryKey.of(RegistryKeys.WORLD, session.worldId()));
            if (world == null) {
                SESSIONS.remove(pathOf(session.worldId()));
                continue;
            }
            if (!world.getPlayers().isEmpty()) {
                SESSIONS.put(pathOf(session.worldId()), new Session(session.dimension(),
                        session.seed(), session.worldId(), session.createdTick(), now));
                continue;
            }
            if (now - session.lastPresenceTick() >= idleTicks) {
                end(server, session.worldId());
            }
        }
    }

    private static void touch(MinecraftServer server, String path) {
        Session session = SESSIONS.get(path);
        if (session != null) {
            SESSIONS.put(path, new Session(session.dimension(), session.seed(), session.worldId(),
                    session.createdTick(), server.getTicks()));
        }
    }

    private static void build(MinecraftServer server, PendingStart start) {
        Identifier dimensionId = Identifier.of(start.dimension());
        DimensionConfig real = MultiverseConfig.getInstance().getDimension(dimensionId.getPath());
        if (real == null) {
            MultiverseServer.LOGGER.warn("try-out {}: no configured dimension", start.dimension());
            return;
        }
        Identifier worldId = worldIdFor(dimensionId, start.seed());
        String path = pathOf(worldId);
        try {
            // The generator is the REAL dimension's, built from the REAL
            // config: same biome source, same noise settings, same dimension
            // type entry (already registered at boot, so nothing new reaches
            // a registry the client was told about at join).
            DimensionOptions options = DimensionManager.getInstance().buildOptionsHeadless(real);
            // The definition is the clone's, so everything that resolves a
            // world's config by path — the seed mixin, structure placement,
            // difficulty — sees this seed rather than the real dimension's.
            DimensionConfig clone = cloneWithSeed(real, path, dimensionId.getNamespace(), start.seed());
            DimensionManager.getInstance().rememberRuntimeDefinition(clone);
            ServerWorld world = DimensionManager.getInstance()
                    .createEphemeralWorld(worldId, options, start.seed());
            if (world == null) {
                DimensionManager.getInstance().forgetRuntimeDefinition(path);
                return;
            }
            SESSIONS.put(path, new Session(dimensionId.toString(), start.seed(), worldId,
                    server.getTicks(), server.getTicks()));
            MultiverseServer.LOGGER.info("try-out ready: {} (dimension {}, seed {})",
                    worldId, dimensionId, start.seed());
        } catch (RuntimeException e) {
            MultiverseServer.LOGGER.error("try-out {} seed {} failed to build",
                    dimensionId, start.seed(), e);
            DimensionManager.getInstance().forgetRuntimeDefinition(path);
        }
    }

    /**
     * The real config with a new name and seed. A Gson round trip copies
     * every worldgen field without this class having to know what they are;
     * {@code name} and {@code namespace} are transient on
     * {@link DimensionConfig}, so they are stamped afterwards.
     */
    static DimensionConfig cloneWithSeed(DimensionConfig real, String name, String namespace, long seed) {
        DimensionConfig clone = GSON.fromJson(GSON.toJsonTree(real), DimensionConfig.class);
        clone.setName(name);
        clone.setNamespace(namespace);
        clone.setSeed(seed);
        return clone;
    }

    // ------------------------------------------------------------------ enter/leave

    /**
     * Puts a player into a try-out world in creative, flying, at five times
     * normal fly speed, and remembers where they came from.
     */
    public static boolean enter(ServerPlayerEntity player, Identifier worldId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        ServerWorld world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, worldId));
        if (world == null) {
            return false;
        }
        if (!ORIGINS.containsKey(player.getUuid())) {
            ORIGINS.put(player.getUuid(), new Origin(player.getWorld().getRegistryKey(),
                    player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                    player.interactionManager.getGameMode(), player.getAbilities().getFlySpeed()));
        }
        int y = PortalHelper.findSurfaceY(world, 0, 0) + 2;
        player.changeGameMode(GameMode.CREATIVE);
        player.teleport(world, 0.5, y, 0.5, Set.of(), player.getYaw(), player.getPitch());
        player.getAbilities().allowFlying = true;
        player.getAbilities().flying = true;
        player.getAbilities().setFlySpeed(0.05f * 5.0f);
        player.sendAbilitiesUpdate();
        touch(server, pathOf(worldId));
        MultiverseServer.LOGGER.info("try-out entered: {} by {} at 0 {} 0",
                worldId, player.getName().getString(), y);
        return true;
    }

    /**
     * Sends a player back. The overworld origin is 0,0 by design — every
     * candidate's measurements are taken from there, so coming back to the
     * same place is what keeps two try-outs comparable.
     */
    public static boolean leave(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        Origin origin = ORIGINS.remove(player.getUuid());
        ServerWorld overworld = server.getOverworld();
        int y = PortalHelper.findSurfaceY(overworld, 0, 0) + 1;
        player.teleport(overworld, 0.5, y, 0.5, Set.of(), player.getYaw(), player.getPitch());
        if (origin != null) {
            player.changeGameMode(origin.gameMode());
            player.getAbilities().setFlySpeed(origin.flySpeed());
            player.sendAbilitiesUpdate();
        }
        return true;
    }

    // ------------------------------------------------------------------ end

    /**
     * Closes a try-out world and deletes what it generated. Region files are
     * the only durable trace a try-out leaves, and they are not small.
     */
    public static boolean end(MinecraftServer server, Identifier worldId) {
        String path = pathOf(worldId);
        boolean closed = DimensionManager.getInstance().closeEphemeralWorld(server, worldId);
        DimensionManager.getInstance().forgetRuntimeDefinition(path);
        SESSIONS.remove(path);
        Path dir = worldDirectory(server, worldId);
        deleteRecursively(dir);
        pruneEmptyParents(dir.getParent(), tryOutRoot(server, worldId.getNamespace()));
        if (closed) {
            MultiverseServer.LOGGER.info("try-out ended: {} (world closed and deleted)", worldId);
        }
        return closed;
    }

    /** Every live try-out, newest first. */
    public static List<Session> sessions() {
        List<Session> out = new ArrayList<>(SESSIONS.values());
        out.sort(Comparator.comparingLong(Session::createdTick).reversed());
        return out;
    }

    /**
     * Removes every try-out world left on disk. Run at server start: a
     * try-out never reaches {@code level.dat}, so after a restart its region
     * files are unreferenced bytes and nothing else.
     */
    public static void purgeOnStart(MinecraftServer server) {
        Path root = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                .resolve("dimensions");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var namespaces = Files.list(root)) {
            for (Path namespace : namespaces.toList()) {
                Path tryouts = namespace.resolve("tryout");
                if (Files.isDirectory(tryouts)) {
                    deleteRecursively(tryouts);
                    MultiverseServer.LOGGER.info("Removed stale try-out worlds: {}", tryouts);
                }
            }
        } catch (IOException e) {
            MultiverseServer.LOGGER.warn("Could not scan for stale try-out worlds: {}", e.getMessage());
        }
    }

    /** Deletes now-empty directories up to (and including) the try-out root. */
    private static void pruneEmptyParents(Path from, Path root) {
        Path current = from;
        while (current != null && current.startsWith(root)) {
            try (var entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
                Files.deleteIfExists(current);
            } catch (IOException e) {
                return;
            }
            current = current.getParent();
        }
    }

    private static Path tryOutRoot(MinecraftServer server, String namespace) {
        return server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                .resolve("dimensions").resolve(namespace).resolve("tryout");
    }

    private static Path worldDirectory(MinecraftServer server, Identifier worldId) {
        return server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                .resolve("dimensions")
                .resolve(worldId.getNamespace())
                .resolve(worldId.getPath());
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            MultiverseServer.LOGGER.warn("Could not delete try-out world files at {}: {}",
                    dir, e.getMessage());
        }
    }
}
