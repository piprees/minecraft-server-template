package com.customdimensions.server;

import com.customdimensions.server.companion.CompanionNetwork;
import com.customdimensions.server.exit.ExitHooks;
import com.customdimensions.server.difficulty.DimensionDifficulty;
import com.customdimensions.server.command.CustomDimCommands;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.level.PackInstaller;
import com.customdimensions.server.portal.Portals;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CustomDimensions implements ModInitializer {
    public static final String ID = "customdimensions";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    @Override
    public void onInitialize() {
        PackInstaller.install();
        LevelManager.register();
        CustomDimCommands.register();
        Portals.register();
        CompanionNetwork.register();
        ExitHooks.register();
        DimensionDifficulty.register();
        SelfTest.register();
    }
}
