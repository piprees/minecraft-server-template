package com.customdimensions.compiler.convert;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which id namespaces belong to mods the register keeps. A namespace is flagged
 * when it appears in {@code namespaces.csv} and none of the mods owning it is
 * {@code kept}; {@code minecraft} and {@code adventure} are never flagged. An
 * owner's decision comes from {@code mod-register.csv} when the register lists
 * its slug, and from {@code namespaces.csv} otherwise.
 */
public final class NamespaceRegister {

    public static final String KEPT = "kept";
    private static final Set<String> NEVER_FLAGGED = Set.of("minecraft", "adventure");

    /** One mod that ships a namespace. */
    public record Owner(String slug, String decision) {
    }

    private final Map<String, List<Owner>> owners;

    public NamespaceRegister(Map<String, List<Owner>> owners) {
        Map<String, List<Owner>> copy = new LinkedHashMap<>();
        owners.forEach((ns, list) -> copy.put(ns, List.copyOf(list)));
        this.owners = Map.copyOf(copy);
    }

    public static NamespaceRegister load(Path modRegisterCsv, Path namespacesCsv) {
        Map<String, String> registerDecisions = new HashMap<>();
        List<Map<String, String>> register = Csv.read(modRegisterCsv);
        for (Map<String, String> row : register) {
            registerDecisions.put(Csv.require(row, "slug", modRegisterCsv), Csv.require(row, "decision", modRegisterCsv));
        }
        Map<String, List<Owner>> owners = new LinkedHashMap<>();
        for (Map<String, String> row : Csv.read(namespacesCsv)) {
            String namespace = Csv.require(row, "namespace", namespacesCsv);
            String slug = Csv.require(row, "slug", namespacesCsv);
            String decision = registerDecisions.getOrDefault(slug, Csv.require(row, "decision", namespacesCsv));
            owners.computeIfAbsent(namespace, k -> new ArrayList<>()).add(new Owner(slug, decision));
        }
        return new NamespaceRegister(owners);
    }

    public boolean isFlagged(String namespace) {
        if (NEVER_FLAGGED.contains(namespace)) {
            return false;
        }
        List<Owner> list = owners.get(namespace);
        return list != null && list.stream().noneMatch(o -> KEPT.equals(o.decision()));
    }

    /** The owners' distinct decisions, in register order, joined for the report. */
    public String decisions(String namespace) {
        Set<String> decisions = new LinkedHashSet<>();
        for (Owner owner : owners.getOrDefault(namespace, List.of())) {
            decisions.add(owner.decision());
        }
        return String.join(", ", decisions);
    }

    /** Minimal RFC 4180 reading: a header row, quoted fields with doubled quotes. */
    static final class Csv {

        private Csv() {
        }

        static List<Map<String, String>> read(Path file) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file, e);
            }
            List<Map<String, String>> rows = new ArrayList<>();
            if (lines.isEmpty()) {
                return rows;
            }
            List<String> header = split(lines.get(0));
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) {
                    continue;
                }
                List<String> fields = split(lines.get(i));
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    row.put(header.get(c), c < fields.size() ? fields.get(c) : "");
                }
                rows.add(row);
            }
            return rows;
        }

        static String require(Map<String, String> row, String column, Path file) {
            String value = row.get(column);
            if (value == null || value.isBlank()) {
                throw new ConversionException(file + ": row " + row + " has no " + column);
            }
            return value.trim();
        }

        static List<String> split(String line) {
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        field.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                } else {
                    field.append(c);
                }
            }
            fields.add(field.toString());
            return fields;
        }
    }
}
