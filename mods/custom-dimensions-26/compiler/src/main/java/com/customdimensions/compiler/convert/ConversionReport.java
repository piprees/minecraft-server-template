package com.customdimensions.compiler.convert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code definitions/conversion-report.md}: totals first, then one section per
 * file. The text depends only on the reports passed in, so a re-run over the
 * same v1 files writes the same bytes.
 */
public final class ConversionReport {

    public static final String FLAGGED_HEADING = "### Flagged ids by namespace";

    private ConversionReport() {
    }

    /** {@code definitions} in output order; {@code settings} is the settings.json report. */
    public static String render(List<FileReport> definitions, FileReport settings, NamespaceRegister register) {
        List<FileReport> all = new java.util.ArrayList<>(definitions);
        all.add(settings);

        Map<String, Integer> flaggedByNamespace = new TreeMap<>();
        Map<String, Set<String>> distinctByNamespace = new TreeMap<>();
        Map<String, Integer> droppedByField = new TreeMap<>();
        Map<String, Integer> rewritesByPair = new TreeMap<>();
        Map<String, Integer> decisionsByCategory = new TreeMap<>();
        int flaggedTotal = 0;
        int droppedTotal = 0;
        int rewriteTotal = 0;
        int decisionTotal = 0;
        for (FileReport report : all) {
            for (FileReport.Flagged f : report.flagged()) {
                flaggedByNamespace.merge(f.namespace(), f.where().size(), Integer::sum);
                distinctByNamespace.computeIfAbsent(f.namespace(), k -> new TreeSet<>()).add(f.id());
                flaggedTotal += f.where().size();
            }
            for (FileReport.Dropped d : report.dropped()) {
                droppedByField.merge(d.field(), 1, Integer::sum);
                droppedTotal++;
            }
            for (FileReport.Rewrite r : report.rewrites()) {
                rewritesByPair.merge("`" + r.from() + "` → `" + r.to() + "`", 1, Integer::sum);
                rewriteTotal++;
            }
            for (FileReport.Decision d : report.decisions()) {
                decisionsByCategory.merge(d.category(), 1, Integer::sum);
                decisionTotal++;
            }
        }
        int distinctTotal = distinctByNamespace.values().stream().mapToInt(Set::size).sum();

        StringBuilder md = new StringBuilder();
        md.append("# Conversion report\n\n");
        md.append("`./gradlew :compiler:convertDefinitions` writes this file and the schema v2 definitions beside it\n");
        md.append("from the 1.21.1 definitions in `config/custom-dimensions/`. Every run rewrites both; edit the v1\n");
        md.append("files, not these. The mapping is `docs/design/rebuild-26/schema-v2.md`.\n\n");
        md.append("An id is flagged when no mod owning its namespace is kept (`mod-register.csv`, `namespaces.csv`);\n");
        md.append("`minecraft` and `adventure` never are. \"Where\" is the path in the v1 file, and \"(key)\" marks an\n");
        md.append("id used as an object key. Flagged ids are carried unchanged; W8 replaces them.\n\n");

        md.append("## Totals\n\n");
        md.append("- Files: ").append(definitions.size()).append(" definitions and settings.json\n");
        md.append("- Flagged ids: ").append(flaggedTotal).append(" occurrences of ").append(distinctTotal)
                .append(" distinct ids in ").append(flaggedByNamespace.size()).append(" namespaces\n");
        md.append("- Dropped fields: ").append(droppedTotal).append('\n');
        md.append("- Rewrites: ").append(rewriteTotal).append('\n');
        md.append("- W8 decisions: ").append(decisionTotal).append("\n\n");

        md.append(FLAGGED_HEADING).append("\n\n");
        if (flaggedByNamespace.isEmpty()) {
            md.append("None.\n\n");
        } else {
            md.append("| Namespace | Owners' decisions | Occurrences | Distinct ids |\n");
            md.append("| --- | --- | ---: | ---: |\n");
            flaggedByNamespace.forEach((ns, count) -> md.append("| ").append(ns).append(" | ")
                    .append(register.decisions(ns)).append(" | ").append(count).append(" | ")
                    .append(distinctByNamespace.get(ns).size()).append(" |\n"));
            md.append('\n');
        }
        table(md, "### Dropped fields by name", "Field", droppedByField, true);
        table(md, "### Rewrites", "Rewrite", rewritesByPair, false);
        table(md, "### W8 decisions", "Decision", decisionsByCategory, false);

        for (FileReport report : all) {
            section(md, report);
        }
        return md.toString().stripTrailing() + "\n";
    }

    private static void table(StringBuilder md, String heading, String column, Map<String, Integer> counts,
                              boolean codeKeys) {
        md.append(heading).append("\n\n");
        if (counts.isEmpty()) {
            md.append("None.\n\n");
            return;
        }
        md.append("| ").append(column).append(" | Count |\n| --- | ---: |\n");
        counts.forEach((key, count) -> md.append("| ").append(codeKeys ? "`" + key + "`" : key)
                .append(" | ").append(count).append(" |\n"));
        md.append('\n');
    }

    private static void section(StringBuilder md, FileReport report) {
        md.append("## ").append(report.source());
        if (!report.source().equals(report.target())) {
            md.append(" → ").append(report.target());
        }
        md.append("\n\n");
        if (report.isEmpty()) {
            md.append("Nothing flagged, dropped, rewritten or left for W8.\n\n");
            return;
        }
        if (!report.flagged().isEmpty()) {
            Map<String, Integer> byNs = report.flaggedCountByNamespace();
            int total = byNs.values().stream().mapToInt(Integer::intValue).sum();
            md.append("Flagged ids (").append(total).append("):\n\n");
            md.append("| Id | Owners' decisions | Where |\n| --- | --- | --- |\n");
            for (FileReport.Flagged f : report.flagged()) {
                md.append("| `").append(f.id()).append("` | ").append(f.decisions()).append(" | ")
                        .append(String.join(", ", f.where().stream().map(w -> "`" + w + "`").toList()))
                        .append(" |\n");
            }
            md.append('\n');
        }
        if (!report.dropped().isEmpty()) {
            md.append("Dropped:\n\n");
            Map<String, List<String>> details = new LinkedHashMap<>();
            report.dropped().forEach(d -> details.computeIfAbsent(d.field(), k -> new java.util.ArrayList<>())
                    .add(d.detail()));
            details.forEach((field, list) -> list.forEach(detail ->
                    md.append("- `").append(detail.replace("`", "'")).append("`\n")));
            md.append('\n');
        }
        if (!report.rewrites().isEmpty()) {
            md.append("Rewrites:\n\n");
            report.rewrites().forEach(r -> md.append("- `").append(r.where()).append("`: `").append(r.from())
                    .append("` → `").append(r.to()).append("`\n"));
            md.append('\n');
        }
        if (!report.decisions().isEmpty()) {
            md.append("W8 decisions:\n\n");
            report.decisions().forEach(d -> md.append("- ").append(d.detail()).append('\n'));
            md.append('\n');
        }
    }
}
