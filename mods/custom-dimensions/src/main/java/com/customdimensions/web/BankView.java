package com.customdimensions.web;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.InputHash;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.facts.Json;
import com.customdimensions.roll.CandidateRender;
import com.customdimensions.roll.Roller;
import com.customdimensions.roll.SeedBank;
import com.customdimensions.score.Frontier;
import com.customdimensions.score.Scorecard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the bank on disk looks like right now, for the browser.
 *
 * <p>Reads only. Every number comes from the written measurement and score —
 * {@link SeedBank}, {@link Scorecard}, {@link Frontier} — never from
 * anything recomputed here.
 */
public final class BankView {

    private BankView() {
    }

    /** One dimension and every candidate banked under its current input hash. */
    public record DimensionView(String slug, Identifier id, DimensionConfig config, String inputHash,
                                boolean rollable, List<CandidateView> candidates, int rejected,
                                List<Long> frontierSeeds) {
    }

    /** One candidate, with the scorecard's own per-criterion entries. */
    public record CandidateView(long seed, double achieved, double ceiling, Double percentage,
                                String verdict, Scorecard scorecard, List<String> strengths,
                                boolean hasLowres, boolean hasHighres) {
    }

    /**
     * Every configured world, in config order, with its bank — the base
     * worlds included. The overworld, the nether, the end and paradise_lost
     * carry the same seed, border, difficulty and structures blocks as any
     * custom dimension and are rolled and scored the same way, so a page that
     * skipped them would be missing the four worlds people spend most of
     * their time in.
     *
     * <p>A world with no candidates is included: the work not yet done is the
     * most useful row on the page.
     */
    public static List<DimensionView> all(MinecraftServer server) {
        List<DimensionView> out = new ArrayList<>();
        for (DimensionConfig def : rollTargets()) {
            out.add(of(server, def));
        }
        return out;
    }

    /**
     * Every world the roller and the viewer both work over: base worlds
     * first, since they are the ones a player actually lives in, then the
     * custom dimensions.
     */
    public static List<DimensionConfig> rollTargets() {
        List<DimensionConfig> out = new ArrayList<>(MultiverseConfig.getInstance().getWorlds());
        out.addAll(MultiverseConfig.getInstance().getDimensions());
        return out;
    }

    /**
     * A configured world by its slug, base worlds included. Every caller that
     * takes a slug off a URL or a button needs this rather than
     * {@code getDimension}, which answers null for a base world by design.
     */
    public static DimensionConfig resolve(String slug) {
        DimensionConfig def = MultiverseConfig.getInstance().getDimension(slug);
        return def != null ? def : MultiverseConfig.getInstance().getWorld(slug);
    }

    public static DimensionView of(MinecraftServer server, DimensionConfig def) {
        Identifier id = def.getDimensionIdentifier();
        String dimension = id.toString();
        String inputHash = InputHash.of(def, server);
        List<Scorecard> cards = SeedBank.scorecards(inputHash, dimension);
        List<Frontier.Member> frontier = Frontier.of(cards);
        Map<Long, List<String>> strengths = new LinkedHashMap<>();
        List<Long> frontierSeeds = new ArrayList<>();
        for (Frontier.Member m : frontier) {
            strengths.put(m.scorecard().seed(), m.strengths());
            frontierSeeds.add(m.scorecard().seed());
        }

        // Indexed rather than scanned: the page is built for every dimension
        // on every poll, and a scan per candidate is quadratic in a bank that
        // grows all through a roll.
        Map<Long, Scorecard> bySeed = new LinkedHashMap<>();
        for (Scorecard c : cards) {
            bySeed.putIfAbsent(c.seed(), c);
        }

        List<CandidateView> candidates = new ArrayList<>();
        for (SeedBank.CandidateSummary s : SeedBank.leaderboard(inputHash, dimension)) {
            Scorecard card = bySeed.get(s.seed());
            candidates.add(new CandidateView(s.seed(), s.achieved(), s.ceiling(), s.percentage(),
                    s.verdict(), card, strengths.getOrDefault(s.seed(), List.of()),
                    Files.isRegularFile(SeedBank.candidateImagePath(inputHash, dimension, s.seed(),
                            CandidateRender.Resolution.LOWRES)),
                    Files.isRegularFile(SeedBank.candidateImagePath(inputHash, dimension, s.seed(),
                            CandidateRender.Resolution.HIGHRES))));
        }
        return new DimensionView(id.getPath(), id, def, inputHash, Roller.rollable(def),
                candidates, SeedBank.rejectedSeeds(inputHash, dimension).size(), frontierSeeds);
    }

    /**
     * A candidate PNG's path on disk. The URL carries the dimension and seed;
     * the input hash is resolved from live config here, so a link stays valid
     * for as long as the bank it names does.
     */
    public static Path renderPath(MinecraftServer server, String dimensionSlug, String seed,
                                  boolean hires) {
        DimensionConfig def = resolve(dimensionSlug);
        if (def == null) {
            return null;
        }
        long parsed;
        try {
            parsed = Long.parseLong(seed);
        } catch (NumberFormatException e) {
            return null;
        }
        String dimension = def.getDimensionIdentifier().toString();
        return SeedBank.candidateImagePath(InputHash.of(def, server), dimension, parsed,
                hires ? CandidateRender.Resolution.HIGHRES : CandidateRender.Resolution.LOWRES);
    }

    /**
     * One candidate's structure census, read back out of the file the roll
     * wrote — counts per structure, the distance to the nearest of each, and
     * where spawn landed.
     *
     * <p>Positions are recomputed rather than read: the bank stores counts
     * and distances, never a coordinate per placement, and the sites are
     * derivable from the seed for a fraction of what measuring them cost.
     */
    public static String censusJson(MinecraftServer server, String slug, String seedText) {
        DimensionConfig def = resolve(slug);
        long seed;
        try {
            seed = Long.parseLong(seedText);
        } catch (NumberFormatException e) {
            return "{\"ok\": false, \"error\": \"unreadable seed\"}\n";
        }
        if (def == null) {
            return "{\"ok\": false, \"error\": " + Json.quote("no configured world " + slug) + "}\n";
        }
        Path file = SeedBank.candidatePath(InputHash.of(def, server),
                def.getDimensionIdentifier().toString(), seed);
        if (!Files.isRegularFile(file)) {
            return "{\"ok\": false, \"error\": \"this seed is not banked under the current inputs\"}\n";
        }
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser
                    .parseString(Files.readString(file)).getAsJsonObject();
            com.google.gson.JsonObject facts = root.getAsJsonObject("facts");
            com.google.gson.JsonObject out = new com.google.gson.JsonObject();
            out.addProperty("ok", true);
            com.google.gson.JsonElement structures = facts.get("structures");
            if (structures != null && structures.isJsonObject()) {
                com.google.gson.JsonObject s = structures.getAsJsonObject();
                copy(s, out, "totalPositions");
                copy(s, out, "byStructure");
                copy(s, out, "nearestByStructure");
                copy(s, out, "byGroup");
                copy(s, out, "nearestHostile");
                out.add("groups", positions(server, def, seed));
            }
            com.google.gson.JsonElement spawn = facts.get("spawn");
            if (spawn != null && spawn.isJsonObject()) {
                copyAs(spawn.getAsJsonObject(), out, "x", "spawnX");
                copyAs(spawn.getAsJsonObject(), out, "z", "spawnZ");
            }
            // Distances are measured from spawn, so the page needs one even
            // where the measurement recorded no spawn column.
            int[] configured = def.getSpawn();
            if (!out.has("spawnX") && configured != null && configured.length >= 3) {
                out.addProperty("spawnX", configured[0]);
                out.addProperty("spawnZ", configured[2]);
            }
            return out + "\n";
        } catch (IOException | RuntimeException e) {
            return "{\"ok\": false, \"error\": " + Json.quote("census unreadable: " + e) + "}\n";
        }
    }

    /**
     * Every noise-managed structure site, by group, recomputed here rather
     * than banked.
     *
     * <p>A dimension can carry thousands of placements; writing those into
     * every candidate file would multiply the bank for data only ever read
     * when one of a board's top ten is open. {@code NoiseFieldIndex} derives
     * them from the seed, so recomputing costs a fraction of the measurement
     * that produced the candidate in the first place.
     */
    private static com.google.gson.JsonObject positions(MinecraftServer server,
                                                        DimensionConfig def, long seed) {
        com.google.gson.JsonObject groups = new com.google.gson.JsonObject();
        try {
            com.customdimensions.command.SpikeSampler.Base base =
                    com.customdimensions.command.SpikeSampler.base(
                            server, def.getDimensionIdentifier());
            if (!base.ok()) {
                return groups;
            }
            int radius = Math.max(1, def.getPlayerBorderRadius());
            int[] spawn = def.getSpawn();
            long sx = spawn != null && spawn.length >= 3 ? spawn[0] : 0;
            long sz = spawn != null && spawn.length >= 3 ? spawn[2] : 0;
            for (Map.Entry<String, List<CandidateRender.Site>> e
                    : CandidateRender.structurePositions(server, def, base, seed, radius).entrySet()) {
                com.google.gson.JsonArray points = new com.google.gson.JsonArray();
                double nearest = Double.MAX_VALUE;
                for (CandidateRender.Site p : e.getValue()) {
                    com.google.gson.JsonArray xz = new com.google.gson.JsonArray();
                    xz.add(p.x());
                    xz.add(p.z());
                    xz.add(p.structureId());
                    points.add(xz);
                    double dx = p.x() - sx;
                    double dz = p.z() - sz;
                    nearest = Math.min(nearest, Math.sqrt(dx * dx + dz * dz));
                }
                com.google.gson.JsonObject group = new com.google.gson.JsonObject();
                group.add("positions", points);
                group.addProperty("hostile", CandidateRender.isHostileGroup(e.getKey()));
                if (nearest < Double.MAX_VALUE) {
                    group.addProperty("nearestBlocks", nearest);
                }
                groups.add(e.getKey(), group);
            }
        } catch (RuntimeException ex) {
            MultiverseServer.LOGGER.warn("Structure positions unavailable for {} seed {}: {}",
                    def.getName(), seed, ex.toString());
        }
        return groups;
    }

    private static void copy(com.google.gson.JsonObject from, com.google.gson.JsonObject to, String key) {
        copyAs(from, to, key, key);
    }

    private static void copyAs(com.google.gson.JsonObject from, com.google.gson.JsonObject to,
                               String key, String as) {
        com.google.gson.JsonElement v = from.get(key);
        if (v != null && !v.isJsonNull()) {
            to.add(as, v);
        }
    }

    // ------------------------------------------------------------------ json

    /** The whole bank as JSON — the same view the page is built from. */
    public static String json(MinecraftServer server) {
        List<DimensionView> views = all(server);
        StringBuilder b = new StringBuilder("{\n \"kind\": \"seed-bank\",\n \"dimensions\": [");
        for (int i = 0; i < views.size(); i++) {
            DimensionView v = views.get(i);
            b.append(i > 0 ? ",\n  " : "\n  ");
            b.append("{\"dimension\": ").append(Json.quote(v.id().toString()));
            b.append(", \"slug\": ").append(Json.quote(v.slug()));
            b.append(", \"inputHash\": ").append(Json.quote(v.inputHash()));
            b.append(", \"rollable\": ").append(v.rollable());
            b.append(", \"rejected\": ").append(v.rejected());
            b.append(", \"candidates\": [");
            for (int j = 0; j < v.candidates().size(); j++) {
                CandidateView c = v.candidates().get(j);
                b.append(j > 0 ? ", " : "");
                b.append("{\"seed\": ").append(c.seed());
                b.append(", \"percentage\": ")
                        .append(c.percentage() == null ? "null" : Json.number(c.percentage()));
                b.append(", \"achieved\": ").append(Json.number(c.achieved()));
                b.append(", \"ceiling\": ").append(Json.number(c.ceiling()));
                b.append(", \"verdict\": ").append(Json.quote(c.verdict()));
                b.append(", \"onFrontier\": ").append(v.frontierSeeds().contains(c.seed()));
                b.append(", \"lowres\": ").append(c.hasLowres());
                b.append(", \"highres\": ").append(c.hasHighres());
                b.append("}");
            }
            b.append("]}");
        }
        b.append(views.isEmpty() ? "]\n}\n" : "\n ]\n}\n");
        return b.toString();
    }
}
