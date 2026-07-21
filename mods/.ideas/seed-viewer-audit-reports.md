# Seed Viewer — Audit & Critique Reports (2026-07-21)

Three reports from impeccable agents evaluating `scripts/seed/viewer_template.html`
and `scripts/seed/score-dimensions.py` (HTML generation).

---

## Report 1: Technical Audit (Rei) — Score: 8/20

### Audit Health Score

| # | Dimension | Score | Key Finding |
|---|---|---|---|
| 1 | Accessibility | 1/4 | Interactive divs with no keyboard support; contrast failures (#777 on dark = 3.7:1); no `lang` attribute; no ARIA on lightbox |
| 2 | Performance | 2/4 | 770+ images with no lazy loading; DOM cloning in ungrouped mode; 10s polling creates uncached requests |
| 3 | Responsive Design | 1/4 | Fixed 5-column candidate grid unusable on mobile; zero `@media` breakpoints; touch targets too small (<24px) |
| 4 | Theming | 1/4 | Every colour is a hardcoded hex literal; zero CSS custom properties; dark-mode only |
| 5 | Anti-Patterns | 3/4 | Clean — no AI slop. Main issue is div-based interactivity without button semantics |

### Anti-Patterns Verdict

**Pass.** Does not look AI-generated. Utilitarian, developer-built, functional. Restrained colour palette with genuine personality (muted blue-grey, gold winners, red/amber flags). Reads as "developer built a tool for themselves."

### Priority Fixes

#### P0 — Blocking

1. **No keyboard navigation for cards** — `.dim-card` elements are `<div>` with `cursor: pointer` but no `tabindex`, `role="button"`, or keydown handler. Keyboard users can't operate the core UI.
   - Fix: `tabindex="0"` + `role="button"` + keydown listener for Enter/Space.

2. **Fixed 5-column candidate grid** — `repeat(5, 1fr)` at 320px = 51px columns. Unusable.
   - Fix: `repeat(auto-fill, minmax(140px, 1fr))` or breakpoints.

#### P1 — Major

3. **No `<html lang="en">`** — Screen readers and translation tools need this.
4. **Contrast failure: `#777` on dark backgrounds** — 3.7:1 ratio. WCAG AA requires 4.5:1.
   - Fix: Lighten to `#999` or `#9aa`.
5. **770+ images with no lazy loading** — All fetched on page load.
   - Fix: `loading="lazy"` on all `<img>` tags.
6. **DOM cloning in `buildUngrouped()`** — Clones every candidate card, duplicating memory.
   - Fix: DocumentFragment or move originals instead of cloning.
7. **Zero CSS custom properties** — ~25 distinct colours as raw hex. No theming possible.
   - Fix: Extract to `:root` custom properties (~12 tokens).
8. **Touch targets too small** — `.cand-toggle` ~10px, action buttons ~14px. WCAG requires 24px minimum.
   - Fix: `min-height: 44px` on interactive elements.

#### P2 — Minor

9. **No focus indicators** on custom interactive elements.
10. **No ARIA on lightbox** — No `role="dialog"`, no focus management.
11. **`applyState()` layout thrashing** — Individual `appendChild` calls trigger reflows.
12. **Fixed-width search input** — `style="width:12rem"` could overflow.
13. **`.expanded img.winner-img { width: 200px; }`** — Fixed px, doesn't scale.

#### P3 — Polish

14. Badge text contrast borderline (4.3:1, just under 4.5:1 AA threshold).
15. `color-scheme: dark` with no light alternative.
16. Lightbox normal→hires flash.

### Positive Findings

- Native `<dialog>` for create-dimension modal (free focus trapping + Escape)
- Event delegation on grid (efficient for 770+ cards)
- Responsive dimension grid (`auto-fill, minmax(240px, 1fr)`)
- Sticky filter bar
- Hash-based state persistence (survives reload, shareable URLs)
- Escape key with correct priority ordering
- Semantic form elements in filter bar
- Single-file, zero dependencies

---

## Report 2: UX Design Critique (Kira) — Score: 18/40

### Design Health Score (Nielsen's 10 Heuristics)

| # | Heuristic | Score | Key Issue |
|---|---|---|---|
| 1 | Visibility of System Status | 2 | No overall progress indicator — how many dims have winners? |
| 2 | Match System / Real World | 2 | "Namesake" is opaque jargon. "N30 V25 T25 S20" is cryptic. |
| 3 | User Control and Freedom | 3 | Good escape affordances, hash state persistence |
| 4 | Consistency and Standards | 2 | Three different expansion patterns (dim expand, cand overlay, lightbox) |
| 5 | Error Prevention | 2 | "Make Winner" (highest stakes) has zero confirmation |
| 6 | Recognition vs Recall | 1 | **Critical failure.** No context for what scores mean, no legend |
| 7 | Flexibility and Efficiency | 3 | Good filter surface, missing keyboard nav and bulk actions |
| 8 | Aesthetic and Minimalist Design | 2 | Card face clean, expanded view dumps 80+ data points at once |
| 9 | Error Recovery | 1 | "pick (failed)" with no explanation |
| 10 | Help and Documentation | 0 | No onboarding, no tooltips, no legend, no "what does this mean" |

### Anti-Patterns Verdict

**Pass.** Not AI-generated. Hand-optimised CSS, vanilla JS, genuine personality. "Developer built a tool for themselves" — what it lacks isn't taste, it's empathy for users who aren't the author.

### Key Insight

"The tool helps you SEE data but doesn't help you DECIDE. With 77 dimensions × 10 candidates, the primary task is decision-making under volume — and the interface treats every piece of information as equally important."

### Priority Issues

1. **[P0] Expanded view is an information avalanche** — 80+ data points hit the user at once with no guided path. Fix: two-tier structure (winner + toggle for alternatives). Reduce candidate grid from 5 to 3 columns.

2. **[P1] Score bars communicate nothing actionable** — Raw 0-1 values with no reference frame. Fix: weight bar widths by axis importance, colour per axis (green/amber/red), label with weight percentage, highlight weakest axis as "limiting factor."

3. **[P2] No task-flow guidance across 77 dimensions** — No distinction between "confirmed," "auto-selected," and "unreviewed." Fix: add reviewed/confirmed state, progress bar, default sort by "needs attention."

4. **[P3] Candidate comparison requires superhuman visual memory** — 3px bars, must hold values in memory to compare. Fix: table/list view, taller bars (8-10px), gridlines at 0.25/0.50/0.75.

5. **[P4] "More" overlay destroys context** — Covers the render image, can't see terrain AND scores simultaneously. Fix: show scores inline on card face, eliminating the need for "more."

### Cognitive Load Assessment

**Per dimension evaluation:** 3 context switches, ~40 decision points, 6-8 simultaneous working memory items, 2-12 clicks.
**Across 77 dimensions:** ~3,000 decision points, ~230 context switches, ~600 clicks. A full working day.

### Provocative Questions

1. What if the default view was a triage dashboard? (Needs attention / Review recommended / Done)
2. What if a scoring legend existed? (2 lines of HTML, saves every new user 15 minutes)
3. Could the tool auto-decide and only surface ambiguity? (Auto-accept >80, flag when gap <10)
4. Is 5-column candidate grid the right density? (3 columns = scores visible without overlay)
5. What if candidates could be pinned side-by-side for comparison?

### Persona Red Flags

- **Alex (Power User):** Wants bulk accept, keyboard shortcuts, table view. 5-column grid wastes space on 2560px.
- **Jordan (First-Timer):** No legend, no tooltips, cryptic weight notation. Would close the tab.
- **Riley (Stress Tester):** 0 candidates shows dead-end text. 100+ candidates limited to 10 with no pagination.

---

## Report 3: UX Copy Clarify (Sable) — Pending

Dispatched, awaiting results. Will cover: all button labels, filter labels, status text, data label rewrites, scoring legend, empty/error states.

---

## Implementation Status

### Done (committed)

- [x] P0 #3: Spawn coordinates shown on candidate cards
- [x] P0 #4: One-line blurb on compact dimension cards
- [x] P0 #1: Structure hit/miss table in candidate detail overlay
- [x] P0 #2: Terrain summary (relief, grain, water) in candidate detail

### Next priorities (from combined reports)

1. Responsive candidate grid (`auto-fill` instead of `repeat(5)`)
2. CSS custom properties for all colours
3. Contrast fixes (#777 → #999)
4. Keyboard support for interactive elements
5. Score legend at top of page
6. Scoring bar improvements (weighted widths, per-axis colour)
7. Copy rewrites (pending Sable's report)
8. Two-tier expanded view (winner prominent, alternatives toggle)
9. `loading="lazy"` on images
10. Focus indicators

---

## Report 3: UX Copy Clarify (Sable) — 34 Rewrites

### P0 — Blocking comprehension

1. Bar label `namesake` → `spawn biome` (display-name map, not key rename)
2. Weight notation `N30 V25 T25 S20` → `Score mix: spawn 30% · variety 25% · terrain 25% · structures 20%`
3. Raw scores `0.85` → `85%` (format `{:.0%}` instead of `{:.2f}`)
4. Add scoring legend at top of page (2-line explainer with colour key)

### P1 — High friction

5. `Make Winner` → `Use this seed`
6. `No candidates measured.` → `No seeds tested yet. Run ./dev seed-roll to start.`
7. `pick (failed)` → `Could not save — server not responding`
8-12. All error states: explain cause, suggest recovery
13. Remove confirm: add "and all its candidate data? This cannot be undone."
14. `Flagged` → `Below threshold`
15. `shun` → `avoid`
16. `Spawn filter` → `Target spawn biomes`
17. `Wants` → `Structures nearby` + add "blocks" to ranges

### P2 — Polish

18-34. Page title, heading, "cands" → "seeds tested", "Ungrouped" → "Flat view",
"Unshortlist" → "Remove from shortlist", radius units, spawn label, trophy/pin tooltips,
sort labels, dialog title, validation message, summary stats text.

### Proposed Scoring Legend

Compact block below summary: explains 4 axes in plain English + colour key (green/default/amber/red with thresholds).
