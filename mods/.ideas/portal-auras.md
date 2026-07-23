# Portal auras — per-dimension environmental spread around portals

Pip's ask (2026-07-24): portals should affect their surroundings, themed
per dimension. A hard nether-variant portal corrupts aggressively — nether
blocks creeping outward, exposed surfaces catching fire, nearby obsidian
(never the frame's own) turning crying. A forest dimension's portal grows
trees, grasses, and moss around it. Customisable per dimension.

## Why in-house, not netherportalspread

netherportalspread is monolithic: one global config, one conversion table
(`spreadsettings.txt`), no per-portal/per-dimension hooks, no API — and we
already had to ship `NetherPortalProtectionMixin` to stop its spread
popping our portals (2026-07-23). Everything an aura needs is ours now:
registered portal positions both sides (PORTAL_ZONES + PORTAL_TARGETS/
PORTAL_FRAMES), tag matching (FrameMatcher generalises to conversion
keys), a safe tick seam (ServerWorldMixin), and the protection mixin so an
aura can never destroy the portal it decorates.

**Pack-curation decision once this lands**: remove netherportalspread (or
zero its `instantConvertAmount`/spread) and ship its behaviour as an
opt-in aura preset for nether-y dims — two spread engines around the same
portals will fight thematically.

## Default behaviour: bi-directional aura leak (Pip, 2026-07-24)

With no config at all, every linked portal pair leaks the OTHER side's
nature through. The default aura is DERIVED, not curated:

1. **At link time** (arrival portal creation — the only moment both ends
   are guaranteed loaded), scan a small cube (~9×5×9) around EACH portal:
   - histogram solid terrain blocks → top 4–5 = the terrain palette
   - collect plant blocks (#flowers, #saplings, grass/tall_grass/ferns,
     moss, fungi, nylium carpets) → the flora palette
   - note log types → map to tree ConfiguredFeatures via a lookup table
     (oak_log→minecraft:oak, cherry_log→minecraft:cherry, crimson_stem→
     minecraft:crimson_fungus, …; unknown modded logs: skip trees)
   - note surface fluids (water/lava) → the fluid palette
2. **Persist both palettes into the portal link records** (plain block
   ids ONLY — the downgrade-parseability rule applies to these fields
   too). Later passes never need the far world loaded (idle unload), and
   the palette is an immutable snapshot like everything else zone-side.
3. **Aura passes** then convert dimension A's surroundings using
   dimension B's palette and vice versa: terrain blocks convert
   like-for-like-ish (surface block→surface block, stone-ish→stone-ish),
   flora is placed on valid ground, tree features generate occasionally,
   and fluids form ONLY in depressions (solid below, ≥3 enclosed sides —
   puddle discipline; lava is a griefing engine, so low chance and inside
   the finite budget).

Why terrain SAMPLING and not biome-registry lookup: `world.getBiome(pos)`
is easy, but a biome's actual surface blocks live in worldgen surface
RULES (Terralith etc. bake them into noise settings) — not practically
queryable. Sampling the real loaded terrain is mod-proof and captures
what is genuinely there, plants included.

## Config sketch (portal block — boot-re-read like everything portal-side)

Everything below OVERRIDES the derived default; absent config = the
bi-directional leak above.

```jsonc
"portal": {
  "aura": {
    "enabled": false,         // explicit off switch (default true)
    "radius": 12,             // blocks from portal centre (default ~8)
    "interval": 40,           // ticks between passes
    "blocksPerPass": 2,       // aggression dial
    "budget": 300,            // lifetime conversions per portal; -1 = endless creep
    "sides": "both",          // "source" | "target" | "both" (default both)

    // Palette override: replaces the SAMPLED far-side palette for auras
    // this dimension emits. Empty list = emit nothing (flora/fluids ditto).
    "palette": ["minecraft:crimson_nylium", "minecraft:netherrack",
                "minecraft:blackstone", "minecraft:magma_block"],
    "flora": ["minecraft:crimson_fungus", "minecraft:crimson_roots"],
    "trees": ["minecraft:crimson_fungus"],   // ConfiguredFeature ids
    "fluids": ["minecraft:lava"],            // puddle-discipline placement

    // Extras on top of either palette mode:
    "conversions": {          // explicit from (id or #tag) -> to pairs,
      "minecraft:obsidian": "minecraft:crying_obsidian"   // outside the frame
    },
    "fireChance": 0.08        // per-pass ignition on exposed surfaces
  }
}
```

Forest example: conversions stone→mossy_cobblestone, grass_block→
moss_block; features ["minecraft:oak", "minecraft:patch_grass",
"minecraft:moss_patch"]. `ConfiguredFeature.generate(world, chunkGen,
random, pos)` works server-side — real trees, not block stamps.

## Engineering rules (all learned the hard way — do not relearn)

- **Exclusion set = portal interior + frame ring.** An aura must never
  convert its own portal or frame (netherportalspread's exact sin).
  Obsidian→crying conversions apply only OUTSIDE the exclusion set.
- Tick from ServerWorldMixin per world: chunk-loaded guard (never
  sync-load to spread), bounded work per pass, no worlds-map mutation.
- Conversions use `NOTIFY_LISTENERS | FORCE_STATE` (registered portals
  are pop-proof now, but the placement discipline stands — and converted
  blocks next to OTHER blocks shouldn't cascade updates).
- Persist per-portal budget spent in the zone/links records (restarts
  resume, never re-burn). Conversions must be idempotent anyway (a
  converted block no longer matches any `from` key).
- Random per pass: seed from portal pos + pass counter (deterministic-ish,
  and Date/Math.random-free patterns don't apply — this is Java, use
  world random forked per portal).
- Fire: only on air-above-solid positions outside the exclusion set;
  respect `doFireTick`-adjacent expectations (fire spreads on its own —
  fireChance is ignition, vanilla handles the rest).
- Feature placement failures are silent no-ops (generate() returns false
  on invalid ground) — never retry-loop a failing feature.
- Palette sampling: at arrival-portal creation only (both ends loaded);
  persisted palettes are plain block ids (downgrade-parseability rule).
  A palette that samples empty (portal in a void) = no derived aura.
  Anchor portals: the anchor arrival is shared by many sources — sample
  once, first link wins (immutable snapshot); per-source leaks into the
  anchor dim would fight each other.
- Fluid placement is the grief vector: depressions only (solid below,
  ≥3 enclosed horizontal sides), low per-pass chance, never inside the
  exclusion set, counts double against the budget. Water in cold biomes
  freezing and lava fire-starting are vanilla behaviours we inherit —
  fine, but the budget must bound the blast radius.
- Bot recipe: ignite, wait N intervals, assert converted-block counts in
  the annulus (execute if blocks / sampled probes), assert the frame and
  interior untouched, assert budget stops the creep. For the derived
  default: build the source portal amid a known planted palette (e.g.
  surrounded by moss + birch), traverse, assert far-side sampled blocks
  appear around the arrival portal and vice versa.

## Slot in the queue

Extension of item 5 (further portal customisations) — natural landing is
after Tier 2 shapes (door/doorway/end_exit + per-part materials), before
or alongside the deep tier (end_gateway, pattern templates). Runtime-only
feature: needs a build_profile passthrough test (roller ignores portal
config — fingerprint tests already assert portal blocks don't shift it,
extend the same test to cover "aura").
