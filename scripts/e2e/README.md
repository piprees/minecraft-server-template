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

| Script | Proves |
| --- | --- |
| `e2e-item2-stronghold.sh` | Builds its own twelve-socket rig. One eye sockets and spawns no gateway; a ringed cell ignites and a bare block does not; the portal is dark at eleven eyes and lit at twelve; the player reaches the End and is still there ten seconds later; `seenCredits` unchanged |
| `e2e-item3-end-exit.sh` | Builds a bedrock-floored 3x3 `end_portal` (this world's preset has no dragon and no natural exit) and proves the mod does not refuse it — overworld on contact and still overworld ten seconds later |
| `e2e-item4-idle-unload.sh` | Both halves of idle unload: resident while the player is near the frame, gone after a walk-away, polled to a real figure rather than a fixed wait. The only script that needs no client |
| `e2e-item5-crucible.sh` | The companion chain end to end: build, ignite, walk in on the real client, and require **both** `companion-suppress:arrival-screen` lines (`site=joinWorld` and `site=startWorldLoading` — one line is a fail), the server's `companion-send:preloaded-transfer`, arrival in `adventure:the_crucible`, and no mc restart mid-run |
| `e2e-c1-render.sh` | The destination render through the aperture: the client's own projection with its mesh built and its quad count, and the emit line's per-layer counts |
| `portal-aperture.sh` | A scratch portal at 2048/90/2048 lit, photographed from both sides, and walked through |
| `build-test-frame.sh` | Builds the copper crucible frame and flattens the approach lane. Called by item 5 |
| `lib-selftest.sh` | `lib.sh` itself, against fixture artefacts and a stub bridge. No Minecraft, no docker, no RCON |

## What the harness knows that you do not

`lib.sh` carries these as helpers, each written after a run failed for the
reason it now prevents. Read its header comments before changing an assertion.

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
- `require_backup_idle` refuses to run while `mc-backup-local` is mid-cycle: it
  drives the same RCON socket and its replies arrive as answers to your probes.
- An incomplete run is forced to FAIL by the `EXIT` trap, so dying early can
  never look like a pass.
