package com.customdimensions.core.firewall;

/** One thing the firewall refuses: which mod, what it adds and where. */
public record Finding(String mod, Kind kind, String where) {

    public enum Kind {
        DIMENSION("dimension"),
        DIMENSION_TYPE("dimension type"),
        NOISE_SETTINGS("noise settings"),
        BIOME_SOURCE("biome source type"),
        CHUNK_GENERATOR("chunk generator type"),
        MIXIN("mixin into worldgen class"),
        FRAMEWORK("worldgen framework");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public String line() {
        return mod + ": " + kind.label + " " + where;
    }
}
