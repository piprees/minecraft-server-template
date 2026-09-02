package com.customdimensions.client.mixin;

import com.customdimensions.client.ArrivalScreen;
import com.customdimensions.client.CustomDimensionsClient;
import com.customdimensions.client.PendingTransfer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Replaces the loading screen on a traversal the server has already preloaded.
 *
 * joinWorld hands its DownloadingTerrainScreen to reset(Screen), NOT to
 * setScreen — read from the 1.21.1 bytecode, where reset is the invokevirtual
 * at offset 14 and calls setScreen itself at its own offset 31. Hooking
 * setScreen here matches nothing. Redirecting reset wholesale would skip the
 * sound stop and the cameraEntity/integratedServerConnection clears it also
 * performs, so the argument is what gets modified.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientJoinWorldMixin {
    @ModifyArg(
            method = "joinWorld",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MinecraftClient;reset(Lnet/minecraft/client/gui/screen/Screen;)V"),
            index = 0)
    private Screen customdimensions$skipPreloadedTerrainScreen(Screen original) {
        Identifier destination = PendingTransfer.consumeDestination();
        if (destination == null) {
            return original;
        }
        CustomDimensionsClient.LOGGER.info("{} dimension={}",
                CustomDimensionsClient.SUPPRESS_MARKER, destination);
        return new ArrivalScreen();
    }
}
