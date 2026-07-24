# Next steps — the working queue

Queue cleared 2026-07-24 (second session of the day): optional-mods
hardening (item 1 of the post-portals queue) shipped in full — all three
rounds implemented, verified, and documented, and the idea doc deleted
after capture (audit table → docs/customisation.md § Removing default
mods; design → mods/custom-dimensions/README.md § Noise presets; lessons
→ mods/AGENTS.md § Worldgen self-containment rules; open remnants →
backlog below). Work top to bottom; one piece to completion (implemented
AND verified AND documented) before the next. Delete an idea doc only
after verifying its content is captured (`git show 086bfed` is the
worked example).

## Queue

(empty — pull from the backlog below or wait for Pip)

## Idea backlog (unscheduled)

- `fixed-structure-placements.md` — the last precision-placement piece
  (exact structure at an exact spot; two routes sketched with
  implementation notes). Fingerprint corollary applies.
- **Seed-viewer terrain-height fidelity for preset dims** — the viewer's
  `TerrainEvaluator` (scripts/seed/terrain_height.py) applies Terralith's
  overworld offset spline to ALL overworld-family dims, including
  `adventure:wide`/`compressed` dims whose real splines differ (Terratonic
  graph + preset constants). Since the hardening work the preset DF JSONs
  live IN-REPO fully inlined
  (mods/custom-dimensions/src/main/resources/data/adventure/worldgen/),
  so per-preset spline extraction needs no jar walk: extract offset/factor
  from `density_function/{wide,compressed}/tectonic/...`, key
  terrain_splines.json by noiseSettings id, fall back to the Terralith
  spline for plain-overworld dims. Viewer-only fidelity — measurements
  bank real server output and are unaffected. While in there, sweep
  scripts/seed/spike/14-non-overworld-render-quality.md for other
  render-quality limitations worth lifting into tasks.
- **Client pack parity** — client packs are consumer-forked, a separate
  system from overlay removals; never audited for mod-removal safety
  (noted at the end of docs/customisation.md § Removing default mods).

## Decisions waiting on Pip

- **Next release**: v3.7.0 shipped 2026-07-24 (portal/aura/GUI arc +
  netherportalspread retirement). The optional-mods hardening work is now
  pending on main for the NEXT release: self-contained noise presets in
  the customdimensions jar, the smoke removal matrix, the ownership lint.
  When asked: `gh workflow run release.yml -f version=vX.Y.Z`, never
  `gh release create`; refresh the major tag after
  (`git fetch origin '+refs/tags/v3:refs/tags/v3'`).

## Standing state (2026-07-24, post-hardening session)

- **elfydd**: healthy (Restarts=0), running the unreleased HARDENED
  customdimensions.jar — locate-oracle verified bit-identical against the
  previous build (26/26 probes across both preset dims). `.stack/current`
  → `v3-dev` (live repo). Cosmetic residue unchanged: test
  platforms/frames in the overworld at x≈2998–3186, z≈2995–3205,
  y149–154, plus a registered source gateway at (3160,150,3000) —
  functional, harmless, clean up only if in the way.
- **CI**: smoke-test.yml is now a 2-leg matrix (default/removal);
  release.yml gates on both. The removal leg is the regression net for
  the "every default mod is removable" promise — it also exercises
  filter-datapacks.py, which CI never ran before.
- **Awaiting a real player on production**: the `respawnAt` death
  redirect and the first organic exit-shrine encounter (carpet bots
  can't respawn).
- **Deferred**: the fork-config GUI's 900px/600px screenshot pass
  (Chrome extension was offline headlessly) — do it opportunistically in
  a browser-enabled session.
