package com.customdimensions.compiler.datapack;

import com.customdimensions.compiler.convert.JsonWriter;
import com.customdimensions.compiler.terrain.TerrainLibrary;
import com.customdimensions.compiler.terrain.TerrainSpec;
import com.customdimensions.core.definition.Atmosphere;
import com.customdimensions.core.definition.BiomeEntry;
import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.Win;
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
 * per definition, noise settings and a material rule where liquids, the default
 * block, palettes or ore veins change, runtime worldgen files and added ores,
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

    /** Clone targets whose dimension the pack replaces: {@code of} to the slug that stands in for it. */
    static final Map<String, String> CLONE_ALIASES = Map.of("paradise_lost:paradise_lost", "the_aviary");

    /** Vanilla's End terrain references, and the copies an End without its central island uses instead. */
    static final String END_ISLANDS = "minecraft:end/islands";
    static final String END_SLOPED_CHEESE = "minecraft:end/sloped_cheese";
    static final String NO_CENTRAL_ISLAND = "end_without_central_island";

    /** Vanilla's checkerboard {@code scale} when a definition sets no {@code cellScale}. */
    static final int DEFAULT_CHECKERBOARD_SCALE = 2;

    /**
     * The compiled pack: pack-relative path to file text, the report, and each
     * compiled definition as the compiler saw it (a clone with its source's generator).
     */
    public record Output(SortedMap<String, String> files, CompileReport report, String reportText,
                         Map<String, Definition> compiled) {
    }

    private final VanillaData vanilla;
    private final VersionTable.Version version;
    private final String namespace;
    private final SortedMap<String, String> files = new TreeMap<>();
    private final CompileReport report;
    private final Map<String, Definition> definitions = new TreeMap<>();
    private final Map<String, Definition> compiled = new TreeMap<>();

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
        Map<String, Definition> definitions = new TreeMap<>();
        for (Path file : definitionFiles(definitionsDir)) {
            String name = file.getFileName().toString();
            definitions.put(name.substring(0, name.length() - ".json".length()), DefinitionReader.read(file));
        }
        Output output = compileAll(namespace, definitions, vanilla, version);
        output.files().put("pack.mcmeta", JsonWriter.write(packMeta(version, output.report().entries().size())));
        return output;
    }

    /** Compiles a set of definitions by slug, clones resolved against each other; no {@code pack.mcmeta}. */
    static Output compileAll(String namespace, Map<String, Definition> definitions, VanillaData vanilla,
                             VersionTable.Version version) {
        DatapackCompiler compiler = new DatapackCompiler(vanilla, version, namespace);
        compiler.definitions.putAll(definitions);
        compiler.definitions.keySet().forEach(compiler::compile);
        return new Output(compiler.files, compiler.report, compiler.report.markdown(compiler.files), compiler.compiled);
    }

    /** Compiles one definition on its own, for tests of a single mapping. */
    static Output compileOne(String namespace, String slug, Definition definition, VanillaData vanilla,
                             VersionTable.Version version) {
        return compileAll(namespace, Map.of(slug, definition), vanilla, version);
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

    private void compile(String slug) {
        CompileReport.Entry entry = report.add(slug);
        Definition def = definitions.get(slug);
        wins(slug, def, entry);
        String cloneNote = "";
        if (Generator.CLONE.equals(def.generator().type())) {
            List<String> chain = cloneChain(slug);
            String last = chain.getLast();
            if (!definitions.containsKey(last)) {
                entry.kind(CompileReport.Kind.CLONE, "copies `" + def.generator().of() + "`, which no definition provides"
                        + (chain.size() > 2 ? " (via " + String.join(" -> ", chain.subList(1, chain.size() - 1)) + ")" : "")
                        + "; not compiled");
                return;
            }
            String of = def.generator().of();
            def = cloned(def, definitions.get(last), entry);
            entry.cloneOf(namespace + ":" + last);
            cloneNote = ", clone of `" + namespace + ":" + last + "`"
                    + (CLONE_ALIASES.containsKey(of) ? ", which stands in for `" + of + "`" : "")
                    + (chain.size() > 2 ? " via " + String.join(" -> ", chain.subList(1, chain.size() - 1)) : "");
        }
        compiled.put(slug, def);
        Generator generator = def.generator();
        String typeId = namespace + ":" + slug;
        String stemPath;
        if (BASE_WORLDS.containsKey(slug)) {
            String stemId = BASE_WORLDS.get(slug).isEmpty() ? namespace + ":" + slug : BASE_WORLDS.get(slug);
            stemPath = dataPath(stemId, "dimension");
            entry.kind(CompileReport.Kind.BASE_WORLD, "`" + stemId + "`" + cloneNote);
        } else {
            stemPath = "data/" + RUNTIME_NAMESPACE + "/" + LAZY_FOLDER + "/" + slug + ".json";
            entry.kind(CompileReport.Kind.LAZY, "`" + typeId + "`" + cloneNote);
        }

        JsonObject type = dimensionType(def, entry);
        boolean noiseBased = !Generator.FLAT.equals(generator.type()) && !Generator.VOID.equals(generator.type());
        if (noiseBased) {
            String seaNote = LiquidsCompiler.validate(slug, def.liquids(), type);
            if (seaNote != null) {
                entry.note(seaNote);
            }
        }
        String bedrock = BedrockHeights.check(type);
        if (bedrock != null) {
            entry.bedrock(bedrock);
        }
        put(dataPath(typeId, "dimension_type"), sorted(type), entry);

        JsonObject stem = new JsonObject();
        stem.addProperty("type", typeId);
        stem.add("generator", generator(slug, def, entry));
        put(stemPath, stem, entry);
        if (noiseBased) {
            String levelId = BASE_WORLDS.containsKey(slug) && !BASE_WORLDS.get(slug).isEmpty() ? BASE_WORLDS.get(slug) : typeId;
            RuntimeWorldgen.files(namespace, slug, levelId, def, vanilla).forEach((path, json) -> put(path, sorted(json), entry));
        }

        reportRuntimeFields(def, entry);
    }

    // ---- wins ------------------------------------------------------------------------------

    /**
     * Each win as an advancement under one {@code wins/root} tab (decisions.md
     * RB-30). Every criterion is {@code minecraft:impossible}: the mod grants
     * them, one per kill for a counted {@code kill} win.
     */
    private void wins(String slug, Definition def, CompileReport.Entry entry) {
        if (def.wins().isEmpty()) {
            return;
        }
        String rootId = namespace + ":" + Win.ROOT;
        put(dataPath(rootId, "advancement"), winRoot(), entry);
        for (Win win : def.wins()) {
            JsonObject json = new JsonObject();
            json.addProperty("parent", rootId);
            json.add("criteria", impossible(win.criteria()));
            JsonObject display = new JsonObject();
            JsonObject icon = new JsonObject();
            icon.addProperty("id", win.icon() != null ? win.icon() : Win.DEFAULT_ICON);
            display.add("icon", icon);
            display.addProperty("title", win.title());
            display.addProperty("description", win.description() != null ? win.description() : win.title());
            display.addProperty("frame", win.frame() != null ? win.frame() : "goal");
            display.addProperty("show_toast", true);
            display.addProperty("announce_to_chat", false);
            json.add("display", display);
            json.add("requirements", requirements(win.criteria()));
            put(dataPath(namespace + ":" + win.advancementPath(slug), "advancement"), json, entry);
        }
        entry.note(def.wins().size() + " win(s) as advancements under `" + rootId + "`");
    }

    private static JsonObject winRoot() {
        JsonObject root = new JsonObject();
        root.add("criteria", impossible(List.of("root")));
        JsonObject display = new JsonObject();
        JsonObject icon = new JsonObject();
        icon.addProperty("id", Win.DEFAULT_ICON);
        display.add("icon", icon);
        display.addProperty("title", "Wins");
        display.addProperty("description", "What you have won across the dimensions");
        display.addProperty("background", "minecraft:gui/advancements/backgrounds/end");
        display.addProperty("show_toast", false);
        display.addProperty("announce_to_chat", false);
        root.add("display", display);
        root.add("requirements", requirements(List.of("root")));
        return root;
    }

    private static JsonObject impossible(List<String> names) {
        JsonObject criteria = new JsonObject();
        for (String name : names) {
            JsonObject c = new JsonObject();
            c.addProperty("trigger", "minecraft:impossible");
            criteria.add(name, c);
        }
        return criteria;
    }

    /** Every criterion required: one group per criterion. */
    private static JsonArray requirements(List<String> names) {
        JsonArray out = new JsonArray();
        for (String name : names) {
            JsonArray group = new JsonArray();
            group.add(name);
            out.add(group);
        }
        return out;
    }

    // ---- clones -----------------------------------------------------------------------------

    /**
     * The slugs a clone resolves through, starting with itself and ending with
     * the first non-clone, or with the unresolved {@code of} when no definition
     * provides it. A chain that returns to a slug is a compile error naming it.
     */
    List<String> cloneChain(String slug) {
        List<String> chain = new ArrayList<>(List.of(slug));
        Definition def = definitions.get(slug);
        while (def != null && Generator.CLONE.equals(def.generator().type())) {
            String of = def.generator().of();
            String next = cloneTarget(of);
            if (next == null) {
                chain.add(of);
                return chain;
            }
            if (chain.contains(next)) {
                chain.add(next);
                throw new IllegalStateException("clone cycle: " + String.join(" -> ", chain));
            }
            chain.add(next);
            def = definitions.get(next);
            if (def == null) {
                chain.set(chain.size() - 1, of);
                return chain;
            }
        }
        return chain;
    }

    /** The slug a clone's {@code of} names: an alias, a base world's level stem, or a pack dimension; else null. */
    String cloneTarget(String of) {
        if (CLONE_ALIASES.containsKey(of)) {
            return CLONE_ALIASES.get(of);
        }
        for (Map.Entry<String, String> base : BASE_WORLDS.entrySet()) {
            if (of.equals(base.getValue())) {
                return base.getKey();
            }
        }
        String prefix = namespace + ":";
        return of.startsWith(prefix) ? of.substring(prefix.length()) : null;
    }

    /**
     * The clone with its source's generator, biome list and terrain; its own
     * seed, dimension type, atmosphere and rules; its own liquids and default
     * block over the source's, field by field.
     */
    private static Definition cloned(Definition clone, Definition source, CompileReport.Entry entry) {
        if (!clone.biomes().isEmpty()) {
            entry.unsupported("biomes", "a clone takes its source's biome source; its own " + clone.biomes().size()
                    + " biome(s) are not used");
        }
        Liquids own = clone.liquids();
        Liquids theirs = source.liquids();
        Liquids liquids = own == null ? theirs : theirs == null ? own : new Liquids(
                own.seaLevel() != null ? own.seaLevel() : theirs.seaLevel(),
                own.lavaLevel() != null ? own.lavaLevel() : theirs.lavaLevel(),
                own.defaultFluid() != null ? own.defaultFluid() : theirs.defaultFluid(),
                own.aquifers() != null ? own.aquifers() : theirs.aquifers());
        JsonObject materials = overlay(source.materials(), clone.materials());
        JsonObject ores = overlay(source.ores(), clone.ores());
        return new Definition(clone.schemaVersion(), clone.description(), clone.seed(), clone.spawn(), source.generator(),
                source.terrain(), source.biomes(), liquids, materials, ores, clone.zones(), clone.dimensionType(),
                clone.atmosphere(), clone.rules(), clone.borders(), clone.difficulty(), clone.structures(), clone.portals(),
                clone.exitPortal(), clone.exits(), clone.exitShrines(), clone.deaths(), clone.wins(), clone.roll());
    }

    /** {@code theirs} with each of {@code own}'s fields over it; null when both are. */
    private static JsonObject overlay(JsonObject theirs, JsonObject own) {
        JsonObject out = theirs == null ? null : theirs.deepCopy();
        if (own != null) {
            if (out == null) {
                out = new JsonObject();
            }
            for (Map.Entry<String, JsonElement> e : own.entrySet()) {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        return out;
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
     * after them), or {@code <ns>:<slug>} written from them when liquids, the
     * default block, the material palette or the ore veins change; a changed
     * material rule is written as {@code <ns>:<slug>} too.
     */
    private String noiseSettings(String slug, Definition def, String preset, CompileReport.Entry entry) {
        Liquids liquids = def.liquids();
        String defaultBlock = defaultBlock(def);
        boolean changesLiquids = LiquidsCompiler.changesSettings(liquids);
        boolean removesIsland = "end".equals(preset) && Boolean.FALSE.equals(def.generator().endIsland());
        TerrainSpec terrain = TerrainSpec.parse(slug, def.terrain());
        JsonObject settings = vanilla.noiseSettings(preset);
        if (terrain != null) {
            settings = terrain(slug, preset, terrain, settings, liquids, entry);
        }
        int seaLevel = liquids != null && liquids.seaLevel() != null ? liquids.seaLevel() : settings.get("sea_level").getAsInt();
        JsonObject materialRule = MaterialsCompiler.compose(vanilla, settings.get("material_rule").getAsString(),
                MaterialsCompiler.read(slug, def.materials()), OresCompiler.veins(OresCompiler.read(slug, def.ores()), vanilla),
                seaLevel);
        if (!changesLiquids && defaultBlock == null && !removesIsland && materialRule == null && terrain == null) {
            return "minecraft:" + preset;
        }
        if (removesIsland) {
            settings = withoutCentralIsland(settings, entry);
        }
        if (liquids != null) {
            LiquidsCompiler.apply(slug, preset, liquids, settings);
        }
        String id = namespace + ":" + slug;
        if (materialRule != null) {
            put(dataPath(id, "worldgen/material_rule"), sorted(materialRule), entry);
            settings.addProperty("material_rule", id);
        }
        if (version.noiseSettingsDefaultBlock()) {
            if (defaultBlock != null) {
                settings.addProperty("default_block", defaultBlock);
            }
        } else {
            settings.remove("default_block");
            if (defaultBlock != null) {
                settings.add("material_rule", withFallback(settings.get("material_rule"), defaultBlock));
            }
        }
        put(dataPath(id, "worldgen/noise_settings"), sorted(settings), entry);
        return id;
    }

    /** The settings with a library terrain's router; its density functions and noises go into the pack. */
    private JsonObject terrain(String slug, String preset, TerrainSpec terrain, JsonObject settings, Liquids liquids,
                               CompileReport.Entry entry) {
        TerrainLibrary.Compiled compiled = TerrainLibrary.compile(slug, namespace, slug, terrain, preset, settings,
                liquids != null ? liquids.seaLevel() : null, vanilla);
        compiled.files().forEach((path, json) -> put(path, sorted(json), entry));
        entry.note("Terrain `" + terrain.family().id() + "` (" + terrain.setting() + ") under `" + namespace + ":terrain/"
                + slug + "/`" + (terrain.family().airborne() ? ", over a void with no aquifers" : ""));
        return compiled.settings();
    }

    /** The material rule, then {@code block} as the last entry of a sequence: what no earlier rule claims. */
    static JsonObject withFallback(JsonElement rule, String block) {
        JsonObject fallback = new JsonObject();
        fallback.addProperty("type", "minecraft:block");
        fallback.addProperty("result_state", block);
        JsonArray sequence = new JsonArray();
        sequence.add(rule.deepCopy());
        sequence.add(fallback);
        JsonObject out = new JsonObject();
        out.addProperty("type", "minecraft:sequence");
        out.add("sequence", sequence);
        return out;
    }

    /**
     * The End's noise settings with vanilla's island function replaced by a copy
     * that keeps only {@code end_outer_islands}: vanilla's is the maximum of a
     * {@code distance_to_point} dome around 0,0 and the outer islands.
     */
    private JsonObject withoutCentralIsland(JsonObject settings, CompileReport.Entry entry) {
        JsonObject islands = vanilla.densityFunction("end/islands");
        JsonObject max = islands.getAsJsonObject("input");
        if (!"minecraft:max".equals(max.get("type").getAsString())) {
            throw new IllegalStateException("vanilla's end/islands is no longer a max; the central island cannot be removed");
        }
        islands.add("input", max.get("right"));
        String islandsId = namespace + ":" + NO_CENTRAL_ISLAND + "/islands";
        String cheeseId = namespace + ":" + NO_CENTRAL_ISLAND + "/sloped_cheese";
        JsonObject cheese = vanilla.densityFunction("end/sloped_cheese");
        JsonElement replaced = replaceIds(cheese, Map.of(END_ISLANDS, islandsId));
        put(dataPath(islandsId, "worldgen/density_function"), sorted(islands), entry);
        put(dataPath(cheeseId, "worldgen/density_function"), sorted(replaced.getAsJsonObject()), entry);
        return replaceIds(settings, Map.of(END_ISLANDS, islandsId, END_SLOPED_CHEESE, cheeseId)).getAsJsonObject();
    }

    /** A copy of {@code e} with every string equal to a key replaced by its value. */
    static JsonElement replaceIds(JsonElement e, Map<String, String> ids) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && ids.containsKey(e.getAsString())) {
            return new com.google.gson.JsonPrimitive(ids.get(e.getAsString()));
        }
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            e.getAsJsonObject().entrySet().forEach(kv -> out.add(kv.getKey(), replaceIds(kv.getValue(), ids)));
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            e.getAsJsonArray().forEach(v -> out.add(replaceIds(v, ids)));
            return out;
        }
        return e.deepCopy();
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
        if (def.materials() != null) {
            for (String key : def.materials().keySet()) {
                entry.unsupported("materials." + key, "a flat generator is made of its layers");
            }
        }
        if (def.ores() != null) {
            entry.unsupported("ores", "a flat generator places no features");
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
        String type = def.generator().type();
        if (terrain != null && terrain.has("base") && !terrain.get("base").isJsonNull()
                && (Generator.FLAT.equals(type) || Generator.VOID.equals(type))) {
            entry.unsupported("terrain", "a " + type + " generator has no noise terrain");
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
