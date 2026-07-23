# Exit shrines, dimension links, and exit conditions

Two related programmes: the jigsaw shrine structure (the pretty way home), and the broader vision it grew into (2026-07-23) — dimensions as an interlinked graph with thematic, configurable ways OUT, where death is not always final.

## Part 2 — dimension links and exit conditions — ✅ SHIPPED 2026-07-23

Landed as designed (docs/customisation.md § Dimension links and exit conditions is the user reference): `ExitTarget` descriptors (any-dimension links with anchor/spawn/[x,y,z] arrivals, canonical `dim!ns:slug!arrival` strings so `portal_links.json` needed no schema change), the `exits` trigger block (void, death, death:&lt;cause&gt;, death:mob:&lt;id&gt;, enderPearl, fallFrom; teleport/respawnAt/kill actions), `ExitConditions` + `PlayerRespawnRedirectMixin`, validator rules (death-only exits, dangling links), roller passthrough test. Bot-verified live: void→link, enderPearl, death:lava cancel-teleport, linked exit portal. `respawnAt` is the one path a Carpet bot cannot exercise (fake players don't respawn) — mixin applies clean; first real-player death confirms it.

Original design notes kept below for reference:

Today every exit funnels through one mechanism: a portal (anchor/exit) with a target of origin/bed/worldSpawn. Generalise both halves:

**Configurable exit targets.** Exit portals and shrines should target ANY dimension, not just the overworld — `"target": {"dimension": "adventure:the_starwell", "arrival": "anchor" | "spawn" | [x,y,z]}` alongside the existing `"bed"`/`"worldSpawn"`/`"origin"` shorthands. That makes dims composable into chains and hubs (enter the gauntlet only through the boneyard; the starwell as a nexus with shrines to three sibling pockets). The existing exitMode plumbing in `PortalReturnTarget` is the right seam — it becomes a target descriptor rather than a mode string.

**Exit conditions — leaving without a portal.** A per-dimension `exits` block mapping TRIGGERS to targets:

    "exits": {
      "void":        { "action": "teleport", "target": "bed" },
      "death":       { "action": "respawnAt", "target": "worldSpawn" },
      "death:lava":  { "action": "respawnAt", "target": "adventure:the_furnace_halls" },
      "fallFrom":    { "minHeight": 100, "action": "teleport", "target": "origin" },
      "enderPearl":  { "action": "teleport", "target": "adventure:the_starwell" }
    }

Trigger families worth supporting (each maps to an existing hook):

- **Void fall** — the sharpest one for sky_islands/void dims. Options: kill (vanilla), teleport home safe, or drop-from-sky into the target (arrive at the target's top build height with slow-falling — very thematic for sky dims). Hook: Y-below-minY check in the existing `ServerWorldMixin` tick pass; must fire BEFORE vanilla void damage.
- **Death, generally** — "dying here sends you home instead of ending the run". Hook: `ServerPlayerEntity.getRespawnTarget` is already mixin-adjacent (the bed-exit work); a per-dimension respawn override is a natural extension. Death stops being final per-dimension: a nether-style dim where dying wakes you in your bed reframes the whole risk model. Keep vanilla keepInventory semantics orthogonal (that's a gamerule; note the interaction in docs).
- **Death by specific cause** — `death:lava`, `death:drowning`, `death:burning`, `death:fall`, `death:mob:<entity_id>` — the damage source is available at death time (`DamageSource` type ids map cleanly to config keys). Thematic exits: drown in the tidepools to surface in the shallows.
- **Action triggers** — ender pearl throw, swimming (time-in-water threshold), status effect held (e.g. leave the wisteria by sleeping, leave a spirit dim while invisible), falling from a height without dying. Each is an event hook + a small state tracker in the tick pass; gate the ambitious ones behind demand.

**Design principles** (from the discussion):

- Peaceful dims: leaving is always a free choice — exit portals stay; exit conditions ADD routes, never remove them.
- Never strand, never surprise-kill: every configured exit resolves to a safe arrival (surface-resolved like anchors); a dim configuring `void: kill` is explicitly opting into vanilla behaviour, not the default.
- `PortalSafetyValidator` grows with this: a dim whose ONLY exit is a death trigger warns (stranding-by-config again); cyclic links are fine (that's the point) but a link to a nonexistent dimension warns at boot in the fingerprint tone.
- The seed roller ignores all of it (`exits` is runtime-only) — `build_profile` passthrough test, same as the portal blocks.
- Persistence: exit conditions are config-driven and boot-re-read like portal config — no world wipes, applies to existing dims.

## Part 1 — the jigsaw shrine structure — ✅ SHIPPED 2026-07-23

Landed: `adventure:exit_shrine` jigsaw structure + `adventure:exit_shrines` set + template pool in the jar datapack; `scripts/gen-exit-shrine.py` generates the NBT template (own minimal NBT writer, deterministic gzip — rerun after design changes, rebuild the jar). Beacon centrepiece under the frame is the detection marker: `ExitShrineManager` scans chunk-load block entities, verifies the crying-obsidian ring in all four jigsaw rotations, lights + registers from the world tick (never the load event). The set ships at frequency 0.001 so base worlds (which bypass the DimensionStructures rebuild) can never generate one; opted-in dims (`exitShrines.enabled`) get a x1000 full-frequency copy, exempt from theme factors. `exit_shrine` joined the roller's STRUCTS + tier-1 frequency parity. Bot-verified end to end (generation 144 blocks out at spacing 24, detection under a rotated placement, traversal home). Open question resolved as leaned: spawn exitPortal stays the guarantee, shrines are scenery; per-dimension frame substitution went to further-portal-customisations.md.

Original notes below for reference. The pre-shipping context: the one unbuilt piece of the portal-concepts work (v3.3.0 shipped anchors, single-use portals, and mod-built exit portals). The mod-built exit portal at spawn is functional but plain — this is the pretty version.

## The idea

An `adventure:exit_shrine` jigsaw structure + structure set shipped in the mod's jar datapack (alongside the existing `adventure:void`/`wide`/ `compressed` presets): themed shrine ruins containing an exit portal frame, scattered through pocket dimensions. Placement spacing tuned per border size (a 256-radius pocket wants 1–2 shrines, not a grid).

## Integration points (all already exist)

- Jar datapack: `mods/custom-dimensions/src/main/resources/data/adventure/` — add `worldgen/structure/`, `worldgen/structure_set/`, and `structures/*.nbt` templates.
- Exit semantics: the shrine's frame should register like `ExitPortalManager` zones (exit-mode `"bed"` return targets), either by detecting the structure's frame blocks at world load or by placing a marker block the mod scans for.
- `DimensionStructures` rescales placements per `structureDensity` — the shrine set needs to be exempt or whole-set-drop-only, like other custom placement types.
- Seed roller: score `exit_shrine: near_spawn` in `seedRoll.wants` so rolled winners tend to have a shrine in walking distance (`dimension_profiles.py` already passes unknown structure names through).

## Open questions

- One shrine theme, or per-dimension frame-block substitution in the template (processor list swapping the frame material)?
- Does the guaranteed spawn exit portal stay when shrines are enabled ("both" in the concepts doc), or does `exitPortal.enabled` become `"shrines"` mode? Lean: keep both — the spawn exit is the guarantee, shrines are scenery.
