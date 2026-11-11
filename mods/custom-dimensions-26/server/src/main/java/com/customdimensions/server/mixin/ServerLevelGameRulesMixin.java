package com.customdimensions.server.mixin;

import com.customdimensions.server.level.LevelGameRules;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A level with game rule values of its own answers from its {@link LevelGameRules.View}
 * over the server-wide store, and one that holds its own {@code advance_weather}
 * keeps weather of its own.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelGameRulesMixin {
    @ModifyReturnValue(method = "getGameRules", at = @At("RETURN"))
    private GameRules customdimensions$levelRules(GameRules store) {
        GameRules view = LevelGameRules.viewOrNull(((ServerLevel) (Object) this).dimension(), store);
        return view != null ? view : store;
    }

    @ModifyReturnValue(method = "getWeatherData", at = @At("RETURN"))
    private WeatherData customdimensions$levelWeather(WeatherData shared) {
        WeatherData own = LevelGameRules.weatherOrNull((ServerLevel) (Object) this, shared);
        return own != null ? own : shared;
    }
}
