package com.customdimensions.compiler.datapack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Compiles definitions/ with every draft of a directory in place and samples each draft's place field. */
public class K7Drafts {
    public static void main(String[] a) throws Exception {
        Path defs = Path.of("definitions"), drafts = Path.of(a.length > 0 ? a[0] : "fidelity/drafts/k7");
        Path tmp = Files.createTempDirectory(Path.of("/tmp/claude-0/k7"), "defs");
        try (Stream<Path> s = Files.list(defs)) {
            for (Path p : s.toList()) Files.copy(p, tmp.resolve(p.getFileName()));
        }
        List<String> slugs = new ArrayList<>();
        try (Stream<Path> s = Files.list(drafts)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                Files.copy(p, tmp.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                slugs.add(p.getFileName().toString().replace(".json", ""));
            }
        }
        long t0 = System.nanoTime();
        DatapackCompiler.Output out = DatapackCompiler.compile(tmp, Fixtures.vanilla(), VersionTable.V26_3);
        System.out.println("full set (" + countDefs(tmp) + " definitions, " + slugs.size() + " drafts) compiled in "
                + (System.nanoTime() - t0) / 1_000_000 + " ms, " + out.files().size() + " files");
        for (String slug : slugs) {
            System.out.println("== " + slug);
            for (String note : out.report().entry(slug).notes()) {
                if (note.contains("place")) System.out.println("  NOTE " + note);
            }
            String id = null;
            for (String k : out.files().keySet()) {
                if (k.endsWith("/worldgen/density_function/places/" + slug + "/place.json")) {
                    String ns = k.split("/")[1];
                    id = ns + ":places/" + slug + "/place";
                }
            }
            if (id == null) {
                System.out.println("  no place field");
                continue;
            }
            DfEval e = new DfEval(out.files(), 20261022);
            JsonObject f = e.function(id).getAsJsonObject();
            int n = (int) Math.round(2 / f.getAsJsonObject("left").get("right").getAsDouble());
            JsonObject def = JsonParser.parseString(Files.readString(tmp.resolve(slug + ".json"))).getAsJsonObject();
            JsonObject borders = def.getAsJsonObject("borders");
            int r = Math.min(borders.has("player") ? borders.get("player").getAsInt() : 99999,
                    borders.has("generation") ? borders.get("generation").getAsInt() : 99999);
            int g = 128;
            int[] lab = new int[g * g];
            double[] share = new double[n];
            for (int row = 0; row < g; row++) {
                for (int c = 0; c < g; c++) {
                    int x = (int) (-r + (c + 0.5) * 2 * r / g), z = (int) (-r + (row + 0.5) * 2 * r / g);
                    float v = e.at(JsonParser.parseString("\"" + id + "\""), x, z);
                    int i = (int) Math.round((v + 1) * n / 2 - 0.5);
                    lab[row * g + c] = i;
                    share[i] += 1.0 / (g * g);
                }
            }
            var reg = new com.customdimensions.core.survey.Placeness(g, 2 * r / g, -r, -r).regions(lab);
            int[] majorPer = new int[n];
            int specks = 0;
            for (int k = 0; k < reg.areas().length; k++) {
                if (reg.areas()[k] >= 0.01 * g * g) majorPer[reg.labels()[k]]++;
                else specks += reg.areas()[k];
            }
            TreeSet<String> adj = new TreeSet<>();
            for (int row = 0; row < g; row++) {
                for (int c = 0; c < g; c++) {
                    int p = lab[row * g + c];
                    if (c + 1 < g && lab[row * g + c + 1] != p) adj.add(Math.min(p, lab[row * g + c + 1]) + "-" + Math.max(p, lab[row * g + c + 1]));
                    if (row + 1 < g && lab[(row + 1) * g + c] != p) adj.add(Math.min(p, lab[(row + 1) * g + c]) + "-" + Math.max(p, lab[(row + 1) * g + c]));
                }
            }
            StringBuilder b = new StringBuilder(), m = new StringBuilder();
            for (int i = 0; i < n; i++) {
                b.append(String.format(" %.1f", share[i] * 100));
                m.append(' ').append(majorPer[i]);
            }
            StringBuilder sites = new StringBuilder();
            JsonObject places = def.getAsJsonObject("biomeLayout").getAsJsonObject("places");
            if (places.has("at")) {
                for (String k : places.getAsJsonObject("at").keySet()) {
                    var xz = places.getAsJsonObject("at").getAsJsonArray(k);
                    float v = e.at(JsonParser.parseString("\"" + id + "\""), xz.get(0).getAsInt(), xz.get(1).getAsInt());
                    sites.append(' ').append(k).append("@").append(xz).append("->").append((int) Math.round((v + 1) * n / 2 - 0.5));
                }
            }
            System.out.printf("  FIELD n=%d r=%d shares%%%s; major regions per place%s; specks %.2f%%; centre place %d; adjacent %s; sites%s%n", n, r, b, m,
                    100.0 * specks / (g * g), lab[(g / 2) * g + g / 2], adj, sites);
        }
    }

    private static long countDefs(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).count();
        }
    }
}
