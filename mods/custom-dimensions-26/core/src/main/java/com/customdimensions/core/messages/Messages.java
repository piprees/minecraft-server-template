package com.customdimensions.core.messages;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Player-facing strings from {@code config/messages.json}, with built-in
 * defaults for a key the file lacks or a missing file. Templates use the
 * file's {@code {variable}} syntax; an unknown variable stays as written.
 */
public final class Messages {
    public static final String PORTAL_NO_SITE = "portal.no_site";
    public static final String WIN_EARNED = "win.earned";

    private static final Map<String, String> DEFAULTS = Map.of(
            PORTAL_NO_SITE, "The portal cannot find anywhere safe to put you.",
            WIN_EARNED, "§6{player} §rearned §e{win}§r in {dimension}.");

    private final Map<String, String> entries;

    private Messages(Map<String, String> entries) {
        this.entries = entries;
    }

    public static Messages defaults() {
        return new Messages(Map.of());
    }

    /** Reads {@code file}; a missing or unreadable file gives the defaults. */
    public static Messages load(Path file) {
        if (!Files.isRegularFile(file)) return defaults();
        try {
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            return defaults();
        }
    }

    public static Messages parse(String json) {
        Map<String, String> out = new HashMap<>();
        JsonElement root = JsonParser.parseString(json);
        if (root.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : ((JsonObject) root).entrySet()) {
                if (!e.getKey().startsWith("_") && e.getValue().isJsonPrimitive()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        return new Messages(out);
    }

    /** The template for {@code key}: the file's, the built-in default, else the key itself. */
    public String template(String key) {
        String own = entries.get(key);
        if (own != null) return own;
        return DEFAULTS.getOrDefault(key, key);
    }

    public String format(String key, Map<String, String> variables) {
        String text = template(key);
        for (Map.Entry<String, String> v : variables.entrySet()) {
            text = text.replace("{" + v.getKey() + "}", v.getValue());
        }
        return text;
    }
}
