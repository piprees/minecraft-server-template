package com.customdimensions.facts;

/**
 * A fact, or the reason there isn't one. Never both, never neither.
 *
 * <p>This type makes it impossible by construction to blend a measurement
 * with a guess: <em>measurements are exact or absent, never estimated</em>.
 * A {@code double} field can hold a
 * default, an average, or a value inferred from a sibling dimension, and
 * nothing downstream can tell which. A {@code Measured<Double>} cannot: it is
 * either a number somebody computed exactly, or a sentence saying why nobody
 * could.
 *
 * <p>The reason is not decoration. "not measured" in a viewer is useless;
 * "no terrain: this dimension's generator places no blocks" is a fact about the
 * dimension. Every {@link #absent} call must give the second kind.
 *
 * @param value  the measurement, or null when absent
 * @param reason why the measurement could not be made, or null when present
 */
public record Measured<T>(T value, String reason) {

    public Measured {
        if ((value == null) == (reason == null)) {
            throw new IllegalArgumentException(
                    "a Measured is exactly one of a value or a reason (got value="
                    + value + ", reason=" + reason + ")");
        }
    }

    public static <T> Measured<T> of(T value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Measured.of(null) — use absent(reason) and say why");
        }
        return new Measured<>(value, null);
    }

    /** @param reason why this could not be measured. Not "unknown". */
    public static <T> Measured<T> absent(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "an absent measurement must carry a reason");
        }
        return new Measured<>(null, reason);
    }

    public boolean isPresent() {
        return value != null;
    }

    /**
     * The value, or throw. Deliberately awkward: a caller that wants a number
     * whatever happens has to say so at the call site, where a reviewer can
     * see it, rather than a default sliding in from a field initialiser.
     */
    public T orThrow() {
        if (value == null) {
            throw new IllegalStateException("not measured: " + reason);
        }
        return value;
    }

    /** Map the value if present; carry the reason through if not. */
    public <R> Measured<R> map(java.util.function.Function<T, R> fn) {
        return value == null ? new Measured<>(null, reason) : Measured.of(fn.apply(value));
    }

    /**
     * JSON for one measurement: the bare value when present, or
     * {@code {"absent": "<reason>"}} when not.
     *
     * <p>Absence is a shape a consumer must handle, not a null it can coerce.
     * A viewer reading this cannot accidentally render an absent fact as 0 —
     * it would have to render an object as a number.
     */
    public String toJson(java.util.function.Function<T, String> valueJson) {
        if (value == null) {
            return "{\"absent\": " + Json.quote(reason) + "}";
        }
        return valueJson.apply(value);
    }
}
