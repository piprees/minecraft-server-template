package com.customdimensions.compiler.convert;

import com.customdimensions.core.structure.StructureSpec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rewrites one schema v2 definition through the {@link IdMapping}: every string
 * value and object key that is a mapped id, wherever it sits. Key order and
 * number text are kept, so {@link JsonWriter} output differs from the source
 * only where an id changed.
 *
 * <ul>
 *   <li>A replacement is applied only when the compiler knows its target ({@link Targets}); otherwise the id stays and is reported.</li>
 *   <li>An empty replacement removes the list entry holding the id (the entry object for {@code biomes[].id} and
 *       {@code force[].structure}) or the object key; in any other field it is not applied.</li>
 *   <li>A list entry left equal to an earlier one by a replacement is dropped, the first kept. Biome entries with
 *       different {@code climate} are different points and both stay.</li>
 *   <li>A checkerboard's or void world's {@code biomes} and {@code roll.spawnFilter} are sets of ids, so there an
 *       entry is dropped when its id repeats an earlier one, as the compiler would.</li>
 *   <li>An object key that lands on a key already present is dropped, the first kept.</li>
 *   <li>A short name in {@code wants} or {@code shuns} ({@code structures} and {@code roll}) is read through the
 *       structure alias table; a name that resolves to a mapped id is replaced by the row's target id.</li>
 *   <li>An id in a flagged namespace ({@link NamespaceRegister}) with no row is an error: the table must be complete.</li>
 * </ul>
 */
public final class IdMappingApplier {

    private static final Pattern ID = Pattern.compile("#?[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Set<String> SET_KEYED = Set.of("rarity", "spacing");
    private static final Set<String> SET_LISTS = Set.of("list", "exclude", "include");
    private static final Set<String> ID_SETS = Set.of("checkerboard", "void");
    private static final Set<String> NAMED = Set.of("wants", "shuns");
    private static final Set<String> NAMED_IN = Set.of("structures", "roll");

    /** What the compiler knows: a replacement whose target is not here is not applied. */
    public interface Targets {
        boolean biome(String id);

        boolean structure(String id);

        boolean structureSet(String id);

        boolean block(String id);

        boolean item(String id);

        boolean dimension(String id);
    }

    public record Replaced(String from, String to, String where) {
    }

    public record Removed(String id, String where, String reason) {
    }

    public record NotApplied(String id, String target, String where, String reason) {
    }

    public record Missing(String id, String where) {
    }

    /** One definition rewritten, with what changed and the distinct biome counts before and after. */
    public record Result(String file, JsonObject json, boolean changed, List<Replaced> replaced, List<Removed> removed,
                         List<NotApplied> notApplied, List<Missing> missing, int biomesBefore, int biomesAfter) {
    }

    private final IdMapping mapping;
    private final NamespaceRegister register;
    private final Targets targets;

    public IdMappingApplier(IdMapping mapping, NamespaceRegister register, Targets targets) {
        this.mapping = mapping;
        this.register = register;
        this.targets = targets;
    }

    public Result apply(String file, JsonObject definition) {
        Walk walk = new Walk(definition);
        JsonElement out = walk.element(definition, new ArrayList<>(), false);
        JsonObject json = out == null ? new JsonObject() : out.getAsJsonObject();
        return new Result(file, json, !json.equals(definition), walk.replaced, walk.removed, walk.notApplied, walk.missing,
                distinctBiomes(definition), distinctBiomes(json));
    }

    /** Distinct biome ids in {@code biomes} and every zone's {@code biomes}, a tag counted by its id. */
    public static int distinctBiomes(JsonObject definition) {
        Set<String> ids = new LinkedHashSet<>();
        addBiomeIds(definition.get("biomes"), ids);
        if (definition.has("zones") && definition.get("zones").isJsonArray()) {
            for (JsonElement zone : definition.getAsJsonArray("zones")) {
                if (zone.isJsonObject()) addBiomeIds(zone.getAsJsonObject().get("biomes"), ids);
            }
        }
        return ids.size();
    }

    private static void addBiomeIds(JsonElement biomes, Set<String> ids) {
        if (biomes == null || !biomes.isJsonArray()) return;
        for (JsonElement b : biomes.getAsJsonArray()) {
            String id = entryId(b);
            if (id != null) ids.add(id.startsWith("#") ? id.substring(1) : id);
        }
    }

    private static String entryId(JsonElement e) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString();
        if (e.isJsonObject() && e.getAsJsonObject().has("id") && e.getAsJsonObject().get("id").isJsonPrimitive()) {
            return e.getAsJsonObject().get("id").getAsString();
        }
        return null;
    }

    private static String where(List<String> path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path) {
            if (!segment.startsWith("[") && !out.isEmpty()) out.append('.');
            out.append(segment);
        }
        return out.toString();
    }

    /** The nearest object key on {@code path}, skipping list indices. */
    private static String field(List<String> path, int fromEnd) {
        int seen = 0;
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i).startsWith("[")) continue;
            if (seen++ == fromEnd) return path.get(i);
        }
        return "";
    }

    private final class Walk {
        final List<Replaced> replaced = new ArrayList<>();
        final List<Removed> removed = new ArrayList<>();
        final List<NotApplied> notApplied = new ArrayList<>();
        final List<Missing> missing = new ArrayList<>();
        private final String generatorType;

        Walk(JsonObject definition) {
            JsonElement generator = definition.get("generator");
            generatorType = generator != null && generator.isJsonObject() && generator.getAsJsonObject().has("type")
                    ? generator.getAsJsonObject().get("type").getAsString() : "";
        }

        /** The rewritten element, or null when an empty replacement removes it. */
        JsonElement element(JsonElement e, List<String> path, boolean listEntry) {
            if (e.isJsonObject()) return object(e.getAsJsonObject(), path, listEntry);
            if (e.isJsonArray()) return array(e.getAsJsonArray(), path);
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                String id = e.getAsString();
                boolean removable = listEntry || (path.size() >= 2 && path.get(path.size() - 2).startsWith("[")
                        && Set.of("id", "structure").contains(path.getLast()));
                String to = rewrite(id, path, false, removable);
                return to == null ? null : to.equals(id) ? e : new JsonPrimitive(to);
            }
            return e;
        }

        private JsonObject object(JsonObject in, List<String> path, boolean listEntry) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : in.entrySet()) {
                path.add(entry.getKey());
                String key = rewrite(entry.getKey(), path, true, true);
                JsonElement value = key == null ? null : element(entry.getValue(), path, false);
                path.removeLast();
                if (key == null) continue;
                if (value == null) {
                    if (listEntry) return null;
                    continue;
                }
                if (out.has(key)) {
                    removed.add(new Removed(entry.getKey(), where(path) + (path.isEmpty() ? "" : ".") + entry.getKey() + " (key)",
                            "collapses onto the key `" + key + "` already there"));
                    continue;
                }
                out.add(key, value);
            }
            return out;
        }

        private JsonArray array(JsonArray in, List<String> path) {
            List<JsonElement> kept = new ArrayList<>();
            List<Boolean> touched = new ArrayList<>();
            List<Integer> source = new ArrayList<>();
            for (int i = 0; i < in.size(); i++) {
                path.add("[" + i + "]");
                JsonElement value = element(in.get(i), path, true);
                path.removeLast();
                if (value != null) {
                    kept.add(value);
                    touched.add(!value.equals(in.get(i)));
                    source.add(i);
                }
            }
            boolean byId = "spawnFilter".equals(field(path, 0))
                    || (path.size() == 1 && "biomes".equals(path.getFirst()) && ID_SETS.contains(generatorType));
            JsonArray out = new JsonArray();
            List<Object> keys = new ArrayList<>();
            List<Boolean> keysTouched = new ArrayList<>();
            List<Integer> keysSource = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                JsonElement value = kept.get(i);
                Object key = byId && entryId(value) != null ? entryId(value) : value;
                int earlier = keys.indexOf(key);
                if (earlier >= 0 && (touched.get(i) || keysTouched.get(earlier))) {
                    String id = entryId(value) != null ? entryId(value) : value.toString();
                    removed.add(new Removed(entryId(in.get(source.get(i))) != null ? entryId(in.get(source.get(i))) : id,
                            where(path) + "[" + source.get(i) + "]",
                            "collapses onto `" + id + "` at " + where(path) + "[" + keysSource.get(earlier) + "]"));
                    continue;
                }
                keys.add(key);
                keysTouched.add(touched.get(i));
                keysSource.add(source.get(i));
                out.add(value);
            }
            return out;
        }

        /** {@code id} rewritten, unchanged, or null when its entry is removed. */
        private String rewrite(String written, List<String> path, boolean key, boolean removable) {
            String id = written;
            String at = where(path) + (key ? " (key)" : "");
            if (named(path, key) && !id.contains(":") && !id.startsWith("#")) {
                String resolved = StructureSpec.resolveName(id);
                if (resolved == null) return written;
                id = resolved;
                at += " (alias `" + written + "`)";
            }
            if (!ID.matcher(id).matches()) return written;
            IdMapping.Row row = mapping.row(id).orElse(null);
            if (row == null) {
                String namespace = id.substring(id.startsWith("#") ? 1 : 0, id.indexOf(':'));
                if (register.isFlagged(namespace)) missing.add(new Missing(id, at));
                return written;
            }
            if (row.removes()) {
                if (!removable) {
                    notApplied.add(new NotApplied(id, "", at, "an empty replacement removes a list entry or a key; this is a field"));
                    return written;
                }
                removed.add(new Removed(id, at, "the table removes it (" + row.decision() + ")"));
                return null;
            }
            String refusal = refusal(row, path, key);
            if (refusal != null) {
                notApplied.add(new NotApplied(id, row.replacement(), at, refusal));
                return written;
            }
            replaced.add(new Replaced(id, row.replacement(), at));
            return row.replacement();
        }

        /** Whether {@code path} is a {@code wants} or {@code shuns} name: a key of the object, or an entry of the list form. */
        private boolean named(List<String> path, boolean key) {
            if (path.isEmpty() || !NAMED_IN.contains(path.getFirst())) return false;
            if (key) return path.size() == 3 && NAMED.contains(path.get(1));
            return path.size() == 3 && NAMED.contains(path.get(1)) && path.get(2).startsWith("[");
        }

        /** Why the compiler would not take the row's target here, or null when it would. */
        private String refusal(IdMapping.Row row, List<String> path, boolean key) {
            String target = row.replacement();
            String field = key ? field(path, 1) : field(path, 0);
            boolean inStructures = !path.isEmpty() && "structures".equals(path.getFirst());
            if (inStructures && ((key && SET_KEYED.contains(field)) || (!key && SET_LISTS.contains(field)))) {
                return targets.structureSet(target) ? null
                        : "`structures." + field + "` takes structure sets; `" + target + "` is not a vanilla or bridged set";
            }
            if ("frameBlock".equals(field)) {
                return targets.block(target) ? null : "`" + target + "` is not a vanilla block";
            }
            if ("igniterItem".equals(field)) {
                return targets.item(target) ? null : "`" + target + "` is not a vanilla item";
            }
            return switch (row.kind()) {
                case "biome" -> targets.biome(target) ? null : "`" + target + "` is neither a vanilla nor a library biome";
                case "structure" -> targets.structure(target) ? null : "`" + target + "` is not a vanilla or bridged structure";
                case "portal" -> targets.block(target) || targets.item(target) ? null : "`" + target + "` is not a vanilla block or item";
                default -> targets.dimension(target) ? null : "`" + target + "` is no definition's dimension";
            };
        }
    }
}
