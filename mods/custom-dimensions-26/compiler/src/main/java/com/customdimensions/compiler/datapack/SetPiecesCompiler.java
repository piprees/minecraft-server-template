package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.structure.Age;
import com.customdimensions.core.structure.SetPieceSpec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * A definition's {@code setPieces} to worldgen structures of our type
 * {@code customdimensions:set_piece} at {@code <ns>:set_piece/<slug>/<name>},
 * each with its resolved {@link SetPieceSpec}; a biome tag when it names
 * biomes; a random-spread structure set when it joins a group, which the
 * runtime pools into that group's noise placement. The control file gains one
 * {@code setPieces} entry per piece. Template sizes are read from vanilla's own
 * {@code .nbt} files; nothing is copied.
 */
public final class SetPiecesCompiler {

    public static final String TYPE = "customdimensions:set_piece";
    public static final String FOLDER = "set_piece";
    public static final String LIBRARY = "set-pieces";

    /** The files for one dimension and its control file's {@code setPieces} entries. */
    public record Compiled(Map<String, JsonObject> files, JsonArray control) {
        static final Compiled NONE = new Compiled(Map.of(), new JsonArray());
    }

    private SetPiecesCompiler() {
    }

    /** {@code definitions/set-pieces/<name>.json} by name; empty when the folder does not exist. */
    public static Map<String, JsonObject> library(Path definitionsDir) {
        Map<String, JsonObject> out = new TreeMap<>();
        Path dir = definitionsDir.resolve(LIBRARY);
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String name = f.getFileName().toString();
                out.put(name.substring(0, name.length() - 5), JsonParser.parseString(Files.readString(f)).getAsJsonObject());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Copies {@code from}'s set-piece library into {@code to}, for a compile of a copied definitions folder. */
    public static void copyLibrary(Path from, Path to) throws IOException {
        Path dir = from.resolve(LIBRARY);
        if (!Files.isDirectory(dir)) return;
        Files.createDirectories(to.resolve(LIBRARY));
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Files.copy(f, to.resolve(LIBRARY).resolve(f.getFileName()));
            }
        }
    }

    /** The structure id of set piece {@code name} of {@code slug}. */
    public static String structureId(String namespace, String slug, String name) {
        return namespace + ":" + FOLDER + "/" + slug + "/" + name;
    }

    static Compiled files(String namespace, String slug, Definition def, Map<String, JsonObject> library, VanillaData vanilla,
                          CompileReport.Entry entry) {
        JsonObject pieces = def.setPieces();
        if (pieces == null || pieces.isEmpty()) return Compiled.NONE;
        com.google.gson.JsonElement ageField = def.structures() != null ? def.structures().get("age") : null;
        com.customdimensions.core.structure.SetPieceSetting.AgeBands bands = com.customdimensions.core.structure.SetPieceSetting.AgeBands.read(
                slug + ": structures.age", ageField, def.spawn() != null ? def.spawn().x() : 0, def.spawn() != null ? def.spawn().z() : 0);
        Age dimensionAge = ageField != null && bands == null ? Age.parse(ageField.getAsString()) : null;
        SetPieceSpec.Catalogue catalogue = catalogue(vanilla);
        Map<String, JsonObject> files = new LinkedHashMap<>();
        JsonArray control = new JsonArray();
        for (Map.Entry<String, JsonElement> e : pieces.entrySet()) {
            String name = e.getKey();
            if (!name.matches("[a-z0-9_]+")) throw new IllegalArgumentException(slug + ": setPieces." + name + ": a name is lower case, digits and _");
            if (!e.getValue().isJsonObject()) throw new IllegalArgumentException(slug + ": setPieces." + name + " must be an object");
            SetPieceSpec spec = SetPieceSpec.resolve(slug, name, e.getValue().getAsJsonObject(), library, dimensionAge, catalogue);
            if (bands != null && spec.age() == null) spec = spec.withBands(bands);
            String id = structureId(namespace, slug, name);
            JsonObject structure = new JsonObject();
            structure.addProperty("type", TYPE);
            if (spec.site().biomes().isEmpty()) {
                structure.add("biomes", new JsonArray());
            } else {
                JsonObject tag = new JsonObject();
                JsonArray values = new JsonArray();
                for (String b : spec.site().biomes()) {
                    if (b.startsWith("#") || b.startsWith("minecraft:")) {
                        values.add(b);
                    } else {
                        JsonObject optional = new JsonObject();
                        optional.addProperty("id", b);
                        optional.addProperty("required", false);
                        values.add(optional);
                    }
                }
                tag.add("values", values);
                files.put(DatapackCompiler.dataPath(id, "tags/worldgen/biome"), tag);
                structure.addProperty("biomes", "#" + id);
            }
            structure.addProperty("step", spec.elements().stream().anyMatch(el -> com.customdimensions.core.structure.SetPieceShapes.KINDS.contains(el.kind()))
                    ? "top_layer_modification" : "surface_structures");
            structure.add("spawn_overrides", new JsonObject());
            structure.addProperty("terrain_adaptation", (Set.of("footing", "terrace").contains(spec.terrainAdaptation()) ? "none" : spec.terrainAdaptation()));
            structure.add("set_piece", spec.toJson());
            files.put(DatapackCompiler.dataPath(id, "worldgen/structure"), structure);
            JsonObject c = new JsonObject();
            c.addProperty("structure", id);
            c.addProperty("name", spec.name());
            c.addProperty("seat", spec.seat());
            c.addProperty("size", spec.size());
            if (spec.setting().lived() != null) c.addProperty("lived", true);
            if (spec.group() != null) {
                JsonObject set = new JsonObject();
                JsonArray entries = new JsonArray();
                JsonObject one = new JsonObject();
                one.addProperty("structure", id);
                one.addProperty("weight", 1);
                entries.add(one);
                set.add("structures", entries);
                JsonObject placement = new JsonObject();
                placement.addProperty("type", "minecraft:random_spread");
                placement.addProperty("spacing", 32);
                placement.addProperty("separation", 8);
                placement.addProperty("salt", Math.floorMod(id.hashCode(), 1_000_000_000));
                set.add("placement", placement);
                files.put(DatapackCompiler.dataPath(id, "worldgen/structure_set"), set);
                c.addProperty("set", id);
                c.addProperty("group", spec.group());
                if (spec.rarity() != null) c.addProperty("rarity", spec.rarity());
                if (spec.repetition() != null) c.addProperty("repetition", spec.repetition());
            }
            control.add(c);
            entry.structure("set piece `" + id + "` (" + spec.name() + "): seat " + spec.seat() + ", " + spec.elements().size()
                    + " element(s), size " + spec.size() + (spec.age() != null ? ", age " + spec.age().id()
                            : spec.setting().bands() != null ? ", age by distance" : "")
                    + (spec.setting().lived() != null ? ", lived-in at " + spec.setting().lived().density() + " per km²" : "")
                    + (spec.setting().approaches().isEmpty() ? "" : ", " + spec.setting().approaches().size() + " approach(es)")
                    + (spec.group() != null ? ", group " + spec.group() : ", placed by force or wants only"));
        }
        return new Compiled(files, control);
    }

    /**
     * A set piece's vanilla form ({@link VanillaForms}): a buried treasure in no
     * biome, so a vanilla server loads the entry and never places it.
     */
    static JsonObject vanillaForm(JsonObject setPiece) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "minecraft:buried_treasure");
        out.add("biomes", new JsonArray());
        out.add("step", setPiece.get("step"));
        out.add("spawn_overrides", new JsonObject());
        out.add("terrain_adaptation", setPiece.get("terrain_adaptation"));
        return out;
    }

    private static final Map<String, Optional<int[]>> SIZES = new ConcurrentHashMap<>();

    static SetPieceSpec.Catalogue catalogue(VanillaData vanilla) {
        return new SetPieceSpec.Catalogue() {
            public int[] templateSize(String id) {
                String key = vanilla.source().describe() + "|" + id;
                return SIZES.computeIfAbsent(key, k -> vanilla.source().bytes("data/minecraft/structure/" + id.substring(id.indexOf(':') + 1)
                        + ".nbt").map(SetPiecesCompiler::templateSize)).orElse(null);
            }

            public boolean block(String id) {
                return vanilla.registry("minecraft:block").contains(id);
            }

            public boolean entity(String id) {
                return vanilla.registry("minecraft:entity_type").contains(id);
            }

            public boolean lootTable(String id) {
                return vanilla.hasFile("loot_table", id);
            }

            public boolean processorList(String id) {
                return vanilla.hasFile("worldgen/processor_list", id);
            }
        };
    }

    /** A structure template's {@code size} {x, y, z} from its gzipped NBT. */
    static int[] templateSize(byte[] gz) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gz)))) {
            if (in.readByte() != 10) throw new IOException("a template's root is a compound");
            in.readUTF();
            while (true) {
                byte type = in.readByte();
                if (type == 0) throw new IOException("the template has no size");
                String name = in.readUTF();
                if (type == 9 && name.equals("size")) {
                    byte element = in.readByte();
                    int n = in.readInt();
                    if (element != 3 || n != 3) throw new IOException("the template's size is not three ints");
                    return new int[] {in.readInt(), in.readInt(), in.readInt()};
                }
                skip(in, type);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable structure template: " + e.getMessage(), e);
        }
    }

    private static void skip(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1 -> in.skipNBytes(1);
            case 2 -> in.skipNBytes(2);
            case 3, 5 -> in.skipNBytes(4);
            case 4, 6 -> in.skipNBytes(8);
            case 7 -> in.skipNBytes(in.readInt());
            case 8 -> in.skipNBytes(in.readUnsignedShort());
            case 9 -> {
                byte element = in.readByte();
                int n = in.readInt();
                for (int i = 0; i < n; i++) skip(in, element);
            }
            case 10 -> {
                while (true) {
                    byte t = in.readByte();
                    if (t == 0) return;
                    in.skipNBytes(in.readUnsignedShort());
                    skip(in, t);
                }
            }
            case 11 -> in.skipNBytes(4L * in.readInt());
            case 12 -> in.skipNBytes(8L * in.readInt());
            default -> throw new IOException("unknown tag type " + type);
        }
    }
}
