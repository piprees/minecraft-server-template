package com.customdimensions.client.dev;

import java.util.Map;

/**
 * Where the dev control surface's port comes from, and every reason it is 0.
 *
 * <p>Three sources, first hit wins: the system property, the environment
 * variable, then {@code config/customdimensionsclient-dev.json}. The property
 * and the variable suit a developer launching from an IDE; the file is what
 * local tooling writes, because Prism's CLI takes no JVM arguments and it
 * rewrites {@code instance.cfg} from memory when it quits.
 *
 * <p>The pack never ships the file. An absent one is the normal state on every
 * client and is silent; one that exists and names no usable port is a single
 * warning and still no listener.
 */
public final class DevPort {

    public static final String PROPERTY = "customdimensions.devPort";
    public static final String ENVIRONMENT = "CUSTOMDIMENSIONS_DEV_PORT";
    public static final String FILE_NAME = "customdimensionsclient-dev.json";

    private static final int MAX_PORT = 65535;

    /** {@code port} is 0 unless a listener should be created; {@code warning} is the only line to log. */
    public record Resolved(int port, String source, String warning) {}

    private static final Resolved OFF = new Resolved(0, "none", null);

    private DevPort() {}

    /** {@code fileJson} is null when the file does not exist, which is not a fault. */
    public static Resolved resolve(String property, String environment, String fileJson) {
        if (!blank(property)) {
            return fromValue(property.trim(), "property", "-D" + PROPERTY);
        }
        if (!blank(environment)) {
            return fromValue(environment.trim(), "environment", "$" + ENVIRONMENT);
        }
        return fileJson == null ? OFF : fromFile(fileJson);
    }

    /**
     * A source that names an unusable port stops there. Falling through would
     * open a listener the caller did not ask for.
     */
    private static Resolved fromValue(String raw, String source, String label) {
        int port = parse(raw);
        return port > 0
                ? new Resolved(port, source, null)
                : new Resolved(0, "none", label + " is not a usable port (" + raw
                        + ") — dev control surface off");
    }

    private static Resolved fromFile(String json) {
        Map<String, Object> read;
        try {
            read = JsonReader.object(json);
        } catch (JsonReader.Malformed e) {
            return new Resolved(0, "none", unusable(e.getMessage()));
        }
        if (!(read.get("port") instanceof Double held)) {
            return new Resolved(0, "none", unusable("no numeric \"port\" field"));
        }
        int port = (int) held.doubleValue();
        if (port != held.doubleValue() || port <= 0 || port > MAX_PORT) {
            return new Resolved(0, "none", unusable("port " + Json.number(held)));
        }
        return new Resolved(port, "file", null);
    }

    private static String unusable(String detail) {
        return "config/" + FILE_NAME + " names no usable port (" + detail
                + ") — delete it, or write {\"port\": <1-" + MAX_PORT
                + ">}; dev control surface off";
    }

    private static int parse(String raw) {
        try {
            int port = Integer.parseInt(raw);
            return port > 0 && port <= MAX_PORT ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
