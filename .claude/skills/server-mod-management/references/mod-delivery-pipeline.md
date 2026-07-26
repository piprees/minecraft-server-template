---
title: Mod Delivery Pipeline
description: How a pinned mod actually gets from a modrinth-mods.txt line onto disk in data/mods/ — seed merge, resolve-mods.py, sync-mods.sh, and deploy.sh's prune step, per environment
tags: [seed, resolve-mods, sync-mods, deploy, offline-boot, mods-manifest]
---

# Mod Delivery Pipeline

The default assumption — "the game server downloads its mods when it boots" — is wrong for this platform, and assuming otherwise is exactly what produces a Modrinth `429 Too Many Requests` crash-loop the moment a mod list changes. Nothing in the pipeline below calls the Modrinth API at `mc` boot time.

## Stage 1: merge (defaults-seed container, `docker/defaults-seed/seed.sh`)

Runs once per `compose up`/deploy, before `mc` starts:

1. Reads `config/modrinth-mods.txt` (baked into the `defaults-seed` image at `/defaults/modrinth-mods.txt`).
2. Removes every slug present in `/overlay/mods-remove.txt` (warns to stderr if a slug in that file doesn't match any default — doesn't fail the boot).
3. Overlays `/overlay/mods-extra.txt`: any slug already in the merged list gets its line **replaced** by the overlay's version (so a consumer can re-pin or reconfigure a platform default by re-declaring it in `mods-extra.txt`); anything new is appended in the order it appears in the overlay file.
4. Writes the merged list to `/out/mods/modrinth-mods.txt` (the `stack-mods` Docker volume) and logs a one-line summary: `seed: <N> defaults, <removed> removed, <added> added, sha256=<hash>`.
5. Immediately invokes `resolve-mods.py` on that merged file — this is stage 2, same container, same run.

Config files follow a separate path in the same container: `rsync -a --delete /defaults/config/ /out/config/`, then the overlay's `config/` rsynced on top (with `custom-dimensions/` routed to an `overlay/` subdirectory instead of overwriting platform dimension JSONs directly — that mod does its own overrides/full-replace/disable merge logic).

## Stage 2: resolve (`docker/defaults-seed/resolve-mods.py`, same container)

For every `slug:versionId` in the merged list:

- Looks up a cache first: a repo-committed `config/modrinth-resolve-cache.json` (baked into the image — known pins resolve with **zero** API calls, survives a full Modrinth outage) overlaid by a runtime cache persisted in the `stack-mods` volume (`.resolve-cache.json`).
- On a cache miss, calls `GET /v2/version/{versionId}` (paced 0.35s apart), takes the `primary` file's URL + filename, and writes the result into the cache. Version IDs are immutable on Modrinth, so **this cache never goes stale** — a warm cache means the *entire* merged list resolves with zero API calls.
- 429 responses honour `Retry-After` (or an exponential backoff starting at 2s, capped at 60s) across up to 6 attempts. 5xx responses get the same backoff treatment (added 2026-07-17 after Modrinth 5xx flapping killed five consecutive CI runs).
- A `datapack:` prefix routes the URL to the datapacks output list instead of mods. A trailing `?` marks the entry optional: if optional and unresolvable, it's skipped with a stderr warning and the boot continues. If **required** and unresolvable, its slug is collected as a failure — after all entries are processed, any failures cause the script (and therefore the seed container, and therefore the whole boot) to exit 1.

Outputs, written into the `stack-mods` volume:

| File | Consumed by |
| --- | --- |
| `mods-urls.txt` | `mc` container's `MODS_FILE` env var |
| `datapacks-urls.txt` | `mc` container's `DATAPACKS_FILE` env var |
| `mods-manifest.txt` | The stale-jar prune in `deploy.sh`/`dev-up.sh` (expected filenames) |

## Stage 3: fetch what's missing (`scripts/sync-mods.sh`, host-side)

Runs **between** the seed container finishing and `mc` starting — not inside either container. Reads `mods-urls.txt`/`datapacks-urls.txt` straight from the `stack-mods` volume (one throwaway `alpine` container mount, avoiding a second spin-up), then for each URL: percent-decodes the filename (`%2B` → `+`, `%20` → space — itzg does the same decoding, and a mismatch here means every boot re-downloads files under the wrong name), skips it if already present in `data/mods/` (or `data/world/datapacks/`), otherwise downloads it (`curl --retry 3`) to a `.part` file and renames on success.

**Exit codes matter**: any required file that fails to download makes the whole script exit 1, which aborts the deploy/boot rather than starting a server that's silently missing a worldgen mod. If nothing was missing, it logs `All managed mods/datapacks present - no network needed` and touches nothing.

## Stage 4: `mc` boot (itzg image)

`MODS_FILE`/`DATAPACKS_FILE` are **empty by default** in this platform's compose config — itzg's entrypoint neither downloads anything nor issues a per-URL freshness HEAD check. The jars are already on disk from stage 3 (or from a previous boot, or from stage 5 below). Zero network requests, every boot, unless the mod list actually changed since the last one.

## Stage 5: in-house mod jars + stale-jar prune (`deploy.sh` step 8b/10, `dev-up.sh` equivalent)

Runs while `mc` is stopped, before it's started again:

1. **Copy in-house jars**: everything in `stack/local-mods/` (the remapped jars from `mods/` Fabric projects, staged into the bundle by `release.yml`/`build-stack-bundle.sh`) is copied into `data/mods/`, and their filenames are recorded to `data/mods/.local-mods-manifest` — this is what exempts them from the prune below.
2. **Prune stale jars**: for every `*.jar` already in `data/mods/`, delete it unless its filename appears in (a) `mods-manifest.txt` from this boot's seed run, (b) `.local-mods-manifest` just written in step 1, or (c) `stack/local-mods/` directly. A missing `mods-manifest.txt` altogether (seed never ran, or ran into an unexpected state) makes `deploy.sh` refuse to prune anything and exit 1 rather than guess.
3. **Sync missing managed files**: `sync-mods.sh` (stage 3) runs here, against the same `stack-mods` volume the seed just populated.

`dev-up.sh` runs the same two-manifest prune logic locally (`scripts/dev-up.sh` — search `.local-mods-manifest`/`mods-manifest.txt`), so the same exemptions apply to a local `./dev up`.

## Per-environment summary

| Environment | Merge | Resolve | Fetch missing | Prune | Trigger |
| --- | --- | --- | --- | --- | --- |
| Local (`./dev up`) | `defaults-seed` container via compose | Same container, same run | `sync-mods.sh` inside `dev-up.sh` | `dev-up.sh`, against the local `stack-mods` volume | Every `./dev up` |
| Production (full deploy) | `defaults-seed` container, force-recreated (`deploy.sh` step 7) | Same | `sync-mods.sh` (`deploy.sh` step 10b) | `deploy.sh` step 10, against the production `stack-mods` volume | Every full deploy (see the deploy-pipeline skill for tier detection) |
| CI (smoke test) | Same image, ephemeral runner | Same | Cached mod JARs via `actions/cache` keyed on `modrinth-mods.txt`'s hash | N/A (fresh container each run) | `smoke-test.yml` |

Weekly re-pin (`mod-updates.yml`, Monday 06:00 UTC or `gh workflow run mod-updates.yml`) only touches stage 0 (the source files, `config/modrinth-mods.txt` + `modpack/adventure.mrpack.json`) via `pin-mod-versions.sh --apply` — it opens a PR, it does not itself trigger any of the stages above. Regenerated worldgen presets (structure/terrain datapacks derived from the newly-pinned jars) ride along in the same PR via `gen-structure-presets.py`/`gen-terrain-presets.py`.
