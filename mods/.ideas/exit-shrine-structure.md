# Exit shrines — jigsaw structure for the way home

The one unbuilt piece of the portal-concepts work (v3.3.0 shipped anchors,
single-use portals, and mod-built exit portals; see
new-portal-concepts.md §3 option 2). The mod-built exit portal at spawn is
functional but plain — this is the pretty version.

## The idea

An `adventure:exit_shrine` jigsaw structure + structure set shipped in the
mod's jar datapack (alongside the existing `adventure:void`/`wide`/
`compressed` presets): themed shrine ruins containing an exit portal frame,
scattered through pocket dimensions. Placement spacing tuned per border
size (a 256-radius pocket wants 1–2 shrines, not a grid).

## Integration points (all already exist)

- Jar datapack: `mods/custom-dimensions/src/main/resources/data/adventure/`
  — add `worldgen/structure/`, `worldgen/structure_set/`, and
  `structures/*.nbt` templates.
- Exit semantics: the shrine's frame should register like
  `ExitPortalManager` zones (exit-mode `"bed"` return targets), either by
  detecting the structure's frame blocks at world load or by placing a
  marker block the mod scans for.
- `DimensionStructures` rescales placements per `structureDensity` — the
  shrine set needs to be exempt or whole-set-drop-only, like other custom
  placement types.
- Seed roller: score `exit_shrine: near_spawn` in `seedRoll.wants` so
  rolled winners tend to have a shrine in walking distance
  (`dimension_profiles.py` already passes unknown structure names through).

## Open questions

- One shrine theme, or per-dimension frame-block substitution in the
  template (processor list swapping the frame material)?
- Does the guaranteed spawn exit portal stay when shrines are enabled
  ("both" in the concepts doc), or does `exitPortal.enabled` become
  `"shrines"` mode? Lean: keep both — the spawn exit is the guarantee,
  shrines are scenery.
