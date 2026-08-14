package com.customdimensions.facts;

/**
 * The three JSON primitives the facts layer emits, and nothing else.
 *
 * <p>Doubles are written with {@link Double#toString}, never a formatted
 * rendering: a {@code %.6f} would make two different measurements compare
 * equal, which is the one thing a facts layer must never do.
 */
public final class Json {

    private Json() {
    }

    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** Exact repr. Non-finite values are absent facts, never JSON literals. */
    public static String number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(
                    "a non-finite value is not a measurement — use Measured.absent");
        }
        return Double.toString(v);
    }

    public static String number(long v) {
        return Long.toString(v);
    }
}
