package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.firewall.Bridge;
import com.customdimensions.core.firewall.BridgeReader;
import com.customdimensions.core.structure.StructureSpec;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A definition's {@code structures} block to
 * {@code data/customdimensions/structure_control/<slug>.json}, the resolved
 * {@link StructureSpec} the runtime applies to the level's structure state.
 * Every dimension gets one, since noise placement is the default. Ids neither
 * vanilla nor a bridge provides are compile-report warnings.
 */
public final class StructuresCompiler {

    public static final String FOLDER = "structure_control";

    private StructuresCompiler() {
    }

    /** The structure, structure set and structure tag ids the compiler knows: vanilla's and the bridges'. */
    public record Catalogue(Set<String> structures, Set<String> sets, Set<String> tags) {

        public static Catalogue of(VanillaData vanilla, Path bridgesDir) {
            Set<String> structures = new LinkedHashSet<>();
            Set<String> sets = new LinkedHashSet<>();
            Set<String> tags = new LinkedHashSet<>();
            VanillaSource source = vanilla.source();
            source.list("data/minecraft/worldgen/structure").forEach(f -> structures.add("minecraft:" + strip(f)));
            source.list("data/minecraft/worldgen/structure_set").forEach(f -> sets.add("minecraft:" + strip(f)));
            source.list("data/minecraft/tags/worldgen/structure").forEach(f -> tags.add("#minecraft:" + strip(f)));
            if (bridgesDir != null && Files.isDirectory(bridgesDir)) {
                for (Bridge bridge : BridgeReader.readAll(bridgesDir).values()) {
                    sets.addAll(bridge.structureSets());
                    bridge.structures().forEach(s -> structures.add(s.id()));
                }
            }
            return new Catalogue(Set.copyOf(structures), Set.copyOf(sets), Set.copyOf(tags));
        }

        boolean knowsStructure(String id) {
            return id.startsWith("#") ? tags.contains(id) || !id.startsWith("#minecraft:") : structures.contains(id);
        }

        private static String strip(String file) {
            return file.endsWith(".json") ? file.substring(0, file.length() - 5) : file;
        }
    }

    /** The bridges folder next to a definitions folder, or the one {@code customdimensions.bridges} names. */
    public static Path bridgesBeside(Path definitionsDir) {
        String property = System.getProperty("customdimensions.bridges");
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        Path parent = definitionsDir.toAbsolutePath().getParent();
        return parent == null ? null : parent.resolve("bridges");
    }

    /** The control file for one dimension, with what applies and every warning in the report. */
    static Map<String, JsonObject> files(String slug, String levelId, Definition def, Catalogue catalogue,
                                         CompileReport.Entry entry) {
        int border = def.borders() != null && def.borders().player() != null ? def.borders().player() : 4096;
        int spawnX = def.spawn() != null ? def.spawn().x() : 0;
        int spawnZ = def.spawn() != null ? def.spawn().z() : 0;
        double mob = def.difficulty() != null && def.difficulty().has("mobMultiplier")
                ? def.difficulty().get("mobMultiplier").getAsDouble() : 1.0;
        StructureSpec.Context ctx = new StructureSpec.Context(slug, levelId,
                StructureSpec.worldType(def.generator().type(), def.generator().preset()), mob, border, spawnX, spawnZ);
        StructureSpec.Result result = StructureSpec.resolve(ctx, def.structures());
        result.applied().forEach(entry::structure);
        result.warnings().forEach(entry::structureWarning);
        unknownIds(result.spec(), catalogue).forEach(entry::structureWarning);
        return Map.of(path(slug), result.spec().toJson());
    }

    static List<String> unknownIds(StructureSpec spec, Catalogue catalogue) {
        List<String> out = new ArrayList<>();
        for (StructureSpec.Force f : spec.force()) {
            if (!catalogue.knowsStructure(f.structure())) {
                out.add("force: `" + f.structure() + "` is not a vanilla or bridged structure");
            }
        }
        for (StructureSpec.Want w : spec.wants()) {
            if (!catalogue.knowsStructure(w.structure())) {
                out.add("wants: `" + w.structure() + "` is not a vanilla or bridged structure");
            }
        }
        for (String s : spec.shuns()) {
            if (!catalogue.knowsStructure(s)) {
                out.add("shuns: `" + s + "` is not a vanilla or bridged structure");
            }
        }
        spec.replace().forEach((from, to) -> {
            for (String id : List.of(from, to)) {
                if (!catalogue.knowsStructure(id)) {
                    out.add("replace: `" + id + "` is not a vanilla or bridged structure");
                }
            }
        });
        for (String t : spec.terrainAdaptation().keySet()) {
            if (t.contains(":") && !catalogue.knowsStructure(t)) {
                out.add("terrainAdaptation: `" + t + "` is not a vanilla or bridged structure");
            }
        }
        Set<String> sets = new LinkedHashSet<>(spec.list());
        sets.addAll(spec.exclude());
        sets.addAll(spec.include());
        sets.addAll(spec.rarity().keySet());
        sets.addAll(spec.spacing().keySet());
        for (String set : sets) {
            if (!catalogue.sets().contains(set)) {
                out.add("`" + set + "` is not a vanilla or bridged structure set");
            }
        }
        return out;
    }

    /** {@code data/customdimensions/structure_control/<slug>.json}. */
    public static String path(String slug) {
        return "data/" + DatapackCompiler.RUNTIME_NAMESPACE + "/" + FOLDER + "/" + slug + ".json";
    }
}
