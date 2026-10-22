package com.customdimensions.compiler.datapack;

import com.customdimensions.compiler.convert.JsonWriter;
import com.customdimensions.core.definition.Atmosphere;
import com.customdimensions.core.definition.BiomeEntry;
import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.definition.DefinitionSettings;
import com.customdimensions.core.definition.DimensionTypeFields;
import com.customdimensions.core.definition.Generator;
import com.customdimensions.core.definition.Liquids;
import com.customdimensions.core.definition.Rules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Compiles schema v2 definitions into an ordinary datapack: a dimension type
 * per definition, noise settings where liquids or the default block change,
 * level stems for the base worlds where vanilla loads them, and level stems
 * for every other dimension under {@code data/customdimensions/lazy_dimension/},
 * a folder vanilla ignores and the runtime opens on first entry. Every file
 * starts from the vanilla file of record for its generator. Output is
 * deterministic: sorted keys where vanilla sorts them, two-space indent,
 * a trailing newline.
 */
public final class DatapackCompiler {

    /** The runtime's namespace: lazy level stems live under it. */
    public static final String RUNTIME_NAMESPACE = "customdimensions";
    public static final String LAZY_FOLDER = "lazy_dimension";
    public static final String REPORT_FILE = "compile-report.md";
    static final String FALLBACK_NOTE = "Fallback biome source";

    /** Base worlds by slug: the level stem id vanilla loads at start (empty: the pack's namespace and the slug). */
    static final Map<String, String> BASE_WORLDS = Map.of(
            "overworld", "minecraft:overworld",
            "the_nether", "minecraft:the_nether",
            "the_end", "minecraft:the_end",
            "the_aviary", "");

    static final Set<String> OVERWORLD_FAMILY = Set.of("overworld", "large_biomes", "amplified", "caves", "floating_islands");

    /** {@code atmosphere.effects} to the vanilla dimension type whose sky it names. */
    static final Map<String, String> EFFECTS = Map.of(
            "minecraft:overworld", "overworld",
            "minecraft:the_nether", "the_nether",
            "minecraft:the_end", "the_end");
    /** What a sky brings with it: the skybox, cardinal lighting, clouds and distance fog. */
    static final List<String> EFFECT_FIELDS = List.of("skybox", "cardinal_light");
    static final List<String> EFFECT_ATTRIBUTES = List.of("minecraft:visual/cloud_color", "minecraft:visual/cloud_height",
            "minecraft:visual/fog_start_distance", "minecraft:visual/fog_end_distance");

    /** Vanilla's checkerboard {@code scale} when a definition sets no {@code cellScale}. */
    static final int DEFAULT_CHECKERBOARD_SCALE = 2;

    /** The compiled pack: pack-relative path to file text, and the report. */
    public record Output(SortedMap<String, String> files, CompileReport report, String reportText) {
    }

    private final VanillaData vanilla;
    private final VersionTable.Version version;
    private final String namespace;
    private final SortedMap<String, String> files = new TreeMap<>();
    private final CompileReport report;

    private DatapackCompiler(VanillaData vanilla, VersionTable.Version version, String namespace) {
        this.vanilla = vanilla;
        this.version = version;
        this.namespace = namespace;
        this.report = new CompileReport(version, namespace);
    }

    /** Compiles every definition in {@code definitionsDir} (with its {@code settings.json}). */
    public static Output compile(Path definitionsDir, VanillaData vanilla, VersionTable.Version version) {
        DefinitionSettings settings = DefinitionReader.readSettings(definitionsDir.resolve("settings.json"));
        String namespace = settings.namespace() != null ? settings.namespace() : "adventure";
        DatapackCompiler compiler = new DatapackCompiler(vanilla, version, namespace);
        for (Path file : definitionFiles(definitionsDir)) {
            String name = file.getFileName().toString();
            compiler.compile(name.substring(0, name.length() - ".json".length()), DefinitionReader.read(file));
        }
        compiler.files.put("pack.mcmeta", JsonWriter.write(packMeta(version, compiler.report.entries().size())));
        return new Output(compiler.files, compiler.report, compiler.report.markdown(compiler.files));
    }

    /** Compiles one definition on its own, for tests of a single mapping. */
    static Output compileOne(String namespace, String slug, Definition definition, VanillaData vanilla,
                             VersionTable.Version version) {
        DatapackCompiler compiler = new DatapackCompiler(vanilla, version, namespace);
        compiler.compile(slug, definition);
        return new Output(compiler.files, compiler.report, compiler.report.markdown(compiler.files));
    }

    /**
     * Writes the pack into {@code packDir}, replacing what an earlier compile left
     * there, and the report to {@code reportFile}.
     */
    public static void write(Output output, Path packDir, Path reportFile) {
        try {
            clean(packDir);
            for (Map.Entry<String, String> e : output.files().entrySet()) {
                Path target = packDir.resolve(e.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, e.getValue());
            }
            Files.createDirectories(reportFile.toAbsolutePath().getParent());
            Files.writeString(reportFile, output.reportText());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code --definitions <dir> --vanilla <dir> --pack <dir> --report <file> [--version <id>]}.
     */
    public static void main(String[] args) {
        Map<String, String> opts = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opts.put(args[i], args[i + 1]);
        }
        for (String required : List.of("--definitions", "--vanilla", "--pack", "--report")) {
            if (!opts.containsKey(required)) {
                throw new IllegalArgumentException("missing " + required
                        + "; usage: --definitions <dir> --vanilla <dir> --pack <dir> --report <file> [--version <id>]");
            }
        }
        VersionTable.Version version = VersionTable.forId(opts.getOrDefault("--version", VersionTable.V26_3.id()));
        Output output = compile(Path.of(opts.get("--definitions")), new VanillaData(Path.of(opts.get("--vanilla"))), version);
        write(output, Path.of(opts.get("--pack")), Path.of(opts.get("--report")));
        System.out.println("Compiled " + output.files().size() + " files into " + opts.get("--pack"));
        output.report().totals(output.files()).forEach((k, v) -> System.out.println("  " + k + ": " + v));
    }

    private void compile(String slug, Definition def) {
        CompileReport.Entry entry = report.add(slug);
        Generator generator = def.generator();
        if (Generator.CLONE.equals(generator.type())) {
            entry.kind(CompileReport.Kind.CLONE, "copies `" + generator.of() + "`; the runtime or W8 decides it");
            return;
        }
        String typeId = namespace + ":" + slug;
        String stemPath;
        if (BASE_WORLDS.containsKey(slug)) {
            String stemId = BASE_WORLDS.get(slug).isEmpty() ? namespace + ":" + slug : BASE_WORLDS.get(slug);
            stemPath = dataPath(stemId, "dimension");
            entry.kind(CompileReport.Kind.BASE_WORLD, "`" + stemId + "`");
        } else {
            stemPath = "data/" + RUNTIME_NAMESPACE + "/" + LAZY_FOLDER + "/" + slug + ".json";
            entry.kind(CompileReport.Kind.LAZY, "`" + typeId + "`");
        }

        JsonObject type = dimensionType(def, entry);
        put(dataPath(typeId, "dimension_type"), sorted(type), entry);

        JsonObject stem = new JsonObject();
        stem.addProperty("type", typeId);
        stem.add("generator", generator(slug, def, entry));
        put(stemPath, stem, entry);

        reportRuntimeFields(def, entry);
    }

    // ---- dimension type ---------------------------------------------------------------------

    /** The vanilla dimension type a definition starts from. */
    static String baseDimensionType(Generator generator) {
        if (!Generator.NOISE.equals(generator.type())) {
            return "overworld";
        }
        return switch (generator.preset()) {
            case "caves" -> "overworld_caves";
            case "nether" -> "the_nether";
            case "end" -> "the_end";
            default -> "overworld";
        };
    }

    private JsonObject dimensionType(Definition def, CompileReport.Entry entry) {
        JsonObject type = vanilla.dimensionType(baseDimensionType(def.generator()));
        applyDimensionType(type, def.dimensionType());
        applyAtmosphere(type, def.atmosphere(), entry);
        applyRules(type, def.rules());
        return type;
    }

    /** {@code dimensionType}: each set field replaces the vanilla field of the same name in snake case. */
    static void applyDimensionType(JsonObject type, DimensionTypeFields f) {
        if (f == null) {
            return;
        }
        setIfPresent(type, "min_y", f.minY());
        setIfPresent(type, "height", f.height());
        setIfPresent(type, "logical_height", f.logicalHeight());
        if (f.hasSkylight() != null) {
            type.addProperty("has_skylight", f.hasSkylight());
        }
        if (f.hasCeiling() != null) {
            type.addProperty("has_ceiling", f.hasCeiling());
        }
        if (f.infiniburn() != null) {
            type.addProperty("infiniburn", f.infiniburn());
        }
        if (f.monsterSpawnLightLevel() != null) {
            type.add("monster_spawn_light_level", f.monsterSpawnLightLevel().deepCopy());
        }
        setIfPresent(type, "monster_spawn_block_light_limit", f.monsterSpawnBlockLightLimit());
    }

    /**
     * {@code atmosphere}: {@code effects} brings the named world's sky (see
     * {@link #EFFECT_FIELDS} and {@link #EFFECT_ATTRIBUTES}); colours become the
     * {@code minecraft:visual/sky_color} and {@code fog_color} attributes;
     * {@code ambientLight} is {@code ambient_light}; {@code fixedTime} freezes
     * the dimension's timelines at that tick ({@link FixedTime}).
     */
    void applyAtmosphere(JsonObject type, Atmosphere a, CompileReport.Entry entry) {
        if (a == null) {
            return;
        }
        JsonObject attributes = attributes(type);
        if (a.effects() != null) {
            String world = EFFECTS.get(a.effects());
            if (world == null) {
                entry.unsupported("atmosphere.effects", "`" + a.effects() + "` is not a vanilla sky; known: "
                        + String.join(", ", new TreeMap<>(EFFECTS).keySet()));
            } else {
                JsonObject source = vanilla.dimensionType(world);
                JsonObject sourceAttributes = attributes(source);
                for (String field : EFFECT_FIELDS) {
                    copyOrRemove(source, type, field);
                }
                for (String attribute : EFFECT_ATTRIBUTES) {
                    copyOrRemove(sourceAttributes, attributes, attribute);
                }
            }
        }
        if (a.skyColor() != null) {
            attributes.addProperty("minecraft:visual/sky_color", colour(a.skyColor()));
        }
        if (a.fogColor() != null) {
            attributes.addProperty("minecraft:visual/fog_color", colour(a.fogColor()));
        }
        if (a.ambientLight() != null) {
            type.addProperty("ambient_light", a.ambientLight());
        }
        if (a.fixedTime() != null) {
            applyFixedTime(type, a.fixedTime(), entry);
        }
    }

    private void applyFixedTime(JsonObject type, long tick, CompileReport.Entry entry) {
        JsonArray timelines = new JsonArray();
        for (String id : vanilla.expandTimelines(type.get("timelines"))) {
            JsonObject timeline = vanilla.timeline(id);
            if (!FixedTime.isPeriodic(timeline)) {
                timelines.add(id);
                continue;
            }
            String name = id.substring(id.indexOf(':') + 1) + "_" + Math.floorMod(tick, timeline.get("period_ticks").getAsLong());
            String frozenId = namespace + ":fixed_time/" + name;
            put(dataPath(frozenId, "timeline"), sorted(FixedTime.freeze(timeline, tick)), entry);
            timelines.add(frozenId);
        }
        type.add("timelines", timelines);
        type.addProperty("has_fixed_time", true);
    }

    /**
     * {@code rules}: {@code bedsWork} takes the overworld's or the Nether's bed
     * rules; {@code respawnAnchorsWork}, {@code piglinsZombify} and {@code raids}
     * set their attributes; {@code ultraWarm} sets {@code water_evaporates} and
     * {@code fast_lava}; {@code natural} sets {@code nether_portal_spawns_piglin},
     * the one gameplay attribute vanilla's overworld has and its Nether lacks
     * that a 1.21.1 natural dimension implied.
     */
    void applyRules(JsonObject type, Rules r) {
        if (r == null) {
            return;
        }
        JsonObject attributes = attributes(type);
        if (r.bedsWork() != null) {
            JsonObject source = attributes(vanilla.dimensionType(r.bedsWork() ? "overworld" : "the_nether"));
            for (String rule : List.of("minecraft:gameplay/bed_rule", "minecraft:gameplay/straw_bed_rule")) {
                copyOrRemove(source, attributes, rule);
            }
        }
        setIfPresent(attributes, "minecraft:gameplay/respawn_anchor_works", r.respawnAnchorsWork());
        setIfPresent(attributes, "minecraft:gameplay/piglins_zombify", r.piglinsZombify());
        setIfPresent(attributes, "minecraft:gameplay/water_evaporates", r.ultraWarm());
        setIfPresent(attributes, "minecraft:gameplay/fast_lava", r.ultraWarm());
        setIfPresent(attributes, "minecraft:gameplay/can_start_raid", r.raids());
        setIfPresent(attributes, "minecraft:gameplay/nether_portal_spawns_piglin", r.natural());
    }

    // ---- generator --------------------------------------------------------------------------

    private JsonObject generator(String slug, Definition def, CompileReport.Entry entry) {
        Generator g = def.generator();
        return switch (g.type()) {
            case Generator.NOISE -> noiseGenerator(slug, def, g.preset(), noiseBiomeSource(def, entry), entry);
            case Generator.CHECKERBOARD -> noiseGenerator(slug, def, "overworld", checkerboard(def, entry), entry);
            case Generator.SINGLE_BIOME -> noiseGenerator(slug, def, "overworld", fixed(g.biome(), entry), entry);
            case Generator.FLAT -> flat(flatBiome(g.biome(), "minecraft:plains", entry), layers(g.layers()), def, entry);
            case Generator.VOID -> flat(voidBiome(def, entry), new JsonArray(), def, entry);
            default -> throw new IllegalStateException("generator type " + g.type());
        };
    }

    private JsonObject noiseGenerator(String slug, Definition def, String preset, JsonObject biomeSource,
                                      CompileReport.Entry entry) {
        JsonObject generator = new JsonObject();
        generator.addProperty("type", "minecraft:noise");
        generator.add("biome_source", biomeSource);
        generator.addProperty("settings", noiseSettings(slug, def, preset, entry));
        return generator;
    }

    /**
     * The noise settings id: the preset's vanilla settings (each preset is named
     * after them), or {@code <ns>:<slug>} written from them when liquids or the
     * default block change.
     */
    private String noiseSettings(String slug, Definition def, String preset, CompileReport.Entry entry) {
        Liquids liquids = def.liquids();
        String defaultBlock = defaultBlock(def);
        boolean changesLiquids = liquids != null && (liquids.seaLevel() != null || liquids.defaultFluid() != null);
        if (!changesLiquids && defaultBlock == null) {
            return "minecraft:" + preset;
        }
        JsonObject settings = vanilla.noiseSettings(preset);
        if (liquids != null && liquids.seaLevel() != null) {
            settings.addProperty("sea_level", liquids.seaLevel());
        }
        if (liquids != null && liquids.defaultFluid() != null) {
            settings.addProperty("default_fluid", liquids.defaultFluid());
        }
        if (version.noiseSettingsDefaultBlock()) {
            if (defaultBlock != null) {
                settings.addProperty("default_block", defaultBlock);
            }
        } else {
            settings.remove("default_block");
            if (defaultBlock != null) {
                entry.unsupported("materials.defaultBlock", "Minecraft " + version.id()
                        + " noise settings have no `default_block`; W3's material rules supply it");
            }
        }
        String id = namespace + ":" + slug;
        put(dataPath(id, "worldgen/noise_settings"), sorted(settings), entry);
        return id;
    }

    private static String defaultBlock(Definition def) {
        JsonObject materials = def.materials();
        if (materials == null || !materials.has("defaultBlock") || materials.get("defaultBlock").isJsonNull()) {
            return null;
        }
        return materials.get("defaultBlock").getAsString();
    }

    private JsonObject noiseBiomeSource(Definition def, CompileReport.Entry entry) {
        String preset = def.generator().preset();
        List<BiomeEntry> biomes = def.biomes();
        if (OVERWORLD_FAMILY.contains(preset)) {
            return biomes.isEmpty() ? multiNoisePreset("overworld") : filtered("overworld", biomes, entry);
        }
        if ("nether".equals(preset)) {
            return biomes.isEmpty() ? multiNoisePreset("nether") : filtered("nether", biomes, entry);
        }
        if (Boolean.FALSE.equals(def.generator().endIsland())) {
            entry.unsupported("generator.endIsland", "removing the central island is terrain, W1");
        }
        if (isNetherIslands(biomes)) {
            entry.note("Nether biomes in End terrain (nether_islands): the Nether parameter list is kept, "
                    + "but End terrain's climate does not spread them; that needs W2");
            return filtered("nether", biomes, entry);
        }
        if (!biomes.isEmpty()) {
            entry.unsupported("biomes", "the `minecraft:the_end` biome source places the End's own biomes; "
                    + "a list of " + biomes.size() + " needs W2");
        }
        JsonObject source = new JsonObject();
        source.addProperty("type", "minecraft:the_end");
        return source;
    }

    /** An End-preset definition whose biomes include any of the Nether's. */
    private boolean isNetherIslands(List<BiomeEntry> biomes) {
        Set<String> nether = new LinkedHashSet<>();
        for (JsonElement e : vanilla.biomeParameters("nether")) {
            nether.add(e.getAsJsonObject().get("biome").getAsString());
        }
        return biomes.stream().anyMatch(b -> nether.contains(b.id()));
    }

    private static JsonObject multiNoisePreset(String list) {
        JsonObject source = new JsonObject();
        source.addProperty("type", "minecraft:multi_noise");
        source.addProperty("preset", "minecraft:" + list);
        return source;
    }

    /**
     * {@code minecraft:multi_noise} with the vanilla {@code list} entries whose
     * biome the definition names, in vanilla's order.
     */
    JsonObject filtered(String list, List<BiomeEntry> biomes, CompileReport.Entry entry) {
        JsonArray parameters = vanilla.biomeParameters(list);
        Set<String> inList = new LinkedHashSet<>();
        for (JsonElement e : parameters) {
            inList.add(e.getAsJsonObject().get("biome").getAsString());
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (BiomeEntry b : biomes) {
            if (!vanilla.biomeIds().contains(b.id())) {
                entry.skippedBiome(b.id(), "no vanilla biome");
                continue;
            }
            if (!inList.contains(b.id())) {
                entry.skippedBiome(b.id(), "not in vanilla's " + list + " parameter list");
                continue;
            }
            wanted.add(b.id());
            if (b.climate() != null) {
                entry.unsupported("biomes[" + b.id() + "].climate", "vanilla's climate entries are kept; W2 partitions climate");
            }
        }
        JsonArray kept = new JsonArray();
        for (JsonElement e : parameters) {
            if (wanted.contains(e.getAsJsonObject().get("biome").getAsString())) {
                kept.add(e);
            }
        }
        if (kept.isEmpty()) {
            kept.add(MultiNoise.fullRange("minecraft:plains"));
            entry.note(FALLBACK_NOTE + ": no listed biome has a vanilla " + list
                    + " entry, so `minecraft:plains` covers all of climate space");
        }
        if (version.multiNoiseDisjoint()) {
            JsonArray before = kept;
            kept = MultiNoise.carve(before);
            if (!kept.equals(before)) {
                entry.note("Overlapping vanilla " + list + " entries carved apart for Minecraft " + version.id()
                        + " (" + before.size() + " entries became " + kept.size() + "); earlier entries keep their space");
            }
            int[] pair = MultiNoise.firstOverlap(kept);
            if (pair != null) {
                throw new IllegalStateException("multi-noise entries " + pair[0] + " and " + pair[1]
                        + " overlap, which Minecraft " + version.id() + " refuses");
            }
        }
        JsonObject source = new JsonObject();
        source.addProperty("type", "minecraft:multi_noise");
        source.add("biomes", kept);
        return source;
    }

    private JsonObject checkerboard(Definition def, CompileReport.Entry entry) {
        JsonArray biomes = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (BiomeEntry b : def.biomes()) {
            if (!vanilla.biomeIds().contains(b.id())) {
                entry.skippedBiome(b.id(), "no vanilla biome");
            } else if (seen.add(b.id())) {
                biomes.add(b.id());
            }
        }
        if (biomes.isEmpty()) {
            biomes.add("minecraft:plains");
            entry.note(FALLBACK_NOTE + ": no listed biome is vanilla, so the checkerboard is all `minecraft:plains`");
        }
        JsonObject source = new JsonObject();
        source.addProperty("type", "minecraft:checkerboard");
        source.add("biomes", biomes);
        Integer scale = def.generator().cellScale();
        source.addProperty("scale", scale != null ? scale : DEFAULT_CHECKERBOARD_SCALE);
        return source;
    }

    private JsonObject fixed(String biome, CompileReport.Entry entry) {
        JsonObject source = new JsonObject();
        source.addProperty("type", "minecraft:fixed");
        source.addProperty("biome", flatBiome(biome, "minecraft:plains", entry));
        return source;
    }

    private String flatBiome(String biome, String fallback, CompileReport.Entry entry) {
        if (biome == null) {
            return fallback;
        }
        if (!vanilla.biomeIds().contains(biome)) {
            entry.skippedBiome(biome, "no vanilla biome");
            entry.note(FALLBACK_NOTE + ": `" + fallback + "` replaces `" + biome + "`");
            return fallback;
        }
        return biome;
    }

    /** A void world's biome: the first vanilla biome it lists, else {@code minecraft:the_void}. */
    private String voidBiome(Definition def, CompileReport.Entry entry) {
        String chosen = null;
        for (BiomeEntry b : def.biomes()) {
            if (!vanilla.biomeIds().contains(b.id())) {
                entry.skippedBiome(b.id(), "no vanilla biome");
            } else if (chosen == null) {
                chosen = b.id();
            } else if (!chosen.equals(b.id())) {
                entry.skippedBiome(b.id(), "a void world takes one biome; `" + chosen + "` is first");
            }
        }
        return chosen != null ? chosen : "minecraft:the_void";
    }

    private static JsonArray layers(List<JsonObject> layers) {
        JsonArray out = new JsonArray();
        if (layers != null) {
            layers.forEach(l -> out.add(l.deepCopy()));
        }
        return out;
    }

    private static JsonObject flat(String biome, JsonArray layers, Definition def, CompileReport.Entry entry) {
        if (def.liquids() != null) {
            entry.unsupported("liquids", "a flat generator has no sea level or fluid");
        }
        if (defaultBlock(def) != null) {
            entry.unsupported("materials.defaultBlock", "a flat generator is made of its layers");
        }
        JsonObject settings = new JsonObject();
        settings.addProperty("biome", biome);
        settings.addProperty("features", false);
        settings.addProperty("lakes", false);
        settings.add("layers", layers);
        settings.add("structure_overrides", new JsonArray());
        JsonObject generator = new JsonObject();
        generator.addProperty("type", "minecraft:flat");
        generator.add("settings", settings);
        return generator;
    }

    // ---- report -----------------------------------------------------------------------------

    private void reportRuntimeFields(Definition def, CompileReport.Entry entry) {
        if (def.borders() != null) {
            entry.unsupported("borders", "world borders are set by the runtime, M2");
        }
        if (def.seed() != null && !BASE_WORLDS.containsKey(entry.slug())) {
            entry.unsupported("seed", "a level stem has no seed; the runtime seeds the level, M2");
        }
        if (def.spawn() != null) {
            entry.unsupported("spawn", "spawn is placed by the runtime, M2");
        }
        if (!def.zones().isEmpty()) {
            entry.unsupported("zones", def.zones().size() + " zone(s); zones compile in W2b");
        }
        JsonObject terrain = def.terrain();
        if (terrain != null && terrain.has("base") && !terrain.get("base").isJsonNull()) {
            entry.unsupported("terrain.base", "named terrain compiles in W1");
        }
        JsonObject materials = def.materials();
        if (materials != null) {
            for (String key : materials.keySet()) {
                if (!"defaultBlock".equals(key)) {
                    entry.unsupported("materials." + key, "material palettes compile in W3");
                }
            }
        }
        if (def.structures() != null && !def.structures().isEmpty()) {
            entry.unsupported("structures", "structure control is W4");
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void put(String path, JsonObject json, CompileReport.Entry entry) {
        String text = JsonWriter.write(json);
        String existing = files.putIfAbsent(path, text);
        if (existing != null && !existing.equals(text)) {
            throw new IllegalStateException(path + " is emitted twice with different content");
        }
        entry.emitted(path);
    }

    /** {@code ns:path} in {@code folder} to {@code data/ns/folder/path.json}. */
    static String dataPath(String id, String folder) {
        int colon = id.indexOf(':');
        return "data/" + id.substring(0, colon) + "/" + folder + "/" + id.substring(colon + 1) + ".json";
    }

    static JsonObject packMeta(VersionTable.Version version, int definitions) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "custom-dimensions: " + definitions + " dimension definitions compiled for Minecraft "
                + version.id());
        pack.addProperty("min_format", version.packFormat());
        pack.addProperty("max_format", version.packFormat());
        JsonObject meta = new JsonObject();
        meta.add("pack", pack);
        return meta;
    }

    /** {@code #4A2C6B} as vanilla writes colours: lower case. */
    static String colour(String hex) {
        return hex.toLowerCase(Locale.ROOT);
    }

    /** A copy with every object's keys in sorted order, as vanilla writes its files. */
    static JsonObject sorted(JsonObject o) {
        return (JsonObject) sortedElement(o);
    }

    private static JsonElement sortedElement(JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            new TreeMap<>(e.getAsJsonObject().asMap()).forEach((k, v) -> out.add(k, sortedElement(v)));
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            e.getAsJsonArray().forEach(v -> out.add(sortedElement(v)));
            return out;
        }
        return e.deepCopy();
    }

    private static JsonObject attributes(JsonObject type) {
        if (!type.has("attributes")) {
            type.add("attributes", new JsonObject());
        }
        return type.getAsJsonObject("attributes");
    }

    private static void copyOrRemove(JsonObject from, JsonObject to, String key) {
        if (from.has(key)) {
            to.add(key, from.get(key).deepCopy());
        } else {
            to.remove(key);
        }
    }

    private static void setIfPresent(JsonObject o, String key, Number value) {
        if (value != null) {
            o.addProperty(key, value);
        }
    }

    private static void setIfPresent(JsonObject o, String key, Boolean value) {
        if (value != null) {
            o.addProperty(key, value);
        }
    }

    private static List<Path> definitionFiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals("settings.json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Empties {@code packDir}; refuses a non-empty directory that is not an earlier pack. */
    private static void clean(Path packDir) throws IOException {
        if (!Files.exists(packDir)) {
            return;
        }
        boolean empty;
        try (Stream<Path> s = Files.list(packDir)) {
            empty = s.findAny().isEmpty();
        }
        if (!empty && !Files.exists(packDir.resolve("pack.mcmeta"))) {
            throw new IOException(packDir + " is not empty and holds no pack.mcmeta; refusing to replace it");
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(packDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path p : paths) {
            if (!p.equals(packDir)) {
                Files.delete(p);
            }
        }
    }
}
