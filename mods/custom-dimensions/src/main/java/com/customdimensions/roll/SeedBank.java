package com.customdimensions.roll;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.Artefacts;
import com.customdimensions.facts.Json;
import com.customdimensions.facts.SeedFacts;
import com.customdimensions.facts.SeedFactsCodec;
import com.customdimensions.score.Criterion;
import com.customdimensions.score.Scorecard;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    // ------------------------------------------------------------------ cache

    /**
     * The derived views of a dimension's directory, held in memory.
     *
     * <p>Deriving the leaderboard means reading and parsing every candidate
     * file, and a roll reads it once per dimension via {@link
     * #alreadyTriedSeeds} to skip already-tried seeds, again to count what is
     * banked, and again to cull — while the viewer asks for it (and the
     * scorecards) once per dimension on every page poll. At a few hundred
     * candidates of 30 KB each that is megabytes of re-read and re-parse for a
     * search whose measurement is the only part worth spending time on, and
     * it grows with the bank: the roll of a low-yielding dimension slows down
     * the longer it runs.
     *
     * <p>This process is the only writer, so the cache is updated in place by
     * {@link #writeCandidate} and {@link #appendRejected} rather than dropped
     * — invalidating on write would re-read the whole directory on the next
     * seed, which is every seed. A bank deleted underneath a running server
     * is the one case this cannot see; restart the server after wiping
     * {@code .seed-rolling/}.
     */
    private static final Map<String, List<CandidateSummary>> SUMMARIES = new ConcurrentHashMap<>();

    /**
     * Full scorecards, softly held: a scorecard carries every criterion entry,
     * so a pack-wide bank of them is orders of magnitude larger than the
     * summaries and is only ever read by the viewer. The GC may take these
     * back under pressure; the next read re-derives them from disk.
     */
    private static final Map<String, SoftReference<List<Scorecard>>> SCORECARDS =
            new ConcurrentHashMap<>();

    private static final Map<String, Map<Long, String>> REJECTED = new ConcurrentHashMap<>();

    private static String cacheKey(String inputHash, String dimension) {
        return inputHash + '/' + dimension;
    }

    /** Forgets a dimension's cached views, for a bank changed by something other than this class. */
    public static void forget(String inputHash, String dimension) {
        String key = cacheKey(inputHash, dimension);
        SUMMARIES.remove(key);
        SCORECARDS.remove(key);
        REJECTED.remove(key);
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

    /** Every seed a tier-1 screen scored, and what it scored — one file per dimension. */
    public static Path screenedPath(String inputHash, String dimension) {
        return dimensionDir(inputHash, dimension).resolve("screened.json");
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
        remember(inputHash, dimension, seed, card);
    }

    /**
     * Folds one just-written candidate into the cached views, so the next
     * read does not go back to disk for a directory this process just changed.
     * Only an already-populated entry is updated: an absent one is derived on
     * demand, and would otherwise be built here from a single candidate and
     * mistaken for the whole bank.
     */
    private static void remember(String inputHash, String dimension, long seed, Scorecard card) {
        String key = cacheKey(inputHash, dimension);
        Double percentage = card.percentage();
        if (percentage == null) {
            // Unmeasurable: rank() drops it, so the cached views must not gain
            // an entry the disk scan would never produce.
            return;
        }
        CandidateSummary fresh = new CandidateSummary(seed, card.achieved(), card.ceiling(),
                percentage, card.verdict().name());
        SUMMARIES.computeIfPresent(key, (k, existing) -> {
            List<CandidateSummary> merged = new ArrayList<>(existing.size() + 1);
            for (CandidateSummary c : existing) {
                if (c.seed() != seed) {
                    merged.add(c);
                }
            }
            merged.add(fresh);
            merged.sort(Comparator.comparingDouble(CandidateSummary::percentage).reversed());
            return List.copyOf(merged);
        });
        SCORECARDS.computeIfPresent(key, (k, ref) -> {
            List<Scorecard> existing = ref.get();
            if (existing == null) {
                return null;
            }
            List<Scorecard> merged = new ArrayList<>(existing.size() + 1);
            for (Scorecard c : existing) {
                if (c.seed() != seed) {
                    merged.add(c);
                }
            }
            merged.add(card);
            return new SoftReference<>(List.copyOf(merged));
        });
    }

    /**
     * Seeds a bank should delete, given its leaderboard (best first, as
     * {@link #leaderboard} returns it) and what must survive regardless of
     * rank. Pure, so the cut line is pinned with no filesystem, the same
     * tolerance {@link #rank} is given.
     */
    static List<Long> cullable(List<CandidateSummary> ranked, int keep, Set<Long> protectedSeeds) {
        List<Long> out = new ArrayList<>();
        for (int i = keep; i < ranked.size(); i++) {
            long seed = ranked.get(i).seed();
            if (!protectedSeeds.contains(seed)) {
                out.add(seed);
            }
        }
        return out;
    }

    /**
     * Deletes every candidate {@link #cullable} names, and its renders, so a
     * roll never leaves more than {@code keep} on disk. Reads the leaderboard
     * AFTER this roll's new candidates are already written, so a new seed
     * that outranks an old one culls the old one, never itself. Never
     * throws: a cull that cannot delete a file must not take the roll down
     * with it.
     */
    public static void cullToTop(String inputHash, String dimension, int keep,
                                 Set<Long> protectedSeeds) {
        List<Long> doomed = cullable(leaderboard(inputHash, dimension), keep, protectedSeeds);
        boolean changed = false;
        for (long seed : doomed) {
            try {
                Files.deleteIfExists(candidatePath(inputHash, dimension, seed));
                Files.deleteIfExists(candidateImagePath(inputHash, dimension, seed,
                        CandidateRender.Resolution.LOWRES));
                Files.deleteIfExists(candidateImagePath(inputHash, dimension, seed,
                        CandidateRender.Resolution.HIGHRES));
                changed = true;
            } catch (IOException e) {
                MultiverseServer.LOGGER.warn("Could not cull candidate {} for {}: {}",
                        seed, dimension, e.toString());
            }
        }
        if (changed) {
            forget(inputHash, dimension);
        }
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
        REJECTED.put(cacheKey(inputHash, dimension), Collections.unmodifiableMap(current));
    }

    /** The rejected-seeds file's body. Pure, for the same reason {@link #candidateJson} is. */
    /**
     * Records what a tier-1 screen saw, written ONCE at the end of the screen
     * rather than per seed — a pool is thousands of seeds and
     * {@link #appendRejected}'s rewrite-the-file shape would be quadratic over
     * it.
     *
     * <p>The rank a seed was given is the whole point: it is the only record of
     * how the shortlist was chosen, and comparing it against the final score
     * the survivors earn is what says whether the screen ranks anything. It
     * also carries the pool's score distribution, which is what sizing the pool
     * has to be argued from.
     */
    public static void writeScreened(String inputHash, String dimension,
                                     Map<Long, Double> scores) throws IOException {
        Artefacts.write(screenedPath(inputHash, dimension),
                screenedJson(dimension, scores, Artefacts.stackVersion(),
                        Instant.now().toString()));
    }

    /**
     * How many seeds the dimension's most recent tier-1 screen measured, from
     * {@code screened.json} — overwritten each screen, so this is the last
     * pass's size, not a lifetime total. Zero when the dimension has never
     * been screened, or the file is unreadable.
     */
    public static int screenedCount(String inputHash, String dimension) {
        Path p = screenedPath(inputHash, dimension);
        if (!Files.isRegularFile(p)) {
            return 0;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            return root.has("screened") ? root.get("screened").getAsInt() : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    static String screenedJson(String dimension, Map<Long, Double> scores,
                               String stackVersion, String generatedAt) {
        StringBuilder b = new StringBuilder("{\n \"kind\": \"seed-screened\",\n");
        b.append(" \"generatedAt\": ").append(Json.quote(generatedAt)).append(",\n");
        b.append(" \"stackVersion\": ").append(Json.quote(stackVersion)).append(",\n");
        b.append(" \"dimension\": ").append(Json.quote(dimension)).append(",\n");
        b.append(" \"screened\": ").append(scores.size()).append(",\n");
        b.append(" \"scores\": {");
        int i = 0;
        for (Map.Entry<Long, Double> e : scores.entrySet()) {
            b.append(i++ > 0 ? ",\n  " : "\n  ")
                    .append(Json.quote(String.valueOf(e.getKey())))
                    .append(": ").append(Json.number(e.getValue()));
        }
        b.append(scores.isEmpty() ? "}\n}\n" : "\n }\n}\n");
        return b.toString();
    }

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
        return SUMMARIES.computeIfAbsent(cacheKey(inputHash, dimension),
                k -> List.copyOf(rank(readCandidateBodies(dimensionDir(inputHash, dimension)))));
    }

    /**
     * Every candidate's full {@link Scorecard} — the input {@link Frontier}
     * needs, since a percentage alone cannot say which criteria a candidate
     * is distinctively strong on. A file the scorecard cannot be parsed back
     * out of is skipped, the same tolerance {@link #rank} gives a corrupt
     * candidate.
     */
    public static List<Scorecard> scorecards(String inputHash, String dimension) {
        String key = cacheKey(inputHash, dimension);
        SoftReference<List<Scorecard>> held = SCORECARDS.get(key);
        List<Scorecard> cached = held == null ? null : held.get();
        if (cached != null) {
            return cached;
        }
        List<Scorecard> out = new ArrayList<>();
        for (String body : readCandidateBodies(dimensionDir(inputHash, dimension))) {
            Scorecard card = parseScorecard(body);
            if (card != null) {
                out.add(card);
            }
        }
        List<Scorecard> fixed = List.copyOf(out);
        SCORECARDS.put(key, new SoftReference<>(fixed));
        return fixed;
    }

    /**
     * One candidate's full {@link SeedFacts}, read back out of its file via
     * {@link SeedFactsCodec} — the spawn column it measured, and everything
     * else. Null when the file is missing or unparseable, the same
     * tolerance {@link #parseSummary} gives a corrupt candidate.
     */
    public static SeedFacts candidateFacts(String inputHash, String dimension, long seed) {
        Path p = candidatePath(inputHash, dimension, seed);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (!root.has("facts") || !root.get("facts").isJsonObject()) {
                return null;
            }
            return SeedFactsCodec.read(root.getAsJsonObject("facts").toString());
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
                if (!name.endsWith(".json") || name.equals("rejected.json") || name.equals("frontier.json")
                        || name.equals("screened.json")) {
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

    /**
     * Every rejected seed for a dimension and the gate that rejected it.
     *
     * <p>A fresh mutable copy each call — {@link #appendRejected} adds to what
     * it gets back, and the cached map behind it is shared.
     */
    public static Map<Long, String> rejectedSeedReasons(String inputHash, String dimension) {
        return new LinkedHashMap<>(REJECTED.computeIfAbsent(cacheKey(inputHash, dimension),
                k -> Collections.unmodifiableMap(readRejectedSeeds(inputHash, dimension))));
    }

    private static Map<Long, String> readRejectedSeeds(String inputHash, String dimension) {
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
