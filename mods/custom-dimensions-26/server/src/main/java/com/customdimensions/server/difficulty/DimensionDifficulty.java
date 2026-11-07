package com.customdimensions.server.difficulty;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.Difficulty;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * Each dimension's {@code difficulty}: monster attributes scaled once, when a
 * mob first enters any level (its {@link #SCALED} marker persists with the
 * entity, so a reload or a dimension change never scales it again); no hostile
 * mobs in a {@code hostileSpawning: false} dimension, where the natural spawner
 * skips the monster category and any monster that enters is discarded before
 * it ticks; and player luck on join, respawn and every level change.
 */
public final class DimensionDifficulty {
    public static final Identifier MODIFIER_ID = Identifier.fromNamespaceAndPath(CustomDimensions.ID, "dimension_difficulty");
    public static final Identifier LUCK_ID = Identifier.fromNamespaceAndPath(CustomDimensions.ID, "dimension_luck");

    /** Set on a mob once its spawn scaling is decided, whether or not it scaled. */
    public static final AttachmentType<Boolean> SCALED = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(CustomDimensions.ID, "difficulty_scaled"), b -> b.persistent(Codec.BOOL));

    private static final Map<ResourceKey<Level>, Difficulty> BY_LEVEL = new HashMap<>();
    private static final Set<ResourceKey<Level>> PEACEFUL = new HashSet<>();
    private static final Set<ResourceKey<Level>> SUSPENDED = new HashSet<>();
    private static final Map<ResourceKey<Level>, List<Entity>> DISCARD = new HashMap<>();
    private static final List<BiConsumer<Entity, ServerLevel>> LOAD_WATCHERS = new ArrayList<>();

    private DimensionDifficulty() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(DimensionDifficulty::index);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BY_LEVEL.clear();
            PEACEFUL.clear();
            SUSPENDED.clear();
            DISCARD.clear();
        });
        ServerEntityEvents.ENTITY_LOAD.register(DimensionDifficulty::onLoad);
        ServerTickEvents.START_LEVEL_TICK.register(level -> {
            if (isPeaceful(level.dimension())) level.getChunkSource().setSpawnSettings(false);
            drain(level);
        });
        ServerTickEvents.END_LEVEL_TICK.register(DimensionDifficulty::drain);
        ServerPlayerEvents.JOIN.register(DimensionDifficulty::applyLuck);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> applyLuck(newPlayer));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> applyLuck(player));
    }

    private static void index(MinecraftServer server) {
        BY_LEVEL.clear();
        PEACEFUL.clear();
        LevelManager.get().ifPresent(manager -> {
            LevelIndex index = manager.index();
            JsonElement d = index.defaults().get("difficulty");
            JsonObject defaults = d != null && d.isJsonObject() ? d.getAsJsonObject() : null;
            index.baseWorlds().forEach(w -> put(w.key(), w.definition(), defaults));
            index.lazy().values().forEach(e -> put(e.key(), e.definition(), defaults));
        });
        CustomDimensions.LOG.info("difficulty: {} dimensions, {} peaceful", BY_LEVEL.size(), PEACEFUL.size());
    }

    private static void put(ResourceKey<Level> key, Definition definition, JsonObject defaults) {
        if (definition == null) return;
        Difficulty difficulty = Difficulty.of(definition.difficulty(), defaults);
        BY_LEVEL.put(key, difficulty);
        if (!difficulty.hostileSpawning()) PEACEFUL.add(key);
    }

    public static Difficulty of(ResourceKey<Level> key) {
        return BY_LEVEL.getOrDefault(key, Difficulty.NONE);
    }

    public static boolean isPeaceful(ResourceKey<Level> key) {
        return PEACEFUL.contains(key) && !SUSPENDED.contains(key);
    }

    /** Self-test only: lifts {@code key}'s peaceful rule so a control run can show what it holds back. */
    static void suspendPeaceful(ResourceKey<Level> key, boolean suspended) {
        if (suspended) SUSPENDED.add(key);
        else SUSPENDED.remove(key);
    }

    /** Self-test only: sees every entity load after this mod's own handling. */
    static Runnable watchLoads(BiConsumer<Entity, ServerLevel> watcher) {
        LOAD_WATCHERS.add(watcher);
        return () -> LOAD_WATCHERS.remove(watcher);
    }

    private static void onLoad(Entity entity, ServerLevel level) {
        if (entity instanceof Mob mob && mob.getType().getCategory() == MobCategory.MONSTER) {
            if (isPeaceful(level.dimension())) {
                DISCARD.computeIfAbsent(level.dimension(), k -> new ArrayList<>()).add(mob);
            } else if (!mob.hasAttached(SCALED)) {
                mob.setAttached(SCALED, true);
                scale(mob, of(level.dimension()));
            }
        }
        if (!LOAD_WATCHERS.isEmpty()) List.copyOf(LOAD_WATCHERS).forEach(w -> w.accept(entity, level));
    }

    private static void drain(ServerLevel level) {
        List<Entity> pending = DISCARD.remove(level.dimension());
        if (pending == null) return;
        for (Entity e : pending) {
            if (!e.isRemoved()) e.discard();
        }
    }

    /** Applies {@code difficulty}'s multiplier at the mob's height; a scaled mob starts at full health. */
    static void scale(Mob mob, Difficulty difficulty) {
        double factor = difficulty.mobFactor(mob.getY());
        if (!difficulty.scales(mob.getY())) return;
        boolean health = difficulty.health() && multiply(mob, Attributes.MAX_HEALTH, factor);
        if (difficulty.damage()) multiply(mob, Attributes.ATTACK_DAMAGE, factor);
        if (difficulty.armor()) multiply(mob, Attributes.ARMOR, factor);
        if (difficulty.speed()) multiply(mob, Attributes.MOVEMENT_SPEED, Difficulty.speedFactor(factor));
        if (difficulty.knockback()) multiply(mob, Attributes.ATTACK_KNOCKBACK, factor);
        if (health) mob.setHealth(mob.getMaxHealth());
    }

    private static boolean multiply(Mob mob, Holder<Attribute> attribute, double factor) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null) return false;
        instance.addOrReplacePermanentModifier(
                new AttributeModifier(MODIFIER_ID, factor - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        return true;
    }

    /** The player's luck modifier for the level it stands in, replacing any other dimension's. */
    public static void applyLuck(ServerPlayer player) {
        AttributeInstance luck = player.getAttribute(Attributes.LUCK);
        if (luck == null) return;
        luck.removeModifier(LUCK_ID);
        double bonus = of(player.level().dimension()).luckBonus();
        if (bonus != 0.0) {
            luck.addPermanentModifier(new AttributeModifier(LUCK_ID, bonus, AttributeModifier.Operation.ADD_VALUE));
        }
    }
}
