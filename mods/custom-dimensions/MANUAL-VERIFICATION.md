# Manual verification checklist — custom-dimensions

> Companion to `TEST-COVERAGE-AUDIT.md`. That document says what should be
> automated. **This one is the short list of things that genuinely cannot
> be**, so they get checked deliberately rather than discovered by a player.
>
> The rule: if a check appears here, there must be a reason it cannot be a
> unit test or a Carpet-bot assertion. "It would be fiddly" is not a reason.
> Anything that moves from here into the suite should be deleted from here.

## How to run these

Local only, against `~/Projects/elfydd`. Never `./dev up` to test a build —
it overwrites your jar with the bundle's. Install and restart:

```bash
cp mods/custom-dimensions/build/libs/customdimensions-*.jar \
   ~/Projects/elfydd/data/mods/customdimensions.jar
# re-patch c2me useDensityFunctionCompiler BEFORE every restart (see dev-up.sh)
docker stop -t 60 mc && docker start mc
```

**Mod DEBUG logging is an env var, not a file patch.** Set
`CUSTOMDIM_LOG_LEVEL='debug'` in the consumer `.env` and restart mc;
`log4j2-adventure.xml` reads it through log4j2's Environment Lookup. Do NOT
hand-patch that file inside the `stack-config` volume — every seed run
reverts it (AGENTS.md trap #12), so the diagnostics disappear on the next
`./dev up` without a word.

**`./dev up` overwrites your locally-built mod jar** with the bundle's, and
**recreating mc outside `./dev up` breaks the mod set** — a
`docker compose up --force-recreate --no-deps mc` skips the seed and booted
with 4 mods instead of 287 (hit 2026-07-25). Use `docker stop`/`docker start`
to keep both, and `./dev up` only when you want the bundle's jars back.

Check for ghosts before believing any visual report — orphaned fake blocks
from a previous session look identical to a live masking failure:

```bash
docker exec mc sh -c 'grep -E "immersive: .*(activated|cleared)" /data/logs/latest.log | tail'
docker inspect mc --format '{{.State.StartedAt}}'
```

**F3+A does NOT clear fake blocks.** It is `WorldRenderer.reload()`, which
rebuilds render meshes from the client's local `ClientWorld` — it does not
re-request chunks. Fake blocks arrive via `BlockUpdateS2CPacket`, land in
`ClientWorld`, and survive it. Use a **relog**, or move beyond view distance
and back. (`PHASE-8-SOLIDITY.md` and `PLAN.md § Ghosts` both assert
otherwise; they are wrong and cost a diagnosis this session.)

The authoritative discriminator for "is this block real?" is never the
client:

```bash
docker exec -i mc rcon-cli 'execute in <dim> if block <x> <y> <z> minecraft:air'
```

## The list

### V1 — Visual quality of the projection
**Why manual:** no automated oracle for "does this look right".
- Terrain through the frame reads as the destination, not as noise.
- Parallax shifts naturally as you strafe; nothing swims or pops.
- Edges of the aperture are clean — no geometry visible outside the frame at
  grazing angles, from either side, standing and crouching.
- Particles read as dust drifting out, not as fog obscuring the view.

### V2 — Audio quality
**Why manual:** perceptual.
- Biome ambience from the far side is audible near the portal and fades with
  distance. Not louder than the source dimension's own ambience.

### V3 — Client frame-time near a portal
**Why manual:** client-side cost is not observable from the server.
- Walk past a portal at speed, then circle it, then sprint away. Watch F3
  frame time. A multi-second stall is a defect (observed 2026-07-25; root
  cause was an unbudgeted ~1000-packet pass, fixed by `ProjectionBudget`).
- Relog in range: the projection must reappear, not double-send.
- *Now automated:* the per-pass packet ceiling and the side-flip carry-over
  are asserted server-side from the `sightline mask` DEBUG line — no pass may
  exceed the ceiling, and `deferred` must reach 0. What remains manual is
  only whether the CLIENT still feels smooth.

### V4 — Portal presentation matches the destination
**Why partly manual:** colour perception. The lookup itself is automatable
and currently untested (audit row 9).
- An arrival in an ember dimension whose exit leads to the overworld must
  **not** glow ember. It should present as the place it takes you.

### V5 — Aura feel
**Why manual:** "does this feel like a leak rather than vandalism".
- Spread is legible as the far side bleeding through, not a blast radius.
- A compact/hard dimension should feel like it is fighting you; an
  unassuming one should barely register.
- *Automatable and covered:* what an aura may convert (`AuraPolicy`), claim
  veto, budget arithmetic.

### V6 — First-arrival experience in a new dimension
**Why manual:** integration of many systems under real worldgen.
- Arrive: you can move immediately, in every direction you would expect.
- The way home is visible and obviously the way home.
- **Regression watch:** arriving encased in terrain. Now covered by
  `PortalSiteTest` and `PortalSite.ensureEgress`, but worldgen is the one
  input a unit test cannot enumerate — spot-check in a ceilinged
  (nether-type) dimension specifically, which is where it failed.

## Known-wrong assumptions (do not re-derive these)

| Assumption | Reality |
|---|---|
| F3+A clears fake blocks | It does not — see above. Relog. |
| A server `if block` probe proves what the player sees | It proves what is REAL. Divergence is the whole point of the projection; you need both halves. |
| An RCON `setblock` reproduces a player break | It does not. Player breaks fire `PlayerBlockBreakEvents`; `setblock` does not, so break-triggered logic is invisible to it. |
| "Arrival portal broken" absent from the log = the hook is broken | It only logs for REGISTERED positions. Breaking nearby terrain logs nothing and is correct. |
| Budget-exhausted aura is still writing | `budgetSpent >= budget` makes `tick()` skip the site entirely. Check the record before blaming the aura. |
