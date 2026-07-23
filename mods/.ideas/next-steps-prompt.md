# Next Steps — Agent Prompt

You're working in `/Users/pip/Projects/minecraft-server-template`. Your
task queue is `mods/.ideas/next-steps.md` — read it first, then read the
spec doc for the top unfinished item, in full, before touching code. Work
one item to completion (implemented AND verified AND documented) before
starting the next.

Read `AGENTS.md` and `mods/AGENTS.md` in full before any code — the
verification loop, mixin conventions, tick-loop threading rules, and git
habits there are mandatory and each exists because of a real incident.
The local consumer for live verification is `~/Projects/elfydd` (shared —
check nobody is mid-test before restarting its containers).

## Ground rules (learned the hard way this week)

- **Build**: `cd mods/custom-dimensions && mise exec -- ./gradlew build`.
  Verify the artefact, not the build: class count, refmap,
  `strings | grep class_` on a mixin from `build/libs/` (never devlibs).
- **Install**: `cp build/libs/customdimensions-*.jar
  ~/Projects/elfydd/data/mods/customdimensions.jar` then STOP mc first,
  copy, start (`docker stop mc && ... && docker start mc`) — copying over
  a running server's jar makes its shutdown hooks throw ZipExceptions.
  Never `./dev up` (it overwrites your dev jar with the released one).
- **Bot recipes**: mods/AGENTS.md §3b. Stand the bot INSIDE the frame and
  `look down` at the bottom frame block; a failed ender-eye click throws
  the eye and stronghold-locates on the main thread (~30s+ stall that
  looks like a hang — check `data get entity Bot Dimension` before
  assuming the server died).
- **Worldgen vs portal/runtime config**: worldgen is creation-time-only
  (level.dat); portal/exits config is boot-re-read. Say which one your
  feature is in its doc comments.
- **Roller parity is non-negotiable**: any generator-affecting change
  lands together with its `scripts/seed/` counterpart, or candidate
  scoring silently lies. Runtime-only features get a `build_profile`
  passthrough check instead.
- **Quality gates**: Java tests run inside `gradlew build` (keep green,
  add tests for pure logic); `./scripts/test-scripts.sh --quick` before
  committing. Conventional commits straight to main; park unfinished work
  as WIP commits, never `git stash`; hold pushes while release.yml runs;
  refresh the major tag after releases
  (`git fetch origin '+refs/tags/v3:refs/tags/v3'`).
- **Releases are Pip's call** — do not cut one unless asked. When asked:
  `gh workflow run release.yml -f version=vX.Y.Z`, never
  `gh release create`.

## When an item completes

1. Live-verify per the item's oracles (RCON + log greps, never chat
   echoes; soak timers through their real windows).
2. Fold schemas/settings into the user docs (`docs/customisation.md`,
   `mods/custom-dimensions/README.md`) and hard-won lessons into the
   AGENTS files.
3. Mark the item done in `next-steps.md`; delete its idea doc once fully
   absorbed (verify coverage first — the portal-concepts retirement in
   commit `086bfed` is the worked example of this).
4. Tell Pip what shipped and what surprised you.
