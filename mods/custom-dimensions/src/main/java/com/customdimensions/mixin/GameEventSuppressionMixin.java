package com.customdimensions.mixin;

import com.customdimensions.config.MultiverseConfig;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.event.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops game-event dispatch in adventure: worlds that have no players.
 *
 * Game events are only observable through listeners (sculk sensors and
 * friends), and a listener's occlusion check RAYCASTS from the event to
 * the listener. During dimension activation that raycast can step into a
 * not-yet-generated chunk, forcing a synchronous getChunkBlocking on the
 * main thread — which loads that chunk's persisted entities, whose remount
 * (ENTITY_MOUNT) emits another game event, and the cascade wedges the
 * server for minutes to hours (observed in production, 2026-07-11: main
 * thread parked under GameEventDispatchManager -> raycast -> getChunk).
 *
 * In a world with zero players there is nothing gameplay-relevant a
 * listener can do, so dispatch is pure cost: skip it entirely. The moment
 * a player enters the dimension, dispatch resumes and sculk behaves
 * normally. Vanilla worlds (minecraft:*) are never touched.
 */
@Mixin(ServerWorld.class)
public class GameEventSuppressionMixin {
    @Inject(method = "emitGameEvent(Lnet/minecraft/registry/entry/RegistryEntry;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/world/event/GameEvent$Emitter;)V", at = @At("HEAD"), cancellable = true)
    private void customdimensions$skipGameEventsInEmptyWorlds(RegistryEntry<GameEvent> event, Vec3d emitterPos, GameEvent.Emitter emitter, CallbackInfo ci) {
        ServerWorld world = (ServerWorld) (Object) this;
        if (!MultiverseConfig.getInstance().isManagedNamespace(world.getRegistryKey().getValue().getNamespace())) {
            return;
        }
        if (world.getPlayers().isEmpty()) {
            ci.cancel();
        }
    }
}
