# Next steps — the working queue

Agreed with Pip 2026-07-23. Work top to bottom; each item links to the doc that specifies it. Update this file as items complete (mark done, move lessons into AGENTS/READMEs, delete the item's idea doc once its content is captured — the pattern used for the portal-concepts docs).

## 1. Custom world settings, Tiers 2–3

Spec: `vanilla-custom-world-settings.md` (Tier 1 already shipped).

- **Tier 2** (independent, small): `checkerboard` biome-source case; `superflat` custom `layers` + `flatBiome`. Each needs its seed-roll touch (`rollable()`, biome sampler) or a `seedRoll: {skip: true}` default.
- **Tier 3** (each lands TOGETHER with its Python-roller counterpart or candidate scores lie): per-biome multi-noise `parameters`, `settingsOverrides` whitelist (sea_level/default_block/default_fluid/ disable_mob_generation), per-structure spacing overrides via the `DimensionStructures` rebuild.

## 2. biomePatches

Spec: `vanilla-custom-world-settings.md` § Precision placement (the implementation sketch from the platform handoff is merged in there: PatchedBiomeSource, quart coords, CODEC, sampler parity, oracle). Guaranteed spawn biome at (0,0) deletes the spawn-filter lottery. Related but gated behind it: fixed structure placements (same section).

## 3. Exit shrines + dimension links & exit conditions

Spec: `exit-shrine-structure.md` (Part 1: jigsaw shrine structure, configurable shapes/variants; Part 2: exits targeting ANY dimension, and per-dimension exit conditions — void fall, death and death-by-cause redirection, action triggers like ender pearls and swimming; death not always final; peaceful dims always leave-at-will). Design principles and validator rules are in the doc.

## 4. Fork-config GUI

Spec: `fork-dimension-config-gui.md` + ready-to-run prompt in `fork-gui-implementation-prompt.md` (verified against code 2026-07-22). Pure Python/HTML in `scripts/seed/`.

## 5. Optional-mods hardening (last)

Spec: `optional-mods-hardening.md`. The open boot-breaker: removing Tectonic/Terralith breaks boots because the `adventure:wide`/ `compressed` presets reference their registries. Round 1 = self-contained noise presets; round 2 = removal-matrix smoke coverage.

## Parked / timing-dependent

- **`seed-group-rolling.md`** — do it immediately BEFORE the next full `seed-roll-all` sweep (that's when the ~2.4× measurement saving on the 31 grouped dims is realised); pointless as a standalone refactor.
