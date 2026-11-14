package com.customdimensions.core;

/**
 * A namespaced id ({@code namespace:path}) with Minecraft's charset rules:
 * the namespace takes {@code [a-z0-9_.-]}, the path the same plus {@code /}.
 * A bare path takes the {@code minecraft} namespace, as does a leading colon.
 */
public record Id(String namespace, String path) implements Comparable<Id> {

    public static final String DEFAULT_NAMESPACE = "minecraft";

    public Id {
        if (!isNamespaceValid(namespace)) {
            throw new IllegalArgumentException("Non [a-z0-9_.-] character in namespace of location: "
                    + namespace + ":" + path);
        }
        if (!isPathValid(path)) {
            throw new IllegalArgumentException("Non [a-z0-9/._-] character in path of location: "
                    + namespace + ":" + path);
        }
    }

    /** Parses {@code ns:path} or a bare path; throws on an invalid id. */
    public static Id parse(String id) {
        Id parsed = tryParse(id);
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid id: " + id);
        }
        return parsed;
    }

    /** {@link #parse}, or null when the id is invalid. */
    public static Id tryParse(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) {
            return isPathValid(id) ? new Id(DEFAULT_NAMESPACE, id) : null;
        }
        String path = id.substring(colon + 1);
        if (!isPathValid(path)) {
            return null;
        }
        if (colon == 0) {
            return new Id(DEFAULT_NAMESPACE, path);
        }
        String namespace = id.substring(0, colon);
        return isNamespaceValid(namespace) ? new Id(namespace, path) : null;
    }

    public static Id of(String namespace, String path) {
        return new Id(namespace, path);
    }

    public static boolean isNamespaceValid(String namespace) {
        if (namespace == null) {
            return false;
        }
        for (int i = 0; i < namespace.length(); i++) {
            if (!isNamespaceChar(namespace.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isPathValid(String path) {
        if (path == null) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!isNamespaceChar(c) && c != '/') {
                return false;
            }
        }
        return true;
    }

    private static boolean isNamespaceChar(char c) {
        return c == '_' || c == '-' || c == '.' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    @Override
    public int compareTo(Id other) {
        int byPath = this.path.compareTo(other.path);
        return byPath != 0 ? byPath : this.namespace.compareTo(other.namespace);
    }

    @Override
    public String toString() {
        return this.namespace + ":" + this.path;
    }
}
