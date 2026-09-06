# End-to-end harness

Template-only. Drives a **linked local consumer** (`./dev link`) and the real
Minecraft client through the portal and companion-mod behaviour that no unit
test can reach: ignition, traversal, arrival, idle unload, and the client's own
loading-screen suppression.

Not shipped in the stack bundle, not referenced by `ops` or `dev`, so it is
absent from `build-stack-bundle.sh`'s `MANIFEST` by design.

## The two instruments

Every assertion reads one of these. Nothing parses an RCON reply for data, and
nothing touches the window manager.

**The server** — `customdim e2e-state <player>`, one RCON round trip that writes
`$CONSUMER_DIR/.seed-rolling/e2e-state.json` (or the mod's config directory when
that mount is absent). `state_refresh` runs it and refuses an artefact whose
`generatedAt` did not change, so the previous call's answer can never be read as
this one's. Facts are typed: a player who is not there is `"online": false` with
null everywhere, so a position is a number or nothing.

**The client** — the companion mod's dev bridge, opened by
`./dev launch --dev-bridge` on loopback port 8766 and deleted by a plain launch.
`/state` reads the client's own view, `/screenshot` writes the GAME's
framebuffer to a path it then reports, and `/input` holds a real key binding and
comes back with how far the player got and why it stopped.

## Run

```bash
cd ~/Projects/elfydd && ./dev up && ./dev launch --dev-bridge   # stack + client, then join
/Users/pip/Projects/minecraft-server-template/scripts/e2e/e2e-item5-crucible.sh
```

`CONSUMER_DIR` (default `~/Projects/elfydd`), `RUN_ROOT` (default
`/tmp/c0-e2e`) and `BRIDGE_PORT` (default `8766`) are the knobs. The player is
resolved from the consumer's `.env` — `WHITELIST` first, then `OPS`, first name
of either — and the run refuses rather than guessing; `PLAYER=<name>` overrides
it. Each run writes a timestamped directory of screenshots, JSON artefacts,
logs and `assertions.tsv`.

Order matters for two of them: item 4 needs a resident dimension, so run item 5
first; item 3 needs the player in the End, so run item 2 first.

The portal matrix is the long one and takes options rather than an order:

```bash
./scripts/e2e/portal-matrix-selftest.sh                        # its own logic, no server
./scripts/e2e/e2e-portal-matrix.sh --dry-run                   # the plan and its denominator
./scripts/e2e/e2e-portal-matrix.sh --only the_crucible         # one dimension end to end
./scripts/e2e/e2e-portal-matrix.sh --horizontal all --render 8 # every Y-axis frame too
```

79 dimensions is hours, and worlds are created one at a time on purpose
([K6](../../TROUBLESHOOTING.md#k6)) — `--limit`, `--only` and `--from` are how
it is run in pieces. `IGNITER_EXPECTATION` is the one switch for what an
igniter should look like after a successful ignition: `auto` (the ruled
contract — damageable igniters take damage, others are untouched),
`untouched`, `damaged`, or `consumed`. `auto` mirrors `IgniterSpend.of`,
`consumesIgniter` included.

| Script | Proves |
| --- | --- |
| `e2e-item2-stronghold.sh` | Builds its own twelve-socket rig. One eye sockets and spawns no gateway; a ringed cell ignites and a bare block does not; the portal is dark at eleven eyes and lit at twelve; the player reaches the End and is still there ten seconds later; `seenCredits` unchanged |
| `e2e-item3-end-exit.sh` | Builds a bedrock-floored 3x3 `end_portal` (this world's preset has no dragon and no natural exit) and proves the mod does not refuse it — overworld on contact and still overworld ten seconds later |
| `e2e-item4-idle-unload.sh` | Both halves of idle unload: resident while the player is near the frame, gone after a walk-away, polled to a real figure rather than a fixed wait. The only script that needs no client |
| `e2e-item5-crucible.sh` | The companion chain end to end: build, ignite, walk in on the real client, and require **both** `companion-suppress:arrival-screen` lines (`site=joinWorld` and `site=startWorldLoading` — one line is a fail), the server's `companion-send:preloaded-transfer`, arrival in `adventure:the_crucible`, and no mc restart mid-run |
| `e2e-c1-render.sh` | The destination render through the aperture: the client's own projection with its mesh built and its quad count, and the emit line's per-layer counts |
| `e2e-c3-portal-light.sh` | Both of a portal's light paths, separately. The server half proves the aperture's light is a fake block — air with zero luminance and zero block light at the same cells the client is lighting. The client half reads the block-light gradient out of the opening (a shape only a point source there produces), the destination light the server sent, and the light the mesh was built with. Then it forces a server light update in the opening's own chunk section and re-reads, with the light block itself as the positive control. `RIG=nexus` (default) or `RIG=crucible` |
| `flicker-record.sh` | Whether the opening changes between CONSECUTIVE frames. Captures a cropped screen region with `screencapture` at ~9-11 frames/s — the client's own frame rate, where `/screenshot` is 1 Hz — and `flicker-check.py` reports run lengths and separates an opening that goes EMPTY from one that alternates between two contents, by Rec.709 luma spread per state. `--self-test` proves the whole path against a synthetic window with a known defect and no Minecraft |
| `verify-arena.py` | That an arena is the box it claims to be. Samples the floor, all four wall faces to full height, the interior air and the ABSENT ceiling, and reports `That position is not loaded` as its own failure rather than a pass — an unloaded chunk answers every question with silence and places nothing. `--except-box=` exempts a region the arena does not own, such as a portal frame standing in it |
| `frame-identity.py` | Whether a scene is static at all: differing pixel counts at threshold 0 between frames of an untouched scene, per named box, with the peak channel delta that separates one cause from another. This is the acceptance test for a benchmark, taken BEFORE any number is quoted in it |
| `shadow-gradient.py` | Whether there is a shadow across the projection AT ALL, scored against "there is no shadow" rather than "the shadow tracks the world". Reports the left-right luma ramp inside a single-material crop across several poses from ONE eye, and refuses a single pose — the defect is a difference BETWEEN poses, which no scalar per pose can represent |
| `portal-aperture.sh` | A scratch portal at 2048/90/2048 lit, photographed from both sides, and walked through |
| `build-test-frame.sh` | Builds the copper crucible frame and flattens the approach lane. Called by item 5 |
| `stack-lock.sh` | One writer at a time for the stack. Take it before any container action; a busy lock names the holder and how long they have had it |
| `rig-ready.sh` | Asserts the rig is measurable — mc healthy, RCON answering, bridge up, client actually IN a world — and recovers a dropped client. Run it before and after anything that touches the client |
| `e2e-portal-matrix.sh` | The whole lifecycle once per configured dimension: carve a bay, stand a frame and a deliberate near neighbour in it unlit, then ignite, cross, come back, break it, re-light it, and light the neighbour. Every geometry and every expectation comes from the plan, so no coordinate is written here |
| `portal-matrix-plan.py` | The pure half of the matrix: dimension configs in, a bay per mod-managed portal out — frame geometry, room layout, orientation filtering, and what each assertion should expect. Runs with no server |
| `portal-matrix-lib.sh` | The matrix's own helpers, apart from the driver so they can be self-tested: `assert_items`, `within_reuse_box`, `near_column`, `place_cells`, the bounded waits |
| `portal-matrix-selftest.sh` | The planner's arithmetic and those helpers, each proved red and green against fixtures. `--mutate` breaks one piece on purpose to show which case catches it. No Minecraft, no docker, no RCON |
| `lib-selftest.sh` | `lib.sh` itself, against fixture artefacts and a stub bridge. No Minecraft, no docker, no RCON |

## Before you restart anything: `stack-lock.sh`

**`./dev up` RECREATES mc.** Every client in the world goes to Connection Lost
and any measurement in flight is invalidated. When several agents share one
stack, "tell everyone first" does not work — messages arrive batched, so two
agents can each believe they were cleared. This is the interlock that does.

```bash
scripts/e2e/stack-lock.sh status
scripts/e2e/stack-lock.sh acquire <holder> --reason "what for" [--wait 300]
scripts/e2e/stack-lock.sh release <holder>
```

`mkdir` is the atomic test-and-set. A busy lock names the holder and how long
they have had it, so you know who to ask. Releasing a lock you do not hold is
refused, so a late release cannot free somebody else's slot. A holder that dies
leaves the lock behind — `status` reports STALE past 15 minutes, and `steal`
works only then and only with a reason.

It is ADVISORY: it cannot stop `./dev up`, it stops a careful agent racing
another careful agent. Take it around **any** container action, and around a
client relaunch.

## Before you measure: `rig-ready.sh`

**A client on "Connection Lost" is invisible to every other instrument.** The
bridge still answers `/health`, the process is still alive, `mc` is still
healthy — and a screenshot never arrives. Any `mc` restart drops the client, so
this is the normal state after a `./dev up`, not an exceptional one.

```bash
scripts/e2e/rig-ready.sh              # check, and recover if it can
scripts/e2e/rig-ready.sh --check      # report only, never launches or kills
```

Run it **before and after** any measurement that touches the client. It asserts
`mc` healthy, RCON answering, the bridge up, and `worldLoaded` true, which is
false on the Connection Lost screen. When the client is stuck it kills the JVM
(Prism swallows a `--launch` for an instance already running) and relaunches,
waiting a capped 180s.

**`worldLoaded` alone is not a settle gate.** It reads true on a loading screen
with `fps=0`, and a screenshot taken there is a void that looks like a
measurement. Poll `worldLoaded && currentScreen == null && fps > 0` before every
shot; `rig-ready.sh` checks only the first.

It exits non-zero with the reason NAMED. **Never wait on the client yourself** —
that is the wait that never ends.

## What the harness knows that you do not

`lib.sh` carries these as helpers, each written after a run failed for the
reason it now prevents. Read its header comments before changing an assertion.

- **A jar's sha identifies its commit.** The Gradle build here is
  deterministic: `git archive <commit> mods | tar -x` into a scratch tree and
  rebuild gives a jar that hashes identical to one built from that commit in
  place. Re-read the installed hash immediately BEFORE every install rather than
  once at the start — that is what catches a jar swapped underneath a
  measurement.
- **With no shader pack the frame is deterministic.** Two shots of one condition
  at one camera differ by 0 pixels on the source control, so there is no noise
  floor to argue about and any non-zero control is a real change. With a pack
  loaded there is one and it has to be measured per session — recent
  same-condition floors are 5.20%, 5.25% and 6.63%.
- **A read that has no value says so in words.** `json_read` returns the value,
  or a sentence — and keeps three cases apart: a filter that does not compile
  (a harness bug), a filter that matches nothing (the fact is absent), and a
  value. A run that scored 22/4 once reported `z=No entity was found` as a
  coordinate; nothing here can do that again.
- **A zone is found by a cell of its own interior, never by where it leads.**
  An ARRIVAL zone standing in the same world legitimately carries the same
  `targetWorld` — the crucible's return arrival sits in the overworld and names
  the crucible — so counting by `targetWorld` counts two things. The mod's zone
  identity is `(target, axis, interior)`, so `source_zone_select x y z` is the
  question that matches it, and two source zones over one interior is the
  re-ignition duplicate `PortalHelper.addZoneIfAbsent` exists to prevent.
- **`frameStands` is three-valued.** `true`, `false`, or `null` when the chunks
  are cold and nobody could tell — `resident` says which. Assert `= true`, never
  truthiness, and never treat `null` as `false`.
- **A walk's `arrived` and `stalled` are independent.** A walk can fail without
  stalling: too slow, or in a circle. `assert_walk` asserts `arrived`.
  `walk_to_dimension` does not even use it — crossing moves the player far
  enough that any walk reads as arrived, so the oracle is the SERVER's dimension
  after each step.
- **Never assert a vanilla screen by name.** `currentScreen` carries the
  intermediary name (`class_424`). `client.arrivalScreen` is an `instanceof`
  check and survives remapping.
- **With no player in the world, `/state`'s whole `player` record is
  `{"absent": …}`** and every path under it reads `null` — a value, not an
  absence, and the same `null` an empty hand gives. That is the state during
  world load, which is when a harness polls. Read client player facts through
  `bridge_player_read` / `assert_bridge_player`, which check the absence first
  and report it in words.
- **`held.mainHand` is an object, and `mainHandItem` does not exist.**
  `held.mainHand.id` is the only spelling. `offHand` and an empty hotbar slot
  are `null`, `hotbar` is always nine entries so an index means the same slot
  every call, and `damage`/`maxDamage` are present-with-`null` for anything that
  cannot wear — a fact, not an absence.
- **The status flags and the pose speak different languages.** Sneaking reports
  `pose: "CROUCHING"`, gliding reports `"FALL_FLYING"`, and crawling reports
  `"SWIMMING"` with `swimming: false` — `isCrawling()` is
  `isInSwimmingPose() && !isTouchingWater()`, so the pose alone cannot tell
  crawling from swimming. Assert the boolean, never the pose string. `inWater`
  (feet wet), `submerged` (eyes under) and `swimming` (the sprint-swim flag) are
  three different facts.
- **No number carries a `.0`.** The writer rounds to 3dp and strips trailing
  zeros, so full health is `20` and a position component is `130`. `jq` does not
  care, but every assertion here compares strings, so expect the stripped form.
- **Do not assert `vitals.saturation` against the server.** The client only
  receives it inside `HealthUpdateS2CPacket`, which is not sent when saturation
  drifts on its own, so it lags an RCON `data get entity` reading.
- **Never compute inside jq.** `null + 1` is `1`, so a missing field would come
  back as a number. Read the value, then compare in the shell.
- `forceload` holds the build region. **A player is not a chunk ticket** — one
  teleported in before the floor exists falls, takes the ticket with them, and
  every later `fill` answers "position not loaded" after a probe said ready.
- `wait_for_chunk` replaces a blind sleep after a teleport, and reports how long
  it took.
- `ASSERT_DIM` names the world every probe runs in. Unset means the overworld,
  so an End assertion without it silently measures a different world
  ([T50](../../TROUBLESHOOTING.md#t50)).
- `use_block` / `stand_and_use` run `customdim use`, which goes through
  `ServerPlayerInteractionManager.interactBlock`. **Synthetic right-clicks do
  not reach this game** — six implementations were measured, and a Carpet fake
  player's `use once` produces nothing either. The dev bridge's `/input` drives
  key bindings, which is why walking works through it and a click still does not.
- **A walk stops dead on a one-block rise.** Vanilla step height is 0.6, so a
  player auto-steps a slab and not a block. Any test that walks a distance
  flattens its lane to the frame's own bottom-ring level.
- **`orientation: "horizontal"` is a Y-plane requirement, not a ban on lying
  flat.** `PortalDefinition.allowsAxis` reads it as "the Y axis and nothing
  else", so the seven dimensions that carry it refuse a VERTICAL frame and must
  be built horizontal. The matrix reads the field rather than trying both.
- **An anchor dimension does not break at both ends.**
  `PortalHelper.breakLinkedArrival` returns 0 for one, because a single arrival
  is shared by every source into that dimension. Its bay asserts the arrival
  SURVIVES the source break, and that a re-light lands back on it.
- **The reuse box is `(5, 16)` in DESTINATION blocks**
  (`findRegisteredPortalNear`, mirrored by `ArrivalResolver`), so how close two
  source frames have to be depends on the dimension's scale. The plan computes
  the neighbour's offset and refuses to assert proximity for a pair that
  divides out past the radius.
- **An arrival coordinate is not stable across a break.** Arrival placement
  carves when no open site exists, and the broken arrival's frame is left
  standing, so the heightmap the next one reads has moved. Assert the dimension
  and the round trip, never a fixed arrival coordinate.
- **A destination that idled out lists no zones at all.** `e2e-state` reports
  zones per LOADED world, so every arrival assertion first proves the world is
  still there rather than reading an unload as an unlink.
- **`<slug>_thumb.json` is a seed-viewer sidecar, not a dimension.** There is
  one per dimension, so reading the directory naively doubles the denominator
  and files 82 "no portal block" skips against things that were never
  dimensions. The planner filters them and reports `sidecarsIgnored` beside
  `dimensionsRead`.
- **An igniter is spent only where a dimension asks for it.** `IgniterSpend.of`
  checks `consumesIgniter` BEFORE `damageable`, so a dimension that wants the
  item gets the item even when the item could have taken damage instead. The
  matrix's `auto` expectation mirrors that order.
- **Java's integer division truncates toward zero and Python's `//` floors.**
  `PortalBreakLink.centreColumn` is Java's, and the matrix room stands at
  negative coordinates, so the planner reimplements the Java rule
  (`java_int_div`) and the self-test pins both signs.
- `require_backup_idle` refuses to run while `mc-backup-local` is mid-cycle: it
  drives the same RCON socket and its replies arrive as answers to your probes.
- An incomplete run is forced to FAIL by the `EXIT` trap, so dying early can
  never look like a pass.
