---
title: Carpet bot harness
description: The full headless fake-player test rig for player-dependent Fabric mod paths — ignition, traversal, breaking, shape/material/aura/gateway recipes, and every gotcha found running it
tags: [carpet, fake-player, rcon, portal, traversal, headless-testing]
---

# Carpet bot harness

Paths that only trigger on real player presence (portal traversal, zone entry, presence timers) can be tested headlessly by puppeting a Carpet fake player over RCON. This turned "needs a human in-game" into an automated loop and has caught bugs code review missed — most notably the portal self-destruction bug found 2026-07-13 (see mods/AGENTS.md § Portal system): the bot arrived fine but could never return, and nobody would have found that by reading the diff.

## Setup

**Carpet ships as a platform default** (`config/modrinth-mods.txt`) and is already loaded on any booted server — there is nothing to install and no extra restart needed to get it. (An older procedure documented side-loading a `TEMP-carpet-test.jar` before Carpet became a default mod; that step is obsolete — check `docker exec mc rcon-cli "carpet"` responds before assuming you need to install anything.)

Carpet's known crash next to Supplementaries (any piston moving a block entity) is pre-patched out by `scripts/patch-mod-data.py` on every deploy and every `./dev up` — see `docs/known-issues/carpet-supplementaries-piston-crash.md`. Don't re-diagnose that crash if you hit it; re-run the patch and confirm it applied (`PistonBaseBlock_movableBEMixin` absent from the installed jar's `carpet.mixins.json`).

```bash
docker exec -i mc rcon-cli 'carpet commandPlayer true'
docker exec -i mc rcon-cli 'player Bot spawn'          # async — wait ~3s, verify with "list"
```

## Building and igniting a portal frame

```bash
X=2000; Y=80; Z=2000
docker exec -i mc rcon-cli "tp Bot $((X+1)).5 $((Y+1)) $Z.5"
# ... place frame blocks via setblock/fill ...
docker exec -i mc rcon-cli 'item replace entity Bot hotbar.0 with minecraft:cherry_sapling 8'
docker exec -i mc rcon-cli 'player Bot hotbar 1'
docker exec -i mc rcon-cli 'player Bot look west'      # look at frame wall from INSIDE
docker exec -i mc rcon-cli 'player Bot use once'        # right-click to ignite
```

## Asserting traversal

```bash
sleep 10                                                 # dimension creation takes several seconds on first visit
docker exec -i mc rcon-cli 'data get entity Bot Dimension'   # expect the target dimension
docker exec -i mc rcon-cli 'data get entity Bot Pos'
```

## Return trip

```bash
docker exec -i mc rcon-cli 'tp Bot <x+5> <y> <z>'      # step out of portal zone
sleep 5                                                  # wait for cooldown to clear
docker exec -i mc rcon-cli 'tp Bot <portal_x> <portal_y> <portal_z>'  # step back in
sleep 8
docker exec -i mc rcon-cli 'data get entity Bot Dimension'   # expect overworld
```

## Portal breaking

```bash
docker exec -i mc rcon-cli 'setblock <frame_x> <frame_y> <frame_z> air'  # break one frame block
sleep 3
# The zone is gone from portal_links.json, at BOTH ends — a frame with a hole
# in it is not a portal, and there is nothing inside one to check
```

## Every gotcha, in one place

- **Shared igniter items: every matching definition is a candidate.** If several dimensions share an igniter item (e.g. `ender_eye`), ignition must try each candidate, clicked-frame match first — a first-match-wins implementation makes every dimension except the alphabetically first unignitable, and it fails silently (found 2026-07-23 via the bot loop; eight dims share `ender_eye`).
- **Aim from INSIDE the frame, `look down` at the bottom frame block.** Near-horizontal aims at eye-level frame blocks miss inconsistently, regardless of frame size. If an ender-eye igniter click misses, vanilla throws the eye and runs a synchronous stronghold locate on the main thread (~30s+ stall that looks like a hang) — check `data get entity Bot Dimension` before assuming the server died.
- **Zone-injection shortcut** (when ignition isn't the thing under test): stop `mc`, hand-append `source-zone-v1` records to `data/config/portal_links.json` (frame blocks must exist; interiors can stay air — validity only checks the frame ring), start `mc` — the restore path registers them and `tp Bot` into the interior drives traversal.
- **The bot must be INSIDE the frame looking at the frame wall.** From outside, an item like `cherry_sapling` plants itself on the adjacent block instead of triggering ignition — the clicked position needs an air block adjacent to the frame for flood-fill to find the shape.
- `player Bot spawn at ...` may ignore the position — `tp` after spawning instead.
- Vanilla resets portal cooldown every tick while an entity stands IN a portal, so return trips need step-out → wait cooldown (check `data get entity Bot PortalCooldown` = 0) → step-in.
- **Assert with `data get entity` and log greps** (`docker exec mc cat /data/logs/latest.log`), never RCON chat echoes.
- **Tag/colour frame recipes**: build the frame from ANY member block (tag breadth is part of the assertion — e.g. oak AND birch for `#minecraft:logs`; two wool/terracotta colours mixed for a colour group), plus a non-member negative (stone) that must NOT ignite. Orientation configs need a rotated-frame negative (a Z-axis frame under `vertical_x` must fail). Arrival-frame material asserts `framePlaceBlock` (`execute in <dim> if block <frame pos> <place block>`).
- **Per-part material recipes**: the positive frame mixes tag members WITHIN a part (oak + birch side columns for `"sides": "#minecraft:logs"`). The load-bearing negative is a UNION-VALID frame with one block in the wrong PART (e.g. stone in a side column when stone is the bottom material) — flood-fill still bounds it, only the per-part classifier can reject it. Arrival/exit frames assert per-part placement.
- **Gateway recipes** (`end_gateway` shape): a ring of the declared frame block around exactly ONE air cell, in one plane only — leave the two cells on the normal axis open, or the flood-fill bounds on every axis. Aim with `player Bot look at <bottom frame block centre>` from the cell in front of the opening; `use once` ignites. Assert the opening is still air, then `tp Bot` into it for traversal. The negative that matters is an ender eye on a stronghold's `end_portal_frame`: no gateway frame, so the mixin declines and the eye reaches vanilla and sockets.
- **Aura recipes**: use a DISTINCTIVE far side (e.g. an end_stone superflat) so the derived leak is unambiguous — a grass/dirt far side is indistinguishable from overworld terrain. Build the source frame on a platform surrounded by a known palette inside the sample cube; traverse; assert palettes landed in `portal_links.json` (`auraPalette` on the zone, the `aura-site-v1` record for the source); wait N intervals and probe the annulus for converted blocks on BOTH sides. Assert the frame ring and interior untouched, and `auraBudgetSpent > 0` and `<= budget`. Speed the test with `"aura": {"interval": 10, "blocksPerPass": 8}` — never test at the default cadence.
- **Shape recipes**: build frames on floating platforms — clean geometry, no terrain interference. Load site chunks by `tp Bot <site>` FIRST (player-driven generation — never RCON forceload for this; fills fail with "That position is not loaded" otherwise). Negatives are wrong-SIZE frames and wrong-ORIENTATION frames — assert `data get entity Bot Dimension` unchanged AND the source-zone record count in `portal_links.json` unchanged (the zone-count oracle catches "ignited but didn't teleport").
- **No portal has anything inside it, at either end.** Assert success by traversal (`data get entity Bot Dimension`) and by the record count in `portal_links.json`, never by probing an interior for a portal block — an `if block <interior> minecraft:air` that answers SOLID means something is wrong, not that the portal is lit.
- **`player Bot attack once` does not break a block needing more than one click** (including portal blocks, even in creative). Use `player Bot attack continuous`, wait, then `player Bot attack stop`. `attack once` silently does nothing and looks exactly like a broken event hook (2026-07-26: nearly sent a session looking for a dead `PlayerBlockBreakEvents` registration).
- **Carpet bots may not despawn over RCON.** `player Bot kill`, `player Bot stop`, and `kick Bot` can all leave the bot in `list`. It clears on the next `mc` restart — don't spend time chasing it.
- **RCON `fill`/`setblock` into an unloaded dimension silently does nothing**, answering `Unknown dimension '<ns>:<dim>'` — easy to scroll past when it sits above a result that looks right. Load the world by teleporting a player there first, and read each command's own output rather than only the final assertion.
- **Profile an arrival column BEFORE traversing.** Probing it afterwards measures the portal you just built — the frame ring and the carve have already changed the surrounding cells.
- **When the live world won't produce the failing case, construct it.** E.g. verifying arrival placement in a ceilinged dimension took a deliberately entombed column (`fill … minecraft:netherrack` across the full Y range) — natural traversals may never exercise the path under test.
- **Autopause kicks the bot.** `docker exec mc sh -c 'touch /data/.skip-pause'` before testing.
- **Always clean up**: `player Bot kill`, remove any temporary test jar, restart `mc`.

## Worked example: CI's portal traversal e2e (`smoke-test.yml`)

The full end-to-end pattern CI actually runs — build a frame, ignite from inside looking down, poll for arrival, then assert the arrival is both inside the destination's world border AND at the scale-correct coordinate:

```bash
X=600; Y=100; Z=600
DIM=adventure:the_smoke_portal
docker exec mc rcon-cli "execute in minecraft:overworld run forceload add $((X-4)) $((Z-4)) $((X+4)) $((Z+4))"
# ... fill a 2x3 copper_block frame in the XY plane, air the interior ...
docker exec mc rcon-cli "gamemode creative SmokeBot"
docker exec mc rcon-cli "execute in minecraft:overworld run tp SmokeBot $X.5 $((Y+1)) $Z.5"
docker exec mc rcon-cli "item replace entity SmokeBot hotbar.0 with minecraft:golden_carrot 8"
docker exec mc rcon-cli "player SmokeBot hotbar 1"
docker exec mc rcon-cli "player SmokeBot look down"
docker exec mc rcon-cli "player SmokeBot use once"

# Poll rather than assume — dimension creation is async
for attempt in $(seq 1 10); do
  sleep 4
  BOTDIM=$(docker exec mc rcon-cli "data get entity SmokeBot Dimension")
  echo "$BOTDIM" | grep -q "the_smoke_portal" && break
done
```

Then it asserts three separate things about the arrival, each catching a different regression class:

1. **Inside the destination's player border** — an arrival outside it means the player can never break or place a block there again.
2. **Scaled correctly** — entering DIVIDES by `portal.scale`; pinning the exact expected column (`600 / 8 = 75`) makes a silent direction-flip (multiply instead of divide) impossible to miss, where "inside the border" alone would still pass for a wrong-but-small coordinate.
3. **Not entombed** — at least one face of the portal plane must be steppable, checked with `#minecraft:replaceable` (mirrors `PortalSite.isClear` exactly: `state.isAir() || state.isReplaceable()`). Two earlier, stricter versions of this check (air-only, then an enumerated air list) both called a walkable arrival "entombed" — assert the property, not a block list.

Adapt this pattern for any new player-dependent path: don't just assert "it happened", assert the specific failure modes a regression would actually produce.
