# Seed viewer front-end

The browser interface for picking a dimension's seed: `template.html` plus the
four scripts in `web/`. It has a filter bar, search, sort, a scatter view and a
card per dimension.

`app.js` reads everything it filters and sorts on from `dataset.*` attributes on
the cards — `dim`, `name`, `family`, `type`, `mood`, `score`, `seed`, `radius`,
`cands`, `hires`, `hiresCoverage`, `low`, `borderDiameter`, `borderScale`,
`dimScale`, `shortlisted`, `hidden`. Anything that emits cards carrying those
attributes gets the whole interface working.

The template has five placeholders a generator fills: `{{DIMENSIONS_HTML}}`,
`{{FAMILY_BUTTONS}}`, `{{TYPE_OPTIONS}}`, `{{MOOD_OPTIONS}}`, `{{SUMMARY_STATS}}`.

## What does not work yet

There is no generator. Nothing writes an `index.html` from
`.seed-rolling/candidates/{inputHash}/{ns}__{slug}/`, so the renders beside those
candidate files cannot be browsed.

The action controls in `app.js` — roll, pick, edit-config, create-dimension —
call HTTP endpoints (`/job/`, `/fork-schema`, `/dim-config`, `/pick`, `/reroll`)
that no longer exist. A static page must hide them or replace each with the
`./dev` command it corresponds to.
