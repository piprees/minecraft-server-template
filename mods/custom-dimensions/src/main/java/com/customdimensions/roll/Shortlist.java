package com.customdimensions.roll;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.Artefacts;
import com.customdimensions.facts.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeds a person has kept by hand, at
 * {@code .seed-rolling/shortlist.json}.
 *
 * <p>Keyed by dimension id and NOT by {@link
 * com.customdimensions.command.InputHash} — unlike everything in {@link
 * SeedBank}, which is a cache of measurements and is rightly discarded when
 * the inputs that produced it move. A shortlist is a decision about a seed,
 * and a decision does not stop being one because a mod was rebuilt. A
 * shortlisted seed whose bank entry no longer exists shows without a score
 * and still gets drawn; the seed is what was being kept.
 *
 * <p>One file for the whole pack rather than one per dimension: it is a short
 * list of longs, it is read on every page render, and a directory of
 * single-line files would cost a scan to answer a question this size.
 */
public final class Shortlist {

    /** Loaded once, then authoritative — this process is the only writer. */
    private static volatile Map<String, Set<Long>> cache;

    private Shortlist() {
    }

    private static Path path() {
        return Artefacts.rollingDir().resolve("shortlist.json");
    }

    private static synchronized Map<String, Set<Long>> load() {
        Map<String, Set<Long>> held = cache;
        if (held != null) {
            return held;
        }
        Map<String, Set<Long>> loaded = new LinkedHashMap<>();
        Path file = path();
        if (Files.isRegularFile(file)) {
            try {
                loaded = parse(Files.readString(file));
            } catch (IOException | RuntimeException e) {
                MultiverseServer.LOGGER.warn("Shortlist unreadable, starting empty: {}", e.toString());
            }
        }
        cache = loaded;
        return loaded;
    }

    /** The {@code dimensions} map out of a shortlist body. Pure, so the shape is pinned with no filesystem. */
    static Map<String, Set<Long>> parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        Map<String, Set<Long>> out = new LinkedHashMap<>();
        if (!root.has("dimensions") || !root.get("dimensions").isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("dimensions").entrySet()) {
            Set<Long> seeds = new LinkedHashSet<>();
            for (JsonElement s : e.getValue().getAsJsonArray()) {
                seeds.add(s.getAsLong());
            }
            if (!seeds.isEmpty()) {
                out.put(e.getKey(), seeds);
            }
        }
        return out;
    }

    /** The file's body. Pure, for the same reason {@link #parse} is. */
    static String toJson(Map<String, Set<Long>> dimensions, String generatedAt) {
        StringBuilder b = new StringBuilder("{\n \"kind\": \"seed-shortlist\",\n");
        b.append(" \"generatedAt\": ").append(Json.quote(generatedAt)).append(",\n");
        b.append(" \"dimensions\": {");
        int i = 0;
        for (Map.Entry<String, Set<Long>> e : dimensions.entrySet()) {
            b.append(i++ > 0 ? ",\n  " : "\n  ").append(Json.quote(e.getKey())).append(": [");
            int j = 0;
            for (Long seed : e.getValue()) {
                b.append(j++ > 0 ? ", " : "").append(seed);
            }
            b.append(']');
        }
        b.append(dimensions.isEmpty() ? "}\n}\n" : "\n }\n}\n");
        return b.toString();
    }

    /** Every seed shortlisted for one dimension, in the order they were added. */
    public static Set<Long> of(String dimension) {
        Set<Long> seeds = load().get(dimension);
        return seeds == null ? Set.of() : new LinkedHashSet<>(seeds);
    }

    public static boolean contains(String dimension, long seed) {
        Set<Long> seeds = load().get(dimension);
        return seeds != null && seeds.contains(seed);
    }

    /**
     * Adds or removes one seed.
     *
     * @return whether the seed is shortlisted afterwards
     */
    public static synchronized boolean set(String dimension, long seed, boolean shortlisted) {
        Map<String, Set<Long>> current = load();
        Set<Long> seeds = current.computeIfAbsent(dimension, k -> new LinkedHashSet<>());
        boolean changed = shortlisted ? seeds.add(seed) : seeds.remove(seed);
        if (seeds.isEmpty()) {
            current.remove(dimension);
        }
        if (changed) {
            persist(current);
        }
        return shortlisted;
    }

    private static void persist(Map<String, Set<Long>> dimensions) {
        try {
            Artefacts.write(path(), toJson(dimensions, Instant.now().toString()));
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write the shortlist", e);
        }
    }

    /** Every dimension holding at least one shortlisted seed. */
    public static List<String> dimensions() {
        return new ArrayList<>(load().keySet());
    }

    /** Test seam only — drops the in-memory copy so the next read goes back to disk. */
    static synchronized void forget() {
        cache = null;
    }
}
