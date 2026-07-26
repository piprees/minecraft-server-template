# Archived phase documents

Shipped and verified. Kept because the *reasoning* in them is load-bearing —
several exist specifically to stop a future change reintroducing a bug that
cost real debugging time. Read the one that owns the code you are touching
before changing it.

| Phase | What it shipped |
|---|---|
| 0 | Config schema + proximity pre-loading (chunk tickets) |
| 1 | Fake-block portal preview, the sightline mask, the `lastSent` invariant |
| 2 | Cross-portal biome audio (`world.playSound`, never game events) |
| 3 | Entity pass-through, swept-path detection, `teleportTo` recreation |
| 4 | Aperture light layer, edge particles, refresh cadence |
| 6 | `aura.subsume` policy and the claims hard veto |
| 8 | Solidity — arrival egress, portals stay broken, no fake block in a body |

**Corrections that outlived their documents** (these are wrong where the
archived text still says otherwise):

- **`F3+A` does NOT clear fake blocks.** It is `WorldRenderer.reload()`, which
  rebuilds meshes from the client's local `ClientWorld` and never re-requests
  chunks. Fake blocks survive it. Use a relog. PHASE-8 built its "decisive
  test" on this and got a misleading answer.
- **`healPortalHole` is gone** (owner decision, 2026-07-25). With
  `NetherPortalProtectionMixin` it made portals indestructible in creative.
  The mixin stays — it compensates for a non-obsidian frame and never resists
  a player.
- **PHASE-6's header said "not started"** for weeks after it shipped in
  v3.7.0. Trust `mods/AGENTS.md` and the code over a phase doc's status line.
- **A phase doc's SPEC can be wrong too, not just its status.** PHASE-9
  specified its boot check as `destinationBorder < sourceBorder × scale` —
  the multiply-on-entry formula, wrong in the same direction the code had
  been. The code (`ArrivalReachability`) was already right. When a document
  and the code disagree about arithmetic, derive the answer from the configs
  and vanilla's own semantics; do not assume either one is the authority
  (2026-07-26).
- **PHASE-9's "58 dimensions can strand a player" table is historical.** It
  was measured under the inverted transform. Against the real config set with
  divide-on-entry, **three** fail, for an unrelated reason (pocket dims at
  scale 1 into a 256 border). See `PLAN.md § Outstanding for the owner`.
- **The mod README documented `/dimension create`, `/dimension delete`,
  `/portal link` and `/portal delete` long after all four stopped existing** —
  and its `/portal link` example still carried `0.125` as the way to write
  "1:8 scaling", which is the single line that caused the scale inversion.
  Corrected 2026-07-26; the only command root is `/customdim`.
