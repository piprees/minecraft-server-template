# Kickoff — Custom Dimensions Client Companion Mod

> Read [`SPEC.md`](SPEC.md) in this directory in full before writing any code.
> It is the staged copy of the canonical spec at
> [`mods/custom-dimensions/immersive/PHASE-5-CLIENT-COMPANION.md`](../immersive/PHASE-5-CLIENT-COMPANION.md)
> and [`mods/custom-dimensions/immersive/PLAN.md`](../immersive/PLAN.md) (the
> decision record for the whole immersive-portals feature — read its
> Architecture Decision section for why a client mod was rejected for the MVP
> and only reconsidered here). Also read [`mods/AGENTS.md`](../../AGENTS.md)
> (mixin conventions, verification loop) and the root
> [`AGENTS.md`](../../../AGENTS.md) (operating contract, safety rules) before
> touching anything.

## The goal

The existing `custom-dimensions` server mod (`mods/custom-dimensions/`, Phases
0–4/6/8/9 shipped) gives portals a server-side "immersive" presentation: you
can see through the frame into the destination dimension, hear ambient sound
leak through, and throw items across. It does all of this **without any
client mod**, using fake block-update packets sent to a vanilla client.

In-game testing (2026-07-25) found a hard ceiling on that approach — a set of
things that are **provably impossible from the server alone**, no matter how
clever the packet trickery gets:

1. **The dimension-change loading screen** (§5a) — `DownloadingTerrainScreen`
   is vanilla client state; no server packet suppresses it, even when the
   destination is already fully loaded.
2. **Portal block transparency** (§5b) — the purple `nether_portal` texture
   and its particles are a client-side render and completely obscure the
   preview behind them. This is called out in SPEC.md as **the single
   highest-value item in this phase**.
3. **Ghost entities visible through the portal** (§5c) — showing mobs/players
   on the far side needs read-only proxy entities with no physics, AI, or
   interaction; a vanilla client has no concept of "entity that exists but
   isn't really here".
4. **Correct lighting for projected blocks** (§5d) — the current approximation
   (fake `Blocks.LIGHT` at the aperture) decays linearly with depth regardless
   of the destination's actual light, so a sunlit meadow and a lit cave look
   the same through the portal.
5. **Biome-dependent colours** (§5e) — water/foliage/grass colour is computed
   client-side from the biome the client believes it's standing in, so
   projected blocks wrongly take the *source* dimension's palette.

There are two more items in SPEC.md worth knowing up front because they
reframe the scope: sub-block-accurate edge clipping (§5g) and going beyond an
8-block preview depth, including the skybox (§5h) both point at the same real
fix — **a second `WorldRenderer` pass rendering the destination to a
texture**, not incremental tuning of the fake-block approach. Read those two
sections before committing to an architecture; they explain why depth/edge
quality is a rendering problem, not a config problem.

This is a **long, high-risk build** (Phase Summary in PLAN.md rates it size
"L", risk "High"). It is deliberately not started yet — you are the first
agent picking it up.

## Constraints (non-negotiable, from SPEC.md's risk register)

- **Sodium/Iris compatibility.** Both are in the shipped client pack
  (`modpack/adventure.mrpack.json`). Any render hook (portal transparency,
  ghost entities, the render-to-texture pass) must be tested against both —
  this was the original reason a client mod was rejected for the MVP, and
  it's still the single biggest risk here.
- **Version lock.** A client mod pins the pack to a client version far harder
  than a server mod pins the server. MC version upgrades are already a "big
  job" (see root `AGENTS.md` → Update Minecraft version) across ~150 server
  mods and ~110 client mods — a required client companion mod adds one more
  hard dependency to that upgrade, and it's a render-hooking one, so expect it
  to be one of the slower mods to catch up on every MC bump.
- **Pack install friction.** The pack ships as a `.mrpack` with packwiz
  auto-update (`modpack/adventure.mrpack.json` → `_clientMods`). Making this
  mod *required* is a pack-wide decision affecting every player, not a
  mod-level one — confirm with the project owner before flipping it from
  optional to required.
- **Backwards compatibility is mandatory, not aspirational.** The server mod
  at `mods/custom-dimensions/` must keep working exactly as it does today for
  players who don't install the companion. SPEC.md §5f is explicit: the
  handshake must be versioned, the client announces itself and its protocol
  version on join, and **if the handshake doesn't happen, the server behaves
  exactly as it does today.** Never build a feature that assumes the client
  mod is present.

## First steps

1. **Set up Fabric client mod scaffolding.** Match the server mod's pins
   exactly unless you have a reason not to (`mods/custom-dimensions/gradle.properties`):
   Minecraft `1.21.1`, Yarn `1.21.1+build.3`, Fabric Loader `0.16.14`, Fabric
   API `0.115.0+1.21.1`, Java 21 (`mods/mise.toml` — `temurin-21`). Decide
   whether this lives as a new `mods/custom-dimensions-client/` Gradle project
   (sibling to the server mod, most consistent with the existing repo layout)
   or a client-side source set inside the existing project — check with the
   project owner if the answer isn't obvious once you've read how
   `mods/custom-dimensions/build.gradle` is structured.
2. **Design the 5f handshake first, before any rendering work.** Everything
   else in this phase depends on the client knowing which blocks/dimensions
   are part of an active projection, and the server knowing whether the
   connected client can be trusted with that data. Concretely:
   - A custom payload channel (Fabric's networking API), versioned from day
     one.
   - Client → server: announce mod presence + protocol version on join.
   - Server → client: per active projection, send the aperture, the projected
     volume, the destination dimension id, and whether it pre-loaded (needed
     for gating the loading-screen suppression in §5a).
   - Prove the negative case first: a vanilla client (or a stale-protocol
     client) connecting must see **zero behaviour change** from what's shipped
     today. This is the fact that makes the whole feature safe to ship
     incrementally.
3. **Pick one hard blocker and ship it end-to-end before touching the next.**
   Portal transparency (§5b) is flagged as the highest-value single item and
   has no dependency on the ghost-entity or lighting work — it's the
   recommended first vertical slice once the handshake exists. Verify it
   against both Sodium and Iris before calling it done.
4. **Read the server-side integration points you'll need to hook into.**
   `mods/AGENTS.md` § Architecture (custom-dimensions) has the full class map;
   the `immersive/` package (`ProjectionVolume`, `PlayerProjectionState`,
   `ImmersiveProjector`) is what currently computes and sends the fake-block
   packets you'll be extending or replacing on the client side. Do not modify
   traversal logic (ignition, zone validation, teleport) — this phase is
   presentation-only, same rule as every other immersive phase (PLAN.md
   "For agents" section).
5. **Plan verification before writing render code.** SPEC.md's closing
   section is blunt about this: Phases 0–4 were verified headlessly (RCON +
   Carpet bot + log greps); **this phase cannot be**. Every item is a
   render-path change, so budget for in-game verification by a human as the
   primary loop, not a fallback, and expect screenshot-driven iteration.

## Where things live

- Server mod (do not break): `mods/custom-dimensions/`
- Immersive presentation layer you're extending:
  `mods/custom-dimensions/src/main/java/**/immersive/` (see `mods/AGENTS.md`
  for the full architecture diagram)
- Phase docs and the decision record: `mods/custom-dimensions/immersive/`
- Client pack manifest (if/when a client mod entry is needed):
  `modpack/adventure.mrpack.json`
- This staging directory: `mods/custom-dimensions/client/` — move your new
  Gradle project's actual code wherever you decide in step 1; this directory
  is docs-only.
