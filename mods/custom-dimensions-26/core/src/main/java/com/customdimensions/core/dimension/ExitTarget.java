package com.customdimensions.core.dimension;

import com.customdimensions.core.Id;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A parsed exit target: where "leaving" takes you. Understands the
 * shorthands ("bed" | "worldSpawn" | "origin") AND dimension-link
 * descriptors ({"dimension": "ns:slug", "arrival": "anchor" | "spawn" |
 * [x, y, z]}), making dimensions composable into chains and hubs.
 *
 * Canonical string form (for PortalReturnTarget.exitMode and
 * portal_links.json, which persist strings): shorthands pass through
 * unchanged; descriptors serialise as "dim!ns:slug!arrival" where arrival
 * is "anchor", "spawn", or "x,y,z". parse() inverts canonical strings.
 *
 * Resolving a target to a world position needs a running server and lives
 * in the server module; this class is the grammar.
 */
public final class ExitTarget {

    public enum Kind { BED, WORLD_SPAWN, ORIGIN, DIMENSION }

    private final Kind kind;
    private final String dimensionId;   // DIMENSION only
    private final String arrival;       // "anchor" | "spawn" | null (explicit pos)
    private final int[] arrivalPos;     // explicit [x, y, z] or null

    private ExitTarget(Kind kind, String dimensionId, String arrival, int[] arrivalPos) {
        this.kind = kind;
        this.dimensionId = dimensionId;
        this.arrival = arrival;
        this.arrivalPos = arrivalPos;
    }

    public Kind getKind() {
        return this.kind;
    }

    public String getDimensionId() {
        return this.dimensionId;
    }

    /** Parse a raw config value: shorthand string, canonical string, or descriptor object. */
    public static ExitTarget parse(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) {
            return null;
        }
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            return parse(raw.getAsString());
        }
        if (!raw.isJsonObject()) {
            return null;
        }
        JsonObject obj = raw.getAsJsonObject();
        JsonElement dim = obj.get("dimension");
        if (dim == null || !dim.isJsonPrimitive() || !dim.getAsJsonPrimitive().isString()) {
            return null;
        }
        String dimId = dim.getAsString().trim().toLowerCase();
        if (Id.tryParse(dimId) == null) {
            return null;
        }
        JsonElement arrival = obj.get("arrival");
        if (arrival == null || arrival.isJsonNull()) {
            return new ExitTarget(Kind.DIMENSION, dimId, "spawn", null);
        }
        if (arrival.isJsonPrimitive() && arrival.getAsJsonPrimitive().isString()) {
            String a = arrival.getAsString().trim();
            if ("anchor".equals(a) || "spawn".equals(a)) {
                return new ExitTarget(Kind.DIMENSION, dimId, a, null);
            }
            return null;
        }
        if (arrival.isJsonArray() && arrival.getAsJsonArray().size() == 3) {
            JsonArray arr = arrival.getAsJsonArray();
            try {
                return new ExitTarget(Kind.DIMENSION, dimId, null,
                        new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()});
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** Parse a shorthand or canonical string ("bed", "dim!ns:slug!spawn", ...). */
    public static ExitTarget parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        switch (s) {
            case "bed":
                return new ExitTarget(Kind.BED, null, null, null);
            case "worldSpawn":
                return new ExitTarget(Kind.WORLD_SPAWN, null, null, null);
            case "origin":
                return new ExitTarget(Kind.ORIGIN, null, null, null);
            default:
                break;
        }
        if (!s.startsWith("dim!")) {
            return null;
        }
        String[] parts = s.split("!", 3);
        if (parts.length != 3 || Id.tryParse(parts[1]) == null) {
            return null;
        }
        String arrival = parts[2];
        if ("anchor".equals(arrival) || "spawn".equals(arrival)) {
            return new ExitTarget(Kind.DIMENSION, parts[1], arrival, null);
        }
        String[] coords = arrival.split(",");
        if (coords.length == 3) {
            try {
                return new ExitTarget(Kind.DIMENSION, parts[1], null, new int[]{
                        Integer.parseInt(coords[0].trim()),
                        Integer.parseInt(coords[1].trim()),
                        Integer.parseInt(coords[2].trim())});
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** The canonical string form (round-trips through parse(String)). */
    public String canonical() {
        switch (this.kind) {
            case BED: return "bed";
            case WORLD_SPAWN: return "worldSpawn";
            case ORIGIN: return "origin";
            default: break;
        }
        if (this.arrivalPos != null) {
            return "dim!" + this.dimensionId + "!"
                    + this.arrivalPos[0] + "," + this.arrivalPos[1] + "," + this.arrivalPos[2];
        }
        return "dim!" + this.dimensionId + "!" + this.arrival;
    }

    /**
     * Canonicalise a raw config value to the string the persistence layer
     * stores; falls back to the given default shorthand on invalid input
     * (warn is the caller's job — this is used from getters).
     */
    public static String canonicalise(JsonElement raw, String fallback) {
        ExitTarget parsed = parse(raw);
        return parsed != null ? parsed.canonical() : fallback;
    }

    /** The explicit arrival position, or null for "anchor"/"spawn". */
    public int[] getArrivalPos() {
        return this.arrivalPos == null ? null : this.arrivalPos.clone();
    }

    /** "anchor", "spawn", or null when the arrival is an explicit position. */
    public String getArrival() {
        return this.arrival;
    }
}
