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

        Map<String, String> frameOwners = frameOwners(views);
        Map<String, Boolean> igniterDamageable = igniterDamageable(views);
        StringBuilder cards = new StringBuilder();
        for (BankView.DimensionView v : views) {
            cards.append(card(v, anyoneOnline, frameOwners, igniterDamageable)).append('\n');
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

    /**
     * Every frame accept form in the pack, mapped to the dimension that
     * claims it. First claim wins, matching how ignition resolves; the
     * weathering check reads it to name where a defecting frame would go.
     */
    private static Map<String, String> frameOwners(List<BankView.DimensionView> views) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (BankView.DimensionView v : views) {
            for (DimensionConfig.Portal portal : v.config().getPortals()) {
                for (String form : portal.getFrameAcceptForms()) {
                    owners.putIfAbsent(form, v.slug());
                }
            }
        }
        return owners;
    }

    /**
     * Whether each igniter in the pack has durability to spend. An item the
     * registry does not know is left out rather than guessed at, which the
     * igniter row renders as both outcomes.
     */
    private static Map<String, Boolean> igniterDamageable(List<BankView.DimensionView> views) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (BankView.DimensionView v : views) {
            for (DimensionConfig.Portal portal : v.config().getPortals()) {
                String id = portal.igniterItem;
                if (id == null || id.isBlank() || out.containsKey(id)) {
                    continue;
                }
                net.minecraft.util.Identifier parsed = net.minecraft.util.Identifier.tryParse(id);
                if (parsed == null) {
                    continue;
                }
                net.minecraft.registry.Registries.ITEM.getOrEmpty(parsed).ifPresent(item ->
                        out.put(id, new net.minecraft.item.ItemStack(item).isDamageable()));
            }
        }
        return out;
    }

    private static String card(BankView.DimensionView v, boolean anyoneOnline,
                               Map<String, String> frameOwners,
                               Map<String, Boolean> igniterDamageable) {
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
        // Roll selection. Outside compact-trigger so clicking it never expands
        // the card, and title-cased nowhere: the slug is the identity.
        b.append("<label class='dim-pick' title='Select for rolling'>")
                .append("<input type='checkbox' class='dim-pick-box' data-pick='")
                .append(escape(slug)).append("'></label>");
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
                b.append(candidate(i, slug, candidates.get(i), v, anyoneOnline, frameOwners,
                        igniterDamageable));
            }
            b.append("</div>");
        }
        b.append("</div></div>");
        return b.toString();
    }

    // ------------------------------------------------------------------ candidate

    private static String candidate(int idx, String slug, BankView.CandidateView c,
                                    BankView.DimensionView v, boolean anyoneOnline,
                                    Map<String, String> frameOwners,
                                    Map<String, Boolean> igniterDamageable) {
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
        b.append(portals(v.config(), frameOwners, igniterDamageable));
        b.append(config(v.config()));
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

    // ------------------------------------------------------------------ config

    /**
     * What this dimension IS, beside the seed being judged: the whole config
     * bar the portal block, which has a panel of its own.
     *
     * <p>Every block carries the timing of the fields in it, because that is
     * the fact a reader would act on. A frozen field is baked into
     * {@code level.dat} at creation and editing it does nothing, ever,
     * without a world wipe ({@code TROUBLESHOOTING.md#d2}); a "new chunks"
     * field rebuilds at boot but leaves generated chunks as they are; a live
     * field takes effect on the next boot everywhere. Two more markers exist
     * because the schema can express things nothing reads: {@code tooling}
     * for what only scripts consume, and {@code inert} for fields parsed and
     * then read by nobody.
     */
    static String config(DimensionConfig def) {
        StringBuilder b = new StringBuilder("<div class='portals dimconf'>");
        b.append("<div class='portals-label'>config</div>");
        b.append("<div class='conf-legend'>")
                .append(timing(FROZEN)).append("creation-time &mdash; changing it needs a world wipe")
                .append(timing(CHUNKS)).append("rebuilt at boot, generated chunks keep what they have")
                .append(timing(LIVE)).append("applies on the next boot")
                .append(timing(TOOLING)).append("read by scripts, never by the world")
                .append(timing(INERT)).append("parsed, then read by nothing")
                .append("</div>");
        b.append(worldBlock(def));
        b.append(terrainBlock(def));
        b.append(biomesBlock(def));
        b.append(boundsBlock(def));
        b.append(difficultyBlock(def));
        b.append(structuresBlock(def));
        b.append(environmentBlock(def));
        b.append(exitsBlock(def));
        b.append(rollBlock(def));
        return b.append("</div>").toString();
    }

    private static final String FROZEN = "frozen";
    private static final String CHUNKS = "new chunks";
    private static final String LIVE = "live";
    private static final String TOOLING = "tooling";
    private static final String INERT = "inert";

    private static String timing(String kind) {
        return "<span class='ptime ptime-" + kind.replace(' ', '-') + "'>"
                + kind + "</span>";
    }

    /** A sub-block, or nothing when it has no rows — an empty heading says less than none. */
    private static String block(String name, String kind, String rows) {
        if (rows.isEmpty()) {
            return "";
        }
        return "<div class='pways'><div class='portal-head'><span class='portal-id'>"
                + escape(name) + "</span>" + timing(kind) + "</div>" + rows + "</div>";
    }

    // --- world ----------------------------------------------------------------

    private static String worldBlock(DimensionConfig def) {
        StringBuilder b = new StringBuilder();
        if (def.getType() != null) {
            b.append(prow("type", "<span class='pv-name'>" + escape(def.getType()) + "</span>"));
        }
        if (def.getNoiseSettings() != null && !def.getNoiseSettings().isBlank()) {
            b.append(prow("noise settings", "<span class='pv-name'>"
                    + escape(def.getNoiseSettings()) + "</span>"));
        }
        com.google.gson.JsonElement seed = def.getRawSeed();
        if (seed != null && !seed.isJsonNull()) {
            boolean env = seed.isJsonPrimitive() && seed.getAsJsonPrimitive().isString();
            b.append(prow("seed", env
                    ? "<span class='pv-name'>env</span>"
                            + pnote("reads the SEED environment variable")
                    : "<span class='pv-name'>" + escape(seed.getAsString()) + "</span>"));
        }
        int[] spawn = def.getSpawn();
        if (spawn != null) {
            b.append(prow("spawn", "<span class='pv-name'>" + spawn[0] + ", " + spawn[1]
                    + ", " + spawn[2] + "</span>" + timing(LIVE)
                    + pnote("the roller overwrites this when a seed is picked")));
        }
        return block("world", FROZEN, b.toString());
    }

    // --- terrain --------------------------------------------------------------

    private static String terrainBlock(DimensionConfig def) {
        StringBuilder b = new StringBuilder();
        DimensionConfig.SettingsOverrides so = def.getSettingsOverrides();
        if (so != null) {
            if (so.seaLevel != null) {
                b.append(prow("sea level", "<span class='pv-name'>" + so.seaLevel + "</span>"));
            }
            if (so.defaultBlock != null && !so.defaultBlock.isBlank()) {
                b.append(prow("default block", blocks(List.of(so.defaultBlock))
                        + pnote("what solid terrain is made of")));
            }
            if (so.defaultFluid != null && !so.defaultFluid.isBlank()) {
                b.append(prow("default fluid", blocks(List.of(so.defaultFluid))
                        + pnote("what fills below sea level")));
            }
            if (Boolean.TRUE.equals(so.disableMobGeneration)) {
                b.append(prow("mob generation", "no mobs generate with the terrain"));
            }
            if (so.endIsland != null) {
                b.append(prow("end island", Boolean.TRUE.equals(so.endIsland)
                        ? "the End's origin island and void moat"
                        : "no origin island"));
            }
        }
        if (def.getCheckerboardScale() != null) {
            int cells = 1 << (def.getCheckerboardScale() + 4);
            b.append(prow("checkerboard", "<span class='pv-name'>" + cells
                    + " block cells</span>" + pnote("scale " + def.getCheckerboardScale())));
        }
        if (def.getFlatBiome() != null && !def.getFlatBiome().isBlank()) {
            b.append(prow("flat biome", blocks(List.of(def.getFlatBiome()))));
        }
        List<DimensionConfig.FlatLayer> layers = def.getLayers();
        if (layers != null && !layers.isEmpty()) {
            StringBuilder v = new StringBuilder();
            for (DimensionConfig.FlatLayer l : layers) {
                if (l.block != null) {
                    v.append("<span class='pchip'>").append(escape(l.block))
                            .append(l.height == null ? "" : " x" + l.height).append("</span>");
                }
            }
            b.append(prow("layers", v + pnote("bottom up")));
        }
        return block("terrain", FROZEN, b.toString());
    }

    // --- biomes ---------------------------------------------------------------

    private static String biomesBlock(DimensionConfig def) {
        StringBuilder b = new StringBuilder();
        List<String> ids = def.getBiomes();
        if (ids != null && !ids.isEmpty()) {
            b.append(prow("listed", chips(ids) + pnote(ids.size() + " biomes")));
        }
        for (DimensionConfig.BiomeBand band : def.getBiomeBands()) {
            StringBuilder axes = new StringBuilder();
            for (String axis : band.parameters().keySet()) {
                axes.append("<span class='pchip'>").append(escape(axis)).append(' ')
                        .append(interval(band.parameters().get(axis))).append("</span>");
            }
            b.append(prow("banded", blocks(List.of(band.id())) + axes));
        }
        List<DimensionConfig.BiomePatch> patches = def.getBiomePatches();
        if (patches != null) {
            for (DimensionConfig.BiomePatch p : patches) {
                if (p.biome == null) {
                    continue;
                }
                StringBuilder v = new StringBuilder(blocks(List.of(p.biome)));
                if (p.x != null && p.z != null) {
                    v.append(pnote("at " + p.x + ", " + p.z));
                }
                if (p.radius != null) {
                    v.append(pnote("radius " + p.radius));
                }
                if (p.shape != null && !p.shape.isBlank()) {
                    v.append(pnote(escape(p.shape)));
                }
                if (p.replace != null && !p.replace.isBlank()) {
                    v.append(pnote("replacing " + escape(p.replace)));
                }
                if (p.scope != null && !p.scope.isBlank()) {
                    v.append(pnote(escape(p.scope) + " scope"));
                }
                if (p.blend != null) {
                    v.append(pnote(p.blend + " block edge jitter"));
                }
                b.append(prow("patch", v.toString()));
            }
        }
        return block("biomes", FROZEN, b.toString());
    }

    // --- bounds ---------------------------------------------------------------

    private static String boundsBlock(DimensionConfig def) {
        int player = def.getPlayerBorderRadius();
        int generation = def.getGenerationBorderRadius();
        double scale = def.getScale();
        StringBuilder b = new StringBuilder();
        b.append(prow("player border", "<span class='pv-name'>" + player + "</span>"
                + pnote("radius, so " + (player * 2) + " blocks across")
                + pnote("reaches " + Math.round(player * scale)
                        + " blocks of the source world at " + num(scale) + "x scale")));
        b.append(prow("generation", "<span class='pv-name'>" + generation + "</span>"
                + timing(TOOLING)
                + pnote("never applied to the world &mdash; the map renderer's clamp, "
                        + "Chunky's extent and the roller's locate cap read it")));
        return block("bounds", LIVE, b.toString());
    }

    // --- difficulty -----------------------------------------------------------

    private static String difficultyBlock(DimensionConfig def) {
        DimensionConfig.Difficulty d = def.getDifficulty();
        if (d == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        if (d.hostileSpawning != null) {
            b.append(prow("hostile spawning", Boolean.TRUE.equals(d.hostileSpawning)
                    ? "on" : "off" + pnote("effectively peaceful")));
        }
        if (d.mobMultiplier != null) {
            b.append(prow("mob multiplier", "<span class='pv-name'>" + num(d.mobMultiplier)
                    + "</span>" + pnote(d.mobMultiplier >= 2.0
                            ? "at or above 2.0, which spreads dungeons and pulls endgame in"
                            : (d.mobMultiplier <= 0.5
                                    ? "at or below 0.5, which suppresses dungeons and endgame"
                                    : "scales hostile mobs at spawn"))));
        }
        if (d.playerLuck != null) {
            b.append(prow("player luck", "<span class='pv-name'>" + num(d.playerLuck)
                    + "</span>" + pnote("flat loot bonus while inside")));
        }
        if (d.attributes != null) {
            StringBuilder on = new StringBuilder();
            appendFlag(on, "health", d.attributes.health);
            appendFlag(on, "damage", d.attributes.damage);
            appendFlag(on, "armor", d.attributes.armor);
            appendFlag(on, "speed", d.attributes.speed);
            appendFlag(on, "knockback", d.attributes.knockback);
            b.append(prow("attributes", on.length() == 0
                    ? pnote("none") : on + pnote("what the multiplier touches")));
        }
        DimensionConfig.DepthScaling ds = d.depthScaling;
        if (ds != null && !Boolean.FALSE.equals(ds.enabled)) {
            double base = d.mobMultiplier == null ? 1.0 : d.mobMultiplier;
            double lo = ds.minMultiplier == null ? 1.0 : ds.minMultiplier;
            double hi = ds.maxMultiplier == null ? 1.0 : ds.maxMultiplier;
            b.append(prow("depth scaling", "<span class='pv-name'>y "
                    + (ds.startY == null ? 0 : ds.startY) + " to "
                    + (ds.endY == null ? 0 : ds.endY) + "</span>"
                    + pnote("factor " + num(lo) + " to " + num(hi))
                    + pnote("so the effective multiplier runs " + num(base * lo)
                            + " to " + num(base * hi))));
        }
        return block("difficulty", LIVE, b.toString());
    }

    private static void appendFlag(StringBuilder b, String name, Boolean on) {
        if (Boolean.TRUE.equals(on)) {
            b.append("<span class='pchip'>").append(name).append("</span>");
        }
    }

    // --- structures -----------------------------------------------------------

    private static String structuresBlock(DimensionConfig def) {
        StringBuilder b = new StringBuilder();
        if (def.getStructureDensity() != null && !def.getStructureDensity().isBlank()) {
            b.append(prow("density", "<span class='pv-name'>"
                    + escape(def.getStructureDensity()) + "</span>"
                    + pnote("the profile every group starts from")));
        }
        DimensionConfig.Structures s = def.getStructures();
        if (s != null) {
            b.append(noiseRows(s));
            b.append(wantShunRows(s));
            b.append(placementRows(s));
        }
        return block("structures", CHUNKS, b.toString());
    }

    private static String noiseRows(DimensionConfig.Structures s) {
        StringBuilder b = new StringBuilder();
        if (s.noise != null && !s.noise.isJsonNull()) {
            if (s.noise.isJsonObject()) {
                StringBuilder v = new StringBuilder();
                for (String group : s.noise.getAsJsonObject().keySet()) {
                    v.append("<span class='pchip'>").append(escape(group)).append(' ')
                            .append(escape(s.noise.getAsJsonObject().get(group).getAsString()))
                            .append("</span>");
                }
                b.append(prow("noise plan", v.toString()));
            } else {
                b.append(prow("noise plan", "<span class='pv-name'>"
                        + escape(s.noise.getAsString()) + "</span>"));
            }
        }
        if (s.radial != null) {
            for (Map.Entry<String, List<Double>> e : s.radial.entrySet()) {
                StringBuilder v = new StringBuilder();
                for (Double d : e.getValue()) {
                    v.append("<span class='pchip'>").append(num(d)).append("</span>");
                }
                b.append(prow("radial " + e.getKey(),
                        v + pnote("spawn to border, ten bands")));
            }
        }
        if (s.rarity != null && !s.rarity.isEmpty()) {
            StringBuilder v = new StringBuilder();
            for (Map.Entry<String, String> e : s.rarity.entrySet()) {
                v.append("<span class='pchip'>").append(escape(e.getKey())).append(' ')
                        .append(escape(e.getValue())).append("</span>");
            }
            b.append(prow("rarity", v.toString()));
        }
        if (s.exclude != null && !s.exclude.isEmpty()) {
            b.append(prow("excluded", chips(s.exclude) + pnote("out of the pool entirely")));
        }
        if (s.include != null && !s.include.isEmpty()) {
            b.append(prow("included", chips(s.include) + pnote("in, past the biome filter")));
        }
        return b.toString();
    }

    private static String wantShunRows(DimensionConfig.Structures s) {
        StringBuilder b = new StringBuilder();
        if (s.wants != null && !s.wants.isEmpty()) {
            StringBuilder v = new StringBuilder();
            for (Map.Entry<String, DimensionConfig.StructureWant> e : s.wants.entrySet()) {
                v.append("<span class='pchip'>").append(escape(e.getKey()));
                if (e.getValue() != null && e.getValue().min != null && e.getValue().max != null) {
                    v.append(' ').append(e.getValue().min).append('-').append(e.getValue().max);
                }
                v.append("</span>");
            }
            b.append(prow("wants", v + pnote("pool weight x1.2, and past the biome filter")));
        }
        if (s.shuns != null && !s.shuns.isEmpty()) {
            b.append(prow("shuns", chips(new ArrayList<>(s.shuns.keySet()))
                    + pnote("pool weight divided by 1.5, never to zero")));
            for (DimensionConfig.StructureShun shun : s.shuns.values()) {
                if (shun != null && shun.minDistance != null) {
                    b.append(prow("shun distance", "<span class='pv-name'>"
                            + shun.minDistance + "</span>" + timing(INERT)
                            + pnote("the value is discarded; every shun behaves as {}")));
                    break;
                }
            }
        }
        if (s.wants != null && s.shuns != null) {
            List<String> both = new ArrayList<>(s.wants.keySet());
            both.retainAll(s.shuns.keySet());
            if (!both.isEmpty()) {
                b.append(prow("conflict", "<span class='pv-bad'>" + escape(String.join(", ", both))
                        + "</span>" + pnote("named in wants and shuns, so the two cancel")));
            }
        }
        if (s.endgame != null) {
            b.append(prow("endgame", "<span class='pv-name'>"
                    + (Boolean.FALSE.equals(s.endgame.allow) ? "banned" : "allowed")
                    + (s.endgame.safeRadius == null ? ""
                            : ", safe radius " + s.endgame.safeRadius) + "</span>"
                    + timing(INERT) + pnote("read by nothing; clearSpawnRadius is the live lever")));
        }
        return b.toString();
    }

    private static String placementRows(DimensionConfig.Structures s) {
        StringBuilder b = new StringBuilder();
        if (s.mode != null && !s.mode.isBlank()) {
            b.append(prow("filter", "<span class='pv-name'>" + escape(s.mode) + "</span>"
                    + (s.list == null || s.list.isEmpty() ? "" : chips(s.list))));
        }
        if (s.spacing != null && !s.spacing.isEmpty()) {
            StringBuilder v = new StringBuilder();
            for (Map.Entry<String, DimensionConfig.SpacingOverride> e : s.spacing.entrySet()) {
                v.append("<span class='pchip'>").append(escape(e.getKey())).append(' ')
                        .append(e.getValue().spacing).append('/').append(e.getValue().separation)
                        .append("</span>");
            }
            b.append(prow("spacing", v + pnote("grid-placed sets only")));
        }
        if (s.force != null) {
            for (DimensionConfig.ForcedStructure f : s.force) {
                if (f.structure == null) {
                    continue;
                }
                StringBuilder v = new StringBuilder(blocks(List.of(f.structure)));
                if (f.x != null && f.z != null) {
                    v.append(pnote("at " + f.x + ", " + f.z));
                }
                if (f.y != null) {
                    v.append(pnote("pinned to y " + f.y));
                }
                v.append(pnote(f.isExclusive()
                        ? "and nowhere else" : "organic copies kept too"));
                b.append(prow("forced", v.toString()));
            }
        }
        if (s.terrainAdaptation != null && !s.terrainAdaptation.isEmpty()) {
            StringBuilder v = new StringBuilder();
            for (Map.Entry<String, String> e : s.terrainAdaptation.entrySet()) {
                v.append("<span class='pchip'>").append(escape(e.getKey())).append(' ')
                        .append(escape(e.getValue())).append("</span>");
            }
            b.append(prow("adaptation", v + pnote("how each structure meets the ground")));
        }
        if (s.clearSpawnRadius != null) {
            b.append(prow("clear spawn", "<span class='pv-name'>" + s.clearSpawnRadius
                    + "</span>" + pnote("groups kept this far from spawn")));
        }
        return b.toString();
    }

    // --- environment ----------------------------------------------------------

    private static String environmentBlock(DimensionConfig def) {
        DimensionConfig.Environment e = def.getEnvironment();
        if (e == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        if (e.skyColor != null || e.fogColor != null) {
            StringBuilder v = new StringBuilder();
            if (e.skyColor != null) {
                v.append(swatch(e.skyColor)).append(pnote("sky"));
            }
            if (e.fogColor != null) {
                v.append(swatch(e.fogColor)).append(pnote("fog"));
            }
            b.append(prow("colours", v + pnote("client-side only; a plain client sees neither")));
        }
        if (e.ambientLight != null) {
            b.append(prow("ambient light", "<span class='pv-name'>"
                    + num(e.ambientLight) + "</span>"));
        }
        if (e.fixedTime != null) {
            b.append(prow("fixed time", "<span class='pv-name'>" + e.fixedTime + "</span>"
                    + pnote("the sun never moves")));
        }
        StringBuilder flags = new StringBuilder();
        appendState(flags, "hasCeiling", e.hasCeiling);
        appendState(flags, "hasSkylight", e.hasSkylight);
        appendState(flags, "ultraWarm", e.ultraWarm);
        appendState(flags, "natural", e.natural);
        appendState(flags, "bedWorks", e.bedWorks);
        appendState(flags, "respawnAnchorWorks", e.respawnAnchorWorks);
        appendState(flags, "piglinSafe", e.piglinSafe);
        appendState(flags, "hasRaids", e.hasRaids);
        if (flags.length() > 0) {
            b.append(prow("flags", flags.toString()));
        }
        if (e.effects != null && !e.effects.isBlank()) {
            b.append(prow("sky effects", "<span class='pv-name'>" + escape(e.effects)
                    + "</span>" + pnote("which sky the client draws")));
        }
        if (e.infiniburn != null && !e.infiniburn.isBlank()) {
            b.append(prow("infiniburn", blocks(List.of(e.infiniburn))
                    + pnote("blocks fire burns on forever")));
        }
        if (e.monsterSpawnLightLevel != null && !e.monsterSpawnLightLevel.isJsonNull()) {
            b.append(prow("spawn light", "<span class='pv-name'>"
                    + escape(e.monsterSpawnLightLevel.toString()) + "</span>"));
        }
        if (e.monsterSpawnBlockLightLimit != null) {
            b.append(prow("block light limit", "<span class='pv-name'>"
                    + e.monsterSpawnBlockLightLimit + "</span>"));
        }
        StringBuilder shape = new StringBuilder();
        if (e.minY != null) {
            shape.append("<span class='pchip'>minY ").append(e.minY).append("</span>");
        }
        if (e.height != null) {
            shape.append("<span class='pchip'>height ").append(e.height).append("</span>");
        }
        if (e.logicalHeight != null) {
            shape.append("<span class='pchip'>logicalHeight ").append(e.logicalHeight)
                    .append("</span>");
        }
        if (shape.length() > 0) {
            b.append(prow("build height", shape + timing(FROZEN)
                    + pnote("chunk storage shape; the rest of this block is not")));
        }
        return block("environment", LIVE, b.toString());
    }

    private static String swatch(String hex) {
        String css = hex.startsWith("#") ? hex : "#" + hex;
        return "<span class='pswatch' style='background:" + escape(css)
                + "'></span><span class='pv-name'>" + escape(hex) + "</span>";
    }

    /** A tri-state flag: shown as set-true or set-false, absent when unset. */
    private static void appendState(StringBuilder b, String name, Boolean value) {
        if (value != null) {
            b.append("<span class='pchip'>").append(name).append(' ')
                    .append(value ? "yes" : "no").append("</span>");
        }
    }

    // --- exits ----------------------------------------------------------------

    private static String exitsBlock(DimensionConfig def) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, DimensionConfig.ExitRule> e : def.getExits().entrySet()) {
            DimensionConfig.ExitRule r = e.getValue();
            if (r == null) {
                continue;
            }
            StringBuilder v = new StringBuilder("<span class='pv-name'>")
                    .append(escape(r.getAction())).append("</span>");
            if (r.target != null && !r.target.isJsonNull()) {
                v.append(pnote("to " + exitTargetLabel(
                        com.customdimensions.dimension.ExitTarget.canonicalise(r.target, "origin"))));
            }
            if ("fallFrom".equals(e.getKey())) {
                v.append(pnote("after falling " + r.getMinHeight() + " blocks"));
            }
            b.append(prow(e.getKey(), v.toString()));
        }
        DimensionConfig.ExitShrines shrines = def.getExitShrines();
        if (shrines != null) {
            b.append(prow("exit shrines", def.hasExitShrines()
                    ? "<span class='pv-name'>scattered</span>"
                            + pnote("leading to " + exitTargetLabel(shrines.getTargetMode()))
                    : "disabled"));
        }
        return block("exits", LIVE, b.toString());
    }

    // --- roll intent ----------------------------------------------------------

    private static String rollBlock(DimensionConfig def) {
        DimensionConfig.SeedRoll r = def.getSeedRoll();
        if (r == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        if (Boolean.TRUE.equals(r.skip)) {
            b.append(prow("skip", "<span class='pv-bad'>not rolled</span>"
                    + pnote("the roller ignores this dimension entirely")));
        }
        if (r.mood != null && !r.mood.isBlank()) {
            b.append(prow("mood", "<span class='pv-name'>" + escape(r.mood) + "</span>"));
        }
        if (r.family != null && !r.family.isBlank()) {
            b.append(prow("family", "<span class='pv-name'>" + escape(r.family) + "</span>"
                    + pnote("overrides the family inferred from type")));
        }
        if (r.spawnFilter != null && !r.spawnFilter.isEmpty()) {
            b.append(prow("spawn filter", chips(r.spawnFilter)
                    + pnote("the biomes this dimension is named after")));
        }
        if (r.water != null && !r.water.isBlank()) {
            b.append(prow("water", "<span class='pv-name'>" + escape(r.water) + "</span>"));
        }
        if (r.terrain != null && !r.terrain.isBlank()) {
            b.append(prow("terrain", "<span class='pv-name'>" + escape(r.terrain) + "</span>"));
        }
        if (r.heightRange != null && r.heightRange.length == 2) {
            b.append(prow("height range", "<span class='pv-name'>" + r.heightRange[0] + " to "
                    + r.heightRange[1] + "</span>"
                    + pnote("an envelope the terrain should live inside, not a quota")));
        }
        if (Boolean.TRUE.equals(r.allowHazardousSpawn)) {
            b.append(prow("hazardous spawn", "allowed"
                    + pnote("withdraws both spawn safety criteria")));
        }
        if (r.wants != null && !r.wants.keySet().isEmpty()) {
            StringBuilder v = new StringBuilder();
            for (String k : r.wants.keySet()) {
                v.append("<span class='pchip'>").append(escape(k)).append(' ')
                        .append(escape(r.wants.get(k).getAsString())).append("</span>");
            }
            b.append(prow("roll wants", v + pnote("scored on the nearest instance's distance")));
        }
        if (r.shuns != null && !r.shuns.isJsonNull()) {
            StringBuilder v = new StringBuilder();
            if (r.shuns.isJsonArray()) {
                for (com.google.gson.JsonElement s : r.shuns.getAsJsonArray()) {
                    v.append("<span class='pchip'>").append(escape(s.getAsString()))
                            .append("</span>");
                }
            } else if (r.shuns.isJsonObject()) {
                for (String k : r.shuns.getAsJsonObject().keySet()) {
                    v.append("<span class='pchip'>").append(escape(k)).append("</span>");
                }
            }
            if (v.length() > 0) {
                b.append(prow("roll shuns", v.toString()));
            }
        }
        if (r.spawnRadius != null) {
            b.append(prow("spawn radius", "<span class='pv-name'>" + r.spawnRadius + "</span>"
                    + timing(INERT) + pnote("read by nothing")));
        }
        if (r.locateCap != null) {
            b.append(prow("locate cap", "<span class='pv-name'>" + r.locateCap + "</span>"
                    + timing(INERT) + pnote("nothing calls getLocateCap; the search is fixed")));
        }
        if (r.allowEndgameNearSpawn != null) {
            b.append(prow("endgame near spawn", "<span class='pv-name'>"
                    + r.allowEndgameNearSpawn + "</span>" + timing(INERT)
                    + pnote("there is no penalty for it to lift")));
        }
        return block("roll intent", "scoring", b.toString());
    }

    /** A climate interval as words: {@code [-0.5, 0.2]} reads "-0.5 to 0.2". */
    private static String interval(com.google.gson.JsonElement e) {
        if (e.isJsonArray() && e.getAsJsonArray().size() == 2) {
            return escape(e.getAsJsonArray().get(0).getAsString()) + " to "
                    + escape(e.getAsJsonArray().get(1).getAsString());
        }
        return escape(e.toString());
    }

    /** Up to two decimals, never fewer than one — 2.0 stays 2.0, 3.75 stays 3.75. */
    private static String num(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        while (s.endsWith("0") && !s.endsWith(".0")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ------------------------------------------------------------------ portals

    /**
     * Every portal a dimension declares, in config order, and the ways the
     * mod builds out of it.
     *
     * <p>Read to answer three questions in front of the map: what do I
     * build, what lights it, and what happens once it is lit. So a field
     * the config leaves out is left out here — a row carrying an empty
     * value reads as a setting, and there is nothing to set.
     *
     * <p>Every value is the one the mod resolves, not the one the file
     * writes: accept forms come from {@code getFrameAcceptForms}, so a key
     * the schema does not know never appears as something a frame accepts.
     */
    static String portals(DimensionConfig def) {
        return portals(def, Map.of());
    }

    /**
     * @param frameOwners accept form -> the dimension whose frame it is,
     *     across the whole pack. Needed only to name the dimension a
     *     weathering frame would defect to; empty means "say it weathers,
     *     name no owner" rather than guessing one.
     */
    static String portals(DimensionConfig def, Map<String, String> frameOwners) {
        return portals(def, frameOwners, Map.of());
    }

    /**
     * @param igniterDamageable item id -> whether it has durability to spend.
     *     Absent means unresolved, and the igniter row then names both
     *     outcomes instead of picking one: damageability belongs to the item,
     *     not to the config, so it cannot be read off a dimension file.
     */
    static String portals(DimensionConfig def, Map<String, String> frameOwners,
                          Map<String, Boolean> igniterDamageable) {
        List<DimensionConfig.Portal> all = def.getPortals();
        if (all.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("<div class='portals'>");
        b.append("<div class='portals-label'>portals</div>");
        for (int i = 0; i < all.size(); i++) {
            b.append(portal(all.get(i), def.portalId(i), i == 0, def.getName(), frameOwners,
                    igniterDamageable));
        }
        b.append(waysOut(def, all));
        return b.append("</div>").toString();
    }

    private static String portal(DimensionConfig.Portal p, String id, boolean primary,
                                 String slug, Map<String, String> frameOwners,
                                 Map<String, Boolean> igniterDamageable) {
        List<String> accepts = p.getFrameAcceptForms();
        Map<String, List<String>> parts = p.getFramePartAcceptForms();
        StringBuilder b = new StringBuilder("<div class='portal'>");

        b.append("<div class='portal-head'><span class='portal-id'>").append(escape(id))
                .append("</span>");
        if (primary) {
            b.append("<span class='portal-tag'>primary</span>");
        }
        if (p.isVanillaManaged()) {
            b.append("<span class='portal-tag'>vanilla-managed</span>");
        }
        if (accepts.isEmpty()) {
            b.append("<span class='portal-tag bad'>cannot be lit</span>");
        }
        b.append("</div>");

        if (!parts.isEmpty()) {
            b.append(prow("frame", blocks(accepts)
                    + pnote("the flood-fill takes any of these; each part is checked below")));
            for (String part : DimensionConfig.Portal.FRAME_PARTS) {
                List<String> forms = parts.get(part);
                b.append(prow(part, forms == null || forms.isEmpty()
                        ? pnote("any accepted form")
                        : blocks(forms) + formCount(forms)));
            }
        } else {
            b.append(prow("frame", accepts.isEmpty()
                    ? pnote("no frame block, so nothing bounds an opening")
                    : blocks(accepts) + formCount(accepts) + colourGroup(p)));
        }

        b.append(weathering(accepts, slug, frameOwners));

        String place = p.resolvePlacementBlockId();
        if (place != null && placeWorthStating(p, accepts)) {
            b.append(prow("builds with", blocks(List.of(place))
                    + pnote("what the mod places for arrival and exit frames")));
        }
        if (p.igniterItem != null && !p.igniterItem.isBlank()) {
            b.append(prow("igniter", blocks(List.of(p.igniterItem))
                    + igniterCost(p, igniterDamageable.get(p.igniterItem))));
        }

        String shape = p.getShapeTemplate() != null
                ? com.customdimensions.portal.PortalShape.PATTERN
                : com.customdimensions.portal.PortalShape.normalise(p.getShapeName());
        String facing = orientation(p, shape);
        b.append(prow("shape", "<span class='pv-name'>" + escape(shape) + "</span>"
                + pnote(shapeBlurb(shape))));
        b.append(shapeGrid(shape, p));
        b.append(prow("orientation", "<span class='pv-name'>" + escape(facing)
                + "</span>" + pnote(orientationBlurb(facing))));
        if (p.centreBlock != null && !p.centreBlock.isBlank()) {
            b.append(prow("centre block", blocks(List.of(p.centreBlock))));
        }

        b.append(prow("look", look(p)));
        if (p.particleType != null && !p.particleType.isBlank()) {
            b.append(prow("particles", "<span class='pv-name'>" + escape(p.particleType)
                    + "</span>" + pnote("overrides the colour")));
        }
        b.append(prow("travel", fmt(p.scale == null ? 1.0 : p.scale) + "x scale &middot; "
                + (p.cooldown == null ? 40 : p.cooldown) + " tick cooldown"));
        b.append(prow("sounds", sound("ignite", p.getIgniteSound())
                + sound("enter", p.getEnterSound()) + sound("exit", p.getExitSound())));

        if (p.anchor != null) {
            b.append(prow("anchor", "<span class='pv-name'>" + anchorPos(p.anchor)
                    + "</span>" + pnote("every portal here lands at one place, leaving by "
                    + exitTargetLabel(p.anchor.getExit()))));
        }
        if (p.singleUse != null && Boolean.TRUE.equals(p.singleUse.enabled)) {
            int swaps = p.singleUse.decayMap == null ? 0 : p.singleUse.decayMap.size();
            String decay = swaps == 0 ? ""
                    : pnote(swaps + (swaps == 1 ? " decay swap" : " decay swaps"));
            b.append(prow("single use", "shuts " + p.singleUse.getDelaySeconds()
                    + "s after the first crossing &middot; frame breaks "
                    + escape(p.singleUse.getBreakMode()) + decay));
        }
        b.append(aura(p.aura));
        b.append(prow("immersive", immersive(p)));
        return b.append("</div>").toString();
    }

    /**
     * What lighting this portal costs, mirroring
     * {@code IgniterSpend.of(damageable, creative, consumesIgniter)}:
     * consuming beats damageable, and creative pays for nothing on every
     * path. A null {@code damageable} means the item was not resolved, and
     * the row then states both outcomes rather than choosing one.
     */
    private static String igniterCost(DimensionConfig.Portal p, Boolean damageable) {
        String cost;
        if (p.consumesIgniter()) {
            cost = "<span class='pv-name'>consumed</span>"
                    + pnote("the item is taken, not damaged");
        } else if (Boolean.TRUE.equals(damageable)) {
            cost = "<span class='pv-name'>1 damage</span>"
                    + pnote("durability, the vanilla flint-and-steel cost");
        } else if (Boolean.FALSE.equals(damageable)) {
            cost = "<span class='pv-name'>untouched</span>"
                    + pnote("no durability to spend");
        } else {
            cost = "<span class='pv-name'>damaged if it has durability, "
                    + "otherwise untouched</span>";
        }
        return cost + pnote("creative spends nothing");
    }

    /**
     * Copper frames oxidise, and an oxidation step can carry a built frame
     * out of the dimension that owns it — into another dimension's frame,
     * or out of every frame at once ({@code TROUBLESHOOTING.md#t85}). Wax
     * freezes the stage, so a waxed form never appears here.
     */
    private static String weathering(List<String> accepts, String slug,
                                     Map<String, String> frameOwners) {
        StringBuilder stays = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (String form : accepts) {
            String next = weathersTo(form);
            if (next == null) {
                continue;
            }
            if (accepts.contains(next)) {
                stays.append("<span class='pchip'>").append(escape(form)).append(" &rarr; ")
                        .append(escape(next)).append("</span>");
                continue;
            }
            String owner = frameOwners.get(next);
            boolean defects = owner != null && !owner.equals(slug);
            b.append(prow("weathers", "<span class='" + (defects ? "pv-bad" : "pv-name") + "'>"
                    + escape(form) + " &rarr; " + escape(next) + "</span>"
                    + pnote(defects
                            ? "which is " + escape(owner) + "'s frame, so a built portal "
                                    + "changes destination in the rain"
                            : "which this frame does not accept, so the portal is "
                                    + "no longer this portal once it oxidises")
                    + pnote("wax it to freeze the stage")));
        }
        if (stays.length() > 0) {
            b.append(prow("weathers", stays + pnote("all stages accepted here")));
        }
        return b.toString();
    }

    /**
     * The next oxidation stage of a copper block, or null when it has none.
     *
     * <p>Only the nine oxidisable families count: deriving a stage from the
     * name alone turns {@code raw_copper_block} — a real portal frame in
     * this pack — into {@code exposed_raw_copper_block}, which is not a
     * block. The plain family is irregular ({@code copper_block} weathers to
     * {@code exposed_copper}, not to {@code exposed_copper_block}).
     */
    static String weathersTo(String id) {
        if (id == null || id.startsWith("#") || !id.contains("copper")) {
            return null;
        }
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "minecraft:" : id.substring(0, colon + 1);
        String path = colon < 0 ? id : id.substring(colon + 1);
        if (path.startsWith("waxed_")) {
            return null;
        }
        switch (path) {
            case "copper_block":
                return ns + "exposed_copper";
            case "exposed_copper":
                return ns + "weathered_copper";
            case "weathered_copper":
                return ns + "oxidized_copper";
            default:
                break;
        }
        String base = path;
        String stage = "exposed_";
        if (path.startsWith("exposed_")) {
            base = path.substring("exposed_".length());
            stage = "weathered_";
        } else if (path.startsWith("weathered_")) {
            base = path.substring("weathered_".length());
            stage = "oxidized_";
        } else if (path.startsWith("oxidized_")) {
            return null;
        }
        return OXIDISABLE.contains(base) ? ns + stage + base : null;
    }

    /** The shaped copper families that oxidise; the plain block is handled above. */
    private static final Set<String> OXIDISABLE = Set.of(
            "cut_copper", "cut_copper_stairs", "cut_copper_slab", "chiseled_copper",
            "copper_grate", "copper_bulb", "copper_door", "copper_trapdoor");

    /**
     * The place block earns a row when it is a choice: an explicit setting,
     * a frame with several accepted forms, or a tag the mod cannot build
     * from. A single plain id builds itself and saying so twice is noise.
     */
    private static boolean placeWorthStating(DimensionConfig.Portal p, List<String> accepts) {
        if (p.framePlaceBlock != null && !p.framePlaceBlock.isBlank()) {
            return true;
        }
        return accepts.size() != 1 || accepts.get(0).startsWith("#");
    }

    /** Whether a frame takes one form or a choice of them — the thing a builder needs. */
    private static String formCount(List<String> forms) {
        if (forms.size() > 1) {
            return pnote("any of " + forms.size());
        }
        return pnote(forms.get(0).startsWith("#")
                ? "any block in this tag" : "the only form it accepts");
    }

    /** Label first, then the id — the row is read for which sound is which. */
    private static String sound(String when, String id) {
        return pnote(when) + "<span class='pv-name'>" + escape(id) + "</span>";
    }

    private static String colourGroup(DimensionConfig.Portal p) {
        String colour = p.getColorGroup();
        return colour == null ? "" : pnote("colour group " + escape(colour));
    }

    /** The portal's own colour, as a swatch beside the value that made it. */
    private static String look(DimensionConfig.Portal p) {
        StringBuilder b = new StringBuilder();
        if (p.color != null && !p.color.isBlank()) {
            String hex = p.color.startsWith("#") ? p.color : "#" + p.color;
            b.append("<span class='pswatch' style='background:").append(escape(hex))
                    .append("'></span><span class='pv-name'>").append(escape(p.color))
                    .append("</span>");
        }
        b.append("light level ").append(p.lightLevel == null ? 0 : p.lightLevel);
        return b.toString();
    }

    private static String shapeBlurb(String shape) {
        switch (shape) {
            case com.customdimensions.portal.PortalShape.DOOR:
                return "a 1 by 2 opening";
            case com.customdimensions.portal.PortalShape.DOORWAY:
                return "a 2 by 3 opening, the vanilla Nether one";
            case com.customdimensions.portal.PortalShape.END_EXIT:
                return "a flat ring on the ground, any footprint";
            case com.customdimensions.portal.PortalShape.END_GATEWAY:
                return "one block of air, ringed";
            case com.customdimensions.portal.PortalShape.PATTERN:
                return "the frame must match this template exactly";
            default:
                return "any frame-bounded opening up to 128 blocks";
        }
    }

    /** The orientation ignition really uses: the field, else the shape's, else any. */
    private static String orientation(DimensionConfig.Portal p, String shape) {
        if (p.orientation != null && !p.orientation.isBlank()) {
            return p.orientation.trim();
        }
        String implied = com.customdimensions.portal.PortalShape.impliedOrientation(shape);
        return implied != null ? implied : "any";
    }

    private static String orientationBlurb(String orientation) {
        switch (orientation) {
            case "vertical":
                return "standing, on either horizontal axis";
            case "vertical_x":
                return "standing, on the X axis only";
            case "vertical_z":
                return "standing, on the Z axis only";
            case "horizontal":
                return "flat on the ground, lit from above";
            default:
                return "standing or flat, whichever the build makes";
        }
    }

    /**
     * The shape as a shape. A preset's grid is the interior it demands
     * with the ring it needs around it; a pattern's is its own template.
     * Free-form and any-footprint shapes get no grid — a drawing of one
     * would state a size the config does not.
     */
    private static String shapeGrid(String shape, DimensionConfig.Portal p) {
        List<String> rows;
        Map<String, String> legend;
        if (com.customdimensions.portal.PortalShape.PATTERN.equals(shape)) {
            rows = p.getShapeTemplate();
            legend = p.getShapeLegend();
        } else {
            rows = presetGrid(shape);
            legend = Map.of("F", "frame", ".", "interior");
        }
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("<div class='pshape' role='img' aria-label='")
                .append(escape(shape)).append(" portal, ").append(rows.size())
                .append(" rows'>");
        for (String row : rows) {
            b.append("<div class='pshape-row'>");
            for (int i = 0; i < row.length(); i++) {
                String role = legend.get(String.valueOf(row.charAt(i)));
                String kind = "frame".equals(role) ? "frame"
                        : ("interior".equals(role) ? "interior" : "any");
                b.append("<span class='pcell pcell-").append(kind).append("'></span>");
            }
            b.append("</div>");
        }
        b.append("<div class='pshape-key'><span class='pkey-swatch pkey-frame'></span>frame"
                + "<span class='pkey-swatch pkey-interior'></span>opening</div>");
        return b.append("</div>").toString();
    }

    /** A named preset's fixed geometry; null where the preset fixes none. */
    private static List<String> presetGrid(String shape) {
        switch (shape) {
            case com.customdimensions.portal.PortalShape.DOOR:
                return List.of("FFF", "F.F", "F.F", "FFF");
            case com.customdimensions.portal.PortalShape.DOORWAY:
                return List.of("FFFF", "F..F", "F..F", "F..F", "FFFF");
            case com.customdimensions.portal.PortalShape.END_GATEWAY:
                return List.of("FFF", "F.F", "FFF");
            default:
                return null;
        }
    }

    /**
     * What the aura does. Absent is not off: each side samples the other's
     * terrain at link time and leaks it through, which is the behaviour a
     * dimension gets for writing nothing.
     */
    private static String aura(DimensionConfig.Aura a) {
        if (a != null && Boolean.FALSE.equals(a.enabled)) {
            return prow("aura", "off");
        }
        if (a == null) {
            return prow("aura", "on" + pnote("leaks the far side's sampled terrain, "
                    + "converting natural ground within 8 blocks"));
        }
        StringBuilder v = new StringBuilder("on");
        v.append(pnote("eats " + escape(com.customdimensions.portal.AuraPolicy.normalise(a.subsume))));
        v.append(pnote((a.radius == null ? 8 : a.radius) + " block radius"));
        v.append(pnote("every " + (a.interval == null ? 40 : a.interval) + " ticks"));
        v.append(pnote((a.blocksPerPass == null ? 2 : a.blocksPerPass) + " blocks a pass"));
        int budget = a.budget == null ? 300 : a.budget;
        v.append(pnote(budget < 0 ? "no budget, endless" : budget + " block budget"));
        v.append(pnote(a.sides == null ? "both sides" : escape(a.sides) + " side"));
        if (a.fireChance != null && a.fireChance > 0) {
            v.append(pnote("sets fires"));
        }
        StringBuilder b = new StringBuilder(prow("aura", v.toString()));
        if (a.palette == null) {
            b.append(prow("aura emits", pnote("its own sampled terrain")));
        } else {
            b.append(prow("aura emits", a.palette.isEmpty()
                    ? pnote("nothing") : chips(a.palette)));
        }
        if (a.flora != null && !a.flora.isEmpty()) {
            b.append(prow("aura flora", chips(a.flora)));
        }
        if (a.trees != null && !a.trees.isEmpty()) {
            b.append(prow("aura trees", chips(a.trees)));
        }
        if (a.fluids != null && !a.fluids.isEmpty()) {
            b.append(prow("aura fluids", chips(a.fluids)));
        }
        if (a.conversions != null && !a.conversions.isEmpty()) {
            StringBuilder c = new StringBuilder();
            for (Map.Entry<String, String> e : a.conversions.entrySet()) {
                c.append("<span class='pchip'>").append(escape(e.getKey()))
                        .append(" &rarr; ").append(escape(e.getValue())).append("</span>");
            }
            b.append(prow("aura swaps", c.toString()));
        }
        return b.toString();
    }

    /** Immersive is on unless the config says otherwise, so state which. */
    private static String immersive(DimensionConfig.Portal p) {
        com.customdimensions.config.ImmersiveSettings s = p.getImmersiveSettings();
        if (s == null) {
            return "not immersive";
        }
        return "on" + pnote(s.previewDepth() + " blocks deep")
                + pnote(s.previewRadius() + " block padding")
                + pnote("refreshed every " + s.refreshInterval() + " ticks")
                + pnote("within " + s.activationRange() + " blocks")
                + pnote(s.audio() ? "far-side ambience" : "no audio")
                + pnote(s.entityPassthrough() ? "things cross" : "nothing crosses");
    }

    /**
     * The frames the mod builds and maintains itself. A dimension whose
     * only way in strands a player — an anchor or a single-use portal with
     * no exit portal — says so here, which is the boot-time safety warning
     * put where somebody choosing a seed will read it.
     */
    private static String waysOut(DimensionConfig def, List<DimensionConfig.Portal> all) {
        boolean oneWayIn = all.stream().anyMatch(p -> p.anchor != null
                || (p.singleUse != null && Boolean.TRUE.equals(p.singleUse.enabled)));
        DimensionConfig.ExitPortal exit = def.getExitPortal();
        DimensionConfig.ExitShrines shrines = def.getExitShrines();
        boolean anyShrines = shrines != null;
        if (exit == null && !anyShrines && !oneWayIn) {
            return "";
        }
        StringBuilder b = new StringBuilder("<div class='pways'>");
        b.append("<div class='portal-head'><span class='portal-id'>ways out</span></div>");
        if (def.hasExitPortal()) {
            int[] at = exit.getExplicitPos();
            String where = at == null ? "near spawn" : at[0] + ", " + at[1] + ", " + at[2];
            b.append(prow("exit portal", "<span class='pv-name'>" + escape(where)
                    + "</span>" + pnote("leads to " + exitTargetLabel(exit.getTargetMode()))));
        } else if (exit != null) {
            b.append(prow("exit portal", "disabled"));
        } else {
            b.append(prow("exit portal", "<span class='pv-bad'>none</span>"
                    + pnote("nothing here builds a way back")));
        }
        if (anyShrines) {
            b.append(prow("exit shrines", def.hasExitShrines()
                    ? "<span class='pv-name'>scattered</span>"
                            + pnote("leading to " + exitTargetLabel(shrines.getTargetMode()))
                    : "disabled"));
        }
        return b.append("</div>").toString();
    }

    /** A dimension link's canonical form ({@code dim!ns:slug!spawn}), read as words. */
    private static String exitTargetLabel(String canonical) {
        if (!canonical.startsWith("dim!")) {
            return escape(canonical);
        }
        String[] parts = canonical.split("!", 3);
        return parts.length == 3 ? escape(parts[1]) + " at " + escape(parts[2])
                : escape(canonical);
    }

    /** An anchor's own words: a written coordinate, or the dimension's spawn. */
    private static String anchorPos(DimensionConfig.Anchor a) {
        if (a.pos != null && a.pos.isJsonArray() && a.pos.getAsJsonArray().size() == 3) {
            return escape(a.pos.getAsJsonArray().get(0).getAsInt() + ", "
                    + a.pos.getAsJsonArray().get(1).getAsInt() + ", "
                    + a.pos.getAsJsonArray().get(2).getAsInt());
        }
        return "spawn";
    }

    private static String prow(String key, String value) {
        return "<div class='prow'><span class='pk'>" + escape(key) + "</span>"
                + "<span class='pv'>" + value + "</span></div>";
    }

    private static String pnote(String text) {
        return "<span class='pnote'>" + text + "</span>";
    }

    private static String blocks(List<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String id : ids) {
            b.append("<code class='pblock'>").append(escape(id)).append("</code>");
        }
        return b.toString();
    }

    private static String chips(List<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String id : ids) {
            b.append("<span class='pchip'>").append(escape(id)).append("</span>");
        }
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
