package com.customdimensions.compiler.convert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code report.md} for {@link ApplyIdMapping}: totals, the rows not applied,
 * missing and removed, then one section per changed file. The text depends
 * only on the run, so two runs over the same set write the same bytes.
 */
public final class IdMappingReport {

    private IdMappingReport() {
    }

    public static String render(ApplyIdMapping.Run run, double flagLoss) {
        List<IdMappingApplier.Result> results = run.results();
        int replaced = 0;
        int removed = 0;
        int notApplied = 0;
        int missing = 0;
        Set<String> replacedIds = new TreeSet<>();
        int withBiomes = 0;
        int before = 0;
        int after = 0;
        List<IdMappingApplier.Result> flagged = new ArrayList<>();
        for (IdMappingApplier.Result r : results) {
            replaced += r.replaced().size();
            removed += r.removed().size();
            notApplied += r.notApplied().size();
            missing += r.missing().size();
            r.replaced().forEach(x -> replacedIds.add(x.from()));
            if (r.biomesBefore() > 0) {
                withBiomes++;
                before += r.biomesBefore();
                after += r.biomesAfter();
                if (loss(r) > flagLoss) flagged.add(r);
            }
        }
        int[] compiledBefore = ApplyIdMapping.compileCounts(run.compiledBefore());
        int[] compiledAfter = run.compiledAfter() != null ? ApplyIdMapping.compileCounts(run.compiledAfter()) : null;

        StringBuilder md = new StringBuilder();
        md.append("# Id mapping report\n\n");
        md.append("`./gradlew :compiler:applyIdMapping` writes this file, the rewritten set in `definitions/` beside it\n");
        md.append("and that set's `compile-report.md`. The table is `definitions/id-mapping.csv` (RB-36); the rules are in\n");
        md.append("`docs/design/rebuild-26/schema-v2.md` § Applying the id mapping.\n\n");
        md.append("## Totals\n\n");
        md.append("- Table rows: ").append(run.rows()).append('\n');
        md.append("- Files: ").append(results.size()).append(" (definitions and settings.json), ")
                .append(ApplyIdMapping.changed(run).size()).append(" changed\n");
        md.append("- Replaced: ").append(replaced).append(" occurrences of ").append(replacedIds.size()).append(" distinct ids\n");
        md.append("- Removed: ").append(removed).append('\n');
        md.append("- Not applied: ").append(notApplied).append('\n');
        md.append("- Missing rows: ").append(missing).append('\n');
        md.append("- Distinct biomes summed over the ").append(withBiomes).append(" definitions that list any: ")
                .append(before).append(" → ").append(after).append('\n');
        md.append("- Definitions losing over a third of their distinct biomes: ").append(flagged.size()).append('\n');
        if (compiledAfter == null) {
            md.append("- Compile: **the rewritten set does not compile**: ").append(run.compileError()).append('\n');
        } else {
            md.append("- Compile, before → after: biomes skipped ").append(compiledBefore[0]).append(" → ").append(compiledAfter[0])
                    .append(", structure warnings ").append(compiledBefore[1]).append(" → ").append(compiledAfter[1]).append('\n');
        }

        if (!flagged.isEmpty()) {
            md.append("\n## Losing over a third\n\n| Definition | Distinct biomes | Lost |\n| --- | ---: | ---: |\n");
            for (IdMappingApplier.Result r : flagged) {
                md.append("| ").append(slug(r)).append(" | ").append(r.biomesBefore()).append(" → ").append(r.biomesAfter())
                        .append(" | ").append(Math.round(loss(r) * 100)).append("% |\n");
            }
        }

        md.append("\n## Missing rows\n\n");
        Map<String, List<String>> missingById = new TreeMap<>();
        results.forEach(r -> r.missing().forEach(m -> missingById.computeIfAbsent(m.id(), k -> new ArrayList<>())
                .add(slug(r) + ": " + m.where())));
        if (missingById.isEmpty()) {
            md.append("None: every id from a cut mod has a row.\n");
        } else {
            md.append("| Id | Where |\n| --- | --- |\n");
            missingById.forEach((id, where) -> md.append("| `").append(id).append("` | ").append(String.join("; ", where)).append(" |\n"));
        }

        md.append("\n## Not applied\n\n");
        Map<String, List<String>> notById = new TreeMap<>();
        Map<String, String> reasonById = new TreeMap<>();
        results.forEach(r -> r.notApplied().forEach(n -> {
            String key = n.id() + "` → `" + n.target();
            notById.computeIfAbsent(key, k -> new ArrayList<>()).add(slug(r) + ": " + n.where());
            reasonById.putIfAbsent(key, n.reason());
        }));
        if (notById.isEmpty()) {
            md.append("None.\n");
        } else {
            md.append("| Id → target | Reason | Where |\n| --- | --- | --- |\n");
            notById.forEach((key, where) -> md.append("| `").append(key).append("` | ").append(reasonById.get(key))
                    .append(" | ").append(String.join("; ", where)).append(" |\n"));
        }

        md.append("\n## Removed\n\n");
        List<String> removedLines = new ArrayList<>();
        results.forEach(r -> r.removed().forEach(x -> removedLines.add("| " + slug(r) + " | `" + x.id() + "` | "
                + x.where() + " | " + x.reason() + " |")));
        if (removedLines.isEmpty()) {
            md.append("None.\n");
        } else {
            md.append("| Definition | Id | Where | Why |\n| --- | --- | --- | --- |\n");
            removedLines.forEach(l -> md.append(l).append('\n'));
        }

        md.append("\n## Definitions\n");
        for (IdMappingApplier.Result r : results) {
            if (r.replaced().isEmpty() && r.removed().isEmpty() && r.notApplied().isEmpty() && r.missing().isEmpty()) {
                continue;
            }
            md.append("\n### ").append(slug(r)).append("\n\n");
            if (r.biomesBefore() > 0) {
                md.append("- Distinct biomes: ").append(r.biomesBefore()).append(" → ").append(r.biomesAfter()).append('\n');
            }
            Map<String, List<String>> byPair = new LinkedHashMap<>();
            r.replaced().forEach(x -> byPair.computeIfAbsent("`" + x.from() + "` → `" + x.to() + "`", k -> new ArrayList<>())
                    .add(x.where()));
            if (!byPair.isEmpty()) {
                md.append("- Replaced ").append(r.replaced().size()).append(":\n");
                byPair.forEach((pair, where) -> md.append("  - ").append(pair).append(" (").append(String.join(", ", where)).append(")\n"));
            }
            r.removed().forEach(x -> md.append("- Removed `").append(x.id()).append("` at ").append(x.where())
                    .append(": ").append(x.reason()).append('\n'));
            r.notApplied().forEach(x -> md.append("- Not applied `").append(x.id()).append("` → `").append(x.target())
                    .append("` at ").append(x.where()).append(": ").append(x.reason()).append('\n'));
            r.missing().forEach(x -> md.append("- Missing row `").append(x.id()).append("` at ").append(x.where()).append('\n'));
        }
        return md.toString();
    }

    private static double loss(IdMappingApplier.Result r) {
        return r.biomesBefore() == 0 ? 0 : (r.biomesBefore() - r.biomesAfter()) / (double) r.biomesBefore();
    }

    private static String slug(IdMappingApplier.Result r) {
        return r.file().endsWith(".json") ? r.file().substring(0, r.file().length() - ".json".length()) : r.file();
    }
}
