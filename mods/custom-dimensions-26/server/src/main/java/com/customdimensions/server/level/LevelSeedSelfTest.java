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
        String mode = System.getProperty(LevelSelfTest.PERSIST_PROPERTY, "");
        if (!mode.isEmpty()) lines.add(persistence(server, mode));
        return lines;
    }

    /**
     * The two-boot check ({@code -PselfTestPersist=write|verify}): each base world's
     * marker column at 8, 8 (every block from the floor to the top, as a hash)
     * and its recorded seed, written on the first boot and compared on the second.
     */
    static String persistence(MinecraftServer server, String mode) {
        java.nio.file.Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve(PERSIST_FILE);
        List<String> now = new ArrayList<>();
        LevelManager manager = LevelManager.get().orElseThrow();
        for (LevelIndex.BaseWorld base : manager.index().baseWorlds()) {
            ServerLevel level = server.getLevel(base.key());
            if (level == null) continue;
            now.add(base.key().identifier() + " " + level.getSeed() + " " + column(level) + " "
                    + LevelSeeds.readRecord(LevelSeeds.dimensionPath(server, base.key())));
        }
        try {
            if ("write".equals(mode)) {
                java.nio.file.Files.write(file, now);
                return "PASS seeds persist write: " + now.size() + " base worlds' marker columns and records written to " + file.getFileName();
            }
            List<String> then = java.nio.file.Files.readAllLines(file);
            return (then.equals(now) ? "PASS" : "FAIL") + " seeds persist verify: each base world's seed, marker column and record "
                    + (then.equals(now) ? "match the first boot: " + now : "differ: then " + then + ", now " + now);
        } catch (java.io.IOException e) {
            return "FAIL seeds persist " + mode + ": " + e;
        }
    }

    static final String PERSIST_FILE = "customdimensions-seed-persist.txt";

    /** Every block of the column at 8, 8 in chunk 0, 0, from the floor up, as a hash. */
    static String column(ServerLevel level) {
        net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunk(0, 0);
        StringBuilder all = new StringBuilder();
        int top = -1;
        for (int y = level.getMinY(); y < level.getMinY() + level.getHeight(); y++) {
            var state = chunk.getBlockState(new net.minecraft.core.BlockPos(8, y, 8));
            all.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())).append(';');
            if (!state.isAir()) top = y;
        }
        return "top" + top + "#" + Integer.toHexString(all.toString().hashCode());
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
        String gateways = gateways(level, expected);
        if (gateways != null) wrong.add(gateways);
        String said = say(server, "execute in " + id + " run seed");
        if (!said.contains("[" + expected + "]")) wrong.add("seed command \"" + said + "\"");
        return (wrong.isEmpty() ? "PASS" : "FAIL") + " seeds " + id + ": " + expected
                + (wrong.isEmpty() ? " in getSeed, random state, structure state, structure check, biome zoom and the seed command"
                : ", but " + String.join(", ", wrong));
    }

    /**
     * A level with a dragon fight shuffles its twenty gateways under the seed its
     * constructor read; the list left is a run of that order. Null when it is, or
     * when the level has no fight.
     */
    static String gateways(ServerLevel level, long expected) {
        try {
            java.lang.reflect.Field f = ServerLevel.class.getDeclaredField("dragonFight");
            f.setAccessible(true);
            Object fight = f.get(level);
            if (fight == null) return null;
            java.lang.reflect.Field g = fight.getClass().getDeclaredField("gateways");
            g.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Integer> left = (List<Integer>) g.get(fight);
            List<Integer> order = new ArrayList<>(java.util.stream.IntStream.range(0, 20).boxed().toList());
            net.minecraft.util.Util.shuffle(order, net.minecraft.util.RandomSource.createThreadLocalInstance(expected));
            boolean run = left.isEmpty() || java.util.Collections.indexOfSubList(order, left) >= 0;
            return run ? null : "dragon fight gateways " + left + " are not a run of " + order;
        } catch (ReflectiveOperationException e) {
            return "dragon fight unreadable: " + e;
        }
    }

    private static String mixins() {
        List<String> missing = new ArrayList<>();
        if (!merged(ServerLevel.class, "customdimensions$ownSeed")) missing.add("ServerLevelSeedMixin getSeed");
        if (!merged(ServerLevel.class, "customdimensions$levelSeed")) missing.add("ServerLevelSeedMixin <init>");
        if (!merged(ServerLevel.class, "customdimensions$levelRules")) missing.add("ServerLevelGameRulesMixin");
        if (!merged(MinecraftServer.class, "customdimensions$ownZoomSeed")) missing.add("MinecraftServerLevelsMixin");
        if (!merged(ServerPlayer.class, "customdimensions$deathLevelRules")) missing.add("ServerPlayerRestoreMixin");
        if (!merged(ServerLevel.class, "customdimensions$levelWeather")) missing.add("ServerLevelGameRulesMixin weather");
        if (!merged(MinecraftServer.class, "customdimensions$levelValues")) missing.add("MinecraftServerGameRulesMixin");
        if (!merged(ServerLevel.class, "customdimensions$oncePerTick")) missing.add("ServerLevelWeatherMixin");
        return (missing.isEmpty() ? "PASS" : "FAIL") + " seed and game rule mixins merged: "
                + (missing.isEmpty() ? "all eight handlers" : "missing " + missing);
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
