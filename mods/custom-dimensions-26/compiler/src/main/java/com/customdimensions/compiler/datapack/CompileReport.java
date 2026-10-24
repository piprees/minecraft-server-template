package com.customdimensions.compiler.datapack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a compile emitted, per definition and in total, written beside the
 * pack as {@code compile-report.md}: every file, every skipped biome, every
 * field the compiler does not turn into data and which work package does.
 */
public final class CompileReport {

    /** Where a definition's level stem goes. */
    public enum Kind { BASE_WORLD, LAZY, CLONE }

    /** One definition's part of the report. */
    public static final class Entry {
        private final String slug;
        private Kind kind;
        private String detail = "";
        private final Set<String> emitted = new LinkedHashSet<>();
        private final List<String> skippedBiomes = new ArrayList<>();
        private final List<String> unsupported = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private final List<String> bedrock = new ArrayList<>();
        private String cloneOf;

        Entry(String slug) {
            this.slug = slug;
        }

        void kind(Kind kind, String detail) {
            this.kind = kind;
            this.detail = detail;
        }

        void emitted(String path) {
            emitted.add(path);
        }

        void skippedBiome(String id, String reason) {
            skippedBiomes.add("`" + id + "` (" + reason + ")");
        }

        void unsupported(String field, String reason) {
            unsupported.add("`" + field + "`: " + reason);
        }

        void note(String note) {
            notes.add(note);
        }

        void bedrock(String warning) {
            bedrock.add(warning);
        }

        void cloneOf(String id) {
            cloneOf = id;
        }

        /** The dimension a compiled clone takes its generator from, or null. */
        public String cloneOf() {
            return cloneOf;
        }

        /** Where the dimension's height leaves Bedrock's range for the dimension Geyser shows it as. */
        public List<String> bedrock() {
            return List.copyOf(bedrock);
        }

        public String slug() {
            return slug;
        }

        public Kind kind() {
            return kind;
        }

        /** Pack-relative paths this definition's output is made of, timelines it shares included. */
        public Set<String> emitted() {
            return Set.copyOf(emitted);
        }

        public List<String> skippedBiomes() {
            return List.copyOf(skippedBiomes);
        }

        public List<String> unsupported() {
            return List.copyOf(unsupported);
        }

        public List<String> notes() {
            return List.copyOf(notes);
        }
    }

    private final VersionTable.Version version;
    private final String namespace;
    private final Map<String, Entry> entries = new TreeMap<>();

    CompileReport(VersionTable.Version version, String namespace) {
        this.version = version;
        this.namespace = namespace;
    }

    Entry add(String slug) {
        return entries.computeIfAbsent(slug, Entry::new);
    }

    public Map<String, Entry> entries() {
        return Map.copyOf(entries);
    }

    public Entry entry(String slug) {
        return entries.get(slug);
    }

    /** The totals table rows, label to count, in report order. */
    public Map<String, Integer> totals(Map<String, String> files) {
        Map<String, Integer> totals = new java.util.LinkedHashMap<>();
        totals.put("Definitions", entries.size());
        totals.put("Compiled", count(k -> k != Kind.CLONE));
        totals.put("Clones compiled", (int) entries.values().stream().filter(e -> e.cloneOf != null).count());
        totals.put("Clones (not compiled)", count(k -> k == Kind.CLONE));
        totals.put("Base worlds (loaded at start)", count(k -> k == Kind.BASE_WORLD));
        totals.put("Lazy level stems", count(k -> k == Kind.LAZY));
        totals.put("Dimension types", countFiles(files, "/dimension_type/"));
        totals.put("Noise settings", countFiles(files, "/worldgen/noise_settings/"));
        totals.put("Timelines", countFiles(files, "/timeline/"));
        totals.put("Biome references skipped", entries.values().stream().mapToInt(e -> e.skippedBiomes.size()).sum());
        totals.put("Fallback biome sources", (int) entries.values().stream()
                .filter(e -> e.notes.stream().anyMatch(n -> n.startsWith(DatapackCompiler.FALLBACK_NOTE))).count());
        totals.put("Unsupported fields", entries.values().stream().mapToInt(e -> e.unsupported.size()).sum());
        totals.put("Bedrock height warnings", (int) entries.values().stream().filter(e -> !e.bedrock.isEmpty()).count());
        return totals;
    }

    String markdown(Map<String, String> files) {
        StringBuilder out = new StringBuilder();
        out.append("# Compile report\n\n");
        out.append("Generated by `./gradlew :compiler:compileDatapack`; do not edit. Target Minecraft ")
                .append(version.id()).append(" (data pack format ").append(version.packFormat())
                .append("), namespace `").append(namespace).append("`.\n\n");
        out.append("## Totals\n\n| | Count |\n| --- | --- |\n");
        totals(files).forEach((label, n) -> out.append("| ").append(label).append(" | ").append(n).append(" |\n"));
        out.append("\n## Per definition\n");
        for (Entry e : entries.values()) {
            out.append("\n### ").append(e.slug).append("\n\n");
            out.append("- Kind: ").append(switch (e.kind) {
                case BASE_WORLD -> "base world, loaded at start";
                case LAZY -> "lazy, opened by the runtime on first entry";
                case CLONE -> "clone, not compiled";
            }).append(e.detail.isEmpty() ? "" : " — " + e.detail).append('\n');
            if (!e.emitted.isEmpty()) {
                out.append("- Emitted: ").append(String.join(", ", e.emitted.stream().map(p -> "`" + p + "`").toList())).append('\n');
            }
            list(out, "Skipped biomes", e.skippedBiomes);
            list(out, "Unsupported", e.unsupported);
            list(out, "Notes", e.notes);
            list(out, "Bedrock", e.bedrock);
        }
        return out.toString();
    }

    private static void list(StringBuilder out, String label, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        out.append("- ").append(label).append(":\n");
        for (String item : items) {
            out.append("  - ").append(item).append('\n');
        }
    }

    private int count(java.util.function.Predicate<Kind> test) {
        return (int) entries.values().stream().filter(e -> test.test(e.kind)).count();
    }

    private static int countFiles(Map<String, String> files, String folder) {
        return (int) files.keySet().stream().filter(p -> p.contains(folder)).count();
    }
}
