# The environment block, client side

Which of a dimension config's `environment` fields reach a companion client,
and by what route. Read [`portal-live-view.md`](portal-live-view.md) for what
the wire carries; this file says where a camera-rendered destination gets its
sky from.

Every claim here is read from source. Nothing in this file is a runtime
measurement.

Paths are relative to `mods/custom-dimensions/src/main/java/com/customdimensions/`
and `mods/custom-dimensions-client/src/main/java/com/customdimensions/client/`.

## The two routes

**`DimensionType`.** `dimension/DimensionTypeBuilder.build` (`:142-233`) folds
seventeen of the nineteen fields into a `DimensionType`, registered as
`{namespace}:{slug}_type` before any client logs in (`:42-45`). Vanilla
synchronises the `DIMENSION_TYPE` registry to every client, so those seventeen
are on the client for **every** dimension, not only the one the player is
standing in. `realtime/DestinationWorlds.ensure` (`:108-110`) reads exactly
that registry, keyed by `PortalFrame.dimensionType`, and builds the destination
`ClientWorld` from the entry it finds. A destination whose type is absent is
refused and logged once (`:112-118`).

**The wire.** `skyColor` and `fogColor` cannot go in a `DimensionType` — no
component carries them — so `DimensionTypeBuilder` parses and drops them with a
log line (`:86-90`). They ride `CompanionPayloads.Projection` and
`CompanionPayloads.PortalFrame` instead. The two copies of `CompanionPayloads`
are byte-identical apart from their package line.

## The table

| field | lands at | reaches the client | camera needs it | verdict |
| --- | --- | --- | --- | --- |
| `skyColor` | dropped, `DimensionTypeBuilder:86-90`; read by `companion/ProjectionStream.configuredColour:235-243` | wire — `Projection:136`, `PortalFrame:178` | yes — the sky behind the scene | CARRIED |
| `fogColor` | dropped, `DimensionTypeBuilder:86-90`; read by `ProjectionStream.configuredColour:235-243` | wire — `Projection:137`, `PortalFrame:179` | yes — the backdrop colour | CARRIED |
| `ambientLight` | `DimensionTypeBuilder:228` | registry sync | yes — the floor under every light level | VANILLA-SYNCED |
| `fixedTime` | `DimensionTypeBuilder:211-214` | registry sync | yes — a locked sun, and the only clock the destination has | VANILLA-SYNCED |
| `hasCeiling` | `DimensionTypeBuilder:216-217` | registry sync | yes — fog treatment and whether a sky is drawn | VANILLA-SYNCED |
| `hasSkylight` | `DimensionTypeBuilder:215` | registry sync | yes — sky light propagation and sky rendering | VANILLA-SYNCED |
| `minY` | `DimensionTypeBuilder:149` | registry sync | yes — the `ClientWorld`'s section count | VANILLA-SYNCED |
| `height` | `DimensionTypeBuilder:150` | registry sync | yes — same | VANILLA-SYNCED |
| `effects` | `DimensionTypeBuilder:162-173` | registry sync | yes — picks overworld / nether / end sky rendering | VANILLA-SYNCED |
| `ultraWarm` | `DimensionTypeBuilder:218` | registry sync | no — water evaporation, lava flow | VANILLA-SYNCED |
| `natural` | `DimensionTypeBuilder:219` | registry sync | no — compasses, sleep, portal spawning | VANILLA-SYNCED |
| `bedWorks` | `DimensionTypeBuilder:221` | registry sync | no | VANILLA-SYNCED |
| `respawnAnchorWorks` | `DimensionTypeBuilder:222` | registry sync | no | VANILLA-SYNCED |
| `piglinSafe` | `DimensionTypeBuilder:230` | registry sync | no | VANILLA-SYNCED |
| `hasRaids` | `DimensionTypeBuilder:231` | registry sync | no | VANILLA-SYNCED |
| `logicalHeight` | `DimensionTypeBuilder:151` | registry sync | no — portal and chorus teleport limit | VANILLA-SYNCED |
| `infiniburn` | `DimensionTypeBuilder:175-188` | registry sync | no | VANILLA-SYNCED |
| `monsterSpawnLightLevel` | `DimensionTypeBuilder:190-199` | registry sync | no | VANILLA-SYNCED |
| `monsterSpawnBlockLightLimit` | `DimensionTypeBuilder:200-209` | registry sync | no | VANILLA-SYNCED |

**No field is MISSING.** Every one of the nineteen either rides the registry
sync or is explicitly on the wire.

`minY` has a second reader: `web/RollPipeline.assumedFloorY:1017-1024` uses it
as the seed roller's assumed floor. `skyColor` and `fogColor` have a third:
the seed viewer draws them as swatches (`web/ViewerPage.java:56-57`, `:920-926`).

## The five colours on the wire

Sky and fog prefer the CONFIG; the three tints are biome-only.

| wire field | source | file:line |
| --- | --- | --- |
| `skyColor` | `environment.skyColor` when it parses as six hex digits, else the arrival biome's `getSkyColor()` | `ProjectionStream:136`, `:178` |
| `fogColor` | `environment.fogColor` when it parses, else the arrival biome's `getFogColor()` | `ProjectionStream:137`, `:179` |
| `grassColor` | biome at the arrival column, always | `ProjectionStream:138` |
| `foliageColor` | biome at the arrival column, always | `ProjectionStream:139` |
| `waterColor` | biome at the arrival column, always | `ProjectionStream:140` |

`configuredColour` (`:235-243`) returns -1 for an absent, blank, non-six-digit
or non-hex value, which is the same sentinel as "no biome resolved", so an
unparseable authored colour falls back to the biome silently. One biome serves
the whole volume — the arrival column's, sampled once per payload
(`ProjectionStream.biomeAt:220-228`). There are no `grassColor`, `foliageColor`
or `waterColor` fields in `Environment` to override them with.

The client consumes them at `render/ProjectionView.getColor:85-95` (the three
tints, falling through to the source world when -1) and
`render/ProjectionRenderer.backdropPolygon:474-478` (fog, falling back to sky,
then to black).

## The aura ignores the authored fog colour

`immersive/DestinationGlow.sample:43-50` takes the destination's tint from
`targetWorld.getBiome(arrivalPos).value().getFogColor()` and nothing else. It
never consults `environment.fogColor`, so a dimension that authors one gets it
on the projection backdrop and not in the colour leaking out of the frame,
which `DestinationGlow.applyTo:95-102` blends 60% of the way towards the biome
value by default.

The two paths disagree about the same concept. `ProjectionStream` treats the
config as authoritative and the biome as the fallback; `DestinationGlow` treats
the biome as the only answer.

## What a camera still has no source for

Neither is an `environment` field, and both bear directly on a rendered
destination scene.

**Time of day.** `DestinationWorlds.ensure:121-134` builds the destination
`ClientWorld` with `new ClientWorld.Properties(Difficulty.NORMAL, false, false)`
and never writes a time into it. Nothing in the client mod calls `setTime`.
Vanilla's time packet updates the player's own world only, so a destination
without `fixedTime` in its `DimensionType` renders at a fixed time 0 — and
`fixedTime` is precisely the field most dimensions leave unset.

**Weather.** The same `Properties` construction leaves rain and thunder at
their defaults, and nothing feeds them. One save-wide weather state serves
every dimension server-side; none of it reaches the second `ClientWorld`.
