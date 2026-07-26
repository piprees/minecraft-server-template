# Open tasks

Everything left to action in this repo, as of 2026-07-26. Ordered by consequence, not effort.

Problems already written up with a permanent id live in [`TROUBLESHOOTING.md`](../TROUBLESHOOTING.md) — cite those by id rather than restating them here.

---

## 1. Correctness — silent failures in shipped behaviour

| # | Task | Evidence |
| --- | --- | --- |
| 1.1 | **Version holds don't bind the server mod list.** `pin-mod-versions.sh` reads `_clientMods.holds` inside its client loop only; the loop that rewrites `config/modrinth-mods.txt` has no holds check. `c2me-fabric` is held because a newer alpha wedges fresh-world creation, is server-only, and is therefore protected by nothing but human review of the Monday PR. Fix: have the server loop consult the same map, or move holds to a top-level key both loops read. | `FINDINGS.md` §3 |
| 1.2 | **Discord role sync matches by role NAME, not id.** `PLAYER_ROLE = "Player"` / `ADMIN_ROLE = "Admin"` are literal case-sensitive strings; `DISCORD_ADMIN_ROLE_ID`/`DISCORD_PLAYER_ROLE_ID` are used only for `<@&id>` mentions. Renaming either Discord role silently stops whitelist and op sync, with no error. The variable names actively imply the opposite mechanism. | `FINDINGS.md` §5 |
| 1.3 | **`deploy.sh` comments claim a sidecar list is in sync with `infra-deploy.sh` when it isn't**, and a bare manual `deploy.sh` over SSH is the one path that never refreshes `kuma-init`. Fix the comments; decide whether the manual path should refresh it too. | `FINDINGS.md` §6 |

## 2. Documentation that describes a different mechanism than the code

| # | Task | Evidence |
| --- | --- | --- |
| 2.1 | **`discord-sync.py`'s own docstring says it is bind-mounted read-only.** It is `COPY`d into the image. A force-recreate picks up nothing; shipping a bot change needs a new image → release → full deploy. Docstring corrected 2026-07-26, but check nothing else repeats the claim. | `FINDINGS.md` §4 |
| 2.2 | **`examples/consumer/AGENTS.md` says client mods can't be added in a consumer repo.** `overlay/modpack/manifest.json` is a real merge mechanism (`entrypoint.sh` → `merge-manifest.py`) supporting `add.required`, `add.optional`, `remove`. | `FINDINGS.md` §7.6 |
| 2.3 | **`examples/consumer/overlay/modpack/README.md`'s worked example is the wrong shape.** It shows `{"_clientMods": {...}}`; `merge-manifest.py` reads `{"add": {"required": ["slug:versionId"]}}`. Copying the shipped example produces a no-op. | `FINDINGS.md` §7.5 |
| 2.4 | **`examples/consumer/commands.json` is out of sync with the dispatchers.** Missing ops: `shutdown`, `startup`, `reboot`. Missing dev: `refresh-config`, `seed-viewer`. | `FINDINGS.md` §7.8 |
| 2.5 | **`.env.example` lists a 6th Cloudflare token permission** (`Zone / Cache Purge : Purge`) that neither `setup.sh` nor `docs/credentials.md` mentions, and no `/purge_cache` call exists. Either the permission is unnecessary or the other two docs are incomplete. | `FINDINGS.md` §7.10 |

## 3. Repo hygiene

| # | Task | Notes |
| --- | --- | --- |
| 3.1 | **`mods-cache/` is 719 MB in git history, permanently.** Pruning HEAD doesn't reclaim it; that needs `git filter-repo`/BFG and a force-push. Now that it's wired into `sync-mods.sh` it earns its place in the working tree — the question is only whether the history cost is acceptable. Consumers are unaffected (they copy `examples/consumer/`, they don't clone). | Decision, not a bug |
| 3.2 | **`config/custom-dimensions/extractors/` (2.6 MB) ships to every consumer** via the seed image `COPY`, but `dev-up.sh` explicitly excludes it from the `data/config` sync — it's authoring data, not runtime config. Probably shouldn't be in the seed image at all. | Unverified: check whether the mod reads it |
| 3.3 | **`scripts/seed/spike/`** — six R&D reports from the seed-roller spike phase. Archived research, still referenced from `scripts/seed/README.md`. Keep or move under a clearer `archive/` marker. | Low |
| 3.4 | ~~`lint.yml`'s bundle-manifest coverage gap~~ — **fixed in v3.10.3.** It had a live victim: `pin-mod-versions.sh` and `modrinth-api.py` were referenced by `dev` alone, never made the MANIFEST, and `./dev pin` died on a missing file for every consumer while CI stayed green. The job now resolves every `$STACK_SCRIPTS/...` exec in `dev` and matches `.py` as well as `.sh`. | — |
| 3.5 | **`mod-build.yml` hardcodes `mods/custom-dimensions`** while `release.yml` iterates `mods/local-mods.manifest`. A second in-house mod would build in release but not in PR CI. | `FINDINGS.md` §8.4 |
| 3.6 | **`CONTRIBUTING.md` tells platform contributors to run `./scripts/dev-up.sh` directly**, but that script's path math assumes bundle nesting and resolves `CONSUMER_DIR` to `/Users` from a plain checkout. Either document `CONSUMER_DIR="$(pwd)"` or point at `docker compose --profile local up -d`. | `FINDINGS.md` §8.2 |
| 3.7a | **`build-stack-bundle.sh` warns `GNU tar not available` on macOS** — a locally-built bundle won't byte-match a CI one. Harmless (CI does the real build on Linux) but worth knowing before anyone diffs them. Fix: `brew install gnu-tar`. | Low |
| 3.7 | **`config/modrinth-mods.txt` has one malformed optional marker**: `attributefix?:XwbErf6s` — the `?` is before the colon, so the re-pin script's optional detection doesn't recognise it. Harmless today (resolution is by immutable version id) but not a pattern to copy. | `FINDINGS.md` §8.1 |

## 4. Feature work

| # | Task | Notes |
| --- | --- | --- |
| 4.1 | **Immersive Phase 7 tail** — presentation shipped v3.9.1; the End's activation model and the sound half of the rule remain. | `mods/custom-dimensions/immersive/PHASE-7-PORTAL-IDENTITY.md` |
| 4.2 | **Immersive Phase 5 — client companion mod.** Not started, deliberately. The doc enumerates exactly what a server-side approach provably cannot do (the dimension-change loading screen, portal transparency, ghost entities, real lighting/biome colour). The bar for taking on a client mod is high and the doc says so. | `mods/custom-dimensions/immersive/PHASE-5-CLIENT-COMPANION.md` |
| 4.3 | **Client-pack parity gap.** 2026-07-24 audit, status "gap confirmed", recommendations unactioned. Consumers fork the client manifest entirely and there is no merge, diff, or sync mechanism between the server overlay and the client manifest. | [`client-pack-parity-audit.md`](client-pack-parity-audit.md) |

## 5. Watch list

Not tasks — things that will bite if forgotten. Full detail under their ids.

- **[K1](../TROUBLESHOOTING.md#k1)** — Epic Dungeons loot ids wedge the main thread under c2me. Upstream candidate; the loot table id is the mod's data bug.
- **[K2](../TROUBLESHOOTING.md#k2)** — c2me `TheChunkSystem` CME during heavy multi-dimension chunk activity. Non-fatal, correlates with degraded TPS on boots.
- **[T15](../TROUBLESHOOTING.md#t15)** — any server predating v3.10.1 needs one manual `rm -rf ~/server/data/config/custom-dimensions/overlay`. Delete this entry once every server is past it, or it becomes narration by attrition.
- **Retention change lands on the next backup run.** The `3/1/1` compose defaults now match the documented policy; any server that was silently on `7/4/2` prunes down in one go on its first run after deploy. Expected, but it's a one-way delete.
