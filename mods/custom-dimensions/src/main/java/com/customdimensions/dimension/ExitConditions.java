package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exit conditions ("exits" config block): ways OUT of a dimension without
 * a portal — void falls, death (generally or by cause), ender pearls,
 * surviving long falls. Boot-re-read like portal config.
 *
 * Design principles (exit-shrine-structure.md): exit conditions ADD
 * routes, never remove them; every configured exit resolves to a safe
 * arrival (surface-resolved via ExitTarget, slow-falling on sky drops);
 * "void": {"action": "kill"} is an explicit vanilla opt-in, not a
 * default. A per-player cooldown stops trigger loops (e.g. chained void
 * dims ping-ponging a falling player).
 *
 * Hooks: tick() from ServerWorldMixin (void + fallFrom — runs at tick
 * HEAD, before vanilla void damage); ALLOW_DEATH + UseItemCallback
 * registered in MultiverseServer; PlayerRespawnRedirectMixin consumes
 * pending respawn overrides.
 */
public final class ExitConditions {

    private static final int TRIGGER_COOLDOWN_TICKS = 100;
    /** Blocks below minY before the void trigger fires (vanilla damages at minY - 64). */
    private static final int VOID_TRIGGER_DEPTH = 8;

    private static final Map<UUID, Long> lastTrigger = new ConcurrentHashMap<>();
    /** uuid -> canonical exit-target string, consumed at next respawn. */
    private static final Map<UUID, String> pendingRespawns = new ConcurrentHashMap<>();

    private ExitConditions() {
    }

    private static DimensionConfig configFor(ServerWorld world) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        if (!MultiverseConfig.getInstance().isManagedNamespace(key.getValue().getNamespace())) {
            return null;
        }
        return DimensionManager.getInstance().resolveDefinition(key.getValue().getPath());
    }

    private static boolean onCooldown(ServerPlayerEntity player, ServerWorld world) {
        Long last = lastTrigger.get(player.getUuid());
        return last != null && world.getServer().getTicks() - last < TRIGGER_COOLDOWN_TICKS;
    }

    private static void markTriggered(ServerPlayerEntity player, ServerWorld world) {
        lastTrigger.put(player.getUuid(), (long) world.getServer().getTicks());
    }

    /** Per-tick pass (void + fallFrom). Called from ServerWorldMixin tick HEAD. */
    public static void tick(ServerWorld world) {
        DimensionConfig def = configFor(world);
        if (def == null || def.getExits().isEmpty()) {
            return;
        }
        for (ServerPlayerEntity player : java.util.List.copyOf(world.getPlayers())) {
            if (player.isRemoved() || player.isSpectator() || onCooldown(player, world)) {
                continue;
            }
            DimensionConfig.ExitRule voidRule = def.getExits().get("void");
            if (voidRule != null && player.getY() < world.getBottomY() - VOID_TRIGGER_DEPTH) {
                if (!"kill".equals(voidRule.getAction())) {
                    fire(player, world, voidRule, "void", true);
                }
                continue;  // "kill" = explicit vanilla opt-in, let it fall
            }
            DimensionConfig.ExitRule fallRule = def.getExits().get("fallFrom");
            if (fallRule != null && !player.isOnGround() && !player.isTouchingWater()
                    && player.fallDistance >= fallRule.getMinHeight()) {
                fire(player, world, fallRule, "fallFrom", true);
            }
        }
    }

    /** Ender pearl use in a dim with an "enderPearl" rule: true = handled (cancel the throw). */
    public static boolean handleEnderPearl(ServerPlayerEntity player, ServerWorld world) {
        DimensionConfig def = configFor(world);
        if (def == null) {
            return false;
        }
        DimensionConfig.ExitRule rule = def.getExits().get("enderPearl");
        if (rule == null || onCooldown(player, world)) {
            return false;
        }
        return fire(player, world, rule, "enderPearl", false);
    }

    /**
     * ALLOW_DEATH hook. Returns false to CANCEL the death ("teleport"
     * action: the run continues somewhere else); true lets it proceed
     * ("respawnAt" queues a one-shot respawn override; "kill" or no rule
     * is vanilla). Cause keys resolve most-specific first:
     * death:mob:&lt;entity_id&gt; &gt; death:&lt;damage_type_path&gt; &gt; death.
     */
    public static boolean onDeath(ServerPlayerEntity player, ServerWorld world, DamageSource source) {
        DimensionConfig def = configFor(world);
        if (def == null || def.getExits().isEmpty()) {
            return true;
        }
        DimensionConfig.ExitRule rule = null;
        String matched = null;
        Entity attacker = source.getAttacker();
        if (attacker != null) {
            String mobKey = "death:mob:" + net.minecraft.registry.Registries.ENTITY_TYPE
                    .getId(attacker.getType());
            rule = def.getExits().get(mobKey);
            matched = mobKey;
        }
        if (rule == null) {
            String causePath = source.getTypeRegistryEntry().getKey()
                    .map(k -> k.getValue().getPath()).orElse("");
            if (!causePath.isEmpty()) {
                String causeKey = "death:" + causePath;
                rule = def.getExits().get(causeKey);
                matched = causeKey;
            }
        }
        if (rule == null) {
            rule = def.getExits().get("death");
            matched = "death";
        }
        if (rule == null || "kill".equals(rule.getAction())) {
            return true;
        }
        if ("respawnAt".equals(rule.getAction())) {
            ExitTarget target = ExitTarget.parse(rule.target);
            if (target != null) {
                // Pre-warm an unloaded runtime target now — the death-screen
                // seconds are usually enough for the END_SERVER_TICK load.
                target.resolve(player, world);
                pendingRespawns.put(player.getUuid(), target.canonical());
                MultiverseServer.LOGGER.info("Player {} died in {} ({}) — respawn redirected to {}",
                        player.getName().getString(), def.getName(), matched, target.canonical());
            }
            return true;
        }
        // "teleport": cancel the death entirely and leave instead.
        if (onCooldown(player, world)) {
            return true;  // trigger-looping? die normally rather than loop
        }
        if (fire(player, world, rule, matched, true)) {
            player.setHealth(Math.max(6.0f, player.getHealth()));
            player.extinguish();
            return false;
        }
        return true;
    }

    /** One-shot respawn override for PlayerRespawnRedirectMixin; null = vanilla. */
    public static TeleportTarget consumePendingRespawn(ServerPlayerEntity player) {
        String canonical = pendingRespawns.remove(player.getUuid());
        if (canonical == null) {
            return null;
        }
        ExitTarget target = ExitTarget.parse(canonical);
        if (target == null) {
            return null;
        }
        ServerWorld current = (ServerWorld) player.getWorld();
        ExitTarget.Destination dest = target.resolve(player, current);
        if (dest == null) {
            MultiverseServer.LOGGER.warn(
                    "Respawn redirect to {} not ready (world still loading) — vanilla respawn instead",
                    canonical);
            return null;
        }
        return new TeleportTarget(dest.world(), dest.pos(), Vec3d.ZERO,
                player.getYaw(), player.getPitch(), TeleportTarget.NO_OP);
    }

    // Resolve + teleport for immediate triggers. slowFall guards sky-drop
    // arrivals (never surprise-kill: the fall that triggered the exit must
    // not finish the job at the destination). False = not ready this tick.
    private static boolean fire(ServerPlayerEntity player, ServerWorld world,
                                DimensionConfig.ExitRule rule, String trigger, boolean slowFall) {
        ExitTarget target = ExitTarget.parse(rule.target);
        if (target == null) {
            MultiverseServer.LOGGER.warn("Dimension {}: exits.{} has an invalid target — ignored",
                    world.getRegistryKey().getValue(), trigger);
            return false;
        }
        ExitTarget.Destination dest = target.resolve(player, world);
        if (dest == null) {
            return false;  // target world loading — retry next tick
        }
        markTriggered(player, world);
        player.fallDistance = 0.0f;
        if (slowFall) {
            player.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.SLOW_FALLING, 15 * 20, 0, false, false, true));
        }
        player.teleport(dest.world(), dest.pos().x, dest.pos().y, dest.pos().z,
                Set.of(), player.getYaw(), player.getPitch());
        MultiverseServer.LOGGER.info("Player {} left {} via exits.{} -> {}",
                player.getName().getString(), world.getRegistryKey().getValue().getPath(),
                trigger, target.canonical());
        return true;
    }

    /** Session hygiene: forget per-player state on disconnect. */
    public static void forgetPlayer(UUID uuid) {
        lastTrigger.remove(uuid);
        pendingRespawns.remove(uuid);
    }
}
