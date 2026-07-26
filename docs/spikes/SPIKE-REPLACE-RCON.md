# Spike — Verification without RCON

> **Date:** 2026-07-27 | **Status:** decided, ready to build
> **Prompted by:** the owner, after a session where RCON commands routinely
> took minutes or timed out entirely, and by
> <https://modrinth.com/plugin/recon> / <https://github.com/yamak493/Recon>
> as a candidate replacement.
> **Decision:** don't replace the channel. Stop sending answers through it.

## Kickoff prompt

/goal

Read this spike in full (`docs/spikes/SPIKE-REPLACE-RCON.md`), then
`AGENTS.md`, `README.md`, `TROUBLESHOOTING.md` and `mods/AGENTS.md`
(§ Verification loop and § Noise structure placement), then
`mods/custom-dimensions/src/main/java/com/customdimensions/command/DimensionCommands.java`
and `scripts/check-noise-regression.py` — the pattern this plan generalises.
Start with Phase A (write the artefact contract down, then retrofit it).

Implement the artefact contract for the custom-dimensions mod: every
diagnostic command answers with a one-line summary plus a path, writes the
real answer to a versioned JSON file, and ships with a checker in `scripts/`
that asserts over that file with no server running.

For testing, use `~/Projects/elfydd` as the local consumer. Do not push to
elfydd's `main`. Follow the local loop in `mods/AGENTS.md` § Verification
loop — never `./dev up` to test a local mod build, and re-patch c2me's
`useDensityFunctionCompiler` before every `docker stop`/`start`.

Review all of the mentioned documentation, then say "Ready to start".

## Verdict

**Recon cannot be used, and replacing RCON would not fix what hurts.**

1. **Recon is a Bukkit plugin.** Its Modrinth loader list is
   `Bukkit, BungeeCord, Folia, Paper, Purpur, Spigot, Velocity, Waterfall` —
   no Fabric, no Forge, no NeoForge. It extends `JavaPlugin` against the
   Bukkit API; that class does not exist in our runtime. There is no "slight
   adjustment" that loads it. Fabric is a fixed platform decision
   (`AGENTS.md` § Fixed decisions) with ~150 Fabric-only mods behind it, so
   moving to Paper to gain Recon is not on the table either.
2. **The transport was never the bottleneck.** Measured below: the slow
   thing is the command, on the server thread. A REST request running the
   same command waits exactly as long and returns HTTP 504 instead of an
   RCON timeout.

What IS true is the owner's read: *"We basically cannot use rcon for
anything, and never have been able to."* The fix is to stop asking it to
carry answers.

## Evidence

Collected 2026-07-27 on the local consumer, on a **freshly generated
world**, using the mod's async locate — which calls the identical
`ChunkGenerator.locateStructure(world, entries, origin, 100, false)` that
`/locate structure` calls, off the server thread:

| Probe | Dimension | Pre-generated? | Result |
| --- | --- | --- | --- |
| `minecraft:jungle_pyramid` (noise-placed) | `the_overgrowth` (1024 border) | no | timed out at 180 s |
| `minecraft:jungle_pyramid` | `the_overgrowth` | **yes — full Chunky pass, 16,384 chunks, 59 cps** | timed out at 240 s |
| `minecraft:village_plains` | **`minecraft:overworld`** — stock vanilla, no custom placement in the path | yes | **timed out at 120 s** |

The last row settles ownership. Nothing this platform wrote is in that code
path and it is just as slow. Three conclusions:

- **Locate latency is a vanilla-plus-150-structure-mods property.** Not
  RCON's, not noise placement's, not custom dimensions'.
- **Chunky pre-generation does not fix it.** This was the plan of record
  (owner, 2026-07-26: *"Pre-warming a world down the line with chunky will
  help us there"*). Tested properly; it got slower, not faster. Retired in
  `mods/AGENTS.md`.
- **A better channel would not have helped**, because this already runs
  off-thread through our own async command — which is, as the owner put it,
  *"just a wrapped command… not really a better alternative but at least it
  doesn't crash the server."*

### What is genuinely wrong with RCON

| Problem | Real? | Fixed by a REST channel? | Fixed by the artefact contract? |
| --- | --- | --- | --- |
| Long commands block the server thread | Yes | No — same thread, same wait | Yes — the answer is a file that appears when ready |
| Responses truncate at a few KB | Yes | Yes | Yes — nothing large crosses the wire |
| Feedback lines concatenate with no separator | Yes | Yes | Yes |
| Empty response under load reads as success | Yes | Partly | Yes — the file is absent or stale, unambiguously |
| Silent during autopause | Yes | **No** — the JVM is frozen | N/A, and deliberate |
| One connection, no streaming | Yes | Yes | Yes |

**The autopause trap is the constraint any alternative must respect.**
`AGENTS.md` and `TROUBLESHOOTING.md` both carry it: never touch the game
port on an interval, it defeats autopause, and several Kuma monitor designs
died on that rule. A new control channel with a healthcheck or keepalive
would wake a paused server forever. The artefact contract adds no listener
at all, so it cannot break this.

## Decision: the artefact contract

**Diagnostic commands answer with a summary and a path. The file is the
answer. A checker asserts over the file, with no server running.**

This is not a new idea — it is three independent inventions of the same idea
that nobody has named. `structure-audit` writes `structure-audit.txt`
because 380 rows came back over RCON as one unreadable, half-missing string.
`structure-census` writes JSON because a large dimension holds tens of
thousands of positions. `sample-biome-grid` writes `biome_grid.csv`. All
three were written to dodge the same wall.

**It is also the only thing that worked under load this session.** G2's 72
assertions came from census JSON via `scripts/check-noise-regression.py`;
RCON's entire role was one short "go" command with a one-line answer — the
shape it handles perfectly well. The same session could not complete a
single `/locate`.

### The contract

1. **One line back, everything else on disk.**
   `<command> <subject>: <summary> -> <path>`. The summary must be
   independently useful (counts, not "OK") and must fit comfortably inside
   RCON's truncation limit.
2. **Artefacts live under `config/custom-dimensions/<kind>/`** in the server
   data directory — `data/config/custom-dimensions/…` on the host, already
   bind-mounted and already where the four existing ones land.
3. **JSON by default**, with two grandfathered exceptions kept because their
   consumer is a human or a spreadsheet: `structure-audit.txt`,
   `biome_grid.csv`.
4. **Every artefact carries `schemaVersion` and `generatedAt`.** A checker
   that meets an unknown version fails loudly. Silent mis-reading of a
   changed shape is the failure mode this platform keeps hitting.
5. **Writes are atomic** — `.tmp` then rename, exactly as
   `scripts/seed/candidates.py` already does. A checker must never read a
   half-written file, and a large census takes real time to serialise.
6. **Every artefact has a checker in `scripts/`** that runs with no Docker
   and exits non-zero on failure.
7. **Checkers are runnable against committed fixtures**, so a logic
   regression is caught in CI without a server —
   `scripts/seed/testdata/census/` already pins Java output this way for the
   parity gate.

### What stays on RCON, permanently

- `itzg/mc-backup`'s `save-off`/`save-on`. Third-party, not ours to change,
  and short fire-and-forget commands are what RCON is adequate for. **RCON
  can therefore never be removed, only demoted** — any plan promising
  otherwise is wrong.
- Interactive admin: `whitelist add`, `op`, `/mc restart`, `list`. Short in,
  short out.
- The "go" that triggers an artefact write.

## Task list

> **Estimate:** 4–5 days. Phase A is mechanical and unblocks the rest.
> Mark each `[x]` when complete and add handoff notes below the task.

### Phase A: name the contract and retrofit it (1 day)

- [ ] **A1. Write the contract into `mods/AGENTS.md`** — a new
  "§ Diagnostic artefacts" section stating the seven rules above, the
  artefact directory layout, and the rule that a command which iterates a
  registry or a world MUST NOT answer inline. Cross-reference from
  `TROUBLESHOOTING.md`'s symptom index ("RCON output truncated or
  concatenated").

  **Verify:** `grep -c 'schemaVersion' mods/AGENTS.md` non-zero; the four
  existing artefact commands are listed by name with their paths.

- [ ] **A2. Retrofit `schemaVersion`/`generatedAt` and atomic writes** to
  `structure-census`, `structure-audit`, `dump-biome-params` and
  `sample-biome-grid` in `DimensionCommands.java`. One shared helper
  (`writeArtefact(Path, String)`) doing `.tmp` + `ATOMIC_MOVE`.

  **Verify (JUnit + local server):**
  - A unit test on the helper: a write that throws mid-way leaves the
    previous file intact and no `.tmp` behind.
  - Live: dump each of the four, assert every file parses and carries both
    keys; re-dump while reading in a loop and assert the reader never sees a
    partial file.
  - `scripts/check-noise-regression.py` still passes 72/72 against the new
    census shape.

- [ ] **A3. Teach the checkers to enforce the version.** `schemaVersion`
  mismatch → explicit failure naming both versions, never a `KeyError` and
  never a silent pass.

  **Verify:** unit test feeding a checker an artefact with a bumped version;
  assert a non-zero exit and the message names the expected and found
  versions.

- [ ] **A4. Fix the id-resolution inconsistency found during G2.**
  `structure-census` requires a fully-qualified id (`adventure:the_overgrowth`)
  while `customdim load` accepts a bare slug and resolves it against the
  configured namespace; a bare name silently becomes `minecraft:` and answers
  "Dimension not loaded". Make every `customdim` subcommand resolve ids the
  same way.

  **Verify:** live, `customdim structure-census the_overgrowth` and
  `... adventure:the_overgrowth` produce identical files; an id in a
  genuinely different namespace still resolves or errors clearly.

### Phase B: the untested surface — portals (1.5 days)

`portal_links.json` is the mod's largest piece of persisted state, it is
already on disk, and **nothing anywhere asserts over it.** Every portal
regression this platform has had was found by a human in game or by a Carpet
bot script written from scratch each time.

- [ ] **B1. `customdim portal-audit`** → `portals/audit.json`: every source
  zone (dimension, column, frame accept forms, immersive settings,
  single-use countdown), every registered arrival with its stamped source
  column, exit portals, anchors, and the reconciliation state of each
  (orphaned zone, missing counterpart, dangling target dimension).

  **Verify (local server):** with a known portal built by the Carpet-bot
  harness, the audit lists it with the right column and target; break one
  frame block and the next audit reports the zone gone at both ends.

- [ ] **B2. `scripts/check-portal-integrity.py`** asserting the invariants
  `mods/AGENTS.md` § Portal system already states in prose: a persisted
  `frameBlock` is always a plain parseable id and never a `#tag` (this
  crash-looped production on a rollback, 2026-07-23); every arrival's
  `exitMode` is one of the three known values; anchor dimensions have an
  exit portal; every referenced target dimension exists in config; no zone
  references a dimension that has left the config.

  **Verify:** run against a real `portal_links.json` — passes; against
  hand-crafted broken fixtures (a `#tag` frameBlock, a dangling dimension,
  an anchor with no exit portal) — one clear failure each.

### Phase C: the boot report (1 day)

The bug that ate the most time this session was invisible for hours: 63 of
78 dimensions were silently reusing generators baked into `level.dat` under
an older config ([D2](../../TROUBLESHOOTING.md#d2)), so every biome-filter
assertion was measuring history. The evidence was there — 15 `biome source
built` lines instead of 75 — but only if you already suspected it and knew
to count.

- [ ] **C1. One artefact per boot** → `boot-report.json`: for every
  configured dimension, whether it was **created or reused**, its resolved
  biome source (count of entries and distinct biomes), its structure profile
  (groups, positions, biome-filtered counts), border, portal presence, and
  its **fingerprint drift state** against the current config.

  **Verify:** boot a stale world and a fresh one; the report distinguishes
  every dimension's `created` vs `reused` correctly, and the stale boot flags
  drift on the dimensions whose config moved.

- [ ] **C2. `scripts/check-boot-report.py`** — fails when a dimension is
  `reused` with a drifted fingerprint, which is precisely "this world no
  longer matches its config and any test you run against it is lying".

  **Verify:** passes on the fresh world; fails on the pre-wipe backup at
  `data/world.bak.*`, naming the drifted dimensions.

### Phase D: make the checkers a gate (0.5 day)

- [ ] **D1. `./dev verify`** — runs every checker against the current
  artefacts, prints one PASS/FAIL block per checker, exits non-zero on any
  failure. Wired into `examples/consumer/dev` and its sync list (the
  consumer-scaffold trap in `AGENTS.md`).

  **Verify:** `./dev verify` on the local consumer is green; break one
  artefact by hand and it goes red with a useful message.

- [ ] **D2. Commit fixtures and run the checkers in CI** against them, so a
  checker-logic regression is caught with no server. Extend
  `scripts/test-scripts.sh` (or `lint.yml`) the way the parity gate already
  works.

  **Verify:** `./scripts/test-scripts.sh --quick` green; deliberately break
  a checker's logic and confirm CI would fail.

### Phase E: retire the fragile RCON parsing (1 day, optional)

- [ ] **E1. `doctor.sh`'s `spark health` grep** (`grep -iE "tps|memory"` over
  prose) is the last load-bearing parse of RCON output. Either accept it as
  best-effort and say so in the header, or replace it with a `customdim`
  artefact carrying TPS/MSPT/memory as numbers.

  **Verify:** `./ops doctor` reports TPS on a live server and degrades to a
  clear WARN — never a false PASS — when the value cannot be read.

## Risks

| Risk | Mitigation |
| --- | --- |
| Artefacts drift from the code that writes them | `schemaVersion` + checker enforcement (A2/A3); a mismatch is a loud failure |
| A checker passes because the artefact is stale, not because the world is right | `generatedAt` + a max-age assertion in `./dev verify`; the C1 boot report is regenerated every boot |
| Growing artefacts bloat `data/config/` | Census files are already the largest at ~1 MB for `the_end_citadel`; keep them out of backups (`AGENTS.md` § Backups excludes) and prune on write |
| A new command answers inline out of habit | A1 states the rule; review catches it; the truncation symptom is in the index |
| Scope creep into "replace RCON" | Explicitly out of scope — `mc-backup` pins RCON in place regardless |

## Files touched

| File | Change |
| --- | --- |
| `mods/AGENTS.md` | **New** § Diagnostic artefacts — the contract |
| `TROUBLESHOOTING.md` | Symptom-index row for truncated/concatenated RCON output |
| `command/DimensionCommands.java` | `writeArtefact` helper; version stamps; id resolution; `portal-audit`; boot report |
| `scripts/check-noise-regression.py` | Version enforcement |
| `scripts/check-portal-integrity.py` | **New** |
| `scripts/check-boot-report.py` | **New** |
| `examples/consumer/dev` | `verify` subcommand + sync-list entry |
| `scripts/test-scripts.sh` | Run the checkers against committed fixtures |

## Rejected alternatives

**A. Per-command async job wrappers.** What `LocateManager` already does.
Whack-a-mole: each slow command needs someone to notice and hand-write a
wrapper, and the result still cannot tell you where a village is. Do it when
a command is being written anyway; not as a programme.

**B. An in-house Fabric HTTP control mod.** Recon's idea in our runtime,
with job semantics as the default, typed errors and streamed responses. Real
value, and buildable — we already ship a Fabric mod through a verified
release pipeline. Rejected *for now* because it is a week of work and a new
network listener (with the autopause hazard) to deliver structured output we
can have today by writing a file. Reconsider if Phase B/C leave genuine gaps.

**C. An existing Fabric alternative.** None found. `CraftControl RCON`
(CurseForge) supports Fabric but is an RCON companion, not a replacement.
Worth one more search before ever building B.
