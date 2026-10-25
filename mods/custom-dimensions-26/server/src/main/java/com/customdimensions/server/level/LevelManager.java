package com.customdimensions.server.level;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.server.CustomDimensions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/**
 * Opens, unloads and deletes the lazy dimensions through Fantasy.
 * <p>
 * Activations wait in an {@link ActivationQueue}: one at a time, so no two
 * first-time generations overlap (TROUBLESHOOTING.md K6). An activation opens
 * the persistent level, sets its border, and places a ticket on its spawn chunk
 * through the chunk system; it completes on a later tick, once that chunk is
 * loaded, so generation never blocks the tick. The ticket stays while the
 * level is open, because 26.x keeps no spawn chunks loaded on its own.
 * <p>
 * Fantasy's unload and delete finish on a later tick, so both are polled each
 * tick, with a cap, until the level leaves {@code server.levelKeys()}. An
 * unload queues the level's chunk writes a second before Fantasy's own save,
 * which flushes them on the tick. All futures complete on the server thread.
 */
public final class LevelManager {
    /** Spawn ticket radius: the spawn chunk at full status with its neighbours ready. */
    static final int TICKET_RADIUS = 1;
    /** How long an activation may take to load its spawn chunk. */
    static final long ACTIVATION_CAP_MILLIS = 180_000;
    /** How many ticks an unload or delete may take to leave {@code levelKeys()}. */
    static final int UNLOAD_CAP_TICKS = 20 * 120;
    /**
     * Ticks between queueing a closing level's chunk writes and Fantasy's
     * unload, whose save waits for every write; queued early, they are done.
     */
    static final int SAVE_AHEAD_TICKS = 20;
    /** Ticks between idle checks. */
    static final int IDLE_CHECK_TICKS = 20;

    public static final TicketType SPAWN_TICKET = new TicketType(TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    public enum State { LAZY, QUEUED, OPENING, ACTIVE, UNLOADING, DELETING }

    /** A finished activation: how long the spawn chunk took and whether the level had no saved chunks before. */
    public record Activation(String slug, ServerLevel level, long millis, boolean firstTime) {}

    private static LevelManager instance;
    private static final List<java.util.function.Consumer<ServerLevel>> CLOSE_LISTENERS = new ArrayList<>();
    private static final List<java.util.function.Consumer<ResourceKey<Level>>> DELETE_LISTENERS = new ArrayList<>();

    private final MinecraftServer server;
    private final LevelIndex index;
    private final IdlePolicy idle;
    private final ActivationQueue<String> queue = new ActivationQueue<>();
    private final Map<String, CompletableFuture<Activation>> waiting = new HashMap<>();
    private final Map<String, Open> open = new LinkedHashMap<>();
    private final Map<String, Closing> closing = new LinkedHashMap<>();
    private Opening opening;
    private int ticks;

    private record Open(RuntimeLevelHandle handle, ChunkPos spawn) {}

    private record Opening(String slug, RuntimeLevelHandle handle, ChunkPos spawn, CompletableFuture<?> chunk,
                           long startNanos, long openMillis, boolean firstTime) {}

    private static final class Closing {
        final ResourceKey<Level> key;
        final boolean delete;
        final CompletableFuture<Integer> done = new CompletableFuture<>();
        /** Handed to Fantasy after {@link #SAVE_AHEAD_TICKS}; null once handed over, or for a delete. */
        RuntimeLevelHandle unload;
        int ticks;

        Closing(ResourceKey<Level> key, boolean delete) {
            this.key = key;
            this.delete = delete;
        }
    }

    private LevelManager(MinecraftServer server, LevelIndex index) {
        this.server = server;
        this.index = index;
        this.idle = IdlePolicy.ofMinutes(index.idleUnloadMinutes(), System::currentTimeMillis);
    }

    /** Registers the ticket type and the lifecycle hooks; called once at mod initialisation. */
    public static void register() {
        Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath(CustomDimensions.ID, "lazy_spawn"), SPAWN_TICKET);
        ServerLifecycleEvents.SERVER_STARTED.register(LevelManager::start);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (instance != null && instance.server == server) instance.tick();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> instance = null);
    }

    private static void start(MinecraftServer server) {
        long begin = System.nanoTime();
        LevelIndex index = LevelIndex.load(server);
        index.problems().forEach(p -> CustomDimensions.LOG.warn("level index: {}", p));
        instance = new LevelManager(server, index);
        instance.applyBaseBorders();
        CustomDimensions.LOG.info("level index: {} lazy dimensions, {} paired with a definition, in {} ms; idle unload {}",
                index.lazy().size(), index.paired(), (System.nanoTime() - begin) / 1_000_000,
                instance.idle.enabled() ? index.idleUnloadMinutes() + " min" : "off");
    }

    /**
     * Runs {@code listener} on a level just before it unloads or is deleted,
     * so whoever placed a ticket in it can remove it (TROUBLESHOOTING.md T118).
     */
    public static void onClose(java.util.function.Consumer<ServerLevel> listener) {
        CLOSE_LISTENERS.add(listener);
    }

    /** Runs {@code listener} with a level's key when {@link #delete} removes it, so records of its blocks can go. */
    public static void onDelete(java.util.function.Consumer<ResourceKey<Level>> listener) {
        DELETE_LISTENERS.add(listener);
    }

    /** The manager for the running server, once SERVER_STARTED has run. */
    public static Optional<LevelManager> get() {
        return Optional.ofNullable(instance);
    }

    public LevelIndex index() {
        return index;
    }

    public State state(String slug) {
        if (closing.containsKey(slug)) return closing.get(slug).delete ? State.DELETING : State.UNLOADING;
        if (opening != null && opening.slug.equals(slug)) return State.OPENING;
        if (open.containsKey(slug)) return State.ACTIVE;
        if (queue.contains(slug)) return State.QUEUED;
        return State.LAZY;
    }

    /** The open level for {@code slug}, while it is active. */
    public Optional<ServerLevel> level(String slug) {
        Open o = open.get(slug);
        return o == null ? Optional.empty() : Optional.of(o.handle.asLevel());
    }

    /** True while an unload or delete is waiting for its level to leave {@code levelKeys()}. */
    public boolean closing() {
        return !closing.isEmpty();
    }

    /** How many lazy levels are in {@code server.levelKeys()} now. */
    public int loadedLazyCount() {
        return (int) index.lazy().values().stream().filter(e -> server.levelKeys().contains(e.key())).count();
    }

    /**
     * Opens {@code slug} through the queue. Completes when its spawn chunk is
     * loaded, at once when it is already active, or exceptionally with the reason.
     */
    public CompletableFuture<Activation> activate(String slug) {
        Optional<LevelIndex.Entry> entry = index.lazy(slug);
        if (entry.isEmpty()) return CompletableFuture.failedFuture(new IllegalArgumentException("no lazy dimension " + slug));
        Open o = open.get(slug);
        if (o != null) return CompletableFuture.completedFuture(new Activation(slug, o.handle.asLevel(), 0, false));
        CompletableFuture<Activation> pending = waiting.get(slug);
        if (pending != null) return pending;
        CompletableFuture<Activation> future = new CompletableFuture<>();
        waiting.put(slug, future);
        queue.offer(slug);
        return future;
    }

    /**
     * Unloads {@code slug}: drops the spawn ticket, asks Fantasy to unload, and
     * completes with the ticks it took once the level has left {@code levelKeys()}.
     */
    public CompletableFuture<Integer> deactivate(String slug) {
        Closing c = closing.get(slug);
        if (c != null && !c.delete) return c.done;
        Open o = open.get(slug);
        if (o == null) return CompletableFuture.failedFuture(new IllegalStateException(slug + " is " + state(slug)));
        open.remove(slug);
        idle.forget(slug);
        ServerLevel level = o.handle.asLevel();
        level.getChunkSource().removeTicketWithRadius(SPAWN_TICKET, o.spawn, TICKET_RADIUS);
        CLOSE_LISTENERS.forEach(l -> l.accept(level));
        level.save(null, false, false);
        Closing closing = new Closing(level.dimension(), false);
        closing.unload = o.handle;
        this.closing.put(slug, closing);
        return closing.done;
    }

    /**
     * Deletes {@code slug}'s level and its saved chunks through Fantasy. A level
     * that is not open is opened first, without a ticket, so Fantasy can delete
     * it. Completes once the level has left {@code levelKeys()} and its folder is gone.
     */
    public CompletableFuture<Integer> delete(String slug) {
        Optional<LevelIndex.Entry> entry = index.lazy(slug);
        if (entry.isEmpty()) return CompletableFuture.failedFuture(new IllegalArgumentException("no lazy dimension " + slug));
        State state = state(slug);
        if (state != State.ACTIVE && state != State.LAZY) {
            return CompletableFuture.failedFuture(new IllegalStateException(slug + " is " + state));
        }
        RuntimeLevelHandle handle;
        Open o = open.remove(slug);
        if (o != null) {
            idle.forget(slug);
            o.handle.asLevel().getChunkSource().removeTicketWithRadius(SPAWN_TICKET, o.spawn, TICKET_RADIUS);
            CLOSE_LISTENERS.forEach(l -> l.accept(o.handle.asLevel()));
            handle = o.handle;
        } else {
            if (!Files.exists(dimensionPath(entry.get().key()))) {
                DELETE_LISTENERS.forEach(l -> l.accept(entry.get().key()));
                return CompletableFuture.completedFuture(0);
            }
            handle = Fantasy.get(server).getOrOpenPersistentLevel(entry.get().key().identifier(), config(entry.get()));
        }
        handle.delete();
        DELETE_LISTENERS.forEach(l -> l.accept(entry.get().key()));
        Closing closing = new Closing(handle.getRegistryKey(), true);
        this.closing.put(slug, closing);
        return closing.done;
    }

    /** Where a level keeps its chunks: {@code <world>/dimensions/<namespace>/<path>/}. */
    public Path dimensionPath(ResourceKey<Level> key) {
        return server.getWorldPath(LevelResource.ROOT).resolve("dimensions")
                .resolve(key.identifier().getNamespace()).resolve(key.identifier().getPath());
    }

    private void tick() {
        ticks++;
        tickOpening();
        if (opening == null) startNext();
        tickClosing();
        if (ticks % IDLE_CHECK_TICKS == 0 && idle.enabled()) tickIdle();
    }

    private void startNext() {
        Optional<String> next = queue.peek();
        if (next.isEmpty() || closing.containsKey(next.get())) return;
        String slug = queue.start().orElseThrow();
        CompletableFuture<Activation> future = waiting.remove(slug);
        try {
            LevelIndex.Entry entry = index.lazy(slug).orElseThrow();
            boolean firstTime = !Files.isDirectory(dimensionPath(entry.key()).resolve("region"));
            long start = System.nanoTime();
            RuntimeLevelHandle handle = Fantasy.get(server).getOrOpenPersistentLevel(entry.key().identifier(), config(entry));
            ServerLevel level = handle.asLevel();
            applyBorder(level, entry.borderRadius());
            com.customdimensions.core.ChunkPos c = LevelMaths.spawnChunk(spawnOf(entry));
            ChunkPos spawn = new ChunkPos(c.x(), c.z());
            CompletableFuture<?> chunk = level.getChunkSource().addTicketAndLoadWithRadius(SPAWN_TICKET, spawn, TICKET_RADIUS);
            opening = new Opening(slug, handle, spawn, chunk, start, (System.nanoTime() - start) / 1_000_000, firstTime);
            waiting.put(slug, future);
        } catch (Throwable t) {
            queue.finish(slug);
            future.completeExceptionally(t);
        }
    }

    private void tickOpening() {
        if (opening == null) return;
        Opening o = opening;
        long millis = (System.nanoTime() - o.startNanos) / 1_000_000;
        ServerLevel level = o.handle.asLevel();
        Throwable failure = null;
        if (o.chunk.isDone()) {
            if (o.chunk.isCompletedExceptionally()) {
                failure = o.chunk.handle((v, t) -> t).join();
            } else if (level.getChunkSource().getChunkNow(o.spawn.x(), o.spawn.z()) == null) {
                failure = new IllegalStateException("spawn chunk " + o.spawn + " not loaded after its ticket completed");
            }
        } else if (millis > ACTIVATION_CAP_MILLIS) {
            failure = new IllegalStateException("spawn chunk " + o.spawn + " not loaded after " + millis + " ms");
        } else {
            return;
        }
        opening = null;
        queue.finish(o.slug);
        CompletableFuture<Activation> future = waiting.remove(o.slug);
        open.put(o.slug, new Open(o.handle, o.spawn));
        if (failure == null) {
            CustomDimensions.LOG.info("activated {} ({}): opened in {} ms on the tick, spawn chunk {} loaded after {} ms; {} lazy level(s) open",
                    o.slug, o.firstTime ? "first time" : "reopened", o.openMillis, o.spawn, millis, open.size());
            future.complete(new Activation(o.slug, level, millis, o.firstTime));
        } else {
            CustomDimensions.LOG.warn("activation of {} failed after {} ms: {}", o.slug, millis, failure.toString());
            deactivate(o.slug);
            future.completeExceptionally(failure);
        }
    }

    private void tickClosing() {
        if (closing.isEmpty()) return;
        Map<String, Boolean> done = new LinkedHashMap<>();
        for (Map.Entry<String, Closing> e : closing.entrySet()) {
            Closing c = e.getValue();
            c.ticks++;
            if (c.unload != null) {
                if (c.ticks >= SAVE_AHEAD_TICKS) {
                    c.unload.unload();
                    c.unload = null;
                }
                continue;
            }
            if (gone(c)) {
                done.put(e.getKey(), true);
            } else if (c.ticks >= UNLOAD_CAP_TICKS) {
                done.put(e.getKey(), false);
            }
        }
        // Removed before completing, so a callback sees the slug's state after the close.
        for (Map.Entry<String, Boolean> e : done.entrySet()) {
            Closing c = closing.remove(e.getKey());
            if (e.getValue()) {
                c.done.complete(c.ticks);
            } else {
                c.done.completeExceptionally(new IllegalStateException(c.key.identifier() + " still "
                        + (server.levelKeys().contains(c.key) ? "loaded" : "on disk") + " after " + c.ticks + " ticks"));
            }
        }
    }

    private boolean gone(Closing c) {
        return !server.levelKeys().contains(c.key) && (!c.delete || !Files.exists(dimensionPath(c.key)));
    }

    private void tickIdle() {
        List<String> due = new ArrayList<>();
        for (Map.Entry<String, Open> e : open.entrySet()) {
            if (idle.observe(e.getKey(), !e.getValue().handle.asLevel().players().isEmpty())) due.add(e.getKey());
        }
        for (String slug : due) {
            CustomDimensions.LOG.info("unloading {}: no players for {} min", slug, index.idleUnloadMinutes());
            deactivate(slug).whenComplete((t, error) -> {
                if (error != null) CustomDimensions.LOG.warn("idle unload of {} failed: {}", slug, error.getMessage());
            });
        }
    }

    private RuntimeLevelConfig config(LevelIndex.Entry entry) {
        LevelStem stem = index.decode(server, entry);
        WorldgenRuntime.apply(server, entry.key(), stem.generator());
        Definition definition = entry.definition();
        long seed = LevelMaths.resolveSeed(definition == null ? null : definition.seed(), server.overworld().getSeed());
        return new RuntimeLevelConfig()
                .setDimensionType(stem.type())
                .setGenerator(stem.generator())
                .setSeed(seed)
                .mirrorOverworldGameState();
    }

    private static com.customdimensions.core.BlockPos spawnOf(LevelIndex.Entry entry) {
        return entry.definition() == null ? null : entry.definition().spawn();
    }

    /** The lazy dimension whose level is {@code key}, if it is one. */
    public Optional<String> slugOf(ResourceKey<Level> key) {
        return index.lazy().values().stream().filter(e -> e.key().equals(key)).map(LevelIndex.Entry::slug).findFirst();
    }

    /** The definition's spawn, or none. */
    public Optional<com.customdimensions.core.BlockPos> spawn(String slug) {
        return index.lazy(slug).map(LevelManager::spawnOf);
    }

    private void applyBaseBorders() {
        for (LevelIndex.BaseWorld world : index.baseWorlds()) {
            ServerLevel level = server.getLevel(world.key());
            if (level == null) {
                CustomDimensions.LOG.warn("base world {} is not loaded; its border is not set", world.key().identifier());
                continue;
            }
            applyBorder(level, world.borderRadius());
        }
    }

    static void applyBorder(ServerLevel level, Integer radius) {
        if (radius == null) return;
        level.getWorldBorder().setCenter(0, 0);
        level.getWorldBorder().setSize(LevelMaths.borderSize(radius));
    }
}
