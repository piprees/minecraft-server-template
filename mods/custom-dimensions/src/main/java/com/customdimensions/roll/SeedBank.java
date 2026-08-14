package com.customdimensions.roll;

import com.customdimensions.command.Artefacts;
import com.customdimensions.facts.Json;
import com.customdimensions.facts.SeedFacts;
import com.customdimensions.score.Criterion;
import com.customdimensions.score.Scorecard;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Every candidate seed rolled for one dimension, on disk one file per seed at
 * {@code .seed-rolling/candidates/{inputHash}/{dimension}/{seed}.json}.
 *
 * <p>One file per candidate, not one file per dimension: adding a candidate
 * never rewrites another, and a partial roll (killed mid-run, or crashed on
 * seed 8 of 20) leaves every seed measured so far as a valid, readable file.
 * The leaderboard is never stored — it is derived by reading the directory,
 * so there is nothing to keep in sync with what is actually on disk.
 *
 * <p>Each candidate file carries the FULL measured {@link SeedFacts} record
 * and the derived {@link Scorecard}, not just the ranking numbers: a reader
 * must be able to answer any question about that seed from this one file,
 * and a future scoring change can re-score from disk with no re-measurement.
 *
 * <p>A rejected seed carries no facts worth keeping (it failed a gate before
 * scoring), so all of a dimension's rejected seeds share one small
 * {@code rejected.json} rather than one file each — remembered forever so a
 * future roll never re-measures one.
 *
 * <p>Directories are namespaced by {@code inputHash} ({@link
 * com.customdimensions.command.InputHash}), not {@code stackVersion}: a hash
 * identifies the exact config, mod set and mod bytes that produced a
 * measurement, so a bank under one hash is safe to reuse for as long as it
 * exists on disk, and a bank whose inputs changed simply lives at a
 * different path — nothing to compare, nothing to discard.
 */
public final class SeedBank {

    private SeedBank() {
    }

    /** One scored candidate's ranking fields — enough to build a leaderboard without re-reading its facts. */
    public record CandidateSummary(long seed, double achieved, double ceiling,
                                   double percentage, String verdict) {
    }

    // -------------------------------------------------------------------- paths

    /** One dimension's candidate directory under its exact-input-hash namespace. */
    public static Path dimensionDir(String inputHash, String dimension) {
        return dimensionDirUnder(Artefacts.rollingDir(), inputHash, dimension);
    }

    /**
     * The {@code candidates/{inputHash}/{dimension}/} suffix under any root.
     * Pure — touches no Fabric API — so the sub-path shape (namespaced by
     * hash, colons sanitised) is pinned with no server; {@link #dimensionDir}
     * only supplies the live root.
     */
    static Path dimensionDirUnder(Path rollingRoot, String inputHash, String dimension) {
        return rollingRoot
                .resolve("candidates")
                .resolve(inputHash)
                .resolve(dimension.replace(":", "__"));
    }

    /**
     * One candidate's own file. The name carries only the seed, so a future
     * {@code {seed}.lowres.png} / {@code {seed}.highres.png} render can sit
     * beside it with no migration.
     */
    public static Path candidatePath(String inputHash, String dimension, long seed) {
        return dimensionDir(inputHash, dimension).resolve(seed + ".json");
    }

    /** The render beside a candidate's own file — {@code {seed}.lowres.png} or {@code {seed}.highres.png}. */
    public static Path candidateImagePath(String inputHash, String dimension, long seed,
                                          CandidateRender.Resolution resolution) {
        String suffix = resolution == CandidateRender.Resolution.LOWRES ? "lowres" : "highres";
        return dimensionDir(inputHash, dimension).resolve(seed + "." + suffix + ".png");
    }

    /** Every seed this dimension has rejected, so none is ever re-measured. */
    public static Path rejectedPath(String inputHash, String dimension) {
        return dimensionDir(inputHash, dimension).resolve("rejected.json");
    }

    /**
     * Where a frontier file WOULD live. Nothing writes one any more — the
     * viewer derives the non-dominated set live with {@code Frontier.of} on
     * every page render, so a file on disk could only ever be staler than
     * what is on screen. Kept because older banks carry these and the
     * candidate scan has to know to skip them.
     */
    public static Path frontierPath(String inputHash, String dimension) {
        return dimensionDir(inputHash, dimension).resolve("frontier.json");
    }

    // -------------------------------------------------------------------- writes

    /**
     * Persists one SCORED seed as its own file: the full measured facts and
     * the derived scorecard, plus {@code inputHash} — the exact config and
     * mod set that produced this measurement ({@link com.customdimensions.command.InputHash}),
     * which is also what the file's directory is keyed on. The ranking
     * fields are duplicated at the top level so {@link #leaderboard} can scan
     * without parsing the nested facts.
     */
    public static void writeCandidate(String dimension, long seed, SeedFacts facts,
                                      Scorecard card, String inputHash) throws IOException {
        String body = candidateJson(dimension, seed, facts, card, inputHash,
                Artefacts.stackVersion(), Instant.now().toString());
        Artefacts.write(candidatePath(inputHash, dimension, seed), body);
    }

    /**
     * The candidate file's body. Pure — touches no Fabric API — so the shape
     * is pinned against hand-built facts/scorecards with no server.
     */
    static String candidateJson(String dimension, long seed, SeedFacts facts, Scorecard card,
                                String inputHash, String stackVersion, String generatedAt) {
        Double pct = card.percentage();
        StringBuilder b = new StringBuilder("{\n \"kind\": \"seed-candidate\",\n");
        b.append(" \"generatedAt\": ").append(Json.quote(generatedAt)).append(",\n");
        b.append(" \"stackVersion\": ").append(Json.quote(stackVersion)).append(",\n");
        b.append(" \"dimension\": ").append(Json.quote(dimension)).append(",\n");
        b.append(" \"seed\": ").append(seed).append(",\n");
        b.append(" \"inputHash\": ").append(Json.quote(inputHash)).append(",\n");
        b.append(" \"achieved\": ").append(Json.number(card.achieved())).append(",\n");
        b.append(" \"ceiling\": ").append(Json.number(card.ceiling())).append(",\n");
        b.append(" \"percentage\": ").append(pct == null ? "null" : Json.number(pct)).append(",\n");
        b.append(" \"verdict\": ").append(Json.quote(card.verdict().name())).append(",\n");
        b.append(" \"facts\": ").append(facts.toJson()).append(",\n");
        b.append(" \"scorecard\": ").append(card.toJson());
        b.append("}\n");
        return b.toString();
    }

    /**
     * Adds one seed to the rejected set with the reason it was rejected,
     * rewriting the file atomically. A seed already there is a no-op.
     *
     * <p>The reason is the whole value of the record. A bank of bare seed
     * numbers cannot be asked which gate did it, so the only route to that
     * answer was re-measuring the seed by hand through
     * {@code customdim score} — one seed at a time, against a bank of
     * thousands.
     */
    public static void appendRejected(String inputHash, String dimension,
                                      long seed, String reason) throws IOException {
        Map<Long, String> current = rejectedSeedReasons(inputHash, dimension);
        if (current.putIfAbsent(seed, reason == null || reason.isBlank()
                ? "rejected with no reason recorded" : reason) != null) {
            return;
        }
        String body = rejectedJson(dimension, current, Artefacts.stackVersion(), Instant.now().toString());
        Artefacts.write(rejectedPath(inputHash, dimension), body);
    }

    /** The rejected-seeds file's body. Pure, for the same reason {@link #candidateJson} is. */
    static String rejectedJson(String dimension, Map<Long, String> seeds,
                               String stackVersion, String generatedAt) {
        StringBuilder b = new StringBuilder("{\n \"kind\": \"seed-rejected\",\n");
        b.append(" \"generatedAt\": ").append(Json.quote(generatedAt)).append(",\n");
        b.append(" \"stackVersion\": ").append(Json.quote(stackVersion)).append(",\n");
        b.append(" \"dimension\": ").append(Json.quote(dimension)).append(",\n");
        b.append(" \"seeds\": {");
        int i = 0;
        for (Map.Entry<Long, String> e : seeds.entrySet()) {
            b.append(i++ > 0 ? ",\n  " : "\n  ")
                    .append(Json.quote(String.valueOf(e.getKey())))
                    .append(": ").append(Json.quote(e.getValue()));
        }
        b.append(seeds.isEmpty() ? "}\n}\n" : "\n }\n}\n");
        return b.toString();
    }

    // -------------------------------------------------------------------- reads

    /** Every scored candidate for a dimension, ranked highest percentage first. */
    public static List<CandidateSummary> leaderboard(String inputHash, String dimension) {
        return rank(readCandidateBodies(dimensionDir(inputHash, dimension)));
    }

    /**
     * Every candidate's full {@link Scorecard} — the input {@link Frontier}
     * needs, since a percentage alone cannot say which criteria a candidate
     * is distinctively strong on. A file the scorecard cannot be parsed back
     * out of is skipped, the same tolerance {@link #rank} gives a corrupt
     * candidate.
     */
    public static List<Scorecard> scorecards(String inputHash, String dimension) {
        List<Scorecard> out = new ArrayList<>();
        for (String body : readCandidateBodies(dimensionDir(inputHash, dimension))) {
            Scorecard card = parseScorecard(body);
            if (card != null) {
                out.add(card);
            }
        }
        return out;
    }

    /** Every candidate file's raw body — shared by {@link #leaderboard} and {@link #scorecards}. */
    private static List<String> readCandidateBodies(Path dir) {
        List<String> bodies = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return bodies;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".json") || name.equals("rejected.json") || name.equals("frontier.json")) {
                    continue;
                }
                try {
                    bodies.add(Files.readString(p));
                } catch (IOException ignored) {
                    // A file that vanished between the listing and the read is
                    // absent, not an error — the scan continues.
                }
            }
        } catch (IOException ignored) {
            // The directory vanished mid-scan: reads the same as no candidates.
        }
        return bodies;
    }

    /**
     * Parses every candidate body, skips a half-written or corrupt one rather
     * than failing the whole scan (atomic writes mean this should only ever
     * be a foreign file), and sorts by percentage descending. Pure, so the
     * sort and the skip-on-corruption behaviour are pinned with no
     * filesystem.
     */
    static List<CandidateSummary> rank(List<String> candidateBodies) {
        List<CandidateSummary> out = new ArrayList<>();
        for (String body : candidateBodies) {
            CandidateSummary s = parseSummary(body);
            if (s != null) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingDouble(CandidateSummary::percentage).reversed());
        return out;
    }

    /** One candidate file's ranking fields, or null when it is unusable (unmeasurable seed, corrupt file). */
    static CandidateSummary parseSummary(String candidateBody) {
        try {
            JsonObject root = JsonParser.parseString(candidateBody).getAsJsonObject();
            if (!root.has("percentage") || root.get("percentage").isJsonNull()) {
                return null;
            }
            return new CandidateSummary(
                    root.get("seed").getAsLong(),
                    root.get("achieved").getAsDouble(),
                    root.get("ceiling").getAsDouble(),
                    root.get("percentage").getAsDouble(),
                    root.get("verdict").getAsString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A candidate file's full {@code scorecard} object, reconstructed as a
     * real {@link Scorecard} — the inverse of {@link Scorecard#toJson}, which
     * nests entries under their group name rather than keeping a flat list.
     * Null on anything unparseable, the same tolerance {@link #parseSummary}
     * gives a corrupt file.
     */
    static Scorecard parseScorecard(String candidateBody) {
        try {
            JsonObject root = JsonParser.parseString(candidateBody).getAsJsonObject();
            JsonObject sc = root.getAsJsonObject("scorecard");
            String dimension = root.get("dimension").getAsString();
            long seed = root.get("seed").getAsLong();
            Scorecard.Verdict verdict = Scorecard.Verdict.valueOf(sc.get("verdict").getAsString());
            String verdictReason = sc.get("verdictReason").getAsString();
            double achieved = sc.get("achieved").getAsDouble();
            double ceiling = sc.get("ceiling").getAsDouble();

            List<Scorecard.Entry> entries = new ArrayList<>();
            if (sc.has("groups")) {
                for (var groupEntry : sc.getAsJsonObject("groups").entrySet()) {
                    Criterion.Group group = Criterion.Group.valueOf(
                            groupEntry.getKey().toUpperCase(Locale.ROOT));
                    for (JsonElement el : groupEntry.getValue().getAsJsonArray()) {
                        JsonObject e = el.getAsJsonObject();
                        Double value = e.get("value").isJsonNull() ? null : e.get("value").getAsDouble();
                        double[] band = null;
                        if (e.has("band") && e.get("band").isJsonArray()
                                && e.getAsJsonArray("band").size() == 2) {
                            band = new double[]{e.getAsJsonArray("band").get(0).getAsDouble(),
                                    e.getAsJsonArray("band").get(1).getAsDouble()};
                        }
                        entries.add(new Scorecard.Entry(e.get("id").getAsString(), group,
                                e.get("target").getAsString(), e.get("outcome").getAsString(),
                                value, e.get("detail").getAsString(), band));
                    }
                }
            }
            // The tier tallies are read back rather than recomputed: the
            // headline on a banked card is the mean of them, so dropping them
            // would silently re-derive a different number from the same file.
            java.util.Map<Criterion.Tier, Scorecard.Tally> tiers =
                    new java.util.EnumMap<>(Criterion.Tier.class);
            if (sc.has("tiers") && sc.get("tiers").isJsonObject()) {
                for (var t : sc.getAsJsonObject("tiers").entrySet()) {
                    JsonObject v = t.getValue().getAsJsonObject();
                    tiers.put(Criterion.Tier.valueOf(t.getKey().toUpperCase(Locale.ROOT)),
                            new Scorecard.Tally(v.get("achieved").getAsDouble(),
                                    v.get("ceiling").getAsDouble()));
                }
            }
            return new Scorecard(dimension, seed, verdict, verdictReason, achieved, ceiling,
                    entries, tiers);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Every seed already rejected for this dimension. */
    public static Set<Long> rejectedSeeds(String inputHash, String dimension) {
        return new LinkedHashSet<>(rejectedSeedReasons(inputHash, dimension).keySet());
    }

    /** Every rejected seed for a dimension and the gate that rejected it. */
    public static Map<Long, String> rejectedSeedReasons(String inputHash, String dimension) {
        Path p = rejectedPath(inputHash, dimension);
        if (!Files.isRegularFile(p)) {
            return new LinkedHashMap<>();
        }
        try {
            return parseRejectedSeeds(Files.readString(p));
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** The {@code seeds} map out of a rejected.json body. Pure, for the same reason {@link #rank} is. */
    static Map<Long, String> parseRejectedSeeds(String rejectedBody) {
        JsonObject root = JsonParser.parseString(rejectedBody).getAsJsonObject();
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("seeds").entrySet()) {
            out.put(Long.parseLong(e.getKey()), e.getValue().getAsString());
        }
        return out;
    }

    /** Every seed already tried for this dimension — scored or rejected — so a roll skips it. */
    public static Set<Long> alreadyTriedSeeds(String inputHash, String dimension) {
        Set<Long> out = new LinkedHashSet<>(rejectedSeeds(inputHash, dimension));
        for (CandidateSummary c : leaderboard(inputHash, dimension)) {
            out.add(c.seed());
        }
        return out;
    }
}
