package com.customdimensions.server.level;

import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.compiler.datapack.VanillaData;
import com.customdimensions.compiler.datapack.VersionTable;
import com.customdimensions.server.CustomDimensions;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Compiles the definitions into {@code <game dir>/<level-name>/datapacks/customdimensions/}
 * at mod initialisation, before the world loads, when
 * {@code -Dcustomdimensions.definitions} is set ({@code -Dcustomdimensions.vanilla}
 * names vanilla's extracted files). Vanilla enables a new pack in that folder
 * on its own. Without the property nothing is compiled and the world keeps the
 * pack it has.
 */
public final class PackInstaller {
    public static final String VANILLA_PROPERTY = "customdimensions.vanilla";
    public static final String PACK_NAME = "customdimensions";

    private PackInstaller() {}

    public static void install() {
        String definitions = System.getProperty(LevelIndex.DEFINITIONS_PROPERTY, "");
        if (definitions.isEmpty()) return;
        String vanilla = System.getProperty(VANILLA_PROPERTY, "");
        if (vanilla.isEmpty()) {
            throw new IllegalStateException("-D" + LevelIndex.DEFINITIONS_PROPERTY + " is set but -D" + VANILLA_PROPERTY
                    + " is not; the pack cannot be compiled");
        }
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path pack = packDir(gameDir, levelName(gameDir.resolve("server.properties")));
        String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        long start = System.nanoTime();
        DatapackCompiler.Output output = DatapackCompiler.compile(Path.of(definitions), new VanillaData(Path.of(vanilla)),
                VersionTable.forId(version));
        DatapackCompiler.write(output, pack, pack.resolve(DatapackCompiler.REPORT_FILE));
        CustomDimensions.LOG.info("compiled {} files into {} in {} ms", output.files().size(), pack,
                (System.nanoTime() - start) / 1_000_000);
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
