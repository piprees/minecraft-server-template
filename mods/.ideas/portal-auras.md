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

## Config sketch (portal block — boot-re-read like everything portal-side)

```jsonc
"portal": {
  "aura": {
    "radius": 12,             // blocks from portal centre
    "interval": 40,           // ticks between passes
    "blocksPerPass": 2,       // aggression dial
    "budget": 300,            // lifetime conversions per portal; -1 = endless
    "conversions": {          // from (id or #tag) -> to
      "minecraft:grass_block": "minecraft:crimson_nylium",
      "minecraft:obsidian": "minecraft:crying_obsidian",
      "#minecraft:logs": "minecraft:crimson_stem"
    },
    "fireChance": 0.08,       // per-pass chance to ignite air-above-solid
    "features": ["minecraft:crimson_fungus"],  // ConfiguredFeature ids,
                              // occasional generate() at a converted pos
    "sides": "both"           // "source" | "target" | "both" (default both)
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
- Bot recipe: ignite, wait N intervals, assert converted-block counts in
  the annulus (execute if blocks / sampled probes), assert the frame and
  interior untouched, assert budget stops the creep.

## Slot in the queue

Extension of item 5 (further portal customisations) — natural landing is
after Tier 2 shapes (door/doorway/end_exit + per-part materials), before
or alongside the deep tier (end_gateway, pattern templates). Runtime-only
feature: needs a build_profile passthrough test (roller ignores portal
config — fingerprint tests already assert portal blocks don't shift it,
extend the same test to cover "aura").
