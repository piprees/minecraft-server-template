package com.customdimensions.compiler.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The ground a biome belongs to, which decides the area of the map a biome
 * layout may give it.
 *
 * <ul>
 *   <li>{@code ocean}: vanilla's {@code #minecraft:is_ocean}.</li>
 *   <li>{@code river}: {@code #minecraft:is_river}.</li>
 *   <li>{@code shore}: {@code #minecraft:is_beach}, and every vanilla biome
 *       whose overworld placements all lie in vanilla's coast band.</li>
 *   <li>{@code peak}: {@code #minecraft:is_mountain}.</li>
 *   <li>{@code cave}: every vanilla biome whose overworld placements all lie
 *       below the surface (depth above 0), and every library biome of the
 *       {@code caves} realm.</li>
 *   <li>A library biome's {@code role} field wins over its realm.</li>
 *   <li>{@code land} otherwise; {@code wetland} only by a library or definition role.</li>
 * </ul>
 */
public final class BiomeRoles {
    public static final String OCEAN = "ocean";
    public static final String SHORE = "shore";
    public static final String RIVER = "river";
    public static final String PEAK = "peak";
    public static final String CAVE = "cave";
    public static final String WETLAND = "wetland";
    public static final String LAND = "land";
    public static final List<String> ROLES = List.of(LAND, OCEAN, SHORE, RIVER, PEAK, CAVE, WETLAND);
    /** Vanilla's coast band on continentalness, where its beaches and stony shores sit. */
    static final double COAST_LOW = -0.19, COAST_HIGH = -0.11;

    /** Vanilla oceans by the highest biome temperature each suits, coldest first. */
    private static final List<Map.Entry<Double, String>> SEAS = List.of(Map.entry(0.0, "minecraft:frozen_ocean"),
            Map.entry(0.4, "minecraft:cold_ocean"), Map.entry(0.9, "minecraft:ocean"), Map.entry(1.5, "minecraft:lukewarm_ocean"),
            Map.entry(Double.POSITIVE_INFINITY, "minecraft:warm_ocean"));

    private final Map<String, String> roles;
    private final Map<String, Double> temperatures;

    private BiomeRoles(Map<String, String> roles, Map<String, Double> temperatures) {
        this.roles = roles;
        this.temperatures = temperatures;
    }

    /** Roles from vanilla's biome tags and overworld placements, and the library's realms and {@code role} fields. */
    public static BiomeRoles of(VanillaData vanilla, BiomeLibrary library, String namespace) {
        Map<String, String> roles = new HashMap<>();
        Map<String, Double> temperatures = new HashMap<>();
        Map<String, boolean[]> placements = new HashMap<>();
        for (JsonElement e : vanilla.biomeParameters("overworld")) {
            JsonObject o = e.getAsJsonObject();
            JsonObject p = o.getAsJsonObject("parameters");
            boolean[] all = placements.computeIfAbsent(o.get("biome").getAsString(), k -> new boolean[]{true, true});
            all[0] &= low(p.get("depth")) > 0;
            all[1] &= low(p.get("continentalness")) >= COAST_LOW - 1e-6 && high(p.get("continentalness")) <= COAST_HIGH + 1e-6;
        }
        placements.forEach((id, all) -> {
            if (all[0]) roles.put(id, CAVE);
            else if (all[1]) roles.put(id, SHORE);
        });
        tag(vanilla, "is_mountain", PEAK, roles);
        tag(vanilla, "is_beach", SHORE, roles);
        tag(vanilla, "is_river", RIVER, roles);
        tag(vanilla, "is_ocean", OCEAN, roles);
        for (String id : vanilla.biomeIds()) {
            JsonElement t = vanilla.biome(id).get("temperature");
            if (t != null) temperatures.put(id, t.getAsDouble());
        }
        for (BiomeLibrary.Entry e : library.entries().values()) {
            String id = namespace + ":" + e.id();
            if (e.role() != null) roles.put(id, e.role());
            else if ("caves".equals(e.realm())) roles.put(id, CAVE);
            JsonElement t = e.biome().get("temperature");
            if (t != null) temperatures.put(id, t.getAsDouble());
        }
        return new BiomeRoles(Map.copyOf(roles), Map.copyOf(temperatures));
    }

    /** {@code id}'s role: {@code overrides} first, then the shipped roles, else {@link #LAND}. */
    public String role(String id, Map<String, String> overrides) {
        String o = overrides.get(id);
        return o != null ? o : roles.getOrDefault(id, LAND);
    }

    /** The biome's own temperature, if known. */
    public Optional<Double> temperature(String id) {
        return Optional.ofNullable(temperatures.get(id));
    }

    /** The vanilla ocean whose climate suits {@code temperature}. */
    public static String sea(double temperature) {
        for (Map.Entry<Double, String> s : SEAS) {
            if (temperature <= s.getKey()) return s.getValue();
        }
        return SEAS.getLast().getValue();
    }

    /** The ids of {@code #minecraft:<name>}, nested tags expanded. */
    static Set<String> tag(VanillaData vanilla, String name) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : vanilla.biomeTag("minecraft:" + name)) out.add(v);
        return out;
    }

    private static void tag(VanillaData vanilla, String name, String role, Map<String, String> roles) {
        for (String id : tag(vanilla, name)) roles.put(id, role);
    }

    private static double low(JsonElement range) {
        return range.isJsonArray() ? range.getAsJsonArray().get(0).getAsDouble() : range.getAsDouble();
    }

    private static double high(JsonElement range) {
        return range.isJsonArray() ? range.getAsJsonArray().get(1).getAsDouble() : range.getAsDouble();
    }
}
