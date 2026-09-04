package com.customdimensions.client.mixin;

import com.customdimensions.client.ArrivalScreen;
import com.customdimensions.client.CustomDimensionsClient;
import com.customdimensions.client.PendingTransfer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen.WorldEntryReason;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What a crossing owes the client: the loading screen it installs, and the
 * projections the world it is leaving addressed.
 *
 * joinWorld hands its DownloadingTerrainScreen to reset(Screen), NOT to
 * setScreen — read from the 1.21.1 bytecode, where reset is the invokevirtual
 * at offset 14. Redirecting reset wholesale would skip the sound stop and the
 * cameraEntity/integratedServerConnection clears it also performs, so the
 * argument is what gets modified. The second screen is
 * ClientPlayNetworkHandlerTerrainScreenMixin's.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientJoinWorldMixin {

    /**
     * The crossing carries the new world's portal frame and first chunks in
     * the same batch as the join, so the stores are dropped here rather than
     * on the tick that follows — which would throw those away, and the server
     * sends neither a second time.
     */
    @Inject(method = "joinWorld", at = @At("HEAD"))
    private void customdimensions$dropProjectionsOnCrossing(ClientWorld world,
            WorldEntryReason reason, CallbackInfo info) {
        CustomDimensionsClient.enterWorld(world);
    }

    @ModifyArg(
            method = "joinWorld",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MinecraftClient;reset(Lnet/minecraft/client/gui/screen/Screen;)V"),
            index = 0)
    private Screen customdimensions$skipPreloadedTerrainScreen(Screen original) {
        Identifier destination = PendingTransfer.peekDestination();
        if (destination == null) {
            return original;
        }
        CustomDimensionsClient.LOGGER.info("{} site=joinWorld dimension={}",
                CustomDimensionsClient.SUPPRESS_MARKER, destination);
        return new ArrivalScreen();
    }
}
