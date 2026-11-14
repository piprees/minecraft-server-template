package com.customdimensions.server.level;

import com.customdimensions.server.CustomDimensions;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;

/**
 * A player saved in a lazy level is placed there after a restart or an idle
 * unload. Vanilla's spawn task takes the saved level from {@code server.levels}
 * and, when it is not there, keeps the saved position in the world spawn's
 * level. Before that task, a configuration task of ours opens the saved level
 * through {@link LevelManager}'s queue (one level at a time, on the tick) and
 * ends when it is open or has failed; {@code PrepareSpawnTaskMixin} then sends
 * a player whose saved level is still not open to the world spawn.
 */
public final class SavedLevel {
    /** What the login does with a player's saved position. */
    public enum Placement { AS_SAVED, OPEN_FIRST, WORLD_SPAWN }

    static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type(CustomDimensions.ID + ":open_saved_level");
    /** Past the queue's own cap the login goes on; the mixin then places at the world spawn. */
    static final int TASK_CAP_TICKS = (int) (LevelManager.ACTIVATION_CAP_MILLIS / 50) + 200;

    private SavedLevel() {
    }

    public static void register() {
        // Loaded at start so a failed PrepareSpawnTaskMixin stops the boot instead of every login.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                Class.forName("net.minecraft.server.network.config.PrepareSpawnTask", true, SavedLevel.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        });
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            NameAndId who = new NameAndId(handler.getOwner());
            savedDimension(server.getPlayerList().loadPlayerData(who)).ifPresent(key -> {
                Optional<LevelManager> manager = LevelManager.get();
                Optional<String> slug = manager.flatMap(m -> m.slugOf(key));
                if (decide(true, server.getLevel(key) != null, slug.isPresent()) == Placement.OPEN_FIRST) {
                    CustomDimensions.LOG.info("login: opening {} for {}, who was saved there", slug.get(), who.name());
                    // CONFIGURE runs on a network thread; the queue belongs to the server thread.
                    CompletableFuture<LevelManager.Activation> opening = CompletableFuture
                            .supplyAsync(() -> manager.get().activate(slug.get()), server)
                            .thenCompose(f -> f);
                    handler.addTask(new OpenTask(opening, slug.get(), who.name()));
                }
            });
        });
    }

    /**
     * The placement for a saved level: none saved or already open places as saved; a lazy
     * level of ours is opened first; anything else (a deleted definition, a removed mod's
     * level) is not open at placement and goes to the world spawn.
     */
    public static Placement decide(boolean saved, boolean open, boolean lazy) {
        if (!saved || open) return Placement.AS_SAVED;
        return lazy ? Placement.OPEN_FIRST : Placement.WORLD_SPAWN;
    }

    static Optional<ResourceKey<Level>> savedDimension(Optional<CompoundTag> data) {
        return data.flatMap(tag -> tag.getString("Dimension"))
                .map(Identifier::tryParse)
                .map(id -> ResourceKey.create(Registries.DIMENSION, id));
    }

    /** The self-test's probe: the mixin's handler is merged into vanilla's spawn task. */
    public static boolean mixinApplied() {
        for (java.lang.reflect.Method m : net.minecraft.server.network.config.PrepareSpawnTask.class.getDeclaredMethods()) {
            if (m.getName().contains("customdimensions$placeable")) return true;
        }
        return false;
    }

    /** Called from the spawn task: the saved position, or none when its level is not open. */
    public static ServerPlayer.SavedPosition placeable(MinecraftServer server, NameAndId who, ServerPlayer.SavedPosition saved) {
        Optional<ResourceKey<Level>> key = saved.dimension();
        if (key.isEmpty() || server.getLevel(key.get()) != null) return saved;
        CustomDimensions.LOG.warn("login: {} was saved in {}, which is not open; placing them at the world spawn", who.name(),
                key.get().identifier());
        return ServerPlayer.SavedPosition.EMPTY;
    }

    static final class OpenTask implements ConfigurationTask {
        private final CompletableFuture<LevelManager.Activation> opening;
        private final String slug;
        private final String player;
        private int ticks;

        OpenTask(CompletableFuture<LevelManager.Activation> opening, String slug, String player) {
            this.opening = opening;
            this.slug = slug;
            this.player = player;
        }

        @Override
        public void start(Consumer<Packet<?>> send) {
        }

        @Override
        public boolean tick() {
            if (opening.isDone()) {
                if (opening.isCompletedExceptionally()) {
                    CustomDimensions.LOG.warn("login: {} did not open for {}: {}", slug, player,
                            opening.handle((a, t) -> String.valueOf(t)).join());
                }
                return true;
            }
            if (++ticks >= TASK_CAP_TICKS) {
                CustomDimensions.LOG.warn("login: {} still not open for {} after {} ticks", slug, player, ticks);
                return true;
            }
            return false;
        }

        @Override
        public Type type() {
            return TYPE;
        }
    }
}
