package com.customdimensions.server.exit;

import com.customdimensions.core.definition.Win;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.level.LevelSelfTest;
import com.customdimensions.server.portal.ExitShrines;
import com.customdimensions.server.portal.PortalRegistry;
import com.customdimensions.server.portal.PortalTravel;
import com.customdimensions.server.portal.PortalWorld;
import com.customdimensions.server.portal.PortalZone;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.impl.event.interaction.FakePlayerPacketListener;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * Deaths, exits and wins against the running game, with Fabric's fake player
 * (a plain player on its connection for the wins, see {@link Witness}) in the
 * self-test's fixture dimensions ({@code server/src/selftest/definitions}):
 * a void death that drops everything and respawns in another dimension, a
 * death that keeps everything, void and cause exits, a shipped definition's
 * ender-pearl exit, a hand-built exit shrine, and every win trigger once and
 * never twice. The persistence pass records a shrine and a granted win and
 * finds both after a restart.
 */
public final class ExitSelfTest {
    static final String VOID = "selftest_void";
    static final String KEEP = "selftest_keep";
    static final String WINS = "selftest_wins";
    static final String PEARL_FROM = "the_pillared_void";
    static final String PEARL_TO = "the_tiled_expanse";
    static final String PERSIST_FILE = "customdimensions-exit-persist.txt";
    static final UUID PERSIST_PLAYER = UUID.fromString("c0ffee00-0000-4000-8000-00000000e517");
    static final BlockPos SHRINE = new BlockPos(16, 0, 16);
    static final BlockPos PERSIST_SHRINE = new BlockPos(48, 0, 16);

    private final MinecraftServer server;
    private final List<String> lines = new ArrayList<>();
    private final List<Waiter> waiters = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private int profiles;

    private record Waiter(BooleanSupplier done, CompletableFuture<Boolean> future, long deadline) {
    }

    /** A fake player that damage can reach, so it can die. */
    static final class Mortal extends FakePlayer {
        Mortal(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
            return false;
        }
    }

    /**
     * The wins' player. Fabric refuses advancements to a {@link FakePlayer}, so
     * this is a plain server player with the fake player's no-op connection.
     */
    static final class Witness extends ServerPlayer {
        Witness(MinecraftServer server, ServerLevel level, GameProfile profile) {
            super(server, level, profile, ClientInformation.createDefault());
            this.connection = new FakePlayerPacketListener(this);
        }
    }

    private Witness witness(ServerLevel level, BlockPos at, GameProfile profile) {
        Witness p = new Witness(server, level, profile);
        p.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        return p;
    }

    private ExitSelfTest(MinecraftServer server) {
        this.server = server;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        ExitSelfTest test = new ExitSelfTest(server);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.poll());
        Wins.listener = (player, text) -> test.messages.add(player.getName().getString() + "|" + text);
        String mode = System.getProperty(LevelSelfTest.PERSIST_PROPERTY, "");
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        if (!mode.isEmpty()) {
            chain = chain.thenCompose(v -> test.persistence(mode));
        }
        return chain
                .thenCompose(v -> test.voidDeath())
                .thenCompose(v -> test.keep())
                .thenCompose(v -> test.pearl())
                .thenCompose(v -> test.wins())
                .handle((v, error) -> {
                    if (error != null) test.lines.add("FAIL exits: " + error);
                    Wins.listener = null;
                    return test.lines;
                });
    }

    private void poll() {
        long now = server.getTickCount();
        List<Waiter> ready = new ArrayList<>();
        Iterator<Waiter> it = waiters.iterator();
        while (it.hasNext()) {
            Waiter w = it.next();
            boolean done;
            try {
                done = w.done.getAsBoolean();
            } catch (RuntimeException e) {
                it.remove();
                w.future.completeExceptionally(e);
                continue;
            }
            if (done || now >= w.deadline) {
                it.remove();
                ready.add(w);
            }
        }
        ready.forEach(w -> w.future.complete(w.done.getAsBoolean()));
    }

    private CompletableFuture<Boolean> until(BooleanSupplier done, int capTicks) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        waiters.add(new Waiter(done, f, server.getTickCount() + capTicks));
        return f;
    }

    private CompletableFuture<Boolean> ticks(int n) {
        long end = server.getTickCount() + n;
        return until(() -> server.getTickCount() >= end, n + 1);
    }

    /** Opens {@code slug} and loads the chunk at {@code at}; the level, or a failed future. */
    private CompletableFuture<ServerLevel> open(String slug, BlockPos at) {
        LevelManager m = LevelManager.get().orElseThrow();
        return m.activate(slug).thenCompose(a -> {
            ServerLevel level = a.level();
            level.setChunkForced(at.getX() >> 4, at.getZ() >> 4, true);
            return until(() -> level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null, 20 * 60)
                    .thenApply(ok -> level);
        });
    }

    private CompletableFuture<Void> close(String slug, ServerLevel level, BlockPos at) {
        level.setChunkForced(at.getX() >> 4, at.getZ() >> 4, false);
        return LevelManager.get().orElseThrow().deactivate(slug).handle((n, e) -> null);
    }

    private Mortal mortal(ServerLevel level, BlockPos at) {
        return mortal(level, at, new GameProfile(UUID.randomUUID(), "cd-exit-" + (++profiles)));
    }

    private static Mortal mortal(ServerLevel level, BlockPos at, GameProfile profile) {
        Mortal p = new Mortal(level, profile);
        p.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        return p;
    }

    /**
     * The respawn as vanilla's {@code PlayerList.respawn} runs it for a mod: a
     * new player, {@code restoreFrom} (Fabric's copy-from event) and the
     * after-respawn event. The player list itself is left alone: a fake player
     * there without a login breaks Open Parties and Claims' per-player tick.
     */
    private Mortal respawn(Mortal dead) {
        ServerLevel level = server.overworld();
        Mortal fresh = new Mortal(level, dead.getGameProfile());
        TeleportTransition spawn = fresh.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
        fresh.snapTo(spawn.position().x, spawn.position().y, spawn.position().z, 0, 0);
        fresh.restoreFrom(dead, false);
        ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(dead, fresh, false);
        return fresh;
    }

    /** Takes a player out of whatever level or player list a teleport or respawn put it in. */
    private void discard(ServerPlayer p) {
        if (server.getPlayerList().getPlayers().contains(p)) {
            server.getPlayerList().remove(p);
        } else if (p.level().players().contains(p)) {
            p.level().removePlayerImmediately(p, Entity.RemovalReason.DISCARDED);
        }
    }

    /** An orb's value times its count: orbs of one value merge into one orb with a count, which has no getter. */
    private static int experience(ExperienceOrb orb) {
        try {
            java.lang.reflect.Field count = ExperienceOrb.class.getDeclaredField("count");
            count.setAccessible(true);
            return orb.getValue() * count.getInt(orb);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ExperienceOrb.count is not readable", e);
        }
    }

    private int surface(ServerLevel level, int x, int z) {
        return PortalWorld.surfaceY(level, x, z);
    }

    private <T> CompletableFuture<Void> step(String name, CompletableFuture<T> work, Function<T, String> verdict) {
        return work.handle((value, error) -> {
            String line;
            if (error != null) {
                line = "FAIL " + name + ": " + error;
            } else {
                try {
                    line = verdict.apply(value);
                } catch (Throwable t) {
                    line = "FAIL " + name + ": " + t;
                }
            }
            lines.add(line);
            return null;
        });
    }

    // ---- void death: drop under keepInventory, respawn moved --------------------------------

    private CompletableFuture<Void> voidDeath() {
        BlockPos at = new BlockPos(0, 64, 0);
        return open(VOID, at).thenCompose(level -> {
            GameRules rules = level.getGameRules();
            boolean before = rules.get(GameRules.KEEP_INVENTORY);
            rules.set(GameRules.KEEP_INVENTORY, true, server);
            Mortal p = mortal(level, at);
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
            p.experienceLevel = 10;
            p.hurtServer(level, level.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
            AABB box = new AABB(at).inflate(4);
            int dropped = level.getEntitiesOfClass(ItemEntity.class, box).stream()
                    .filter(e -> e.getItem().is(Items.DIAMOND)).mapToInt(e -> e.getItem().getCount()).sum();
            int orbs = level.getEntitiesOfClass(ExperienceOrb.class, box).stream().mapToInt(ExitSelfTest::experience).sum();
            boolean dead = p.isDeadOrDying();
            rules.set(GameRules.KEEP_INVENTORY, before, server);
            lines.add(dead && dropped == 5 && orbs == 70 && p.getInventory().isEmpty() && p.experienceLevel == 0
                    ? "PASS deaths drop with keepInventory on: " + dropped + " diamonds and " + orbs
                            + " experience on the ground, inventory empty, level 0"
                    : "FAIL deaths drop with keepInventory on: dead=" + dead + " diamonds=" + dropped + " orbs=" + orbs
                            + " level=" + p.experienceLevel);
            level.getEntitiesOfClass(Entity.class, box, e -> e instanceof ItemEntity || e instanceof ExperienceOrb)
                    .forEach(Entity::discard);
            Mortal respawned = respawn(p);
            String target = "adventure:" + WINS;
            return step("exits death respawnAt", until(() -> respawned.level().dimension().identifier().toString()
                    .equals(target), 20 * 90), ok -> {
                String where = respawned.level().dimension().identifier() + " " + respawned.blockPosition().toShortString();
                return ok && respawned.blockPosition().getX() == 4 && respawned.blockPosition().getZ() == 4
                        ? "PASS exits death respawnAt: void death in " + VOID + " respawned at " + where
                                + ", inventory empty=" + respawned.getInventory().isEmpty()
                        : "FAIL exits death respawnAt: respawned at " + where;
            }).whenComplete((v, e) -> discard(respawned))
                    .thenCompose(v -> close(VOID, level, at))
                    .thenCompose(v -> LevelManager.get().orElseThrow().deactivate(WINS).handle((n, e) -> null));
        });
    }

    // ---- keep, void and cause exits, the shrine ---------------------------------------------

    private CompletableFuture<Void> keep() {
        BlockPos at = new BlockPos(0, 0, 0);
        return open(KEEP, at).thenCompose(level -> {
            level.setChunkForced(SHRINE.getX() >> 4, SHRINE.getZ() >> 4, true);
            BlockPos stand = new BlockPos(0, surface(level, 0, 0), 0);
            level.getGameRules().set(GameRules.KEEP_INVENTORY, false, server);
            Mortal p = mortal(level, stand);
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 4));
            p.getInventory().setItem(5, new ItemStack(Items.IRON_SWORD));
            p.experienceLevel = 7;
            p.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
            int onGround = level.getEntitiesOfClass(ItemEntity.class, new AABB(stand).inflate(4)).size();
            Mortal respawned = respawn(p);
            int diamonds = respawned.getInventory().getItem(0).is(Items.DIAMOND) ? respawned.getInventory().getItem(0).getCount() : 0;
            boolean sword = respawned.getInventory().getItem(5).is(Items.IRON_SWORD);
            lines.add(p.isDeadOrDying() && onGround == 0 && diamonds == 4 && sword && respawned.experienceLevel == 7
                    ? "PASS deaths keep with keepInventory off: 0 items dropped, 4 diamonds and the sword in their slots and level 7 after respawn"
                    : "FAIL deaths keep with keepInventory off: dead=" + p.isDeadOrDying() + " dropped=" + onGround
                            + " diamonds=" + diamonds + " sword=" + sword + " level=" + respawned.experienceLevel);
            discard(respawned);

            Mortal faller = mortal(level, new BlockPos(0, level.getMinY() - 10, 0));
            return until(() -> {
                ExitConditions.check(faller, level);
                return faller.level().dimension().equals(Level.OVERWORLD);
            }, 20 * 30).thenCompose(left -> keepRest(level, at, stand, faller, left));
        });
    }

    private CompletableFuture<Void> keepRest(ServerLevel level, BlockPos at, BlockPos stand, Mortal faller, boolean left) {
        lines.add(left && faller.hasEffect(MobEffects.SLOW_FALLING)
                ? "PASS exits void teleport: fell to y " + (level.getMinY() - 10) + " in " + KEEP
                        + ", arrived in the overworld at " + faller.blockPosition().toShortString() + " with slow falling"
                : "FAIL exits void teleport: in " + faller.level().dimension().identifier());
        discard(faller);

        Mortal burnt = mortal(level, stand);
        boolean allowed = ExitConditions.onDeath(burnt, level, level.damageSources().lava());
        boolean burntLeft = burnt.level().dimension().equals(Level.OVERWORLD);
        Mortal plain = mortal(level, stand);
        boolean plainAllowed = ExitConditions.onDeath(plain, level, level.damageSources().generic());
        lines.add(!allowed && burntLeft && plainAllowed && plain.level() == level
                ? "PASS exits death:lava teleport: lava death cancelled and sent to the overworld; a generic death is left alone"
                : "FAIL exits death:lava teleport: cancelled=" + !allowed + " left=" + burntLeft
                        + " genericAllowed=" + plainAllowed);
        discard(burnt);
        return shrine(level, SHRINE).thenCompose(v -> {
            level.setChunkForced(SHRINE.getX() >> 4, SHRINE.getZ() >> 4, false);
            return close(KEEP, level, at);
        });
    }

    /** Builds the shrine's beacon and crying-obsidian ring by the template contract, along +X. */
    private static BlockPos buildShrine(ServerLevel level, BlockPos column) {
        int y = PortalWorld.surfaceY(level, column.getX(), column.getZ());
        BlockPos beacon = new BlockPos(column.getX(), y, column.getZ());
        level.setBlockAndUpdate(beacon, Blocks.BEACON.defaultBlockState());
        for (int step = 0; step <= 1; step++) {
            level.setBlockAndUpdate(beacon.east(step).above(1), Blocks.CRYING_OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(beacon.east(step).above(5), Blocks.CRYING_OBSIDIAN.defaultBlockState());
        }
        for (int dy = 2; dy <= 4; dy++) {
            level.setBlockAndUpdate(beacon.west().above(dy), Blocks.CRYING_OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(beacon.east(2).above(dy), Blocks.CRYING_OBSIDIAN.defaultBlockState());
        }
        return beacon;
    }

    private CompletableFuture<Void> shrine(ServerLevel level, BlockPos column) {
        BlockPos beacon = buildShrine(level, column);
        ExitShrines.onChunkLoad(level, level.getChunkAt(beacon));
        BlockPos cell = beacon.above(2);
        // Past one frame validation pass, so a ring the arrival check refuses would already be closed.
        return step("exit shrine", until(() -> PortalRegistry.get().zoneAt(level.dimension(), cell) != null, 20 * 10)
                .thenCompose(found -> ticks(120).thenApply(x -> found)), found -> {
            PortalZone zone = PortalRegistry.get().zoneAt(level.dimension(), cell);
            int portalBlocks = zone == null ? 0
                    : (int) zone.interior.stream().filter(c -> PortalWorld.isPortal(level.getBlockState(c))).count();
            Mortal p = mortal(level, cell);
            TeleportTransition t = zone == null ? null : PortalTravel.destination(level, p, cell);
            boolean home = t != null && t.newLevel().dimension().equals(Level.OVERWORLD);
            return zone != null && zone.arrival && portalBlocks == 6 && home
                    ? "PASS exit shrine: beacon at " + beacon.toShortString() + " registered a 6-cell exit zone, still lit after "
                            + "120 ticks, leading to the overworld at " + t.position()
                    : "FAIL exit shrine: zone=" + (zone != null) + " portal blocks=" + portalBlocks + " home=" + home;
        });
    }

    // ---- a shipped definition's ender-pearl exit --------------------------------------------

    private CompletableFuture<Void> pearl() {
        Optional<com.customdimensions.core.BlockPos> spawn = LevelManager.get().orElseThrow().spawn(PEARL_FROM);
        BlockPos at = spawn.map(s -> new BlockPos(s.x(), s.y(), s.z())).orElse(new BlockPos(0, 64, 0));
        return open(PEARL_FROM, at).thenCompose(level -> {
            Mortal p = mortal(level, at);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_PEARL, 4));
            String target = "adventure:" + PEARL_TO;
            InteractionResult[] last = {InteractionResult.PASS};
            return step("exits enderPearl", until(() -> {
                if (!p.level().dimension().identifier().toString().equals(target)) {
                    last[0] = UseItemCallback.EVENT.invoker().interact(p, p.level(), InteractionHand.MAIN_HAND);
                }
                return p.level().dimension().identifier().toString().equals(target);
            }, 20 * 120), ok -> ok && last[0] == InteractionResult.FAIL && p.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 4
                    ? "PASS exits enderPearl: " + PEARL_FROM + " pearl throw taken as the exit to " + target + " at "
                            + p.blockPosition().toShortString() + ", pearl kept"
                    : "FAIL exits enderPearl: in " + p.level().dimension().identifier() + ", result " + last[0])
                    .whenComplete((v, e) -> discard(p))
                    .thenCompose(v -> close(PEARL_FROM, level, at))
                    .thenCompose(v -> LevelManager.get().orElseThrow().deactivate(PEARL_TO).handle((n, e) -> null));
        });
    }

    // ---- wins --------------------------------------------------------------------------------

    private CompletableFuture<Void> wins() {
        BlockPos at = new BlockPos(0, 0, 0);
        return open(WINS, at).thenCompose(level -> {
            messages.clear();
            int y = surface(level, 0, 0);
            Witness p = witness(level, new BlockPos(0, y, 0), new GameProfile(UUID.randomUUID(), "cd-win-" + (++profiles)));
            String name = p.getName().getString();
            Wins.check(p, level);
            Wins.check(p, level);
            return ticks(45).thenCompose(x -> {
                Wins.check(p, level);
                p.getInventory().setItem(3, new ItemStack(Items.DIAMOND, 3));
                Wins.check(p, level);
                for (int i = 0; i < 3; i++) {
                    Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                    zombie.snapTo(2.5, y, 2.5, 0, 0);
                    level.addFreshEntity(zombie);
                    zombie.hurtServer(level, level.damageSources().playerAttack(p), Float.MAX_VALUE);
                }
                AdvancementHolder storyRoot = server.getAdvancements().get(Identifier.withDefaultNamespace("story/root"));
                p.getAdvancements().award(storyRoot, "crafting_table");
                Wins.check(p, level);
                p.snapTo(40.5, -60, 40.5, 0, 0);
                Wins.check(p, level);
                return until(() -> p.level().dimension().equals(Level.OVERWORLD), 20 * 10);
            }).thenApply(sentOn -> {
                Definitions.Of of = Definitions.of(level.dimension());
                List<String> fired = new ArrayList<>();
                List<String> problems = new ArrayList<>();
                for (Win win : of.definition().wins()) {
                    AdvancementHolder holder = Wins.holder(server, WINS, win);
                    boolean done = holder != null && p.getAdvancements().getOrStartProgress(holder).isDone();
                    long sent = messages.stream().filter(m -> m.startsWith(name + "|") && m.contains(win.title())).count();
                    if (done && sent == 1) {
                        fired.add(win.trigger().type().json() + ":" + win.id());
                    } else {
                        problems.add(win.id() + " done=" + done + " messages=" + sent);
                    }
                }
                AdvancementHolder root = server.getAdvancements().get(Identifier.fromNamespaceAndPath("adventure", Win.ROOT));
                boolean rootDone = root != null && p.getAdvancements().getOrStartProgress(root).isDone();
                lines.add(problems.isEmpty() && rootDone && fired.size() == 6
                        ? "PASS wins each trigger once: " + String.join(", ", fired) + "; root tab done; message \""
                                + messages.get(0).substring(name.length() + 1) + "\""
                        : "FAIL wins each trigger once: fired " + fired + "; " + problems + "; root=" + rootDone);
                lines.add(sentOn ? "PASS wins exit: summit sent the player on to the overworld at " + p.blockPosition().toShortString()
                        : "FAIL wins exit: still in " + p.level().dimension().identifier());
                int before = messages.size();
                p.teleport(new TeleportTransition(level, new net.minecraft.world.phys.Vec3(40.5, -60, 40.5),
                        net.minecraft.world.phys.Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
                Wins.check(p, level);
                Wins.grant(p, WINS, of.definition().wins().get(0));
                lines.add(messages.size() == before
                        ? "PASS wins never twice: every trigger checked again and arrive granted again, 0 new messages"
                        : "FAIL wins never twice: " + (messages.size() - before) + " new messages");
                discard(p);
                return null;
            }).thenCompose(v -> close(WINS, level, at));
        });
    }

    // ---- two boots -----------------------------------------------------------------------------

    private CompletableFuture<Void> persistence(String mode) {
        String name = "exit persistence " + mode;
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(PERSIST_FILE);
        GameProfile profile = new GameProfile(PERSIST_PLAYER, "cd-exit-persist");
        CompletableFuture<ServerLevel> keep = open(KEEP, PERSIST_SHRINE);
        if ("write".equals(mode)) {
            return keep.thenCompose(level -> shrine(level, PERSIST_SHRINE).thenCompose(v -> open(WINS, BlockPos.ZERO))
                    .thenCompose(wins -> {
                        Witness p = witness(wins, BlockPos.ZERO, profile);
                        Win arrive = Definitions.of(wins.dimension()).definition().wins().get(0);
                        boolean granted = Wins.grant(p, WINS, arrive);
                        p.getAdvancements().save();
                        BlockPos cell = PortalRegistry.get().zones(level.dimension()).stream()
                                .filter(z -> z.interior.stream().anyMatch(c -> c.getX() == PERSIST_SHRINE.getX()))
                                .findFirst().map(PortalZone::landingCell).orElse(null);
                        try {
                            Files.writeString(file, cell == null ? "" : cell.getX() + " " + cell.getY() + " " + cell.getZ());
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                        lines.add(granted && cell != null
                                ? "PASS " + name + ": shrine zone at " + cell.toShortString() + " recorded, win arrive granted to "
                                        + PERSIST_PLAYER
                                : "FAIL " + name + ": granted=" + granted + " shrine cell=" + cell);
                        return close(WINS, wins, BlockPos.ZERO);
                    }).thenCompose(v -> close(KEEP, level, PERSIST_SHRINE)));
        }
        return keep.thenCompose(level -> {
            String[] xyz;
            try {
                xyz = Files.readString(file).trim().split(" ");
            } catch (IOException e) {
                lines.add("FAIL " + name + ": no record from the write boot: " + e);
                return close(KEEP, level, PERSIST_SHRINE);
            }
            BlockPos cell = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            return ticks(5).thenCompose(x -> open(WINS, BlockPos.ZERO)).thenCompose(wins -> {
                PortalZone zone = PortalRegistry.get().zoneAt(level.dimension(), cell);
                PortalZone.ReturnTarget target = PortalRegistry.get().target(level.dimension(), cell);
                boolean lit = PortalWorld.isPortal(level.getBlockState(cell));
                Witness p = witness(wins, BlockPos.ZERO, profile);
                Win arrive = Definitions.of(wins.dimension()).definition().wins().get(0);
                AdvancementHolder holder = Wins.holder(server, WINS, arrive);
                boolean done = holder != null && p.getAdvancements().getOrStartProgress(holder).isDone();
                int before = messages.size();
                boolean again = Wins.grant(p, WINS, arrive);
                lines.add(zone != null && target != null && "worldSpawn".equals(target.exitMode()) && lit && done && !again
                        && messages.size() == before
                        ? "PASS " + name + ": shrine zone at " + cell.toShortString() + " reloaded, exit mode "
                                + target.exitMode() + ", lit; win arrive still done and not granted again"
                        : "FAIL " + name + ": zone=" + (zone != null) + " exit=" + (target == null ? null : target.exitMode())
                                + " lit=" + lit + " done=" + done + " grantedAgain=" + again);
                return close(WINS, wins, BlockPos.ZERO);
            }).thenCompose(v -> close(KEEP, level, PERSIST_SHRINE));
        });
    }
}
