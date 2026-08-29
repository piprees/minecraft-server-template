package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which structures a dimension config actually asks for.
 *
 * <p>Three places can carry the answer, most specific first:
 *
 * <ol>
 *   <li>{@code structures.wants} — the current shape, explicit block bands.</li>
 *   <li>{@code seedRoll.wants} — the legacy shape, band-name shorthand.</li>
 *   <li>Neither, in which case the dimension inherits its family's default
 *       list and is scored against structures its author never named.</li>
 * </ol>
 *
 * <p>The order here must mirror the roller's {@code build_profile} exactly —
 * a lint that resolves wants differently from the thing that scores them is
 * not checking the same wants.
 *
 * <p>The family table and the default lists are jar-baked
 * ({@code structure_default_wants.json}), for the same self-containment
 * reason as the structure themes.
 */
public final class StructureWants {

    /** Where a want list came from — lint reports inherited lists differently. */
    public enum Source {
        STRUCTURES_BLOCK,
        SEED_ROLL,
        FAMILY_DEFAULT,
        NONE,
    }

    /** The resolved want names, in config order, and where they came from. */
    public record Resolved(List<String> names, Source source, String family) {
    }

    private static Map<String, List<String>> familyTypes;
    private static Map<String, List<String>> familyWants;

    private StructureWants() {
    }

    public static Resolved resolve(DimensionConfig def) {
        DimensionConfig.Structures block = def.getStructures();
        if (block != null && block.wants != null) {
            return new Resolved(new ArrayList<>(block.wants.keySet()),
                    Source.STRUCTURES_BLOCK, familyOf(def));
        }
        DimensionConfig.SeedRoll sr = def.getSeedRoll();
        if (sr != null && (sr.wants != null || sr.shuns != null)) {
            List<String> names = new ArrayList<>();
            if (sr.wants != null) {
                for (Map.Entry<String, JsonElement> e : sr.wants.entrySet()) {
                    names.add(e.getKey());
                }
            }
            return new Resolved(names, Source.SEED_ROLL, familyOf(def));
        }
        String family = familyOf(def);
        List<String> defaults = defaultWants().get(family);
        if (defaults == null || defaults.isEmpty()) {
            return new Resolved(List.of(), Source.NONE, family);
        }
        return new Resolved(List.copyOf(defaults), Source.FAMILY_DEFAULT, family);
    }

    /**
     * The shun names a config carries, most specific first — the same order
     * {@link #resolve} walks for wants: {@code structures.shuns} (a MAP of
     * name to shun options) when the block names it, else {@code
     * seedRoll.shuns} (a bare list, or the same map shape). Two shapes, one
     * answer, because the pool builder and the scorer must discourage the same
     * structures or rolling searches for something the world is no less likely
     * to make.
     *
     * <p>Here rather than in the scorer because two readers of one config
     * field drift, and this one is read by the scorer, the facts layer and
     * {@code NoisePoolBuilder.shunnedStructureIds}.
     */
    public static List<String> shunNames(DimensionConfig def) {
        DimensionConfig.Structures block = def == null ? null : def.getStructures();
        if (block != null && block.shuns != null) {
            return new ArrayList<>(block.shuns.keySet());
        }
        DimensionConfig.SeedRoll sr = def == null ? null : def.getSeedRoll();
        if (sr == null || sr.shuns == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (sr.shuns.isJsonArray()) {
            for (JsonElement e : sr.shuns.getAsJsonArray()) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()
                        && !e.getAsString().isBlank()) {
                    out.add(e.getAsString().trim());
                }
            }
        } else if (sr.shuns.isJsonObject()) {
            out.addAll(sr.shuns.getAsJsonObject().keySet());
        }
        return out;
    }

    /**
     * The {@code #tag} ids this config's wants and shuns resolve to.
     *
     * <p>A want may name a tag rather than a structure ({@code village} is
     * {@code #minecraft:village}), and a tag is a set whose membership only the
     * registry knows. This is the list the facts layer has to look up so a
     * tag want can be scored against something instead of reporting itself
     * unmeasured forever.
     */
    public static java.util.Set<String> referencedTags(DimensionConfig def) {
        if (def == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        List<String> names = new ArrayList<>(resolve(def).names());
        names.addAll(shunNames(def));
        for (String name : names) {
            String id = StructureAliases.resolve(name);
            if (id != null && id.startsWith("#")) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * The scoring family: an explicit {@code seedRoll.family} wins, then the
     * type's family, then a guess from a clone id's text. Null when the type
     * belongs to no family — a paradise_lost clone, for instance, which
     * inherits no default want list and should not be given one.
     */
    public static String familyOf(DimensionConfig def) {
        DimensionConfig.SeedRoll sr = def.getSeedRoll();
        if (sr != null && sr.family != null) {
            String f = sr.family;
            if (f.equals("overworld") || f.equals("nether")
                    || f.equals("end") || f.equals("paradise_lost")) {
                return f;
            }
        }
        String type = def.getType();
        if (type == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : familyTypes().entrySet()) {
            if (e.getValue().contains(type)) {
                return e.getKey();
            }
        }
        if (type.contains(":")) {
            if (type.contains("nether")) {
                return "nether";
            }
            if (type.contains("end")) {
                return "end";
            }
        }
        return null;
    }

    /**
     * The family a world TYPE belongs to, from the jar-baked table alone.
     *
     * <p>{@link #familyOf} answers a different question: it is the SCORING
     * family and lets {@code seedRoll.family} override, which is right for
     * picking a want list and wrong for anything about the generator. This
     * one never consults the config.
     */
    public static String familyForType(String type) {
        if (type == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : familyTypes().entrySet()) {
            if (e.getValue().contains(type)) {
                return e.getKey();
            }
        }
        return null;
    }

    private static synchronized Map<String, List<String>> familyTypes() {
        if (familyTypes == null) {
            load();
        }
        return familyTypes;
    }

    private static synchronized Map<String, List<String>> defaultWants() {
        if (familyWants == null) {
            load();
        }
        return familyWants;
    }

    /** Test seam: forces the next lookup to re-read from the jar. */
    static synchronized void reset() {
        familyTypes = null;
        familyWants = null;
    }

    private static void load() {
        familyTypes = new LinkedHashMap<>();
        familyWants = new LinkedHashMap<>();
        try (InputStream in = StructureWants.class
                .getResourceAsStream("/structure_default_wants.json")) {
            if (in == null) {
                MultiverseServer.LOGGER.warn(
                        "structure_default_wants.json missing from jar — dimensions "
                        + "that name no wants will be reported as naming none");
                return;
            }
            Map<String, Object> raw = new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>() {
                    }.getType());
            familyTypes.putAll(stringLists(raw.get("families")));
            familyWants.putAll(stringLists(raw.get("wants")));
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Failed to load structure_default_wants.json", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> stringLists(Object node) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!(node instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getValue() instanceof List<?> list) {
                List<String> values = new ArrayList<>();
                for (Object o : list) {
                    values.add(String.valueOf(o));
                }
                out.put(String.valueOf(e.getKey()), values);
            }
        }
        return out;
    }
}
