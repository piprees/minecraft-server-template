package com.customdimensions.mixin;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class WorldLoaderMixin {
    @Inject(method = "createWorlds", at = @At("HEAD"))
    private void beforeCreateWorlds(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        MultiverseConfig.getInstance().setServer(server);
        MultiverseConfig.getInstance().load();
        PortalHelper.setServer(server);
        PortalHelper.loadPortalLinks();
        DimensionManager.getInstance().onServerStart(server);
        // SEED_ROLL_MODE: the seed roller boots with a candidate-only config
        // and drives registration/creation itself via /customdim create —
        // skipping the boot pass keeps roll boots fast and the registry clean.
        boolean seedRollMode = "true".equalsIgnoreCase(System.getenv("SEED_ROLL_MODE"));
        if (seedRollMode) {
            MultiverseServer.LOGGER.info("SEED_ROLL_MODE active — skipping boot dimension creation (use /customdim create)");
        } else {
            DimensionManager.getInstance().registerDimensions();
            DimensionManager.getInstance().bootCreateDimensions();
        }
    }

    @Inject(method = "shutdown", at = @At("HEAD"))
    private void onShutdown(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        PortalHelper.savePortalLinks();
        MultiverseServer.onServerStopping(server);
    }
}
