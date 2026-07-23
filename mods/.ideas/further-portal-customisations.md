# Further portal customisations

> **Status:** idea / spec sketch. No code written.
>
> Extends the portal system (`PortalHelper`, `PortalIgnitionMixin`, `ExitPortalManager`, `DimensionConfig.Portal`) with richer frame materials, portal shapes, and orientation control. Backwards compatible with the existing `frameBlock`/`igniterItem` config surface.

## 1. Frame material generalisation

Today every portal definition carries a single `frameBlock` string (e.g. `"minecraft:dark_prismarine"`). `PortalHelper.floodFill` and `isAreaBoundedByFrame` compare against exactly one `Block` instance. This section generalises that to four levels of specificity.

### 1a. Block tags (`#minecraft:logs` = "any wood")

Accept a `#`-prefixed string as a tag reference: `"frameBlock": "#minecraft:logs"`. Resolution at `PortalDefinition.getFrameBlock()` time (or a new `resolveFrameMatcher()` method) would return a predicate rather than a block id. Tags are vanilla's grouping mechanism and already cover the useful families (logs, planks, wool, coral_blocks).

**Seams:**

- `PortalHelper.floodFill` (line ~553) and `isAreaBoundedByFrame` (line ~584) both do `state.getBlock() == frameBlock`. Replace with a `FrameMatcher.matches(BlockState)` predicate passed through instead of a raw `Block`.
- `isZoneValid` (line ~266) resolves the frame block from the zone's persisted definition. Same change: resolve to a matcher.
- `ExitPortalManager.resolveFrameBlock` (line ~156) picks a block to BUILD with. Tags need a "canonical" block for placement (the first entry, or an explicit `"framePlaceBlock"` field). This is the trap: a tag describes what's ACCEPTED but you still need a concrete block for the mod to place arrival frames and exit portals.

**Tag existence caveat:** block tags aren't queryable at config-parse time (they load with datapacks, after our config). Validate lazily on first use; malformed tag id = warn + reject ignition (existing policy: never crash, never auto-fix).

### 1b. Block lists

`"frameBlock": ["minecraft:oak_planks", "minecraft:birch_planks", "minecraft:spruce_planks"]` — an explicit list when no vanilla tag covers the set. `FrameMatcher` becomes a union: any listed block satisfies the frame check. Still needs a canonical placement block (first entry, or explicit `framePlaceBlock`).

### 1c. Colour groups ("any red block")

`"frameBlock": {"colorGroup": "red"}` — a curated tag shipped in the jar datapack (e.g. `adventure:red_blocks` containing red wool, red concrete, red terracotta, red glazed terracotta, red stained glass, red mushroom block, etc.). This is syntactic sugar over 1a: the mod ships `data/adventure/tags/blocks/red_blocks.json` (and friends for the 16 dye colours), and the config resolves `colorGroup: "red"` to `#adventure:red_blocks`.

Sixteen colour tags is a one-time authoring job in the jar datapack under `mods/custom-dimensions/src/main/resources/data/adventure/tags/blocks/`. The maintenance cost is near zero (the block list per colour barely changes across MC versions).

### 1d. Per-part materials (top/sides/bottom different)

`"frameMaterials": { "top": "#minecraft:planks", "sides": "#minecraft:logs", "bottom": "minecraft:stone" }` — different requirements for different frame segments. This is the richest form and the hardest to implement.

**Seams (deep):**

- `floodFill` currently treats every non-interior, non-fillable block as "frame or reject". With per-part materials, the fill itself doesn't change (any frame-material block stops the flood), but VALIDATION afterwards must classify each frame position as top/bottom/left/right and check the correct matcher.
- Frame position classification: for a vertical portal (axis X or Z), "bottom" = frame blocks at `min(interior.y) - 1`, "top" = frame blocks at `max(interior.y) + 1`, "sides" = the rest. For horizontal (axis Y), "bottom" has no natural meaning — perhaps inner ring vs outer ring, or omit per-part for horizontal portals entirely.
- `isAreaBoundedByFrame` → `isAreaBoundedByFrameParts`: iterate the frame ring (the `collectFramePositions` helper already does this for single-use decay), classify each position, check the matching predicate.
- `ExitPortalManager.buildFrame` currently places one `frameState` everywhere. With per-part materials it needs the same classification logic to place the right block per position.
- `createTargetPortal` (line ~446) also builds a frame ring — same change.

**Backwards compatibility:** when `frameMaterials` is absent, the existing `frameBlock` (now potentially a tag/list/colour-group) applies uniformly to all parts. `frameMaterials` and `frameBlock` are mutually exclusive; both present = warn + `frameMaterials` wins.

### Config schema sketch (material)

```jsonc
"portal": {
  // Level 0 (today): single block id
  "frameBlock": "minecraft:cherry_planks",

  // Level 1: tag reference
  "frameBlock": "#minecraft:logs",

  // Level 2: explicit list
  "frameBlock": ["minecraft:oak_planks", "minecraft:birch_planks"],

  // Level 3: colour group (sugar for a jar-shipped tag)
  "frameBlock": { "colorGroup": "red" },

  // Level 4: per-part (each part accepts any of the above forms)
  "frameMaterials": {
    "top": "#minecraft:planks",
    "sides": "#minecraft:logs",
    "bottom": "minecraft:stone"
  },

  // Which block the mod places when it builds frames (arrival, exit portal).
  // Required when frameBlock is a tag/list/group; defaults to the single
  // block id when frameBlock is a plain string.
  "framePlaceBlock": "minecraft:oak_log"
}
```

## 2. Portal shape generalisation

Today the portal shape is whatever the flood-fill finds: a contiguous region of fillable air bounded by frame blocks, scanned in one of three planes (X, Z, or Y). The 128-block cap (`MAX_PORTAL_BLOCKS`) is the only constraint on shape. This is flexible but entirely player-determined — the config has no say in what shape is valid.

### Named shape presets

Add an optional `"shape"` field to the portal config. When absent, the existing free-form flood-fill applies (full backwards compatibility). When set, ignition validates the discovered interior against the shape's geometry.

#### `"standard"` (default / absent) — free-form flood-fill

Exactly today's behaviour. Any shape the player builds, up to 128 interior blocks, bounded by frame.

#### `"door"` — 1x2 interior (a single door)

The portal interior must be exactly 2 blocks tall, 1 block wide. Vertical only (X or Z axis). After flood-fill, validate: `interior.size() == 2` and the two positions differ by exactly 1 on the Y axis.

**Seam:** validation is a post-fill check in `PortalIgnitionMixin.tryIgnite`, between the flood-fill and `registerAndFinish`. A `ShapeValidator.validate(fill, axis, shapeName)` method returns true/false.

#### `"doorway"` — 2x3 interior (standard Nether portal size)

Interior must be exactly 6 blocks: 2 wide, 3 tall. Same validation pattern as `"door"` but checking `width == 2, height == 3`.

#### `"end_gateway"` — single-block teleporter (no frame)

A 1-block portal with no frame at all, like vanilla end gateways. This is fundamentally different from the other shapes:

- **No frame, no flood-fill.** Ignition places a single `END_GATEWAY` block (not `NETHER_PORTAL`) at the clicked position. The gateway block itself is the portal.
- **No frame validation.** `isZoneValid` must skip frame checks for gateway zones; the interior is 1 block and the zone is valid as long as the gateway block exists.
- **Ignition semantics change.** With no interior air to flood-fill into, the igniter must be used ON the target block position itself (right-click air or a replaceable block). `tryIgnite` needs a branch: if shape is `end_gateway`, skip flood-fill, check that the clicked position (or an adjacent fillable block) is a single valid spot, place the gateway, register a 1-block zone.
- **Traversal semantics.** Vanilla `EndGatewayBlockEntity` handles its own teleportation; we'd either hook that (mixin on `EndGatewayBlockEntity.tryTeleportingEntity`) or suppress vanilla behaviour and handle it ourselves via the existing zone-based tick check in `ServerWorldMixin`. The latter is cleaner — it keeps all custom portal travel in one path.
- **Arrival.** No arrival frame to build. `createTargetPortal` places a single gateway block at the arrival position. No frame particles.

#### `"end_exit"` — horizontal ring with centre block (post-dragon style)

A horizontal portal (Y axis) shaped like the vanilla End exit portal: a 3x3 interior cross (or 5x5 with corner cutoffs) with frame blocks forming the outer ring. The player builds the frame ring on the ground; ignition floods horizontally to find the interior and places `END_PORTAL` blocks.

- **Interior geometry.** The vanilla end exit portal's interior is a 3x3 cross (no corners), totalling 9 blocks. For our version, any horizontal flood-fill result is valid (the player decides the shape); the `"end_exit"` name is thematic, not prescriptive. The key distinction from `"standard"` is that this forces horizontal orientation.
- **Centre block.** Optionally, a config field `"centreBlock": "minecraft:dragon_egg"` or `"centreBlock": true` (uses the frame block) places a block in the geometric centre of the interior on ignition. Thematic: a pedestal, an eye, a trophy.
- **Uses `END_PORTAL` blocks** (already handled: horizontal portals use `Blocks.END_PORTAL` — see `createTargetPortal` line ~458).

#### `"pattern"` — explicit template matching

The most expressive option: a small 2D grid template defining exactly which cells must be frame, which must be interior, and which are don't-care.

```jsonc
"shape": {
  "type": "pattern",
  "template": [
    "FFF",
    "F.F",
    "F.F",
    "F.F",
    "FFF"
  ],
  "legend": { "F": "frame", ".": "interior" }
}
```

This is a pattern grammar: the template is a list of strings (rows, top to bottom), each character maps to a role. `F` = must be frame material. `.` = must be fillable (becomes portal). ` ` (space) = don't care (ignored — the surroundings can be anything).

**Validation:** after flood-fill finds a candidate interior, overlay the template centred on the interior's bounding box. Every `F` cell must contain a frame-matching block; every `.` cell must be in the interior set. Mismatch = ignition fails. For vertical portals the template rows map to Y (top row = highest Y); for horizontal portals rows map to Z (or X, TBD).

**Rotation:** the template is authored for one orientation. For vertical portals it auto-applies to both X and Z axes (the fill already tries both). No rotation beyond that — if you want an L-shaped portal, author the L.

This is the deep option. It gives full control ("the portal must be a 1x2 door with a stone lintel and oak sides") by combining pattern shapes with per-part materials. But the implementation cost is real: template parsing, bounding-box alignment, rotation, and the interaction with the flood-fill (which currently finds shapes, not validates them against templates).

### Config schema sketch (shape)

```jsonc
"portal": {
  // Absent or "standard": free-form flood-fill (today's behaviour)
  "shape": "standard",

  // Named preset
  "shape": "door",          // 1x2 vertical
  "shape": "doorway",       // 2x3 vertical
  "shape": "end_gateway",   // 1-block, no frame
  "shape": "end_exit",      // horizontal ring

  // Explicit pattern
  "shape": {
    "type": "pattern",
    "template": ["FFF", "F.F", "FFF"],
    "legend": { "F": "frame", ".": "interior" }
  },

  // End-exit options
  "centreBlock": "minecraft:dragon_egg"   // only meaningful with end_exit
}
```

## 3. Orientation control

Today `PortalIgnitionMixin.tryIgnite` tries all three axes (X, Z, Y) and picks the best match based on the clicked face direction. The config has no say.

### `"orientation"` field

```jsonc
"portal": {
  "orientation": "vertical",     // X or Z only (default when absent)
  "orientation": "horizontal",   // Y only (end-portal style)
  "orientation": "any",          // all three (today's effective behaviour)
  "orientation": "vertical_x",   // locked to X axis
  "orientation": "vertical_z"    // locked to Z axis
}
```

**Seam:** `tryIgnite` currently runs flood-fill on all three axes unconditionally. With an orientation constraint, skip the disallowed axes. The priority logic (lines ~97-119, clicked-face preference) stays for the allowed subset.

**Interaction with shape:** `"end_gateway"` implies `"any"` (a single block has no orientation). `"end_exit"` implies `"horizontal"`. Named presets can override the orientation default, but an explicit `"orientation"` field always wins.

**Arrival side:** horizontal portals already get different arrival handling (`END_PORTAL` blocks, floor placement — `createTargetPortal` lines ~481-487). Orientation on the arrival side matches the source portal's axis, which is persisted in the zone's `axis` field and carried through `StoredPortalZone`.

## 4. Ignition for frameless shapes (end_gateway)

The existing ignition flow assumes: right-click a frame block → find adjacent air → flood-fill → validate frame ring. Frameless shapes (single-block teleporters) break every step.

### Stepping-on vs igniter-click semantics

Two options for how a player activates a frameless portal:

**Option A — click-to-place (recommended).** The igniter item is used on a block surface (like placing a torch). Instead of flood-filling, the mod places a gateway block at the clicked position (or one above, for top-face clicks). This reuses the existing `PortalIgnitionMixin` hook (`ItemStack.useOnBlock`) — just a different branch after shape detection.

- The igniter item is consumed (creative exemption stays).
- Ignition sound plays.
- Zone is registered with a 1-block interior.
- Pre-warm fires as usual.

**Option B — stepping-on activation.** A special block (configurable) is placed by the player. When any player steps on it while holding the igniter, it converts to a gateway. This needs a new tick-based detection hook — more complex, less intuitive. Reject unless demand appears.

**Seam for Option A:** in `tryIgnite`, before the flood-fill loops, check `if (def.getShape().equals("end_gateway"))`. Find a single fillable position adjacent to or at the clicked pos. Place the gateway block. Register a 1-block zone. Return true. The 7x7 fallback scan (lines ~126-164) is skipped entirely for gateway shapes.

## 5. Config schema (complete portal block, backwards compatible)

```jsonc
"portal": {
  // --- existing fields (unchanged) ---
  "frameBlock": "minecraft:cherry_planks",
  "igniterItem": "minecraft:cherry_sapling",
  "color": "FFB7C5",
  "lightLevel": 11,
  "scale": 1.0,
  "cooldown": 40,
  "particleType": "minecraft:cherry_leaves",
  "sounds": { "ignite": "...", "enter": "...", "exit": "..." },
  "anchor": { ... },
  "singleUse": { ... },

  // --- new fields ---
  // Frame material: string (today), tag, list, or colour-group object.
  // Mutually exclusive with frameMaterials.
  "frameBlock": "#minecraft:logs",

  // Per-part materials (overrides frameBlock when present).
  "frameMaterials": {
    "top": "#minecraft:planks",
    "sides": "#minecraft:logs",
    "bottom": "minecraft:stone"
  },

  // Block the mod places when building frames. Required when frameBlock
  // is not a single block id.
  "framePlaceBlock": "minecraft:oak_log",

  // Shape constraint. Absent = free-form flood-fill.
  "shape": "doorway",

  // Orientation constraint. Absent = "vertical" for most shapes.
  "orientation": "any",

  // Centre block for end_exit shape.
  "centreBlock": "minecraft:dragon_egg"
}
```

**Backwards compatibility:** every new field is optional. A config with only the existing fields behaves identically to today. The `PortalDefinition` class gains new fields with null/default values; `DimensionConfig.toPortalDefinition()` populates them from the new config entries. `FrameMatcher` is a new class, defaulting to single-block exact match when constructed from a plain string.

## 6. Interaction with existing features

### Exit portals (`ExitPortalManager`)

`ExitPortalManager.buildFrame` currently builds a hardcoded 2x3 X-axis frame. With shape presets:

- If the dimension's portal config specifies a shape, `buildFrame` should respect it. `"doorway"` = today's 2x3. `"door"` = 1x2. `"end_exit"` = horizontal ring. `"end_gateway"` = single gateway block (no frame to build).
- `resolveFrameBlock` already reads the portal's frame block — extend it to handle tags/lists via `framePlaceBlock`.
- The `INTERIOR_WIDTH` / `INTERIOR_HEIGHT` constants become shape-derived.

### Single-use decay

`expireSingleUse` calls `collectFramePositions` to find the frame ring, then decays per `breakMode`. With per-part materials, the decay map should be able to specify per-material replacements (it already maps block id to replacement id, so this works without changes — each frame block is resolved individually). The existing `PortalDecay.resolve` handles this naturally.

With tag-based frames, the same portal might have oak logs AND birch logs in its frame. Each decays independently through the decay map. If the map doesn't cover a specific block, the existing fallback applies (remove in partial mode, leave in decay mode). Document: when using tags/lists, the decay map should cover every block the tag might resolve to.

### Portal fingerprinting / zone validation

`isZoneValid` checks that every frame position still contains the expected frame block. With `FrameMatcher`, this becomes `frameMatcher.matches(state)` — a tag/list/group check instead of exact block equality. The persisted `PortalDefinition` in zone records carries the matcher config, so validation uses the rules the portal was ignited with (not the current config — same principle as singleUse persisting its definition).

**Trap:** if the config changes a portal's `frameBlock` from `"minecraft:oak_planks"` to `"#minecraft:logs"`, existing persisted zones still carry the old single-block matcher. They'll validate fine (oak_planks is in #logs), but new portals will accept any log. This is correct behaviour — zones are immutable snapshots of their ignition-time config.

### The NOTIFY_LISTENERS | FORCE_STATE placement trap

All frame and portal block placement already uses `Block.NOTIFY_LISTENERS | Block.FORCE_STATE` (the hard-won fix from 2026-07-13). Per-part material placement must use the same flags. No new trap here, but worth stating: every new `setBlockState` call for frame/portal blocks MUST use these flags, never `NOTIFY_ALL`.

### Bot-testing recipes for new shapes

Each new shape needs a Carpet-bot ignition recipe in the verification loop (AGENTS.md section 3b). Key additions:

- **Door shape:** build a 1x3 frame (1 wide, 3 tall including top/bottom frame), stand inside, ignite. Validate `interior.size() == 2`.
- **End gateway:** right-click air with the igniter. Validate a gateway block appears. Traverse by walking into it.
- **End exit (horizontal):** build a frame ring on the ground, stand above, look down, ignite. Validate `END_PORTAL` blocks fill the interior.
- **Per-part materials:** build a frame with mixed materials matching the config. Validate ignition succeeds. Swap one block to a non-matching material. Validate ignition fails.
- **Tag frames:** build with any block from the tag. Validate ignition. Build with a non-tag block. Validate rejection.

The bot's `look`/`use` positioning is shape-dependent. For horizontal portals: `look down` + `use once` standing above the frame. For gateway: `look at <target_pos>` + `use once`. Document these in AGENTS.md alongside the existing cherry_planks recipe.

## 7. Design principles

- **Portal config is not creation-time-only.** Like the rest of the portal block, shape/material/orientation re-reads every boot. Changing a portal's shape retroactively doesn't break existing zones (they validated against the ignition-time config), but new ignitions must match the new shape.
- **Never crash, never auto-fix.** Invalid shape names, malformed templates, unresolvable tags = WARN at boot + reject ignition attempts. The dimension still loads; the portal just doesn't work until the config is fixed.
- **Backwards compatible by default.** Every new field is optional. Absent `shape` = free-form. Absent `orientation` = vertical. Absent `frameMaterials` = uniform `frameBlock`. No existing config needs editing.
- **`framePlaceBlock` is the escape hatch.** When the mod needs to BUILD a frame (arrival portals, exit portals), it needs a concrete block. Tags and lists describe what's ACCEPTED; `framePlaceBlock` describes what's PLACED. This split is load-bearing — without it, the mod can't build arrival frames for tag-configured portals.

## Open questions

1. **Should `FrameMatcher` be a first-class type in `PortalDefinition`, or resolved at use time from the raw config?** First-class is cleaner (parse once, use everywhere) but adds serialisation complexity for persisted zone records. Leaning: first-class, with a custom Gson serialiser.

2. **Pattern templates: row-major or column-major?** Row-major (each string is a horizontal row, top to bottom) matches how people think about building vertically. But for horizontal portals, "top to bottom" maps to Z-axis, which might be surprising. Leaning: row-major always, document the mapping.

3. **Should `"end_gateway"` shapes use actual `EndGatewayBlockEntity` or a custom block?** Vanilla gateways have their own teleport logic (beam, cooldown, exit search). Using real gateway blocks gives vanilla visuals for free but means fighting vanilla's teleport system. A custom block (registered by the mod) avoids the conflict but loses the iconic beam effect. Leaning: use real gateway blocks, mixin to suppress vanilla teleport when in a custom zone.

4. **Colour-group tags: ship all 16 or just the ones players ask for?** The authoring cost per tag is trivial (a JSON file listing ~8-12 blocks). Ship all 16 — it's a one-time job and avoids "why is there red but not cyan" issues.

5. **Per-part materials for horizontal portals: does "bottom" mean the underside (below the portal plane) or the "south" edge?** Leaning: skip per-part for horizontal portals entirely in v1. The concept maps cleanly to vertical frames (top/bottom/sides are intuitive) but gets confusing for flat rings.

6. **Template patterns: should they support "any solid block" as a legend entry?** e.g. `"S": "solid"` meaning any block where `isSolid()` is true. This would let you define "the portal must have a stone-class lintel but any solid sides". Leaning: defer to v2 — the `FrameMatcher` abstraction already supports tag/list/group, which covers the concrete use cases.

## Exit-shrine residuals (absorbed from new-portal-concepts.md, 2026-07-23)

The jigsaw shrine shipped (see exit-shrine-structure.md) with two ideas from the retired portal-concepts doc deliberately left behind — they belong to THIS work because both are frame/material/placement customisation:

1. **Per-dimension shrine frame substitution.** The shipped template hard-codes crying obsidian; the original vision was the shrine frame matching each dimension's `frameBlock`. Route: a structure **processor list** in the jar datapack (`minecraft:rule` processors swapping crying obsidian for a placeholder that the mod rewrites — or N pre-generated template variants from `gen-exit-shrine.py`, one per frame family, selected per-dimension by pointing `exitShrines` at a variant pool). The detection side is already material-agnostic (`ExitShrineManager.frameRingIntact` reads whatever block sits above the beacon), so ONLY generation needs work. Pairs naturally with `FrameMatcher`: a shrine in an "any wood" dimension should build from the dimension's `framePlaceBlock`.
2. **Per-border shrine spacing.** Shipped spacing is a static 24 chunks; the original note wanted "a 256-radius pocket wants 1–2 shrines, not a grid". The `structures.spacing` override (Tier 3, shipped) already lets a dimension tune `adventure:exit_shrines` per-dim BY HAND — the residual is only a sensible AUTOMATIC default derived from `borders.player` (e.g. spacing ≈ radius-in-chunks / 2, clamped 12..48) applied in the same `DimensionStructures` branch that raises the frequency. Cheap; needs the tier-1 roller mirror updated in the same commit.

## Effort estimate

| Feature | Tier | Effort | Notes |
| --- | --- | --- | --- |
| `FrameMatcher` abstraction + tag support | Cheap | 1-2 sessions | New class, swap `Block` for matcher in 4 methods. Jar datapack tags are authoring-only. |
| Block lists in `frameBlock` | Cheap | Falls out | `FrameMatcher` handles union matching. |
| Colour-group tags | Cheap | 1 session | 16 JSON files in the jar datapack + `colorGroup` sugar in config parsing. |
| `framePlaceBlock` field | Cheap | Trivial | One new field, used in 3 places (`createTargetPortal`, `ExitPortalManager.buildFrame`, `resolveFrameBlock`). |
| `"orientation"` field | Cheap | < 1 session | Gate the axis attempts in `tryIgnite`. |
| `"door"` / `"doorway"` presets | Medium | 1 session | Post-fill size/shape validation. |
| `"end_exit"` (horizontal ring) | Medium | 1-2 sessions | Mostly works today (horizontal flood-fill exists). Centre-block placement is the new bit. |
| Per-part materials | Medium | 2-3 sessions | Frame classification logic in `collectFramePositions`, per-part validation, per-part placement in `buildFrame` and `createTargetPortal`. |
| `"end_gateway"` (frameless) | Deep | 3-4 sessions | Different ignition path, different traversal hook, gateway block entity interaction, no arrival frame. Needs a mixin on `EndGatewayBlockEntity` or a custom block. |
| `"pattern"` templates | Deep | 3-4 sessions | Template parsing, bounding-box alignment, rotation handling, interaction with flood-fill direction. |
| Bot recipes for all shapes | Ongoing | 1 session per shape | Each shape needs an RCON-drivable build + ignite + traverse + break recipe in AGENTS.md. |
