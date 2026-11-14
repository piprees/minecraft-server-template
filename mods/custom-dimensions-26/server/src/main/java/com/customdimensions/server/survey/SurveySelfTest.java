package com.customdimensions.server.survey;

import com.customdimensions.server.command.SurveyCommand;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * The survey part of the self-test: {@code /customdim survey overworld 2 1}
 * (two stratified one-chunk patches, plus adaptive ones) through the command,
 * then the dossier is read back and must carry every section, a surface place
 * list and its layer maps.
 */
public final class SurveySelfTest {
    static final List<String> SECTIONS = List.of("plan", "atmosphere", "landforms", "crossCheck", "vertical", "surface", "vegetation", "terrain",
            "underground", "notable", "clusters", "structures", "biomeExpression", "places", "layerMaps");

    private SurveySelfTest() {}

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        CompletableFuture<List<String>> out = new CompletableFuture<>();
        List<String> replies = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                replies.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack source = server.createCommandSourceStack().withSource(sink);
        long start = System.nanoTime();
        var job = SurveyCommand.survey(source, "overworld", 2, 1, r -> {
            String line;
            try {
                JsonObject d = JsonParser.parseString(Files.readString(r.dossier())).getAsJsonObject();
                List<String> missing = new ArrayList<>();
                for (String s : SECTIONS) if (!d.has(s)) missing.add(s);
                boolean md = Files.readString(r.dossier().resolveSibling("dossier.md")).contains("## Summary");
                boolean maps = Files.exists(r.dossier().resolveSibling("layers").resolve("top.png"));
                int surfaceCells = d.getAsJsonObject("places").getAsJsonObject("surface").get("cells").getAsInt();
                boolean ok = missing.isEmpty() && md && maps && surfaceCells > 0 && r.patches() >= 2;
                line = (ok ? "PASS" : "FAIL") + " survey of a tiny patch: " + r.patches() + " patches, " + r.columns() + " columns, " + surfaceCells
                        + " surface cells, " + (SECTIONS.size() - missing.size()) + "/" + SECTIONS.size() + " sections"
                        + (missing.isEmpty() ? "" : " (missing " + missing + ")") + ", md " + md + ", maps " + maps + " in "
                        + (System.nanoTime() - start) / 1_000_000 + " ms";
            } catch (Exception e) {
                line = "FAIL survey of a tiny patch: " + e;
            }
            out.complete(List.of(line));
        });
        if (job.isEmpty()) return CompletableFuture.completedFuture(List.of("FAIL survey of a tiny patch: refused: " + replies));
        job.get().finished().exceptionally(e -> {
            out.complete(List.of("FAIL survey of a tiny patch: " + e));
            return null;
        });
        return out;
    }
}
