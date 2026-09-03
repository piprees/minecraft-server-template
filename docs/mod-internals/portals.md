# Portal internals (custom-dimensions)

Implementation rules for the portal subsystem. Read [`mods/AGENTS.md`](../../mods/AGENTS.md) first — it carries the short "never" list; this file carries the reasoning and the recipes. Player-facing config schema: [`mods/custom-dimensions/README.md`](../../mods/custom-dimensions/README.md).

## Who owns a portal

The most nuanced part of the subsystem. Read it before changing anything portal-shaped.

**Owning a portal means closing two vanilla entry points, not one.**

- `PortalManager.createTeleportTarget` decides the **destination**. `mixin/PortalDestinationMixin` wraps it; a null return is a clean no-op, because `tickPortalTeleportation` jumps past the teleport and `resetPortalCooldown` has already run, so cooldown and expiry survive.
- `onEntityCollision` runs **side-effects on contact**, and fires whatever the wrap answers. `EndGatewaySuppressionMixin` and `EndPortalSuppressionMixin` close it. `EndPortalBlock.onEntityCollision` calls `detachForDimensionChange`, which sends `GAME_WON` gated only on `seenCredits` — an unsuppressed End arrival rolls the credits and ejects the player to spawn.

**`vanillaManaged` documents a classic route without claiming it.** An entry marked `"vanillaManaged": true` is no ignition candidate, gets no adoption, no zone and no projection, and `isManagedPortal` answers false for it. It is set on `the_nether` (obsidian + flint and steel) and `the_end` (end_portal_frame + ender eye), and deliberately not on `overworld`, whose `mossy_stone_bricks` + torch portal is a mod route *to* the overworld rather than a classic route.

Two traps sit behind it, and neither is visible from the definition alone:

- `settings.frameNether` is `minecraft:obsidian`, so `getDefaultPortalForFrameBlock` re-claims every obsidian frame independently of the definition. Excluding the definition from `getPortalsByIgniter` alone looks correct and changes nothing.
- Adoption asks `vanillaManaged` definitions **first**. Ordinary definitions tried first hand a real vanilla nether portal to whichever dimension also builds from obsidian, and give it a traversal zone. Deliberate ignition is unaffected: a netherite ingot on obsidian still makes a sanctum portal, and an already-lit obsidian portal was lit with flint and steel, which is vanilla's route by definition.

**Adoption fires on contact and on approach, never on the arrival edge.** `enteredArrivalPortal` can never be satisfied by a vanilla portal — vanilla pins `portalCooldown` the instant you touch the block — so `EntityTickPortalMixin` keys adoption on `inPortal`, and `ServerWorldMixin.adoptPortalsOnApproach` offers portals off the nether-portal POI index before contact. `claimAttempt` dedupes per area per boot. The approach pass proves every column a fill could reach is resident first: `getBlockState` resolves through `getChunk(create=true)` and generates terrain on the calling thread.

**Presentation zones give a `vanillaManaged` portal a preview without ownership.** `PRESENTATION_ZONES` is a separate in-memory registry parallel to `PORTAL_ZONES`. Every consumer that decides *who teleports* reads `getSourceZones`, so the exclusions fall out of the structure rather than being a list to maintain. **Nothing reaches disk, on purpose**: a presentation zone in `portal_links.json` is read by an older jar as an ordinary zone, which would claim traversal on vanilla portals. Deploys roll back, so persisted state is a compatibility contract.

**`immersive` defaults to ON.** Absent means yes; opting out is an explicit `"immersive": false`, and deleting `"immersive": true` does nothing. That is the lever if preview load on every vanilla nether portal is too much.

## Zones and creation

- Frames are configurable blocks ignited with configurable items. Flood-fill scans up to 128 blocks in a plane (X or Z axis) bounded by frame blocks. Zones are validated every tick — a broken frame clears the portal.
- Coordinate scaling applies to the portal centre when the target-side portal is created. Player origins are tracked by UUID for return trips; target-side return links persist to `config/portal_links.json`.
- **Nothing is placed inside a portal, at either end.** The frame is the portal; its interior stays air. Frames are written with `Block.NOTIFY_LISTENERS | Block.FORCE_STATE` — `NOTIFY_ALL` cascades into a neighbouring piston. Arrival teleports are coordinate-based, so traversal can look fine while the arrival frame was never built — the Carpet loop below is what catches it.
- **Shared igniter items: every matching definition is a candidate.** `getPortalsByIgniter(item, clickedBlockId)` returns all candidates, clicked-frame match first, and ignition tries each. First-match-wins makes every shared-igniter portal but the alphabetically first dimension unignitable, silently.
- `PortalIgnitionMixin` hooks `ItemStack.useOnBlock` at HEAD; the clicked position must have an air block adjacent to the frame for flood-fill to find the shape.
- **An arrival is registered before a single block is touched.** Every pass that reads one — particles, projection, the return trip, the aura exclusion — asks `PORTAL_TARGETS`, so it must be true before the site is disturbed. `NetherPortalProtectionMixin` still defends a registered position from vanilla's obsidian-only re-validation, which is what an adopted vanilla portal inside a managed dimension needs.

## Arrival placement (`PortalSite`)

- **Ask the COLUMN, never the dimension type.** In a ceilinged world the search band runs from just under the roof down to the world floor, measured live: `findCeilingY` is the highest opaque block in the column (so the interior always has cover overhead and "standing on the roof" is unreachable by construction), `findRoofUndersideY` is the first open block below the contiguous roof slab (so an entombed column is not carved near the top of a forty-block mass).
- **Never start from `logicalHeight`** — it is a dimension-TYPE property, 128 for anything nether-shaped, and these generators ignore it. `the_boneyard`'s roof measures y≈180–190 with the playable floor near y≈100; a `bottomY + logicalHeight() - 2` start tops out at 126 and can miss the playable space entirely. Non-ceilinged dimensions keep the `findSurfaceY` heightmap start.
- **Never trust `World.getTopY` on an unloaded chunk** — it silently returns `bottomY`.
- **When no open site exists, CARVE — never fall back to the heightmap.** `MOTION_BLOCKING_NO_LEAVES` reads the roof in a ceilinged dimension, so `siteY = surfaceY` as a rescue path puts players on the nether roof at y=192. `PortalSite.findCarveY` re-searches the same band with the requirement relaxed from "already open" to "openable", preferring solid ground under the floor row; `createTargetPortal` lays a floor under a vertical arrival's bottom row when there is none. Carveability is defined ONCE and shared with `carveEgress`, so the search only promises sites the carve can deliver.
- Both failing means bedrock all the way down: **REFUSE the traversal** with a log line and an action-bar message rather than teleporting someone somewhere unopenable. The creation log says which was used (`[open site]` / `[carved site]`) — "carved" on every arrival in a dimension means its band is wrong again.
- **Resolve the arrival through `ArrivalResolver`, never the heightmap directly.** The player path lands at the existing arrival portal when there is one (`landY = existing.getY()`) and only consults `findSurfaceY` to build a new one — building that portal changes the heightmap it came from, because `createTargetPortal` places solid frame blocks above the top interior row. `ArrivalResolver` reads `PortalHelper`'s **in-memory** registered-target map (`findRegisteredPortalNear`, a `(5, 16)` box in `(x, z, y)` scan order) and falls back to the heightmap only for a column with no arrival yet. There is no block scan to fall back on: an arrival has no blocks in it.

## Reachability and borders

- **Checked at boot** (`ArrivalReachability` → `PortalSafetyValidator`). Entering DIVIDES by scale, so a source portal at radius R arrives at `R / scale` and must land inside the destination's PLAYER border. Outside it, vanilla forbids breaking AND placing every block and the symptom points nowhere near the cause.
- The margin is **0** on purpose: every dimension is authored as exactly `overworldBorder / scale`, so any margin warns about all 74 and stops being a warning.
- **Wire a validator and prove it runs.** A green build and a green test suite both report fine while a fully written check has zero callers.

## Breaking (`PortalBreakLink`)

- **Portals break at BOTH ends.** Mining the source frame clears the arrival; mining the arrival closes the source zone. The two ends match on the source column each arrival cell was stamped with at creation (`setSourceColumn`), never by inverting the scale transform.
- `PortalBreakLink.centreColumn` is the ONE definition of a zone's column, shared with `ServerWorldMixin` — two copies of that average drift and the break silently matches nothing.
- **Exempt:** anchor dimensions (one arrival shared by many sources; guarded by `hasAnchor()` on the source side and `exitMode != null` on the arrival side), exit portals and shrines (they carry an `exitMode`), and single-use expiry. Hence the trigger lives at the two places a player actually breaks something and NEVER inside `removeZone`, which `expireSingleUse` also calls.
- The source frame is deliberately left standing when its arrival breaks: deregistering closes the way, and the blocks stay the player's to mine or re-ignite.
- A counterpart in a cold chunk is deregistered immediately and its blocks queued for `processPendingBreaks` (loaded chunks only — never sync-load). Both paths `savePortalLinks()` immediately, or a broken portal survives in `portal_links.json` until a clean shutdown and returns after a crash.

## Anchor, single-use, exit portals

- **Anchor** (`portal.anchor`): every source portal lands at one fixed anchor; per-source target portals are suppressed. The anchor arrival's return targets carry an `exitMode` (`origin`/`bed`/`worldSpawn`) resolved by `EntityTickPortalMixin`; `bed` uses `getRespawnTarget(true, NO_OP)` (alive=true locates without consuming respawn-anchor charges).
- **Single-use** (`portal.singleUse`): the countdown lives on the zone (`singleUseTicksLeft`, -1 = unarmed), ticks in `ServerWorldMixin`, and persists in the `portal_links.json` zone record — written at countdown start and at shutdown, so restarts resume rather than reset. Decay-map resolution is pure logic in `PortalDecay` (unit-tested).
- **Exit portals** (`exitPortal`): `ExitPortalManager` builds/rebuilds a frame near dimension spawn, checked every 100 ticks from the world tick with a chunk-loaded guard (never sync-load a chunk just to inspect it). `PortalSafetyValidator` WARNs at boot when singleUse/anchor dimensions lack one — fingerprint tone: never crash, never auto-fix.

## Frame materials (`FrameMatcher`)

- `frameBlock` accepts a plain id, `#ns:tag`, a list, or `{"colorGroup": "<dye>"}` (16 `adventure:<colour>_blocks` tags ship in the jar datapack).
- **Accepting is not placing** — `framePlaceBlock` is what mod-built frames use. `orientation` gates ignition axes (absent = "any").
- Zone records are immutable snapshots of their ignition-time accept forms.
- **Persisted `PortalDefinition.frameBlock` must ALWAYS be a plain parseable id, never a `#tag`.** Accept forms belong in `frameAccepts`; `toPortalDefinition` enforces it and a unit test pins it. General rule: fields serialised into `portal_links.json` must stay parseable by every jar that might ever read them back — deploys roll back.

## Auras (`portal.aura.subsume`)

What an aura may convert: `none` | `natural` (default) | `everything`. Pure policy in `AuraPolicy`; the claim gate is `compat/ClaimsCompat`.

- **Claims are a hard veto ABOVE the policy, and `everything` does not bypass them.** Evaluated in `runPass` before `convertAt`, so it covers flora and fluids as well as replacements. One rule, no exceptions: claiming land IS the interaction.
- **Open Parties and Claims is reached by REFLECTION against its public server API** (`xaero.pac.common.server.api.OpenPACServerAPI`), never a compile dependency and never its internals — consumers assemble their own mod list. Every failure path answers "not claimed", so an upstream rename degrades to pre-claims behaviour rather than silently switching the aura off. ~2 lookups per 40 ticks per portal side, so no cache.
- **`natural` discriminates by BLOCK IDENTITY** (`#adventure:aura_protected`, a jar-datapack tag), never by history. A Ledger SQL query per candidate from the world tick is the same class of mistake as sync-loading a chunk, and a link-time terrain snapshot fails the motivating case outright (people build the house first and put the portal in the basement afterwards). Identity has no ordering hole and gives players a rule they can state.
- **The policy is resolved LIVE at use time** (`PortalAuraManager.subsumeFor`), keyed on the dimension whose nature is leaking — the same key for the source zone and the arrival site. The value persisted in a zone record is only a fallback for a dimension that has left the config. Without this, auditing the shipped set changes nothing on a running world, because every live portal is already lit.
- **`subsume` gates explicit `conversions` too.** A conversion map names what an author wants changed; it does not carry permission to eat a player's walls. An author who means that says `everything`.
- **`none` also withholds fire and fluids**, which only fill air and would otherwise pass a literal reading of "never replaces an existing block". Flora and configured trees still appear.

## Immersive portals (`portal.immersive`)

A presentation layer over an existing portal — see through it, hear the far side, throw things through. Server-side only; no client mod. Config schema: [README § Immersive portals](../../mods/custom-dimensions/README.md#immersive-portals).

- **Immersive is ON by default** (`ImmersiveSettings.fromJson(null)` returns `DEFAULTS`). An absent `immersive` field means immersive; `"immersive": false` is the opt-out. A malformed value also falls back to DEFAULTS rather than off — silently disabling a feature is this codebase's worst failure mode.
- **`immersive` is boot-re-read but `transient` on `PortalDefinition`** and deliberately not serialised into `portal_links.json`, so a restored zone deserialises with it null. `PortalHelper.restoreZones` re-stamps it from live config via `MultiverseConfig.getImmersiveFor(targetWorld)`. Stamping **null** is correct and load-bearing: it is how turning the setting off reaches existing zones.
- **The sightline mask reads the world, not the frame's shape** (`ProjectionVolume.occluders`). A block is projected when its shadow on the portal plane touches the aperture AND never leaves the aperture plus whatever really occludes around it. Centre-only leaks geometry past the frame edge at grazing angles; whole-shadow-inside-the-aperture hides anything clipping a frame CORNER, because a geometric ring built from the four in-plane offsets never contains the diagonals. Probing real block states also lets a portal set into a wide wall show the window that wall already occludes.
- **`seesThroughOpening`'s "eye is past the plane" shortcut must check the APERTURE, not the plane.** `viewerFarSide` only flips the slab on a block boundary, so there is a one-block band on the normal axis — infinite in the in-plane axes — where the eye is past the plane's midpoint while the slab is still on the far side. Treating all of it as "standing in the doorway" pops the whole slab in for anyone walking sideways past a portal, and it is the only path that ever shows the padded columns (fake blocks a player can collide with and mine).
- **The aperture carries light, nothing else.** `PlayerProjectionState` paints `LIGHT` at the portal's configured `lightLevel` over the aperture cells — a set that has no side, so it cannot flip as a player walks round the frame. `lightLevel: 0` leaves the aperture alone; there is no swirl to hide, in either direction.
- **Immersive portals THIN their particles, they do not lose them.** Both the source fill (`PortalHelper.spawnParticles`) and the arrival dust (`spawnTargetPortalParticles`, gated on `ImmersiveProjector.isImmersiveArrival`) emit one particle per cell every `IMMERSIVE_PARTICLE_INTERVAL` ticks instead of two per tick — about a twelfth of the density. Suppressing the interior fill entirely reads as a hollow box.
- **The opening's particles are drawn PER VIEWER and drift towards them.** The projection slab always sits on the viewer's far side, so dust sent the other way is behind it and correctly occluded — a broadcast loses half of it that way. `PortalAperture.driftSignToward` takes the side from the same block-coordinate test as `ProjectionVolume.viewerFarSide` and falls back to the even split only for a viewer level with the plane, who has no near side. The wire cost is unchanged: vanilla's broadcast overload already loops the world's players and gates each on 32 blocks, and the per-player overload applies that same gate.
- **Never sync-load a chunk from the projector, the audio pass, or entity pass-through.** All target-world reads go through `getChunkManager().getWorldChunk(cx, cz, false)`; null means skip. `PortalHelper.findSurfaceY` force-generates, so the immersive code deliberately reimplements its maths (`ImmersiveProjector.arrivalSurfaceY`, `EntityPassthrough.arrivalSurfaceY`) on an already-loaded chunk. A cosmetic preview must never be able to hang the tick loop.
- **Arrival chunks need a ticket, not just pre-generation.** The proximity pre-load generates a 5x5 grid, but with no ticket and no player there the outer ring unloads within seconds and the preview works exactly once. The projector holds a chunk ticket while any player is near, released on every teardown path, with a 100-tick expiry refreshed every 20 so a missed release self-heals rather than pinning chunks forever.
- **Anchor dimensions share one arrival between many source portals**, and a chunk ticket is a single entry keyed on `(type, level, argument)` — a release must check no other zone still wants that chunk, or the first teardown drops the ticket for every holder.
- **Fake blocks are client-only `BlockUpdateS2CPacket`s**, never placed in the world. `ChunkDeltaUpdateS2CPacket` looks like the batched answer but its only 1.21.1 constructor takes a `ChunkSection` and reads states out of the real world, so it cannot express fake states — don't "optimise" back to it. Cleanup must restore `world.getBlockState(pos)`, never a hardcoded AIR (a projection overlapping a real portal block must come back as the portal block).
- **Cleanup has six paths and all six matter**: out of range, zone removed (hook `PortalHelper.removeZone`, which covers frame-break and single-use expiry), disconnect, join (a relog in range otherwise leaves stale `lastSent`, making the delta pass a no-op against a client holding fresh real blocks), player world change, and target world unloaded. Leaked fake blocks persist until the player relogs.
- **Audio uses `world.playSound`, never game events.** `GameEventSuppressionMixin` drops every game event in a managed world with no players, and the target world usually has none. Sound is emitted in the SOURCE world at the portal centre, sampling biome data from the target.
- **There is no cross-portal weather relay and there cannot be one.** Every dimension this mod creates is built over an `UnmodifiableLevelProperties` wrapping the same main world properties (`DimensionManager.getOrCreateDimension`), exactly as vanilla does, so `isRaining()`/`isThundering()` read one save-wide flag. Vanilla's `/weather` also ignores `execute in <dim>` and always targets the overworld.
- **Entity pass-through detects a swept path, not a position.** A bow arrow covers ~3 blocks per tick and would tunnel through a one-block-thick portal. The interior is tested as a set of block positions rather than its bounding box, so irregular flood-filled frames don't grab things flying past a concave corner. Crossing is entry-EDGE triggered (like the player loop) and the "inside" set is recorded *before* the cooldown gate, or an entity waiting out its cooldown inside the zone re-fires the instant it expires.
- **Cross-dimension teleport RECREATES a non-player entity**, so anything set on the original reference afterwards is set on a corpse. Use `Entity.teleportTo(TeleportTarget)`: it carries velocity as a first-class field and returns the live arrival. Living entities are excluded wholesale — pathfinding, AI memories, leashes and spawn tracking are all world-scoped.
- **Distinguish air from unknown when sampling the target.** An unloaded chunk reads as "no block" exactly like air, and conflating them shrinks every preview to 2 blocks deep (`PlayerProjectionState.decideDepth` declines to decide below 75% known and measures its air threshold over known samples only). The same three-state discipline applies to any future heuristic over projected content.
- **Log counts, not just events.** The chunk-ticket and arrival-resolution bugs were only visible because the projector logs how many blocks it projected and the sampler logs its air/solid/unknown tallies. An "activated" line alone looks healthy in every broken state.

### Verification recipes (immersive)

Headless; the visual and audio quality checks need a human in-game.

```bash
# Mod DEBUG logging is off by default and audio/entity lines are DEBUG. It is an
# ENV VAR, not a file patch: log4j2-adventure.xml reads it via log4j2's
# Environment Lookup (${env:CUSTOMDIM_LOG_LEVEL:-info}). Patching the XML in the
# stack-config volume instead is reverted by every seed run
# (TROUBLESHOOTING.md#t12), taking the diagnostics with it.
echo "CUSTOMDIM_LOG_LEVEL='debug'" >> .env   # consumer repo
docker stop -t 60 mc && docker start mc      # restart is enough; no recreate needed

# Activation / teardown (INFO — always visible)
docker exec mc sh -c 'grep -E "immersive: (projection|holding|released)" /data/logs/latest.log | tail'
# Expect "projection activated ... (336 blocks)" for a 2x3 doorway at depth 8
# radius 2. A SHORT count on the first send is normal: the ticket loads
# asynchronously so the far chunk misses that tick and the delta pass fills it.
# Pin the arrival chunks with a second player to see the full 336.

# The load-bearing assert: fake blocks must never become real.
docker exec -i mc rcon-cli 'setblock <x> <y> <z+1> minecraft:gold_block'
docker exec -i mc rcon-cli 'execute if block <x> <y> <z+1> minecraft:gold_block'

# Entity pass-through (capture a baseline BEFORE the change, or the result is
# unfalsifiable). kill's output doubles as the arrival count and cleanup.
docker exec -i mc rcon-cli 'summon minecraft:item <zone x> <y> <z> {Item:{id:"minecraft:diamond",Count:1b},Motion:[0.0,0.0,-0.4]}'
docker exec -i mc rcon-cli 'execute in <ns>:<dim> positioned <ax> <ay> <az> run kill @e[type=item,distance=..48]'
# Load-bearing negative: a pig must NOT cross (living entities excluded).
```

Config negatives run the whole parse→gate path: `"immersive": {"enabled": true, "audio": false}` must give a projection with zero audio DEBUG lines, and `{"entityPassthrough": false}` must put entities back to not crossing.

## Carpet portal test recipes

The rig itself — setup, ignition, traversal, return trip, breaking, and the general gotchas — is [`.claude/skills/fabric-mod-development/references/carpet-bot-harness.md`](../../.claude/skills/fabric-mod-development/references/carpet-bot-harness.md). Portal-specific recipes it does not carry:

- **Pattern shapes:** build the template EXACTLY (the positive), then a same-material frame of a DIFFERENT valid free-form shape (the negative — flood-fill bounds it, only the template overlay rejects it).
- **`end_exit` shapes:** ring on the platform with a 3x3 air interior, bot in a CORNER cell. Assert the whole interior is still air after ignition — nothing is placed in one.
- **Exit-portal shapes:** grep `Built exit portal in <dim> at (x, y, z)`, then `if block` the RING cells (door = frame at y-1 and y+2, opening at y and y+1) and assert the opening is air.
- **A failed arrival-frame `if block` assert may mean conversion, not misplacement.** Any block-converting mod in the pack can rewrite frame materials within seconds of a portal appearing — stone becomes netherrack or nether ores, logs become crimson/warped stems, while quartz, deepslate and planks usually stay put. Assert arrival-frame materials IMMEDIATELY after traversal, and probe the suspect mod's conversion table before blaming placement: finding `crimson_stem` where you expected `oak_log` PROVES a log was placed.
