# Open tasks

Everything left to action in this repo. Ordered by consequence, not effort.

Problems already written up with a permanent id live in [`TROUBLESHOOTING.md`](../TROUBLESHOOTING.md) — cite those by id rather than restating them here.

---

## Open

| # | Task | Notes |
| --- | --- | --- |
| 3.1b | **`mods-cache/` history prune.** Gitignored and untracked (v3.11.0). ~1.4 GB of historical blobs remain in `.git/objects/`. Needs `git filter-repo` + force-push — coordinate before running. | Deferred |
| 4.1 | **Immersive Phase 7 tail** — presentation shipped v3.9.1; the End's activation model and the sound half of the rule remain. **Needs a spike**: investigate the End portal activation model (`fill` vs `ignite` schema). | `mods/custom-dimensions/immersive/PHASE-7-PORTAL-IDENTITY.md` |

## Watch list

Not tasks — things that will bite if forgotten. Full detail under their ids.

- **[K1](../TROUBLESHOOTING.md#k1)** — Epic Dungeons loot ids wedge the main thread under c2me. Upstream candidate; the loot table id is the mod's data bug.
- **[K2](../TROUBLESHOOTING.md#k2)** — c2me `TheChunkSystem` CME during heavy multi-dimension chunk activity. Non-fatal, correlates with degraded TPS on boots.
- **[T15](../TROUBLESHOOTING.md#t15)** — any server predating v3.10.1 needs one manual `rm -rf ~/server/data/config/custom-dimensions/overlay`. Delete this entry once every server is past it, or it becomes narration by attrition.
- **Retention change lands on the next backup run.** The `3/1/1` compose defaults now match the documented policy; any server that was silently on `7/4/2` prunes down in one go on its first run after deploy. Expected, but it's a one-way delete.

---

## Completed (v3.11.0, 2026-07-26)

<details>
<summary>16 items actioned — click to expand</summary>

| # | What changed |
| --- | --- |
| 1.1 | `pin-mod-versions.sh` server loop now respects version holds. `c2me-fabric` mechanically protected. |
| 1.2 | `discord-sync.py` role sync switched to ID-based matching (`DISCORD_PLAYER_ROLE_ID`/`DISCORD_ADMIN_ROLE_ID`). |
| 1.3 | `deploy.sh` comments explain the intentional kuma-init asymmetry. |
| 2.1 | `discord-sync.py` docstring + skill doc fixed (bind-mount claim removed). |
| 2.2 | Consumer AGENTS.md client-mod row points at the overlay/modpack merge mechanism. |
| 2.3 | Consumer modpack README example matches `merge-manifest.py`'s `add`/`remove` schema. |
| 2.4 | `commands.json` synced — added ops `shutdown`/`startup`/`reboot`, dev `refresh-config`/`seed-viewer`. |
| 2.5 | Cache Purge permission added to `docs/credentials.md` + `setup.sh` (consistency fix). |
| 3.1 | `mods-cache/` gitignored and untracked (271 files). History prune deferred → 3.1b. |
| 3.2 | Seed image no longer ships `extractors/` (2.6 MB dead weight). |
| 3.3 | `scripts/seed/README.md` research-notes table expanded to all 15 reports. |
| 3.5 | `mod-build.yml` iterates `local-mods.manifest` instead of hardcoding one mod. |
| 3.6 | CONTRIBUTING.md rewritten for consumer-alongside-template workflow + GNU tar note (3.7a). |
| 3.7 | `attributefix:XwbErf6s?` — optional marker moved to line end, stale FIXME deleted. |
| 4.2 | `mods/custom-dimensions/client/` staged with spec, kickoff brief, and README. |
| 4.3 | R3 shipped (`strip-removed-mods.py`). R4 already covered by existing smoke-test `removal` variant. |

Previously closed: **3.4** (lint.yml bundle-manifest gap, fixed v3.10.3).

</details>
