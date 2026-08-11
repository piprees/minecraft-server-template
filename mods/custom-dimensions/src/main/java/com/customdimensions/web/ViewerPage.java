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

    private static final int TOP_CANDIDATES = 12;

    private ViewerPage() {
    }

    public static String render(MinecraftServer server) throws IOException {
        List<BankView.DimensionView> views = BankView.all(server);
        String template = template();

        Set<String> families = new LinkedHashSet<>();
        Set<String> types = new LinkedHashSet<>();
        Set<String> moods = new LinkedHashSet<>();
        int totalCandidates = 0;
        int withCandidates = 0;
        for (BankView.DimensionView v : views) {
            families.add(family(v.config()));
            types.add(nullSafe(v.config().getType(), "unknown"));
            moods.add(mood(v.config()));
            totalCandidates += v.candidates().size();
            if (!v.candidates().isEmpty()) {
                withCandidates++;
            }
        }

        StringBuilder familyButtons = new StringBuilder(
                "<button class='family-btn active' data-family=''>All</button>");
        for (String f : sorted(families)) {
            familyButtons.append("<button class='family-btn' data-family='").append(escape(f))
                    .append("'>").append(escape(f)).append("</button>");
        }
        StringBuilder typeOptions = new StringBuilder("<option value=''>All types</option>");
        for (String t : sorted(types)) {
            typeOptions.append("<option>").append(escape(t)).append("</option>");
        }
        StringBuilder moodOptions = new StringBuilder("<option value=''>All moods</option>");
        for (String m : sorted(moods)) {
            moodOptions.append("<option>").append(escape(m)).append("</option>");
        }

        String summary = "<b>" + views.size() + "</b> dimensions &middot; <b>"
                + withCandidates + "</b> rolled &middot; <b>" + totalCandidates
                + "</b> candidates banked";

        StringBuilder cards = new StringBuilder();
        for (BankView.DimensionView v : views) {
            cards.append(card(v)).append('\n');
        }

        return template
                .replace("{{FAMILY_BUTTONS}}", familyButtons.toString())
                .replace("{{TYPE_OPTIONS}}", typeOptions.toString())
                .replace("{{MOOD_OPTIONS}}", moodOptions.toString())
                .replace("{{SUMMARY_STATS}}", summary)
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

    private static String card(BankView.DimensionView v) {
        DimensionConfig def = v.config();
        String slug = v.slug();
        List<BankView.CandidateView> candidates = v.candidates();
        BankView.CandidateView best = candidates.isEmpty() ? null : candidates.get(0);
        double bestScore = best != null && best.percentage() != null ? best.percentage() : 0.0;
        String panelId = "detail-" + slug;

        StringBuilder b = new StringBuilder();
        // data-dim on the card as well as its candidates: the roller's
        // dimension picker is built from every [data-dim] on the page, and a
        // dimension with nothing banked yet is exactly the one worth rolling.
        b.append("<div class='dim-card' data-dim='").append(escape(slug))
                .append("' data-name='").append(escape(slug))
                .append("' data-family='").append(escape(family(def)))
                .append("' data-type='").append(escape(nullSafe(def.getType(), "unknown")))
                .append("' data-mood='").append(escape(mood(def)))
                .append("' data-flagged='").append(candidates.isEmpty() || bestScore < 70 ? 1 : 0)
                .append("' data-score='").append(fmt(bestScore))
                .append("' data-cands='").append(candidates.size())
                .append("' data-shortlisted='0' data-pinned='0' data-radius='")
                .append(def.getPlayerBorderRadius())
                .append("' data-dim-scale='").append(fmt(def.getScale()))
                .append("'>");

        if (candidates.isEmpty()) {
            b.append("<div class='flag-dot red'></div>");
        } else if (bestScore < 50) {
            b.append("<div class='flag-dot amber'></div>");
        }

        // Compact face
        b.append("<div class='compact'>");
        if (best != null) {
            b.append(image(slug, best));
        } else {
            b.append("<div class='no-render'>not rolled</div>");
        }
        b.append("<div class='dim-name'>").append(escape(slug)).append("</div>");
        b.append("<div class='dim-meta'>")
                .append("<span class='dim-score' style='color:").append(scoreColour(bestScore))
                .append("'>").append(candidates.isEmpty() ? "&mdash;" : fmt(bestScore)).append("</span>")
                .append("<span class='badge'>").append(escape(nullSafe(def.getType(), "unknown"))).append("</span>")
                .append("<span class='badge'>").append(escape(mood(def))).append("</span>")
                .append("<span>").append(candidates.size()).append(" seeds</span>")
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
                .append("<span class='badge'>").append(escape(nullSafe(def.getType(), "unknown"))).append("</span>")
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
        b.append("<span class='badge'>hash ").append(escape(shortHash(v.inputHash()))).append("</span>");
        b.append("</div></div></div>");

        if (candidates.isEmpty()) {
            b.append("<p class='meta'>No seeds banked under this dimension's current inputs.</p>");
        } else {
            b.append("<div class='all-cands'>");
            int shown = Math.min(candidates.size(), TOP_CANDIDATES);
            for (int i = 0; i < shown; i++) {
                b.append(candidate(i, slug, candidates.get(i), v));
            }
            b.append("</div>");
            if (candidates.size() > shown) {
                b.append("<p class='cand-count meta'>Top ").append(shown).append(" of ")
                        .append(candidates.size()).append(" banked</p>");
            }
        }
        b.append("</div></div>");
        return b.toString();
    }

    // ------------------------------------------------------------------ candidate

    private static String candidate(int idx, String slug, BankView.CandidateView c,
                                    BankView.DimensionView v) {
        double pct = c.percentage() == null ? 0.0 : c.percentage();
        boolean onFrontier = v.frontierSeeds().contains(c.seed());
        StringBuilder b = new StringBuilder();
        b.append("<div class='cand cand-item").append(onFrontier ? " winner" : "")
                .append("' data-idx='").append(idx)
                .append("' data-score='").append(fmt(pct))
                .append("' data-dim='").append(escape(slug))
                .append("' data-seed='").append(c.seed())
                .append("' data-parts='").append(escape(partsJson(c.scorecard())))
                .append("' data-render='renders/").append(escape(slug)).append('/').append(c.seed())
                .append(".png'").append(idx >= 6 ? " style='display:none'" : "").append('>');
        b.append(image(slug, c));
        if (c.hasHighres()) {
            b.append("<div class='hires-badge'>HD</div>");
        }
        b.append("<div class='cand-dim-label'>").append(escape(slug)).append("</div>");
        b.append("<div class='score' style='color:").append(scoreColour(pct)).append("'>")
                .append(fmt(pct)).append(onFrontier ? " &#x1F3C6;" : "").append("</div>");
        b.append("<div class='seed'>").append(c.seed()).append("</div>");
        b.append("<button type='button' class='cmp-pick' aria-label='Compare seed ")
                .append(c.seed()).append("'>compare</button>");

        // The modal's body: the score AND the reasons behind it.
        b.append("<div class='cand-detail' style='display:none'>");
        b.append("<div class='lb-header'><div class='lb-title'>")
                .append("<span class='dim-label'>").append(escape(slug)).append("</span> ")
                .append("<span class='score' style='color:").append(scoreColour(pct)).append("'>")
                .append(fmt(pct)).append("</span> ")
                .append("<span class='seed'>").append(c.seed()).append("</span></div>");
        b.append("<div class='score-parts'>").append(fmt(c.achieved())).append(" of ")
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
        b.append(criteria(c.scorecard()));
        // The two things a person does from here: go and look at it, then
        // choose it. Nothing between the map and the decision.
        b.append("<div class='lb-actions'>")
                .append("<button type='button' class='action-btn tryout' data-dim='")
                .append(escape(slug)).append("' data-seed='").append(c.seed())
                .append("'>Try it out</button>")
                .append("<button type='button' class='action-btn tryout-back'>Back to spawn</button>")
                .append("<button type='button' class='pick' data-dim='").append(escape(slug))
                .append("' data-seed='").append(c.seed())
                .append("'>Use this seed</button>")
                .append("</div>");
        b.append("</div>");

        b.append("</div>");
        return b.toString();
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
                b.append("<div class='crit-row' data-outcome='")
                        .append(escape(e.outcome().toLowerCase(Locale.ROOT))).append("'>")
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
        return "<img src='" + escape(c.hasLowres() ? low : hires) + "' data-hires='" + escape(hires)
                + "' loading='lazy' decoding='async'"
                + " alt='Map render " + escape(slug) + " seed " + c.seed() + "'"
                + " onerror=\"this.onerror=null;var d=document.createElement('div');"
                + "d.className='no-render '+this.className;d.textContent='render queued';"
                + "this.replaceWith(d)\">";
    }

    // ------------------------------------------------------------------ helpers

    private static String family(DimensionConfig def) {
        DimensionConfig.SeedRoll roll = def.getSeedRoll();
        if (roll != null && roll.family != null && !roll.family.isBlank()) {
            return roll.family;
        }
        return nullSafe(def.getType(), "unknown");
    }

    private static String mood(DimensionConfig def) {
        DimensionConfig.SeedRoll roll = def.getSeedRoll();
        return roll != null && roll.mood != null && !roll.mood.isBlank() ? roll.mood : "unset";
    }

    private static String description(DimensionConfig def) {
        if (def.getDescription() != null && !def.getDescription().isBlank()) {
            return def.getDescription();
        }
        DimensionConfig.SeedRoll roll = def.getSeedRoll();
        return roll != null && roll.description != null ? roll.description : "";
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
