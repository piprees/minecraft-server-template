package com.customdimensions.server.exit;

import com.customdimensions.core.config.DimensionConfig;
import com.customdimensions.core.dimension.ExitTarget;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.portal.PortalCatalogue;
import com.customdimensions.server.portal.PortalTravel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;

/**
 * A definition's {@code exits}: ways out of a dimension without a portal —
 * falling into the void, dying (generally or by cause), throwing an ender
 * pearl, a long fall. Each adds a route; none removes one.
 * <p>
 * Actions: {@code teleport} leaves at once (for a death it cancels the
 * death), {@code respawnAt} lets the death happen and moves the respawned
 * player to the target, {@code kill} leaves vanilla's behaviour (the explicit
 * opt-in for {@code void}). A per-player cooldown stops trigger loops, such as
 * a player falling from one void exit into another; a sky drop arrives with
 * slow falling so the fall that triggered the exit cannot finish the job.
 */
public final class ExitConditions {
    static final int TRIGGER_COOLDOWN_TICKS = 100;
    /** Blocks below the level's floor before the void trigger fires; vanilla's void damage starts at 64. */
    static final int VOID_TRIGGER_DEPTH = 8;
    static final int SLOW_FALL_TICKS = 15 * 20;
    static final int WARM_INTERVAL = 20;
    /** How long a respawn redirect waits for its target level to open before vanilla's respawn stands. */
    static final int RESPAWN_WAIT_TICKS = 20 * 60;

    private static final Map<UUID, Long> LAST_TRIGGER = new ConcurrentHashMap<>();
    /** At death: the canonical target the next respawn goes to. */
    private static final Map<UUID, String> PENDING_RESPAWNS = new ConcurrentHashMap<>();
    private static final List<Redirect> REDIRECTS = new ArrayList<>();

    private record Redirect(ServerPlayer player, ExitTarget target, long deadline) {
    }

    private ExitConditions() {
    }

    static DimensionConfig configFor(ServerLevel level) {
        return PortalCatalogue.dimension(level.dimension());
    }

    private static boolean onCooldown(ServerPlayer player, MinecraftServer server) {
        Long last = LAST_TRIGGER.get(player.getUUID());
        return last != null && server.getTickCount() - last < TRIGGER_COOLDOWN_TICKS;
    }

    /** Void and long-fall triggers, from the start of the level's tick, before vanilla's void damage. */
    static void tick(ServerLevel level) {
        DimensionConfig def = configFor(level);
        if (def == null || def.getExits().isEmpty()) {
            return;
        }
        if (!level.players().isEmpty()) {
            warm(level, def);
        }
        for (ServerPlayer player : List.copyOf(level.players())) {
            check(player, level, def);
        }
    }

    /** The void and long-fall triggers for one player in {@code level}. */
    public static void check(ServerPlayer player, ServerLevel level) {
        DimensionConfig def = configFor(level);
        if (def != null && !def.getExits().isEmpty()) {
            warm(level, def);
            check(player, level, def);
        }
    }

    /** Every {@link #WARM_INTERVAL} ticks while players are here: tickets on the open targets of {@code teleport} rules. */
    private static void warm(ServerLevel level, DimensionConfig def) {
        if (level.getGameTime() % WARM_INTERVAL != 0) {
            return;
        }
        for (DimensionConfig.ExitRule rule : def.getExits().values()) {
            ExitTarget target = "teleport".equals(rule.getAction()) ? ExitTarget.parse(rule.target) : null;
            if (target != null) {
                PortalTravel.warmExit(level.getServer(), target);
            }
        }
    }

    private static void check(ServerPlayer player, ServerLevel level, DimensionConfig def) {
        if (player.isRemoved() || player.isSpectator() || onCooldown(player, level.getServer())) {
            return;
        }
        DimensionConfig.ExitRule voidRule = def.getExits().get("void");
        if (voidRule != null && player.getY() < level.getMinY() - VOID_TRIGGER_DEPTH) {
            if (!"kill".equals(voidRule.getAction())) {
                fire(player, level, voidRule, "void", true);
            }
            return;
        }
        DimensionConfig.ExitRule fallRule = def.getExits().get("fallFrom");
        if (fallRule != null && !player.onGround() && !player.isInWater()
                && player.fallDistance >= fallRule.getMinHeight()) {
            fire(player, level, fallRule, "fallFrom", true);
        }
    }

    /** An ender pearl thrown where an {@code enderPearl} rule applies: true when it was taken as an exit. */
    public static boolean onEnderPearl(ServerPlayer player, ServerLevel level) {
        DimensionConfig def = configFor(level);
        if (def == null) {
            return false;
        }
        DimensionConfig.ExitRule rule = def.getExits().get("enderPearl");
        if (rule == null || onCooldown(player, level.getServer())) {
            return false;
        }
        return fire(player, level, rule, "enderPearl", false);
    }

    /**
     * Fabric's allow-death event: false cancels the death (a {@code teleport}
     * rule leaves instead). The rule is the most specific match:
     * {@code death:mob:<entity>}, then {@code death:<damage type path>}, then {@code death}.
     */
    public static boolean onDeath(ServerPlayer player, ServerLevel level, DamageSource source) {
        DimensionConfig def = configFor(level);
        if (def == null || def.getExits().isEmpty()) {
            return true;
        }
        String matched = ruleKey(def.getExits(), source);
        DimensionConfig.ExitRule rule = matched == null ? null : def.getExits().get(matched);
        if (rule == null || "kill".equals(rule.getAction())) {
            return true;
        }
        if ("respawnAt".equals(rule.getAction())) {
            ExitTarget target = ExitTarget.parse(rule.target);
            if (target != null) {
                // Opens a lazy target now: the seconds on the death screen are usually enough.
                PortalTravel.exitTransition(level.getServer(), player, target, TeleportTransition.DO_NOTHING);
                PENDING_RESPAWNS.put(player.getUUID(), target.canonical());
                CustomDimensions.LOG.info("{} died in {} ({}); respawn goes to {}", player.getName().getString(),
                        level.dimension().identifier(), matched, target.canonical());
            }
            return true;
        }
        if (onCooldown(player, level.getServer())) {
            return true;
        }
        if (fire(player, level, rule, matched, true)) {
            player.setHealth(Math.max(6.0f, player.getHealth()));
            player.clearFire();
            return false;
        }
        return true;
    }

    /** The exits key a death matches, most specific first; null when none does. */
    static String ruleKey(Map<String, DimensionConfig.ExitRule> exits, DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker != null) {
            String key = "death:mob:" + BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType());
            if (exits.containsKey(key)) return key;
        }
        String cause = source.typeHolder().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        if (!cause.isEmpty() && exits.containsKey("death:" + cause)) {
            return "death:" + cause;
        }
        return exits.containsKey("death") ? "death" : null;
    }

    /** Fabric's after-respawn event: a death that matched a {@code respawnAt} rule moves the new player on. */
    static void afterRespawn(ServerPlayer player, boolean alive) {
        if (alive) {
            return;
        }
        String canonical = PENDING_RESPAWNS.remove(player.getUUID());
        ExitTarget target = canonical == null ? null : ExitTarget.parse(canonical);
        if (target == null) {
            return;
        }
        sendOn(player, target);
    }

    /** Sends {@code player} to {@code target} now, or once it is ready, within {@link #RESPAWN_WAIT_TICKS}. */
    public static void sendOn(ServerPlayer player, ExitTarget target) {
        Redirect redirect = new Redirect(player, target, player.level().getServer().getTickCount() + RESPAWN_WAIT_TICKS);
        if (!tryRedirect(redirect)) {
            REDIRECTS.add(redirect);
        }
    }

    /** Redirects whose target was still opening, retried each server tick. */
    static void serverTick(MinecraftServer server) {
        Iterator<Redirect> it = REDIRECTS.iterator();
        while (it.hasNext()) {
            Redirect r = it.next();
            if (r.player.isRemoved() && r.player.connection == null) {
                it.remove();
            } else if (tryRedirect(r)) {
                it.remove();
            } else if (server.getTickCount() >= r.deadline) {
                CustomDimensions.LOG.warn("redirect to {} not ready after {} ticks; the player stays",
                        r.target.canonical(), RESPAWN_WAIT_TICKS);
                it.remove();
            }
        }
    }

    private static boolean tryRedirect(Redirect r) {
        ServerPlayer player = r.player;
        TeleportTransition t = PortalTravel.exitTransition(player.level().getServer(), player, r.target,
                TeleportTransition.DO_NOTHING);
        if (t == null) {
            return false;
        }
        player.teleport(t);
        CustomDimensions.LOG.info("{} respawned at {} via exits ({})", player.getName().getString(),
                t.newLevel().dimension().identifier(), r.target.canonical());
        return true;
    }

    /** Resolves and teleports; false while the target is not ready. */
    private static boolean fire(ServerPlayer player, ServerLevel level, DimensionConfig.ExitRule rule, String trigger,
                                boolean slowFall) {
        ExitTarget target = ExitTarget.parse(rule.target);
        if (target == null) {
            CustomDimensions.LOG.warn("{}: exits.{} has no valid target; ignored", level.dimension().identifier(), trigger);
            return false;
        }
        TeleportTransition t = PortalTravel.exitTransition(level.getServer(), player, target, TeleportTransition.DO_NOTHING);
        if (t == null) {
            return false;
        }
        LAST_TRIGGER.put(player.getUUID(), (long) level.getServer().getTickCount());
        player.resetFallDistance();
        if (slowFall) {
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, SLOW_FALL_TICKS, 0, false, false, true));
        }
        player.teleport(t);
        CustomDimensions.LOG.info("{} left {} via exits.{} to {}", player.getName().getString(),
                level.dimension().identifier(), trigger, target.canonical());
        return true;
    }

    static void forget(UUID id) {
        LAST_TRIGGER.remove(id);
        PENDING_RESPAWNS.remove(id);
    }

    /** For the self-test: clears the cooldown so consecutive checks are independent. */
    public static void clearCooldown(UUID id) {
        LAST_TRIGGER.remove(id);
    }
}
