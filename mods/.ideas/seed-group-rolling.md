# Seed group rolling — measure once per generation fingerprint

Pip's observation (2026-07-23): many dims are "same world, different curated taste" — identical generation settings, differing only by wants/shuns/spawn filters. Roll seeds for the GROUP once, then assign candidates to member dims by fit. Analysis confirms it's real.

## The invariant that makes it work (and its hard edge)

Two dimensions can share a seed's measurements **iff their generation-affecting config is byte-identical**: `type`, `noiseSettings`, the full biome list (it builds the biome source — one biome's difference re-deals the whole layout), `structureDensity`, the peaceful overlay (`hostileSpawning` drops structure sets), worldgen `environment` fields (minY/height/logicalHeight/coordinateScale), and `borders.generation` (measurement radius). Everything else — `seedRoll` (mood/wants/shuns/spawnFilter), portal, difficulty multipliers, description, colours — is scoring or runtime and shares freely.

**Hard edge**: measurements never transfer across differing biome lists, even "similar" ones. Same-or-nothing. The original idea's "re-group to closest match" step is only valid WITHIN an exact fingerprint group.

## Measured payoff (2026-07-23, 78 custom dims)

55 unique generation fingerprints; **8 groups covering 31 dims**:

| Group                    | Members                                                                                  |
| ------------------------ | ---------------------------------------------------------------------------------------- |
| nether default (6)       | basalt_spires, bloodroot_wastes, buried_age, furnace_halls, molten_flats, twisted_groves |
| overworld default (5)    | ashgrove, darkpine_depths, dripping_pines, greywoods, roothold                           |
| overworld compressed (5) | crystal_vale, needlefall, rosebluff, scorched_mesa, stonemantle                          |
| nether dense (4)         | blackstone_keep, blighted_maw, obsidian_sanctum, weeping_vault                           |
| overworld wide (4)       | chalk_meadows, greenreach, verdant_hollow, whitestone_ford                               |
| overworld sparse (3)     | claymarsh, gritlands, miredeep                                                           |
| nether dense (2)         | forged_depths, gilded_pit                                                                |
| overworld wide (2)       | frozen_strait, shallows                                                                  |

31 dims → 8 measurement targets ≈ 2.4× less measurement work (the expensive half: worker boots, locate batteries) for that cohort. The other 47 dims have unique fingerprints (mostly per-dim biome lists) and are untouched by this.

## Design

1. **Fingerprint function** (pure, next to `build_profile` in `dimension_profiles.py`): generation-relevant config → canonical string/hash. Unit-test that seedRoll/portal/difficulty changes do NOT change it and biome/type/density changes DO.
2. **Bank keyed by fingerprint, not dim.** `measurements.csv` rows gain a fingerprint column; existing rows can be re-keyed retroactively from config (fingerprints are derivable — no re-rolling needed).
3. **Roll-all dedupes targets**: one measurement pass per fingerprint, candidate count scaled up for bigger groups (a 6-dim group wants ~6× the accepted candidates of a singleton).
4. **Assignment at report time** (cheap, offline, re-runnable): score every banked seed of a group against EVERY member's seedRoll profile. Fast pass = spawnFilter against the banked spawn-biome sample (the "quick refine"); full pass = the existing scoring. Assign injectively — two dims with the same fingerprint AND the same seed are literal world clones, so winners must be distinct seeds — greedy best-fit is fine at these sizes (≤6 members); revisit with a proper assignment solve only if groups grow.
5. **Fingerprint drift**: any config change that alters a dim's fingerprint invalidates its group membership, not the bank — the dim just re-keys to its new fingerprint (possibly a new singleton). Warn in the report when a picked winner's fingerprint no longer matches the config it was measured under (same tone as the mod's fingerprint drift warning).

## Interplay / when to do it

- Do it BEFORE the next full `seed-roll-all` sweep — that's when the saving is realised; as a standalone refactor it delivers nothing.
- **biomePatches** (vanilla-custom-world-settings.md) changes the calculus: a guaranteed spawn biome deletes the spawn-filter lottery, which cuts rejection rates far harder than grouping cuts measurement. If biomePatches lands first, grouping still helps (structure locates remain the cost) but its priority drops. Patches are part of the fingerprint (they alter the biome source) — group members must share them exactly.
- Priority: below the outstanding queue (fork GUI, optional-mods hardening) unless a full re-roll is imminent.
