# Spike — Replace RCON as the server control channel

> **Date:** 2026-07-27 | **Status:** research, not scheduled
> **Prompted by:** the owner, after a session where RCON commands routinely
> took minutes or timed out entirely.
> **Trigger artefact:** <https://modrinth.com/plugin/recon> /
> <https://github.com/yamak493/Recon>

## Verdict up front

**Recon cannot be used on this platform, and it would not fix the problem we
actually have.** Two independent reasons, either one sufficient:

1. **Recon is a Bukkit plugin.** Its Modrinth loader list is
   `Bukkit, BungeeCord, Folia, Paper, Purpur, Spigot, Velocity, Waterfall` —
   no Fabric, no Forge, and no NeoForge. It is written against the Bukkit
   API (`JavaPlugin`, `Bukkit.getScheduler`, `config.yml`, `users.yml`), not
   the Minecraft server internals a Fabric mod hooks. There is no "slight
   adjustment" that loads a Bukkit plugin on Fabric; the class it extends
   does not exist in our runtime. **Fabric is a fixed platform decision**
   (`AGENTS.md` § Fixed decisions) and ~150 of our mods are Fabric-only, so
   moving to Paper to gain Recon is not on the table either.
2. **The pain is not the transport.** Measured this session (see Evidence),
   the slow thing is the *command*, not the pipe it arrives through. A REST
   request that runs the same command on the same thread waits exactly as
   long — it just gets an HTTP 504 instead of an RCON timeout.

That said, the frustration is real and there IS a spike's worth of work
here. It is just aimed somewhere else. Read the Evidence before deciding.

## Evidence — what is actually slow

Collected 2026-07-27 on the local consumer, on a **freshly generated world**,
using the mod's own async locate (which calls the identical vanilla
`ChunkGenerator.locateStructure(world, entries, origin, 100, false)` that
`/locate structure` calls, off the server thread):

| Probe | Dimension | Chunks pre-generated? | Result |
| --- | --- | --- | --- |
| `minecraft:jungle_pyramid` (noise-placed) | `the_overgrowth` (1024 border) | no | timed out at 180 s |
| `minecraft:jungle_pyramid` | `the_overgrowth` | **yes — full Chunky pass, 16 384 chunks** | timed out at 240 s |
| `minecraft:village_plains` | **`minecraft:overworld`** — vanilla, untouched, no custom placement anywhere | yes (spawn region) | **timed out at 120 s** |

The last row is the control that settles it. The overworld's village
placement is stock vanilla; nothing this platform wrote is in that code
path. It is just as slow.

Three conclusions follow, and they are the reason this spike exists:

- **Locate latency is a vanilla-plus-150-mods property**, not an RCON
  property and not a custom-dimensions property. `/locate` walks 100 rings
  of placement cells and asks each candidate whether a structure would
  validate there; with this many structure mods that is a lot of work.
- **Chunky pre-generation does not fix it.** This was the accepted plan of
  record (owner's ruling, 2026-07-26: *"Pre-warming a world down the line
  with chunky will help us there"*). It was tried properly here — a complete
  1024-radius pass — and the locate got *slower*, not faster. That
  assumption should be retired.
- **Replacing RCON would not have helped any of these.** They are already
  running off-thread through our own async command.

## What IS wrong with RCON here

Separating the genuine grievances from the misattributed one, because a
spike that fixes the wrong thing is worse than no spike:

| Problem | Real? | Would a REST channel fix it? |
| --- | --- | --- |
| Long-running commands block the server thread | **Yes** | **No.** Same thread, same wait. Only running the work off-thread fixes it, which is what `customdim locate` already does. |
| Responses truncate at a few KB | **Yes** | Yes. This is why `structure-audit` and `structure-census` write files instead of answering (`mods/AGENTS.md`). |
| Feedback lines concatenate with no separator | **Yes** | Yes — 380 audit rows came back as one unreadable string. |
| Empty response under load reads as success | **Yes** | Partly — a typed error beats an empty string, but a timeout is still a timeout. |
| RCON goes silent during autopause | **Yes** | **No.** The JVM is frozen; nothing answers, over any protocol. And this is *deliberate* — see below. |
| One connection, no streaming or events | Yes | Yes |
| Password in `.env`, plaintext TCP | Yes, but | It never leaves the Docker network (`AGENTS.md`: RCON is not exposed publicly) |

**The autopause interaction is the trap in this whole idea.** `AGENTS.md`
and `TROUBLESHOOTING.md` both carry the rule: *never add anything that
touches the game port on an interval — it defeats autopause*, and several
Kuma monitor designs died on it. Any replacement control channel that
health-checks, polls, or keeps a connection warm will wake a paused server
and burn the host's idle savings. Whatever we build must be as silent as
RCON is when nobody is playing.

## Options, if this is worth doing

### A. Fix the command side, not the channel (cheapest, highest value)

Extend the pattern the mod already proved: `LocateManager` submits work to a
pool, returns a UUID immediately, and `locate-result` polls it. That
converts an unbounded main-thread stall into a bounded, cancellable job with
a typed status — over plain RCON, with no new protocol, no new port and no
new dependency.

Candidates for the same treatment: anything that scans, walks or generates —
census dumps, audits, chunk operations. `structure-audit` and
`structure-census` already dodge the truncation problem by writing files;
that is the same instinct.

**Cost:** small, incremental, per command. **Risk:** low — the pattern is in
production. **Does not solve:** truncation for commands that legitimately
want to answer inline.

### B. An in-house Fabric control mod (Recon's idea, our runtime)

We already build and ship a Fabric mod (`mods/custom-dimensions`) through a
release pipeline with jar verification. Adding a second one that exposes a
small authenticated HTTP endpoint is entirely within the existing machinery.

What it would need to earn its place:
- Bound to the Docker network only, exactly like RCON today — never
  published through the Cloudflare tunnel (game-adjacent traffic must not
  go through it; `AGENTS.md` fixed decision).
- **No polling, no keepalive, no healthcheck of its own.** See the autopause
  trap above.
- Async by default: every command returns a job id; results are fetched.
  This is the actual fix, and it is worth more than the transport change.
- Chunked/streamed responses, so an audit can answer inline.
- Consumers to migrate: `scripts/lib.sh` (`rcon()`), `scripts/rcon.sh`,
  `deploy.sh`, `doctor.sh`, `setup-permissions.sh`, `idle-tasks.sh`,
  `chunky.sh`, `live-stats.sh`, `dev-up.sh`, `initial-setup.sh`,
  `discord-sync.py` (`ThreadSafeRcon`), the seed roller's
  `seed_worker.py`/`warmup_biomes.py`, and the `mc-backup` sidecar's
  save-off/save-on (third-party — **cannot** be migrated, so RCON stays
  enabled regardless).

That last point is decisive for scoping: `itzg/mc-backup` speaks RCON and we
do not control it. **RCON cannot be removed, only supplemented.** Any spike
that promises "get rid of RCON" should be re-framed as "stop using RCON for
the things it is bad at".

### C. An existing Fabric alternative

Nothing credible found. `CraftControl RCON` (CurseForge) supports Fabric but
is an RCON *companion*, not a replacement. Worth one more search before
building anything, but the ecosystem does not appear to have a maintained
Fabric equivalent of Recon.

### D. Stop using the control channel for verification at all

Owner, 2026-07-27: *"I'm not sure if changing RCON now would help, we may
need to just come up with a better validation mechanism."*

This is the strongest option and it is already half-built, by accident.

**Everything that worked today wrote a file.** G2's 72 assertions came from
`/customdim structure-census` dumping JSON to
`config/custom-dimensions/census/`, which `scripts/check-noise-regression.py`
reads and asserts over. RCON's entire role was to say "go" — one short
command, one one-line answer, the shape RCON handles fine. No truncation
because nothing large came back through it. No timeout because nothing slow
ran inside the request. No parsing of chat feedback.

The same pattern already exists in three places, each invented independently
to dodge the same wall: `structure-audit.txt` (380 rows that came back as
one unreadable half-string over RCON), the census dumps, and
`biome_grid.csv` from `sample-biome-grid`. That is not three workarounds, it
is a design that nobody has named yet.

**Name it and finish it:**

- Every diagnostic command answers `<summary> -> <path>` and writes the real
  answer to a known location. Already true for three commands.
- The files are the contract: versioned, diffable, committable as fixtures.
  `scripts/seed/testdata/census/` already pins Java output this way for the
  Python parity gate.
- Checkers live in `scripts/` and run with no server at all
  (`check-noise-regression.py` needs Docker only to *produce* the dumps, not
  to check them). They run in CI against committed fixtures.
- Anything slow becomes "trigger, then wait for the file to appear" —
  which is a job API without building one, and it survives the RCON call
  timing out, because the work is not inside the request.

**Cost:** one command at a time, no new protocol, no new port, no new mod,
no autopause risk. **What it does not give you:** interactive admin
(`/mc restart`, whitelist changes) — but those are short commands and RCON
already does them adequately.

## Recommendation

**Do D. Reconsider B only if D leaves something genuinely undone.**

The earlier draft of this spike recommended B (an in-house Fabric control
mod) on the strength of this owner note: *"We basically cannot use rcon for
anything, and never have been able to. It's trash. Our async locate command
is just a wrapped command to be honest, not really a better alternative but
at least it doesn't crash the server."*

Both halves of that are right, and neither points at the transport. Option A
is per-command whack-a-mole — the async locate proves it: it stopped
`/locate` wedging the server and it still cannot tell you where a village
is, because the command was always the slow part. And option B would buy a
better pipe for output we have already stopped sending through a pipe.

D is the same instinct as B (structured, complete, typed answers) at a
fraction of the cost, and it is the one this session actually validated
under load. **Ship D. Keep RCON as the doorbell, not the delivery van.**

If D is ever exhausted, B remains available, and it would look like this:

- Every command returns a job id immediately; results are fetched, streamed,
  or long-polled. Short commands complete inline on the first response.
- Responses are typed and complete — no 4 KB cliff, no lines concatenated
  without separators, no empty-string-means-either-success-or-timeout.
- Errors are errors: a parse failure, a timeout and an exception are three
  distinguishable outcomes, which RCON cannot express at all.
- Bound to the Docker network only. Never through the Cloudflare tunnel.
- **No polling, no keepalive, no healthcheck of its own** — the autopause
  rule above is the one hard constraint the design must not break.

Then migrate our own consumers off RCON in one pass (list in B above) and
leave RCON enabled purely for `itzg/mc-backup`'s save-off/save-on, which is
third-party and not ours to change. **"Get rid of RCON" is therefore
"stop using RCON for anything we control"** — the sidecar keeps it alive
whatever we do, and that is fine because save-off/save-on is exactly the
kind of short, fire-and-forget command RCON is adequate for.

**Do A only where it is free** — if a command is being written anyway, write
it async. Not as a programme of work.

**Drop Recon itself.** Wrong loader (Bukkit), and its protocol design solves
authentication and encryption, which are not our problems on a private
Docker network. What is worth stealing from it: the job/queue model, the
per-user permissions, and the fact that a JSON body cannot truncate silently.

**Retire the "Chunky will fix locate" assumption** from `mods/AGENTS.md` and
`NOISE-IMPL-LOG.md`. It has now been tested properly and it does not. Under
D it also stops mattering: a two-minute search is a file that appears when
it is ready, not a terminal you sit in front of.

## If D is chosen, the work is

1. Write down the artefact contract in `mods/AGENTS.md`: a diagnostic
   command answers with a summary plus a path, and never with the data.
   Three commands already do it; the rule is what is missing.
2. Fold `check-noise-regression.py` into the repeatable gates and commit a
   fixture set, the way `scripts/seed/testdata/census/` already pins the
   parity fixtures.
3. Audit the remaining `customdim` commands for anything that answers inline
   and could truncate. `list` and `sample-noise` are small enough; anything
   that iterates the registry is not.
4. Leave RCON exactly as it is. It rings the doorbell perfectly well.

## Open questions for the owner

- Which validations are still missing a file to assert over? Portal state
  and dimension lifecycle are the obvious gaps — `portal_links.json` is
  already on disk but nothing asserts over it.
- Should the checkers run in CI against committed fixtures (fast, catches
  logic regressions) as well as against a live server (slow, catches
  integration regressions)? The parity gate already does both.
- If D proves insufficient, is a second in-house mod acceptable maintenance
  surface, or should an endpoint live inside `custom-dimensions`?
