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

## Changing the fixture once it is up

Gate every fixture change on `destinationChunks >= 20` — never on a fixed sleep.
A destination's chunks are resident only while its projection is up, so a `fill`
issued before the feed comes up silently writes nothing while the script reports
success.

Trust the fill's own `Successfully filled N block(s)`, not a read-back: a
`data get` probe answers `That position is not loaded` for a chunk a `fill` has
just written correctly.

**A block change may not reach the client at all** — see [K11]. Until that is
fixed, a fixture change needs the player cycled to another world and back, which
is the only thing that clears the feed's sent-set.

## What the fixture cannot show you

The pair is degenerate on all three light axes at once: `sky 15/15`,
`block 0/0`, `fixedTime 6000`. A light-term defect that only appears below full
sky, or only with block light, or only away from noon, is invisible here by
construction and a reading taken here is not evidence the term is correct
([T113]). Add an occluder, a torch, or a non-noon clock before making that claim.

[T113]: ../../TROUBLESHOOTING.md#t113
[T114]: ../../TROUBLESHOOTING.md#t114
[K11]: ../../TROUBLESHOOTING.md#k11
