package com.customdimensions.server.zone;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.structure.ZoneStructureRules;
import com.customdimensions.core.zone.ZoneMask;
import com.customdimensions.core.zone.ZoneSpec;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.structure.StructureRuntime;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every level's zones by level id, read from the definitions when the server
 * starts, before any level generates. Structure control reads each level's
 * zone weights from here ({@link StructureRuntime#zoneQueries}), and the
 * worldgen runtime its dry zones' masks.
 */
public final class LevelZones {
    private record Zoned(List<ZoneSpec> zones, ZoneStructureRules.ZoneQuery query, List<ZoneMask> dry) {}

    private static volatile Map<String, Zoned> levels = Map.of();

    private LevelZones() {
    }

    /** Registers the read; it must run before {@link com.customdimensions.server.level.WorldgenRuntime}'s at server start. */
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(LevelZones::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> levels = Map.of());
        StructureRuntime.zoneQueries(LevelZones::structureQuery);
    }

    static void load(MinecraftServer server) {
        LevelIndex index = LevelIndex.load(server);
        Map<String, Zoned> out = new HashMap<>();
        index.lazy().values().forEach(e -> put(out, e.key(), e.slug(), e.definition()));
        index.baseWorlds().forEach(w -> put(out, w.key(), w.slug(), w.definition()));
        levels = Map.copyOf(out);
        CustomDimensions.LOG.info("zones read for {} level(s)", out.size());
    }

    private static void put(Map<String, Zoned> out, ResourceKey<net.minecraft.world.level.Level> key, String slug, Definition def) {
        if (def == null || def.zones().isEmpty()) return;
        try {
            List<ZoneSpec> zones = ZoneSpec.all(slug, def);
            out.put(key.identifier().toString(), new Zoned(zones,
                    ZoneStructureRules.ZoneQuery.of(zones.stream().map(ZoneSpec::mask).toList()),
                    zones.stream().filter(z -> "dry".equals(z.aquifers())).map(ZoneSpec::mask).toList()));
        } catch (RuntimeException e) {
            CustomDimensions.LOG.warn("{}: zones do not read, so none apply at runtime: {}", slug, e.getMessage());
        }
    }

    /** The level's zones in definition order; empty when it has none. */
    public static List<ZoneSpec> zones(String levelId) {
        Zoned level = levels.get(levelId);
        return level == null ? List.of() : level.zones();
    }

    /** The level's zone weights for structure control, or null when it has no zones. */
    public static ZoneStructureRules.ZoneQuery structureQuery(String levelId) {
        Zoned level = levels.get(levelId);
        return level == null ? null : level.query();
    }

    /** The still-edge masks of the level's dry zones; empty when it has none. */
    public static List<ZoneMask> dryMasks(String levelId) {
        Zoned level = levels.get(levelId);
        return level == null ? List.of() : level.dry();
    }

    public static List<ZoneMask> dryMasks(ResourceKey<net.minecraft.world.level.Level> key) {
        return dryMasks(key.identifier().toString());
    }
}
