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
