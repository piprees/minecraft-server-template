package com.customdimensions.server.exit;

import com.customdimensions.core.definition.Win;
import com.customdimensions.core.dimension.ExitTarget;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.MessageFile;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A definition's {@code wins} (decisions.md RB-30). Each is the advancement
 * {@code <namespace>:wins/<slug>/<id>} the compiler wrote; granting it is the
 * once-per-player record, so a win already done never fires again. A win
 * grants its advancement and the {@code wins/root} tab, broadcasts its
 * {@code message} from {@code config/messages.json} with {@code {player}},
 * {@code {dimension}} and {@code {win}}, and sends the player on when it has
 * an {@code exit}.
 * <p>
 * {@code kill} counts through the advancement's own criteria, one per kill.
 * {@code enter}, {@code reach}, {@code obtain}, {@code survive} and
 * {@code advancement} are checked every {@link #CHECK_INTERVAL} ticks for the
 * players in the level; {@code survive} counts from when the player was first
 * seen there alive and restarts on death or on leaving, and
 * {@code advancement} fires when that advancement turns done while the player
 * is there.
 */
public final class Wins {
    static final int CHECK_INTERVAL = 10;

    /** Called with the player and the formatted message whenever a win fires; the self-test's capture. */
    public static volatile BiConsumer<ServerPlayer, String> listener;

    private static final Map<UUID, Arrival> ARRIVED = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Boolean>> BASELINE = new ConcurrentHashMap<>();
    private static final Set<String> MISSING = new HashSet<>();

    private record Arrival(ResourceKey<Level> level, long tick) {
    }

    private Wins() {
    }

    static void levelTick(ServerLevel level) {
        if (level.getGameTime() % CHECK_INTERVAL != 0) {
            return;
        }
        Definitions.Of of = Definitions.of(level.dimension());
        if (of == null || of.definition().wins().isEmpty()) {
            return;
        }
        for (ServerPlayer player : List.copyOf(level.players())) {
            check(player, level);
        }
    }

    /** The polled triggers for one player in {@code level}. */
    public static void check(ServerPlayer player, ServerLevel level) {
        Definitions.Of of = Definitions.of(level.dimension());
        if (of == null || player.isSpectator() || !player.isAlive()) {
            return;
        }
        long now = level.getServer().getTickCount();
        Arrival arrival = ARRIVED.get(player.getUUID());
        if (arrival == null || !arrival.level().equals(level.dimension())) {
            arrival = new Arrival(level.dimension(), now);
            ARRIVED.put(player.getUUID(), arrival);
            BASELINE.remove(player.getUUID());
        }
        for (Win win : of.definition().wins()) {
            Win.Trigger t = win.trigger();
            boolean fires = switch (t.type()) {
                case ENTER -> true;
                case REACH -> t.reached(player.getX(), player.getY(), player.getZ());
                case OBTAIN -> carried(player.getInventory(), t) >= t.count();
                case SURVIVE -> now - arrival.tick() >= t.ticks();
                case ADVANCEMENT -> turnedDone(player, of.slug() + "/" + win.id(), t.target());
                case KILL -> false;
            };
            if (fires) {
                grant(player, of.slug(), win);
            }
        }
    }

    private static int carried(Inventory inventory, Win.Trigger t) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && t.matches(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getItem().builtInRegistryHolder().tags().map(k -> k.location().toString())
                            .collect(Collectors.toSet()))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** True once {@code advancement} goes from not done (when first seen here) to done. */
    private static boolean turnedDone(ServerPlayer player, String key, String advancement) {
        AdvancementHolder holder = player.level().getServer().getAdvancements().get(Identifier.parse(advancement));
        if (holder == null) {
            return false;
        }
        boolean done = player.getAdvancements().getOrStartProgress(holder).isDone();
        Map<String, Boolean> seen = BASELINE.computeIfAbsent(player.getUUID(), u -> new HashMap<>());
        Boolean before = seen.put(key, done);
        return before != null && !before && done;
    }

    /** Fabric's after-killed-other-entity event: a kill in the level counts toward its {@code kill} wins. */
    static void afterKill(ServerLevel level, ServerPlayer player, LivingEntity killed) {
        Definitions.Of of = Definitions.of(level.dimension());
        if (of == null) {
            return;
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(killed.getType()).toString();
        Set<String> tags = killed.getType().builtInRegistryHolder().tags().map(k -> k.location().toString())
                .collect(Collectors.toSet());
        for (Win win : of.definition().wins()) {
            if (win.trigger().type() == Win.Type.KILL && win.trigger().matches(id, tags)) {
                countKill(player, of.slug(), win);
            }
        }
    }

    private static void countKill(ServerPlayer player, String slug, Win win) {
        AdvancementHolder holder = holder(player.level().getServer(), slug, win);
        if (holder == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
        if (progress.isDone()) {
            return;
        }
        for (String criterion : win.criteria()) {
            if (progress.getCriterion(criterion) != null && !progress.getCriterion(criterion).isDone()) {
                player.getAdvancements().award(holder, criterion);
                break;
            }
        }
        if (player.getAdvancements().getOrStartProgress(holder).isDone()) {
            fired(player, slug, win);
        }
    }

    /** Grants {@code win} unless the player has it; true when it fired now. */
    public static boolean grant(ServerPlayer player, String slug, Win win) {
        AdvancementHolder holder = holder(player.level().getServer(), slug, win);
        if (holder == null || player.getAdvancements().getOrStartProgress(holder).isDone()) {
            return false;
        }
        for (String criterion : win.criteria()) {
            player.getAdvancements().award(holder, criterion);
        }
        fired(player, slug, win);
        return true;
    }

    private static void fired(ServerPlayer player, String slug, Win win) {
        MinecraftServer server = player.level().getServer();
        AdvancementHolder root = server.getAdvancements().get(Identifier.fromNamespaceAndPath(Definitions.namespace(), Win.ROOT));
        if (root != null) {
            player.getAdvancements().award(root, "root");
        }
        String text = MessageFile.get().format(win.messageKey(), Map.of(
                "player", player.getName().getString(), "dimension", slug, "win", win.title()));
        server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
        CustomDimensions.LOG.info("win {}/{} granted to {}", slug, win.id(), player.getName().getString());
        BiConsumer<ServerPlayer, String> l = listener;
        if (l != null) {
            l.accept(player, text);
        }
        ExitTarget exit = win.exit() == null ? null : ExitTarget.parse(win.exit());
        if (exit != null) {
            ExitConditions.sendOn(player, exit);
        }
    }

    static AdvancementHolder holder(MinecraftServer server, String slug, Win win) {
        Identifier id = Identifier.fromNamespaceAndPath(Definitions.namespace(), win.advancementPath(slug));
        AdvancementHolder holder = server.getAdvancements().get(id);
        if (holder == null && MISSING.add(id.toString())) {
            CustomDimensions.LOG.warn("win advancement {} is not loaded; the pack was compiled without it", id);
        }
        return holder;
    }

    static void died(UUID id) {
        ARRIVED.remove(id);
    }

    static void forget(UUID id) {
        ARRIVED.remove(id);
        BASELINE.remove(id);
    }
}
