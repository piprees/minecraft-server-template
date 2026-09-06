package com.customdimensions.client.dev;

import java.util.List;
import java.util.Map;

/**
 * One {@code /input} action, named by the body's single top-level key.
 *
 * <p>A field that is present but of the wrong type throws rather than falling
 * back: a walk that quietly used its default because the caller typed
 * {@code "twelve"} is the silent wrong answer this surface exists to stop.
 */
public final class DevRequest {

    public static final List<String> ACTIONS =
            List.of("walk", "look", "use", "sneak", "key", "realtime", "hud", "rebuild", "buckets");

    private final String action;
    private final Map<String, Object> fields;
    private final String value;
    private final String error;

    private DevRequest(String action, Map<String, Object> fields, String value, String error) {
        this.action = action;
        this.fields = fields;
        this.value = value;
        this.error = error;
    }

    private static DevRequest refused(String error) {
        return new DevRequest(null, Map.of(), null, error);
    }

    public static DevRequest parse(String body) {
        if (body == null || body.isBlank()) {
            return refused("empty body");
        }
        Map<String, Object> read;
        try {
            read = JsonReader.object(body);
        } catch (JsonReader.Malformed e) {
            return refused(e.getMessage());
        }
        if (read.isEmpty()) {
            return refused("no action named");
        }
        if (read.size() > 1) {
            return refused("one action per call, got " + String.join(", ", read.keySet()));
        }
        String action = read.keySet().iterator().next();
        if (!ACTIONS.contains(action)) {
            return refused("unknown action: " + action);
        }
        Object payload = read.get(action);
        Map<String, Object> fields = payload instanceof Map<?, ?> map ? cast(map) : Map.of();
        String value = payload instanceof String text ? text : null;
        return new DevRequest(action, fields, value, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    public boolean ok() {
        return this.error == null;
    }

    public String error() {
        return this.error;
    }

    public String action() {
        return this.action;
    }

    /** The action's value when it is a bare string, else null. */
    public String value() {
        return this.value;
    }

    public double number(String field, double fallback) {
        Object held = this.fields.get(field);
        if (held == null) {
            return fallback;
        }
        if (!(held instanceof Double d)) {
            throw new JsonReader.Malformed(field + " must be a number");
        }
        return d;
    }

    public boolean flag(String field, boolean fallback) {
        Object held = this.fields.get(field);
        if (held == null) {
            return fallback;
        }
        if (!(held instanceof Boolean b)) {
            throw new JsonReader.Malformed(field + " must be true or false");
        }
        return b;
    }

    public String text(String field, String fallback) {
        Object held = this.fields.get(field);
        if (held == null) {
            return fallback;
        }
        if (!(held instanceof String s)) {
            throw new JsonReader.Malformed(field + " must be a string");
        }
        return s;
    }
}
