# BRIEF: redesign the portal test rig from scratch

You are being asked to design a test rig and a test suite. Nothing here is a
proposal to accept — the existing rig has failed repeatedly and is being thrown
away. Design what should exist.

---

## 1. The goals

### The product

`custom-dimensions` (server) and `custom-dimensions-client` (client) render
**immersive portals**: standing in dimension A and looking through a portal
frame, you see dimension B, live. It should behave like a window.

There are **two render modes**, agreed by a handshake between client and server:

| mode | when | what the player sees |
| --- | --- | --- |
| **server mode** | the client does NOT have the mod | the server streams a slab of destination blocks into the source world. Crude, no effects. |
| **client mode** | the client HAS the mod (the default when present) | the client renders the destination itself. **Zero server-streamed blocks.** The server ships raw destination *chunk data* only, so the client has something to render from. |

Client mode is what we are testing. The server feed is a data supply, not
rendering.

### The governing spec

`docs/design/immersive-portals.md`. Part 2 names the architectural problem
directly, and it is unfixed:

> *"the destination is drawn at SOURCE coordinates, inside the source frame's
> own pass"* — causing three observed defects: the dark entity, the wrong light,
> and the wrong fog.

### What the rig must let us answer

1. **Is the destination visible through the opening at all?**
2. Is it the *correct* part of the destination, at the correct orientation?
3. Does it stay correct as the player **looks around from a fixed position**?
4. Does it stay correct at **many positions**, and at **many angles at each
   position**?
5. Does anything leak — destination pixels outside the opening, source pixels
   inside it, the portal surface visible through solid blocks?
6. Is it stable, or does it tear and flicker?
7. Does it hold up **with shaders on** as well as off?
8. Is the lighting right — and can the rig even *show* a lighting defect?

---

## 2. Current state: it is broken

This is not a subtle regression. Photographs from the maintainer's own client:

- The opening renders **black** at ordinary viewing distances.
- Inside the opening: a vertical strip of the frame's **own magenta concrete**,
  and a patch of grey stone at the top that should not be there.
- **Black rectangles scattered across the arena floor**, well away from the
  frame. They are not shadows.
- At or inside the aperture, geometry **tears violently** — magenta panels shear
  across the screen, source and destination interpenetrate.
- The portal surface is **visible through a solid block** from some angles.

Meanwhile the client's own instrumentation reports everything is fine:
`destinationChunks 45`, `quadsIn 2334`, `emitted 3840`, projection present at
the right aperture. **The gap between "the counters are happy" and "the render
is broken" is itself a finding your design must close.**

---

## 3. Why the current test method failed — do not repeat any of this

The method was: teleport to one pose, take one or two screenshots, crop a fixed
~190x120 pixel box near the middle, average the luma, compare to a previous run.

Every one of these produced a wrong conclusion in a single day:

| failure | what happened |
| --- | --- |
| **hard-coded crop box** | tied to one pose. The portal was rebuilt elsewhere; the box sampled a wall and still returned a plausible number. A "verified" fix was published on it and withdrawn. |
| **a mean hides structure** | tearing, a black opening, and geometry drawn through walls all pass a brightness check. |
| **one pose is not a test** | everything came from a single position and angle, so nothing was known about looking around or moving. |
| **conditions unrecorded** | shaders on/off, render mode, jar shas, light levels varied silently between runs. Most figures were shaders-OFF, which is not what a player sees. |
| **the fixture was blind** | both dimensions sit at sky 15, zero block light, fixed noon, and are the same colour. Light-term and colour defects are invisible in it *by construction* (`TROUBLESHOOTING.md#t113`). |
| **the rig portal was absent** | for part of the day `portal_links.json` had no e2e entry at all. Readings silently came from a different portal in another world, and nothing detected it (`#t114`). |
| **orientation confusion** | a crop landed on a *side* face (vanilla shade 0.8) and was compared against a *top* face (1.0). The 0.8 was mistaken for an 18% lighting defect and chased for hours. |

**The maintainer's instruction, verbatim:** make each dimension **one single
colour** — e.g. `green_concrete` one side, `yellow_concrete` the other — and
then assert that colour appears in the portal at specific positions. That turns
the primary assertion from a number into a presence check, which none of the
above failures can defeat.

---

## 4. The tools you have

### Stack control (run from the consumer repo `~/Projects/elfydd`)

```bash
./dev up                  # recreate containers; installs the SERVER jar
./dev reload              # copies the CLIENT jar into the Prism instance
./dev launch              # start the game client (add --dev-bridge for port 8766)
./dev screenshot --out P --no-focus   # writes the REAL client's framebuffer to P
./dev refresh-config      # re-copy platform configs into data/config
./dev restart <service>   # recreate one service, no deps
./dev link                # point the consumer at this checkout's built jars
```

**`./dev up` installs the server jar only. The client jar needs `./dev reload`.**
Missing this measures a new server against an old client.

### Server commands

```bash
docker exec -i mc rcon-cli "<command>"
```

Useful ones:

```
customdim load <name>              # CREATES a configured dimension on demand
customdim e2e-state <player>       # writes a JSON artefact of server-side facts
execute in <ns>:<dim> run <cmd>    # does NOT create the dimension; fails if absent
fill X0 Y0 Z0 X1 Y1 Z1 <block>     # capped at 32768 blocks per command
setblock X Y Z <block>
tp <player> X Y Z <yaw> <pitch>
gamemode spectator|survival|creative <player>
data get entity <player> Pos|Rotation|Dimension|playerGameType|Health
time set <n>                       # overridden if the dimension declares fixedTime
```

Carpet fake players (already installed):

```
carpet commandPlayer true
player <Name> spawn at X Y Z facing <yaw> <pitch> in <ns>:<dim>
player <Name> look at X Y Z
player <Name> use once | attack continuous | attack stop
player <Name> kill
item replace entity <Name> hotbar.0 with <item>
```

### The client dev bridge — `http://127.0.0.1:8766`

```bash
curl -s http://127.0.0.1:8766/state          # the client's own view, JSON
curl -X POST .../screenshot -d '{"path":"..."}'
curl -X POST .../input -d '{"realtime":{...}}'   # runtime render toggles
curl -X POST .../input -d '{"hud":{"hidden":true}}'
```

Useful `/state` fields under `realtime`: `destinationChunks`,
`destinationChunkAccepts`, `renderClientSidePortals`, `maxRenderDistance`,
`viewDepth`, `apertureTerrain`, `apertureBackdrop`,
`apertureUnshadedDestination`, `apertureUnshadedBackdrop`, `apertureRenderUs`.
Under `projections[]`: `destination`, `aperture`, `clip.layers[]` (`quadsIn`,
`emitted`), `meshLight`, `destWorldLight`, `ambient`.

Runtime render toggles settable through `/input`: `apertureTerrain`,
`apertureBackdrop`, `apertureUnshadedDestination`, `apertureUnshadedBackdrop`,
`apertureBackdropGain`, `apertureAtlasFilter`, `viewDepth`, `maxRenderDistance`.
These let you turn parts of the render on and off **without a rebuild** — the
single most useful property for isolating which stage is broken.

### Shaders

`~/Library/Application Support/PrismLauncher/instances/elfydd-1.21.1-local-latest/minecraft/config/iris.properties`,
key `enableShaders`. **Changing it needs a client restart (~90s).**

### Existing scripts in `scripts/e2e/`

`stack-lock.sh` (take before any container action), `rig-ready.sh`,
`rig-quiet.sh` (freeze weather/time/mobs), `build-test-arena.sh` (flat
single-colour arena with walls), `build-e2e-portal.sh`, `reset-the-rig.sh`,
`verify-arena.py`, `frame-identity.py`, `shadow-gradient.py`.

**Treat all of these as suspect.** They encode the failed method.

---

## 5. The rig as it exists today

### The dimensions

```
elfydd:e2e_one   source        floor y=-61   ambient 0.25   fixedTime 6000
elfydd:e2e_two   destination   floor y=-61   ambient 0.00   fixedTime 6000
```

Both are flat superflats with a grey-concrete floor and dark walls, open to the
sky above. Both are currently **the same colour**, which is one of the reasons
nothing can be told apart in a screenshot.

### The portal

```
source        elfydd:e2e_one -> elfydd:e2e_two
axis          X
frame block   minecraft:magenta_concrete   (the DESTINATION's block, not the source's)
igniter       minecraft:amethyst_shard     (not consumed)
aperture      6 cells: x 4-5, y -60..-58, z 4
frame ring    x 3-6, y -61..-57, z 4
```

A frame is built from the **destination dimension's** declared `frameBlock`.

### What it looks like from each angle (from photographs)

- **Front, ~3-6 blocks back, eye level:** a magenta rectangle standing on grey
  floor. The opening is **black**, with a magenta strip inside it and a patch of
  grey stone top-left. Black rectangles lie on the floor in front.
- **Close, at the frame:** violent tearing; magenta panels shear diagonally
  across the whole screen; source and destination geometry interleave.
- **Inside the aperture:** near-total black with fragments of sky at odd angles.
- **From behind / off to the side:** the portal surface is visible **through**
  the solid frame blocks.
- **From above (y=-50):** the arena is a grey floor inside dark walls, open to
  sky with sun and clouds. The frame is a small magenta structure. Black
  rectangles are scattered across the floor at distances from it.

---

## 6. Constraints — these are absolute

- **`FLUXXINATED` is the maintainer's real character** with real inventory and
  progress. It is the character to use for viewing and screenshots — the
  screenshot endpoint captures the real client's framebuffer, so the pose must
  be the real player's. **Do not give it items, change its gamemode
  destructively, or leave it in spectator.** Use a Carpet bot for anything
  needing an item in hand.
- **Never edit blocks in `minecraft:overworld` or
  `adventure:the_amplified_reaches`.** The e2e pair is the only editable
  fixture.
- **Take `stack-lock.sh` before any container action.** Several agents share one
  stack, and `./dev up` disconnects every client.
- **Never restart `mc` without coordinating.**
- macOS **bash 3.2** — no `declare -A`, no `mapfile`, no `grep -P`.
- Commit by explicit path; **never `git add -A`** (shared working tree).
- Do not stream logs or use unbounded waits; snapshot only.

---

## 7. Decisions already made — these are not open

The maintainer has settled these. Design within them; do not re-litigate them.

1. **One flat colour per dimension. No landmarks.**
   `e2e_one` (source) is one colour, `e2e_two` (destination) is another —
   green concrete and yellow concrete. Nothing else in either world.
   The maintainer accepts the consequence: this cannot detect a mirrored view,
   a wrong depth or the wrong slice of the world. It detects whether the
   destination is drawn at all, and whether anything leaks. That is the
   priority, because today the answer to "is it drawn at all" is no.

2. **Reset between EVERY run: fill everything with air, delete all portals,
   rebuild.**
   Not a bounded box — everything. A previous run left a floating stone
   platform and a **second ghost portal frame** hanging above the real one,
   both of which were photographed and neither of which any measurement
   accounted for. A run that starts on a dirty fixture is void.

3. **Fully automated end-to-end tests. No human contact sheet.**
   The suite measures **expected colour and pixel values precisely** and
   returns pass or fail. An end-to-end test states its expectation and checks
   it. Do not design something that produces images for a person to look at
   and calls that a test.

4. **Client mode only, with shaders ON and shaders OFF.**
   Both, reported separately, never mixed. Server mode (the no-mod block-slab
   fallback) is out of scope.

## 8. What to design

A rig and a suite that can answer §1's eight questions, and that cannot fail in
any of §3's ways.

Cover at least:

1. **Fixture geometry** — the two single-colour worlds, arena extent, wall and
   ceiling treatment, and where the portal stands. Colours are settled (§7.1);
   the geometry around them is yours.
2. **The light axes.** The current fixture is sky 15 / block 0 / noon and is
   blind to any defect outside that point. Say how the rig varies sky level,
   block light, and time of day.
3. **The camera matrix** — positions, angles, and **fixed position with a
   sweeping camera**, which is how a player actually looks at a portal and is
   the case that has never been tested properly.
4. **Assertions — the core of this.** Precise expected colour and pixel values,
   evaluated over the whole frame, not a hand-placed crop. How the opening's
   screen region is located without hard-coding a box. How "yellow present
   inside, green absent inside, yellow absent outside" becomes exact numbers
   with tolerances. What the expected pixel value IS for a given block colour
   under a given light level, and how that expectation is derived rather than
   copied from a previous run.
5. **Stability** — how tearing and flicker are detected without a human.
6. **Shaders on and off** — both, reported separately, never mixed.
7. **Setup and teardown** — the total reset of §7.2, and how the rig proves it
   exists and is clean before measuring anything. A leftover frame or platform
   must fail setup, not survive into the results.
8. **Recording** — where results and conditions are written, in what format, so
   a run is reproducible and two runs can be diffed.
9. **Cost** — each frame costs seconds and a shader flip costs ~90s. Give a
   minimum viable matrix as well as the full one.

### Deliverable

A written design, saved to `scripts/e2e/RIG-DESIGN.md`. Concrete commands,
coordinates, block choices, file paths, and exact pass/fail criteria with
numbers and tolerances. Name what you would build, in what order, and what each
piece proves.

Where you disagree with anything OUTSIDE §7, say so and why. §7 is settled.
