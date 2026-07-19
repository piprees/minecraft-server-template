package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Per-dimension mob difficulty and player luck, absorbed from the external
 * configurable-difficulty mod (v4 Phase 2). Driven entirely by each
 * dimension's DimensionConfig.difficulty block:
 *
 *   mobMultiplier   overall scaling; final = mobMultiplier x depth factor
 *   attributes      which attributes scale (health/damage/armor default on,
 *                   speed/knockback default off — matches the old config)
 *   depthScaling    mobs harder underground (explicitly configured per
 *                   dimension; the migration puts it on overworld.json only,
 *                   matching the old mod's overworld-only behaviour)
 *   playerLuck      loot-quality luck applied to players inside the dimension
 *
 * Mob modifiers are applied ONCE at natural spawn (MobAttributeMixin →
 * MobEntity.initialize TAIL) and persist in the entity's NBT — mobs loaded
 * from disk keep their scaling without re-application. Hostile (MONSTER
 * spawn group) mobs only, like the old applyToHostile-only default.
 * A multiplier of 0 means "peaceful dimension, spawning already blocked"
 * and is deliberately a no-op (0x health would insta-kill every mob).
 */
public final class DifficultyManager {

    private static final Identifier MODIFIER_ID = Identifier.of("customdimensions", "dimension_difficulty");
    private static final Identifier LUCK_MODIFIER_ID = Identifier.of("customdimensions", "dimension_luck");

    private DifficultyManager() {
    }

    /** The dimension config governing a world: base worlds by full id, managed custom dims by path. */
    public static DimensionConfig configFor(RegistryKey<World> worldKey) {
        MultiverseConfig config = MultiverseConfig.getInstance();
        Identifier id = worldKey.getValue();
        for (String base : DimensionConfig.BASE_WORLDS) {
            DimensionConfig world = config.getWorld(base);
            if (world != null && world.getDimensionId().equals(id.toString())) {
                return world;
            }
        }
        if (config.isManagedNamespace(id.getNamespace())) {
            return DimensionManager.getInstance().resolveDefinition(id.getPath());
        }
        return null;
    }

    /** Depth factor: linear minMultiplier..maxMultiplier between startY and endY. */
    static double depthFactor(DimensionConfig.DepthScaling scaling, double y) {
        if (scaling == null || scaling.enabled == null || !scaling.enabled) {
            return 1.0;
        }
        int startY = scaling.startY != null ? scaling.startY : 64;
        int endY = scaling.endY != null ? scaling.endY : -64;
        double min = scaling.minMultiplier != null ? scaling.minMultiplier : 1.0;
        double max = scaling.maxMultiplier != null ? scaling.maxMultiplier : 1.5;
        if (startY == endY || y >= startY) {
            return min;
        }
        if (y <= endY) {
            return max;
        }
        double t = (startY - y) / (double) (startY - endY);
        return min + (max - min) * t;
    }

    /** Effective multiplier for a mob at the given height, or 1.0 when unscaled. */
    public static double effectiveMultiplier(DimensionConfig config, double y) {
        if (config == null || config.getDifficulty() == null) {
            return 1.0;
        }
        DimensionConfig.Difficulty difficulty = config.getDifficulty();
        double base = difficulty.getMobMultiplier();
        if (base <= 0.0) {
            return 1.0; // peaceful: spawning is blocked elsewhere, never 0x-scale
        }
        return base * depthFactor(difficulty.depthScaling, y);
    }

    /** Called from MobAttributeMixin at MobEntity.initialize TAIL. */
    public static void applyMobModifiers(MobEntity mob) {
        if (mob.getWorld().isClient() || mob.getType().getSpawnGroup() != SpawnGroup.MONSTER) {
            return;
        }
        DimensionConfig config = configFor(mob.getWorld().getRegistryKey());
        if (config == null) {
            return;
        }
        double multiplier = effectiveMultiplier(config, mob.getY());
        if (Math.abs(multiplier - 1.0) < 1e-6) {
            return;
        }
        DimensionConfig.Attributes attrs = config.getDifficulty() != null
                ? config.getDifficulty().attributes : null;
        boolean scaledHealth = false;
        if (enabled(attrs != null ? attrs.health : null, true)) {
            scaledHealth = scale(mob, EntityAttributes.GENERIC_MAX_HEALTH, multiplier);
        }
        if (enabled(attrs != null ? attrs.damage : null, true)) {
            scale(mob, EntityAttributes.GENERIC_ATTACK_DAMAGE, multiplier);
        }
        if (enabled(attrs != null ? attrs.armor : null, true)) {
            scale(mob, EntityAttributes.GENERIC_ARMOR, multiplier);
        }
        if (enabled(attrs != null ? attrs.speed : null, false)) {
            // Speed scales gently: full multipliers make mobs uncatchable.
            scale(mob, EntityAttributes.GENERIC_MOVEMENT_SPEED, 1.0 + (multiplier - 1.0) * 0.25);
        }
        if (enabled(attrs != null ? attrs.knockback : null, false)) {
            scale(mob, EntityAttributes.GENERIC_ATTACK_KNOCKBACK, multiplier);
        }
        if (scaledHealth) {
            mob.setHealth(mob.getMaxHealth());
        }
    }

    /**
     * Player luck while inside a dimension (loot quality/bonus rolls). Luck
     * is an ADDITIVE attribute with default 0, so a playerLuck of 3.0 maps
     * to +2.0 luck — 1.0 means no modifier. Re-applied on join and on every
     * world change; removed when the dimension has no luck configured.
     */
    public static void applyPlayerLuck(ServerPlayerEntity player) {
        EntityAttributeInstance instance = player.getAttributeInstance(EntityAttributes.GENERIC_LUCK);
        if (instance == null) {
            return;
        }
        instance.removeModifier(LUCK_MODIFIER_ID);
        DimensionConfig config = configFor(player.getWorld().getRegistryKey());
        double luck = config != null && config.getDifficulty() != null
                ? config.getDifficulty().getPlayerLuck() : 1.0;
        if (Math.abs(luck - 1.0) < 1e-6) {
            return;
        }
        instance.addPersistentModifier(new EntityAttributeModifier(
                LUCK_MODIFIER_ID, luck - 1.0, EntityAttributeModifier.Operation.ADD_VALUE));
        MultiverseServer.LOGGER.debug("Player {} luck {} in {}",
                player.getName().getString(), luck, player.getWorld().getRegistryKey().getValue());
    }

    private static boolean enabled(Boolean flag, boolean fallback) {
        return flag != null ? flag : fallback;
    }

    private static boolean scale(MobEntity mob, RegistryEntry<EntityAttribute> attribute, double multiplier) {
        EntityAttributeInstance instance = mob.getAttributeInstance(attribute);
        if (instance == null) {
            return false;
        }
        // Idempotent under re-initialisation (mob conversion re-runs initialize).
        instance.removeModifier(MODIFIER_ID);
        instance.addPersistentModifier(new EntityAttributeModifier(
                MODIFIER_ID, multiplier - 1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        return true;
    }
}
