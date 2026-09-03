# End-to-end harness

Template-only. Drives a **linked local consumer** (`./dev link`) and the real
Minecraft client through the portal and companion-mod behaviour that no unit
test can reach: ignition, traversal, arrival, idle unload, and the client's own
loading-screen suppression.

Not shipped in the stack bundle, not referenced by `ops` or `dev`, so it is
absent from `build-stack-bundle.sh`'s `MANIFEST` by design.

## Run

```bash
cd ~/Projects/elfydd && ./dev up && ./dev launch    # stack + client, then join
/Users/pip/Projects/minecraft-server-template/scripts/e2e/e2e-item5-crucible.sh
```

`CONSUMER_DIR` (default `~/Projects/elfydd`) and `RUN_ROOT` (default
`/tmp/c0-e2e`) are the only knobs. The player is resolved from the consumer's
`.env` — `WHITELIST` first, then `OPS`, first name of either — and the run
refuses rather than guessing; `PLAYER=<name>` overrides it. Each run writes a
timestamped directory of screenshots, logs and `assertions.tsv`.

Order matters for two of them: item 4 needs a resident dimension, so run item 5
first; item 3 needs the player in the End, so run item 2 first.

| Script | Proves |
| --- | --- |
| `e2e-item2-stronghold.sh` | Builds its own twelve-socket rig. One eye sockets and spawns no gateway; a ringed cell ignites and a bare block does not; the portal is dark at eleven eyes and lit at twelve; the player reaches the End and is still there ten seconds later; `seenCredits` unchanged |
| `e2e-item3-end-exit.sh` | Builds a bedrock-floored 3x3 `end_portal` (this world's preset has no dragon and no natural exit) and proves the mod does not refuse it — overworld on contact and still overworld ten seconds later |
| `e2e-item4-idle-unload.sh` | Both halves of idle unload: resident while the player is near the frame, gone after a walk-away, polled to a real figure rather than a fixed wait |
| `e2e-item5-crucible.sh` | The companion chain end to end: build, ignite, walk in on the real client, and require **both** `companion-suppress:arrival-screen` lines (`site=joinWorld` and `site=startWorldLoading` — one line is a fail), the server's `companion-send:preloaded-transfer`, arrival in `adventure:the_crucible`, and no mc restart mid-run |
| `e2e-c1-render.sh` | The destination render through the aperture, with the emit line's per-layer quad counts |
| `portal-aperture.sh` | A scratch portal at 2048/90/2048 photographed from both sides. Screenshots land in `$E2E_OUT` (default `/tmp/c0-e2e/aperture`) |
| `build-test-frame.sh` | Builds the copper crucible frame and flattens the approach lane. Called by item 5 |

## What the harness knows that you do not

`lib.sh` carries these as helpers, each written after a run failed for the
reason it now prevents. Read its header comments before changing an assertion.

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
  player's `use once` produces nothing either.
- `require_backup_idle` refuses to run while `mc-backup-local` is mid-cycle: it
  drives the same RCON socket and its replies arrive as answers to your probes.
- An incomplete run is forced to FAIL by the `EXIT` trap, so dying early can
  never look like a pass.
