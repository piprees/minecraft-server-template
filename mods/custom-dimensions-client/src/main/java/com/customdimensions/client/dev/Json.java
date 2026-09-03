package com.customdimensions.client.dev;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Writes the responses. Everything a caller reads with {@code jq} is built
 * here, so a non-finite double becomes {@code null} rather than the literal
 * {@code NaN}, which is not JSON and stops the parse dead.
 */
public final class Json {

    private static final int SCALE = 3;

    private Json() {}

    public static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    public static String number(double value) {
        if (!Double.isFinite(value)) {
            return "null";
        }
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    public static String numbers(double... values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            out.append(i > 0 ? "," : "").append(number(values[i]));
        }
        return out.append(']').toString();
    }

    public static Obj obj() {
        return new Obj();
    }

    /** Fields come out in the order they went in. */
    public static final class Obj {

        private final StringBuilder body = new StringBuilder();

        public Obj str(String key, String value) {
            return raw(key, quote(value));
        }

        public Obj num(String key, double value) {
            return raw(key, number(value));
        }

        public Obj num(String key, long value) {
            return raw(key, Long.toString(value));
        }

        public Obj bool(String key, boolean value) {
            return raw(key, Boolean.toString(value));
        }

        public Obj raw(String key, String rawJson) {
            if (!this.body.isEmpty()) {
                this.body.append(',');
            }
            this.body.append(quote(key)).append(':')
                    .append(rawJson == null ? "null" : rawJson);
            return this;
        }

        @Override
        public String toString() {
            return "{" + this.body + "}";
        }
    }
}
