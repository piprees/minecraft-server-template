# Next steps — the working queue

Reset 2026-07-24 after the portals/auras/GUI session (items 1–6 of the
previous queue all shipped; their history lives in git — the last full
status snapshot is commit `8cdeb64`'s version of this file). Work top to
bottom; one piece to completion (implemented AND verified AND documented)
before the next. Delete an idea doc only after verifying its content is
captured (`git show 086bfed` is the worked example).

## 1. Optional-mods hardening

Spec: `optional-mods-hardening.md` (read in full — it carries a CRITICAL
2026-07-24 amendment: the noise-preset closure must ship under the
ORIGINAL `tectonic:`/`terralith:` ids, never renamed into the adventure
namespace, because vanilla seeds noises by hashing the id string and a
rename shifts terrain on every existing world).

> **Session prompt:** You're working in
> `/Users/pip/Projects/minecraft-server-template`; the local consumer for
> verification is `~/Projects/elfydd` (its `.stack/current` symlinks
> `v3-dev` → this repo, so seed-script edits are live; its
> `data/mods/customdimensions.jar` runs an unreleased build). Read
> AGENTS.md and mods/AGENTS.md in full, then
> `mods/.ideas/optional-mods-hardening.md` including the amendment.
> Round 1: extend `scripts/gen-terrain-presets.py` to resolve the pinned
> Tectonic + Terralith jars (pins in `config/modrinth-mods.txt`), walk
> the reference closure from the `adventure:wide`/`compressed` settings
> (every `"noise"` field, `shift/shift_a/shift_b` argument, and
> density-function reference), and emit byte-identical same-id copies
> into the custom-dimensions jar datapack. Success: with Tectonic and
> Terralith REMOVED the server boots and `adventure:wide`/`compressed`
> dims generate; with them PRESENT generation is bit-identical to today
> (locate/biome oracle on a fixture dim, same seed, before/after — re-run
> the c2me DFC re-patch before every restart, snippet in dev-up.sh).
> Full verification loop for the mod rebuild (artefact checks → install
> on elfydd → boot). Round 2: a removal-matrix smoke variant in CI
> (representative `overlay/mods-remove.txt`: when-dungeons-arise +
> dungeons-and-taverns + one YUNG mod + Tectonic + Terralith) asserting a
> clean boot and a clean `/locate` failure for a removed set. Round 3
> (cheap): the ownership.json lint from the spec. One round to completion
> before the next; fold lessons into mods/AGENTS.md and update this
> file. Do not cut a release — that's Pip's call.

## Idea backlog (unscheduled)

- `fixed-structure-placements.md` — the last precision-placement piece
  (exact structure at an exact spot; two routes sketched with
  implementation notes). Fingerprint corollary applies.

## Decisions waiting on Pip

- **Release**: nine commits are pending on main since v3.6.0 —
  `fc27767` (seed-group rolling), `67e93dc` (map fixes), `f22423a`
  (portals Tier 1 + NetherPortalProtectionMixin), `b33dfe2` (shapes),
  `c410cf2` (per-part materials), `aaf101e` (portal auras), `4a77471`
  (pattern + end_gateway), `2c0ce24` (shrine residuals), `c0d2848`
  (fork-config GUI). The protection fix is player-facing on production
  (return portals silently dying to netherportalspread) — lean quick.
  When asked: `gh workflow run release.yml -f version=vX.Y.Z`, never
  `gh release create`; refresh the major tag after.
- **netherportalspread retirement**: with auras shipped, two spread
  engines fight around the same portals and it eats custom arrival
  frames (mods/AGENTS documents the trap). Remove it (or zero its
  spread) and ship its behaviour as an opt-in aura preset
  (`conversions` + `fireChance`) for nether-y dims.

## Standing state (2026-07-24)

- **elfydd**: healthy, fixture-free (level.dat scrubbed — procedure now
  in AGENTS.md Dimension lifecycle traps), `.stack/current` → `v3-dev`
  (live repo), unreleased customdimensions.jar installed. Cosmetic
  residue: test platforms/frames in the overworld at x≈2998–3186,
  z≈2995–3205, y149–154, plus a registered source gateway at
  (3160,150,3000) — functional, harmless, clean up only if in the way.
- **Awaiting a real player on production**: the `respawnAt` death
  redirect and the first organic exit-shrine encounter (carpet bots
  can't respawn).
- **Deferred**: the fork-config GUI's 900px/600px screenshot pass
  (Chrome extension was offline headlessly) — do it opportunistically in
  a browser-enabled session.
