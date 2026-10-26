package com.customdimensions.compiler.datapack;

import com.customdimensions.compiler.terrain.TerrainLibrary;
import com.customdimensions.compiler.terrain.TerrainSpec;
import com.customdimensions.compiler.zone.ZoneDensity;
import com.customdimensions.core.definition.BiomeEntry;
import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.zone.ZoneQuery;
import com.customdimensions.core.zone.ZoneSpec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A definition's {@code zones} as pack data. Each zone's mask is
 * {@code <ns>:zones/<slug>/<index>/mask} ({@link ZoneDensity}); a global swap
 * needs none. In zone order:
 *
 * <ul>
 *   <li><b>Terrain:</b> a zone's terrain compiles under {@code <ns>:terrain/<slug>/zone_<index>/}, and the router's
 *       {@code final_density}, {@code depth} and {@code chunk_surface_level} and the aquifers' {@code surface_level}
 *       lerp from the layers beneath to it by the mask, so materials and aquifers read the zone's surface.</li>
 *   <li><b>Aquifers:</b> {@code dry} lerps the aquifers' {@code exclusion} to 1 and their {@code surface_level}
 *       above the world, so no aquifer and no sea fills the zone's open cells.</li>
 *   <li><b>Biomes:</b> the router's {@code temperature} carries the zone whose biomes hold: where zone
 *       {@code c}'s mask reaches {@link ZoneQuery#BIOME_THRESHOLD} it is the base temperature plus
 *       {@code CHANNEL * c}. The {@code customdimensions:zoned} biome source reads the channel back, takes that
 *       zone's layout at the base temperature, and applies swaps.</li>
 * </ul>
 */
public final class ZonesCompiler {
    /** Our biome source type. */
    public static final String SOURCE_TYPE = "customdimensions:zoned";
    /** Temperature added per biome channel; vanilla temperature stays well inside half of it. */
    public static final double CHANNEL = 16.0;
    /** Blocks above the world's top that a dry zone's aquifer surface sits at. */
    static final int DRY_SURFACE_ABOVE = 64;

    /** The zones of one definition, read; {@link #apply} and {@link #biomeSource} write them. */
    public record Plan(String slug, String namespace, List<ZoneSpec> zones, Map<String, JsonObject> files) {
        /** Whether the zones change the noise settings (any zone's terrain, aquifers or biomes). */
        public boolean changesSettings() {
            return zones.stream().anyMatch(z -> z.terrain() != null || "dry".equals(z.aquifers()) || channelled(z));
        }

        String maskId(ZoneSpec zone) {
            return namespace + ":zones/" + slug + "/" + zone.index() + "/mask";
        }
    }

    private ZonesCompiler() {
    }

    /** The plan, or null when the definition has no zones. */
    static Plan plan(String slug, String namespace, Definition def, VanillaData vanilla, CompileReport.Entry entry) {
        if (def.zones().isEmpty()) return null;
        List<ZoneSpec> zones = new ArrayList<>();
        for (ZoneSpec read : ZoneSpec.all(slug, def)) {
            zones.add(known(read, vanilla, entry));
        }
        for (ZoneSpec zone : zones) {
            if (def.spawn() == null && def.zones().get(zone.index()).has("centre")
                    && def.zones().get(zone.index()).get("centre").isJsonPrimitive()) {
                entry.note("zones[" + zone.index() + "] is centred on spawn, which the definition does not set: centred on 0, 0");
            }
            if (zone.structures() != null) {
                entry.unsupported("zones[" + zone.index() + "].structures", "structure control is W4; the zone query is ready for it");
            }
        }
        return new Plan(slug, namespace, zones, new TreeMap<>());
    }

    /** Whether a zone takes a biome channel: it sets biomes or swaps them inside its shape. */
    static boolean channelled(ZoneSpec zone) {
        return zone.changesBiomes() && !zone.globalSwap();
    }

    /** {@code settings} with every zone blended into its router and aquifers; the files go into the plan. */
    static JsonObject apply(Plan plan, JsonObject settings, String preset, Integer seaLevel, VanillaData vanilla,
                            java.util.function.Function<String, JsonObject> functions, CompileReport.Entry entry) {
        JsonObject out = settings.deepCopy();
        JsonObject router = out.getAsJsonObject("noise_router");
        JsonObject aquifers = out.has("aquifers") ? out.getAsJsonObject("aquifers") : null;
        int top = out.getAsJsonObject("noise").get("min_y").getAsInt() + out.getAsJsonObject("noise").get("height").getAsInt();
        String jitter = plan.namespace() + ":zone_jitter";
        boolean jittered = false;
        JsonElement temperature = router.get("temperature").deepCopy();
        JsonElement baseTemperature = temperature.deepCopy();
        int channel = 0;
        for (ZoneSpec zone : plan.zones()) {
            if (zone.globalSwap()) continue;
            String where = plan.slug() + ": zones[" + zone.index() + "]";
            JsonObject mask = ZoneDensity.mask(zone.mask(), jitter);
            jittered |= zone.mask().jitter() > 0;
            plan.files().put(DatapackCompiler.dataPath(plan.maskId(zone), "worldgen/density_function"), cache(mask));
            JsonPrimitive maskRef = new JsonPrimitive(plan.maskId(zone));
            if (zone.terrain() != null) {
                TerrainSpec spec;
                TerrainLibrary.Compiled compiled;
                String name = plan.slug() + "/zone_" + zone.index();
                try {
                    spec = TerrainSpec.parse(where, zone.terrain());
                    if (spec == null) throw new IllegalArgumentException("terrain.base is required in a zone");
                    compiled = TerrainLibrary.compile(where, plan.namespace(), name, spec, preset,
                            vanilla.noiseSettings(TerrainLibrary.PRESETS.contains(preset) ? preset : "overworld"), seaLevel, vanilla);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(e.getMessage().startsWith(where) ? e.getMessage() : where + ": " + e.getMessage(), e);
                }
                compiled.files().forEach(plan.files()::put);
                JsonObject zoneRouter = compiled.settings().getAsJsonObject("noise_router");
                String zoneCheese = plan.namespace() + ":terrain/" + name + "/sloped_cheese";
                router.add("final_density", finalDensity(plan, zone, maskRef, router.get("final_density"),
                        zoneRouter.get("final_density"), zoneCheese, functions));
                for (String key : List.of("depth", "chunk_surface_level")) {
                    router.add(key, ZoneDensity.blend(maskRef, router.get(key), zoneRouter.get(key)));
                }
                if (aquifers != null) {
                    JsonElement surface = compiled.settings().has("aquifers")
                            ? compiled.settings().getAsJsonObject("aquifers").get("surface_level")
                            : new JsonPrimitive(plan.namespace() + ":terrain/" + name + "/preliminary_surface_level");
                    aquifers.add("surface_level", ZoneDensity.blend(maskRef, aquifers.get("surface_level"), surface));
                }
                entry.note("zones[" + zone.index() + "]: terrain `" + spec.family().id() + "` (" + spec.setting() + ") under `"
                        + plan.namespace() + ":terrain/" + name + "/`, blended over " + (int) zone.mask().blend() + " blocks");
            }
            if ("dry".equals(zone.aquifers())) {
                if (aquifers == null) {
                    entry.note("zones[" + zone.index() + "]: liquids.aquifers dry has no effect: the dimension has no aquifers");
                } else {
                    aquifers.add("exclusion", ZoneDensity.blend(maskRef, aquifers.get("exclusion"), new JsonPrimitive(1.0)));
                    aquifers.add("surface_level", ZoneDensity.blend(maskRef, aquifers.get("surface_level"),
                            new JsonPrimitive((double) top + DRY_SURFACE_ABOVE)));
                }
            }
            if (channelled(zone)) {
                channel++;
                JsonObject choice = new JsonObject();
                choice.addProperty("type", "minecraft:range_choice");
                choice.add("input", maskRef);
                choice.addProperty("min_inclusive", ZoneQuery.BIOME_THRESHOLD);
                choice.addProperty("max_exclusive", 2.0);
                JsonObject shifted = new JsonObject();
                shifted.addProperty("type", "minecraft:add");
                shifted.add("left", baseTemperature.deepCopy());
                shifted.addProperty("right", CHANNEL * channel);
                choice.add("when_in_range", shifted);
                choice.add("when_out_of_range", temperature);
                temperature = choice;
            }
        }
        if (channel > 0) {
            String climateId = plan.namespace() + ":zones/" + plan.slug() + "/temperature";
            plan.files().put(DatapackCompiler.dataPath(climateId, "worldgen/density_function"), temperature.getAsJsonObject());
            router.addProperty("temperature", climateId);
        }
        if (jittered) {
            plan.files().put(DatapackCompiler.dataPath(jitter, "worldgen/noise"), jitterNoise());
        }
        return out;
    }

    /**
     * The final density across a terrain zone. When the layers beneath are a
     * vanilla-shaped final density over a {@code sloped_cheese}, that cheese is
     * lerped to the zone's first: both surfaces are still unsqueezed there, so
     * the ground moves from one height to the other across the blend instead
     * of breaking off where the squeezed densities cross. Over the last
     * quarter of the mask ({@link #FLOOR_SWITCH}), where the surfaces already
     * agree, the result is lerped to the zone's own final density, which
     * carries the zone's floor and slides. Anything else is lerped directly.
     */
    static JsonElement finalDensity(Plan plan, ZoneSpec zone, JsonPrimitive mask, JsonElement beneath, JsonElement inZone,
                                    String zoneCheese, java.util.function.Function<String, JsonObject> functions) {
        JsonObject body = beneath.isJsonPrimitive() ? functions.apply(beneath.getAsString()) : beneath.getAsJsonObject();
        String cheese = body == null ? null : cheeseId(body);
        if (cheese == null) return ZoneDensity.blend(mask, beneath, inZone);
        String blendedId = plan.namespace() + ":zones/" + plan.slug() + "/" + zone.index() + "/sloped_cheese";
        plan.files().put(DatapackCompiler.dataPath(blendedId, "worldgen/density_function"),
                ZoneDensity.blend(mask, new JsonPrimitive(cheese), new JsonPrimitive(zoneCheese)));
        JsonElement shaped = DatapackCompiler.replaceIds(body, Map.of(cheese, blendedId));
        return ZoneDensity.blend(late(mask), shaped, inZone);
    }

    /** Where the zone's own final density takes over from the shaped one: the last quarter of the mask. */
    static final double FLOOR_SWITCH = 0.75;

    /** {@code clamp((mask - FLOOR_SWITCH) / (1 - FLOOR_SWITCH), 0, 1)}. */
    static JsonObject late(JsonPrimitive mask) {
        JsonObject shift = new JsonObject();
        shift.addProperty("type", "minecraft:add");
        shift.add("left", mask);
        shift.addProperty("right", -FLOOR_SWITCH);
        JsonObject scale = new JsonObject();
        scale.addProperty("type", "minecraft:mul");
        scale.add("left", shift);
        scale.addProperty("right", 1 / (1 - FLOOR_SWITCH));
        JsonObject clamp = new JsonObject();
        clamp.addProperty("type", "minecraft:clamp");
        clamp.add("input", scale);
        clamp.addProperty("min", 0.0);
        clamp.addProperty("max", 1.0);
        return clamp;
    }

    /** The one {@code .../sloped_cheese} id a final density refers to, or null. */
    static String cheeseId(JsonElement e) {
        java.util.Set<String> found = new java.util.TreeSet<>();
        collectCheese(e, found);
        return found.size() == 1 ? found.iterator().next() : null;
    }

    private static void collectCheese(JsonElement e, java.util.Set<String> into) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && e.getAsString().endsWith("/sloped_cheese")) {
            into.add(e.getAsString());
        } else if (e.isJsonObject()) {
            e.getAsJsonObject().entrySet().forEach(kv -> collectCheese(kv.getValue(), into));
        } else if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(v -> collectCheese(v, into));
        }
    }

    /** {@code base} wrapped in our source with each channelled zone's layout and swaps, or {@code base} when no zone changes biomes. */
    static JsonObject biomeSource(Plan plan, JsonObject base, String list, VanillaData vanilla, VersionTable.Version version,
                                  CompileReport.Entry entry) {
        JsonArray zones = new JsonArray();
        Map<String, String> global = new LinkedHashMap<>();
        for (ZoneSpec zone : plan.zones()) {
            if (zone.globalSwap()) {
                global.putAll(zone.swap());
                continue;
            }
            if (!channelled(zone)) continue;
            JsonObject z = new JsonObject();
            if (!zone.biomes().isEmpty()) {
                z.add("biomes", BiomeLayout.partition(zone.biomes(), list, vanilla.biomeParameters(list), vanilla.biomeIds(),
                        version.multiNoiseDisjoint(), version.id(), entry));
            }
            Map<String, String> swaps = zone.swap();
            if (!swaps.isEmpty()) z.add("swap", swapJson(swaps));
            zones.add(z);
        }
        if (zones.isEmpty() && global.isEmpty()) return base;
        JsonObject source = new JsonObject();
        source.addProperty("type", SOURCE_TYPE);
        source.add("base", base);
        source.add("zones", zones);
        if (!global.isEmpty()) source.add("swap", swapJson(global));
        entry.note("Biome source `" + SOURCE_TYPE + "`: " + zones.size() + " zone layout(s) over the "
                + base.get("type").getAsString().replace("minecraft:", "") + " source"
                + (global.isEmpty() ? "" : ", " + global.size() + " global swap(s)"));
        return source;
    }

    /** {@code zone} with only the biomes and swaps the game has; the rest are reported. */
    static ZoneSpec known(ZoneSpec zone, VanillaData vanilla, CompileReport.Entry entry) {
        List<BiomeEntry> biomes = new ArrayList<>();
        for (BiomeEntry b : zone.biomes()) {
            if (vanilla.biomeIds().contains(b.id())) {
                biomes.add(b);
            } else {
                entry.skippedBiome(b.id(), "zones[" + zone.index() + "] stamps a biome the game does not have; the zone keeps the biomes beneath it");
            }
        }
        return new ZoneSpec(zone.index(), zone.shapeName(), zone.mask(), zone.terrain(), biomes, knownSwaps(zone, vanilla, entry),
                zone.aquifers(), zone.structures());
    }

    private static Map<String, String> knownSwaps(ZoneSpec zone, VanillaData vanilla, CompileReport.Entry entry) {
        Map<String, String> out = new LinkedHashMap<>();
        zone.swap().forEach((from, to) -> {
            if (!vanilla.biomeIds().contains(from)) {
                entry.skippedBiome(from, "zones[" + zone.index() + "] swaps a biome the game does not have");
            } else if (!vanilla.biomeIds().contains(to)) {
                entry.skippedBiome(to, "zones[" + zone.index() + "] swaps to a biome the game does not have");
            } else {
                out.put(from, to);
            }
        });
        return out;
    }

    private static JsonObject swapJson(Map<String, String> swaps) {
        JsonObject o = new JsonObject();
        new TreeMap<>(swaps).forEach(o::addProperty);
        return o;
    }

    private static JsonObject cache(JsonObject f) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:cache");
        o.add("input", f);
        return o;
    }

    /** The edge jitter's noise: features of 128 blocks and finer, standard deviation {@link ZoneDensity#JITTER_SIGMA}. */
    static JsonObject jitterNoise() {
        JsonObject o = new JsonObject();
        JsonArray amplitudes = new JsonArray();
        amplitudes.add(1.0);
        amplitudes.add(0.5);
        amplitudes.add(0.25);
        o.add("amplitude_modifiers", amplitudes);
        o.addProperty("base_amplitude", 1.0);
        o.addProperty("base_octave", -7);
        o.addProperty("octave_count", 3);
        return o;
    }
}
