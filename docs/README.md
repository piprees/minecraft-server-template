# Documentation

Server customisation is covered by [CUSTOMISATION.md](../CUSTOMISATION.md) at the repo root.

Reference material split out of [AGENTS.md](../AGENTS.md), so an agent reads it only when the task needs it:

| Topic | File |
| --- | --- |
| Task → file → command lookup | [common-tasks.md](common-tasks.md) |
| Which network calls exist and how each is mitigated | [network-dependencies.md](network-dependencies.md) |
| Web page markup, styles, nav injection | [web-surfaces.md](web-surfaces.md) |

Most procedural documentation lives in the skills system (`.claude/skills/`):

| Topic | Skill |
| --- | --- |
| Setup walkthrough | [`server-provisioning`](../.claude/skills/server-provisioning/SKILL.md) |
| Credentials & API tokens | [`server-provisioning`](../.claude/skills/server-provisioning/SKILL.md) |
| Releasing | [`platform-release-management`](../.claude/skills/platform-release-management/SKILL.md) |
| Branding, mods, packs | [`consumer-customisation`](../.claude/skills/consumer-customisation/SKILL.md) |
| Worldgen tuning | [`worldgen-tuning`](../.claude/skills/worldgen-tuning/SKILL.md) |
| Portals & dimensions | [`custom-dimension-authoring`](../.claude/skills/custom-dimension-authoring/SKILL.md) |
| Web page styling | [`web-surface-branding`](../.claude/skills/web-surface-branding/SKILL.md) |
| Security hardening | [SECURITY.md](../SECURITY.md) + [`server-provisioning`](../.claude/skills/server-provisioning/SKILL.md) |

Problems, traps, and known issues live in [TROUBLESHOOTING.md](../TROUBLESHOOTING.md) — one file, permanent per-entry anchors. For architecture and how-tos see the root [README.md](../README.md); for the agent contract see [AGENTS.md](../AGENTS.md).
