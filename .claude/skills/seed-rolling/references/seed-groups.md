---
title: Seed Groups
description: Generation fingerprints, generation_payload() field-by-field, fingerprint drift, and injective winner assignment within a seed-group — the mechanics behind shared-measurement rolling.
tags: [fingerprint, generation_payload, seed-groups, drift, winners, injective]
---

# Seed Groups

Many dimensions are "same world, different curated taste" — identical generation settings, differing only in `seedRoll` wants/shuns/mood/spawn filter, or in cosmetic fields like `portal`/`description`. Rolling each of those separately would measure the exact same seeds over and over. Seed-group rolling instead measures each seed **once per generation fingerprint** and shares the rows across every dimension with that fingerprint. Measured 2026-07-23 across 78 custom dims: 8 groups covering 31 dims, the biggest a 6-dim nether-default group — for a 5-member group, tier-2 measurement of the whole group costs roughly what measuring one member alone used to cost.

## The fingerprint

`dimension_profiles.generation_fingerprint(dim)` returns `sha256(json.dumps(generation_payload(dim), sort_keys=True))[:12]`, or `None` for base-world entries (`overworld`, `the_nether`, `the_end`, `paradise_lost` — these never group with anything). Two dimensions with the SAME fingerprint have byte-identical generation-affecting config and therefore generate the literal same world for any given seed.

### Candidate stamps and the drift check

Each banked candidate carries the fingerprint its measurements answer for, in one of two keys:

- `fingerprint` — the MEASURED stamp, written by `candidates.merge_rows` on the fold that supplies the candidate's measurements, and never rewritten afterwards. Empty shells (records setdefault'd by census/survey passes before measurements land) take theirs when the measurements arrive.
- `fingerprintAssumed` — for candidates measured before stamping existed: `candidates.ensure_fingerprints`, called by `persist_candidates` on every roll/rescore fold, stamps each measured-but-unstamped candidate ONCE with the fingerprint current at that fold. It is an assumption recorded as its own key, never forged into `fingerprint`: it cannot claim historical drift, but any generation-affecting change AFTER the fold reads as DRIFTED — which makes the drift check live for the whole bank instead of silently inert over legacy candidates.

Every drift read (`seed-status`, the finalise warnings) goes through `candidates.effective_fingerprint(cand)` — measured wins over assumed. Unit coverage: `test_candidates.py`.

## `generation_payload()` — field by field

The payload is the canonical "does this change what a seed generates, or what a measurement of it means" list. As of the current `dimension_profiles.py`, it includes:

| Field | Why it's generation-affecting |
| --- | --- |
| `type` | The terrain generator itself |
| `noiseSettings` | Terrain shape within the type |
| `biomes` (full ORDERED list, `[id, parameters]` pairs) | One biome's difference re-deals the WHOLE layout — order matters because foreign-biome round-robin follows config order (a `LinkedHashSet` mirror) |
| `structureDensity` | Rescales the whole structure placement calculator |
| `peaceful` (derived: `hostileSpawning is False`) | The peaceful overlay drops entire structure sets |
| `environment` (`minY`, `height`, `logicalHeight`, `coordinateScale` only — NOT ambient light/effects/bed rules, which are runtime) | Worldgen-relevant DimensionType fields |
| `generationBorder` (`borders.generation`) | The measurement radius itself |
| `checkerboardScale`, `layers`, `flatBiome` | Shape parameters for those dimension types |
| `settingsOverrides` | Explicit multi-noise interval overrides (Tier 3) |
| `biomePatches` | Fixed circular patches over the biome layout |
| `exitShrines` (boolean: `.enabled`) | Raises the shrine structure set's frequency from its shipped 0.001 to 1.0 |
| `structuresMode` (`structures.mode` + sorted `structures.list`) — **conditional**, only present when `structures.mode` is set | The organic-structure-set allow/reject/none filter changes what generates |
| `forcedStructures` (sorted `[structure, x, z]` triples) — **conditional**, only present when well-formed entries exist | `structures.force` places structures at fixed coordinates regardless of seed |
| `spacingOverrides` (`structures.spacing`) | Rescales placement frequency for named sets |
| `shrineSpacing` — **conditional**, only present when `exitShrines.enabled` is true AND `structures.spacing` has no explicit override for `adventure:exit_shrines` | See "the fingerprint corollary" below |

Everything NOT in this list — `seedRoll` itself, `portal`, difficulty multipliers, `description`, colours — is scoring or runtime metadata and shares freely across dimensions with otherwise-identical generation.

## The fingerprint corollary (read this before adding any new generation-affecting field)

**Any new field the mod grows that changes what a seed generates MUST be added to `generation_payload()`, or seed-group rolling silently lies.** Two dimensions can end up sharing a fingerprint (and therefore a seed's banked measurements) despite actually generating different worlds, and members of the same group end up assigned the SAME seed as a "distinct" winner — they'd be literal world clones without anyone noticing, because the roller has no way to know the field mattered.

**Worked instance (2026-07-24): derived shrine spacing.** `DimensionStructures` derives the exit-shrine structure set's spacing from the dimension's raw `borders.player` when `exitShrines.enabled` is true and no explicit `structures.spacing` override exists for `adventure:exit_shrines` (`spacing = clamp(border // 32, 12, 48)`, `separation = spacing // 2`). This makes `borders.player` — normally pure scoring metadata, shared freely — generation-affecting FOR THOSE DIMS SPECIFICALLY. The fix (`_derived_shrine_spacing()` in `dimension_profiles.py`) computes the derived spacing and adds it to the payload as `shrineSpacing`, but **only when `exitShrines.enabled` and no explicit override exists**. This conditional-add is deliberate: an unconditional new key would have changed the fingerprint of every EXISTING dimension (shrine or not) simultaneously, flagging every candidate store as DRIFTED in one release. Follow this pattern for any future addition: add the new field to the payload only under the condition where it actually matters, so pre-existing fingerprints for unaffected dimensions stay byte-stable.

The same conditional-add discipline applies to `structuresMode` and `forcedStructures` (2026-07-24, same principle) — both are only added to the payload when the corresponding config block is actually present and well-formed.

## Fingerprint drift

`generation_fingerprint()` re-keys whenever generation-affecting config changes. The candidate store (`candidates/{slug}.json`) stamps each NEW candidate with the fingerprint it was measured under (`merge_rows(..., fingerprint=fp)` in `candidates.py`, `fingerprint` set only for genuinely new seeds — an existing candidate keeps its original stamp forever, even across further config edits).

`./dev seed-status` and `finalise` both compare a winner's stamped fingerprint against the dimension's CURRENT `generation_fingerprint()`:

- **Match**: fine, nothing to do.
- **Mismatch**: `DRIFTED (generation config changed since measurement — re-roll)`. The banked measurements describe a world this config no longer generates. **Rescoring cannot fix this** — rescoring only recomputes the score arithmetic against banked rows; it can't un-measure a stale world. Only `./dev seed-roll --dims <slug>` (a genuine re-roll) produces valid data again.

This is a stronger warning than plain `STALE` (a config-hash mismatch with no fingerprint involved — e.g. you changed `seedRoll.wants` but not the generation), which `./dev seed-rescore` DOES fix for free, no re-rolling needed.

## Injective winner assignment within a group

Two members of the same fingerprint group choosing the SAME seed as their independent "best score" would be assigned literal world clones — that's not a bug in scoring, it's the geometry: same generation config, same seed, same world. `cmd_finalise()` in `score-dimensions.py` resolves this per group with a greedy best-fit:

1. **Pinned winners** (human picks from the viewer, `winnerPinned: true`) claim their seed first, in whatever order they're processed. If two PINNED winners in the same group land on the same seed, this is a genuine unresolvable conflict — the tool prints a `WARNING: pinned winners for X and Y share seed ...` and does NOT silently fix it; a human must re-pin one of them.
2. **Unpinned members** are then processed in best-score order. Each takes its own top-ranked seed if unclaimed; if that seed is already taken by an earlier member, it walks down its OWN ranking to the next unclaimed seed and prints `group assignment: <name> takes <seed> (score ...; top seed ... already won by ...)`.
3. If a member's entire ranked candidate list is exhausted without finding an unclaimed seed, it prints `WARNING: no distinct seed left for <name> ... keeping shared seed ... (world clone of ...); roll more candidates` and keeps the shared seed rather than crashing — the fix is to roll more candidates for that group, not to treat the warning as fatal.

This only runs for groups of 2+ members sharing a non-`None` fingerprint; singleton dimensions and base-world entries (`fp is None`) are never subject to it.

## Hard edge: measurements never transfer across differing biome lists

Even "similar" biome lists don't share measurements — the biome list is part of the payload in full, ordered, with per-biome parameters. Same-or-nothing: either the fingerprint matches exactly, or the dimension measures its own seed pool from scratch.
