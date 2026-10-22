package com.customdimensions.server;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CustomDimensions implements ModInitializer {
    public static final String ID = "customdimensions";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    @Override
    public void onInitialize() {
        SelfTest.register();
    }
}
