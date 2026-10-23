package com.customdimensions.server.portal;

import com.customdimensions.core.portal.PortalLinksFile;
import com.customdimensions.server.CustomDimensions;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Every lit portal on the server: source zones, arrival zones and the per-cell
 * return targets, keyed by the world they stand in, plus where each player
 * came from. Persisted to {@code <world>/data/customdimensions/portal_links.json}
 * in the 1.21.1 record format; on a world that has none yet, a 1.21.1 file at
 * {@code config/portal_links.json} is read instead.
 * <p>
 * An arrival is registered before a block of it is touched, because
 * everything that reads one asks the registry.
 */
public final class PortalRegistry {
    private static final PortalRegistry INSTANCE = new PortalRegistry();

    private final Map<ResourceKey<Level>, List<PortalZone>> zones = new HashMap<>();
    private final Map<ResourceKey<Level>, Map<BlockPos, PortalZone.ReturnTarget>> targets = new HashMap<>();
    private final Map<UUID, Origin> origins = new HashMap<>();
    private final List<PortalLinksFile.ReturnLink> unkeyed = new ArrayList<>();
    private final List<JsonElement> foreign = new ArrayList<>();
    private final Map<ResourceKey<Level>, Set<BlockPos>> pendingClears = new HashMap<>();
    private Path file;

    /** Where a player stepped into a source portal, for the way back. */
    public record Origin(ResourceKey<Level> world, BlockPos pos) {
    }

    public static PortalRegistry get() {
        return INSTANCE;
    }

    public static Path fileFor(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(CustomDimensions.ID)
                .resolve(PortalLinksFile.FILE_NAME);
    }

    public void load(MinecraftServer server) {
        clear();
        file = fileFor(server);
        Path source = file;
        Path legacy = server.getServerDirectory().resolve("config").resolve(PortalLinksFile.FILE_NAME);
        if (!Files.exists(file) && Files.exists(legacy)) {
            source = legacy;
        }
        PortalLinksFile.Contents contents;
        try {
            contents = PortalLinksFile.read(source);
        } catch (IOException e) {
            CustomDimensions.LOG.warn("portal links not read from {}: {}", source, e.toString());
            return;
        }
        contents.dropped().forEach(d -> CustomDimensions.LOG.warn("portal links: dropped a record: {}", d));
        for (PortalLinksFile.ReturnLink link : contents.links()) {
            if (link.portalWorld() == null) {
                unkeyed.add(link);
                continue;
            }
            targets.computeIfAbsent(key(link.portalWorld()), k -> new HashMap<>()).put(Pos.mc(link.pos()),
                    new PortalZone.ReturnTarget(key(link.targetWorld()), link.sourceY(), link.color(), link.cooldown(),
                            link.particleType(), link.exitMode(), link.sourceX(), link.sourceZ()));
        }
        for (PortalLinksFile.Zone stored : contents.zones()) {
            PortalZone zone = new PortalZone(Pos.mc(stored.interior()), stored.definition(), Pos.mc(stored.axis()),
                    key(stored.sourceWorld()), key(stored.targetWorld()), stored.arrival());
            if (stored.singleUseTicksLeft() != null) {
                zone.singleUseTicksLeft = stored.singleUseTicksLeft();
            }
            addIfAbsent(zone);
        }
        foreign.addAll(contents.other());
        CustomDimensions.LOG.info("portal links: {} zones, {} return cells from {}", zoneCount(), targetCount(), source);
    }

    public void clear() {
        zones.clear();
        targets.clear();
        origins.clear();
        unkeyed.clear();
        foreign.clear();
        pendingClears.clear();
    }

    public void save() {
        if (file == null) {
            return;
        }
        List<PortalLinksFile.ReturnLink> links = new ArrayList<>(unkeyed);
        targets.forEach((world, cells) -> cells.forEach((pos, t) -> links.add(new PortalLinksFile.ReturnLink(
                world.identifier().toString(), Pos.core(pos), t.leadsTo().identifier().toString(), t.sourceY(), t.color(),
                t.cooldown(), t.particleType(), t.exitMode(), t.sourceX(), t.sourceZ()))));
        List<PortalLinksFile.Zone> stored = new ArrayList<>();
        zones.values().forEach(list -> list.forEach(z -> stored.add(new PortalLinksFile.Zone(z.arrival,
                z.world.identifier().toString(), z.target.identifier().toString(), Pos.core(z.axis), z.definition,
                z.coreInterior(), z.singleUseTicksLeft >= 0 ? z.singleUseTicksLeft : null))));
        try {
            PortalLinksFile.write(file, new PortalLinksFile.Contents(links, stored, foreign, List.of()));
        } catch (IOException e) {
            CustomDimensions.LOG.warn("portal links not saved to {}: {}", file, e.toString());
        }
    }

    static ResourceKey<Level> key(String id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
    }

    /** Adds a zone unless one with the same world, axis and interior is lit; false when it was. */
    public boolean addIfAbsent(PortalZone zone) {
        List<PortalZone> list = zones.computeIfAbsent(zone.world, k -> new ArrayList<>());
        for (PortalZone z : list) {
            if (z.axis == zone.axis && z.interior.equals(zone.interior)) {
                return false;
            }
        }
        list.add(zone);
        return true;
    }

    public void remove(PortalZone zone) {
        List<PortalZone> list = zones.get(zone.world);
        if (list != null) {
            list.remove(zone);
        }
    }

    public List<PortalZone> zones(ResourceKey<Level> world) {
        return List.copyOf(zones.getOrDefault(world, List.of()));
    }

    public List<PortalZone> allZones() {
        List<PortalZone> out = new ArrayList<>();
        zones.values().forEach(out::addAll);
        return out;
    }

    public PortalZone zoneAt(ResourceKey<Level> world, BlockPos pos) {
        for (PortalZone z : zones.getOrDefault(world, List.of())) {
            if (z.interior.contains(pos)) {
                return z;
            }
        }
        return null;
    }

    public PortalZone.ReturnTarget target(ResourceKey<Level> world, BlockPos pos) {
        Map<BlockPos, PortalZone.ReturnTarget> cells = targets.get(world);
        return cells == null ? null : cells.get(pos);
    }

    public void putTarget(ResourceKey<Level> world, BlockPos pos, PortalZone.ReturnTarget target) {
        targets.computeIfAbsent(world, k -> new HashMap<>()).put(pos.immutable(), target);
    }

    public void removeTargets(ResourceKey<Level> world, Set<BlockPos> cells) {
        Map<BlockPos, PortalZone.ReturnTarget> map = targets.get(world);
        if (map != null) {
            cells.forEach(map::remove);
        }
    }

    /** An arrival zone standing within {@code radius} columns of ({@code x}, {@code z}) that passes {@code accept}. */
    public PortalZone arrivalNear(ResourceKey<Level> world, int x, int z, int radius,
                                  java.util.function.Predicate<PortalZone> accept) {
        PortalZone best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (PortalZone zone : zones.getOrDefault(world, List.of())) {
            if (!zone.arrival || !accept.test(zone)) {
                continue;
            }
            int[] c = zone.centreColumn();
            int d = Math.max(Math.abs(c[0] - x), Math.abs(c[1] - z));
            if (d <= radius && d < bestDistance) {
                best = zone;
                bestDistance = d;
            }
        }
        return best;
    }

    /** A source zone in {@code world} leading to {@code leadsTo}, within {@code radius} columns. */
    public PortalZone sourceNear(ResourceKey<Level> world, ResourceKey<Level> leadsTo, int x, int z, int radius) {
        for (PortalZone zone : zones.getOrDefault(world, List.of())) {
            if (zone.arrival || !zone.target.equals(leadsTo)) {
                continue;
            }
            int[] c = zone.centreColumn();
            if (Math.max(Math.abs(c[0] - x), Math.abs(c[1] - z)) <= radius) {
                return zone;
            }
        }
        return null;
    }

    /**
     * Drops every zone and return cell standing in a deleted world; portals
     * elsewhere that lead into it stay lit and build a new arrival.
     */
    public void forgetWorld(ResourceKey<Level> world) {
        int dropped = zones.getOrDefault(world, List.of()).size();
        zones.remove(world);
        targets.remove(world);
        pendingClears.remove(world);
        if (dropped > 0) {
            CustomDimensions.LOG.info("portal links: dropped {} zones in deleted {}", dropped, world.identifier());
            save();
        }
    }

    public void setOrigin(UUID id, ResourceKey<Level> world, BlockPos pos) {
        origins.put(id, new Origin(world, pos.immutable()));
    }

    public Origin origin(UUID id) {
        return origins.get(id);
    }

    public void clearOrigin(UUID id) {
        origins.remove(id);
    }

    /** Cells whose portal blocks must go once their chunk is resident. */
    public void queueClear(ResourceKey<Level> world, Set<BlockPos> cells) {
        pendingClears.computeIfAbsent(world, k -> new LinkedHashSet<>()).addAll(cells);
    }

    public Set<BlockPos> pendingClears(ResourceKey<Level> world) {
        return pendingClears.getOrDefault(world, Set.of());
    }

    public int zoneCount() {
        return zones.values().stream().mapToInt(List::size).sum();
    }

    public int targetCount() {
        return targets.values().stream().mapToInt(Map::size).sum();
    }

    /** Every return cell of {@code world} that leads back to a source portal at column ({@code x}, {@code z}). */
    public Set<BlockPos> arrivalCellsFor(ResourceKey<Level> world, ResourceKey<Level> sourceWorld, int x, int z) {
        Set<BlockPos> out = new HashSet<>();
        targets.getOrDefault(world, Map.of()).forEach((pos, t) -> {
            if (t.breaksSymmetrically() && sourceWorld.equals(t.leadsTo()) && t.sourceX() != null
                    && t.sourceX() == x && t.sourceZ() == z) {
                out.add(pos);
            }
        });
        return out;
    }
}
