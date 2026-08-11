package com.customdimensions.web;

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

        List<CandidateView> candidates = new ArrayList<>();
        for (SeedBank.CandidateSummary s : SeedBank.leaderboard(inputHash, dimension)) {
            Scorecard card = cards.stream().filter(c -> c.seed() == s.seed()).findFirst().orElse(null);
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
