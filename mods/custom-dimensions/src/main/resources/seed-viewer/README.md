# Seed viewer front-end

The browser interface for picking a dimension's seed: `template.html` plus the
scripts and stylesheets in `web/`. It has a filter bar, search, sort, a scatter
view and a card per dimension.

`ViewerPage.render` (`java/com/customdimensions/web/ViewerPage.java`) fills four
placeholders in the template — `{{DIMENSIONS_HTML}}`, `{{FAMILY_BUTTONS}}`,
`{{MOOD_OPTIONS}}`, `{{SCORE_THRESHOLD}}` — and `SeedServer` serves the result at
`/`, with everything in `web/` at `/assets/<name>`. Both read from the jar via
`getResourceAsStream`, so a change here is a mod rebuild. `ViewerTemplateTest`
fails the build when the template names a placeholder `render` does not fill —
an unwired one renders literally and nothing else complains.

`app.js` reads everything it filters and sorts on from `dataset.*` attributes on
the cards — `dim`, `name`, `family`, `type`, `mood`, `score`, `seed`, `radius`,
`cands`, `hires`, `hiresCoverage`, `low`, `borderDiameter`, `borderScale`,
`dimScale`, `shortlisted`, `hidden`. Anything that emits cards carrying those
attributes gets the whole interface working.

## The stylesheets

| File | What it is |
| --- | --- |
| `web/app.css` | Tailwind v4 **source**. Not served, and excluded from the jar. |
| `web/app.built.css` | What `build-viewer-css.sh` compiles it to. This is what the template links. |
| `web/criteria.css` | Plain CSS for the scorecard reasoning. No build step. |
| `web/structures.css` | Plain CSS for the structures panel and map markers. No build step. |

Run `../../../../build-viewer-css.sh` (at the mod root) after editing `app.css`,
and commit `app.built.css` with the change. `mod-build.yml` fails the build when
the two disagree. Nothing else feeds that build: `app.css` declares
`source(none)` and names every utility it uses in an `@apply`, so no markup is
scanned and editing this template, the scripts or `ViewerPage.java` cannot
change the stylesheet.

**Utility classes do not work in the markup.** Tailwind generates only what an
`@apply` asks for, so `class="mt-4"` on an element produces no rule. Give the
element a component class and style it in `app.css` (or `criteria.css` /
`structures.css`) instead. This is why class names can be assembled at runtime
— `'db-' + severity`, `'ef-group' + muted` — without anything being purged.
