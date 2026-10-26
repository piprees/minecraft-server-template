package com.customdimensions.server;

import com.customdimensions.server.companion.CompanionNetwork;
import com.customdimensions.server.exit.ExitHooks;
import com.customdimensions.server.firewall.WorldgenFirewall;
import com.customdimensions.server.difficulty.DimensionDifficulty;
import com.customdimensions.server.command.CustomDimCommands;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.level.WorldgenRuntime;
import com.customdimensions.server.level.PackInstaller;
import com.customdimensions.server.portal.Portals;
import com.customdimensions.server.zone.ZoneTypes;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CustomDimensions implements ModInitializer {
    public static final String ID = "customdimensions";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    @Override
    public void onInitialize() {
        WorldgenFirewall.register();
        ZoneTypes.register();
        PackInstaller.install();
        LevelManager.register();
        WorldgenRuntime.register();
        CustomDimCommands.register();
        Portals.register();
        CompanionNetwork.register();
        ExitHooks.register();
        DimensionDifficulty.register();
        SelfTest.register();
    }
}
