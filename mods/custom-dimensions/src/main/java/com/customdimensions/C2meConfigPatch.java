package com.customdimensions;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forces {@code useDensityFunctionCompiler = false} into {@code config/c2me.toml}
 * before c2me reads it — every boot, every environment. c2me's density-function
 * compiler caches compiled functions across NoiseConfig creations and ignores
 * the per-dimension seed, so with it enabled every custom dimension silently
 * clones the main world's terrain (TROUBLESHOOTING.md#d6).
 *
 * <p>A preLaunch entrypoint because c2me reads its config during mod init and
 * then STRIPS the unknown key when it rewrites the file — so the key must be
 * present before init, and its absence afterwards is expected. The proof the
 * patch was honoured is c2me's own log line:
 * {@code Removing config entry .vanillaWorldGenOptimizations.useDensityFunctionCompiler
 * because it is not used}. The deploy/dev scripts apply the same patch as a
 * harmless second layer; this entrypoint is what covers a bare
 * {@code docker restart mc}.
 */
public final class C2meConfigPatch implements PreLaunchEntrypoint {

    /** MultiverseServer's logger initialises Minecraft classes — too early here. */
    private static final Logger LOGGER = LoggerFactory.getLogger("customdimensions-prelaunch");

    static final String SECTION = "[vanillaWorldGenOptimizations]";
    static final String KEY = "useDensityFunctionCompiler";
    private static final Pattern KEY_LINE =
            Pattern.compile("(?m)^([ \\t]*" + KEY + "[ \\t]*=[ \\t]*)\\S+");

    @Override
    public void onPreLaunch() {
        if (!FabricLoader.getInstance().isModLoaded("c2me")) {
            return;
        }
        Path config = FabricLoader.getInstance().getConfigDir().resolve("c2me.toml");
        try {
            String original = Files.exists(config)
                    ? Files.readString(config, StandardCharsets.UTF_8) : "";
            String patched = patch(original);
            if (!patched.equals(original)) {
                Files.createDirectories(config.getParent());
                Files.writeString(config, patched, StandardCharsets.UTF_8);
            }
            LOGGER.info("c2me density-function compiler forced off (config/c2me.toml) — "
                    + "per-dimension seeds depend on it");
        } catch (IOException e) {
            LOGGER.error("FAILED to force {} = false into {} — every custom dimension "
                    + "will silently clone the main world's terrain this boot. "
                    + "Fix the config file permissions and restart.", KEY, config, e);
        }
    }

    /**
     * Idempotent: rewrites an existing key to false, inserts under an existing
     * section, or appends section + key. Mirrors the dev-up.sh/deploy.sh patch.
     */
    static String patch(String content) {
        Matcher existing = KEY_LINE.matcher(content);
        if (existing.find()) {
            return existing.replaceFirst(Matcher.quoteReplacement(existing.group(1)) + "false");
        }
        int section = content.indexOf(SECTION);
        if (section >= 0) {
            int insertAt = section + SECTION.length();
            return content.substring(0, insertAt)
                    + "\n\t" + KEY + " = false"
                    + content.substring(insertAt);
        }
        String separator = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
        return content + separator + "\n" + SECTION + "\n\t" + KEY + " = false\n";
    }
}
