package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.Definition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The worldgen a datapack cannot express for one dimension, written to
 * {@code data/customdimensions/worldgen_runtime/<slug>.json} for the runtime
 * to apply to that level's generator before it generates a chunk: the lava
 * level in place of the generator's -54, ores added to every biome's
 * underground-ores step, and placed features removed from every biome.
 * Vanilla ignores the folder, so the pack still loads without the mod.
 */
public final class RuntimeWorldgen {

    public static final String FOLDER = "worldgen_runtime";
    public static final String LEVEL = "level";
    public static final String LAVA_LEVEL = "lava_level";
    public static final String ADD_UNDERGROUND_ORES = "add_underground_ores";
    public static final String REMOVE_FEATURES = "remove_features";

    private RuntimeWorldgen() {
    }

    /**
     * The files one noise-generated dimension needs beyond its noise settings:
     * the added ores' features and the runtime file, by pack path; empty when
     * the definition asks for nothing the runtime does.
     *
     * @param levelId the level the runtime file applies to: a base world's stem id or {@code <ns>:<slug>}
     */
    static Map<String, JsonObject> files(String namespace, String slug, String levelId, Definition def,
                                         VanillaData vanilla) {
        OresCompiler.Spec ores = OresCompiler.read(slug, def.ores());
        OresCompiler.validate(slug, ores, vanilla);
        Map<String, JsonObject> out = new LinkedHashMap<>();
        Set<String> paletteBlocks = new LinkedHashSet<>(MaterialsCompiler.read(slug, def.materials()).blocks());
        String defaultBlock = def.materials() != null && def.materials().has(MaterialsCompiler.DEFAULT_BLOCK)
                && !def.materials().get(MaterialsCompiler.DEFAULT_BLOCK).isJsonNull()
                ? def.materials().get(MaterialsCompiler.DEFAULT_BLOCK).getAsString() : null;
        if (defaultBlock != null) {
            paletteBlocks.add(defaultBlock);
        }
        out.putAll(OresCompiler.features(namespace, slug, ores, paletteBlocks));

        Integer lava = LiquidsCompiler.lavaLevel(def.liquids());
        List<String> add = new ArrayList<>();
        ores.add().forEach(o -> add.add(OresCompiler.featureId(namespace, slug, o)));
        Set<String> remove = new LinkedHashSet<>(LiquidsCompiler.removedFeatures(def.liquids()));
        remove.addAll(ores.remove());
        if (lava == null && add.isEmpty() && remove.isEmpty()) {
            return out;
        }
        JsonObject file = new JsonObject();
        file.addProperty(LEVEL, levelId);
        if (lava != null) {
            file.addProperty(LAVA_LEVEL, lava);
        }
        if (!add.isEmpty()) {
            file.add(ADD_UNDERGROUND_ORES, array(add));
        }
        if (!remove.isEmpty()) {
            file.add(REMOVE_FEATURES, array(new ArrayList<>(remove)));
        }
        out.put(path(slug), file);
        return out;
    }

    /** {@code data/customdimensions/worldgen_runtime/<slug>.json}. */
    public static String path(String slug) {
        return "data/" + DatapackCompiler.RUNTIME_NAMESPACE + "/" + FOLDER + "/" + slug + ".json";
    }

    private static JsonArray array(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }
}
