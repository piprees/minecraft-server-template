package com.customdimensions.compiler.convert;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code definitions/id-mapping.csv} (RB-36): each id from a cut or rebuilt mod
 * and the id that replaces it. Columns {@code id, kind, namespace, decision,
 * files, uses, replacement, reason, changed}; an empty {@code replacement}
 * removes the entry that holds the id.
 */
public final class IdMapping {

    public static final String FILE = "id-mapping.csv";
    public static final Set<String> KINDS = Set.of("biome", "structure", "portal", "type");

    /** One row of the table. */
    public record Row(String id, String kind, String decision, String replacement, String reason) {
        public boolean removes() {
            return replacement.isEmpty();
        }
    }

    private final Map<String, Row> rows;

    public IdMapping(Collection<Row> rows) {
        Map<String, Row> byId = new LinkedHashMap<>();
        for (Row row : rows) {
            if (!KINDS.contains(row.kind())) {
                throw new ConversionException(FILE + ": `" + row.id() + "` has kind `" + row.kind() + "`; known: " + KINDS);
            }
            if (byId.put(row.id(), row) != null) {
                throw new ConversionException(FILE + ": `" + row.id() + "` has two rows");
            }
        }
        // A target that is itself mapped would change again on the next run.
        for (Row row : byId.values()) {
            if (byId.containsKey(row.replacement())) {
                throw new ConversionException(FILE + ": `" + row.id() + "` maps to `" + row.replacement()
                        + "`, which the table maps again");
            }
        }
        this.rows = Map.copyOf(byId);
    }

    public static IdMapping read(Path csv) {
        List<Map<String, String>> raw = NamespaceRegister.Csv.read(csv);
        return new IdMapping(raw.stream().map(r -> new Row(
                NamespaceRegister.Csv.require(r, "id", csv),
                NamespaceRegister.Csv.require(r, "kind", csv),
                NamespaceRegister.Csv.require(r, "decision", csv),
                r.getOrDefault("replacement", "").trim(),
                r.getOrDefault("reason", "").trim())).toList());
    }

    public Optional<Row> row(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    public int size() {
        return rows.size();
    }
}
