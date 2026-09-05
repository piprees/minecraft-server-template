# Documentation

Server customisation is covered by [CUSTOMISATION.md](../CUSTOMISATION.md) at the repo root.

Reference material split out of [AGENTS.md](../AGENTS.md) and [README.md](../README.md), so an agent reads it only when the task needs it:

| Topic | File |
| --- | --- |
| Task → file → command lookup | [common-tasks.md](common-tasks.md) |
| Which network calls exist and how each is mitigated | [network-dependencies.md](network-dependencies.md) |
| Web page markup, styles, nav injection | [web-surfaces.md](web-surfaces.md) |
| Update the Minecraft version (~150 server + ~110 client mods) | [minecraft-version-upgrade.md](minecraft-version-upgrade.md) |

In-house mod internals, split out of [mods/AGENTS.md](../mods/AGENTS.md):

| Topic | File |
| --- | --- |
| Design intent: what placement is FOR | [design/README.md](design/README.md) |
| Component tree | [mod-internals/architecture.md](mod-internals/architecture.md) |
| How the portal view should work — read before changing it | [design/immersive-portals.md](design/immersive-portals.md) |
| Portal creation, arrival, carve, break, auras | [mod-internals/portals.md](mod-internals/portals.md) |
| Worldgen self-containment, noise placement, occupancy | [mod-internals/worldgen-structures.md](mod-internals/worldgen-structures.md) |
| Diagnostic artefacts, render-check, seed-rolling internals | [mod-internals/diagnostics.md](mod-internals/diagnostics.md) |

Most procedural documentation lives in the skills system (`.claude/skills/`):

| Topic | Skill |
| --- | --- |
| Setup walkthrough | [`server-provisioning`](../.claude/skills/server-provisioning/SKILL.md) |
| Deployment targets, per-OS notes, home hosting | [`server-provisioning`](../.claude/skills/server-provisioning/SKILL.md) |
| Credentials & API tokens | [`server-provisioning`](../.claude/skills/server-provisioning/SKILL.md) |
| Releasing | [`platform-release-management`](../.claude/skills/platform-release-management/SKILL.md) |
| Branding, mods, packs | [`consumer-customisation`](../.claude/skills/consumer-customisation/SKILL.md) |
| Worldgen tuning | [`worldgen-tuning`](../.claude/skills/worldgen-tuning/SKILL.md) |
| Portals & dimensions | [`custom-dimension-authoring`](../.claude/skills/custom-dimension-authoring/SKILL.md) |
| Web page styling | [`web-surface-branding`](../.claude/skills/web-surface-branding/SKILL.md) |
| Security hardening | [SECURITY.md](../SECURITY.md) + [`server-provisioning`](../.claude/skills/server-provisioning/SKILL.md) |

Problems, traps, and known issues live in [TROUBLESHOOTING.md](../TROUBLESHOOTING.md) — one file, permanent per-entry anchors. For architecture and how-tos see the root [README.md](../README.md); for the agent contract see [AGENTS.md](../AGENTS.md).
