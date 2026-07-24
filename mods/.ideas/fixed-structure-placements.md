# Fixed structure placements (precision placement, final piece)

> Extracted 2026-07-24 from the retired `vanilla-custom-world-settings.md`
> (everything else in that doc shipped: Tiers 1–3 + biomePatches). This is
> the one remaining precision-placement feature: an exact structure at an
> exact spot.

## Two routes

1. **Post-gen `/place structure`** (cheap, ships tomorrow): deploy.sh's
   one-time dimension setup already forceloads a chunk — extend it to run
   `execute in <dim> run place structure minecraft:ancient_city X Y Z` from
   a config list. Baked into chunks, survives forever, zero runtime cost.
   Limitations: placement is "as generated at that spot" (no terrain
   adaptation beyond the structure's own rules), and it's creation-time
   only (marker-gated like the rest of one-time setup).
2. **Custom StructurePlacement type** (proper, medium): register a
   `customdimensions:fixed` placement that returns exact chunk positions
   from config. `DimensionStructures` already rebuilds each world's
   placement calculator with unregistered copies — injecting synthetic
   placements is the same machinery. This gets real generation-time
   placement (terrain adaptation, locate support, maps) and composes with
   structureDensity.

   Implementation notes (2026-07-22 handoff): register the placement type
   at mod init; inject synthetic (structure set → fixed placement) pairs
   during the rebuild. Access points are `StructurePlacementAccessor` and
   `StructurePlacementCalculatorInvoker` — the invoker exists because the
   public Stream create() zeroes concentric-ring seeds, so the private
   ctor is the one to use. A `"structures": {"mode": allow|reject|none,
   "list": [...], "force": [{structure, x, z}]}` shape must coexist with
   the existing seed-roll `Structures` class (wants/shuns) — read
   `c89e1e1` first. "none" is a whole-set drop (structureDensity already
   does these); the peaceful overlay drops sets through a parallel path —
   unifying them while in there leaves things tidier. Pipeline parity:
   `scripts/seed/structure_placement.py` treats filtered sets as absent
   and forced structures as constants (known distance, guaranteed scoring
   hits) — rolls then only hunt the organic remainder. Oracle: fixture
   dim forcing an end_city near spawn — `locate structure` returns the
   configured spot; with `"mode": "none"` every locate is "Could not
   find". Fingerprint corollary applies: forced placements are
   generation-affecting → `generation_payload()` in the same commit.

## Still-open rows from the old "how deep does it go" table

| Layer | Mechanism | Status |
| --- | --- | --- |
| Exact structure at exact spot | /place (v1) or fixed placement (v2) | 🟡 easy / medium |
| Custom biomes (own colours/features) | jar-baked worldgen/biome JSON | 🟡 medium, client-visible tints work |
| Terrain shape at exact spots | authored density functions | 🔴 hard, real worldgen authoring |
| Custom skyboxes beyond the 3 vanilla effects | — | ❌ client mod territory |
