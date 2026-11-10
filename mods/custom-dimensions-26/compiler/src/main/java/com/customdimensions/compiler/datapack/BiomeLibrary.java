package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Our own biomes, built from vanilla blocks and feature types: one file per
 * biome under {@code customdimensions/biomes/<id>.json} in the compiler's
 * resources, listed in {@code index.txt} beside them. Every compile writes each
 * one into the pack as {@code <namespace>:<id>} and its own features as
 * {@code <namespace>:biome/<id>/<name>}; definitions then list it as they list
 * a vanilla biome.
 *
 * <p>A file holds {@code realm}, {@code inspiredBy} and {@code differs}, the
 * {@code biome} as a 26.3 biome file, and optionally {@code features} (name to
 * {@code {feature, placement}}), {@code palette} (a surface palette) and
 * {@code climate} (the box a definition's layout uses when it gives none) and
 * {@code role} (the ground it belongs to, {@link BiomeRoles}).
 * In {@code biome.features} and inside the features, {@code "~<name>"} names
 * one of the biome's own features. Schema: {@code schema/biome.schema.json}.
 */
public final class BiomeLibrary {

    public static final String RESOURCES = "customdimensions/biomes/";
    public static final String INDEX = RESOURCES + "index.txt";
    public static final List<String> REALMS = List.of("overworld", "caves", "nether", "end", "sky");
    /** The folder a library biome's features live under: {@code <namespace>:biome/<id>/<name>}. */
    public static final String FEATURE_FOLDER = "biome";
    static final List<String> FIELDS = List.of("$schema", "realm", "inspiredBy", "differs", "biome", "features", "palette", "climate", "role");
    /** The keys of a 26.3 biome file; the last is optional. */
    static final List<String> BIOME_FIELDS = List.of("attributes", "carvers", "downfall", "effects", "features",
            "has_precipitation", "temperature", "temperature_modifier");
    /** Generation steps a biome's {@code features} may fill: vanilla's {@code GenerationStep.Decoration}. */
    static final int STEPS = 11;
    static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    static final Pattern REF = Pattern.compile("#?[a-z0-9_.-]+:[a-z0-9_./-]+");
    static final String SPAWNS = "minecraft:gameplay/natural_mob_spawns";
    static final String PARTICLES = "minecraft:visual/ambient_particles";

    /** One library biome; {@code features} maps each name to its {@code {feature, placement}} object. */
    public record Entry(String id, String realm, String inspiredBy, String differs, JsonObject biome,
                        Map<String, JsonObject> features, MaterialsCompiler.Palette palette, JsonObject climate, String role) {
    }

    /** The biome ids a compile accepts, vanilla's and the library's, with each library biome's default climate. */
    public record Known(Set<String> ids, Map<String, JsonObject> climates) {
        public boolean contains(String id) {
            return ids.contains(id);
        }

        /** The library's climate box for {@code id}, or null. */
        public JsonObject climate(String id) {
            JsonObject c = climates.get(id);
            return c == null ? null : c.deepCopy();
        }
    }

    private static BiomeLibrary shipped;
    private final SortedMap<String, Entry> entries;

    private BiomeLibrary(SortedMap<String, Entry> entries) {
        this.entries = entries;
    }

    /** The biomes the compiler's resources carry. */
    public static synchronized BiomeLibrary shipped() {
        if (shipped == null) {
            Map<String, String> texts = new TreeMap<>();
            for (String line : resource(INDEX).split("\n")) {
                String name = line.trim();
                if (name.isEmpty()) continue;
                texts.put(name.substring(0, name.length() - ".json".length()), resource(RESOURCES + name));
            }
            shipped = of(texts);
        }
        return shipped;
    }

    public static BiomeLibrary empty() {
        return new BiomeLibrary(new TreeMap<>());
    }

    /** The library from file texts by biome id; a malformed file is an error naming its biome. */
    public static BiomeLibrary of(Map<String, String> texts) {
        SortedMap<String, Entry> out = new TreeMap<>();
        texts.forEach((id, text) -> {
            if (!NAME.matcher(id).matches()) throw error(id, "the file name is not a biome id ([a-z0-9_]+)");
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(text);
            } catch (RuntimeException e) {
                throw error(id, "is not JSON: " + e.getMessage());
            }
            if (!parsed.isJsonObject()) throw error(id, "must be a JSON object");
            out.put(id, read(id, parsed.getAsJsonObject()));
        });
        return new BiomeLibrary(out);
    }

    public SortedMap<String, Entry> entries() {
        return entries;
    }

    /** {@code <namespace>:<id>} for every library biome. */
    public Set<String> ids(String namespace) {
        Set<String> out = new TreeSet<>();
        entries.keySet().forEach(id -> out.add(namespace + ":" + id));
        return out;
    }

    /** The library biome a full id names in {@code namespace}. */
    public Optional<Entry> get(String namespace, String fullId) {
        String prefix = namespace + ":";
        return fullId.startsWith(prefix) ? Optional.ofNullable(entries.get(fullId.substring(prefix.length()))) : Optional.empty();
    }

    /** Vanilla's biome ids and the library's in {@code namespace}. */
    public Known known(VanillaData vanilla, String namespace) {
        Set<String> ids = new TreeSet<>(vanilla.biomeIds());
        Map<String, JsonObject> climates = new TreeMap<>();
        for (Entry e : entries.values()) {
            ids.add(namespace + ":" + e.id());
            if (e.climate() != null) climates.put(namespace + ":" + e.id(), e.climate());
        }
        return new Known(Set.copyOf(ids), Map.copyOf(climates));
    }

    public static String featureId(String namespace, String biome, String name) {
        return namespace + ":" + FEATURE_FOLDER + "/" + biome + "/" + name;
    }

    // ---- reading ----------------------------------------------------------------------------

    private static Entry read(String id, JsonObject o) {
        for (String key : o.keySet()) {
            if (!FIELDS.contains(key)) throw error(id, key + " is not a library field; known: " + String.join(", ", FIELDS.subList(1, FIELDS.size())));
        }
        String realm = text(id, o, "realm");
        if (!REALMS.contains(realm)) throw error(id, "realm `" + realm + "` is not one of " + REALMS);
        String inspiredBy = text(id, o, "inspiredBy");
        String differs = text(id, o, "differs");
        if (!o.has("biome") || !o.get("biome").isJsonObject()) throw error(id, "biome is required: a 26.3 biome object");
        JsonObject biome = o.getAsJsonObject("biome");
        for (String key : biome.keySet()) {
            if (!BIOME_FIELDS.contains(key)) throw error(id, "biome." + key + " is not a 26.3 biome field; known: " + String.join(", ", BIOME_FIELDS));
        }
        for (String key : BIOME_FIELDS.subList(0, BIOME_FIELDS.size() - 1)) {
            if (!biome.has(key)) throw error(id, "biome." + key + " is required");
        }
        Map<String, JsonObject> features = new LinkedHashMap<>();
        JsonElement f = o.get("features");
        if (f != null && !f.isJsonNull()) {
            if (!f.isJsonObject()) throw error(id, "features must be an object of name to {feature, placement}");
            for (Map.Entry<String, JsonElement> e : f.getAsJsonObject().entrySet()) {
                String where = "features." + e.getKey();
                if (!NAME.matcher(e.getKey()).matches()) throw error(id, where + ": a feature name is [a-z0-9_]+");
                if (!e.getValue().isJsonObject()) throw error(id, where + " must be {feature, placement}");
                JsonObject feature = e.getValue().getAsJsonObject();
                for (String key : feature.keySet()) {
                    if (!key.equals("feature") && !key.equals("placement")) throw error(id, where + "." + key + " is not feature or placement");
                }
                JsonElement body = feature.get("feature");
                boolean configured = body != null && body.isJsonObject() && body.getAsJsonObject().has("type");
                boolean reference = body != null && body.isJsonPrimitive() && body.getAsJsonPrimitive().isString();
                if (!configured && !reference) throw error(id, where + ".feature must be a configured feature with a type, or a feature id");
                if (feature.has("placement") && !feature.get("placement").isJsonArray()) throw error(id, where + ".placement must be a list");
                features.put(e.getKey(), feature);
            }
        }
        Map<String, String> palette = new LinkedHashMap<>();
        JsonElement p = o.get("palette");
        if (p != null && !p.isJsonNull()) {
            if (!p.isJsonObject()) throw error(id, "palette must be an object");
            for (String key : p.getAsJsonObject().keySet()) {
                if (!MaterialsCompiler.PALETTE_FIELDS.contains(key)) {
                    throw error(id, "palette." + key + " is not a palette field; known: " + String.join(", ", MaterialsCompiler.PALETTE_FIELDS));
                }
            }
            for (String key : MaterialsCompiler.PALETTE_FIELDS) {
                JsonElement v = p.getAsJsonObject().get(key);
                if (v == null || v.isJsonNull()) continue;
                if (!v.isJsonPrimitive() || !MaterialsCompiler.ID.matcher(v.getAsString()).matches()) {
                    throw error(id, "palette." + key + " must be a block id like `minecraft:stone`, not " + v);
                }
                palette.put(key, v.getAsString());
            }
        }
        JsonObject climate = null;
        JsonElement c = o.get("climate");
        if (c != null && !c.isJsonNull()) {
            if (!c.isJsonObject()) throw error(id, "climate must be an object of climate axes");
            try {
                BiomeLayout.climate(id, c.getAsJsonObject());
            } catch (IllegalArgumentException e) {
                throw error(id, e.getMessage());
            }
            climate = c.getAsJsonObject();
        }
        String role = null;
        if (o.has("role")) {
            role = text(id, o, "role");
            if (!BiomeRoles.ROLES.contains(role)) throw error(id, "role `" + role + "` is not one of " + BiomeRoles.ROLES);
        }
        return new Entry(id, realm, inspiredBy, differs, biome, features, new MaterialsCompiler.Palette(palette), climate, role);
    }

    private static String text(String id, JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || v.getAsString().isBlank()) {
            throw error(id, key + " is required: " + switch (key) {
                case "realm" -> "one of " + REALMS;
                case "inspiredBy" -> "one sentence naming what inspired the biome";
                default -> "one sentence on how it differs from vanilla";
            });
        }
        return v.getAsString();
    }

    // ---- validation -------------------------------------------------------------------------

    /**
     * Checks every library biome against vanilla 26.3: each {@code ~name}
     * names one of its features; palette blocks, block states ({@code id}),
     * feature, placement and particle types, sounds, spawned entities,
     * environment attributes, carvers and placed features exist; and every
     * other {@code minecraft:} reference names something vanilla has. A
     * reference in another namespace is refused: the library is vanilla's
     * blocks only. What the codecs check (field names, value ranges, block
     * state properties) is left to the game, which refuses the pack at load.
     */
    public void validate(VanillaData vanilla, String namespace) {
        for (Entry e : entries.values()) {
            new Checker(e, vanilla, namespace).run();
        }
    }

    private static final class Checker {
        private final Entry entry;
        private final VanillaData vanilla;
        private final String namespace;

        Checker(Entry entry, VanillaData vanilla, String namespace) {
            this.entry = entry;
            this.vanilla = vanilla;
            this.namespace = namespace;
        }

        void run() {
            entry.palette().blocks().forEach((field, block) -> need("palette." + field, block, "minecraft:block", "block"));
            JsonObject biome = entry.biome();
            JsonArray steps = arrayOf(biome.get("features"), "biome.features");
            if (steps.size() > STEPS) fail("biome.features has " + steps.size() + " steps; 26.3 has " + STEPS);
            for (int i = 0; i < steps.size(); i++) {
                JsonArray step = arrayOf(steps.get(i), "biome.features[" + i + "]");
                for (int j = 0; j < step.size(); j++) {
                    String where = "biome.features[" + i + "][" + j + "]";
                    String ref = string(step.get(j), where);
                    if (ref.startsWith("~")) {
                        own(where, ref);
                    } else if (!vanilla.hasPlacedFeature(ref)) {
                        fail(where + ": `" + ref + "` is not a vanilla placed feature");
                    }
                }
            }
            JsonElement carvers = biome.get("carvers");
            for (JsonElement c : carvers.isJsonArray() ? carvers.getAsJsonArray() : single(carvers)) {
                String ref = string(c, "biome.carvers");
                if (!(ref.startsWith("#") ? vanilla.knows(ref) : vanilla.hasFile("worldgen/carver", ref))) {
                    fail("biome.carvers: `" + ref + "` is not a vanilla carver");
                }
            }
            if (!biome.get("attributes").isJsonObject()) fail("biome.attributes must be an object");
            for (Map.Entry<String, JsonElement> a : biome.getAsJsonObject("attributes").entrySet()) {
                String where = "biome.attributes." + a.getKey();
                need(where, a.getKey(), "minecraft:environment_attribute", "environment attribute");
                attribute(where, a.getKey(), a.getValue());
            }
            walk("biome.effects", biome.get("effects"), null);
            for (Map.Entry<String, JsonObject> f : entry.features().entrySet()) {
                String where = "features." + f.getKey();
                JsonElement body = f.getValue().get("feature");
                if (body.isJsonObject()) {
                    need(where + ".feature.type", body.getAsJsonObject().get("type").getAsString(), "minecraft:worldgen/feature_type", "feature type");
                    walk(where + ".feature", body, null);
                } else {
                    String ref = body.getAsString();
                    if (ref.startsWith("~")) own(where + ".feature", ref);
                    else if (!vanilla.hasFile("worldgen/feature", ref)) fail(where + ".feature: `" + ref + "` is not a vanilla configured feature");
                }
                JsonElement placement = f.getValue().get("placement");
                if (placement != null) placement(where + ".placement", placement.getAsJsonArray());
            }
        }

        private void placement(String where, JsonArray modifiers) {
            for (int i = 0; i < modifiers.size(); i++) {
                String at = where + "[" + i + "]";
                if (!modifiers.get(i).isJsonObject() || !modifiers.get(i).getAsJsonObject().has("type")) fail(at + " must be a placement modifier with a type");
                need(at + ".type", modifiers.get(i).getAsJsonObject().get("type").getAsString(),
                        "minecraft:worldgen/placement_modifier_type", "placement modifier type");
                walk(at, modifiers.get(i), "type");
            }
        }

        private void attribute(String where, String key, JsonElement value) {
            if (SPAWNS.equals(key)) {
                JsonElement argument = value.isJsonObject() && value.getAsJsonObject().has("argument") ? value.getAsJsonObject().get("argument") : value;
                JsonElement byCategory = argument.isJsonObject() ? argument.getAsJsonObject().get("spawns_by_category") : null;
                if (byCategory != null && byCategory.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> c : byCategory.getAsJsonObject().entrySet()) {
                        for (JsonElement spawn : arrayOf(c.getValue(), where + "." + c.getKey())) {
                            if (spawn.isJsonObject() && spawn.getAsJsonObject().has("type")) {
                                need(where + "." + c.getKey(), spawn.getAsJsonObject().get("type").getAsString(), "minecraft:entity_type", "entity type");
                            }
                        }
                    }
                }
            }
            walk(where, value, null);
        }

        /** Every reference under {@code e}: typed by its key where the key says what it is, else any vanilla id. */
        private void walk(String where, JsonElement e, String skipKey) {
            if (e == null || e.isJsonNull()) return;
            if (e.isJsonArray()) {
                JsonArray a = e.getAsJsonArray();
                for (int i = 0; i < a.size(); i++) walk(where + "[" + i + "]", a.get(i), null);
                return;
            }
            if (e.isJsonObject()) {
                for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
                    String key = kv.getKey();
                    if (key.equals(skipKey)) continue;
                    String at = where + "." + key;
                    JsonElement v = kv.getValue();
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                        String s = v.getAsString();
                        switch (key) {
                            case "id" -> need(at, s, "minecraft:block", "block");
                            case "to_place", "provider", "block_provider", "below_trunk_provider", "trunk_provider", "foliage_provider",
                                 "state_provider", "ground_state", "cap_provider", "stem_provider" -> {
                                if (!vanilla.hasFile("worldgen/block_state_provider", s)) {
                                    fail(at + ": `" + s + "` is not a vanilla block state provider; a block is {\"id\": \"" + s + "\"}");
                                }
                            }
                            case "sound", "loop" -> need(at, s, "minecraft:sound_event", "sound");
                            default -> reference(at, s);
                        }
                    } else if (key.equals("particle") && v.isJsonObject() && v.getAsJsonObject().has("type")) {
                        need(at + ".type", v.getAsJsonObject().get("type").getAsString(), "minecraft:particle_type", "particle type");
                        walk(at, v, "type");
                    } else {
                        walk(at, v, null);
                    }
                }
                return;
            }
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) reference(where, e.getAsString());
        }

        private void reference(String where, String s) {
            if (s.startsWith("~")) {
                own(where, s);
                return;
            }
            if (!REF.matcher(s).matches()) return;
            String ns = s.substring(s.startsWith("#") ? 1 : 0, s.indexOf(':'));
            if (!ns.equals("minecraft")) {
                fail(where + ": `" + s + "` is not vanilla" + (ns.equals(namespace) ? "; name the biome's own features as ~<name>" : ""));
            } else if (!vanilla.knows(s)) {
                fail(where + ": `" + s + "` is not in vanilla " + "26.3's registries, data or tags");
            }
        }

        private void need(String where, String id, String registry, String what) {
            if (!vanilla.registry(registry).contains(id)) fail(where + ": `" + id + "` is not a vanilla " + what);
        }

        private void own(String where, String ref) {
            if (!entry.features().containsKey(ref.substring(1))) {
                fail(where + ": `" + ref + "` names no feature of this biome; it has " + entry.features().keySet());
            }
        }

        private JsonArray arrayOf(JsonElement e, String where) {
            if (e == null || !e.isJsonArray()) fail(where + " must be a list");
            return e.getAsJsonArray();
        }

        private String string(JsonElement e, String where) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) fail(where + " must be an id");
            return e.getAsString();
        }

        private static JsonArray single(JsonElement e) {
            JsonArray a = new JsonArray();
            a.add(e);
            return a;
        }

        private void fail(String message) {
            throw error(entry.id(), message);
        }
    }

    // ---- files ------------------------------------------------------------------------------

    /**
     * Pack path to file for every library biome in {@code namespace}: the biome,
     * and each feature's configured feature (unless it names one) and placed
     * feature, with every {@code ~name} resolved.
     */
    public SortedMap<String, JsonObject> files(String namespace) {
        SortedMap<String, JsonObject> out = new TreeMap<>();
        for (Entry e : entries.values()) {
            Map<String, String> refs = new LinkedHashMap<>();
            e.features().keySet().forEach(name -> refs.put("~" + name, featureId(namespace, e.id(), name)));
            out.put(DatapackCompiler.dataPath(namespace + ":" + e.id(), "worldgen/biome"),
                    DatapackCompiler.sorted(DatapackCompiler.replaceIds(e.biome(), refs).getAsJsonObject()));
            for (Map.Entry<String, JsonObject> f : e.features().entrySet()) {
                String id = featureId(namespace, e.id(), f.getKey());
                JsonElement body = DatapackCompiler.replaceIds(f.getValue().get("feature"), refs);
                if (body.isJsonObject()) {
                    out.put(DatapackCompiler.dataPath(id, "worldgen/feature"), DatapackCompiler.sorted(body.getAsJsonObject()));
                }
                JsonObject placed = new JsonObject();
                placed.add("feature", body.isJsonObject() ? new JsonPrimitive(id) : body);
                JsonElement placement = f.getValue().get("placement");
                placed.add("placement", placement == null ? new JsonArray() : DatapackCompiler.replaceIds(placement, refs));
                out.put(DatapackCompiler.dataPath(id, "worldgen/placed_feature"), DatapackCompiler.sorted(placed));
            }
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static String resource(String path) {
        try (InputStream in = BiomeLibrary.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("no " + path + " on the compiler's class path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static IllegalArgumentException error(String id, String message) {
        return new IllegalArgumentException("biome library: " + id + ": " + message);
    }

    /** The library biome ids a list of biome references names, in order, without repeats. */
    List<String> named(String namespace, Iterable<String> biomes) {
        List<String> out = new ArrayList<>();
        for (String b : biomes) {
            if (get(namespace, b).isPresent() && !out.contains(b)) out.add(b);
        }
        return out;
    }
}
