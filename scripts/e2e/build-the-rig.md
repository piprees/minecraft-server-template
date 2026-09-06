# How to build the rig

The rig is the only fixture whose blocks may be edited. Every destination-side
measurement — light curves, face shades, view depth, the companion feed — reads
through it. It has three parts and **all three must be present**; each one
missing fails silently, in a way that looks like a null result rather than a
broken instrument.

```bash
./scripts/e2e/build-e2e-portal.sh          # dimensions, frame, ignition, link
./scripts/e2e/rig-quiet.sh                 # freeze weather, time and mobs
./scripts/e2e/rig-ready.sh                 # refuse to measure until it is
```

## What the rig is

| part | what it is | built by |
| --- | --- | --- |
| the pair | `elfydd:e2e_one` (source) and `elfydd:e2e_two` (destination), flat superflats | the dimension configs in the consumer's `overlay/` |
| the arena | flat single-colour floor and walls, so nothing in frame changes but the portal | `build-test-arena.sh --dim … --centre … --confirm` |
| the portal | a `magenta_concrete` frame in `e2e_one`, lit with an amethyst shard | `build-e2e-portal.sh` |

**The frame is made of the DESTINATION's block.** `e2e_two` declares
`magenta_concrete`, so that is what a frame leading to it is built from — not
`e2e_one`'s lime. Getting this backwards builds a frame that ignites into the
wrong world or not at all.

## Build the portal

```bash
./scripts/e2e/build-e2e-portal.sh --dry-run   # prints the site, touches nothing
./scripts/e2e/build-e2e-portal.sh
```

It prints `PORTAL-BUILT` when `portal_links.json` gains an `e2e_two` entry, and
`PORTAL-NOT-LINKED` with the two things to check when it does not.

The order inside it is load-bearing and is the part that is easy to get wrong:

1. **`/customdim load e2e_one` and `e2e_two`.** Dimensions are created on
   demand. `execute in <dim> run …` does **not** create one — it answers
   `Unknown dimension` and the command behind it never runs ([T114]).
2. **Spawn the Carpet bot BEFORE filling any blocks.** A loaded dimension is not
   resident chunks. With nothing ticketing the area, `fill` answers
   `That position is not loaded` and writes nothing. The bot is what makes the
   chunks resident.
3. **Fill the frame ring, then carve the opening back to air.**
4. **Put the igniter in `hotbar.0` explicitly.** `give` fills an inventory
   without selecting the slot, so `use once` fires whatever was already held —
   which once placed a torch inside the opening and made ignition refuse with
   `OPENING_NOT_ENCLOSED`.
5. **Ignite from inside the opening**, looking down at the bottom frame block.
   Near-horizontal aims at eye-level frame blocks miss inconsistently.

## Verify it — three things, all of them

A link in the file is not a working instrument. All three must hold:

```bash
# 1. the link exists
docker exec mc sh -c 'cat /data/config/portal_links.json' | grep -c e2e_two

# 2. a projection exists for the right destination
curl -s http://127.0.0.1:8766/state | python3 -c "
import json,sys
for p in json.load(sys.stdin).get('projections',[]):
    print(p.get('destination'), p.get('aperture'))"

# 3. the feed actually fills
curl -s http://127.0.0.1:8766/state | python3 -c "
import json,sys; print(json.load(sys.stdin)['realtime']['destinationChunks'])"
```

Expected: a non-zero count, `elfydd:e2e_two` at aperture `[4, -60, 4]`, and
`destinationChunks` climbing to 20+ within a couple of minutes of standing at
`5.5 -60 1.0` facing yaw 0.

**`destinationChunks 0` means there is no instrument.** Abort; do not read the
frame as evidence of anything.


## Coordinates, and how to stand in them

Everything below is measured from the live fixture, not assumed. `FLUXXINATED`
is the character to move — the screenshots come from the real game client's
framebuffer, so the pose has to be the real player's.

### The rig portal

```
source        elfydd:e2e_one          destination   elfydd:e2e_two
axis          X                       frame block   minecraft:magenta_concrete
igniter       minecraft:amethyst_shard              (not consumed)
floor         y = -61                 fixed time    6000 (noon, from the config)
ambient       e2e_one 0.25            e2e_two 0.0

aperture      the 6 interior cells    x 4-5, y -60..-58, z 4
frame ring    x 3-6, y -61..-57, z 4
```

### Standing at it

**The canonical pose.** Every luma figure quoted in this repo was taken here:

```
/execute in elfydd:e2e_one run tp FLUXXINATED 5.5 -60 1.0 0 6
```

`5.5 -60 1.0` is three blocks back from the frame, centred on the opening, with
yaw 0 (facing +z, straight through) and pitch 6 (just below level). At this
pitch the sightline does not reach the destination FLOOR until about z 16, so
the aperture is filled by the exposed cut face of the block at z 7 — a **side**
face at vanilla's 0.8 shade, not a floor at 1.0. Comparing it against a source
floor is comparing two orientations, which is what produced a phantom "18%
deficit" for most of a day.

### The poses that have caused trouble, and what each is for

| pose | command | what it shows |
| --- | --- | --- |
| canonical | `tp FLUXXINATED 5.5 -60 1.0 0 6` | the reference crop, aperture y 400-590 x 755-876 at 1708x1016 |
| floor-filling | `tp FLUXXINATED 5.5 -60 1.0 0 25` | pitch 25 puts the destination FLOOR in the crop — a top face at 1.0, the right surface for any same-orientation comparison |
| grazing | `tp FLUXXINATED 5.5 -60 1.0 0 -5` | looks up through the opening at sky and the backdrop alone |
| off-axis | `tp FLUXXINATED 5.5 -60 1.0 12 6` | yaw 12 from the same eye: the sweep that disproved the shadow. Move the ANGLE, never the position |
| stepped back | `tp FLUXXINATED 5.5 -60 -4.0 0 6` | a smaller aperture on screen; use when the crop must exclude the frame entirely |

**Return the character afterwards, every time:**

```
/execute in minecraft:overworld run tp FLUXXINATED 3460.88 80.0 2593.042 -99.893 15.644
/gamemode survival FLUXXINATED
```

That is the maintainer's own pose at the reaches portal, in survival. Spectator
is fine for a measurement; leaving them in it is not.

### The live reaches portal — where depth and partial light ARE measurable

Its destination has generated terrain and a sky range the grey box does not:
`meshLight` reads sky 0–15 over 76,468 cells, mean 11.6, block max 7. **Its
blocks may not be edited** — stand in it, park a bot in it, measure it.

It sits in a carved chamber, roughly x 3456–3472 by z 2586–2598 at y 80, open to
the sky. **Every computed walk-out candidate lands inside solid rock**, so use
the two positions that are verified clear: `3460, 98, 2630` to walk out (41.4
from centre) and `3457, 80, 2596` to park a bot (7.9 from centre, behind the
eye).

### Shaders on or off — say which

`enableShaders` in the Prism instance's `config/iris.properties` decides it, and
**a reading is meaningless without stating which it was**. Off isolates a
mechanism from the pack; on is what the player actually sees. Numbers taken in
one mode do not transfer to the other, and most of this repo's mechanism figures
are pack-off.

## Changing the fixture once it is up

Gate every fixture change on `destinationChunks >= 20` — never on a fixed sleep.
A destination's chunks are resident only while its projection is up, so a `fill`
issued before the feed comes up silently writes nothing while the script reports
success.

Trust the fill's own `Successfully filled N block(s)`, not a read-back: a
`data get` probe answers `That position is not loaded` for a chunk a `fill` has
just written correctly.

**A block change reaches the client on the next feed pass** — a changed chunk is
dropped from every viewer's record and resent. Watch `destinationChunkAccepts`
on `/state`: it climbs on a resend while `destinationChunks` holds flat, which is
what separates a real update from a lucky repaint. A chunk that changes
continuously (flowing water, a redstone clock) is re-offered every pass and eats
into the 4-chunk budget, so a busy destination fills its wedge more slowly.

## What the fixture cannot show you

The pair is degenerate on all three light axes at once: `sky 15/15`,
`block 0/0`, `fixedTime 6000`. A light-term defect that only appears below full
sky, or only with block light, or only away from noon, is invisible here by
construction and a reading taken here is not evidence the term is correct
([T113]). Add an occluder, a torch, or a non-noon clock before making that claim.

**Depth cannot be measured here.** `e2e_two` answers `data get block` at x=128
and `That position is not loaded` at x=256, so it holds roughly 8–16 chunks
around the bot and there is nothing further out to feed whatever the radius. A
`maxRenderDistance` or `viewDepth` sweep reads identical at every value, and that
is the fixture, not the setting. Measure depth at a portal whose destination has
real generated terrain.

## Rules any rig build has to satisfy

The fixture's own values are chosen to keep the code under test reachable. Each
of these has been got wrong, and each failure looked like a clean null result.

- **Neither dimension's `ambientLight` may be 1.0.** `AmbientLift.lift` and
  `UnshadedDestination.scale` both early-return there, so the fixture bypasses the
  code it exists to exercise.
- **The two ambients must differ.** `lift` reads the SOURCE's and `scale` reads
  the DESTINATION's, so equal values make a wrong-side read look correct — and
  that is this area's whole bug class.
- **The two `fixedTime` values must differ**, or a borrowed clock is invisible.
- **The source needs a real sun.** `0` is 06:00, `6000` noon, `12000` 18:00,
  `18000` midnight — `22000` is 04:00, not dusk.

[T113]: ../../TROUBLESHOOTING.md#t113
[T114]: ../../TROUBLESHOOTING.md#t114
