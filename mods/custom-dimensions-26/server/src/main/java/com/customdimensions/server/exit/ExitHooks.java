package com.customdimensions.server.exit;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;

/** Fabric events for exits, death rules and wins; no mixin. */
public final class ExitHooks {
    /** After every other allow-death listener, so a death another mod cancels takes nothing. */
    static final Identifier LATE = Identifier.fromNamespaceAndPath("customdimensions", "deaths");

    private ExitHooks() {
    }

    public static void register() {
        ServerTickEvents.START_LEVEL_TICK.register(ExitConditions::tick);
        ServerTickEvents.END_LEVEL_TICK.register(Wins::levelTick);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ExitConditions.serverTick(server);
            DeathRules.serverTick(server);
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer player && entity.level() instanceof ServerLevel level)
                        || ExitConditions.onDeath(player, level, source));
        ServerLivingEntityEvents.ALLOW_DEATH.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
        ServerLivingEntityEvents.ALLOW_DEATH.register(LATE, (entity, source, amount) -> {
            if (entity instanceof ServerPlayer player && entity.level() instanceof ServerLevel level) {
                DeathRules.beforeDeath(player, level, source);
            }
            return true;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> Wins.died(entity.getUUID()));
        ServerPlayerEvents.COPY_FROM.register(DeathRules::copyFrom);
        ServerPlayerEvents.AFTER_RESPAWN.register((from, to, alive) -> ExitConditions.afterRespawn(to, alive));
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed, source) -> {
            if (killer instanceof ServerPlayer player) {
                Wins.afterKill(level, player, killed);
            }
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl
                    && player.getItemInHand(hand).is(Items.ENDER_PEARL) && ExitConditions.onEnderPearl(sp, sl)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ExitConditions.forget(handler.getPlayer().getUUID());
            DeathRules.forget(handler.getPlayer().getUUID());
            Wins.forget(handler.getPlayer().getUUID());
        });
    }
}
