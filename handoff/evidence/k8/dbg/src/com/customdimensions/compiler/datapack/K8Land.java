package com.customdimensions.compiler.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/** Each place's share of the border against its share of the land (the router's land cue above 0), over several seeds. */
public class K8Land {
    public static void main(String[] a) throws Exception {
        Path tmp = Files.createTempDirectory(Path.of("/tmp/claude-0/k8"), "defs");
        try (Stream<Path> s = Files.list(Path.of("definitions"))) {
            for (Path p : s.toList()) Files.copy(p, tmp.resolve(p.getFileName()));
        }
        for (String dir : new String[]{"fidelity/drafts/k6", "fidelity/drafts/k7"}) {
            try (Stream<Path> s = Files.list(Path.of(dir))) {
                for (Path p : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                    Files.copy(p, tmp.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        DatapackCompiler.Output out = DatapackCompiler.compile(tmp, Fixtures.vanilla(), VersionTable.V26_3);
        for (String slug : a) {
            String settingsKey = out.files().keySet().stream().filter(k -> k.endsWith("/worldgen/noise_settings/" + slug + ".json")).findFirst().orElse(null);
            if (settingsKey == null) {
                System.out.println("== " + slug + ": no noise settings");
                continue;
            }
            JsonObject router = JsonParser.parseString(out.files().get(settingsKey)).getAsJsonObject().getAsJsonObject("noise_router");
            JsonObject def = JsonParser.parseString(Files.readString(tmp.resolve(slug + ".json"))).getAsJsonObject();
            JsonObject places = def.getAsJsonObject("biomeLayout").getAsJsonObject("places");
            boolean humidity = def.getAsJsonObject("biomeLayout").has("axes")
                    && def.getAsJsonObject("biomeLayout").getAsJsonArray("axes").get(0).getAsString().equals("humidity");
            JsonElement place = router.get(humidity ? "vegetation" : "temperature");
            JsonElement land = router.get("continents");
            String placeId = null;
            for (String k : out.files().keySet()) {
                if (k.endsWith("/worldgen/density_function/places/" + slug + "/place.json")) placeId = k.split("/")[1] + ":places/" + slug + "/place";
            }
            System.out.println("== " + slug + " land cue " + land + ", places " + places.keySet());
            if (placeId == null || !land.isJsonPrimitive() || !land.getAsString().endsWith("/place")) {
                System.out.println("  no place field or no land cue");
                continue;
            }
            JsonElement field = JsonParser.parseString("\"" + placeId + "\"");
            JsonObject borders = def.getAsJsonObject("borders");
            int r = Math.min(borders.has("player") ? borders.get("player").getAsInt() : 99999,
                    borders.has("generation") ? borders.get("generation").getAsInt() : 99999);
            int g = 128;
            for (long seed : new long[]{20261022, 1, 2, 3, 4}) {
                DfEval e = new DfEval(out.files(), seed);
                JsonObject f = e.function(placeId).getAsJsonObject();
                int n = (int) Math.round(2 / f.getAsJsonObject("left").get("right").getAsDouble());
                double[] border = new double[n], onLand = new double[n];
                double landCells = 0;
                for (int row = 0; row < g; row++) {
                    for (int c = 0; c < g; c++) {
                        int x = (int) (-r + (c + 0.5) * 2 * r / g), z = (int) (-r + (row + 0.5) * 2 * r / g);
                        int i = (int) Math.round((e.at(field, x, z) + 1) * n / 2 - 0.5);
                        border[i]++;
                        if (e.at(land, x, z) > 0) {
                            onLand[i]++;
                            landCells++;
                        }
                    }
                }
                StringBuilder b = new StringBuilder();
                double worst = 0;
                for (int i = 0; i < n; i++) {
                    double bs = 100 * border[i] / (g * g), ls = landCells == 0 ? 0 : 100 * onLand[i] / landCells;
                    worst = Math.max(worst, Math.abs(bs - ls));
                    b.append(String.format(" %.1f/%.1f", bs, ls));
                }
                System.out.printf("  seed %d land %.1f%% of the border; border/land %% per place:%s; worst gap %.1f points%n", seed,
                        100 * landCells / (g * g), b, worst);
            }
        }
    }
}
