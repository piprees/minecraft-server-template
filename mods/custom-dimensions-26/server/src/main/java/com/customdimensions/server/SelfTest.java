package com.customdimensions.server;

import com.customdimensions.compiler.zone.ZoneDensity;
import com.customdimensions.core.zone.ZoneMask;
import com.customdimensions.core.zone.ZoneShape;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

/**
 * Start-up checks against the running game, enabled by
 * {@code -Dcustomdimensions.selftest=<report file>}: the server writes the
 * report and stops. CI boots the mod this way; a line starting {@code FAIL}
 * fails the build.
 */
final class SelfTest {
    private static final String PROPERTY = "customdimensions.selftest";

    private SelfTest() {}

    static void register() {
        String report = System.getProperty(PROPERTY, "");
        if (report.isEmpty()) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> run(server, Path.of(report)));
    }

    private static void run(MinecraftServer server, Path report) {
        List<String> lines = new ArrayList<>();
        lines.add(check("zone mask parses and samples like core", () -> zoneMask(server)));
        try {
            Files.write(report, lines);
        } catch (IOException e) {
            CustomDimensions.LOG.error("self-test report not written", e);
        }
        lines.forEach(CustomDimensions.LOG::info);
        server.halt(false);
    }

    private static String check(String name, Check check) {
        try {
            return "PASS " + name + ": " + check.run();
        } catch (Throwable t) {
            return "FAIL " + name + ": " + t;
        }
    }

    /** Vanilla decodes the compiler's mask and its sampler matches core's maths. */
    private static String zoneMask(MinecraftServer server) {
        ZoneMask zone = new ZoneMask(37, -90, new ZoneShape.Ring(600, 1200), 120);
        RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        DensityFunction function = DensityFunction.CODEC.parse(ops, ZoneDensity.mask(zone)).getOrThrow();
        RandomState random = server.overworld().getChunkSource().randomState();
        Random points = new Random(11);
        double worst = 0;
        for (int i = 0; i < 2000; i++) {
            int x = 37 + points.nextInt(3001) - 1500;
            int z = -90 + points.nextInt(3001) - 1500;
            int y = points.nextInt(384) - 64;
            double error = Math.abs(random.sampleBlockValueUncached(function, x, y, z) - zone.at(x, z));
            worst = Math.max(worst, error);
        }
        if (worst > 2e-3) {
            throw new AssertionError("vanilla and core differ by " + worst);
        }
        return "2000 points, worst difference " + worst;
    }

    @FunctionalInterface
    private interface Check {
        String run() throws Exception;
    }
}
