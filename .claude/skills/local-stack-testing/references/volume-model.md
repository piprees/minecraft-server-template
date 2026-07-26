---
title: Volume Model
description: What's baked into the defaults-seed image, what lands in the stack-config/stack-mods named volumes vs the data/config bind mount, who seeds each, who reads each, and why they revert differently
tags: [volumes, bind-mounts, defaults-seed, stack-config, stack-mods, data-config, seed-container]
---

# Volume Model

There are **two independent "config" mechanisms** in this stack that look
similar and are not the same thing. Confusing them is the root cause of most
false-pass local tests. Read this before hand-patching anything.

## Mechanism 1: the `stack-config` / `stack-mods` named volumes

These are Docker named volumes (declared at the bottom of
`docker-compose.yml`): `stack-config:` and `stack-mods:`. Actual volume
names are project-prefixed: `${COMPOSE_PROJECT_NAME}_stack-config` (default
project name is `${BRAND_SLUG:-myserver}`, e.g. `myserver_stack-config`).

**Seeded by:** the `seed` service (`container_name: "${CONTAINER_PREFIX:-}seed"`,
so just `seed` by default), image `defaults-seed`. Its entrypoint is
`docker/defaults-seed/seed.sh`, which does:

```bash
rsync -a --delete /defaults/config/ /out/config/
```

`/defaults/config/` is baked into the `defaults-seed` image at **build
time** by `docker/defaults-seed/Dockerfile` — a long list of explicit `COPY`
lines from this repo's `config/` directory (log4j2, tectonic.json,
messages.json, starterkit, uptime-kuma/, nginx/, datapacks/,
custom-dimensions/, and ~20 per-mod config directories). If a consumer
overlay exists at `/overlay/config`, it's rsynced on top (excluding
`custom-dimensions/`, which is routed into a `custom-dimensions/overlay/`
subdirectory instead so the mod can resolve replace/`"overrides"`/empty-`{}`
itself — never a raw overwrite of the platform dimension files).

`seed.sh` also merges `config/modrinth-mods.txt` against
`overlay/mods-remove.txt` / `overlay/mods-extra.txt` into
`/out/mods/modrinth-mods.txt`, and resolves every pin to a direct download
URL via `resolve-mods.py` (cached — a warm cache costs zero Modrinth API
calls).

**The critical property: `rsync --delete` runs on every `seed` execution,
and `seed` runs on every `compose up` that includes it as a dependency.**
`mc`, `nav-proxy`, `pack-web`, `kuma-init`, and `mod-checker` all
`depends_on: seed: condition: service_completed_successfully` — so a plain
`docker compose up` (no `--no-deps`) reruns `seed`, which `rsync --delete`s
the volume back to image-defaults-plus-overlay, silently discarding any
hand-patch you made directly against the volume's contents.

**Read by:**

| Consumer | Mount | What it reads |
| --- | --- | --- |
| `mc` | `stack-config:/config-vol:ro` | `log4j2-adventure.xml` (referenced explicitly via `JVM_OPTS`) |
| `nav-proxy` | `stack-config:/config-vol:ro` | `nginx/nav-proxy.conf.template` — rendered once at container start via nginx's envsubst entrypoint into `/etc/nginx/conf.d/default.conf` |
| `pack-web` | `stack-config:/config-vol:ro` | `nginx/pack-web.conf.template`, same rendering mechanism |
| `kuma-init` | `stack-config:/config-vol:ro` | `uptime-kuma/kuma-config.json` |
| `mod-checker` | `stack-config:/config-vol:ro`, `stack-mods:/mods-vol:ro` | `messages.json`, `modpack-manifest.json`, merged `modrinth-mods.txt` |
| `mc` | `stack-mods:/extras:ro` | merged mod list + resolved URLs (`MODS_FILE`/`DATAPACKS_FILE`, off by default so this is mostly inert at boot) |

**To hand-patch this volume for a local test** (e.g. to try a nginx
template change or a mod-list edit before it's built into a new
`defaults-seed` image), write directly into the volume without going
through `seed` — for example:

```bash
docker run --rm -v myserver_stack-config:/v alpine \
  sh -c "sed -i 's/old/new/' /v/nginx/nav-proxy.conf.template"
```

Then recreate **only the consuming service** — `./dev restart nav-proxy`
(runs `docker compose up -d --force-recreate --no-deps nav-proxy`, per
`scripts/service.sh` — the `--no-deps` is what keeps `seed` from rerunning
and reverting your patch).

## Mechanism 2: the `data/config` bind mount

`mc`'s compose entry mounts `./data:/data` — a plain host-directory bind
mount, not a named volume. `data/config/` (on the host) is where the actual
running mods read and write their TOML/JSON config (`c2me.toml`,
`tectonic.json`, `Discord-Integration.toml`, `DistantHorizons.toml`,
`custom-dimensions/`, and everything else a mod expects under its working
directory's `config/`).

**Seeded by:** `dev-up.sh`'s own copy loop (not the `seed` container, not
the volume above — a completely separate code path that reads straight from
`$STACK_DIR/config` on the host, the same source content the `defaults-seed`
Dockerfile bakes into its image, but copied host-side instead):

```bash
find . -type f -not -path './nginx/*' ... \
  | while IFS= read -r f; do
    dest="$local_data_cfg/${f#./}"
    if [[ ! -f "$dest" ]]; then          # <-- skip-if-exists
      mkdir -p "$(dirname "$dest")"
      cp "$f" "$dest"
    fi
  done
```

**The critical property: `if [[ ! -f "$dest" ]]` means this only ever
copies a file that doesn't already exist.** A file that was seeded by an
older version of the platform keeps its old content forever, across
however many `./dev up` runs follow, until something removes it. This is
what lets a consumer upgrade silently keep a stale `multiverse_config.json`
missing new fields with zero warning.

`dev-up.sh refresh-config` is the escape hatch: it backs up the whole
`data/config` directory (`data/config.bak.<timestamp>`), then copies
**every** platform file **unconditionally**, then reapplies the overlay —
no skip-if-exists. Use it whenever you need `data/config` to actually catch
up to the platform defaults.

Two other things `dev-up.sh` does to `data/config` on every run, both
idempotent and both worth knowing about when a "nothing changed" result
looks suspicious:

- Forces `useDensityFunctionCompiler = false` in `c2me.toml` (see the c2me
  recipe in `SKILL.md`).
- Silences Distant Horizons' GC-warning wall in `DistantHorizons.toml` via
  `sed`.

**Read/written by:** the `mc` process itself, directly, as its own mod
config directory. Nothing else mounts `data/config` read-only the way the
volume above is mounted by sidecars — it's the live, mutable config
directory for the running server.

## Why they revert differently

| | `stack-config`/`stack-mods` (volume) | `data/config` (bind mount) |
| --- | --- | --- |
| Seeded by | `seed` container, `rsync --delete` | `dev-up.sh`, `cp` skip-if-exists |
| Reruns on | Every `compose up` that doesn't `--no-deps` past `seed` | Every `./dev up` |
| Overwrite behaviour | **Always** overwrites (mirror sync) | **Never** overwrites an existing file |
| False-pass mode | Your hand-patch vanishes — you tested the OLD content | Your new file never lands — you tested the OLD content |
| Escape hatch | `--force-recreate --no-deps` on the consuming service, skip `seed` | `./dev refresh-config`, or delete the target file first |

Both failure modes produce the identical symptom — "I changed X, the test
passed, but the running stack still behaves like the old X" — via opposite
mechanisms (one over-writes, one under-writes). Diagnose which mechanism
you're dealing with by asking: is this file baked into the `defaults-seed`
image (`docker/defaults-seed/Dockerfile` has a `COPY` line for it, or it's
part of `config/modrinth-mods.txt`)? If yes, it's the volume, and the fix is
`--force-recreate --no-deps`. If it's a mod's own config directory under
`config/<modname>/` seeded skip-if-exists into `data/config/`, it's the bind
mount, and the fix is `./dev refresh-config` or deleting the target file.
