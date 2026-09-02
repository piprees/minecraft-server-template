package com.customdimensions.client.mixin;

import com.customdimensions.client.ArrivalScreen;
import com.customdimensions.client.CustomDimensionsClient;
import com.customdimensions.client.PendingTransfer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Replaces the loading screen the player actually sees on a crossing.
 *
 * onPlayerRespawn calls joinWorld and then startWorldLoading, which builds a
 * second DownloadingTerrainScreen over WorldLoadingState and installs it with
 * setScreen — the one that stays up until chunks report ready. Suppressing only
 * joinWorld's leaves this one on screen.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerTerrainScreenMixin {
    @ModifyArg(
            method = "startWorldLoading",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MinecraftClient;setScreen(Lnet/minecraft/client/gui/screen/Screen;)V"),
            index = 0)
    private Screen customdimensions$skipPreloadedTerrainScreen(Screen original) {
        Identifier destination = PendingTransfer.peekDestination();
        if (destination == null) {
            return original;
        }
        CustomDimensionsClient.LOGGER.info("{} site=startWorldLoading dimension={}",
                CustomDimensionsClient.SUPPRESS_MARKER, destination);
        return new ArrivalScreen();
    }
}
