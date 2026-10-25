package com.customdimensions.core.firewall;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Writes a first, unreviewed bridge from a structure mod's jar or datapack
 * zip: its structure sets, its structures with the set that places each, and
 * its placed features outside {@code minecraft}. Sizes and tags are left
 * empty for a human; mixins and registries are never adopted here.
 */
public final class BridgeGenerator {
    private static final Pattern DATA = Pattern.compile("data/([a-z0-9_.-]+)/worldgen/(structure_set|structure|placed_feature)/(.+)\\.json");

    private BridgeGenerator() {}

    /** {@code BridgeGenerator <jar or zip> <out dir> [mod id]}: writes {@code <out dir>/<mod>.json} and prints its path. */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: BridgeGenerator <jar or zip> <out dir> [mod id]");
            System.exit(2);
        }
        Bridge bridge = generate(Path.of(args[0]), args.length > 2 ? Optional.of(args[2]) : Optional.empty());
        Path out = Path.of(args[1]).resolve(bridge.mod() + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, BridgeReader.write(bridge));
        System.out.println(out + ": " + bridge.structureSets().size() + " structure sets, " + bridge.structures().size()
                + " structures, " + bridge.features().size() + " placed features");
    }

    public static Bridge generate(Path archive, Optional<String> modId) {
        Map<String, List<String>> sets = new TreeMap<>();
        List<String> structureIds = new ArrayList<>();
        List<String> features = new ArrayList<>();
        String mod;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            mod = modId.or(() -> fabricId(zip)).orElseGet(() -> fromFileName(archive));
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Matcher m = DATA.matcher(entry.getName());
                if (entry.isDirectory() || !m.matches()) continue;
                String id = m.group(1) + ":" + m.group(3);
                switch (m.group(2)) {
                    case "structure_set" -> sets.put(id, setMembers(read(zip, entry)));
                    case "structure" -> structureIds.add(id);
                    default -> { if (!m.group(1).equals("minecraft")) features.add(id); }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> setOf = new TreeMap<>();
        sets.forEach((set, members) -> members.forEach(s -> setOf.putIfAbsent(s, set)));
        List<Bridge.Structure> structures = structureIds.stream().sorted()
                .map(id -> new Bridge.Structure(id, setOf.get(id), Bridge.Size.UNMEASURED, List.of(), List.of(), List.of())).toList();
        return new Bridge(mod, false, List.copyOf(sets.keySet()), structures, features.stream().sorted().toList(),
                List.of(), List.of(), List.of());
    }

    private static List<String> setMembers(String json) {
        List<String> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("structures")) return out;
        for (JsonElement e : root.getAsJsonObject().getAsJsonArray("structures")) {
            if (e.isJsonObject() && e.getAsJsonObject().has("structure")) out.add(e.getAsJsonObject().get("structure").getAsString());
        }
        return out;
    }

    private static Optional<String> fabricId(ZipFile zip) {
        ZipEntry entry = zip.getEntry("fabric.mod.json");
        if (entry == null) return Optional.empty();
        try {
            JsonObject meta = JsonParser.parseString(read(zip, entry)).getAsJsonObject();
            return meta.has("id") ? Optional.of(meta.get("id").getAsString()) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static String fromFileName(Path archive) {
        String name = archive.getFileName().toString().replaceFirst("\\.(jar|zip)$", "").toLowerCase();
        return name.replaceAll("[^a-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String read(ZipFile zip, ZipEntry entry) throws IOException {
        try (var in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
