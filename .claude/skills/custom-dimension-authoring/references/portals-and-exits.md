# Portals and exits

Everything about how a player gets INTO a dimension and back OUT of it: the
aura policy, immersive portals, exit portals and shrines, single-use and
anchored portals, and exit conditions. The portal fields themselves (frame,
igniter, colour, scale, sounds, shape) are in `schema-reference.md`; the
scale/border pairing is in `SKILL.md` § Portal scale guide.

A dimension may declare several portals: `portal` is one object, or an array of
them, each a full definition. The first entry is the primary and governs the
aura, immersive and exit presentation below for every link into the dimension.
Ordering, ids and the shared-`scale` rule: `schema-reference.md` § `portal`
object.

## One frame block, one dimension

A frame block belongs to exactly one portal definition across the whole config
set. Two definitions sharing one is an ERROR — `portal_frame_shared`,
`portal_frame_reserved` or `portal_igniter_collision`, all three failing
`customdim lint` and the smoke-test gate.

A distinct igniter does not rescue a shared frame. Ignition knows which item
was used and can pick the right definition; an already-lit portal carries no
record of what lit it, so adoption falls to whichever definition comes first in
config order. That is how a real vanilla nether portal was adopted as
`adventure:the_obsidian_sanctum`.

Igniters are unconstrained — 27 dimensions share `minecraft:amethyst_shard`.
Every form of a list frame is claimed, and `framePlaceBlock` must be one of
them. Pick from `config/custom-dimensions/extractors/blocks.json`, choose
something the dimension's own theme argues for, and check it is unclaimed.

**Lighting a portal does not cost the igniter.** A damageable one loses a
point of durability, as vanilla treats a flint and steel; anything else is
untouched. `"consumesIgniter": true` takes the item instead — eye-of-ender
semantics, and a design statement that entry is priced. Nothing ships with it,
so a dimension that wants it is saying something new about itself.

## `vanillaManaged` — the classic route, left alone

`"vanillaManaged": true` on a portal entry means vanilla runs that portal. The
mod keeps the entry so the dimension's list documents how players actually get
there, and touches nothing else: the frame is not claimed at ignition (vanilla's
own `useOnBlock` runs, so an ender eye fills a stronghold socket and the twelfth
one opens the portal), the portal is never adopted into a zone, vanilla picks
the destination, and the entry carries no immersive projection and no `scale`.

`the_nether` (obsidian + flint and steel) and `the_end` (end portal frame +
ender eye) ship this way, and those two blocks are theirs alone — a
`vanillaManaged` definition holds its frame outright, so the other side of a
clash is unreachable by adoption and it is always the other side that moves. `overworld` does not: its `mossy_stone_bricks` portal
is a mod portal leading TO the overworld, not a classic route.

A `vanillaManaged` entry is skipped by the per-target lookups, so a second,
ordinary entry in the same list still gives that dimension a full mod route —
its own frame, igniter, colour, aura and immersive settings — and it is that
entry the presentation, immersive and aura-subsume lookups answer with. The
`vanillaManaged` entry does stay the list's primary for `exitPortal` and
`exitShrines` construction, so a dimension that builds either wants an ordinary
primary.

## `portal.aura.subsume` — what the aura may eat

Every linked portal grows an **aura**: it slowly converts blocks in an annulus around both ends, so the two worlds bleed into each other. `subsume` decides what it is allowed to convert, and **it is a design statement about the dimension, not a safety setting**.

```jsonc
"portal": {
  "aura": { "subsume": "everything" }   // "none" | "natural" (default) | "everything"
}
```

| Value | Behaviour | Use for |
| --- | --- | --- |
| `none` | Never replaces an existing block. Still adds flora to bare ground, but no fire and no fluids. | Infrastructure and discretion — `exitPortal` fixtures that must stay recognisable landmarks, and pocket dims meant to be unassuming and easy to hide. |
| `natural` (default) | Converts natural terrain; never anything crafted or shaped. A beach portal takes the sand and leaves the cobblestone wall standing in it. | Everything ordinary. |
| `everything` | Converts whatever it reaches, player builds included. | **Dangerous worlds.** The encroachment IS the story. |

**When to reach for `everything`.** Any dimension that is aggressive, ultra-hard, or whose description names consuming, feeding, corruption, blight, sculk or the void. Opening a portal to one of those should carry visible risk — the dimension telling the truth about itself. The shipped set was audited on exactly that rule: `difficulty.mobMultiplier >= 2.0` **or** a description that names one of those forces. 24 of 84 dimensions qualify.

Two judgement calls that rule does NOT make for you, both real cases from that audit: `the_dripping_pines` ("ruins **rotting** under the pines") and `the_glacial_drift` ("islands calving into the **void**", `mobMultiplier` 0.8) both hit a keyword while being gentle, scenic dimensions. The word describes scenery, not a force acting on the world. Both keep the default. **Read the description; don't just match the word.**

**Say so in the description.** An `everything` dimension is a promise that a build near its portal is at risk. That belongs in the player-facing text, not just the JSON.

**Claims are an absolute veto, and `everything` does not bypass them.** The mod asks Open Parties and Claims before converting anything; a claimed chunk is never touched, under any policy. One rule, no exceptions — an exception would make the guarantee unexplainable to players. This is also what makes the feature fair: claiming land is how a player says "I am prepared to host this thing", and choosing not to claim is a decision with consequences.

**How `natural` tells crafted from natural**: the `#adventure:aura_protected` block tag, shipped in the mod's jar datapack. Planks, wool, concrete, bricks, cobblestone, stone bricks, polished/cut/smooth variants, metal and copper blocks, glazed terracotta and friends are all in it. Plain stone, dirt, sand, gravel, deepslate and logs are not — those are the world, and the aura is allowed to spread through the world. Extend it from a consumer datapack rather than editing the mod.

**There is no revert.** Original block states are deliberately not persisted. Claims are the protection mechanism; rebuilding is quick.

## Immersive portals (`portal.immersive`) — ON by default

Every portal is immersive unless it says otherwise. You see the destination's terrain through the frame, hear its biome ambience, and can throw items through. Server-side only — no client mod, and a vanilla client gets all of it.

```jsonc
"portal": {
  // nothing at all           -> immersive, every default. This is the norm.
  "immersive": false          // the opt-out
  // "immersive": {
  //   "previewDepth": 8,     // blocks projected behind the frame (1-16)
  //   "previewRadius": 2,    // how far the view cone may widen (0-4)
  //   "refreshInterval": 4,  // ticks between delta refreshes (min 2)
  //   "activationRange": 24, // blocks from the portal (1-64)
  //   "audio": true,         // far-side biome ambience
  //   "entityPassthrough": true  // any entity crosses, velocity preserved
  // }
}
```

**Write `"immersive": false` only for a deliberate reason**, and say what it is in a comment or the description. Legitimate reasons: a dimension whose portal should read as mundane, or one whose destination is visually meaningless (pure `void` type — you would be projecting nothing).

**`previewDepth` is a look, not a performance dial.** 8 is the sweet spot. 16 shows lighting artefacts; 4 reads as wallpaper rather than a window. Don't lower it to "save" anything — the projection only runs for players within `activationRange` and sends deltas after the first pass.

**It is boot-re-read**, like the rest of the portal block, so it applies to dimensions that already exist without a world wipe.

Known limitations, all inherent to the server-side approach and all documented in `mods/custom-dimensions/client/SPEC.md`: no entities visible on the far side, source-dimension lighting and biome colours, block entities without their contents, geometry snapping as you walk (block is the granularity), and the vanilla loading screen on the actual transition. None of these are worth reporting as bugs.

## Advanced features (use only when the theme requires them)

These features appear in 0-1 of the 84 shipped dimensions. Don't add them speculatively — only when the theme specifically calls for the mechanic. Full schema in `references/schema-reference.md`.

### Exit portals and exit shrines

- **`exitPortal`**: `{"enabled": true, "pos": "spawn", "target": "bed"}` — the mod builds and maintains a portal frame near the dimension's spawn so players can get home. Particularly important for dims with `singleUse` or `anchor` portals where the entry portal may be destroyed or one-way.
- **`exitShrines`**: `{"enabled": true, "target": "bed"}` — scattered jigsaw exit ruins throughout the dimension. Worldgen is creation-time; the beacon detection that actually teleports players is boot-re-read.

### Portal auras — tuning only

The aura runs by default and needs no config. This block is for tuning it; the policy question (**what it is allowed to eat**) is `subsume`, documented in its own section above because every dimension has an answer to it.

- **`portal.aura`**: `{"enabled": false}` switches it off entirely.
- Tunables: `radius` (default 8, max 32), `interval` (ticks, default 40, min 10), `blocksPerPass` (default 2, max 16), `budget` (lifetime conversions per side, default 300, `-1` = endless), `sides` (`"source"`/`"target"`/`"both"`).
- Palette overrides: `palette` (terrain block ids), `flora`, `trees` (ConfiguredFeature ids), `fluids`, `conversions` (`{"from": "to"}`, `from` may be `#tag`), `fireChance` (0-1, default 0).

**A palette is an override, and no palette is not a gap.** With none set, the aura samples the far side when the link is made: the source portal takes the destination's terrain from around the arrival, and the arrival takes the source's. The leak is therefore derived from where that portal actually opened and differs between two portals into the same world. Setting `palette` fixes it — for every portal into the dimension, and, via `emissionOverrideFor`, for what the dimension leaks into anywhere you travel to from inside it. Set one only when the dimension should impose the same look wherever you stand.

**Palette order is significant.** `palette[0]` is the block an exposed position takes; buried positions take a random member of the rest. Put the surface block first.

**Trees are never inferred**, only planted from an explicit `aura.trees`. A sampled tree palette turned a beach portal into an impassable dark-oak thicket — a tree's footprint is orders of magnitude bigger than the block that seeded it. Set `trees` only when a forest IS the effect you want.

**Test at a sped-up cadence**, never the default: `{"interval": 10, "blocksPerPass": 8}`.

### Single-use portals

- **`portal.singleUse`**: `{"enabled": true, "delaySeconds": 10, "breakMode": "decay"}` — the portal self-destructs after first traversal. `breakMode`: `"destroy"` (removed), `"decay"` (blocks swap via `decayMap`), `"partial"` (1-2 blocks decay, repairable). Pair with `exitPortal` or `exitShrines` so players can get home.

### Anchored portals

- **`portal.anchor`**: `{"pos": "spawn", "exit": "bed"}` — every source portal lands at one fixed position in the destination, rather than matching coordinates. Good for pocket dims that are essentially a single room/arena.

### Exit conditions

- **`exits`**: Map of trigger → action. Triggers: `"void"`, `"death"`, `"death:lava"`, `"enderPearl"`, `"fallFrom"`. Actions: `"teleport"` (intercepts the event), `"respawnAt"` (die normally, respawn at target), `"kill"` (void trigger only). Example: `{"void": {"target": "origin", "action": "teleport"}}`.
