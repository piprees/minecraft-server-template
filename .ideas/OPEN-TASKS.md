# Open tasks

Everything left to action in this repo, as of 2026-07-26. Ordered by consequence, not effort.

Problems already written up with a permanent id live in [`TROUBLESHOOTING.md`](../TROUBLESHOOTING.md) — cite those by id rather than restating them here.

---

## 1. Correctness — silent failures in shipped behaviour

| # | Task | Evidence |
| --- | --- | --- |
| 1.1 | ~~**Version holds don't bind the server mod list.**~~ Server loop now reads `_clientMods.holds` from the manifest before re-pinning and skips held slugs, matching what the client loop already did. `c2me-fabric` is now mechanically protected. Changed: `scripts/pin-mod-versions.sh` (ships in bundle — needs a release to reach consumers). | `FINDINGS.md` §3 |
| 1.2 | ~~**Discord role sync matches by role NAME, not id.**~~ Role sync now uses `DISCORD_PLAYER_ROLE_ID` / `DISCORD_ADMIN_ROLE_ID` (integer IDs) for matching. Name strings kept for display messages only. Env vars added to docker-compose.yml's discord-sync environment block. Logs a warning at startup if either ID is unset. Changed: `scripts/discord-sync.py`, `docker-compose.yml`. Ships as an image change → needs a release. | `FINDINGS.md` §5 |
| 1.3 | ~~**`deploy.sh` comments claim a sidecar list is in sync with `infra-deploy.sh` when it isn't.**~~ Both comments (header + inline at the sidecar list) now explain WHY kuma-init is intentionally omitted: CI and `./ops update` refresh it separately, so only a bare manual `deploy.sh` over SSH skips it. Changed: `scripts/deploy.sh`. | `FINDINGS.md` §6 |

## 2. Documentation that describes a different mechanism than the code

| # | Task | Evidence |
| --- | --- | --- |
| 2.1 | ~~**`discord-sync.py`'s own docstring says it is bind-mounted read-only.**~~ Docstring fixed earlier today. Follow-on: discord-integration-ops skill doc reworded to state the mechanism plainly without referencing the old bug. | `FINDINGS.md` §4 |
| 2.2 | ~~**`examples/consumer/AGENTS.md` says client mods can't be added in a consumer repo.**~~ Row now points at `overlay/modpack/manifest.json` with the correct `add`/`remove` keys. Notes that genuinely new slugs still need a template-repo PR. | `FINDINGS.md` §7.6 |
| 2.3 | ~~**`examples/consumer/overlay/modpack/README.md`'s worked example is the wrong shape.**~~ Both examples rewritten to match `merge-manifest.py`'s actual schema: `add.required`/`add.optional` (string arrays), `remove` (slug array), `name`/`versionId` (scalar overrides only). | `FINDINGS.md` §7.5 |
| 2.4 | ~~**`examples/consumer/commands.json` is out of sync with the dispatchers.**~~ Added missing ops (`shutdown`, `startup`, `reboot`) and dev (`refresh-config`, `seed-viewer`) entries. | `FINDINGS.md` §7.8 |
| 2.5 | ~~**`.env.example` lists a 6th Cloudflare token permission.**~~ Cache Purge permission is intentional. Fixed the inconsistency the other way: added the 6th permission to `docs/credentials.md` and `setup.sh` so all three agree. | `FINDINGS.md` §7.10 |

## 3. Repo hygiene

| # | Task | Notes |
| --- | --- | --- |
| 3.1 | ~~**`mods-cache/` is 719 MB in git history, permanently.**~~ Gitignored and untracked (271 files). Working tree copy stays for `sync-mods.sh`; git no longer grows with each mod update. History prune via `git filter-repo` deferred (needs force-push coordination). | Decision, not a bug |
| 3.2 | ~~**`config/custom-dimensions/extractors/` (2.6 MB) ships to every consumer.**~~ Confirmed the mod never reads it (zero grep hits in source). Dockerfile COPY split into `settings.json` + `dimensions/` only — extractors/ no longer reaches the seed image. Needs a release. | Verified: mod never reads it |
| 3.3 | ~~**`scripts/seed/spike/`** — fifteen R&D reports (not six).~~ `scripts/seed/README.md` research-notes table updated to list all 15 reports (00–14). | Low |
| 3.4 | ~~`lint.yml`'s bundle-manifest coverage gap~~ — **fixed in v3.10.3.** It had a live victim: `pin-mod-versions.sh` and `modrinth-api.py` were referenced by `dev` alone, never made the MANIFEST, and `./dev pin` died on a missing file for every consumer while CI stayed green. The job now resolves every `$STACK_SCRIPTS/...` exec in `dev` and matches `.py` as well as `.sh`. | — |
| 3.5 | ~~**`mod-build.yml` hardcodes `mods/custom-dimensions`.**~~ Now iterates `mods/local-mods.manifest` (same as `release.yml`), using the manifest's jar name, project dir, and refmap fields. A second in-house mod gets PR CI automatically. | `FINDINGS.md` §8.4 |
| 3.6 | ~~**`CONTRIBUTING.md` tells platform contributors to run `./scripts/dev-up.sh` directly.**~~ Rewritten to recommend a consumer-repo-alongside-template setup. Added GNU tar note (3.7a). | `FINDINGS.md` §8.2 |
| 3.7a | ~~**`build-stack-bundle.sh` warns `GNU tar not available` on macOS.**~~ Documented in CONTRIBUTING.md with the `brew install gnu-tar` fix. | Low |
| 3.7 | ~~**`config/modrinth-mods.txt`: `attributefix?:XwbErf6s` has a misplaced `?` AND a stale comment.**~~ Fixed to `attributefix:XwbErf6s?` (optional marker at line end). Deleted the wrong FIXME comment. Also confirmed `resolve-mods.py` uses the same `endswith("?")` check — both scripts now parse it correctly. | `FINDINGS.md` §8.1 + verified |

## 4. Feature work

| # | Task | Notes |
| --- | --- | --- |
| 4.1 | **Immersive Phase 7 tail** — presentation shipped v3.9.1; the End's activation model and the sound half of the rule remain. **Needs a spike**: investigate the End portal activation model (`fill` vs `ignite` schema). | `mods/custom-dimensions/immersive/PHASE-7-PORTAL-IDENTITY.md` |
| 4.2 | ~~**Immersive Phase 5 — client companion mod.**~~ Documentation staged in `mods/custom-dimensions/client/`: SPEC.md (full spec, canonical copy stays in `immersive/`), KICKOFF.md (agent brief with constraints, first steps, and vertical-slice plan), README.md (orientation). Ready for agent-driven implementation. | `mods/custom-dimensions/client/KICKOFF.md` |
| 4.3 | ~~**Client-pack parity gap.**~~ R1 (lint) + R2 (README checklist) shipped `6a48a44`. R3: `strip-removed-mods.py` added to the modpack-builder — `overlay/mods-remove.txt` slugs are now auto-stripped from `_clientMods.required`/`.optional` before the pack build. R4: already covered by the existing `removal` matrix variant in `smoke-test.yml`. Audit doc status updated. | [`client-pack-parity-audit.md`](client-pack-parity-audit.md) |

## Decision record — 2026-07-26

Every open item verified against the current code and decided. Corrections to the original claims are inline.

| # | Decision | Rationale |
| --- | --- | --- |
| 1.1 | **Do now** | Add holds check to `pin-mod-versions.sh` server loop. `c2me-fabric` is server-only; its `_clientMods.holds` entry enforces nothing. Ships in bundle → needs a release. |
| 1.2 | **Do now** | Switch `discord-sync.py` to ID-based role matching using the existing `DISCORD_ADMIN_ROLE_ID`/`DISCORD_PLAYER_ROLE_ID` env vars. Eliminates silent-break-on-rename. Ships as image → needs a release. |
| 1.3 | **Do now** | Fix the stale "keep in sync" comments in `deploy.sh`. The kuma-init asymmetry is intentional and compensated in 3 of 4 paths — document why, don't "fix" to match. |
| 2.1 | **Do now** | Docstring fixed today. Follow-on: `.claude/skills/discord-integration-ops/SKILL.md` still narrates the old bug present-tense — update to past tense. |
| 2.2 | **Do now** | Fix `examples/consumer/AGENTS.md` client-mod row. `overlay/modpack/manifest.json` is a real merge mechanism — the "not here" claim is wrong. |
| 2.3 | **Do now** | Fix `examples/consumer/overlay/modpack/README.md` worked example. Two bugs: wrong top-level key (`_clientMods` → `add`) and wrong item shape (objects → strings). |
| 2.4 | **Do now** | Add missing commands to `examples/consumer/commands.json`: ops `shutdown`/`startup`/`reboot`, dev `refresh-config`/`seed-viewer`. |
| 2.5 | **Do now** (fix the inconsistency the other way) | Cache Purge permission is intentional and useful. Fix `docs/credentials.md` and `setup.sh` to list 6 permissions matching `.env.example`, not remove it. |
| 3.1 | **Do now**: gitignore + untrack. **Do later**: `git filter-repo` to prune history (force-push, coordinate first). | `mods-cache/` is still tracked and ungitignored — worse than stated. The cache earns its working-tree presence (`sync-mods.sh`); git tracking does not. Each mod update adds new blobs permanently. `sync-mods.sh` rebuilds the cache from Modrinth CDN via `modrinth-resolve-cache.json` (committed, ~80KB). |
| 3.2 | **Do now** | `config/custom-dimensions/extractors/` (2.6 MB) confirmed dead weight: the mod never reads it (zero grep hits in source). Exclude from the Dockerfile COPY. Needs a release. |
| 3.3 | **Do now** | [CORRECTION] 15 reports now (not 6); `scripts/seed/README.md` lists only 6. Update the README table. |
| 3.5 | **Do now** | Make `mod-build.yml` iterate `mods/local-mods.manifest` instead of hardcoding `mods/custom-dimensions`. Prevents a latent CI gap when a second mod is added. |
| 3.6 | **Do now** | Rewrite `CONTRIBUTING.md` to recommend a consumer-repo-alongside-template setup (like elfydd) instead of bare `./scripts/dev-up.sh`. The current instruction resolves `CONSUMER_DIR` to a nonsense path. |
| 3.7a | **Do now** | Add `brew install gnu-tar` note to `CONTRIBUTING.md`. |
| 3.7 | **Do now** | Fix to `attributefix:XwbErf6s?` (optional at line end), delete the stale `# FIXME: no 1.21.x build` — the pinned version IS a real 1.21.1 build. [CORRECTION] Worse than described: `pin-mod-versions.sh` extracts `attributefix?` as the slug (invalid on Modrinth), creating a self-perpetuating false negative. |
| 4.1 | **Needs a spike** | Investigate the End portal activation model (`fill` vs `ignite`). Design decisions needed before code. |
| 4.2 | **Do now** (prep only) | Create `mods/custom-dimensions/client/` with the Phase 5 docs and a kickoff prompt for agent-driven implementation. No mod code yet — just documentation staging. |
| 4.3 | **Do now** (R3 + R4) | [CORRECTION] R1 (lint warning) and R2 (README checklist) already shipped in commit `6a48a44`. R3: implement auto-filter in `build-modpack.sh` to consume `overlay/mods-remove.txt`. R4: one additional smoke-test job that boots with a sacrificial mod in `overlay/mods-remove.txt` and verifies the boot succeeds without it (~8 min, single run, not a per-mod matrix). Update the audit doc status. |

## 5. Watch list

Not tasks — things that will bite if forgotten. Full detail under their ids.

- **[K1](../TROUBLESHOOTING.md#k1)** — Epic Dungeons loot ids wedge the main thread under c2me. Upstream candidate; the loot table id is the mod's data bug.
- **[K2](../TROUBLESHOOTING.md#k2)** — c2me `TheChunkSystem` CME during heavy multi-dimension chunk activity. Non-fatal, correlates with degraded TPS on boots.
- **[T15](../TROUBLESHOOTING.md#t15)** — any server predating v3.10.1 needs one manual `rm -rf ~/server/data/config/custom-dimensions/overlay`. Delete this entry once every server is past it, or it becomes narration by attrition.
- **Retention change lands on the next backup run.** The `3/1/1` compose defaults now match the documented policy; any server that was silently on `7/4/2` prunes down in one go on its first run after deploy. Expected, but it's a one-way delete.
