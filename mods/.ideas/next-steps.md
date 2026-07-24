# Next steps — the working queue

Queue cleared twice on 2026-07-24. Session 2 shipped optional-mods
hardening; session 3 (this one) shipped all three backlog items:
seed-viewer preset terrain fidelity, fixed structure placements
(`structures.mode`/`list`/`force`), and the client-pack-parity audit
(+ its two S-sized recommendations). Work top to bottom; one piece to
completion (implemented AND verified AND documented) before the next.
Delete an idea doc only after verifying its content is captured
(`git show 086bfed` is the worked example).

## Queue

(empty)

## Idea backlog (unscheduled)

- **Client pack parity R3/R4** (docs/client-pack-parity-audit.md): R3 =
  make build-modpack.sh auto-filter `_clientMods` from
  overlay/mods-remove.txt (M); R4 = client-side removal matrix in CI (L).
  R1 (warn-only parity lint in build-modpack.sh) and R2 (consumer README
  fork-sync checklist) shipped 2026-07-24.
- **elfydd sample-noise divergence investigation** — production's router
  climate values diverge from pure-vanilla evaluation of the same seed +
  same settings (proven with a c2me-free reference container; see
  mods/AGENTS.md § Structure placement lessons, last bullet). Prime
  suspect: c2me chunk-system/noise modules. Matters only for ABSOLUTE
  headless coordinate predictions; scoring-relative rolling is unaffected
  (shared bias). A/B: boot elfydd's stack minus c2me, same seed, compare
  sample-noise.
- Sweep scripts/seed/spike/14-non-overworld-render-quality.md for other
  render-quality limitations worth lifting into tasks (the preset-height
  one from that family is now shipped).
- **Deeper worldgen authoring** (survivors of the retired
  fixed-structure-placements.md — its placement feature shipped
  2026-07-24): custom biomes with own colours/features via jar-baked
  worldgen/biome JSON (medium; client-visible tints work); authored
  density functions for terrain shape at exact spots (hard — real
  worldgen authoring). Custom skyboxes beyond the three vanilla effects
  remain client-mod territory (out of scope).

## Decisions waiting on Pip

- **Next release**: pending on main since v3.7.0 — optional-mods
  hardening (self-contained presets, smoke removal matrix, ownership
  lint), exact preset terrain heights in the seed viewer, fixed structure
  placements + set filtering, client-parity lint + audit. When asked:
  `gh workflow run release.yml -f version=vX.Y.Z`, never
  `gh release create`; refresh the major tag after
  (`git fetch origin '+refs/tags/v3:refs/tags/v3'`).

## Standing state (2026-07-24, end of session 3)

- **elfydd**: stack DOWN, data wiped (world/maps/mod state — matches the
  production wipe below; the old overworld test-platform residue went
  with it). `.stack/current` → `v3-dev` (live repo);
  data/mods/customdimensions.jar is the fixed-placements build; overlay
  dimension configs intact (81 files), stored candidates removed.
  Session-temporary edits were reverted before the wipe (idleUnloadMinutes
  5, oracle force/mode overlay edits re-staged). Next `./dev up` re-seeds
  config and boots fresh.
- **Verification highlights**: preset_terrain.py exact vs a c2me-free
  vanilla rig (36/36 probes, both presets, ± seeds); fixed placements
  proven by boot-log + mode=none locate oracle; `/locate` is
  first-in-ring-order across sets (vanilla quirk, documented).
- **PRODUCTION IS OFF AND WIPED** (2026-07-24, Pip's instruction, restic
  snapshot `efccdb69` taken first): all containers stopped; world, maps
  (bluemap + unmined), player data, LuckPerms user data, portal_links,
  fingerprints, chunky tasks all deleted. Next full deploy (or manual
  start) regenerates from config. Local elfydd data wiped identically;
  stored candidates removed (template + elfydd overlay); Pip is running
  a fresh dimension-configuration + seed-roll pass and will commit it.
- **Awaiting a real player on production** (after relaunch): the
  `respawnAt` death redirect and the first organic exit-shrine
  encounter.
- **Deferred**: fork-config GUI screenshot pass (browser session); GUI
  form fields for structures.mode/list/force (validator untouched —
  fields pass through config files only for now).
