package com.customdimensions.client.dev;

/**
 * Where the dev control surface's port comes from, and every reason it is 0.
 *
 * <p>The system property is read first: a Prism instance sets JVM args, not
 * environment variables. 0 means no listener is created at all.
 */
public final class DevPort {

    public static final String PROPERTY = "customdimensions.devPort";
    public static final String ENVIRONMENT = "CUSTOMDIMENSIONS_DEV_PORT";

    private DevPort() {}

    public static int resolve() {
        return portFrom(System.getProperty(PROPERTY), System.getenv(ENVIRONMENT));
    }

    /**
     * A value that is present but unusable disables the port outright — it never
     * falls through to the other source, which would open a listener the caller
     * did not name.
     */
    static int portFrom(String property, String environment) {
        String raw = blank(property) ? environment : property;
        if (blank(raw)) {
            return 0;
        }
        int port;
        try {
            port = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
        return port > 0 && port <= 65535 ? port : 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
