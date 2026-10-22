# Portals

Portals carry over as they are, onto a Polymer portal block implementing vanilla's `Portal` interface. Work packages M6, M6b, M7 and M8 ([work-plan.md](work-plan.md)).

## Frames, orientations and shapes

- **Frame forms:** the flood-fill frame matcher with every accept form — a block, a tag, a list, or a colour group; per-part `frameMaterials` (base, sides, lintel); mixed frames; `framePlaceBlock` for frames the mod builds.
- **Orientations:** `vertical`, `vertical_x`, `vertical_z`, `horizontal`, `any`.
- **Shapes:** `standard`, `door`, `doorway`, `end_exit`, `end_gateway` and `pattern` templates.

Of the 82 definitions, 26 use a shape, 7 an orientation, and every one a frame; F3's converter carries them into schema v2. Zone records saved by the 1.21.1 mod keep loading.

## The portal block

One block of ours, with a state per plane. Ignition uses Fabric's use-block event. This removes the portal-tick, destination and ignition mixins and makes horizontal portals ordinary rather than a special case.

| Plane | Unmodded Java client sees | Bedrock (Geyser) sees |
| --- | --- | --- |
| Vertical, X or Z axis | Nether portal on that axis, tinted by the server pack | Nether portal |
| Horizontal | End portal, tinted | End portal |
| Single cell | End gateway, tinted | End gateway |

Vanilla End gateway, End portal and Nether portal behaviour stays dark where a definition suppresses it.

## Travel

Igniters, linking, coordinate scaling, bidirectional travel, anchors, single-use decay (frame-break detection) and exit portals.

## Auras and `subsume`

Each end of a portal slowly converts its surroundings to the far side's nature.

- **Sampling:** the far side is sampled at link time — top terrain blocks, plants, logs as trees, still fluids. When the far side isn't loaded to sample, the aura uses the destination's own material palette from its definition.
- **Passes:** slow and bounded around both ends, with `fireChance`, `radius`, `interval`, per-pass and lifetime budgets, and `sides`; palette, flora, trees, fluids and conversions overrides.
- **`subsume`:** resolved live — `none`, `natural` or `everything` sets what the aura may eat.
- **Guard rails:** frame and interior are never touched; no chunk loading; no containers or block entities; fluids only in hollows.
- **Claim veto:** Open Parties and Claims land is vetoed under every policy.
- **26.3 specifics:** tree ids point at the `worldgen/feature` registry (F3's converter rewrites them); `#adventure:aura_protected` gains the crafted blocks added since 1.21.1 (shelves, copper variants and the rest), checked against the 26.3 block list.

Bedrock players see the same result, because auras place real blocks.

## Previews

| Client | What it gets |
| --- | --- |
| Unmodded Java | A coloured portal, destination particles and the destination's name as a title on approach |
| Bedrock | The coloured portal and particles |
| Companion (optional) | The far side rendered in the portal, live: its colour, sky, fog and blocks, and mobs and arrows passing through. Tested alongside Iris and Distant Horizons |

The server streams the companion feed — destination blocks, atmosphere, mobs and projectiles near the far portal — only to clients that announce the companion, within a per-player budget (M7). Nothing depends on it ([decisions.md](decisions.md), RB-03).

## Checks

- The portal matrix (`scripts/e2e/`) passes on 26.3: one bay per dimension's portal, lit, crossed and returned through; a horizontal bay for every dimension that allows one; extra bays for any frame form or shape no shipped dimension uses.
- A zone saved by the 1.21.1 mod restores; suppressed vanilla portals stay dark.
- Auras: three portals into `none`, `natural` and `everything` dimensions beside a cobblestone wall — untouched, untouched, converted; the same wall in a claimed chunk untouched under all three; the budget stops each pass at its limit.
- Previews: a vanilla client and a companion client at the same portal; only the companion receives the feed.
