package com.customdimensions.client.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a request body. Objects become {@code LinkedHashMap}, arrays
 * {@code ArrayList}, numbers {@code Double}, and anything the grammar does not
 * cover throws {@link Malformed} with the offset it gave up at.
 */
public final class JsonReader {

    public static final class Malformed extends RuntimeException {
        public Malformed(String message) {
            super(message);
        }
    }

    private final String text;
    private int at;

    private JsonReader(String text) {
        this.text = text;
    }

    /** The parsed body, refused unless the whole of it is one JSON object. */
    public static Map<String, Object> object(String text) {
        Object parsed = parse(text);
        if (!(parsed instanceof Map)) {
            throw new Malformed("expected a JSON object, got "
                    + (parsed == null ? "null" : parsed.getClass().getSimpleName()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new Malformed("empty body");
        }
        JsonReader reader = new JsonReader(text);
        reader.skipSpace();
        if (reader.at >= text.length()) {
            throw new Malformed("empty body");
        }
        Object value = reader.value();
        reader.skipSpace();
        if (reader.at < text.length()) {
            throw new Malformed("trailing text at offset " + reader.at);
        }
        return value;
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        this.at++;
        Map<String, Object> map = new LinkedHashMap<>();
        skipSpace();
        if (peek() == '}') {
            this.at++;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw new Malformed("expected a quoted key at offset " + this.at);
            }
            String key = readString();
            skipSpace();
            if (peek() != ':') {
                throw new Malformed("expected ':' at offset " + this.at);
            }
            this.at++;
            skipSpace();
            map.put(key, value());
            skipSpace();
            char c = peek();
            this.at++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new Malformed("expected ',' or '}' at offset " + (this.at - 1));
            }
        }
    }

    private List<Object> readArray() {
        this.at++;
        List<Object> list = new ArrayList<>();
        skipSpace();
        if (peek() == ']') {
            this.at++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value());
            skipSpace();
            char c = peek();
            this.at++;
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new Malformed("expected ',' or ']' at offset " + (this.at - 1));
            }
        }
    }

    private String readString() {
        this.at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (this.at >= this.text.length()) {
                throw new Malformed("unterminated string at offset " + this.at);
            }
            char c = this.text.charAt(this.at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (this.at >= this.text.length()) {
                throw new Malformed("unterminated escape at offset " + this.at);
            }
            char escape = this.text.charAt(this.at++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (this.at + 4 > this.text.length()) {
                        throw new Malformed("truncated \\u escape at offset " + this.at);
                    }
                    String hex = this.text.substring(this.at, this.at + 4);
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        throw new Malformed("bad \\u escape at offset " + this.at);
                    }
                    this.at += 4;
                }
                default -> throw new Malformed("unknown escape at offset " + (this.at - 1));
            }
        }
    }

    private Double readNumber() {
        int start = this.at;
        while (this.at < this.text.length() && "+-.eE0123456789".indexOf(this.text.charAt(this.at)) >= 0) {
            this.at++;
        }
        if (start == this.at) {
            throw new Malformed("expected a value at offset " + start);
        }
        try {
            return Double.valueOf(this.text.substring(start, this.at));
        } catch (NumberFormatException e) {
            throw new Malformed("bad number at offset " + start);
        }
    }

    private void expect(String literal) {
        if (!this.text.startsWith(literal, this.at)) {
            throw new Malformed("expected " + literal + " at offset " + this.at);
        }
        this.at += literal.length();
    }

    private char peek() {
        if (this.at >= this.text.length()) {
            throw new Malformed("ended early at offset " + this.at);
        }
        return this.text.charAt(this.at);
    }

    private void skipSpace() {
        while (this.at < this.text.length() && Character.isWhitespace(this.text.charAt(this.at))) {
            this.at++;
        }
    }
}
