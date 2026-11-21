package com.customdimensions.server.survey;

import com.customdimensions.core.score.RollFacts;
import com.customdimensions.core.structure.StructureSpec;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.render.Sampler;
import com.customdimensions.server.render.StructureStarts;
import com.customdimensions.server.structure.StructureRuntime;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The structures a definition asks for (each member of a listed set, each
 * want, each forced start) that have no confirmed start within the border
 * ({@link StructureStarts}), each with the reason the structure control knows.
 * Each gets a WARN line after a survey, so a curated structure never fails
 * silently.
 */
public final class AskedStarts {

    private AskedStarts() {}

    /** {@code id -> how it is asked for -> why it has no start}, after logging each; empty when every one starts. */
    public static JsonObject check(Sampler sampler, String level, int radius) {
        JsonObject out = new JsonObject();
        StructureSpec spec = StructureRuntime.spec(level).orElse(null);
        StructureRuntime.Controlled c = sampler.control();
        if (spec == null || c.applied() == null) return out;
        Map<String, String> asked = asked(spec, c, sampler.registryAccess());
        if (asked.isEmpty()) return out;
        ChunkGeneratorStructureState state = sampler.structureState();
        List<RollFacts.Start> starts = StructureStarts.find(sampler, state, asked::containsKey, 0, 0, radius, null, id -> 1);
        Set<String> started = new HashSet<>();
        starts.forEach(s -> started.add(s.structure()));
        for (Map.Entry<String, String> e : asked.entrySet()) {
            if (started.contains(e.getKey())) continue;
            String why = reason(e.getKey(), e.getValue(), c, state, radius);
            CustomDimensions.LOG.warn("{}: {} ({}) has no start within {} blocks: {}", level, e.getKey(), e.getValue(), radius, why);
            JsonObject o = new JsonObject();
            o.addProperty("asked", e.getValue());
            o.addProperty("reason", why);
            out.add(e.getKey(), o);
        }
        return out;
    }

    /** Each asked-for structure id and how: {@code force}, {@code want}, or {@code list <set id>}. */
    static Map<String, String> asked(StructureSpec spec, StructureRuntime.Controlled c, net.minecraft.core.RegistryAccess access) {
        Map<String, String> out = new LinkedHashMap<>();
        var sets = access.lookupOrThrow(Registries.STRUCTURE_SET);
        for (String id : spec.list()) {
            sets.getOptional(Identifier.parse(id)).ifPresent(set -> set.structures().forEach(e ->
                    e.structure().unwrapKey().ifPresent(k -> out.putIfAbsent(k.identifier().toString(), "list " + id))));
        }
        for (StructureRuntime.Placed p : c.applied().placed()) out.put(p.structure(), p.kind());
        for (String key : c.applied().unplaced().keySet()) out.put(key.substring(key.indexOf(' ') + 1), key.substring(0, key.indexOf(' ')));
        return out;
    }

    private static String reason(String id, String how, StructureRuntime.Controlled c, ChunkGeneratorStructureState state, int radius) {
        String unplaced = c.applied().unplaced().get(how + " " + id);
        if (unplaced != null) return "not placed: nothing in its ring stands (" + unplaced + ")";
        for (StructureRuntime.Placed p : c.applied().placed()) {
            if (p.structure().equals(id) && p.kind().equals(how)) {
                return "placed at chunk " + p.chunkX() + ", " + p.chunkZ() + ", but it does not start there (outside the border, or refused "
                        + "after placing)";
            }
        }
        if (!how.startsWith("list ")) return "unknown";
        String setId = how.substring(5);
        Holder<StructureSet> set = state.possibleStructureSets().stream()
                .filter(h -> h.unwrapKey().map(k -> k.identifier().toString().equals(setId)).orElse(false)).findFirst().orElse(null);
        boolean member = set != null && set.value().structures().stream()
                .anyMatch(e -> e.structure().unwrapKey().map(k -> k.identifier().toString().equals(id)).orElse(false));
        if (!member) return "its set " + setId + " does not place it here (shunned, replaced, excluded, or pooled into a group that keeps none of it)";
        int sites = StructureStarts.sitesWithin(state, set.value().placement(), radius);
        if (sites == 0) return "its set " + setId + " places no chunk within the border";
        return "its set " + setId + " places " + sites + " chunk(s) within the border; none starts it (its biomes, its site filter, or its own rules)";
    }
}
