# New Portal Concepts — anchors, single-use, exit shrines

Three related portal behaviours for custom-dimensions, aimed primarily at
pocket dimensions (512×512). Grounded in the current implementation:
`PortalHelper` (zones, `portal_links.json`, UUID origins),
`PortalIgnitionMixin` (flood-fill + ignition + prewarm),
`ServerWorldMixin` (per-tick zone validation + teleport), coordinate-scaled
arrival via `findSurfaceY` on the target column.

## 1. Anchor portals — "every portal leads to the same landing"

Like End gateways: no matter where the source portal is, arrival is always
the same spot, and no target-side portal is ever created per-source.

```jsonc
"portal": {
  "anchor": {
    "pos": [0, 64, 0],          // or "spawn" -> the dimension's spawn point
    "exit": "bed"               // "bed" | "origin" | "worldSpawn"
  }
}
```

Behaviour:
- Entering ANY source portal for this dimension teleports to the anchor
  (surface-resolved once via `findSurfaceY`, then cached — the anchor's
  arrival portal is built exactly once, at/next to the anchor).
- Target-side per-source portal creation is suppressed entirely; the
  `portal_links.json` per-source return links are not written for anchor
  dims (one persistent anchor entry instead).
- **Exit modes**:
  - `"origin"` — current behaviour (UUID origin tracking): back to where
    you came from. Preserves the fast-travel property.
  - `"bed"` — leaving lands you at your respawn point
    (`ServerPlayerEntity.getSpawnPointPosition()` +
    `getSpawnPointDimension()`, vanilla respawn-obstruction check,
    fallback to world spawn). Like dying without dying.
  - `"worldSpawn"` — overworld spawn, always.
- **Anti-wormhole note**: `"bed"` still teleports the player to their bed —
  that IS a fast-travel primitive (enter pocket portal anywhere → exit at
  bed ≈ a poor man's recall scroll). If the goal is denying travel
  advantage, `"origin"` is the strict option; `"bed"` is the *thematic*
  option and probably fine for peaceful pocket dims. Per-dimension choice.

Implementation notes:
- `PortalHelper.PortalZone` teleport path short-circuits when the target
  dimension's config carries an anchor: skip scaled-coordinate mapping,
  use the anchor pos.
- Player origin tracking stays as-is for `"origin"` exits; `"bed"`/
  `"worldSpawn"` exits ignore origins (and should clear the stored origin
  so a later `"origin"`-mode dim doesn't resurrect a stale one).
- The anchor arrival portal is a real zone (frame + interior) so the exit
  is discoverable and breakable like any portal; if broken, rebuild it on
  next arrival (anchor dims must never strand players — see §3).
- Ignition prewarm (`requestWorldLoad` at ignite) already covers anchors.

## 2. Single-use portals — "the way shuts behind you"

```jsonc
"portal": {
  "singleUse": {
    "enabled": true,
    "delaySeconds": 10,          // timer starts at first traversal
    "breakMode": "decay",        // "destroy" | "decay" | "partial"
    "decayMap": {                 // optional; sensible defaults below
      "minecraft:obsidian": "minecraft:crying_obsidian"
    }
  }
}
```

- On first traversal through a zone, start a per-zone countdown (ticked in
  the same `ServerWorldMixin` pass that validates zones — no new tick hook).
- On expiry:
  - `"destroy"` — frame blocks removed (drops off), interior cleared,
    break particles + sound.
  - `"decay"` — every frame block swapped via decayMap; interior cleared.
    Default decay pairs (extend as needed):
    obsidian → crying_obsidian, stone_bricks → cracked_stone_bricks,
    nether_bricks → cracked_nether_bricks,
    polished_blackstone_bricks → cracked_polished_blackstone_bricks,
    deepslate_bricks/tiles → cracked variants, any `*_log` → `stripped_*`,
    any `*_planks` → `minecraft:air` (wood burns out entirely).
  - `"partial"` — deterministically pick 1–2 frame blocks (seeded from
    zone position, so it's stable) and decay/remove only those: the frame
    LOOKS repairable, and is — re-igniting a repaired frame is allowed.
- The countdown survives restarts: persist `firstTraversalTick` with the
  zone (zones already persist source-side via the pending-zones path).
- Interactions:
  - Anchor + singleUse: the SOURCE portal crumbles; the anchor arrival
    portal never does (it's the way home, see exit rules).
  - The vanilla-portal override path (`EntityTickPortalMixin`) must respect
    singleUse when the custom frame uses NETHER_PORTAL interiors.
- Clearest use: a genuinely single-use nether-style escape portal — light
  it, dive through, hear it crack shut behind you.

## 3. Exit shrines — never strand a player

Single-use portals (and anchor dims with suppressed portal creation) can
strand players by design. Counterweight: every dimension that can strand
gets a guaranteed way home.

Options, cheapest first:
1. **Mod-built exit portal at dimension spawn** (recommended start): at
   dimension creation, build a small frame (the dimension's own
   `frameBlock`) at a deterministic offset from spawn, registered as a
   permanent anchor-style zone targeting the overworld with
   `exit: "bed"` semantics. Rebuild-if-broken on world tick (cheap check
   piggybacked on zone validation). Config:
   `"exitPortal": { "enabled": true, "pos": "spawn", "target": "bed" }`.
2. **Jigsaw structure via the jar datapack**: we already ship a datapack
   inside the mod jar (`adventure:void` noise preset) — an `adventure:exit_shrine`
   structure + structure set (spacing tuned per border size) would place
   themed shrine ruins containing the exit frame. Prettier, more work,
   and placement is seed-dependent (roller could then score
   `exit_shrine: near_spawn`).
3. **Both**: guaranteed spawn exit + scattered scenic shrines.

Rule of thumb to enforce in config validation: `singleUse.enabled` or
`anchor` with suppressed return ⇒ `exitPortal.enabled` must be true (boot
WARN if not, same tone as the fingerprint drift warning).

## Config interaction matrix

| Combination | Behaviour |
| --- | --- |
| anchor + exit "bed" | Pocket-dim ideal: one landing, leave to your bed, no travel exploit worth speaking of |
| anchor + exit "origin" | Shared landing but symmetric travel (fast-travel preserved) |
| singleUse + exitPortal | Escape-hatch drama without stranding |
| singleUse + no exitPortal | Refuse/warn at config load — stranding by config is a bug, not a feature |
| anchor "spawn" + border 256 | Landing is always inside the playable pocket |

## Open questions

- Should `"bed"` exits respect respawn anchors in nether-family pockets
  (vanilla charge consumption?) — suggest: locate only, never consume.
- Cooldown after anchor arrival: the standing-in-portal cooldown loop
  (vanilla resets `PortalCooldown` every tick in-portal) needs the same
  step-out handling the return-trip tests use.
- Decay of `partial` frames near the border: ensure picked blocks are
  reachable (not embedded in terrain).
- Do single-use SOURCE portals refund the igniter item on crumble? (Lean
  no — the cost is the point.)
