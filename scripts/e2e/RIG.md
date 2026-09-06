# The rig

Built step by step. Nothing enters this file that has not been specified.

Rule: every number here is either **MEASURED** (recorded from a known base
state) or **SPECIFIED** (a value we chose and will build to). Nothing is
derived from prior art, and nothing is copied from a previous run.

---

## Step 1 — What we are measuring

**Exact pixel values at specific, repeated screen coordinates**, sampled at
named sample points inside the portal frame and around it, from specific,
repeated camera angles, at specific, repeated player positions.

The three axes are all **enumerated and fixed**, never ad hoc:

| axis | meaning |
| --- | --- |
| **position** | a named player position. Repeated exactly, every run. |
| **angle** | a named camera yaw/pitch, taken from that position. Repeated exactly. |
| **sample point** | a named screen coordinate, inside the opening or around it, read at that position and angle. |

A **reading** is one tuple:

```
(position, angle, sample_point) -> exact RGB
```

The rig's output is a table of those readings. Every one is compared against
the same tuple measured from a known base state.

Not an average. Not a region mean. Not a description. An exact value at a
named coordinate, repeated identically across runs so two runs can be
subtracted.

---

## Step 2 — How a sample point is fixed: the frame is the reference

**The portal frame locates every sample point, by its exact colour.**

No absolute screen coordinates. No fractions of the window. No projection
maths. The frame is a known, unmistakable colour, it is in every frame we
capture, and it surrounds the thing being measured — so it is the ruler.

### The procedure, per captured frame

1. **Find the frame.** Match against the whole **MAGENTA class** — every shade
   variant of the frame block, not one RGB. A cube has faces at different
   shades and they are all the frame. Each variant's value is MEASURED in the
   base state (Step 4), never assumed.
2. **Derive the opening from the frame.** The matched pixels form the ring.
   The opening is the region it encloses. Its corners and edges are read from
   the ring, in pixels, in this frame.
**The frame's own colour legitimately appears INSIDE the enclosed region.** At
   oblique angles the ring's inner faces project into the opening — the frame is
   a block thick and you are seeing through a short tunnel. That is correct
   rendering, not leakage, and any assertion phrased as "nothing but the
   destination inside the ring" is false as stated.

3. **Place the sample points relative to that opening.** Each named sample
   point is defined by its position *within the located opening* — its
   corners, its centre, its edge midpoints, and named insets from them.
4. **Read the exact RGB at each resolved coordinate.**

### Why this is the right reference

- It **self-corrects for position and angle.** Move the camera and the opening
  lands somewhere else on screen; the frame moves with it and the sample
  points follow. The same named sample point is the same world surface from
  every position and angle in the matrix.
- It **cannot silently sample the wrong thing.** A hard-coded coordinate keeps
  returning a number when the portal moves. This does not: if the frame is not
  found, or is not a single closed ring of the expected proportions, the frame
  is rejected and no reading is taken from it.
- It **needs no camera model.** Nothing depends on FOV, aspect, or projection
  arithmetic being correct.
- It **survives a resolution change**, because everything is relative to a
  feature located in the image itself.

### What the frame must therefore be

- A colour that appears **nowhere else** in either dimension — so a match is
  unambiguous.
- Present and fully visible in **every** frame in the matrix, or that frame is
  out of scope.

### The ring is not visible from every pose — that is planned, not rejected

At 70 degrees FOV with the 8 cardinal directions, **most poses cannot see the
whole ring**, and positions due east and west see an X-axis frame edge-on with
no opening at all.

So **an in-view predicate is computed for every pose before capture** and
written into the run plan:

| `ring_in_view` | assertions that apply |
| --- | --- |
| `true` | ring located, interior classified, full assertion set |
| `false` | **global assertions only** — no destination colour anywhere in frame, `OTHER` count and location |

**No frame is discarded for not showing the ring.** A pose facing away from the
portal is still evidence: the destination's colour must not appear in it, and
`OTHER` must be empty. That is a real assertion and it catches leakage that a
portal-facing pose cannot.

Rejection below is for frames that **should** show the ring and do not.

### Rejection is part of the measurement

A frame **whose pose says `ring_in_view: true`** is discarded, and produces no
readings, if:

- the frame colour is not found;
- the matched pixels are not one connected ring;
- more than one candidate ring is found (this is what a ghost frame looks
  like);
- the ring's proportions do not match the specification.

A rejected frame is a **failure of the run**, reported as such — never a
missing row that quietly narrows the results.

---

## Step 3 — The fixture: three colours, nothing else

### Blocks

| role | dimension | block |
| --- | --- | --- |
| source, where the player stands | `elfydd:e2e_one` | `minecraft:green_concrete` |
| destination, what the portal shows | `elfydd:e2e_two` | `minecraft:yellow_concrete` |
| the frame, the ruler | built in `e2e_one` | `minecraft:magenta_concrete` |

The frame must differ from **both** worlds, because it is seen against both:
against green from the source side, and with yellow visible through it.

`custom-dimensions` builds a frame from the **destination's** declared
`frameBlock`, so `e2e_two`'s config declares magenta. That is a config change,
not a choice made at build time.

The exact RGB of all three is **MEASURED** in Step 4. The hex values that
appear anywhere in this file before that step are identifiers, not
expectations, and nothing asserts against them.

### Enclosure

Floor **and** walls in the dimension's one colour. **No ceiling** — open to the
sky.

- Every solid surface in shot is that dimension's colour, so anything else in
  frame is the portal, the frame, the sky, or a defect. There is no fourth
  legitimate thing.
- No ceiling, because a sealed box has no direct sunlight, and a shadow defect
  cannot exist without it. Roofing the arena would make one of the defects
  under investigation unobservable — the same class of error as measuring a
  light curve at full sky.
- The sky is therefore a fourth known value, and is classified, not ignored.

### The colour classes

Every pixel in every captured frame resolves to exactly one of:

| class | what it is |
| --- | --- |
| `GREEN` | `e2e_one` surfaces |
| `YELLOW` | `e2e_two` surfaces |
| `MAGENTA` | the portal frame |
| `SKY` | sky above the walls |
| `UNMATCHED` | **anything else — every one is a defect** |

`UNMATCHED` is the point of the scheme. A rig where every legitimate surface
has a known colour turns "is there something wrong in this frame" into a count
that cannot be argued with.

### What this cannot distinguish

A uniform colour carries no landmarks, so it cannot detect a **mirrored**,
**rotated**, **offset** or **wrong-depth** view of the destination. Yellow is
yellow from any angle and at any distance. Accepted deliberately: the question
that matters first is whether the destination is drawn at all, and today the
answer is no.

---

## Step 4 — The measurement matrix, and the base state

### The rig is built and measured WITHOUT any portal first

There is no per-dimension sample set. **Every measurement point is taken in
both dimensions**, identically. `e2e_one` and `e2e_two` are the same geometry
in different colours, so the same matrix runs in each.

### The circles

At each radius, positions are evenly spaced clockwise around the rig centre:

| radius | positions |
| --- | --- |
| 4 blocks | 12 |
| 8 blocks | 24 |
| 12 blocks | 36 |
| 24 blocks | 48 |
| 32 blocks | 60 |

**180 positions per height.**

At every position, **8 cardinal camera directions**: N, NE, E, SE, S, SW, W,
NW.

### The heights

The whole circle set is repeated at three heights. All three are positions a
real player can actually occupy — see Step 10.

- **feet on the floor, standing**
- **feet on the floor, crouching**
- **jump apex** — feet on the floor, mid-jump, at the top of the arc

**540 positions x 8 directions = 4,320 frames per pass.**

### Repeats — up to 3, per assertion, to absorb flakiness

**Not** "run the whole matrix three times and demand identical values".

An assertion that fails is **retried, up to 3 attempts, on its own**. Particles,
animation and random variation should not fail a run, and re-running the entire
session to settle one value is waste.

- Only the **failing assertion** retries. Nothing else re-runs.
- If all 3 attempts fail, **only that assertion fails**.
- **The run continues** — a failed assertion never ends the session.
- The only thing that stops a run is a **blocking failure that physically
  prevents continuing** (client gone, server gone, fixture unbuildable).
- The attempt count and each attempt's value are recorded, so a value that
  needed 3 tries is visibly less solid than one that passed first time.

### The passes

The entire matrix above is run, in order:

1. **Magenta frame present, UNLIT.**
2. **Frame LIT** — the portal active.

Then both are repeated **with shaders enabled**.

The frame is always present. There is no bare-arena pass; the unlit frame is
the baseline, and it is the same geometry as the lit one with the portal
switched off. That is the only difference between the two passes, which is what
makes them subtractable.

### Close-range sweeps

A separate set, at the portal:

- Stand at **exactly the minimum distance** from the portal's activation point
  — the closest a player can physically be without activating it or phasing
  into the blocks.
- Dead centre of the portal, on the **south** side. Then **north**. Then every
  other cardinal direction.
- **Not spectator.** A real player, GUI hidden.
- At each, sweep the camera in **10 degree steps** until the frame has been
  completely swept.

Run for both frame states: present-unlit, and lit.

### The only legitimate values

Every pixel resolves to one of:

```
YELLOW   SHADED_YELLOW
GREEN    SHADED_GREEN
MAGENTA  SHADED_MAGENTA
OTHER
```

**Nothing else may exist. If it does, the rendering cannot possibly be
working.** `OTHER` is not a tolerance band — it is the defect count.

Lit and unlit face variants are separate classes because a face's shade is a
different value, not a noisy version of the same one. Conflating them is what
produced a phantom lighting defect.

### Sampling

**One pixel, exactly.** `reading = img[y, x]`. No averaging, no window, no
mean. Nothing to argue about.

### Conditions

- The maintainer's own game client and player. **The player name is read from
  `.env`, never hard-coded.**
- GUI hidden.
- Fixed time, fixed tick rate.
- Shaders off for the first full process, on for the second.
- Fully automatic and perfectly consistent — no manual step anywhere.

### Reset — before every test, not every run

A `beforeEach`, not a `beforeAll`:

1. Clear the portals on **both** sides, and their links.
2. Clear the frames.
3. Fill the **entire rig area with air**, in **both** dimensions.
4. Rebuild both rigs from scratch.

Nothing survives between tests. Not a frame, not a link, not a block.

---

## Step 5 — No sky in frame, and reading pixels without saving files

### Walls tall enough that the sky is never in shot

The arenas stay **open to the sky** — direct sunlight has to exist, or a shadow
defect cannot exist to be measured.

But the walls are built **high enough that no camera angle in the matrix ever
sees over them**. Sky is out of frame everywhere, so there is no `SKY` class
and the legitimate-value list stands exactly as specified:

```
YELLOW   SHADED_YELLOW
GREEN    SHADED_GREEN
MAGENTA  SHADED_MAGENTA
OTHER
```

**Wall height is derived, not guessed.** It follows from the widest upward
sightline in the matrix: the outermost radius (32 blocks), the highest eye
(2x player height), and the greatest upward pitch used at that position. The
wall must occlude the horizon at that worst case, with margin.

Wall height is therefore a **SPECIFIED** value computed from the matrix, and
the rig build asserts it. If the matrix later gains a wider angle or a taller
eye, the wall height is recomputed — it is not a constant anyone may edit
independently.

**Sky appearing in any frame is a build failure**, caught in the unlit pass: an
arena showing anything but its own colour and the magenta frame means the
enclosure is wrong, and nothing downstream runs.

### Reading pixels must be instantaneous

A frame is not a file. **Saving a PNG and reading it back is not part of the
measurement** — it is an implementation detail that must not dominate the cost.

Required, in order of preference:

1. **Read the pixel values directly from the render pipeline**, in the client,
   at the end of the frame. The values are already in memory; sample the named
   coordinates there and return the numbers.
2. Failing that, **read directly from the framebuffer / screen** without a file
   round-trip.
3. Writing a PNG per frame is the fallback and must be justified.

The dev bridge already returns state over HTTP. The same channel should return
**a list of sampled RGB values for named coordinates**, not an image — the
measurement is a handful of integers, and moving megabytes of PNG to extract
them is the wrong shape.

**Consequence:** 77,760 frames is not 40 hours. Position, orient, sample,
next. The cost floor is the client rendering a frame and the server
acknowledging a teleport, not disk I/O and PNG decode.

**The matrix is not cut.** It runs in full.

---

## Step 6 — Two frame states, and what OTHER actually is

### Two passes, not three

The frame is **always present**. There is no bare-arena pass.

1. **UNLIT** — the magenta frame stands, the portal is off.
2. **LIT** — the same geometry, portal active.

The only difference between them is the portal. That is what makes them
subtractable: any pixel that differs between the unlit and lit frame at the
same position, angle and coordinate is the portal's doing and nothing else.

`540 positions x 8 directions x 2 frame-states x 2 shader-states`, **run in
BOTH dimensions**. See Step 9 for the count.

### What can OTHER possibly be?

The right question, and the honest answer is **it should be nothing**. Every
solid surface is one of three blocks, the sky is walled out, and the classes
cover every legitimate case. In a working renderer `OTHER` is empty.

Candidates for a non-zero `OTHER`, all of which are findings rather than
tolerances:

- **Edge blending** between two known colours. Whether Minecraft actually
  produces intermediate values at a block boundary at these settings is
  **unknown and must be measured, not assumed** — with no antialiasing and no
  texture filtering across a boundary, adjacent pixels may simply be one colour
  or the other.
- **The portal surface itself**, in the lit pass — whatever the aperture draws
  is by definition not one of the three block colours.
- **An entity in shot** — a leftover bot renders as neither.
- **A defect** — the thing being hunted.

### The fail rule is written from evidence, not chosen now

The UNLIT pass measures what a real block edge classifies as. Every surface in
it is known, so **whatever `OTHER` it produces is the floor**, and it is
measured before any rule is written.

- If the unlit pass yields **zero** `OTHER`, the rule is: **any `OTHER` pixel
  fails.** Nothing else is needed.
- If it yields `OTHER` only on boundaries between two known classes, the rule
  is written to that measured shape, stating exactly where blending is
  permitted and how wide.

Either way the rule comes from the measurement. It is not decided in advance
and it is not a tolerance band chosen to make results pass.

---

## Step 7 — The assertion, and the three defects it must expose

### The three visible defects this rig exists to catch

1. **BLACK through the opening**, and blocks from the *source* dimension
   appearing inside it. The destination is not drawn.
2. **A shadow cast by the portal**, extending out from the frame into the
   source dimension, that **moves as the camera moves**. A shadow that depends
   on camera angle is not a shadow.
3. **A cutoff distance that should not exist** — the view through the portal
   ends, showing sky or void beyond it. Cause not yet known; candidates are a
   capped render distance, projecting from a position that does not represent
   the connected dimension, or a broken mask.

### The assertion, for now

**Does `YELLOW` or `SHADED_YELLOW` appear inside the located ring?**

That is the pass/fail. It is deliberately the crudest possible question,
because the current answer is **no** — the opening is black. Nothing more
subtle is worth asserting until that is fixed.

`SHADED_YELLOW` counts as a pass at this stage. Separating correct shading from
incorrect shading is a **later** problem, taken up once the destination is
drawn at all.

### Recorded but not asserted, from the first run onward

Every one of these is measured and written down from the start, so the data
exists the moment the black issue is fixed:

- **Exact RGB at every sample point**, compared against the same point measured
  directly in `e2e_two` at the matching face and light. **Any difference is the
  defect, and its size is the measurement.** Recorded per sample even when the
  frame passes, so a drift is visible before it crosses any line.
- **`lit` minus `unlit` at identical pose.** Every changed pixel should be
  inside the ring. Changes outside it are the portal drawing where it must not
  — which is defect 2, the shadow, and it will show up here as a region of
  changed pixels **outside** the frame that **moves with camera angle**.
  Because the two passes differ only by the portal, this isolates it exactly.
- **`OTHER` count and location**, per frame — where the black is, and how much.
- **The far edge of `YELLOW` inside the ring**, per position and angle. Defect
  3 is a cutoff, so the distance at which yellow stops is the quantity that
  describes it. Measured from the start; asserted once there is yellow to
  measure.

### Why the shadow is measurable this way and was not before

The shadow was previously chased with a luma ramp across the opening and
declared absent. Wrong instrument: it extends **out from the frame into the
source dimension**, which is outside every crop that was ever taken, and it
**tracks the camera**, so a single-pose reading cannot see it at all.

Here it is: a set of pixels outside the ring that are `SHADED_GREEN` where the
unlit pass had `GREEN`, whose extent changes with camera angle at a fixed
position. The 8-direction sweep at every position is what exposes it, and the
unlit/lit subtraction is what isolates it from everything else.

---

## Step 8 — Where the rig is built

### Centred on 0,0 in both dimensions

| | |
| --- | --- |
| rig centre | `x=0, z=0` in **both** `e2e_one` and `e2e_two` |
| portal | at the centre of `e2e_one` |
| `e2e_two` | identical arena, no portal |

Every one of the 540 positions is an **offset from 0,0**, so the same position
name is the same offset in both dimensions. A base-state reading taken at
position `r8_p13` in `e2e_two` is directly comparable with the portal reading
taken at `r8_p13` in `e2e_one`, with no coordinate translation and nothing to
get wrong.

The existing site (portal at x 4-5, z 4) is abandoned. It is arbitrary, it is
where the debris is, and every reading taken there is void.

### Extent

**Radius 48.** Floor and walls out to 48 blocks from centre, in both
dimensions.

The outermost measurement circle is at radius 32, leaving **16 blocks of
margin** — enough that a wide outward or upward angle at that radius still sees
arena rather than an edge, without the extent itself needing to be re-derived
every time the matrix changes.

### Floor and eye heights

The floor is one flat plane. All 540 positions sit on it; the three heights are
**eye heights above that floor**, not different floors.

### The portal's orientation

The portal stands at the centre, so the 8 cardinal directions and the circles
around it are symmetric about it. Its axis is a **SPECIFIED** value: the frame
is built on one axis, and which one is recorded here once the build step is
written, because the close-range sweeps name sides (north side, south side)
that only mean something relative to it.

---

## Step 9 — Output format, and the reset unit

### Both dimensions, not one

The full matrix runs in **`e2e_one` AND `e2e_two`**. `e2e_two` is not a
one-off base-state sample — it gets the identical treatment, so every reading
in the source has a same-named counterpart in the destination.

```
540 positions
  x 8 directions
  x 2 frame states   (unlit, lit)
  x 2 shader states  (off, on)
  x 2 dimensions
= 34,560 poses per complete e2e test
```

Repeats are **retries on failure**, not a multiplier on the whole matrix, so
they do not enter this count. A clean run captures 34,560 poses; a run with
failures captures a few more.

**How long that takes is unknown and is not estimated here.** It cannot be
known until the capture path exists and one pose has been timed. Measure it,
then decide whether the matrix needs a fast tier.

### Output: one `.jsonl` per complete e2e test

Line-delimited JSON. One object per line, so the file streams and can be
operated on without reading it whole — which matters at this row count.

One flat file per complete e2e test. Every line carries its full key, so no
line depends on any other and order is not load-bearing:

```json
{"run":"...","dim":"e2e_one","pass":"lit","shaders":"off","repeat":1,
 "position":"r8_p13","angle":"N","eye":"normal",
 "x":854,"y":508,"r":0,"g":0,"b":0,"class":"OTHER"}
```

Comparing two runs is a join on
`(dim, pass, shaders, position, angle, eye, x, y)` and a subtraction of RGB.

### Reset unit: one test = one repeat of one pass

Rebuilt before **every repeat**, not just every pass.

A rebuild clears and rebuilds **both dimensions at once**, so it is not
per-dimension:

```
2 frame states x 2 shader states = 4 rebuilds per complete test
```

An assertion retry does **not** rebuild — it re-reads the same pose on the same
fixture, which is what makes it a retry rather than a new test.

### The rebuild, in full

1. Clear the portals on **both** sides, and their links.
2. Clear the frames.
3. Fill the **entire rig area with air**, in **both** dimensions.
4. Rebuild both arenas from scratch.
5. Build the frame (unlit), and ignite it if the pass calls for it.
6. **Prove the fixture** before any reading is taken.

Nothing survives a rebuild. Not a frame, not a link, not a block, not an
entity.

---

## Step 10 — Portals are typed, and both dimensions have one

### How portals are supposed to work — this is the design, and it is what the rig tests

A portal has a **type**, and the type is the destination. When a frame of
`e2e_two`'s block is built and lit **in `e2e_one`**, it is an *`e2e_two` portal*
and it leads to `e2e_two`.

**In the design, igniting that portal creates the arrival portal on the far
side, and that arrival portal is the `e2e_one` kind** — because from where it
stands, the place it leads to is `e2e_one`. The pair is connected by **type and
location**: each side is the type of the dimension it leads to.

So the two dimensions need two different portal blocks:

| in dimension | frame block | type | leads to |
| --- | --- | --- | --- |
| `e2e_one` | `magenta_concrete` | `e2e_two` portal | `e2e_two` |
| `e2e_two` | `orange_concrete` | `e2e_one` portal | `e2e_one` |

Orange for the return side, distinct from green, yellow and magenta, so the
classes stay unambiguous. The classes become:

```
GREEN   SHADED_GREEN      MAGENTA  SHADED_MAGENTA
YELLOW  SHADED_YELLOW     ORANGE   SHADED_ORANGE
OTHER
```

### What the current implementation does instead

At present a portal is a **single type that always resolves to `e2e_two`**, and
would be inert in `e2e_two` itself. There is no return side of the `e2e_one`
kind.

**The rig follows the design, not the implementation.** It asserts that
igniting the magenta portal in `e2e_one` produces an **orange** arrival portal
in `e2e_two` that leads back.

**That test will probably fail without mod changes. That failure is the
point** — it is evidence the mod does not work as designed, recorded as a
result rather than accommodated by weakening the test. Anything to the contrary
is NOT how the mod should work.

### Both directions are tested

With a portal on each side, the matrix runs both ways: `e2e_one -> e2e_two` and
`e2e_two -> e2e_one`. Each dimension is both a source and a destination, and
its own surfaces are the ground truth for what the other should show through
the opening.

### Other portal formats need their own rigs

Not covered by this rig, and each needs its own fixture and matrix:

- **Anchor-type portals**, which have different rules.
- **Horizontal portals**, where the immersive view must be a **vertically
  flipped reflection** — looking *down* into the portal shows the other
  dimension as if staring *up* into it. The flip is itself an assertion, and a
  uniform-colour fixture cannot check it, so a horizontal rig needs landmarks.

### The three heights, concretely

All three are real player states, reachable without clipping:

| name | what it is |
| --- | --- |
| `standing` | feet on the floor, standing |
| `crouching` | feet on the floor, crouching |
| `jump_apex` | feet on the floor, at the top of a jump |

Not multiples of eye height. `0.5x` eye height would put the feet below the
floor, which is not a place a player can be, and a measurement taken from
inside a block is not a measurement of anything.

`jump_apex` has to be captured **at the apex**, which is a timing problem the
capture step must solve rather than approximate.

---

## Step 11 — Each dimension's frame is its ruler, and the six linkage tests

### Locating: same procedure, different reference colour

In `e2e_one` the **magenta** ring locates the opening and every sample point.
In `e2e_two` the **orange** ring does. Identical code; the reference colour is
a parameter of the dimension.

Applied **always** — both dimensions, both passes. The unlit pass reads the
same resolved coordinates as the lit pass, which is what makes them
subtractable pixel-for-pixel at the same position and angle.

### Why portals must link like this at all

In vanilla the only hand-buildable portal is the nether portal, and it always
links to the overworld. **With this mod a portal to any dimension can be built
in any other dimension.** Without a linkage rule that creates and maintains the
far side, the mod would need a configuration for every ordered pair of
dimensions — millions of them. The rules below are what make that tractable, so
they are load-bearing behaviour, not a convenience.

### What the pair must do — seven tests

Throughout: **magenta** = the `e2e_two` portal, built in `e2e_one`.
**Orange** = the `e2e_one` portal, built in `e2e_two`.

| # | setup | ignite | must happen |
| --- | --- | --- | --- |
| 1 | magenta frame in `e2e_one`, nothing in `e2e_two` | magenta | an **orange portal appears in `e2e_two`, already lit** |
| 2 | as above, both now lit | — | **magenta shows `e2e_two`** through it |
| 3 | as above | — | **orange shows `e2e_one`** through it |
| 4 | magenta in `e2e_one`, **existing UNLIT orange** in `e2e_two` | magenta | the existing orange **ignites** |
| 5 | **existing UNLIT magenta** in `e2e_one`, orange in `e2e_two` | orange | the existing magenta **ignites** |
| 6 | magenta in `e2e_one`, **BROKEN orange** in `e2e_two` | magenta | the orange is **repaired and ignited** |
| 7 | **BROKEN magenta** in `e2e_one`, orange in `e2e_two` | orange | the magenta is **repaired and ignited** |

Both directions, symmetrically. Neither dimension is privileged: igniting from
`e2e_two` must do to `e2e_one` exactly what igniting from `e2e_one` does to
`e2e_two`.

"Broken" means a frame with blocks missing from the ring — enough that it is
not a valid frame — which the linkage is expected to restore.

### Ignition is never done by hand on the far side

The far side is **never** lit manually. Its existence, its ignition, and its
repair are the assertions. If no orange portal appears in `e2e_two` after
igniting magenta in `e2e_one`, that is the failure, and it is recorded as one.

**These tests will probably fail against the current implementation**, which
has a single portal type that always resolves to `e2e_two` and is inert in
`e2e_two` itself. That failure is evidence the mod does not match its design.
It is not a reason to weaken the test.

---

## Step 12 — The four damage states

A portal on either side can be in one of four non-working states. They have
different causes and the mod may well treat them differently, so each is its
own test — and all four must behave the same way when the opposite side is
ignited.

| state | what it is |
| --- | --- |
| **UNLIT** | the frame stands, correctly formed, but **the connection was never created**. It has never been lit. |
| **BROKEN** | it **was** created, then something **cleared the frame** — a ring block removed. |
| **BLOCKED** | it **was** created, then something **filled the gap** — a space-occupying block entered the opening. |
| **SHATTERED** | it **was** created, then **someone broke the portal surface** — attacked it. |

The distinction that matters: **UNLIT never had a connection. The other three
had one and lost it**, by three different mechanisms.

### How each is built

**UNLIT** — place the ring blocks directly with `fill`/`setblock` and simply
never ignite it. Pure block placement, no mod behaviour in the setup, fully
deterministic.

**BROKEN** — ignite it, then remove **one block from the middle of an edge**.

> Not a corner. A corner block does not always count as part of the frame, so
> removing one is not a reliable break. A mid-edge block is unambiguous.

The cell removed is **SPECIFIED** and identical every run.

**BLOCKED** — ignite it, then place a space-occupying block **inside the
opening**. Anything that is not an entity should break it, exactly as a vanilla
nether portal does. Cases:

- a solid building block — cobblestone
- a flowing liquid — water
- a flowing liquid — lava

**SHATTERED** — ignite it, then have a player **attack the portal surface**
until it breaks, as a vanilla nether portal shatters. Causes to cover:

- struck by hand or tool
- destroyed by an explosion

Done by a Carpet bot, never by `FLUXXINATED`.

### What must happen

For every one of the four states on the far side, igniting the near side must
**restore and light it** — repairing the frame where the frame is damaged.

Run symmetrically: each state tested with the damage in `e2e_two` and ignition
in `e2e_one`, and again with the damage in `e2e_one` and ignition in `e2e_two`.

### Why all four must behave alike

A player will produce all of these by accident. If a portal recovers from a
removed block but not from a bucket of water, the mod has an inconsistency the
player will hit, and it is not discoverable except by testing each mechanism
separately.

---

## Step 13 — Proving the fixture, and reading the interior

### The fixture is proved at block level, by probing the world

Before any frame is captured, **query the server for the blocks** and confirm
the arena matches the specification exactly:

- floor and walls are the dimension's colour, over the whole extent
- wall height is the derived value
- the frame ring is present, complete, correct block, correct cells
- **exactly one** portal zone for the dimension — not zero, not two
- **no other blocks anywhere in the rig volume** — no leftover platform, no
  second frame, no stray magenta
- **no entities** in the volume — no leftover bot

This is authoritative about blocks, which is what a build check should be about.
A rendered frame can be wrong for reasons that have nothing to do with the
fixture, so proving the fixture with a render would confuse a build failure
with the defect being hunted.

**A failed fixture proof aborts the test.** It does not warn, and it does not
produce partial results.

### It does not prove rendering, and is not asked to

Block-level proof says the world is right. It says nothing about whether the
world renders right — that is the subject of the measurement, not its
precondition.

The unlit pass still carries its own evidence: with a proven-correct fixture,
every pixel must be that dimension's colour plus exactly one ring. Anything
else in the unlit pass, on a fixture that has been proved at block level, is a
**rendering** defect and is recorded as one. Sky in frame means the wall height
is wrong, and that is caught by the block probe first.

### The interior: every pixel, classified

Inside the located ring, **do not choose sample points**. Classify **every
pixel the ring encloses**.

Per frame, record:

- `count` of each class inside the ring
- **`OTHER` count and its location** — a map, not just a total
- the far extent of the destination colour inside the ring

Nothing can hide between chosen points, and a partial failure — half the
opening drawn, a wedge missing, a black band — is visible as structure in the
counts and the location map. A lattice of named points would miss exactly that.

**Today the interior is essentially 100% `OTHER`.** That is the finding, and
this is the measurement that states it as a number rather than a description.

### Outside the ring

Sample points outside the ring stay as Step 2 defines them — resolved from the
ring, read as exact single pixels — because outside the opening the question is
"did anything change that should not have", and named points that subtract
cleanly between the unlit and lit passes answer it.

---

## Step 14 — Traversal, and the verdict

### The traversal walk

A cardinal-direction walk through the portal and back, measuring at every step.
In scope, run as its own pass — never concurrently with a colour matrix, since
a player standing in the destination breaks those assertions.

**Approach.** From the **south** of the portal, 5 blocks away, facing it.

1. **Measure the portal interior** from 5 blocks out.
2. **Walk forward one block.** Measure. Repeat, one block at a time, all the
   way to the portal — a reading at every step.
3. **Cross.** Detect the transition to the other dimension. With shaders on
   there will most likely be a loading screen; the capture must **allow for it**
   and resume once the world is up, rather than measuring through it.
4. **Walk 5 blocks on** into the far side, one block at a time, measuring at
   each step — the colours must match precisely.
5. **Turn around.** Measure the portal interior again, from the far side.
6. **Walk back** towards the portal, one block at a time, measuring each step.
7. **Cross back**, handling the transition again.
8. **Walk 5 blocks** into the original dimension, measuring each step.

Repeated for **each cardinal approach direction**, not only south.

What this catches that a static matrix cannot: the view changing as the player
closes on the frame, the transition itself, and whether the arrival is where it
should be and the right colour when it lands.

### Verdict: per assertion, plus the numbers

The run does not collapse to a single pass/fail.

**Each named assertion gets its own verdict across the whole matrix**, reported
with the counts that support it. The run fails if **any** assertion fails — but
the report says which failed, where, and by how much.

```
A1  destination present inside ring     FAIL   0 of 77,760 lit frames
A2  no source colour inside ring        PASS
A3  no destination colour outside ring  PASS
A4  no entity in frame                  PASS
A5  unlit/lit difference inside ring    FAIL   changed pixels outside ring
                                               at 1,204 frames
A6  arrival portal created and lit      FAIL   never created
...
```

A single boolean would say "broken", which is already known. The per-assertion
verdict says **which part is broken and by how much**, which is the difference
between a result and a complaint.

Every assertion's numbers are recorded whether it passes or fails, so a drift
that has not yet crossed a threshold is visible before it does.

---

## Step 15 — Non-functional requirements, and how it gets built

### It must be FAST

This is run **hundreds of times** while iterating on the mod. It is not a
ceremony taken once. Every design choice bends toward speed.

**Both dimensions are `void` type, no water.** Nothing to generate, nothing to
tick, nothing to load. A void dimension with a built arena is the cheapest
possible world, and there is no terrain, no ocean and no cave to leak an
unexpected colour into a frame.

### It must be interrupt-safe

A partial run is still a result.

- Every reading is written **as it is taken**, not buffered to the end. The
  `.jsonl` file grows line by line, so a kill at any moment leaves valid data.
- Assertions not reached are recorded as **`skipped`**, never as passed and
  never silently absent. A run that covered 40% must say so.
- A crash, an interrupt or a failure **always** reports **where the data is**
  and **where the report is**. No outcome leaves the operator hunting for
  output.

### It must produce a human- and LLM-readable report

Alongside the raw `.jsonl`, a **markdown report**, written whatever the
outcome — pass, fail, crash, interrupt.

Simplified and legible: which assertions passed, which failed, which were
skipped, the numbers behind each, and where the raw data is. Short enough to
read, specific enough to act on.

### It must give a subagent confidence

The intended user is **a subagent that has just changed the mod's code** and
needs to know whether it helped.

That agent must be able to: make a change, run the harness, and get an answer
it can trust about whether things improved, stayed the same, or got worse. If
the harness cannot support that loop, it has failed at its job regardless of
how correct its measurements are.

Consequences: the report must be diffable against a previous run, and the run
must be cheap enough to sit inside an edit-test cycle.

---

## Step 16 — The portal, and where output goes

### The portal

Identical in both dimensions.

| | |
| --- | --- |
| opening | **2 wide x 3 tall** — the vanilla minimum |
| ring | 4 wide x 5 tall |
| axis | **X** — the plane faces north/south |
| centre | `x=0, z=0`, standing on the floor |

The vanilla minimum is deliberate: it is what a player actually builds, and it
is the smallest opening the renderer has to work with. If it fails at the
common case, larger sizes are academic.

Axis X means a player approaching **from the south looks due north straight
through it**, which is what the traversal walk and the close-range sweeps
assume when they name sides.

A larger opening would give more interior pixels at distance, and is worth a
second matrix later. It is not the first thing to test.

### Output location

```
~/Projects/minecraft-server-template/.e2e/rig/<run-id>/readings.jsonl
~/Projects/minecraft-server-template/.e2e/rig/<run-id>/report.md
```

`.e2e/` is temporary working space in the platform repo, **gitignored**. This
is not seed-rolling output and has nothing to do with the consumer pack, so it
does not belong in `.seed-rolling/` or in the consumer repo.

**Both paths are printed at the end of every run** — on success, on failure, on
crash, on interrupt. An operator never has to go looking.

---

## Step 17 — Shaders

### The pack is the one actually played with

Whatever pack is pinned in `options.txt`. Not a special test pack — the
question is whether the portal looks right **in the game as played**, and a
pack chosen for stability would stop answering it.

Every run records, in its conditions:

- the **shader pack filename**, exactly
- its **settings**, hashed
- the **Iris version**

A different pack, or changed settings, means **a different baseline**. Runs are
never compared across a pack change; the recorded conditions are what makes
that detectable rather than a silent shift in the numbers.

### Freeze everything freezable

Shader packs animate — clouds, water, waving foliage, temporal jitter. Before
accepting any variation as inherent, **eliminate every controllable source**:

- fixed time of day
- no weather
- frozen tick rate
- animated textures off where the pack allows
- temporal antialiasing off where the pack allows
- void dimensions, so there is no water, no foliage, nothing to wave

The rig is a flat single-colour room with no sky in frame, which removes most
of what a pack animates before any setting is touched.

### Then measure what is left

The 3 repeats are the check. **A reading that is not identical across all three
repeats is a finding**, and the first response is to find what is still moving
and freeze it — not to accept the spread as noise.

If a residual spread survives everything freezable, it is recorded per
condition as the **measured floor for shaders-on**, and no claim smaller than
it is reportable. That floor is measured, never assumed, and it is stated in
the report so nobody quotes a difference the instrument cannot resolve.

---

## Step 18 — Where the code lives

### The mod supplies pixels, and nothing else

A new dev-bridge endpoint returns **exact RGB for named screen coordinates**,
read on the render thread from the current frame. No PNG, no encode, no file.

```
POST /sample
  {"points": [[854,508], [790,430]]}
->
  {"points": [[854,508, 94,124,22],
              [790,430,  0,  0, 0]]}
```

This is new client-mod code the builder writes and tests. It is the piece that
makes 155,520 frames affordable — the measurement is a handful of integers, and
moving megabytes of image to extract them is the wrong shape.

**Whole-region reads** are needed too, for classifying every pixel inside the
ring (Step 13) and for locating the ring itself. The same endpoint serves them:
a rectangle request returns that region's pixels. Still no file.

### Everything else is Python, outside the mod

Locating the ring, resolving sample points from it, classifying, asserting,
writing the `.jsonl` and the report — **all outside the mod**.

The mod is the thing under test. If it computed its own verdict, a rendering
bug could corrupt the measurement of itself, and a failure would have no
independent witness.

**In particular the mod is never asked where it drew the aperture.** It knows,
and that is exactly the claim under test — taking its word for it defeats the
purpose. The ring is found in the pixels, by its colour, every time.

### Which means the analysis is testable with no Minecraft running

Because classification, location and assertion are pure functions over pixel
arrays, each is testable against **synthetic images**:

- a synthetic frame with a known ring at a known place — the locator must find
  exactly it
- a frame with a deliberate `OTHER` blob — the classifier must count it and say
  where
- a frame with two rings — must be rejected as a ghost frame
- a frame with the interior filled with the wrong colour — the assertion must
  fail

**Every assertion ships with a synthetic fixture that makes it fail.** An
assertion with no red fixture does not ship. That is the check the old method
never had, and it costs no game time at all.

---

## Step 19 — Preconditions, provenance, and the baseline

### Nothing is sampled until the frame is safe to sample

A **settle gate**, checked before every capture:

- world loaded
- no screen open (`currentScreen == null`) — a loading screen is not a frame
- frame rate above zero
- **destination residency confirmed** — a black opening looks identical whether
  the renderer is broken or `e2e_two` is simply idle-unloaded. Record the
  destination's chunk residency per frame and **refuse the reading at zero**.
  This matters most at the outer circles, which may sit beyond the portal's
  ticket range.

A gate that can be satisfied by zero is not a gate.

### A positive control on the capture path itself

Every assertion's failure state is currently **identical to "the bridge
returned a stale or void frame"**. That is exactly how the previous method
produced a wrong answer with confidence.

So, once per run: **place yellow concrete physically inside the unlit ring** and
require the capture to report YELLOW there. If that fails, **the run is void and
the mod is not implicated** — the instrument is broken.

### Provenance recorded in every run header

Without this, two runs can silently be the same build.

- **mod jar sha256**, read from inside the `mc` container — not from the build
  directory, which may be ahead of what is deployed
- the **whole `options.txt`, hashed**, with the load-bearing keys asserted
  individually: resolution, FOV, render distance, brightness, smooth lighting,
  mipmap levels, biome blend, view bobbing. Every one changes either the RGB or
  the pixel coordinates.
- shader pack filename, its settings hash, Iris version
- server and client versions

**Set mipmap to 0 and smooth lighting off.** Both exist to blend, and blending
is what creates values that belong to no class.

### Particles

Portal ambience inside the opening would land in `OTHER` and can break the
locator. **Off** — `portal.immersive.particleDensity: 0` in both dimension
configs. Not a class, not a tolerance; absent.

### The rig takes the stack lock

It wipes the shared `elfydd` world and rebuilds between tests. `stack-lock.sh`
is taken for the whole run, not per operation.

### Expectations baseline: CHANGED, not just RED

Several assertions are **expected to fail today** — the linkage tests describe
design behaviour the mod does not implement. Without declaring that, the report
is permanently red and "did my change help" is lost, which is the entire point.

Each assertion declares its **expected state**, committed:

```
A1  destination visible in ring      expect FAIL   (opening is black)
A6  arrival portal created and lit   expect FAIL   (not implemented)
A3  no destination colour outside    expect PASS
```

The report says **CHANGED / UNCHANGED against that baseline**. An assertion
that flips from expected-FAIL to PASS is the win condition, and it is visible
at a glance rather than buried in a red wall.

**The baseline is committed, and once the rig is finished it blocks
regressions.** Work is not pushed while the rig itself is being built — it lands
complete, and from then on an UNCHANGED-to-worse result is a blocker.

### Interrupt-safety needs three things

1. **Line-buffered, flushed writes.** Every reading hits disk as it is taken.
2. **A plan manifest written at start**, listing every intended assertion.
   Without it, `skipped` and "never existed" are indistinguishable.
3. **Report generation as a separate offline command** over `(plan, readings)`.
   A `SIGKILL` cannot write a report — so the report must be reconstructable
   from what is already on disk, at any time, by a second command.

### Damage states must be verified as the state intended

An explosion that also removes a ring block turns SHATTERED into BROKEN, and
the test then measures the wrong mechanism while appearing to pass.

**Probe the world after damaging and before asserting**, and confirm the
fixture is in the state the test names. If it is not, the setup failed — that
is not a result about the mod.

### `jump_apex` is reached by teleport, not by timing

Turn view bobbing off and **teleport to the apex eye height**. Same eye
position, no frame-accurate input, no timing problem across a third of the
matrix.

### Wall height

Derived, and it is not small. From the outermost circle at radius 32, looking
across at the far wall 80 blocks away, an upward ray at the steepest pitch in
the matrix clears anything under **~58 blocks**. Computing it against the
*near* wall gives ~13 and puts sky in half the frames.

Compute it from the **worst case in the matrix**, assert it at build time, and
recompute it if the matrix ever gains a wider angle or a higher eye.

---

## How it gets built

### One agent builds the whole thing

A **single subagent** takes this plan and builds it end to end. It gets a full
copy of this brief. Nothing falls between pieces because there are no pieces
handed to different people.

### Then an adversary reviews

A **second subagent**, also with a full copy of this brief, reviews what was
built and reports on how the first did. It does not build; it judges.

### Then the maintainer judges the judge

The orchestrator brings the adversary's findings back **as questions**, the way
this plan was written, so the maintainer can decide whether each finding is
sound or nonsense. **The orchestrator also serves as a judge** — it does not
simply relay.

### Rules for the orchestrator while the builder works

- **Monitor and stay in close contact — without meddling.** No corrections
  mid-flight. A brief reversed halfway makes an agent redo finished work.
- **Do not intervene before the adversary has reported.** The review is the
  intervention point.
- **If the builder has gone off the rails, or has been silent a long time,
  terminate it.** Start a new one with the same brief plus a note explaining
  what went wrong and how to avoid it.
- **Brief thoroughly and manage properly.** Agents wasted hours today on
  under-specified work; that is a management failure, not an agent failure.

---

## Build order

Dependency order. Each piece is provable on its own before anything depends on
it.

**Before piece 1:** `e2e_one` and `e2e_two` already exist and are **reused** —
their configs are changed to proper **void** type, and the local `elfydd`
server's **world data is completely wiped**. Not new dimensions; the same two,
rebuilt from nothing.

| # | piece | what it proves alone |
| --- | --- | --- |
| 1 | void configs for the existing pair, world wipe, arena builder, total reset | a clean fixture can be built and rebuilt identically |
| 2 | block-level fixture probe | the fixture matches spec, and a dirty one aborts |
| 3 | pixel capture with no file round-trip | exact RGB can be read fast |
| 4 | colour classification | every pixel resolves to a known class, and `OTHER` is countable |
| 5 | ring location from the frame colour | sample points resolve from the frame, at any pose |
| 6 | assertions, `.jsonl` writer, markdown report | a verdict and a record survive an interrupt |
| 7 | the matrix driver | positions, angles, heights, repeats, passes, both dimensions |
| 8 | linkage and damage-state tests | the seven linkage cases and the four damage states |
| 9 | traversal | the cardinal walk-through, both directions |

---

## The rules this plan is held to

1. Every number is **MEASURED** or **SPECIFIED**. Nothing is inherited from
   prior art, and nothing is copied from a previous run.
2. **No step depends on anything looking at an image and saying what it sees.**
   Every value is computed from pixel data.
3. **One pixel, exactly**, at a coordinate resolved from the frame.
4. A rejected frame or a failed fixture is a **failure of the run**, never a
   quietly missing row.
5. The fail rule is **written from the measured floor**, never chosen in
   advance to make results pass.
