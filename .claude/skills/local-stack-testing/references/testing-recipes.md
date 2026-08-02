---
title: Testing Recipes
description: Full command sequences for testing every category of unreleased platform change locally without a false pass
tags: [testing, dev-up, docker, rcon, c2me, nginx, map-render, mod-build]
---

# Testing Recipes

One recipe per row of the decision table in `SKILL.md`. Each ends with a verification step that checks the **rendered** state, not the patch.

## 1. An in-house mod jar (`mods/custom-dimensions/`)

Never `./dev up` for this — it overwrites `data/mods/<mod>.jar` from the bundle's `stack/local-mods/` on every run (2026-07-25 incident: a whole lazy-init feature looked broken across four boot cycles because every test ran the old bundle jar).

```bash
cd mods/custom-dimensions
mise exec -- ./gradlew build
unzip -l build/libs/customdimensions-*.jar | grep -c '\.class$'   # not 0
unzip -l build/libs/customdimensions-*.jar | grep refmap          # must be present

cp build/libs/customdimensions-*.jar <consumer>/data/mods/customdimensions.jar
docker stop mc && docker start mc      # or: docker restart mc
sleep 45
docker inspect mc --format '{{.State.Health.Status}}'   # must be healthy
docker logs mc 2>&1 | grep -iE 'mixin apply|customdimensions|error' | tail -20
```

If the persisted state format changed (config schema, namespace, IDs), delete the mod's state file(s) under `data/config/` before restarting — otherwise stale state from the previous build masks the bug you're testing.

The c2me DFC patch is automatic on every boot (the mod's preLaunch entrypoint — recipe 4 has the verification grep).

## 2. Content baked into the `defaults-seed` image

Covers `config/modrinth-mods.txt`, `config/nginx/*.template`, anything with a `COPY` line in `docker/defaults-seed/Dockerfile`. See `references/volume-model.md` for why a plain `compose up` reverts this.

```bash
# Patch the volume directly — do NOT touch data/config, this is the
# stack-config/stack-mods NAMED VOLUME, seeded by the seed container.
docker run --rm -v "${COMPOSE_PROJECT_NAME:-myserver}_stack-config":/v alpine \
  sh -c "sed -i 's/proxy_pass http:\/\/old/proxy_pass http:\/\/new/' /v/nginx/nav-proxy.conf.template"

# Recreate ONLY the consuming service — --no-deps means `seed` does not rerun.
./dev restart nav-proxy

# Verify the RENDERED file, never the template you just edited.
docker exec nav-proxy grep 'proxy_pass http://new' /etc/nginx/conf.d/default.conf
```

For a mod-list change (`config/modrinth-mods.txt`), patch `/v/../mods/modrinth-mods.txt` inside `stack-mods` the same way, then `./dev restart mc` is not available (mc is excluded from `service.sh`) — you need a real `./dev up` to re-run `seed` and `sync-mods.sh`, or accept that mod-list changes to the image genuinely require the seed to rerun (this category doesn't have a `--no-deps` shortcut; test mod-list changes by letting `./dev up` re-seed, and confirm via `docker logs seed` showing the new count).

## 3. A config seeded by `dev-up.sh` into `data/config/` (skip-if-exists)

```bash
# Preferred: refresh everything, with a backup.
./dev refresh-config
# Look at what changed vs the backup:
diff -rq data/config.bak.<timestamp> data/config | grep -v '^Only in data/config.bak'

# Or, surgical: just the one file.
rm <consumer>/data/config/multiverse_config.json
./dev up
```

Verify the new fields actually landed (don't just check the file exists — check the field you added):

```bash
python3 -c "import json; print(json.load(open('data/config/multiverse_config.json')).get('noiseSettings'))"
```

## 4. Per-dimension seeds / worldgen / anything c2me-adjacent

The c2me DFC patch is AUTOMATIC: the mod's preLaunch entrypoint
(`C2meConfigPatch`) forces `useDensityFunctionCompiler = false` into
`data/config/c2me.toml` on every boot, so a bare `docker restart mc` stays
patched — no manual snippet ([TROUBLESHOOTING.md#d6](../../../../TROUBLESHOOTING.md#d6)
has the mixin-bootstrap timing; `deploy.sh`/`dev-up.sh` still pre-patch as
the layer covering a fresh environment's first boot).

```bash
docker restart mc
sleep 30

# Verify via log grep, never the config file (the key is stripped again
# by the boot that honours it — absence from c2me.toml is expected).
docker exec mc sh -c 'grep -F "Removing config entry .vanillaWorldGenOptimizations.useDensityFunctionCompiler because it is not used" /data/logs/latest.log'

# The locate oracle: two dims with different seeds must give different
# results; same seed must match.
docker exec -i mc rcon-cli "execute in adventure:<dim1> run locate structure minecraft:village"
docker exec -i mc rcon-cli "execute in adventure:<dim2> run locate structure minecraft:village"
```

## 5. An nginx config

```bash
./dev restart nav-proxy    # or: ./dev restart pack-web
docker exec nav-proxy grep <expected-string> /etc/nginx/conf.d/default.conf
```

If the change lives in the platform's `config/nginx/` source rather than a volume you've already hand-patched, you need recipe 2 first (patch the volume, then restart), since `nav-proxy` never reads `config/nginx/` directly — only what `seed` copied into `stack-config`.

## 6. Map render config or map data

```bash
docker restart unmined-render
# Give it the boot -r pass to detect changed regions, then check tile mtimes:
docker exec unmined-render find /web -name '*.webp' -newer /tmp/marker -mmin -2 | head
```

Don't wait on the `-u` watcher locally — on macOS Docker its file-change events are unreliable and may never fire. The explicit restart is the only reliable local trigger; trust the watcher only on Linux production.

## Reset to a clean baseline

```bash
./dev up      # restores bundle-shipped in-house mod jars, re-seeds any
              # missing data/config file — deliberate, not a bug
./dev down    # stops and removes containers (data/ and volumes untouched)

# Full worldgen reset (creation-time config change must take effect):
./dev down
rm -rf data/world
./dev up      # all dimensions recreate from current config, winner seeds included
```
