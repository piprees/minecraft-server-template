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
 * <p>Three places can carry the answer and only one of them is obvious:
 *
 * <ol>
 *   <li>{@code structures.wants} — the current shape, explicit block bands.</li>
 *   <li>{@code seedRoll.wants} — the legacy shape, band-name shorthand. This
 *       is where <b>75 of the 82</b> shipped dimensions still keep theirs.</li>
 *   <li>Neither, in which case the dimension silently inherits its family's
 *       default list and is scored against structures its author never
 *       named.</li>
 * </ol>
 *
 * <p>Reading only the first source is why lint's first run reported 4 dead
 * wants where the candidate bank proves 142: it was checking three
 * dimensions' worth of config and calling the pack clean. The order here
 * mirrors the roller's {@code build_profile} exactly, because a lint that
 * resolves wants differently from the thing that scores them is not checking
 * the same wants.
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
