package com.customdimensions.compiler.convert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Iterator;
import java.util.Map;

/**
 * Deterministic JSON text: two-space indent, keys in the object's own order,
 * arrays of numbers and booleans on one line, a trailing newline. Numbers keep
 * the text they were parsed from, so {@code 1.0} stays {@code 1.0}.
 */
public final class JsonWriter {

    private static final Gson PRIMITIVES = new GsonBuilder().disableHtmlEscaping().create();

    private JsonWriter() {
    }

    public static String write(JsonElement element) {
        StringBuilder out = new StringBuilder();
        write(element, out, 0);
        return out.append('\n').toString();
    }

    private static void write(JsonElement element, StringBuilder out, int depth) {
        if (element.isJsonObject()) {
            writeObject(element.getAsJsonObject(), out, depth);
        } else if (element.isJsonArray()) {
            writeArray(element.getAsJsonArray(), out, depth);
        } else {
            out.append(PRIMITIVES.toJson(element));
        }
    }

    private static void writeObject(JsonObject object, StringBuilder out, int depth) {
        if (object.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        Iterator<Map.Entry<String, JsonElement>> entries = object.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, JsonElement> entry = entries.next();
            indent(out, depth + 1);
            out.append(PRIMITIVES.toJson(entry.getKey())).append(": ");
            write(entry.getValue(), out, depth + 1);
            out.append(entries.hasNext() ? ",\n" : "\n");
        }
        indent(out, depth);
        out.append('}');
    }

    private static void writeArray(JsonArray array, StringBuilder out, int depth) {
        if (array.isEmpty()) {
            out.append("[]");
            return;
        }
        if (isScalarRow(array)) {
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                out.append(i == 0 ? "" : ", ").append(PRIMITIVES.toJson(array.get(i)));
            }
            out.append(']');
            return;
        }
        out.append("[\n");
        for (int i = 0; i < array.size(); i++) {
            indent(out, depth + 1);
            write(array.get(i), out, depth + 1);
            out.append(i + 1 < array.size() ? ",\n" : "\n");
        }
        indent(out, depth);
        out.append(']');
    }

    private static boolean isScalarRow(JsonArray array) {
        for (JsonElement e : array) {
            boolean scalar = e.isJsonNull() || (e.isJsonPrimitive() && !e.getAsJsonPrimitive().isString());
            if (!scalar) {
                return false;
            }
        }
        return true;
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }
}
