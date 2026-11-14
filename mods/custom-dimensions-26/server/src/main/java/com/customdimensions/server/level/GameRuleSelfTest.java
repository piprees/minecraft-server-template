package com.customdimensions.server.level;

import com.customdimensions.server.portal.PortalWorld;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;

/**
 * The self-test's game rules section (decisions.md RB-38), {@code -PselfTestOnly=gamerules}:
 * {@value #SLUG} holds {@code keep_inventory} true, {@code block_drops} false and
 * {@code random_tick_speed} 1000 from its definition, and the overworld holds
 * {@code random_tick_speed} 0 through the self-test's hook, while the server-wide
 * store keeps vanilla's values. Each rule is shown to differ between the two open
 * levels at once and to act in each: a death, a broken block, ice beside glowstone
 * over ticks. A server-wide {@code /gamerule} leaves both levels' own values in force.
 */
public final class GameRuleSelfTest {
    static final String SLUG = "selftest_rules";
    /** A chunk of the section's own in both levels, away from every other section's positions. */
    static final ChunkPos GROUND = new ChunkPos(-3, 5);
    static final BlockPos DEATH = GROUND.getBlockAt(2, 0, 4);
    static final BlockPos BREAK = GROUND.getBlockAt(7, 0, 4);
    static final BlockPos ICE = GROUND.getBlockAt(12, 0, 4);
    static final int MELT_TICKS = 400;
    /** The fixture's seed: a lazy level generates from its definition's seed in every reader as a base world does. */
    static final long SEED = 3;
    /** The two-boot check's server-wide value, not vanilla's default. */
    static final int PERSIST_CRAMMING = 31;
    /** Flat skylit fixtures without weather of their own. */
    static final String SHARED_A = "selftest_keep";
    static final String SHARED_B = "selftest_wins";
    /** A fixture whose dimension type has a dragon fight. */
    static final String DRAGON = "selftest_end_cluster";

    private final MinecraftServer server;
    private final List<String> lines = new ArrayList<>();
    private final List<Waiter> waiters = new ArrayList<>();
    private int profiles;

    private record Waiter(BooleanSupplier done, CompletableFuture<Boolean> future, long deadline) {}

    static final class Mortal extends FakePlayer {
        Mortal(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
            return false;
        }
    }

    private GameRuleSelfTest(MinecraftServer server) {
        this.server = server;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        GameRuleSelfTest test = new GameRuleSelfTest(server);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.poll());
        return test.all().handle((v, error) -> {
            if (error != null) test.lines.add("FAIL game rules: " + error);
            return test.lines;
        });
    }

    private void poll() {
        long now = server.getTickCount();
        for (Waiter w : List.copyOf(waiters)) {
            boolean done = w.done().getAsBoolean();
            if (done || now >= w.deadline()) {
                waiters.remove(w);
                w.future().complete(done);
            }
        }
    }

    private CompletableFuture<Boolean> until(BooleanSupplier done, int capTicks) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        waiters.add(new Waiter(done, f, server.getTickCount() + capTicks));
        return f;
    }

    private CompletableFuture<Void> all() {
        String persist = System.getProperty(LevelSelfTest.PERSIST_PROPERTY, "");
        if ("verify".equals(persist)) lines.add(persistVerify());
        ServerLevel over = server.overworld();
        GameRules store = server.getGameRules();
        boolean keep = store.get(GameRules.KEEP_INVENTORY);
        boolean drops = store.get(GameRules.BLOCK_DROPS);
        int speed = store.get(GameRules.RANDOM_TICK_SPEED);
        Map<GameRule<?>, Object> overOwn = LevelGameRules.ownOf(Level.OVERWORLD);
        store.set(GameRules.KEEP_INVENTORY, false, server);
        store.set(GameRules.BLOCK_DROPS, true, server);
        store.set(GameRules.RANDOM_TICK_SPEED, 3, server);
        LevelGameRules.selfTestValues(Level.OVERWORLD, Map.of(GameRules.RANDOM_TICK_SPEED, 0));
        LevelManager manager = LevelManager.get().orElseThrow();
        return manager.activate(SLUG).thenCompose(a -> {
            ServerLevel lazy = a.level();
            for (ServerLevel level : List.of(lazy, over)) level.setChunkForced(GROUND.x(), GROUND.z(), true);
            return until(() -> entitiesTicking(lazy) && entitiesTicking(over), 20 * 60)
                    .thenCompose(loaded -> {
                        for (ServerLevel level : List.of(lazy, over)) {
                            for (BlockPos at : List.of(DEATH, BREAK, ICE)) clearGround(level, at);
                        }
                        lines.add(LevelSeedSelfTest.seedLine(server, lazy, SEED));
                        lines.add(differ(lazy, over));
                        lines.add(deaths(lazy, over));
                        lines.add(blockDrops(lazy, over));
                        return randomTicks(lazy, over);
                    })
                    .thenRun(() -> {
                        lines.add(serverWide(lazy, over));
                        lines.add(clientValues(lazy, over));
                    })
                    .thenCompose(v -> weather(lazy))
                    .thenCompose(v -> sharedWeather(manager))
                    .thenCompose(v -> dragonFightSeed(manager))
                    .whenComplete((v, e) -> {
                        for (ServerLevel level : List.of(lazy, over)) level.setChunkForced(GROUND.x(), GROUND.z(), false);
                        LevelGameRules.selfTestValues(Level.OVERWORLD, overOwn);
                        store.set(GameRules.KEEP_INVENTORY, keep, server);
                        store.set(GameRules.BLOCK_DROPS, drops, server);
                        store.set(GameRules.RANDOM_TICK_SPEED, speed, server);
                    })
                    .thenRun(() -> {
                        if ("write".equals(persist)) lines.add(persistWrite());
                    })
                    .thenCompose(v -> manager.deactivate(SLUG).handle((n, e) -> null));
        });
    }

    /** Boot one of two: a server-wide value set through {@value #SLUG}'s view, which holds its own {@code keep_inventory} true. */
    private String persistWrite() {
        run("gamerule keep_inventory true");
        String said = String.join(" | ", run("execute in " + Level.OVERWORLD.identifier() + " run gamerule max_entity_cramming " + PERSIST_CRAMMING));
        said += " | " + String.join(" | ", run("execute in adventure:" + SLUG + " run gamerule keep_inventory false"));
        boolean ok = server.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING) == PERSIST_CRAMMING
                && !server.getGameRules().get(GameRules.KEEP_INVENTORY);
        return (ok ? "PASS" : "FAIL") + " game rules persist write: store max_entity_cramming " + PERSIST_CRAMMING
                + " and keep_inventory false, set through the levels (" + said + ")";
    }

    /** Boot two: the store came back as written, and no level's own value was saved into it. */
    private String persistVerify() {
        int cramming = server.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        boolean keep = server.getGameRules().get(GameRules.KEEP_INVENTORY);
        boolean ok = cramming == PERSIST_CRAMMING && !keep;
        server.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, GameRules.MAX_ENTITY_CRAMMING.defaultValue(), server);
        return (ok ? "PASS" : "FAIL") + " game rules persist verify: after a restart the store holds max_entity_cramming " + cramming
                + " (want " + PERSIST_CRAMMING + ") and keep_inventory " + keep + " (want false: " + SLUG + "'s own true is not saved into it)";
    }

    /**
     * The values a player in each level is sent ({@link LevelClientRules#send} sends exactly these packets):
     * {@value #SLUG}'s own {@code immediate_respawn} and {@code reduced_debug_info}, the store's in the overworld.
     * Whether the events fire and the client acts on them only a real client shows (the e2e companion check list).
     */
    private String clientValues(ServerLevel lazy, ServerLevel over) {
        List<String> got = new ArrayList<>();
        for (ServerLevel level : List.of(lazy, over)) {
            Mortal p = new Mortal(level, new GameProfile(UUID.randomUUID(), "cd-rules-" + (++profiles)));
            StringBuilder b = new StringBuilder();
            for (var packet : LevelClientRules.packets(p, LevelClientRules.Values.of(p.level().getGameRules()))) {
                if (packet instanceof net.minecraft.network.protocol.game.ClientboundGameEventPacket g) b.append(g.getParam() > 0 ? '1' : '0');
                if (packet instanceof net.minecraft.network.protocol.game.ClientboundEntityEventPacket e) b.append(e.getEventId());
            }
            got.add(b.toString());
        }
        boolean ok = got.equals(List.of("1" + "0" + LevelClientRules.REDUCED_DEBUG_ON, "0" + "0" + LevelClientRules.REDUCED_DEBUG_OFF));
        return (ok ? "PASS" : "FAIL") + " client-held rules follow the level: immediate_respawn, limited_crafting and the debug event "
                + got.get(0) + " in " + SLUG + ", " + got.get(1) + " in the overworld";
    }

    /** Two more open skylit levels share the server's weather: its clear timer still falls by one a tick. */
    private CompletableFuture<Void> sharedWeather(LevelManager manager) {
        List<String> slugs = List.of(SHARED_A, SHARED_B);
        return manager.activate(SHARED_A).thenCompose(a -> manager.activate(SHARED_B).thenCompose(b -> {
            List<ServerLevel> open = List.of(a.level(), b.level());
            run("weather clear 100000");
            net.minecraft.world.level.saveddata.WeatherData shared = server.getWeatherData();
            int before = shared.getClearWeatherTime();
            long start = server.getTickCount();
            return until(() -> server.getTickCount() >= start + 40, 41).thenAccept(v -> {
                long ticks = server.getTickCount() - start;
                int fell = before - shared.getClearWeatherTime();
                long skylit = open.stream().filter(ServerLevel::canHaveWeather).count() + (server.overworld().canHaveWeather() ? 1 : 0);
                lines.add((fell == ticks && skylit >= 3 ? "PASS" : "FAIL") + " the shared weather advances once a tick: its clear timer fell "
                        + fell + " over " + ticks + " ticks with " + skylit + " skylit levels sharing it (" + String.join(", ", slugs)
                        + " and the overworld)");
                run("weather clear");
            });
        })).thenCompose(v -> manager.deactivate(SHARED_B).handle((n, e) -> null))
                .thenCompose(v -> manager.deactivate(SHARED_A).handle((n, e) -> null));
    }

    /** A Fantasy level with a dragon fight: its constructor read the definition's seed for the gateways and the structure check. */
    private CompletableFuture<Void> dragonFightSeed(LevelManager manager) {
        LevelIndex.Entry entry = manager.index().lazy(DRAGON).orElseThrow();
        long seed = entry.definition().seed().value();
        return manager.activate(DRAGON).thenAccept(a -> {
            boolean fight = a.level().getDragonFight() != null;
            lines.add((fight ? "" : "FAIL no dragon fight in " + DRAGON + "; ") + LevelSeedSelfTest.seedLine(server, a.level(), seed)
                    + (fight ? ", gateways shuffled under it" : ""));
        }).thenCompose(v -> manager.deactivate(DRAGON).handle((n, e) -> null));
    }

    /** {@value #SLUG} holds {@code advance_weather} false: weather of its own, which a server-wide /weather leaves and ticks never advance. */
    private CompletableFuture<Void> weather(ServerLevel lazy) {
        run("weather clear");
        net.minecraft.world.level.saveddata.WeatherData shared = server.getWeatherData();
        net.minecraft.world.level.saveddata.WeatherData own = lazy.getWeatherData();
        List<Integer> before = List.of(own.getClearWeatherTime(), own.getRainTime(), own.getThunderTime());
        run("weather rain");
        long end = server.getTickCount() + 40;
        return until(() -> server.getTickCount() >= end, 41).thenAccept(v -> {
            List<Integer> after = List.of(own.getClearWeatherTime(), own.getRainTime(), own.getThunderTime());
            boolean ok = own != shared && shared.isRaining() && !own.isRaining() && before.equals(after);
            lines.add((ok ? "PASS" : "FAIL") + " advance_weather false keeps " + SLUG + "'s own weather: separate=" + (own != shared)
                    + ", server-wide /weather rain raining=" + shared.isRaining() + " there and " + own.isRaining() + " here, timers "
                    + before + " -> " + after + " over 40 ticks");
            run("weather clear");
        });
    }

    /** Two open levels answer the same rules differently, through the level and through {@code execute in … run gamerule}. */
    private String differ(ServerLevel lazy, ServerLevel over) {
        String lazyId = lazy.dimension().identifier().toString();
        List<String> got = List.of(
                query(lazyId, "keep_inventory"), query("minecraft:overworld", "keep_inventory"),
                query(lazyId, "random_tick_speed"), query("minecraft:overworld", "random_tick_speed"),
                query("minecraft:the_nether", "random_tick_speed"));
        List<String> want = List.of("true", "false", "1000", "0", "3");
        boolean levels = lazy.getGameRules().get(GameRules.KEEP_INVENTORY) && !over.getGameRules().get(GameRules.KEEP_INVENTORY)
                && lazy.getGameRules().get(GameRules.RANDOM_TICK_SPEED) == 1000 && over.getGameRules().get(GameRules.RANDOM_TICK_SPEED) == 0;
        return (levels && got.equals(want) ? "PASS" : "FAIL") + " game rules differ between two open levels at once: keep_inventory "
                + got.get(0) + " in " + SLUG + ", " + got.get(1) + " in the overworld; random_tick_speed " + got.get(2) + ", " + got.get(3)
                + ", and the store's " + got.get(4) + " in the nether (want " + want + ")";
    }

    /** {@link #GROUND} is entity-ticking with its entities loaded: an item added there is in a section a query sees. */
    private static boolean entitiesTicking(ServerLevel level) {
        return level.areEntitiesActuallyLoadedAndTicking(GROUND) && level.areEntitiesLoaded(GROUND.pack());
    }

    /** A stone floor under {@code at}'s column at the surface and open air above it, so drops land where they are counted. */
    private static void clearGround(ServerLevel level, BlockPos at) {
        int y = PortalWorld.surfaceY(level, at.getX(), at.getZ());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(at.getX() + dx, y - 1, at.getZ() + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy < 4; dy++) {
                    level.setBlockAndUpdate(new BlockPos(at.getX() + dx, y + dy, at.getZ() + dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** {@code keep_inventory} judged in the level of the death: kept there and back after a respawn in the overworld, dropped in the overworld. */
    private String deaths(ServerLevel lazy, ServerLevel over) {
        int[] dropped = new int[2];
        int[] carried = new int[2];
        List<ServerLevel> levels = List.of(lazy, over);
        for (int i = 0; i < 2; i++) {
            ServerLevel level = levels.get(i);
            BlockPos at = new BlockPos(DEATH.getX(), PortalWorld.surfaceY(level, DEATH.getX(), DEATH.getZ()), DEATH.getZ());
            Mortal p = new Mortal(level, new GameProfile(UUID.randomUUID(), "cd-rules-" + (++profiles)));
            p.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 4));
            p.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
            dropped[i] = level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(4)).stream()
                    .mapToInt(e -> e.getItem().getCount()).sum();
            Mortal fresh = new Mortal(over, p.getGameProfile());
            TeleportTransition spawn = fresh.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
            fresh.snapTo(spawn.position().x, spawn.position().y, spawn.position().z, 0, 0);
            fresh.restoreFrom(p, false);
            ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(p, fresh, false);
            carried[i] = fresh.getInventory().getItem(0).is(Items.DIAMOND) ? fresh.getInventory().getItem(0).getCount() : 0;
            level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(4)).forEach(Entity::discard);
            if (fresh.level().players().contains(fresh)) fresh.level().removePlayerImmediately(fresh, Entity.RemovalReason.DISCARDED);
        }
        boolean ok = dropped[0] == 0 && carried[0] == 4 && dropped[1] == 4 && carried[1] == 0;
        return (ok ? "PASS" : "FAIL") + " keep_inventory acts in the level of the death: in " + SLUG + " " + dropped[0]
                + " dropped and " + carried[0] + " carried after a respawn in the overworld; in the overworld " + dropped[1]
                + " dropped and " + carried[1] + " carried" + (ok ? "" : surroundings(levels, DEATH));
    }

    /** For a failing row: what each level holds around {@code at} and whether its entities are visible to a query. */
    private static String surroundings(List<ServerLevel> levels, BlockPos at) {
        List<String> out = new ArrayList<>();
        for (ServerLevel level : levels) {
            BlockPos top = new BlockPos(at.getX(), PortalWorld.surfaceY(level, at.getX(), at.getZ()), at.getZ());
            out.add(level.dimension().identifier() + " surface " + top.getY() + " " + level.getBlockState(top.below()).getBlock().getDescriptionId()
                    + ", items within 16 " + level.getEntitiesOfClass(ItemEntity.class, new AABB(top).inflate(16)).size()
                    + ", entities ticking " + entitiesTicking(level));
        }
        return " (" + String.join("; ", out) + ")";
    }

    /** {@code block_drops}: a broken block drops nothing in {@value #SLUG} and its item in the overworld. */
    private String blockDrops(ServerLevel lazy, ServerLevel over) {
        int[] drops = new int[2];
        List<ServerLevel> levels = List.of(lazy, over);
        for (int i = 0; i < 2; i++) {
            ServerLevel level = levels.get(i);
            BlockPos at = new BlockPos(BREAK.getX(), PortalWorld.surfaceY(level, BREAK.getX(), BREAK.getZ()), BREAK.getZ());
            level.setBlockAndUpdate(at, Blocks.DIRT.defaultBlockState());
            level.destroyBlock(at, true);
            List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(2));
            drops[i] = items.size();
            items.forEach(Entity::discard);
        }
        boolean ok = drops[0] == 0 && drops[1] > 0;
        return (ok ? "PASS" : "FAIL") + " block_drops acts per level: a broken dirt block dropped "
                + drops[0] + " item(s) in " + SLUG + " and " + drops[1] + " in the overworld" + (ok ? "" : surroundings(levels, BREAK));
    }

    /** {@code random_tick_speed} over ticks: ice under glowstone melts in {@value #SLUG} (1000) and stays in the overworld (0). */
    private CompletableFuture<Void> randomTicks(ServerLevel lazy, ServerLevel over) {
        List<ServerLevel> levels = List.of(lazy, over);
        List<BlockPos> ice = new ArrayList<>();
        for (ServerLevel level : levels) {
            BlockPos at = new BlockPos(ICE.getX(), PortalWorld.surfaceY(level, ICE.getX(), ICE.getZ()), ICE.getZ());
            level.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(at, Blocks.ICE.defaultBlockState());
            level.setBlockAndUpdate(at.above(), Blocks.GLOWSTONE.defaultBlockState());
            ice.add(at);
        }
        long start = server.getTickCount();
        return until(() -> !lazy.getBlockState(ice.get(0)).is(Blocks.ICE), MELT_TICKS).thenAccept(melted -> {
            long took = server.getTickCount() - start;
            boolean kept = over.getBlockState(ice.get(1)).is(Blocks.ICE);
            lines.add((melted && kept ? "PASS" : "FAIL") + " random_tick_speed acts per level over ticks: ice under glowstone "
                    + (melted ? "melted in " + took + " ticks" : "still ice after " + took + " ticks") + " in " + SLUG
                    + " and is " + (kept ? "still ice" : over.getBlockState(ice.get(1)).getBlock().getName().getString())
                    + " in the overworld");
            for (int i = 0; i < 2; i++) {
                for (BlockPos p : List.of(ice.get(i), ice.get(i).above())) levels.get(i).setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            }
        });
    }

    /** A server-wide {@code /gamerule} changes the store and the levels without a value of their own, and no level's own value. */
    private String serverWide(ServerLevel lazy, ServerLevel over) {
        List<String> said = run("gamerule random_tick_speed 5");
        int store = server.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        int nether = server.getLevel(Level.NETHER).getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        int own = over.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        int lazyOwn = lazy.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        boolean ok = store == 5 && nether == 5 && own == 0 && lazyOwn == 1000;
        return (ok ? "PASS" : "FAIL") + " /gamerule stays server-wide and leaves the levels' own values: store " + store
                + ", nether " + nether + ", overworld " + own + ", " + SLUG + " " + lazyOwn + " (" + said + ")";
    }

    private String query(String dimension, String rule) {
        List<String> out = run("execute in " + dimension + " run gamerule " + rule);
        String line = out.isEmpty() ? "" : out.getLast();
        String[] words = line.trim().split("\\s+");
        return words[words.length - 1];
    }

    private List<String> run(String command) {
        List<String> out = new ArrayList<>();
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                out.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture), command);
        return out;
    }
}
