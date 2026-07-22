# Portal Concepts — Implementation Prompt

You're working in `/Users/pip/Projects/minecraft-server-template`. Implement
the three portal behaviours designed in `mods/.ideas/new-portal-concepts.md`
(read it first, in full) in the custom-dimensions Fabric mod, then verify
each one live against the local consumer at `~/Projects/elfydd`.

Read `AGENTS.md` and `mods/AGENTS.md` in full before touching code — the
verification loop, mixin conventions, and tick-loop threading rules there
are mandatory and every one of them exists because of a real incident.

## Scope (in priority order)

1. **Anchor portals** — `portal.anchor` config block: every source portal
   for the dimension lands at one fixed position; per-source target portals
   and `portal_links.json` entries are suppressed; `exit` mode `"origin"` |
   `"bed"` | `"worldSpawn"`. Design details, config shape, and the
   anti-wormhole trade-offs are in the ideas doc — follow it.
2. **Single-use portals** — `portal.singleUse` block: countdown from first
   traversal, then `"destroy"` | `"decay"` | `"partial"` frame break with
   the decay-map defaults from the ideas doc. Countdown must survive
   restarts.
3. **Exit portals** — `exitPortal` block: a mod-built frame at a
   deterministic offset from dimension spawn, registered as a permanent
   zone targeting the overworld; rebuild-if-broken. Config validation rule:
   `singleUse.enabled` (or anchor with suppressed returns) without an exit
   portal logs a boot WARN in the same tone as the fingerprint drift
   warning (never crash, never auto-fix).

Defer the jigsaw exit-shrine structure (option 2 in the ideas doc) — note
it as follow-up, don't build it.

## Key code you'll touch (read each in full first)

- `mods/custom-dimensions/src/main/java/com/customdimensions/portal/PortalHelper.java`
  — zones, `portal_links.json` persistence, UUID origins, teleport targets
- `mods/custom-dimensions/src/main/java/com/customdimensions/mixin/PortalIgnitionMixin.java`
  — ignition + `prewarmTarget` (queues `requestWorldLoad` at ignite)
- `mods/custom-dimensions/src/main/java/com/customdimensions/mixin/ServerWorldMixin.java`
  — per-tick zone validation + player teleports (add the single-use
  countdown here; NEVER mutate the worlds map from a tick mixin — defer
  via END_SERVER_TICK, see MultiverseServer)
- `mods/custom-dimensions/src/main/java/com/customdimensions/config/DimensionConfig.java`
  + `PortalDefinition` — add the new config blocks. Gson shape discipline:
  maps are `Map<String, X>` classes, never bare lists (a list-form
  `structures.shuns` once crash-skipped a whole dimension at boot).
- `mods/custom-dimensions/src/main/java/com/customdimensions/dimension/DimensionFingerprints.java`
  — reference for the warn-don't-act policy and the state-file pattern
  (`server.getRunDirectory().resolve("config")...`)

## Hard-won constraints (violating any of these has caused real incidents)

- Placing NETHER_PORTAL blocks: frames first, portal blocks last with
  `Block.NOTIFY_LISTENERS | Block.FORCE_STATE` — `NOTIFY_ALL` makes
  custom-framed portals self-destruct during placement.
- Arrival placement from the target column's heightmap (`findSurfaceY`,
  chunk force-generated first); never offsets from `getBottomY()`, never
  `World.getTopY` on an unloaded chunk.
- Never create worlds synchronously from command/tick context — queue via
  `requestWorldLoad` (END_SERVER_TICK drain).
- Vanilla resets `PortalCooldown` every tick while an entity stands in a
  portal — return trips need step-out → cooldown-zero → step-in.
- Worldgen/dimension config is creation-time-only (level.dat persistence);
  portal config is NOT — portal blocks re-read config each boot, so these
  features apply to existing dimensions without wipes. Say so in the doc
  comments.

## Build + verify (no exceptions, in this order)

1. `cd mods/custom-dimensions && mise exec -- ./gradlew build` (global Java
   is wrong; mise pins temurin-21).
2. Verify the artefact, not the build: `build/libs/customdimensions-*.jar`
   (never devlibs) — class count sane, refmap present, `strings` on a mixin
   class shows `class_XXXX` intermediary names.
3. Install: `cp build/libs/customdimensions-*.jar ~/Projects/elfydd/data/mods/customdimensions.jar`,
   then `docker restart mc` (NOT `./dev up` — dev up overwrites your dev
   jar with the released bundle jar). Bounded health wait ~5-8 min.
4. Exercise headlessly with Carpet fake players — carpet ships as a
   platform default now (no temp install needed): `carpet commandPlayer
   true`, `player Bot spawn`. Follow the bot recipes in
   `mods/AGENTS.md` §3b. The bot must be positioned in line with a frame
   block for `player Bot use once` to hit it (a ray through the open
   interior clicks nothing).
5. Per-feature oracles, each verified via RCON + log greps, never chat
   echoes:
   - Anchor: two source portals at different overworld positions both land
     the bot at the anchor; no second target portal exists; exit lands per
     mode (set the bot's spawnpoint with `spawnpoint Bot <pos>` to test
     `"bed"`).
   - Single-use: traverse, wait out the real countdown (soak it, don't
     assume), assert frame blocks decayed/destroyed per mode; assert a
     restart mid-countdown resumes it.
   - Exit portal: exists near dimension spawn after creation; break it,
     assert rebuild; traverse it back to the overworld.
   - Regression: an ordinary portal dim (the_wuthering_wisteria,
     mangrove frame + pink petals) still round-trips.
6. Java tests for the pure logic (decay-map resolution, countdown
   persistence shape, validation rule) — the suite runs inside
   `gradlew build`; keep it green.
7. `./scripts/test-scripts.sh --quick` before committing.

## Config + docs

- New blocks documented in the per-dimension config: update
  `mods/README.md` portal section and add one worked example to a pocket
  dim config as a comment-free reference (suggest the_starwell for anchor
  + exit; do NOT enable singleUse on any shipped dim yet).
- The seed roller ignores these blocks — confirm `dimension_profiles.py`
  passes them through untouched (`build_profile` must not choke; add a
  fidelity test if you touch it).
- Conventional commits; do not cut a release — leave that decision to Pip.
