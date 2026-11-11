package com.customdimensions.server.mixin;

import com.customdimensions.server.level.LevelGameRules;
import com.customdimensions.server.level.SharedWeather;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The weather cycle's timers move only when {@code advance_weather} holds and,
 * for the server's shared store, only in the first level to reach it this tick
 * ({@link SharedWeather}); a level with weather of its own always moves its own.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelWeatherMixin {
    @ModifyExpressionValue(method = "advanceWeatherCycle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
    private Object customdimensions$oncePerTick(Object advance) {
        if (!Boolean.TRUE.equals(advance)) return advance;
        ServerLevel level = (ServerLevel) (Object) this;
        if (LevelGameRules.ownsWeather(level.dimension())) return advance;
        return SharedWeather.claim(level.getServer().getTickCount());
    }
}
