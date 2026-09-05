# Design

Intent and direction for the worldgen, structure-placement and portal work.
These say what the system is FOR; `docs/mod-internals/` says what it currently
does, and `TROUBLESHOOTING.md` carries the traps.

| Document | What it covers |
| --- | --- |
| [structure-placement-principles.md](structure-placement-principles.md) | The specification. Anything contradicting it is a defect, not a design |
| [biome-placement.md](biome-placement.md) | What a dimension's biome list is for: encounterable, not merely present and not equal |
| [noise-placement.md](noise-placement.md) | How placement should decide where and what: value slices over a binary threshold, empty as a valid outcome, the strength of each author control |
| [structure-placement-plan.md](structure-placement-plan.md) | The staged plan and what each step measured |
| [danger-field.md](danger-field.md) | Follow-up, unscheduled — depends on the occupancy table |

## Portals

| Document | What it covers |
| --- | --- |
| [immersive-portals.md](immersive-portals.md) | **The single portal design document.** A portal is a doorway in a wall of reality: the geometry, the lighting, what must pass through, the architecture and feed, and what the stack must survive. Every portal decision is judged against it |
