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

        public boolean knowsStructure(String id) {
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
                                         CompileReport.Entry entry, com.google.gson.JsonArray setPieces) {
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
        Set<String> ours = new java.util.HashSet<>();
        setPieces.forEach(p -> ours.add(p.getAsJsonObject().get("structure").getAsString()));
        unknownIds(result.spec(), catalogue).stream().filter(w -> ours.stream().noneMatch(id -> w.contains("`" + id + "`")))
                .forEach(entry::structureWarning);
        JsonObject json = result.spec().toJson();
        if (!setPieces.isEmpty()) {
            json.add("setPieces", setPieces);
        }
        if (def.structures() != null && def.structures().has("sites")) {
            Map<String, com.customdimensions.core.structure.SiteFilter> sites =
                    com.customdimensions.core.structure.SiteFilter.readAll(slug, def.structures().get("sites"));
            if (!sites.isEmpty()) {
                JsonObject s = new JsonObject();
                sites.forEach((id, f) -> {
                    s.add(id, f.toJson());
                    if (!ours.contains(id) && !catalogue.knowsStructure(id)) {
                        entry.structureWarning("sites: `" + id + "` is not a vanilla or bridged structure");
                    }
                });
                json.add("sites", s);
                entry.structure("sites: " + sites.size() + " structure(s) filtered by slope, height, water, cliff, shelter or biome");
            }
        }
        if (def.structures() != null && def.structures().has("approaches")) {
            JsonObject a = new JsonObject();
            def.structures().getAsJsonObject("approaches").entrySet().forEach(e -> {
                com.google.gson.JsonArray list = new com.google.gson.JsonArray();
                com.customdimensions.core.structure.SetPieceSetting.Approach.readAll(slug + ": structures.approaches." + e.getKey(), e.getValue())
                        .forEach(p -> list.add(p.toJson()));
                a.add(e.getKey(), list);
            });
            json.add("approaches", a);
            entry.structure("approaches: " + a.size() + " structure(s) reached by a path, steps, bridge, ladder, chain or debris line");
        }
        com.customdimensions.core.structure.ZoneStructureRules zones =
                com.customdimensions.core.structure.ZoneStructureRules.read(slug, def.zones());
        if (!zones.rules().isEmpty()) {
            json.add("zones", zones.toJson());
            zones.rules().forEach(r -> entry.structure("zone " + r.zone() + ": wants " + r.wants() + ", shuns " + r.shuns()));
        }
        return Map.of(path(slug), json);
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
