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

## 3. Exit shrines + dimension links & exit conditions

Spec: `exit-shrine-structure.md`.

- **Part 2 (dimension links + exit conditions) — DONE 2026-07-23**: ExitTarget descriptors on every exit surface, `exits` trigger block (void/death/death:cause/death:mob/enderPearl/fallFrom), validator rules, bot-verified live (respawnAt awaits first real-player death — mixin applies clean).
- **Part 1 (jigsaw shrine structure)** — still open: jar-datapack `adventure:exit_shrine` structure + set + NBT templates, shrine frames registering as exit zones, structureDensity exemption, `exit_shrine` seed-roll want.

## 4. Fork-config GUI

Spec: `fork-dimension-config-gui.md` + ready-to-run prompt in `fork-gui-implementation-prompt.md` (verified against code 2026-07-22). Pure Python/HTML in `scripts/seed/`.

## 5. Optional-mods hardening (last)

Spec: `optional-mods-hardening.md`. The open boot-breaker: removing Tectonic/Terralith breaks boots because the `adventure:wide`/ `compressed` presets reference their registries. Round 1 = self-contained noise presets; round 2 = removal-matrix smoke coverage.

## Parked / timing-dependent

- **`seed-group-rolling.md`** — do it immediately BEFORE the next full `seed-roll-all` sweep (that's when the ~2.4× measurement saving on the 31 grouped dims is realised); pointless as a standalone refactor.
