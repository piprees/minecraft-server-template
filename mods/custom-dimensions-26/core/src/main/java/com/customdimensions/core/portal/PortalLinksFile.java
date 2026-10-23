package com.customdimensions.core.portal;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.Direction;
import com.customdimensions.core.Id;
import com.customdimensions.core.config.PortalDefinition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code portal_links.json}: the persisted portal state, in the record format
 * the 1.21.1 mod writes, so a file it saved loads here.
 *
 * <ul>
 *   <li>a return link (no {@code recordType}): one arrival cell, the world it
 *       stands in ({@code portalWorld}) and where it leads back to;</li>
 *   <li>{@code source-zone-v1}: a portal a player lit;</li>
 *   <li>{@code arrival-zone-v1}: the frame built at the far end.</li>
 * </ul>
 * Any other record (an aura site) is kept verbatim and written back, so state
 * this module does not own survives a save. A malformed record is dropped
 * with its reason; a file that does not parse is left alone and reads as empty.
 */
public final class PortalLinksFile {

    public static final String SOURCE_RECORD = "source-zone-v1";
    public static final String ARRIVAL_RECORD = "arrival-zone-v1";
    public static final String FILE_NAME = "portal_links.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PortalLinksFile() {
    }

    /** One arrival cell's way back. {@code portalWorld} is null on a record from before worlds were keyed. */
    public record ReturnLink(String portalWorld, BlockPos pos, String targetWorld, int sourceY, int color,
                             int cooldown, String particleType, String exitMode, Integer sourceX, Integer sourceZ) {
    }

    /** A lit opening: which world it stands in, where it leads, and the definition it was lit with. */
    public record Zone(boolean arrival, String sourceWorld, String targetWorld, Direction.Axis axis,
                       PortalDefinition definition, Set<BlockPos> interior, Integer singleUseTicksLeft) {
    }

    public record Contents(List<ReturnLink> links, List<Zone> zones, List<JsonElement> other, List<String> dropped) {
        public static Contents empty() {
            return new Contents(List.of(), List.of(), List.of(), List.of());
        }
    }

    private static final class StoredPosition {
        int x;
        int y;
        int z;
    }

    private static final class StoredZone {
        String recordType;
        String sourceWorld;
        String targetWorld;
        String axis;
        PortalDefinition definition;
        List<StoredPosition> interior;
        Integer singleUseTicksLeft;
    }

    public static Contents read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Contents.empty();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            return parse(JsonParser.parseReader(reader));
        } catch (JsonParseException | IllegalStateException e) {
            return new Contents(List.of(), List.of(), List.of(), List.of("the file does not parse: " + e.getMessage()));
        }
    }

    public static Contents parse(JsonElement root) {
        List<ReturnLink> links = new ArrayList<>();
        List<Zone> zones = new ArrayList<>();
        List<JsonElement> other = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        if (root == null || !root.isJsonArray()) {
            return new Contents(links, zones, other, root == null || root.isJsonNull() ? dropped
                    : List.of("the file is not a list of records"));
        }
        for (JsonElement element : root.getAsJsonArray()) {
            try {
                JsonObject record = element.getAsJsonObject();
                String type = record.has("recordType") ? record.get("recordType").getAsString() : null;
                if (SOURCE_RECORD.equals(type) || ARRIVAL_RECORD.equals(type)) {
                    zones.add(toZone(GSON.fromJson(record, StoredZone.class)));
                } else if (type != null) {
                    other.add(record);
                } else {
                    links.add(toLink(record));
                }
            } catch (RuntimeException e) {
                dropped.add(String.valueOf(e.getMessage()));
            }
        }
        return new Contents(links, zones, other, dropped);
    }

    private static Zone toZone(StoredZone stored) {
        if (stored.sourceWorld == null || stored.targetWorld == null || stored.axis == null
                || stored.definition == null || stored.interior == null || stored.interior.isEmpty()) {
            throw new IllegalArgumentException("zone record is missing a field");
        }
        requireId(stored.sourceWorld);
        requireId(stored.targetWorld);
        Set<BlockPos> interior = new LinkedHashSet<>();
        for (StoredPosition p : stored.interior) {
            interior.add(new BlockPos(p.x, p.y, p.z));
        }
        if (interior.size() > PortalGeometry.MAX_PORTAL_BLOCKS) {
            throw new IllegalArgumentException("zone record exceeds the portal size limit");
        }
        Direction.Axis axis = Direction.Axis.valueOf(stored.axis);
        Integer ticks = stored.singleUseTicksLeft != null && stored.singleUseTicksLeft >= 0 ? stored.singleUseTicksLeft : null;
        return new Zone(ARRIVAL_RECORD.equals(stored.recordType), stored.sourceWorld, stored.targetWorld, axis,
                stored.definition, interior, ticks);
    }

    private static ReturnLink toLink(JsonObject link) {
        int x = link.get("x").getAsInt();
        int y = link.get("y").getAsInt();
        int z = link.get("z").getAsInt();
        String target = requireId(link.get("targetWorld").getAsString());
        String portalWorld = link.has("portalWorld") ? requireId(link.get("portalWorld").getAsString()) : null;
        return new ReturnLink(portalWorld, new BlockPos(x, y, z), target,
                link.has("sourceY") ? link.get("sourceY").getAsInt() : y,
                link.has("color") ? link.get("color").getAsInt() : 0x8844FF,
                link.has("cooldown") ? link.get("cooldown").getAsInt() : 40,
                link.has("particleType") ? link.get("particleType").getAsString() : null,
                link.has("exitMode") ? link.get("exitMode").getAsString() : null,
                link.has("sourceX") ? link.get("sourceX").getAsInt() : null,
                link.has("sourceZ") ? link.get("sourceZ").getAsInt() : null);
    }

    private static String requireId(String id) {
        if (Id.tryParse(id) == null) {
            throw new IllegalArgumentException("not a dimension id: " + id);
        }
        return id;
    }

    public static JsonArray toJson(Contents contents) {
        JsonArray out = new JsonArray();
        for (ReturnLink link : contents.links()) {
            JsonObject o = new JsonObject();
            o.addProperty("x", link.pos().getX());
            o.addProperty("y", link.pos().getY());
            o.addProperty("z", link.pos().getZ());
            if (link.portalWorld() != null) {
                o.addProperty("portalWorld", link.portalWorld());
            }
            o.addProperty("targetWorld", link.targetWorld());
            o.addProperty("sourceY", link.sourceY());
            o.addProperty("color", link.color());
            o.addProperty("cooldown", link.cooldown());
            if (link.particleType() != null) {
                o.addProperty("particleType", link.particleType());
            }
            if (link.exitMode() != null) {
                o.addProperty("exitMode", link.exitMode());
            }
            if (link.sourceX() != null && link.sourceZ() != null) {
                o.addProperty("sourceX", link.sourceX());
                o.addProperty("sourceZ", link.sourceZ());
            }
            out.add(o);
        }
        for (Zone zone : contents.zones()) {
            StoredZone stored = new StoredZone();
            stored.recordType = zone.arrival() ? ARRIVAL_RECORD : SOURCE_RECORD;
            stored.sourceWorld = zone.sourceWorld();
            stored.targetWorld = zone.targetWorld();
            stored.axis = zone.axis().name();
            stored.definition = zone.definition();
            stored.interior = new ArrayList<>();
            for (BlockPos p : zone.interior()) {
                StoredPosition s = new StoredPosition();
                s.x = p.getX();
                s.y = p.getY();
                s.z = p.getZ();
                stored.interior.add(s);
            }
            stored.singleUseTicksLeft = zone.singleUseTicksLeft();
            out.add(GSON.toJsonTree(stored));
        }
        contents.other().forEach(out::add);
        return out;
    }

    /** Writes through a temporary file and an atomic move, so a crash mid-write leaves the old file. */
    public static void write(Path file, Contents contents) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling("." + file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary)) {
            GSON.toJson(toJson(contents), writer);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
