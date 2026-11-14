package com.customdimensions.core.score;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.definition.Definition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@link ZoneMap} of a definition's circle zones that set biomes:
 * {@code {shape: "circle", centre: [x, z], radius, blend, biomes}}, centred on
 * spawn when {@code centre} is absent. A column is inside at full strength
 * within {@code radius - blend / 2}. Later zones win where zones overlap.
 */
public final class DefinitionZones implements ZoneMap {

    private record Circle(int x, int z, double inner) {}

    private final List<Zone> zones = new ArrayList<>();
    private final List<Circle> circles = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();

    private DefinitionZones() {}

    public static DefinitionZones of(Definition def) {
        DefinitionZones out = new DefinitionZones();
        if (def == null) return out;
        BlockPos spawn = def.spawn();
        int index = 0;
        for (JsonObject zone : def.zones()) {
            String id = zone.has("id") ? zone.get("id").getAsString() : "zone" + index;
            index++;
            String shape = zone.has("shape") ? zone.get("shape").getAsString() : "circle";
            Set<String> biomes = biomes(zone.get("biomes"));
            if (biomes.isEmpty()) continue;
            if (!"circle".equals(shape) || !zone.has("radius")) {
                out.skipped.add(id + " (" + shape + ")");
                continue;
            }
            int x = spawn == null ? 0 : spawn.x();
            int z = spawn == null ? 0 : spawn.z();
            if (zone.has("centre") && zone.get("centre").isJsonArray()) {
                x = zone.getAsJsonArray("centre").get(0).getAsInt();
                z = zone.getAsJsonArray("centre").get(1).getAsInt();
            }
            double blend = zone.has("blend") ? zone.get("blend").getAsDouble() : 0.0;
            out.zones.add(new Zone(id, biomes));
            out.circles.add(new Circle(x, z, Math.max(0.0, zone.get("radius").getAsDouble() - blend / 2.0)));
        }
        return out;
    }

    /** Zones that set biomes in a shape this map does not read, as {@code id (shape)}. */
    public List<String> skipped() {
        return List.copyOf(skipped);
    }

    @Override
    public List<Zone> zones() {
        return List.copyOf(zones);
    }

    @Override
    public int zoneAt(int x, int z) {
        for (int i = circles.size() - 1; i >= 0; i--) {
            Circle c = circles.get(i);
            double dx = x - c.x();
            double dz = z - c.z();
            if (dx * dx + dz * dz <= c.inner() * c.inner()) return i;
        }
        return -1;
    }

    private static Set<String> biomes(JsonElement e) {
        Set<String> out = new LinkedHashSet<>();
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement item : e.getAsJsonArray()) {
            if (item.isJsonPrimitive()) out.add(item.getAsString());
            else if (item.isJsonObject() && item.getAsJsonObject().has("id")) out.add(item.getAsJsonObject().get("id").getAsString());
        }
        return out;
    }
}
