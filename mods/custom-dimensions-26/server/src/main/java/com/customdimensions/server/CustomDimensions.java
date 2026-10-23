package com.customdimensions.server;

import com.customdimensions.server.level.LevelCommands;
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
        LevelCommands.register();
        Portals.register();
        SelfTest.register();
    }
}
