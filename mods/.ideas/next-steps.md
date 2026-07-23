# Next steps — the working queue

Agreed with Pip 2026-07-23. Work top to bottom; each item links to the doc that specifies it. Update this file as items complete (mark done, move lessons into AGENTS/READMEs, delete the item's idea doc once its content is captured — the pattern used for the portal-concepts docs).

## 1. Custom world settings, Tiers 2–3 — DONE 2026-07-23

Spec: `vanilla-custom-world-settings.md` (Tiers 1–3 shipped; only the
precision-placement section — item 2 below — remains from that doc).

- **Tier 2**: `checkerboard` (+`checkerboardScale`, `CheckerboardBiomeSampler` parity, live-verified probe-for-probe), `superflat` custom `layers` + `flatBiome`, `seedRoll: {skip: true}` in mod schema + `rollable()`.
- **Tier 3**: `settingsOverrides` whitelist, per-biome `parameters` (object-form biomes entries), per-set `structures.spacing` — each landed with its roller counterpart (`build_mixed_entries` param_overrides, seed_worker fluid check, tier-1 spacing maths) and live-verified.

## 2. biomePatches — DONE 2026-07-23

Spec: `vanilla-custom-world-settings.md` § Precision placement. Shipped
beyond the original sketch after design discussion with Pip: three modes
(stamp / clipped swap via `replace` / global swap via `scope: "global"`
with explicit target or area-selector), `shape: circle|square`, `blend`
edge jitter (bit-mirrored value noise in `PatchedBiomeSampler`).
Codec-registered `customdimensions:patched`; level.dat round-trip and a
codec evolution verified live; all modes oracle-verified. Guaranteed
spawn biome at (0,0) now deletes the spawn-filter lottery. Still gated
behind this section: **fixed structure placements** (same spec section —
next candidate alongside item 3).

## 3. Exit shrines + dimension links & exit conditions — DONE 2026-07-23

Spec: `exit-shrine-structure.md` (both parts shipped; doc carries the
verified-status summaries).

- **Part 2**: ExitTarget descriptors on every exit surface, `exits` trigger block (void/death/death:cause/death:mob/enderPearl/fallFrom), validator rules, bot-verified live (respawnAt awaits first real-player death — mixin applies clean).
- **Part 1**: `adventure:exit_shrine` jigsaw (jar datapack; `scripts/gen-exit-shrine.py` generates the NBT), beacon-marked frames self-register on chunk load (rotation-aware), frequency-gated set (0.001 shipped, x1000 for opted-in dims — never leaks into base worlds), `exit_shrine` STRUCTS entry + tier-1 frequency parity. Bot-verified: generation at spacing, detection under jigsaw rotation, traversal home.

## 4. Seed group rolling

Spec: `seed-group-rolling.md`. Ordered here by Pip (2026-07-23) so it
lands BEFORE the next full `seed-roll-all` sweep — that's when the
~2.4× measurement saving on the 31 grouped dims is realised.

## 5. Further portal customisations

Spec: `further-portal-customisations.md` (researched + written
2026-07-23, code-anchored to the real seams). Tiered: FrameMatcher
abstraction + block tags + orientation control are cheap; door/doorway/
end-exit shapes and per-part materials are medium; end_gateway
single-block teleporters + pattern-template grammar are deep. Work the
tiers in order; every shape needs its own bot recipe (§6 of the doc).

## 6. Fork-config GUI

Spec: `fork-dimension-config-gui.md`. Pure Python/HTML in
`scripts/seed/`. NOTE: the old reference to
`fork-gui-implementation-prompt.md` is STALE — that file was deleted in
the `0dc30dc` ideas tidy-up; its content was folded into
`fork-dimension-config-gui.md` (verify coverage against `git show
0dc30dc` before starting, the worked example for absorbed docs).

## 7. Optional-mods hardening (last)

Spec: `optional-mods-hardening.md`. The open boot-breaker: removing Tectonic/Terralith breaks boots because the `adventure:wide`/ `compressed` presets reference their registries. Round 1 = self-contained noise presets; round 2 = removal-matrix smoke coverage.

## Running-start notes for the next agent (accrued 2026-07-23)

Verification-loop traps hit THIS session (beyond what AGENTS documents):

- **c2me re-patch before EVERY `docker stop/start`** — now in
  mods/AGENTS.md §2. Three consecutive cycles ran unpatched here before
  it was caught. The idempotent snippet lives in `dev-up.sh` (search
  `useDensityFunctionCompiler`).
- **Chunk generation from RCON can wedge the main thread permanently**
  (Epic Dungeons `epic:chests/DungeonZombie` invalid loot id + c2me —
  AGENTS known-issues). Run any forceload/if-block over ungenerated
  chunks as a BACKGROUND command with a timeout; recovery is
  `docker stop -t 90 mc && docker start mc`.
- **`docker exec -i` eats your loop's stdin** — pipe-driven RCON loops
  silently stop after one iteration; append `</dev/null` to every
  docker exec inside a `while read` loop.
- **zsh does not word-split unquoted vars** — `set -- $probe` patterns
  from bash break; use `read -r a b c <<< "$str"`.
- **`unzip -l` with a glob matching two files** treats the second as a
  member filter and lists nothing — always name the jar exactly.
- **`locate biome` samples on a 32-block horizontal grid** — distances
  quantise; classify inside/outside rather than expecting exact 0s near
  edges.
- **Python sampler parity is REGION-level** — large regions and
  half-plane parameter tests match probe-for-probe; sliver biomes
  (sparse_jungle etc.) land within an approximation envelope. Server
  measurement stays ground truth; don't chase point mismatches.
- **Fixture-dim lifecycle takes TWO restarts to fully clear** —
  level.dat re-creates deleted fixture dims for one boot (orphan
  reconciliation unloads them; the registry entry clears the restart
  after). Fixture cleanup = delete config + world dir + fingerprints
  entry, then expect one boot of orphan-unload chatter.
- **elfydd's stack `current` → `v3-dev` symlinks straight to this repo**
  — seed-script changes are live locally without any sync step.
- **Bot recipes**: `gamemode survival Bot` before `/damage` (spawns
  creative-ish/invulnerable); carpet fake players never respawn, so the
  `respawnAt` exit action is still awaiting its first real-player death
  as live confirmation; `touch /data/.skip-pause` before bot sessions
  and delete it in cleanup.
- **Structure-set extraction cache**: `.seedtest/.structure_sets` won't
  contain `adventure:exit_shrines` until the next extraction run —
  roller work touching shrine placement should re-extract first.
