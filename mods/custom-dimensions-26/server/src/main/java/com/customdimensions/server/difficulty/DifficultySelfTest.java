package com.customdimensions.server.difficulty;

import com.customdimensions.core.definition.Difficulty;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * The difficulty part of the start-up self-test: a zombie in the hardest
 * dimension scaled once (and not again after a chunk reload or a dimension
 * change), depth scaling at two heights, a peaceful dimension's natural spawner
 * against a control run with the rule lifted, a hostile mob placed there
 * removed before it ticks, and a fake player's luck across two dimensions.
 */
public final class DifficultySelfTest {
    static final int SPAWN_CYCLES = 1200;
    static final int CONTROL_CYCLES = 600;
    static final int SETTLE_TICKS = 100;
    static final int RELOAD_CAP_TICKS = 20 * 60;
    static final String PREFERRED_PEACEFUL = "the_luminous_caverns";
    /** Chunks out from spawn that the peaceful check searches for an area both halves can measure. */
    static final int AREA_SEARCH_CHUNKS = 8;
    /** Columns apart that the area search samples. */
    static final int AREA_SEARCH_STEP = 4;
    /** Vanilla's {@code NaturalSpawner.MIN_SPAWN_DISTANCE}: no natural spawn this close to a player. */
    static final int MIN_SPAWN_DISTANCE = 24;

    private final MinecraftServer server;
    private final List<String> lines = new ArrayList<>();
    private final List<Waiter> waiters = new ArrayList<>();

    private record Waiter(BooleanSupplier done, CompletableFuture<Boolean> future, long deadline) {}

    private DifficultySelfTest(MinecraftServer server) {
        this.server = server;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        DifficultySelfTest test = new DifficultySelfTest(server);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.poll());
        return test.run();
    }

    private CompletableFuture<List<String>> run() {
        int pause = holdAwake();
        return guard("difficulty hard dimension", this::hard)
                .thenCompose(v -> guard("difficulty depth scaling", this::deep))
                .thenCompose(v -> guard("difficulty peaceful dimension", this::peaceful))
                .handle((v, error) -> {
                    if (error != null) lines.add("FAIL difficulty: the check chain broke: " + cause(error));
                    if (pause > 0 && server instanceof DedicatedServer d) d.setPauseWhenEmptySeconds(pause);
                    return List.copyOf(lines);
                });
    }

    // --- Hard: scaled once, kept through a reload and a dimension change ------------------------

    private CompletableFuture<Void> hard() {
        LevelManager manager = LevelManager.get().orElseThrow();
        LevelIndex.Entry entry = manager.index().lazy().values().stream()
                .max(Comparator.comparingDouble((LevelIndex.Entry e) -> DimensionDifficulty.of(e.key()).mobMultiplier())
                        .thenComparing(LevelIndex.Entry::slug, Comparator.reverseOrder()))
                .orElseThrow();
        Difficulty d = DimensionDifficulty.of(entry.key());
        return manager.activate(entry.slug()).thenCompose(a -> {
            ServerLevel level = a.level();
            int offset = entry.borderRadius() == null ? 256 : Math.min(256, entry.borderRadius() / 2);
            int y = d.depthScaling() == null ? 100 : d.depthScaling().startY();
            y = Math.clamp(y, level.getMinY() + 2, level.getMaxY() - 3);
            BlockPos pos = new BlockPos(offset, y, offset);
            forceLoad(level, pos, true);
            Zombie zombie = spawnZombie(level, pos, EntitySpawnReason.NATURAL);
            double factor = d.mobFactor(y);
            UUID id = zombie.getUUID();
            double[] scaled = new double[1];
            return until(() -> tracked(level, zombie), RELOAD_CAP_TICKS).thenCompose(t -> {
                scaled[0] = zombie.getMaxHealth();
                lines.add(check("difficulty: " + entry.slug() + " scales a naturally spawned zombie at y=" + pos.getY()
                        + " by " + fmt(factor), () -> {
                    if (!t) throw new AssertionError("the zombie was never tracked in " + RELOAD_CAP_TICKS + " ticks");
                    return attributes(zombie, d, factor);
                }));
                forceLoad(level, pos, false);
                return until(() -> zombie.isRemoved() && level.getEntity(id) == null, RELOAD_CAP_TICKS);
            }).thenCompose(unloaded -> {
                forceLoad(level, pos, true);
                return until(() -> level.getEntity(id) != null, RELOAD_CAP_TICKS).thenApply(loaded -> {
                    Entity back = level.getEntity(id);
                    lines.add(check("difficulty: a scaled zombie keeps one scaling over a chunk unload and reload", () -> {
                        if (!unloaded) throw new AssertionError("the zombie never unloaded in " + RELOAD_CAP_TICKS + " ticks");
                        if (!(back instanceof Mob mob)) throw new AssertionError("the zombie never reloaded");
                        if (back == zombie) throw new AssertionError("the same object came back; nothing reloaded");
                        return sameScaling(mob, factor, scaled[0]) + "; reloaded as a new entity";
                    }));
                    return back;
                });
            }).thenCompose(back -> {
                ServerLevel overworld = server.overworld();
                BlockPos there = new BlockPos(-offset, Math.max(overworld.getMinY() + 8, -40), offset);
                forceLoad(overworld, there, true);
                Entity moved = back == null ? null : back.teleport(new TeleportTransition(overworld,
                        Vec3.atBottomCenterOf(there), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING));
                return ticks(1).thenApply(t -> {
                    double overworldFactor = DimensionDifficulty.of(overworld.dimension()).mobFactor(there.getY());
                    lines.add(check("difficulty: a scaled zombie keeps its spawn scaling through a dimension change", () -> {
                        if (!(moved instanceof Mob mob) || mob.level() != overworld) {
                            throw new AssertionError("the zombie did not arrive in the overworld: " + moved);
                        }
                        if (!mob.hasAttached(DimensionDifficulty.SCALED)) throw new AssertionError("the marker did not travel");
                        return sameScaling(mob, factor, scaled[0]) + "; the overworld would give " + fmt(overworldFactor)
                                + " at y=" + there.getY();
                    }));
                    if (moved != null) moved.discard();
                    forceLoad(overworld, there, false);
                    forceLoad(level, pos, false);
                    return null;
                });
            }).thenCompose(v -> manager.deactivate(entry.slug())).thenApply(ticks -> null);
        });
    }

    // --- Deep: the depth factor at two heights ---------------------------------------------------

    private CompletableFuture<Void> deep() {
        LevelManager manager = LevelManager.get().orElseThrow();
        Optional<LevelIndex.BaseWorld> world = manager.index().baseWorlds().stream()
                .filter(w -> DimensionDifficulty.of(w.key()).depthScaling() != null && server.getLevel(w.key()) != null)
                .findFirst();
        if (world.isEmpty()) {
            lines.add("FAIL difficulty: depth scaling: no loaded base world has depthScaling");
            return CompletableFuture.completedFuture(null);
        }
        ServerLevel level = server.getLevel(world.get().key());
        Difficulty d = DimensionDifficulty.of(level.dimension());
        Difficulty.DepthScaling depth = d.depthScaling();
        int span = depth.startY() - depth.endY();
        int[] heights = {depth.startY() - span / 4, depth.endY() + span / 4};
        BlockPos column = new BlockPos(-256, 0, -256);
        forceLoad(level, column, true);
        List<Zombie> zombies = new ArrayList<>();
        for (int i = 0; i < heights.length; i++) {
            heights[i] = Math.clamp(heights[i], level.getMinY() + 2, level.getMaxY() - 3);
            zombies.add(spawnZombie(level, column.atY(heights[i]).offset(i * 2, 0, 0), EntitySpawnReason.NATURAL));
        }
        return until(() -> zombies.stream().allMatch(z -> tracked(level, z)), RELOAD_CAP_TICKS).thenApply(t -> {
            lines.add(check("difficulty: " + world.get().slug() + " depth scaling at two heights matches the formula", () -> {
                List<String> out = new ArrayList<>();
                for (int i = 0; i < heights.length; i++) {
                    double expected = d.mobFactor(heights[i]);
                    double ratio = ratio(zombies.get(i), Attributes.MAX_HEALTH);
                    if (Math.abs(ratio - expected) > 1e-9) {
                        throw new AssertionError("y=" + heights[i] + " health ×" + fmt(ratio) + ", formula ×" + fmt(expected));
                    }
                    out.add("y=" + heights[i] + " health " + fmt(zombies.get(i).getMaxHealth()) + " (×" + fmt(ratio) + ")");
                }
                return String.join(", ", out) + "; formula " + depth.startY() + "→" + depth.endY() + " ×"
                        + fmt(depth.minMultiplier()) + "→×" + fmt(depth.maxMultiplier()) + " under ×" + fmt(d.mobMultiplier());
            }));
            zombies.forEach(Entity::discard);
            forceLoad(level, column, false);
            return null;
        });
    }

    // --- Peaceful: the natural spawner, a placed mob, and player luck ----------------------------

    private CompletableFuture<Void> peaceful() {
        LevelManager manager = LevelManager.get().orElseThrow();
        List<LevelIndex.Entry> peaceful = manager.index().lazy().values().stream()
                .filter(e -> DimensionDifficulty.isPeaceful(e.key())).toList();
        if (peaceful.isEmpty()) {
            lines.add("FAIL difficulty: peaceful dimension: no lazy definition has hostileSpawning false");
            return CompletableFuture.completedFuture(null);
        }
        List<LevelIndex.Entry> candidates = new ArrayList<>(peaceful);
        candidates.sort(Comparator.comparing((LevelIndex.Entry e) -> !e.slug().equals(PREFERRED_PEACEFUL)));
        return pick(manager, candidates, 0, new ArrayList<>()).thenCompose(picked -> {
            LevelIndex.Entry entry = picked.entry();
            Difficulty d = DimensionDifficulty.of(entry.key());
            ServerLevel level = picked.level();
            SpawnArea area = picked.area();
            BlockPos at = area.centre();
            FakePlayer player = FakePlayer.get(server.overworld(),
                    new GameProfile(UUID.nameUUIDFromBytes("customdimensions:difficulty".getBytes()), "cd-difficulty"));
            DimensionDifficulty.applyLuck(player);
            double luckBefore = player.getAttributeValue(Attributes.LUCK);
            player.teleport(new TeleportTransition(level, Vec3.atBottomCenterOf(at), Vec3.ZERO, 0f, 0f,
                    TeleportTransition.DO_NOTHING));
            lines.add(check("difficulty: a fake player's luck is set entering " + entry.slug(), () -> {
                if (player.level() != level) throw new AssertionError("the fake player is in " + player.level().dimension());
                double luck = player.getAttributeValue(Attributes.LUCK);
                AttributeModifier mod = player.getAttribute(Attributes.LUCK).getModifier(DimensionDifficulty.LUCK_ID);
                if (mod == null || Math.abs(luck - (luckBefore + d.luckBonus())) > 1e-9) {
                    throw new AssertionError("luck " + luck + " (before " + luckBefore + "), modifier " + mod
                            + ", playerLuck " + d.playerLuck());
                }
                return "luck " + fmt(luckBefore) + " → " + fmt(luck) + " (playerLuck " + fmt(d.playerLuck()) + ")";
            }));
            int[] counts = new int[6];
            int[] phase = {2};
            List<Mob> saved = new ArrayList<>();
            Runnable stop = DimensionDifficulty.watchLoads((entity, where) -> {
                if (where != level || !(entity instanceof Mob mob)) return;
                boolean hostile = mob.getType().getCategory() == MobCategory.MONSTER;
                if (hostile && phase[0] == 2) saved.add(mob);
                counts[phase[0] * 2 + (hostile ? 0 : 1)]++;
            });
            forceArea(level, at, true);
            int[] freed = {0};
            return ticks(SETTLE_TICKS).thenCompose(t -> {
                for (Entity e : entities(level)) {
                    if (e instanceof Mob m && m.getType().getCategory() != MobCategory.MONSTER) {
                        m.discard();
                        freed[0]++;
                    }
                }
                phase[0] = 0;
                return ticks(SPAWN_CYCLES);
            }).thenCompose(t -> {
                phase[0] = 1;
                DimensionDifficulty.suspendPeaceful(level.dimension(), true);
                level.getChunkSource().setSpawnSettings(level.isSpawningMonsters());
                return ticks(CONTROL_CYCLES);
            }).thenCompose(t -> {
                stop.run();
                DimensionDifficulty.suspendPeaceful(level.dimension(), false);
                int cleared = 0;
                for (Entity e : entities(level)) {
                    if (e instanceof Mob m && m.getType().getCategory() == MobCategory.MONSTER) {
                        m.discard();
                        cleared++;
                    }
                }
                int clearedMonsters = cleared;
                lines.add(check("difficulty: " + entry.slug() + "'s natural spawner makes no hostile mob in "
                        + SPAWN_CYCLES + " ticks while a passive one still spawns", () -> {
                    long kept = saved.stream().filter(m -> !m.isRemoved()).count();
                    if (kept > 0) throw new AssertionError(kept + " of " + saved.size() + " saved hostile mobs were not discarded");
                    if (counts[0] != 0) throw new AssertionError(counts[0] + " hostile mobs loaded");
                    if (!area.found()) throw new AssertionError(area.describe() + "; tried " + picked.tried());
                    if (counts[1] == 0) {
                        throw new AssertionError("no passive mob spawned either; nothing was measured; " + area.describe());
                    }
                    if (counts[2] == 0) {
                        throw new AssertionError("the control run with the rule lifted spawned no hostile mob in "
                                + CONTROL_CYCLES + " ticks, so the check cannot see one; " + area.describe());
                    }
                    return area.describe() + (picked.tried().isEmpty() ? "" : " (passed over " + picked.tried() + ")") + ": 0 hostile, " + counts[1] + " passive; with the rule lifted " + counts[2] + " hostile and "
                            + counts[3] + " passive in " + CONTROL_CYCLES + " ticks (" + clearedMonsters + " cleared); "
                            + counts[4] + " saved hostile mobs loaded and discarded in the " + SETTLE_TICKS + "-tick settle, "
                            + freed[0] + " passive mobs cleared before counting";
                }));
                Zombie egg = spawnZombie(level, at, EntitySpawnReason.SPAWN_ITEM_USE);
                Zombie spawner = spawnZombie(level, at.east(), EntitySpawnReason.SPAWNER);
                return ticks(1).thenApply(x -> {
                    lines.add(check("difficulty: a spawn-egg and a spawner zombie in " + entry.slug()
                            + " are removed before they tick", () -> {
                        for (Zombie z : List.of(egg, spawner)) {
                            if (!z.isRemoved() || z.tickCount != 0) {
                                throw new AssertionError("removed " + z.isRemoved() + ", ticked " + z.tickCount);
                            }
                        }
                        return "both removed with tickCount 0";
                    }));
                    return null;
                });
            }).thenCompose(v -> {
                forceArea(level, at, false);
                ServerLevel overworld = server.overworld();
                BlockPos home = new BlockPos(0, Math.max(overworld.getMinY() + 2, 100), 0);
                player.teleport(new TeleportTransition(overworld, Vec3.atBottomCenterOf(home), Vec3.ZERO, 0f, 0f,
                        TeleportTransition.DO_NOTHING));
                lines.add(check("difficulty: the fake player's luck is removed leaving for a dimension without it", () -> {
                    if (player.level() != overworld) throw new AssertionError("the fake player is in " + player.level().dimension());
                    double luck = player.getAttributeValue(Attributes.LUCK);
                    if (player.getAttribute(Attributes.LUCK).hasModifier(DimensionDifficulty.LUCK_ID)
                            || Math.abs(luck - luckBefore) > 1e-9) {
                        throw new AssertionError("luck " + luck + " in the overworld");
                    }
                    return "luck " + fmt(luck) + " in the overworld (playerLuck "
                            + fmt(DimensionDifficulty.of(overworld.dimension()).playerLuck()) + ")";
                }));
                overworld.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                return manager.deactivate(entry.slug());
            }).thenApply(t -> null);
        });
    }

    /** The peaceful dimension the check measures in, active, with its area; {@code tried} were passed over. */
    private record Picked(LevelIndex.Entry entry, ServerLevel level, SpawnArea area, List<String> tried) {}

    /**
     * The first of {@code candidates} from {@code i} whose spawn has a {@link #spawnArea}, left active; the
     * others are activated, searched and deactivated in turn. With none, the last is kept active with its
     * search result, so the check fails naming every dimension tried.
     */
    private CompletableFuture<Picked> pick(LevelManager manager, List<LevelIndex.Entry> candidates, int i, List<String> tried) {
        LevelIndex.Entry entry = candidates.get(i);
        return manager.activate(entry.slug()).thenCompose(a -> {
            ServerLevel level = a.level();
            com.customdimensions.core.BlockPos spawn = manager.spawn(entry.slug())
                    .orElse(new com.customdimensions.core.BlockPos(0, 64, 0));
            BlockPos origin = new BlockPos(spawn.x(), Math.clamp(spawn.y(), level.getMinY() + 2, level.getMaxY() - 3), spawn.z());
            SpawnArea area = spawnArea(level, origin);
            if (area.found() || i == candidates.size() - 1) {
                return CompletableFuture.completedFuture(new Picked(entry, level, area, tried));
            }
            tried.add(entry.slug() + ": " + area.describe());
            return manager.deactivate(entry.slug()).thenCompose(x -> pick(manager, candidates, i + 1, tried));
        });
    }

    // --- Measurements ------------------------------------------------------------------------------

    /**
     * The 3x3-chunk area the peaceful check forces and counts in, centred on
     * {@code centre}, with the first spot found for a passive and a hostile
     * natural spawn; {@code searched} areas were tried.
     */
    record SpawnArea(BlockPos centre, String passive, String hostile, int searched) {
        boolean found() {
            return passive != null && hostile != null;
        }

        String describe() {
            return found() ? "area " + centre.toShortString() + " (" + searched + " searched): a passive can spawn ("
                    + passive + ") and a hostile (" + hostile + ")"
                    : "no 3x3-chunk area within " + AREA_SEARCH_CHUNKS + " chunks of spawn has spots for both a passive and a hostile"
                    + " natural spawn (" + searched + " searched; closest had passive " + passive + ", hostile " + hostile + ")";
        }
    }

    /**
     * The first 3x3-chunk area, spiralling out from {@code origin}'s chunk, that
     * holds a spot where the natural spawner can place a non-monster and one
     * where it can place a monster: a position the spawner tries (below the
     * column's top, at least {@link #MIN_SPAWN_DISTANCE} blocks from
     * the counting player at the centre) whose spawn list names that mob and
     * whose placement and spawn rules pass there.
     */
    static SpawnArea spawnArea(ServerLevel level, BlockPos origin) {
        int cx = origin.getX() >> 4;
        int cz = origin.getZ() >> 4;
        SpawnArea best = new SpawnArea(origin, null, null, 0);
        int searched = 0;
        for (int ring = 0; ring <= AREA_SEARCH_CHUNKS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    searched++;
                    BlockPos centre = new BlockPos(((cx + dx) << 4) + 8, origin.getY(), ((cz + dz) << 4) + 8);
                    String passive = spot(level, centre, false);
                    String hostile = spot(level, centre, true);
                    SpawnArea area = new SpawnArea(centre, passive, hostile, searched);
                    if (area.found()) return area;
                    if (best.passive() == null && best.hostile() == null && (passive != null || hostile != null)) best = area;
                }
            }
        }
        return new SpawnArea(best.centre(), best.passive(), best.hostile(), searched);
    }

    /** A spot in the 3x3 chunks around {@code centre} where a monster (or a non-monster) can spawn naturally, described; else null. */
    private static String spot(ServerLevel level, BlockPos centre, boolean monster) {
        double minDistance = MIN_SPAWN_DISTANCE * MIN_SPAWN_DISTANCE;
        for (int dx = -16; dx < 32; dx += AREA_SEARCH_STEP) {
            for (int dz = -16; dz < 32; dz += AREA_SEARCH_STEP) {
                int x = ((centre.getX() >> 4) << 4) + dx;
                int z = ((centre.getZ() >> 4) << 4) + dz;
                level.getChunk(x >> 4, z >> 4);
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
                for (int y = level.getMinY(); y <= top; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (pos.distToCenterSqr(centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5) < minDistance) continue;
                    for (MobCategory category : MobCategory.values()) {
                        if (category == MobCategory.MISC || (category == MobCategory.MONSTER) != monster) continue;
                        for (Weighted<MobSpawnSettings.SpawnerData> weighted : level.getChunkSource().getGenerator()
                                .getMobsAt(level, level.structureManager(), category, pos).unwrap()) {
                            MobSpawnSettings.SpawnerData data = weighted.value();
                            if (SpawnPlacements.isSpawnPositionOk(data.type(), level, pos)
                                    && SpawnPlacements.checkSpawnRules(data.type(), level, EntitySpawnReason.NATURAL, pos, level.getRandom())) {
                                return BuiltInRegistries.ENTITY_TYPE.getKey(data.type()) + " at " + pos.toShortString() + " in "
                                        + level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String attributes(Mob mob, Difficulty d, double factor) {
        List<String> out = new ArrayList<>();
        expect(out, mob, "health", Attributes.MAX_HEALTH, d.health() ? factor : 1.0);
        expect(out, mob, "damage", Attributes.ATTACK_DAMAGE, d.damage() ? factor : 1.0);
        expect(out, mob, "armour", Attributes.ARMOR, d.armor() ? factor : 1.0);
        expect(out, mob, "speed", Attributes.MOVEMENT_SPEED, d.speed() ? Difficulty.speedFactor(factor) : 1.0);
        expect(out, mob, "knockback", Attributes.ATTACK_KNOCKBACK, d.knockback() ? factor : 1.0);
        if (Math.abs(mob.getHealth() - mob.getMaxHealth()) > 1e-6) {
            throw new AssertionError("health " + mob.getHealth() + " of " + mob.getMaxHealth());
        }
        return String.join(", ", out) + "; at full health";
    }

    private static void expect(List<String> out, Mob mob, String name, Holder<Attribute> attribute, double expected) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null) {
            out.add(name + " absent");
            return;
        }
        double ratio = ratio(mob, attribute);
        if (Math.abs(ratio - expected) > 1e-9) {
            throw new AssertionError(name + " ×" + fmt(ratio) + ", expected ×" + fmt(expected));
        }
        AttributeModifier ours = instance.getModifier(DimensionDifficulty.MODIFIER_ID);
        double unscaled = ours == null ? instance.getValue() : instance.getValue() / ratio;
        out.add(name + " " + fmt(instance.getValue()) + " = " + fmt(Double.isNaN(unscaled) ? 0 : unscaled) + " ×" + fmt(ratio));
    }

    /**
     * The multiplier this mod's modifier applies, checked against the attribute's value with and
     * without it (a zero value, like a zombie's knockback, carries the modifier but stays zero).
     */
    private static double ratio(Mob mob, Holder<Attribute> attribute) {
        AttributeInstance instance = mob.getAttribute(attribute);
        AttributeModifier ours = instance.getModifier(DimensionDifficulty.MODIFIER_ID);
        if (ours == null) return 1.0;
        if (ours.operation() != AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
            throw new AssertionError(attribute.getRegisteredName() + " modifier is " + ours.operation());
        }
        double scaled = instance.getValue();
        instance.removeModifier(DimensionDifficulty.MODIFIER_ID);
        double unscaled = instance.getValue();
        instance.addPermanentModifier(ours);
        double ratio = 1.0 + ours.amount();
        if (Math.abs(scaled - unscaled * ratio) > 1e-9) {
            throw new AssertionError(attribute.getRegisteredName() + " " + scaled + " is not " + unscaled + " ×" + ratio);
        }
        return ratio;
    }

    private static String sameScaling(Mob mob, double factor, double maxHealth) {
        AttributeModifier mod = mob.getAttribute(Attributes.MAX_HEALTH).getModifier(DimensionDifficulty.MODIFIER_ID);
        if (mod == null) throw new AssertionError("the modifier is gone");
        if (Math.abs(mod.amount() - (factor - 1.0)) > 1e-9 || Math.abs(mob.getMaxHealth() - maxHealth) > 1e-6) {
            throw new AssertionError("modifier " + mod.amount() + ", max health " + mob.getMaxHealth() + " (spawned with "
                    + maxHealth + ")");
        }
        return "max health " + fmt(mob.getMaxHealth()) + ", modifier +" + fmt(mod.amount()) + " (×" + fmt(factor) + ")";
    }

    /** A mob spawned into a chunk that is still reaching full status is untracked until a later tick. */
    private static boolean tracked(ServerLevel level, Mob mob) {
        return level.getEntity(mob.getUUID()) == mob && mob.hasAttached(DimensionDifficulty.SCALED);
    }

    private static Zombie spawnZombie(ServerLevel level, BlockPos pos, EntitySpawnReason reason) {
        Zombie zombie = EntityTypes.ZOMBIE.spawn(level, pos, reason);
        if (zombie == null) throw new IllegalStateException("no zombie spawned at " + pos + " in " + level.dimension());
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        return zombie;
    }

    private static void forceLoad(ServerLevel level, BlockPos pos, boolean force) {
        level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, force);
        if (force) level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static void forceArea(ServerLevel level, BlockPos pos, boolean force) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced((pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz, force);
            }
        }
    }

    // --- Tick plumbing -------------------------------------------------------------------------------

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

    /** A copy of the level's entities, so a loop may discard them; the live view shrinks under it. */
    private static List<Entity> entities(ServerLevel level) {
        List<Entity> all = new ArrayList<>();
        level.getAllEntities().forEach(all::add);
        return all;
    }

    private CompletableFuture<Void> guard(String name, Supplier<CompletableFuture<Void>> step) {
        CompletableFuture<Void> f;
        try {
            f = step.get();
        } catch (Throwable t) {
            f = CompletableFuture.failedFuture(t);
        }
        return f.handle((v, error) -> {
            if (error != null) lines.add("FAIL " + name + ": " + cause(error));
            return null;
        });
    }

    private int holdAwake() {
        if (!(server instanceof DedicatedServer dedicated)) return -1;
        int seconds = dedicated.pauseWhenEmptySeconds();
        if (seconds > 0) dedicated.setPauseWhenEmptySeconds(0);
        return seconds;
    }

    private static String check(String name, Check check) {
        try {
            return "PASS " + name + ": " + check.run();
        } catch (Throwable t) {
            return "FAIL " + name + ": " + t;
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String cause(Throwable error) {
        Throwable c = error;
        while (c instanceof CompletionException && c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }

    @FunctionalInterface
    private interface Check {
        String run() throws Exception;
    }
}
