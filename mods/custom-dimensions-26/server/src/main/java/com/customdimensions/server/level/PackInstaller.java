package com.customdimensions.server.level;

import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.compiler.datapack.PackDrift;
import com.customdimensions.compiler.datapack.PackInputs;
import com.customdimensions.compiler.datapack.VanillaData;
import com.customdimensions.compiler.datapack.VersionTable;
import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.definition.DefinitionSettings;
import com.customdimensions.server.CustomDimensions;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Compiles the definitions ({@link LevelIndex#definitionsDir()}) into
 * {@code <game dir>/<level-name>/datapacks/customdimensions/} at mod
 * initialisation, before the world loads; vanilla enables a new pack in that
 * folder on its own. Vanilla's files come from the running game
 * ({@link RuntimeVanilla}), or from {@code -Dcustomdimensions.vanilla} when it
 * names an extracted copy. The pack is compiled at every start-up and
 * rewritten only when the result differs from the files on disk, so a
 * compiler change reaches the pack whatever the mod's version; the pack
 * records the hash of its inputs. A rewrite that changes the worldgen
 * of a dimension whose region folder already holds chunks logs one WARN per
 * dimension (D2). Without a definitions folder the world keeps the pack it has.
 */
public final class PackInstaller {
    public static final String VANILLA_PROPERTY = "customdimensions.vanilla";
    public static final String PACK_NAME = "customdimensions";
    /** Bytes of a region file's location and timestamp tables; a longer file holds at least one chunk. */
    static final long REGION_HEADER = 8192;

    /** What the last {@link #install()} did: compiled or found current, with its file count and vanilla source. */
    public record Result(boolean compiled, Path pack, String hash, int files, String vanilla, List<String> drift) {}

    private static Result last;

    private PackInstaller() {}

    public static Optional<Result> last() {
        return Optional.ofNullable(last);
    }

    public static void install() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Optional<Path> definitions = LevelIndex.definitionsDir();
        if (definitions.isEmpty()) {
            CustomDimensions.LOG.info("no definitions at {}; the world keeps the pack it has",
                    gameDir.resolve(LevelIndex.DEFINITIONS_FALLBACK));
            return;
        }
        String levelName = levelName(gameDir.resolve("server.properties"));
        Path world = gameDir.resolve(levelName);
        Path pack = packDir(gameDir, levelName);
        String minecraft = minecraftVersion();
        String hash = PackInputs.hash(definitions.get(), modVersion(), minecraft);
        long start = System.nanoTime();
        VanillaData vanilla = vanilla();
        DatapackCompiler.Output output = DatapackCompiler.compile(definitions.get(), vanilla, VersionTable.forId(minecraft));
        SortedMap<String, String> before = PackDrift.read(pack);
        if (before.equals(dataFiles(output)) && Files.isRegularFile(pack.resolve("pack.mcmeta"))) {
            CustomDimensions.LOG.info("the pack in {} is current ({} files, inputs {})", pack, output.files().size(),
                    hash.substring(0, 12));
            last = new Result(false, pack, hash, output.files().size(), vanilla.source().describe(), List.of());
            return;
        }
        List<String> changed = PackDrift.changed(before, output);
        List<String> warnings = driftWarnings(world, namespace(definitions.get()), changed);
        warnings.forEach(CustomDimensions.LOG::warn);
        DatapackCompiler.write(output, pack, pack.resolve(DatapackCompiler.REPORT_FILE));
        PackInputs.write(pack, hash);
        CustomDimensions.LOG.info("compiled {} files into {} from {} in {} ms", output.files().size(), pack,
                vanilla.source().describe(), (System.nanoTime() - start) / 1_000_000);
        last = new Result(true, pack, hash, output.files().size(), vanilla.source().describe(), warnings);
    }

    /** The compiled files under {@code data/}, the part {@link PackDrift#read} reads back from disk. */
    static SortedMap<String, String> dataFiles(DatapackCompiler.Output output) {
        SortedMap<String, String> data = new TreeMap<>(output.files());
        data.keySet().removeIf(path -> !path.startsWith("data/"));
        return data;
    }

    /** {@code -Dcustomdimensions.vanilla} when set, else the running game. */
    public static VanillaData vanilla() {
        String property = System.getProperty(VANILLA_PROPERTY, "");
        return property.isEmpty() ? new VanillaData(RuntimeVanilla.load()) : new VanillaData(Path.of(property));
    }

    public static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
    }

    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer(CustomDimensions.ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    public static String namespace(Path definitions) {
        DefinitionSettings settings = DefinitionReader.readSettings(definitions.resolve("settings.json"));
        return settings.namespace() != null ? settings.namespace() : LevelIndex.DEFAULT_NAMESPACE;
    }

    /** One warning for each changed dimension whose region folder holds chunks. */
    static List<String> driftWarnings(Path world, String namespace, List<String> changed) {
        List<String> out = new ArrayList<>();
        for (String slug : changed) {
            if (holdsChunks(regionDir(world, namespace, slug))) {
                out.add(slug + ": the recompiled pack changes its worldgen and its region folder already holds chunks;"
                        + " generated chunks keep the old terrain and new ones get the new (D2, TROUBLESHOOTING.md#d2)");
            }
        }
        return out;
    }

    /** {@code <world>/dimensions/<namespace>/<path>/region} for a base world's level id or a lazy level's. */
    static Path regionDir(Path world, String namespace, String slug) {
        String id = LevelIndex.baseWorldKeys(namespace).getOrDefault(slug, namespace + ":" + slug);
        int colon = id.indexOf(':');
        return world.resolve("dimensions").resolve(id.substring(0, colon)).resolve(id.substring(colon + 1)).resolve("region");
    }

    public static boolean holdsChunks(Path region) {
        if (!Files.isDirectory(region)) return false;
        try (Stream<Path> files = Files.list(region)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".mca") && size(f) > REGION_HEADER);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    static Path packDir(Path gameDir, String levelName) {
        return gameDir.resolve(levelName).resolve("datapacks").resolve(PACK_NAME);
    }

    /** {@code level-name} from {@code server.properties}; {@code world} when the file or key is absent. */
    static String levelName(Path serverProperties) {
        if (!Files.isRegularFile(serverProperties)) return "world";
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(serverProperties)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String name = properties.getProperty("level-name", "").trim();
        return name.isEmpty() ? "world" : name;
    }
}
