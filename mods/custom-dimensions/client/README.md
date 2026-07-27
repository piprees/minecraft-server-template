# client/

Staging area for the Phase 5 client companion mod — the not-yet-started
counterpart to the server-side `custom-dimensions` mod that would add a real
Fabric client mod to render portal transparency, ghost entities, correct
lighting/biome colour, and suppress the dimension-change loading screen (see
[`SPEC.md`](SPEC.md)).

No code lives here yet. This directory exists so an agent can pick up the
work in one place:

- **[`SPEC.md`](SPEC.md)** — the feature spec: what's impossible server-side
  and why. Staged from the canonical copy at

- **[`KICKOFF.md`](KICKOFF.md)** — the brief for an agent starting
  implementation: goal, constraints, first steps.

The server mod this work extends lives at `mods/custom-dimensions/` (see
[`mods/AGENTS.md`](../../AGENTS.md) for its architecture and conventions).
