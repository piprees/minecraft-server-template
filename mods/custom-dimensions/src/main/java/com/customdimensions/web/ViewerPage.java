package com.customdimensions.web;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.score.Criterion;
import com.customdimensions.score.Scorecard;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The bank as one page: every dimension at once, a card per candidate.
 *
 * <p>Fills the five placeholders in {@code seed-viewer/template.html}. The
 * front-end filters, sorts and plots from {@code data-*} attributes on the
 * cards, so the attribute names here are the contract with {@code app.js} —
 * {@code dim}, {@code name}, {@code family}, {@code type}, {@code mood},
 * {@code score}, {@code seed}, {@code radius}, {@code cands}.
 *
 * <p>Every number rendered comes from a written {@link Scorecard}. Nothing
 * is estimated or defaulted here: a criterion with no measurement is shown
 * as unmeasured, never as zero.
 */
public final class ViewerPage {

    private ViewerPage() {
    }

    /**
     * A named seed's marker, inline and immediately left of the seed it
     * describes. Empty for an ordinary candidate, so the seed line of an
     * unnamed card is unchanged.
     */
    private static String roleBadge(SeedRoster.Role role) {
        if (!role.pinned()) {
            return "";
        }
        return "<span class='role-badge role-" + escape(role.id()) + "' title='"
                + escape(roleTitle(role)) + "'>" + role.badge() + "</span>";
    }

    /**
     * The colour behind this dimension's map: its configured sky, or what its
     * empty columns look down into when it declares none.
     */
    private static String skyColour(DimensionConfig def) {
        DimensionConfig.Environment env = def.getEnvironment();
        if (env != null && env.skyColor != null && !env.skyColor.isBlank()) {
            String raw = env.skyColor.trim();
            return raw.startsWith("#") ? raw : "#" + raw;
        }
        return String.format(Locale.ROOT, "#%06x",
                com.customdimensions.roll.CandidateRender.voidColourFor(def) & 0xFFFFFF);
    }

    /** What a badge means, spelled out — an emoji alone is a guess. */
    private static String roleTitle(SeedRoster.Role role) {
        switch (role) {
            case CURRENT:
                return "Current seed — what this dimension's config says right now";
            case STARTING:
                return "Starting seed — the world this server booted with";
            case BEST:
                return "Best scoring seed in the bank";
            case SHORTLISTED:
                return "Shortlisted — kept by hand, survives a re-roll";
            default:
                return "";
        }
    }

    public static String render(MinecraftServer server) throws IOException {
        List<BankView.DimensionView> views = BankView.all(server);
        boolean anyoneOnline = !server.getPlayerManager().getPlayerList().isEmpty();
        String template = template();

        Set<String> families = new LinkedHashSet<>();
        Set<String> moods = new LinkedHashSet<>();
        for (BankView.DimensionView v : views) {
            families.add(family(v.config()));
            moods.add(mood(v.config()));
        }

        // "All" is the literal the filter compares against, not an empty
        // string — app.js tests `state.family === 'All'`, so an empty value
        // matched nothing and clicking All emptied the grid.
        StringBuilder familyButtons = new StringBuilder(
                "<button class='family-btn active' data-family='All'>All</button>");
        for (String f : sorted(families)) {
            familyButtons.append("<button class='family-btn' data-family='").append(escape(f))
                    .append("'>").append(escape(f)).append("</button>");
        }
        StringBuilder moodOptions = new StringBuilder("<option value=''>All moods</option>");
        for (String m : sorted(moods)) {
            moodOptions.append("<option>").append(escape(m)).append("</option>");
        }

        StringBuilder cards = new StringBuilder();
        for (BankView.DimensionView v : views) {
            cards.append(card(v, anyoneOnline)).append('\n');
        }

        return template
                .replace("{{FAMILY_BUTTONS}}", familyButtons.toString())
                .replace("{{MOOD_OPTIONS}}", moodOptions.toString())
                .replace("{{SCORE_THRESHOLD}}", scoreThreshold())
                .replace("{{DIMENSIONS_HTML}}", cards.toString());
    }

    private static String template() throws IOException {
        try (InputStream in = ViewerPage.class.getResourceAsStream("/seed-viewer/template.html")) {
            if (in == null) {
                throw new IOException("seed-viewer/template.html missing from the jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ card

    private static String card(BankView.DimensionView v, boolean anyoneOnline) {
        DimensionConfig def = v.config();
        String slug = v.slug();
        List<BankView.CandidateView> candidates = v.candidates();
        // The compact face shows the world this dimension IS, not the best one
        // the search found: the first roster slot is the configured seed
        // whenever there is one, which is the picture a person opens the page
        // to check. The score beside it stays the best score, because that is
        // what the flag and the sort are about.
        BankView.CandidateView face = candidates.isEmpty() ? null : candidates.get(0);
        double bestScore = bestScore(candidates);
        boolean anyShortlisted = shortlistedCount(candidates) > 0;
        String panelId = "detail-" + slug;

        StringBuilder b = new StringBuilder();
        // data-dim on the card as well as its candidates: the roller's
        // dimension picker is built from every [data-dim] on the page, and a
        // dimension with nothing banked yet is exactly the one worth rolling.
        b.append("<div class='dim-card' data-dim='").append(escape(slug))
                .append("' data-name='").append(escape(slug))
                .append("' data-family='").append(escape(family(def)))
                .append("' data-type='").append(escape(type(def)))
                .append("' data-mood='").append(escape(mood(def)))
                .append("' data-flagged='").append(flagged(v, bestScore) ? 1 : 0)
                .append("' data-score='").append(fmt(bestScore))
                .append("' data-cands='").append(v.banked())
                .append("' data-screened='").append(v.screened())
                .append("' data-shortlisted='").append(anyShortlisted ? 1 : 0)
                .append("' data-pinned='").append(v.picked() ? 1 : 0)
                .append("' data-sky='").append(escape(skyColour(def)))
                .append("' data-radius='")
                .append(def.getPlayerBorderRadius())
                .append("' data-dim-scale='").append(fmt(def.getScale()))
                .append("' data-spawn-x='").append(spawnCoord(def, 0))
                .append("' data-spawn-z='").append(spawnCoord(def, 2))
                .append("'>");

        if (v.banked() == 0) {
            b.append("<div class='flag-dot red'></div>");
        } else if (bestScore < 50) {
            b.append("<div class='flag-dot amber'></div>");
        }

        // Compact face
        b.append("<div class='compact'>");
        if (face != null) {
            b.append(image(slug, face));
        } else {
            b.append("<div class='no-render'>not rolled</div>");
        }
        b.append("<div class='dim-name'>").append(escape(slug)).append("</div>");
        b.append("<div class='dim-meta'>")
                .append(face == null ? "" : roleBadge(face.role()))
                .append("<span class='dim-score' style='color:").append(scoreColour(bestScore))
                .append("'>").append(v.banked() == 0 ? "&mdash;" : fmt(bestScore)).append("</span>")
                .append("<span class='badge'>").append(escape(type(def))).append("</span>")
                .append("<span class='badge'>").append(escape(mood(def))).append("</span>")
                .append("<span>").append(v.banked()).append(" seeds</span>")
                .append("</div>");
        String blurb = description(def);
        if (!blurb.isEmpty()) {
            b.append("<div class='dim-blurb'>").append(escape(truncate(blurb, 90))).append("</div>");
        }
        b.append("<button type='button' class='compact-trigger' aria-expanded='false' aria-controls='")
                .append(escape(panelId)).append("'><span class='sr-only'>Show candidates for ")
                .append(escape(slug)).append("</span></button>");
        b.append("</div>");

        // Detail panel — a sibling of the trigger, never inside it.
        b.append("<div class='detail' id='").append(escape(panelId)).append("'>");
        b.append("<button type='button' class='close-btn' aria-label='Close details'>&times;</button>");
        b.append("<div class='detail-header'><div class='detail-info'>");
        b.append("<h2>").append(escape(slug)).append("</h2>");
        if (!blurb.isEmpty()) {
            b.append("<div class='blurb'>").append(escape(blurb)).append("</div>");
        }
        b.append("<div class='meta'>")
                .append("<span class='badge'>").append(escape(type(def))).append("</span>")
                .append("<span class='badge'>").append(escape(mood(def))).append("</span>")
                .append("<span class='badge'>").append(def.getPlayerBorderRadius() * 2).append("b border</span>");
        if (def.getStructureDensity() != null) {
            b.append("<span class='badge'>").append(escape(def.getStructureDensity())).append("</span>");
        }
        if (v.rejected() > 0) {
            b.append("<span class='badge'>").append(v.rejected()).append(" rejected</span>");
        }
        if (!v.rollable()) {
            b.append("<span class='badge'>not rollable</span>");
        }
        b.append("<span class='badge'>").append(escape(v.id().getNamespace())).append("</span>");
        if (def.getNoiseSettings() != null && !def.getNoiseSettings().isBlank()) {
            b.append("<span class='badge'>").append(escape(displayId(def.getNoiseSettings()))).append("</span>");
        }
        if (def.getScale() != 1.0) {
            b.append("<span class='badge'>").append(fmt(def.getScale())).append("x scale</span>");
        }
        DimensionConfig.SeedRoll roll = def.getSeedRoll();
        if (roll != null && roll.water != null && !roll.water.isBlank()) {
            b.append("<span class='badge'>").append(escape(roll.water)).append("</span>");
        }
        if (roll != null && roll.terrain != null && !roll.terrain.isBlank()) {
            b.append("<span class='badge'>").append(escape(roll.terrain)).append("</span>");
        }
        b.append("<span class='badge'>hash ").append(escape(shortHash(v.inputHash()))).append("</span>");
        // What the row is showing, stated where the other facts about this
        // dimension already are rather than as a footnote under the cards.
        if (!candidates.isEmpty()) {
            b.append("<span class='badge' title='Top ").append(SeedRoster.OTHERS)
                    .append(" by score, plus the current, starting, best and shortlisted seeds'>")
                    .append(candidates.size()).append(" shown of ").append(v.banked())
                    .append(" banked</span>");
        }
        b.append("</div></div></div>");

        if (candidates.isEmpty()) {
            b.append("<p class='meta'>No seeds banked under this dimension's current inputs, "
                    + "and no seed configured.</p>");
        } else {
            b.append("<div class='all-cands'>");
            for (int i = 0; i < candidates.size(); i++) {
                b.append(candidate(i, slug, candidates.get(i), v, anyoneOnline));
            }
            b.append("</div>");
        }
        b.append("</div></div>");
        return b.toString();
    }

    // ------------------------------------------------------------------ candidate

    private static String candidate(int idx, String slug, BankView.CandidateView c,
                                    BankView.DimensionView v, boolean anyoneOnline) {
        double pct = c.percentage() == null ? 0.0 : c.percentage();
        boolean onFrontier = v.frontierSeeds().contains(c.seed());
        StringBuilder b = new StringBuilder();
        b.append("<div class='cand cand-item").append(onFrontier ? " winner" : "")
                .append(c.role().pinned() ? " named" : "")
                .append("' data-idx='").append(idx)
                .append("' data-score='").append(fmt(pct))
                .append("' data-dim='").append(escape(slug))
                .append("' data-seed='").append(c.seed())
                .append("' data-role='").append(escape(c.role().id()))
                // Presence, not value: app.js filters on
                // `.cand[data-shortlisted]` and clears the attribute outright
                // when a seed is dropped, so a data-shortlisted='0' would
                // match the filter and show every candidate.
                .append('\'').append(c.shortlisted() ? " data-shortlisted='1'" : "")
                .append(" data-parts='").append(escape(partsJson(c.scorecard())))
                .append("' data-render='renders/").append(escape(slug)).append('/').append(c.seed())
                .append(".png'>");
        b.append(image(slug, c));
        if (c.hasHighres()) {
            b.append("<div class='hires-badge'>HD</div>");
        }
        b.append("<div class='cand-dim-label'>").append(escape(slug)).append("</div>");
        b.append("<div class='score' style='color:").append(scoreColour(pct)).append("'>")
                .append(c.banked() ? fmt(pct) : "&mdash;").append("</div>");
        b.append("<div class='seed'>").append(roleBadge(c.role())).append(c.seed()).append("</div>");

        // The modal's body: the score AND the reasons behind it.
        b.append("<div class='cand-detail' style='display:none'>");
        // How many blocks each render covers, stated rather than inferred:
        // the overlays project a distance in blocks onto the image, and
        // app.js used to derive this from constants describing a render
        // geometry that no longer exists, so it came back zero and every
        // overlay silently drew nothing.
        int borderBlocks = Math.max(512, v.config().getPlayerBorderRadius() * 2);
        int[] spawnAt = v.config().getSpawn();
        b.append("<div class='lb-header' data-coverage='").append(borderBlocks)
                .append("' data-coverage-low='512'")
                // Where the spawn view sits inside the whole-world frame —
                // what places the stand-in while the wide render is drawn.
                .append(" data-spawn-x='")
                .append(spawnAt != null && spawnAt.length >= 3 ? spawnAt[0] : 0)
                .append("' data-spawn-z='")
                .append(spawnAt != null && spawnAt.length >= 3 ? spawnAt[2] : 0)
                .append("'><div class='lb-title'>")
                .append("<span class='dim-label'>").append(escape(slug)).append("</span> ")
                .append("<span class='score' style='color:").append(scoreColour(pct)).append("'>")
                .append(c.banked() ? fmt(pct) : "&mdash;").append("</span> ")
                .append("<span class='seed'>").append(roleBadge(c.role())).append(c.seed())
                .append("</span></div>");
        b.append("<div class='score-parts'>").append(tiers(c.scorecard()))
                .append(fmt(c.achieved())).append(" of ")
                .append(fmt(c.ceiling())).append(" &middot; ").append(escape(c.verdict()));
        if (c.scorecard() != null && c.scorecard().verdictReason() != null
                && !c.scorecard().verdictReason().isBlank()) {
            b.append(" &middot; ").append(escape(c.scorecard().verdictReason()));
        }
        b.append("</div>");
        if (!c.strengths().isEmpty()) {
            b.append("<div class='lb-meta'>");
            for (String s : c.strengths()) {
                b.append("<span class='badge'>").append(escape(s)).append("</span>");
            }
            b.append("</div>");
        }
        b.append("</div>");
        // The panel's only scrolling part. The header names the seed and the
        // actions decide its fate — both have to stay put while the reasons
        // behind the score are read, and the structures panel appends itself
        // in here (exactfacts.js) so it scrolls with the criteria rather than
        // landing under the buttons.
        b.append("<div class='lb-scroll'>");
        b.append(criteria(c.scorecard()));
        b.append("</div>");
        // The two things a person does from here: go and look at it, then
        // choose it. Nothing between the map and the decision.
        String offline = anyoneOnline ? "" : " disabled title='Join the server first — "
                + "a try-out is a place you fly around in'";
        boolean isCurrent = v.currentSeed() != null && v.currentSeed() == c.seed();
        b.append("<div class='lb-actions'>")
                .append("<button type='button' class='action-btn tryout'").append(offline)
                .append(" data-dim='").append(escape(slug)).append("' data-seed='").append(c.seed())
                .append("'>Try it out</button>")
                .append("<button type='button' class='action-btn tryout-back'").append(offline)
                .append(">Back to spawn</button>")
                .append("<button type='button' class='action-btn shortlist' data-dim='")
                .append(escape(slug)).append("' data-seed='").append(c.seed())
                .append("'>").append(c.shortlisted() ? "Remove from shortlist" : "Shortlist")
                .append("</button>")
                .append("<button type='button' class='pick'")
                .append(isCurrent ? " disabled title='Already the current seed'" : "")
                .append(" data-dim='").append(escape(slug))
                .append("' data-seed='").append(c.seed())
                .append("'>Use this seed</button>")
                .append("</div>");
        b.append("</div>");

        b.append("</div>");
        return b.toString();
    }

    /**
     * The two tiers, each out of its own ceiling.
     *
     * <p>The headline is their mean, so showing only the headline hides which
     * of the two questions the seed answered — a 70 built from a perfect
     * world that ignores its config reads identically to one built from a
     * dull world that obeys it, and they call for opposite actions.
     */
    private static String tiers(Scorecard card) {
        if (card == null) {
            return "";
        }
        Double intent = card.tierPercentage(Criterion.Tier.CONFIGURED);
        Double quality = card.tierPercentage(Criterion.Tier.GENERAL);
        if (intent == null && quality == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        if (intent != null) {
            b.append("<span class='tier' data-tier='configured' style='color:")
                    .append(scoreColour(intent)).append("'>intent ")
                    .append(fmt(intent)).append("</span> ");
        }
        if (quality != null) {
            b.append("<span class='tier' data-tier='general' style='color:")
                    .append(scoreColour(quality)).append("'>quality ")
                    .append(fmt(quality)).append("</span> ");
        }
        return b.append("&middot; ").toString();
    }

    /**
     * The scorecard's own entries, grouped as it grouped them. An entry with
     * no value is shown as unmeasured rather than as a zero — an unmeasured
     * criterion is excluded from a score, and the page must not imply it
     * scored badly.
     */
    private static String criteria(Scorecard card) {
        if (card == null || card.entries().isEmpty()) {
            return "<div class='criteria meta'>No scorecard on file for this candidate.</div>";
        }
        Map<Criterion.Group, List<Scorecard.Entry>> grouped = new LinkedHashMap<>();
        for (Scorecard.Entry e : card.entries()) {
            grouped.computeIfAbsent(e.group(), k -> new ArrayList<>()).add(e);
        }
        StringBuilder b = new StringBuilder("<div class='criteria-groups'>");
        for (Map.Entry<Criterion.Group, List<Scorecard.Entry>> g : grouped.entrySet()) {
            b.append("<div class='crit-group'><div class='crit-label'>")
                    .append(escape(g.getKey().name().toLowerCase(Locale.ROOT))).append("</div>");
            for (Scorecard.Entry e : g.getValue()) {
                // .mrow[data-band] is dartboard.js's contract: a range in
                // BLOCKS from spawn, which it draws as an arc. Only a
                // criterion whose question IS a distance carries one — a
                // biome share is a fraction and has no radius, and inventing
                // one would draw a ring that means nothing.
                double[] band = e.band();
                b.append("<div class='crit-row")
                        .append(band != null ? " mrow" : "")
                        .append(severityClass(e))
                        .append("' data-outcome='")
                        .append(escape(e.outcome().toLowerCase(Locale.ROOT))).append("'");
                if (band != null) {
                    b.append(" data-band='").append(fmt(band[0])).append(',')
                            .append(fmt(band[1])).append("'");
                }
                b.append(">")
                        .append("<span class='crit-value'>")
                        .append(e.value() == null ? "&mdash;" : fmtScore(e.value()))
                        .append("</span>")
                        .append("<span class='crit-id'>").append(escape(e.id())).append("</span>");
                String detail = e.detail() == null ? "" : e.detail().trim();
                String want = e.target() == null ? "" : e.target().trim();
                if (!detail.isEmpty() || !want.isEmpty()) {
                    b.append("<span class='crit-detail'>");
                    if (!want.isEmpty()) {
                        b.append("want ").append(escape(want));
                    }
                    if (!detail.isEmpty()) {
                        b.append(want.isEmpty() ? "" : " &mdash; ").append(escape(detail));
                    }
                    b.append("</span>");
                }
                b.append("</div>");
            }
            b.append("</div>");
        }
        b.append("</div>");
        return b.toString();
    }

    /** The per-group numbers the compare, dartboard and scatter views read. */
    private static String partsJson(Scorecard card) {
        if (card == null) {
            return "{}";
        }
        Map<String, double[]> totals = new LinkedHashMap<>();
        for (Scorecard.Entry e : card.entries()) {
            if (e.value() == null) {
                continue;
            }
            double[] sum = totals.computeIfAbsent(e.group().name().toLowerCase(Locale.ROOT),
                    k -> new double[]{0.0, 0.0});
            sum[0] += e.value();
            sum[1] += 1;
        }
        StringBuilder b = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, double[]> e : totals.entrySet()) {
            b.append(i++ > 0 ? ", " : "").append('"').append(e.getKey()).append("\": ")
                    .append(fmt(e.getValue()[0]));
        }
        return b.append('}').toString();
    }

    /**
     * A candidate's map.
     *
     * <p>No {@code width}/{@code height} attributes: they arrive as
     * presentational hints, a fixed {@code height} in pixels beats the
     * stylesheet's {@code aspect-ratio: 1}, and a square render was being
     * stretched into a tall rectangle in every card.
     */
    private static String image(String slug, BankView.CandidateView c) {
        if (!c.hasLowres() && !c.hasHighres()) {
            return "<div class='no-render'>render queued</div>";
        }
        String low = "renders/" + slug + "/" + c.seed() + ".png";
        String hires = "renders/" + slug + "/" + c.seed() + "_hires.png";
        // The whole-world render wins whenever it exists: a candidate with
        // only the 512-block spawn thumbnail shows that, but one that has
        // been drawn in full shows its whole playable area instead.
        String src = c.hasHighres() ? hires : low;
        return "<img src='" + escape(src) + "' data-hires='" + escape(hires)
                + "' loading='lazy' decoding='async'"
                + " alt='Map render " + escape(slug) + " seed " + c.seed() + "'"
                + " onerror=\"this.onerror=null;var d=document.createElement('div');"
                + "d.className='no-render '+this.className;d.textContent='render queued';"
                + "this.replaceWith(d)\">";
    }

    // ------------------------------------------------------------------ helpers

    /** A dimension's declared spawn X or Z (index 0 or 2), 0 when unset. */
    private static int spawnCoord(DimensionConfig def, int index) {
        int[] spawn = def.getSpawn();
        return spawn != null && spawn.length >= 3 ? spawn[index] : 0;
    }

    private static String family(DimensionConfig def) {
        DimensionConfig.SeedRoll roll = def.getSeedRoll();
        if (roll != null && roll.family != null && !roll.family.isBlank()) {
            return roll.family;
        }
        return type(def);
    }

    /** {@link DimensionConfig#getType()}, display-collapsed. Reserved dimensions with no
     *  explicit type fall back to a namespace:path id ({@code paradise_lost:paradise_lost}). */
    private static String type(DimensionConfig def) {
        return displayId(nullSafe(def.getType(), "unknown"));
    }

    /** A namespaced id whose namespace equals its path ({@code paradise_lost:paradise_lost})
     *  carries no information the path alone does not — shown as just the path. */
    private static String displayId(String raw) {
        if (raw == null) {
            return null;
        }
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return raw;
        }
        String ns = raw.substring(0, colon);
        String path = raw.substring(colon + 1);
        return ns.equals(path) ? path : raw;
    }

    private static String mood(DimensionConfig def) {
        DimensionConfig.SeedRoll roll = def.getSeedRoll();
        return roll != null && roll.mood != null && !roll.mood.isBlank() ? roll.mood : "unset";
    }

    private static String description(DimensionConfig def) {
        return def.getDescription() != null ? def.getDescription() : "";
    }

    /** The highest percentage among a dimension's shown candidates, 0 when none is scored. */
    private static double bestScore(List<BankView.CandidateView> candidates) {
        double best = 0.0;
        for (BankView.CandidateView c : candidates) {
            if (c.percentage() != null) {
                best = Math.max(best, c.percentage());
            }
        }
        return best;
    }

    /** Below {@link RollPipeline#SCORE_THRESHOLD}, or nothing banked at all — display only. */
    private static boolean flagged(BankView.DimensionView v, double bestScore) {
        return v.banked() == 0 || bestScore < RollPipeline.SCORE_THRESHOLD;
    }

    /**
     * {@link RollPipeline#SCORE_THRESHOLD} as the page states it — "80", not
     * "80.0". The template and app.js both read this rather than carrying a
     * number of their own, so the flagged toggle's label, its tooltip and the
     * candidate filter in the flat view cannot disagree with {@link #flagged}.
     */
    private static String scoreThreshold() {
        double t = RollPipeline.SCORE_THRESHOLD;
        return t == Math.rint(t) ? String.valueOf((long) t) : fmt(t);
    }

    private static int shortlistedCount(List<BankView.CandidateView> candidates) {
        int n = 0;
        for (BankView.CandidateView c : candidates) {
            if (c.shortlisted()) {
                n++;
            }
        }
        return n;
    }

    private static String shortHash(String hash) {
        return hash == null ? "?" : hash.substring(0, Math.min(8, hash.length()));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static List<String> sorted(Set<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort(String::compareTo);
        return out;
    }

    private static String nullSafe(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * dartboard.js colours an arc by severity: {@code sev2} bad, {@code sev1}
     * warning, neither for good. Read off the score the criterion gave, so
     * the map agrees with the number beside it.
     */
    private static String severityClass(Scorecard.Entry e) {
        if (e.value() == null) {
            return "";
        }
        if (e.value() < 0.34) {
            return " sev2";
        }
        return e.value() < 0.67 ? " sev1" : "";
    }

    /** A criterion's score reads to two places — most of them land on 0.3, 0.5, 1.0. */
    private static String fmtScore(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String scoreColour(double score) {
        if (score >= 70) {
            return "#6ec96e";
        }
        if (score >= 50) {
            return "#e8a735";
        }
        return "#e05252";
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
