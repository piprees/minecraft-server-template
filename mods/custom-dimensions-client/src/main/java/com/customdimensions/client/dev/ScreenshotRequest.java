package com.customdimensions.client.dev;

import java.util.Map;

/**
 * Where {@code POST /screenshot} writes. The path must be absolute: a relative
 * one resolves against the game's working directory, which puts the file
 * somewhere inside the launcher's instance and reads as a success.
 */
public final class ScreenshotRequest {

    private final String path;
    private final String error;

    private ScreenshotRequest(String path, String error) {
        this.path = path;
        this.error = error;
    }

    public static ScreenshotRequest parse(String body) {
        if (body == null || body.isBlank()) {
            return new ScreenshotRequest(null, "no path given");
        }
        Map<String, Object> read;
        try {
            read = JsonReader.object(body);
        } catch (JsonReader.Malformed e) {
            return new ScreenshotRequest(null, e.getMessage());
        }
        Object held = read.get("path");
        if (held != null && !(held instanceof String)) {
            return new ScreenshotRequest(null, "path must be a string");
        }
        String path = held == null ? "" : ((String) held).trim();
        if (path.isEmpty()) {
            return new ScreenshotRequest(null, "no path given");
        }
        if (!path.startsWith("/")) {
            return new ScreenshotRequest(null, "path must be absolute");
        }
        if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            return new ScreenshotRequest(null, "path must end in .png");
        }
        return new ScreenshotRequest(path, null);
    }

    public boolean ok() {
        return this.error == null;
    }

    public String error() {
        return this.error;
    }

    public String path() {
        return this.path;
    }
}
