package com.customdimensions.web;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.Artefacts;
import com.customdimensions.command.InputHash;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.facts.Json;
import com.customdimensions.roll.CandidateRender;
import com.customdimensions.roll.Roller;
import com.customdimensions.roll.SeedBank;
import com.customdimensions.roll.Shortlist;
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
import java.util.Set;

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

    /**
     * One dimension and the seeds it shows: its named ones (see
     * {@link SeedRoster}) followed by the top of its board.
     *
     * <p>{@code candidates} is the roster, not the leaderboard — it can carry
     * a seed with no bank entry at all, because the seed a dimension is
     * configured with need never have been rolled.
     */
    public record DimensionView(String slug, Identifier id, DimensionConfig config, String inputHash,
                                boolean rollable, List<CandidateView> candidates, int rejected,
                                List<Long> frontierSeeds, Long currentSeed, Long startingSeed,
                                int banked, boolean picked) {
    }

    /** One card, with the scorecard's own per-criterion entries and the role it is shown for. */
    public record CandidateView(long seed, double achieved, double ceiling, Double percentage,
                                String verdict, Scorecard scorecard, List<String> strengths,
                                boolean hasLowres, boolean hasHighres, SeedRoster.Role role,
                                boolean shortlisted, boolean banked) {
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
     * Every world the roller and the viewer both work over: reserved
     * dimensions first, since they are the ones a player actually lives in,
     * then the custom dimensions.
     */
    public static List<DimensionConfig> rollTargets() {
        List<DimensionConfig> out = new ArrayList<>(MultiverseConfig.getInstance().getAllDimensions().stream()
                .filter(DimensionConfig::isReserved).toList());
        out.addAll(MultiverseConfig.getInstance().getCustomDimensions());
        return out;
    }

    /**
     * A configured world by its slug, reserved dimensions included. Every
     * caller that takes a slug off a URL or a button needs this rather than
     * {@code getDimension}, which answers null for a reserved dimension by
     * design.
     */
    public static DimensionConfig resolve(String slug) {
        DimensionConfig def = MultiverseConfig.getInstance().getCustomDimension(slug);
        return def != null ? def : MultiverseConfig.getInstance().getReservedDimensionBySlug(slug);
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
        Map<Long, SeedBank.CandidateSummary> summaries = new LinkedHashMap<>();
        List<Long> ranked = new ArrayList<>();
        for (SeedBank.CandidateSummary s : SeedBank.leaderboard(inputHash, dimension)) {
            summaries.putIfAbsent(s.seed(), s);
            ranked.add(s.seed());
        }

        Set<Long> shortlisted = Shortlist.of(dimension);
        Long starting = def.getSeed();
        Long picked = overlaySeed(def);
        Long current = picked != null ? picked : starting;
        List<CandidateView> candidates = new ArrayList<>();
        for (SeedRoster.Slot slot : SeedRoster.of(current, starting, ranked, shortlisted)) {
            long seed = slot.seed();
            SeedBank.CandidateSummary s = summaries.get(seed);
            // A named seed is measured whatever its verdict, and a scorecard
            // that did not reach SCORED carries no percentage — so it is
            // banked, with reasons, and still absent from the leaderboard the
            // summaries come from. Read its numbers off the scorecard rather
            // than reporting a measured seed as unrolled.
            Scorecard card = bySeed.get(seed);
            // Boxed deliberately. CandidateSummary.percentage() is a primitive
            // double and Scorecard.percentage() is a nullable Double; a
            // conditional mixing the two is promoted to double and unboxes the
            // null, which is an NPE on every seed that did not reach SCORED —
            // exactly the named seeds this branch exists for.
            Double percentage = s != null ? Double.valueOf(s.percentage())
                    : card != null ? card.percentage() : null;
            candidates.add(new CandidateView(seed,
                    s != null ? s.achieved() : card != null ? card.achieved() : 0.0,
                    s != null ? s.ceiling() : card != null ? card.ceiling() : 0.0,
                    percentage,
                    // Only a seed nothing has measured at all is UNROLLED —
                    // said plainly rather than shown as a zero, which reads as
                    // "measured, and bad".
                    s != null ? s.verdict()
                            : card != null ? card.verdict().name() : "UNROLLED",
                    card, strengths.getOrDefault(seed, List.of()),
                    Files.isRegularFile(SeedBank.candidateImagePath(inputHash, dimension, seed,
                            CandidateRender.Resolution.LOWRES)),
                    Files.isRegularFile(SeedBank.candidateImagePath(inputHash, dimension, seed,
                            CandidateRender.Resolution.HIGHRES)),
                    slot.role(), shortlisted.contains(seed), s != null || card != null));
        }
        return new DimensionView(id.getPath(), id, def, inputHash, Roller.rollable(def),
                candidates, SeedBank.rejectedSeeds(inputHash, dimension).size(), frontierSeeds,
                current, starting, ranked.size(), picked != null);
    }

    /**
     * The seed the overlay picks for this dimension, or null when it picks
     * none — which is also the answer to "has anybody chosen one yet".
     *
     * <p>Read from the overlay file rather than from {@code def}, because
     * {@link Picker} writes a pick straight into
     * {@code overlay/config/custom-dimensions/dimensions/&lt;slug&gt;.json}
     * and nothing reloads the config afterwards — the loaded
     * {@link DimensionConfig} is the world the server is actually running,
     * which is exactly what makes it the STARTING seed and not this one. The
     * two differ from the moment a pick is made until the next restart, and
     * that gap is the thing worth showing.
     */
    static Long overlaySeed(DimensionConfig def) {
        Path overlay = Artefacts.overlayDimensionsDir().resolve(def.getName() + ".json");
        if (!Files.isRegularFile(overlay)) {
            return null;
        }
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser
                    .parseString(Files.readString(overlay)).getAsJsonObject();
            if (!root.has("overrides") || !root.get("overrides").isJsonObject()) {
                return null;
            }
            com.google.gson.JsonObject overrides = root.getAsJsonObject("overrides");
            if (!overrides.has("seed") || overrides.get("seed").isJsonNull()) {
                return null;
            }
            com.google.gson.JsonElement seed = overrides.get("seed");
            // "env" is a sentinel, not a number: the overlay says "take SEED
            // from the environment", which is what the loaded config already
            // resolved, so it names no seed of its own.
            return seed.isJsonPrimitive() && seed.getAsJsonPrimitive().isNumber()
                    ? seed.getAsLong() : null;
        } catch (IOException | RuntimeException e) {
            MultiverseServer.LOGGER.debug("Overlay seed unreadable for {}: {}",
                    def.getName(), e.toString());
            return null;
        }
    }

    /** What the config says right now: the overlay's pick, else the loaded config's own seed. */
    static Long currentSeed(DimensionConfig def, Long fallback) {
        Long picked = overlaySeed(def);
        return picked != null ? picked : fallback;
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
     * One candidate's structure and biome census, read back out of the file
     * the roll wrote — counts per structure, the distance to the nearest of
     * each, where spawn landed, and the biome shares and sampled grid.
     *
     * <p>Structure positions are recomputed rather than read: the bank stores
     * counts and distances, never a coordinate per placement, and the sites
     * are derivable from the seed for a fraction of what measuring them cost.
     * Biome shares and the sampled grid are copied straight out of the file
     * instead — the grid pass that produced them already ran once for the
     * measurement, so recomputing it here would only pay that cost twice.
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
            com.google.gson.JsonElement biomes = facts.get("biomes");
            if (biomes != null && biomes.isJsonObject()) {
                com.google.gson.JsonObject biomesOut = new com.google.gson.JsonObject();
                copy(biomes.getAsJsonObject(), biomesOut, "shares");
                out.add("biomes", biomesOut);
            }
            com.google.gson.JsonElement grid = facts.get("grid");
            if (grid != null && grid.isJsonObject()) {
                com.google.gson.JsonObject g = grid.getAsJsonObject();
                com.google.gson.JsonObject gridOut = new com.google.gson.JsonObject();
                copy(g, gridOut, "side");
                copy(g, gridOut, "biomeIds");
                copy(g, gridOut, "biome");
                out.add("grid", gridOut);
            }
            copy(facts, out, "playableRadius");
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
            b.append(", \"banked\": ").append(v.banked());
            b.append(", \"picked\": ").append(v.picked());
            b.append(", \"currentSeed\": ")
                    .append(v.currentSeed() == null ? "null" : v.currentSeed());
            b.append(", \"startingSeed\": ")
                    .append(v.startingSeed() == null ? "null" : v.startingSeed());
            b.append(", \"candidates\": [");
            for (int j = 0; j < v.candidates().size(); j++) {
                CandidateView c = v.candidates().get(j);
                b.append(j > 0 ? ", " : "");
                b.append("{\"seed\": ").append(c.seed());
                b.append(", \"role\": ").append(Json.quote(c.role().id()));
                b.append(", \"shortlisted\": ").append(c.shortlisted());
                b.append(", \"banked\": ").append(c.banked());
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
