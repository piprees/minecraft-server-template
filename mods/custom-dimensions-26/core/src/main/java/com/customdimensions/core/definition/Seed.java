package com.customdimensions.core.definition;

/** A fixed world seed, or {@code "env"}: the server's {@code SEED} environment variable. */
public record Seed(long value, boolean fromEnv) {

    public static final String ENV = "env";

    public static Seed of(long value) {
        return new Seed(value, false);
    }

    public static Seed env() {
        return new Seed(0L, true);
    }
}
