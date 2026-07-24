package com.customdimensions.config;

import com.customdimensions.MultiverseServer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Directory-based config loading for config/custom-dimensions/:
 *
 *   settings.json            global defaults (namespace, idle unload, frames,
 *                            defaults.borders / defaults.difficulty / defaults.frameBlock)
 *   dimensions/{slug}.json   one self-contained file per dimension
 *   overlay/dimensions/      consumer overrides (placed there by deploy.sh /
 *                            dev-up.sh from overlay/config/custom-dimensions/)
 *
 * Consumer overlay resolution per slug:
 *   - overlay file with top-level "overrides" -> deep-merge over the platform default
 *   - overlay file without "overrides"        -> replaces the platform default entirely
 *   - overlay file that is empty {}           -> dimension skipped entirely
 *   - no overlay file                         -> platform default
 *
 * Consumer-added slugs (overlay-only) get their namespace from the
 * BRAND_SLUG environment variable, falling back to the platform namespace.
 * Base-world slugs keep vanilla ids regardless of namespace.
 *
 * loadLegacy() converts the deprecated monolithic multiverse_config.json
 * (dimensions[] + portals[] + worlds[] + top-level worldSeed/frames) into
 * the same Map&lt;String, DimensionConfig&gt;.
 */
public final class DimensionConfigLoader {

    private static final Gson GSON = new Gson();

    private DimensionConfigLoader() {
    }

    /** Global settings (settings.json, or the legacy config's top-level fields). */
    public static class Settings {
        public String namespace = "adventure";
        public int idleUnloadMinutes = 5;
        public String frameOverworld = "minecraft:mossy_stone_bricks";
        public String frameNether = "minecraft:obsidian";
        public String frameEnd = "minecraft:end_stone_bricks";
        /** Raw "defaults" block, merged under every dimension. */
        public JsonObject defaults = new JsonObject();
    }

    /** Settings + resolved dimension map (base worlds included, keyed by slug). */
    public record LoadResult(Settings settings, Map<String, DimensionConfig> dimensions) {
    }

    // ------------------------------------------------------------------------
    // Directory format
    // ------------------------------------------------------------------------

    /** Prompt-facing surface: env-driven BRAND_SLUG, no settings defaults. */
    public static Map<String, DimensionConfig> loadAll(Path configDir, Path overlayDir, String namespace) {
        Settings settings = loadSettings(configDir.resolve("settings.json"));
        if (namespace != null && !namespace.isBlank()) {
            settings.namespace = namespace;
        }
        return loadDimensions(configDir, overlayDir, settings, System.getenv("BRAND_SLUG"));
    }

    public static LoadResult loadAllWithSettings(Path configDir, Path overlayDir) {
        Settings settings = loadSettings(configDir.resolve("settings.json"));
        Map<String, DimensionConfig> dims =
                loadDimensions(configDir, overlayDir, settings, System.getenv("BRAND_SLUG"));
        return new LoadResult(settings, dims);
    }

    static Settings loadSettings(Path settingsFile) {
        Settings settings = new Settings();
        JsonObject json = readJsonObject(settingsFile);
        if (json == null) {
            return settings;
        }
        if (json.has("namespace") && json.get("namespace").isJsonPrimitive()) {
            settings.namespace = json.get("namespace").getAsString();
        }
        if (json.has("idleUnloadMinutes") && json.get("idleUnloadMinutes").isJsonPrimitive()) {
            int minutes = json.get("idleUnloadMinutes").getAsInt();
            if (minutes > 0) {
                settings.idleUnloadMinutes = minutes;
            }
        }
        JsonObject frames = json.has("frames") && json.get("frames").isJsonObject()
                ? json.getAsJsonObject("frames") : new JsonObject();
        settings.frameOverworld = stringOr(frames, "overworld", settings.frameOverworld);
        settings.frameNether = stringOr(frames, "nether", settings.frameNether);
        settings.frameEnd = stringOr(frames, "end", settings.frameEnd);
        if (json.has("defaults") && json.get("defaults").isJsonObject()) {
            settings.defaults = json.getAsJsonObject("defaults");
        }
        return settings;
    }

    /**
     * Core resolution, testable without environment access: scan platform +
     * overlay dimension files, apply the overlay rules, merge settings
     * defaults under each result, stamp slug + namespace.
     */
    static Map<String, DimensionConfig> loadDimensions(Path configDir, Path overlayDir,
                                                       Settings settings, String brandSlug) {
        Map<String, JsonObject> platform = readDimensionFiles(configDir.resolve("dimensions"));
        Map<String, JsonObject> overlay = overlayDir != null
                ? readDimensionFiles(overlayDir.resolve("dimensions"))
                : Map.of();

        Map<String, JsonObject> resolved = new TreeMap<>();
        for (Map.Entry<String, JsonObject> entry : platform.entrySet()) {
            String slug = entry.getKey();
            JsonObject over = overlay.get(slug);
            if (over == null) {
                resolved.put(slug, entry.getValue());
                MultiverseServer.LOGGER.info("Dimension {}: platform default", slug);
            } else if (over.entrySet().isEmpty()) {
                MultiverseServer.LOGGER.info("Dimension {}: skipped by consumer overlay (empty {})", slug, "{}");
            } else if (over.has("overrides") && over.get("overrides").isJsonObject()) {
                resolved.put(slug, deepMerge(entry.getValue(), over.getAsJsonObject("overrides")));
                MultiverseServer.LOGGER.info("Dimension {}: platform default + consumer overrides", slug);
            } else {
                resolved.put(slug, over);
                MultiverseServer.LOGGER.info("Dimension {}: replaced by consumer overlay", slug);
            }
        }
        for (Map.Entry<String, JsonObject> entry : overlay.entrySet()) {
            String slug = entry.getKey();
            if (platform.containsKey(slug)) {
                continue;
            }
            JsonObject over = entry.getValue();
            if (over.entrySet().isEmpty()) {
                MultiverseServer.LOGGER.info("Dimension {}: empty consumer file with no platform default — ignored", slug);
                continue;
            }
            // "overrides" with nothing underneath still means "start from defaults".
            JsonObject body = over.has("overrides") && over.get("overrides").isJsonObject()
                    ? over.getAsJsonObject("overrides") : over;
            resolved.put(slug, body);
            MultiverseServer.LOGGER.info("Dimension {}: consumer-added", slug);
        }

        String consumerNamespace = brandSlug != null && !brandSlug.isBlank()
                ? brandSlug : settings.namespace;
        Map<String, DimensionConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> entry : resolved.entrySet()) {
            String slug = entry.getKey();
            JsonObject merged = deepMerge(defaultsFor(settings, entry.getValue()), entry.getValue());
            DimensionConfig config;
            try {
                config = GSON.fromJson(merged, DimensionConfig.class);
            } catch (JsonParseException e) {
                MultiverseServer.LOGGER.error("Dimension {}: config invalid — skipped", slug, e);
                continue;
            }
            if (config == null) {
                continue;
            }
            config.setName(slug);
            config.setNamespace(platform.containsKey(slug) ? settings.namespace : consumerNamespace);
            result.put(slug, config);
        }
        return result;
    }

    /**
     * Settings defaults merged UNDER a dimension: borders + difficulty always;
     * defaults.frameBlock only when the dimension actually declares a portal
     * (merging a frame into a portal-less dimension would invent one).
     */
    private static JsonObject defaultsFor(Settings settings, JsonObject dimension) {
        JsonObject base = new JsonObject();
        if (settings.defaults.has("borders") && settings.defaults.get("borders").isJsonObject()) {
            base.add("borders", settings.defaults.getAsJsonObject("borders").deepCopy());
        }
        if (settings.defaults.has("difficulty") && settings.defaults.get("difficulty").isJsonObject()) {
            base.add("difficulty", settings.defaults.getAsJsonObject("difficulty").deepCopy());
        }
        if (dimension.has("portal") && dimension.get("portal").isJsonObject()
                && settings.defaults.has("frameBlock")
                // A dimension declaring per-part materials owns its whole
                // frame — merging the default frameBlock under it would
                // trip the frameBlock/frameMaterials exclusivity warning.
                && !dimension.getAsJsonObject("portal").has("frameMaterials")) {
            JsonObject portal = new JsonObject();
            portal.add("frameBlock", settings.defaults.get("frameBlock"));
            base.add("portal", portal);
        }
        return base;
    }

    /** Recursive object merge: `over` wins; nested objects merge key-by-key. */
    static JsonObject deepMerge(JsonObject base, JsonObject over) {
        JsonObject result = base.deepCopy();
        for (Map.Entry<String, JsonElement> entry : over.entrySet()) {
            JsonElement existing = result.get(entry.getKey());
            JsonElement value = entry.getValue();
            if (existing != null && existing.isJsonObject() && value.isJsonObject()) {
                result.add(entry.getKey(), deepMerge(existing.getAsJsonObject(), value.getAsJsonObject()));
            } else {
                result.add(entry.getKey(), value.deepCopy());
            }
        }
        return result;
    }

    private static Map<String, JsonObject> readDimensionFiles(Path dir) {
        Map<String, JsonObject> files = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                String slug = file.getFileName().toString();
                slug = slug.substring(0, slug.length() - ".json".length()).toLowerCase();
                JsonObject json = readJsonObject(file);
                if (json != null) {
                    files.put(slug, json);
                } else {
                    MultiverseServer.LOGGER.warn("Dimension file unreadable or not an object — skipped: {}", file);
                }
            }
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to scan dimension directory: {}", dir, e);
        }
        return files;
    }

    private static JsonObject readJsonObject(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (IOException | JsonParseException e) {
            MultiverseServer.LOGGER.error("Failed to parse JSON file: {}", file, e);
            return null;
        }
    }

    private static String stringOr(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
    }

    // ------------------------------------------------------------------------
    // Legacy monolithic format (deprecated)
    // ------------------------------------------------------------------------

    /** Prompt-facing surface: legacy conversion, dimension map only. */
    public static Map<String, DimensionConfig> loadLegacy(Path monolithicConfig) {
        return loadLegacyWithSettings(monolithicConfig).dimensions();
    }

    /**
     * Convert the old multiverse_config.json: each dimensions[] entry becomes
     * a DimensionConfig keyed by name, with its portals[] entry (matched by
     * id == name) folded into "portal"; each worlds[] entry becomes a
     * base-world config (the top-level worldSeed lands on the overworld).
     */
    public static LoadResult loadLegacyWithSettings(Path monolithicConfig) {
        Settings settings = new Settings();
        Map<String, DimensionConfig> dims = new LinkedHashMap<>();
        JsonObject root = readJsonObject(monolithicConfig);
        if (root == null) {
            return new LoadResult(settings, dims);
        }

        settings.namespace = stringOr(root, "namespace", settings.namespace);
        settings.frameOverworld = stringOr(root, "frameOverworld", settings.frameOverworld);
        settings.frameNether = stringOr(root, "frameNether", settings.frameNether);
        settings.frameEnd = stringOr(root, "frameEnd", settings.frameEnd);
        if (root.has("idleUnloadMinutes") && root.get("idleUnloadMinutes").isJsonPrimitive()) {
            int minutes = root.get("idleUnloadMinutes").getAsInt();
            if (minutes > 0) {
                settings.idleUnloadMinutes = minutes;
            }
        }

        Map<String, JsonObject> portalsById = new LinkedHashMap<>();
        if (root.has("portals") && root.get("portals").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("portals")) {
                if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                    portalsById.put(el.getAsJsonObject().get("id").getAsString(), el.getAsJsonObject());
                }
            }
        }

        JsonArray dimensions = root.has("dimensions") && root.get("dimensions").isJsonArray()
                ? root.getAsJsonArray("dimensions") : new JsonArray();
        for (JsonElement el : dimensions) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("name")) {
                continue;
            }
            JsonObject dim = el.getAsJsonObject().deepCopy();
            String slug = dim.get("name").getAsString();
            dim.remove("name");
            JsonObject portal = portalsById.get(slug);
            if (portal != null && !dim.has("portal")) {
                dim.add("portal", legacyPortalBlock(portal));
            }
            DimensionConfig config = GSON.fromJson(dim, DimensionConfig.class);
            config.setName(slug);
            config.setNamespace(settings.namespace);
            dims.put(slug, config);
        }

        JsonArray worlds = root.has("worlds") && root.get("worlds").isJsonArray()
                ? root.getAsJsonArray("worlds") : new JsonArray();
        for (JsonElement el : worlds) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("name")) {
                continue;
            }
            JsonObject world = el.getAsJsonObject().deepCopy();
            String slug = world.get("name").getAsString();
            world.remove("name");
            if ("overworld".equals(slug) && !world.has("seed") && root.has("worldSeed")) {
                world.add("seed", root.get("worldSeed"));
            }
            DimensionConfig config = GSON.fromJson(world, DimensionConfig.class);
            config.setName(slug);
            config.setNamespace(settings.namespace);
            dims.put(slug, config);
        }
        // A worldSeed with no worlds[] overworld entry still needs a carrier.
        if (root.has("worldSeed") && !dims.containsKey("overworld")) {
            JsonObject ow = new JsonObject();
            ow.add("seed", root.get("worldSeed"));
            DimensionConfig config = GSON.fromJson(ow, DimensionConfig.class);
            config.setName("overworld");
            config.setNamespace(settings.namespace);
            dims.put("overworld", config);
        }
        return new LoadResult(settings, dims);
    }

    /** portals[] entry -> the v4 "portal" block (target/id are implicit now). */
    private static JsonObject legacyPortalBlock(JsonObject portal) {
        JsonObject block = new JsonObject();
        for (String key : new String[]{"frameBlock", "igniterItem", "color", "lightLevel",
                "scale", "cooldown", "particleType", "igniteSound", "enterSound", "exitSound"}) {
            if (portal.has(key)) {
                block.add(key, portal.get(key));
            }
        }
        return block;
    }
}
