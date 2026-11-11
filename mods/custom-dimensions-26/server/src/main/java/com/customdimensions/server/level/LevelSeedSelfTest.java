package com.customdimensions.server.level;

import com.customdimensions.core.definition.Seed;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * The self-test's seeds section (decisions.md RB-37), {@code -PselfTestOnly=seeds}:
 * the four mixins are merged, and each base world generates from its own seed
 * everywhere vanilla reads one: {@code getSeed()}, the chunk map's random state
 * and structure state, the structure check, the biome-zoom seed and the
 * {@code seed} command run in it, all equal to the recorded seed and, on a fresh
 * world, to its definition's.
 */
public final class LevelSeedSelfTest {
    private LevelSeedSelfTest() {
    }

    public static List<String> run(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        lines.add(mixins());
        LevelManager manager = LevelManager.get().orElseThrow();
        long world = LevelSeeds.worldSeed(server);
        for (LevelIndex.BaseWorld base : manager.index().baseWorlds()) {
            ServerLevel level = server.getLevel(base.key());
            if (level == null) {
                lines.add("FAIL seeds " + base.slug() + ": not loaded");
                continue;
            }
            Seed defined = base.definition() == null ? null : base.definition().seed();
            long wanted = LevelMaths.resolveSeed(defined, world);
            Long recorded = LevelSeeds.readRecord(LevelSeeds.dimensionPath(server, base.key()));
            if (recorded == null) {
                lines.add("FAIL seeds " + base.slug() + ": no " + LevelSeeds.RECORD + " in " + LevelSeeds.dimensionPath(server, base.key()));
                continue;
            }
            lines.add(seedLine(server, level, recorded) + "; definition " + (defined == null || defined.fromEnv() ? "env" : defined.value())
                    + (recorded == wanted ? "" : ", the recorded seed kept over it"));
        }
        return lines;
    }

    /** One level's seed, as each reader sees it, against {@code expected}. */
    public static String seedLine(MinecraftServer server, ServerLevel level, long expected) {
        String id = level.dimension().identifier().toString();
        List<String> wrong = new ArrayList<>();
        long seed = level.getSeed();
        if (seed != expected) wrong.add("getSeed " + seed);
        long random = level.getChunkSource().randomState().seed();
        if (random != expected) wrong.add("random state " + random);
        long structures = level.getChunkSource().getGeneratorState().getLevelSeed();
        if (structures != expected) wrong.add("structure state " + structures);
        long check = field(level, net.minecraft.server.level.ServerLevel.class, "structureCheck", "seed");
        if (check != expected) wrong.add("structure check " + check);
        long zoom = field(level.getBiomeManager(), "biomeZoomSeed");
        if (zoom != BiomeManager.obfuscateSeed(expected)) wrong.add("biome zoom " + zoom);
        String said = say(server, "execute in " + id + " run seed");
        if (!said.contains("[" + expected + "]")) wrong.add("seed command \"" + said + "\"");
        return (wrong.isEmpty() ? "PASS" : "FAIL") + " seeds " + id + ": " + expected
                + (wrong.isEmpty() ? " in getSeed, random state, structure state, structure check, biome zoom and the seed command"
                : ", but " + String.join(", ", wrong));
    }

    private static String mixins() {
        List<String> missing = new ArrayList<>();
        if (!merged(ServerLevel.class, "customdimensions$ownSeed")) missing.add("ServerLevelSeedMixin getSeed");
        if (!merged(ServerLevel.class, "customdimensions$levelSeed")) missing.add("ServerLevelSeedMixin <init>");
        if (!merged(ServerLevel.class, "customdimensions$levelRules")) missing.add("ServerLevelGameRulesMixin");
        if (!merged(MinecraftServer.class, "customdimensions$ownZoomSeed")) missing.add("MinecraftServerLevelsMixin");
        if (!merged(ServerPlayer.class, "customdimensions$deathLevelRules")) missing.add("ServerPlayerRestoreMixin");
        return (missing.isEmpty() ? "PASS" : "FAIL") + " seed and game rule mixins merged: "
                + (missing.isEmpty() ? "all five handlers" : "missing " + missing);
    }

    private static boolean merged(Class<?> target, String handler) {
        for (java.lang.reflect.Method m : target.getDeclaredMethods()) {
            if (m.getName().contains(handler)) return true;
        }
        return false;
    }

    private static long field(Object owner, Class<?> type, String name, String inner) {
        try {
            java.lang.reflect.Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return field(f.get(owner), inner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getSimpleName() + "." + name, e);
        }
    }

    private static long field(Object owner, String name) {
        try {
            java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getLong(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getClass().getSimpleName() + "." + name, e);
        }
    }

    /** What {@code command} said, its lines joined. */
    static String say(MinecraftServer server, String command) {
        List<String> out = new ArrayList<>();
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                out.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture), command);
        return String.join(" | ", out);
    }
}
