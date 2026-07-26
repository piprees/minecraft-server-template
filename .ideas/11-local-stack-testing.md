# Skill brief: `local-stack-testing`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

This skill exists because of one failure mode: **the local test that passes for the wrong reason.** The repo has at least four documented instances, each costing a session:

- **Trap 12** — hand-patching a shared volume, then running any `compose up` without `--no-deps`: the one-shot seed re-runs and silently restores image defaults over the patch. A nav-proxy upstream change "passed" while proxying to the old upstream (2026-07-13).
- **`./dev up` destroying a local mod build** — `dev-up.sh` copies `stack/local-mods/*.jar` into `data/mods/` on every run. An entire lazy-init feature appeared broken across four boot cycles because every test ran the bundle jar (2026-07-25).
- **The c2me re-patch** — c2me strips `useDensityFunctionCompiler` on every boot after reading it, so a bare `docker restart mc` boots unpatched and every custom dimension silently clones the main world. Three consecutive fixture cycles ran that way mid-session (2026-07-23).
- **`dev-up.sh` skip-if-exists** blocking config upgrades — a consumer upgrading keeps the old `multiverse_config.json` without the new fields, and nothing says so.

Every one of these is "the change was never actually live and the test said fine". That is the single highest-value thing a skill can prevent here, and it is currently four unconnected bullet points in three different files.

## Scope

**In:** running and reasoning about the local stack, and specifically **testing unreleased platform content correctly**. Volume vs bind-mount semantics, the seed container's reach, force-recreate rules, verifying the *rendered* state, the local↔production differences, and the macOS Docker caveats.

**Out:** consumer repo conventions → brief 04. Mod build/verify loop → brief 05 (cross-reference: this skill owns "why your test lied", brief 05 owns "how to build and exercise the jar").

## Source material

| File | What to mine |
| --- | --- |
| `AGENTS.md` trap 12 | **The core.** Hand-patched shared volumes reverted by every seed run; the `--force-recreate --no-deps` recipe; verify the *rendered* state |
| `AGENTS.md` trap 2 | The seed container must re-run; nginx configs still bind-mounted so nav-proxy/pack-web need force-recreate |
| `AGENTS.md § Platform-specific traps (macOS local dev)` | `od`, `grep -P`, `mise exec` |
| `AGENTS.md § Dimension lifecycle traps` → `dev-up.sh` skip-if-exists | The v3 config upgrade block |
| `AGENTS.md` trap 13 | On macOS Docker, bind-mount file events are unreliable — the BlueMap `-u` watcher may never fire locally; validate with `docker restart bluemap` |
| `mods/AGENTS.md § Fast local loop` | **Never `./dev up` to test a local mod build**; the c2me re-patch before every stop/start; verify by log grep not config inspection |
| `scripts/dev-up.sh` (header + body) | Env loading, hosts printout, `--project-directory` so relative volumes resolve to the CONSUMER dir; the skip-if-exists config seeding; the c2me patch snippet (search `useDensityFunctionCompiler`) |
| `docker-compose.yml` + `docker-compose.local.yml` | The `local` profile: `ONLINE_MODE`/whitelist/autopause off, MinIO stand-in, localhost-bound ports |
| `docker/defaults-seed/seed.sh` + `Dockerfile` | What the seed lays down and where it comes from (the image, rebuilt only by CI on push) |
| `README.md § Run your own server`, `CONTRIBUTING.md § Which workflow am I in?` | The two local entry points: `./dev up` (consumer) vs `./scripts/dev-up.sh` (platform checkout) |
| `docker-compose.modrinth.yml` | The temporary Modrinth override — what it is for and why deleting it makes mc look config-drifted |
| `.github/workflows/smoke-test.yml` | The CI equivalent of this loop — mirror its assertion style |

## Required structure

```
local-stack-testing/
├── SKILL.md
└── references/
    ├── volume-model.md          # what comes from the image, what's bind-mounted, what the seed overwrites and when
    └── testing-recipes.md       # per-change-type: how to test it locally without a false pass
```

### SKILL.md must contain

1. **A "will this test lie to me?" decision table** as the opening section. Rows keyed on what changed:
   | You changed | The trap | Correct local test |
   | --- | --- | --- |
   | An in-house mod jar | `./dev up` overwrites it from the bundle | `cp` into `data/mods/`, then `docker stop mc && docker start mc` |
   | Content in the `defaults-seed` image (mod list, nginx templates) | The image is rebuilt only by CI; the seed restores image defaults over hand-patches on any `compose up` | Patch the volume, recreate **only** the consumer with `--force-recreate --no-deps`, verify the *rendered* file |
   | A config seeded by `dev-up.sh` | Skip-if-exists means your new file never lands | Delete the target under `data/config/` first |
   | Anything touching per-dimension seeds | c2me strips the DFC key each boot; a bare restart boots unpatched | Re-patch before every `stop/start`; verify by log grep |
   | An nginx config | Bind-mounted, but the container caches it | `--force-recreate` nav-proxy / pack-web |
2. **The verification principle, stated once and hard:** *verify the rendered state, never the patch.*
   ```bash
   docker exec nav-proxy grep <expected> /etc/nginx/conf.d/default.conf
   ```
   Checking that your edit is still in the file you edited proves nothing about what the container is serving.
3. **The two local entry points**, and when each is right: `./dev up` in a consumer repo; `./scripts/dev-up.sh` (or `docker compose --profile local up -d`) in a platform checkout.
4. **What the local profile changes** vs production: `ONLINE_MODE` off, whitelist off, autopause off, MinIO instead of R2, Kuma and pack-web bound to localhost. An agent must know that "it worked locally" does not test the whitelist dance, autopause behaviour, or the tunnel.
5. **The c2me recipe**, inline, because it is needed on nearly every restart: the idempotent snippet from `dev-up.sh`, and the log line that proves it applied — `Removing config entry .vanillaWorldGenOptimizations.useDensityFunctionCompiler because it is not used`. The key's *absence* from `c2me.toml` after boot is expected and is not evidence either way.
6. **A "reset to clean" section**: `./dev up` deliberately restores the bundle's shipped jars; `./dev down`; wiping `data/world` when a creation-time worldgen change must take effect.

### Traps to capture

Beyond the four in "Why this skill":

5. **`docker restart mc` is fine locally, forbidden on production.** Do not let the local habit leak — production restarts go through `deploy.sh` or Discord `/mc restart`.
6. **On macOS Docker, bind-mount file events are unreliable.** Watchers (BlueMap `-u`) may never fire. Validate change pickup with an explicit restart and trust the watcher on Linux.
7. **The local consumer server (`~/Projects/elfydd`) is shared** — check nobody is mid-test before restarting its containers.
8. **`data/logs/latest.log` is reset on every boot** (OnStartupTriggeringPolicy) — a crash-loop's real error is in `docker logs`, not the file.
9. **Copying `data/config/` wholesale into a test dir brings per-dimension BlueMap/DH state** and causes 70+ boot warnings. Delete `config/bluemap` and `config/DistantHorizons`.
10. **`mise exec` for mod builds** — a global Java wins and Gradle fails with a misleading error.

### Validation section

Give a generic "did my change actually take effect" ritual:

```bash
docker inspect <service> --format '{{.State.Health.Status}} {{.RestartCount}}'
docker exec <service> <read the rendered artefact>      # never the source file
docker logs mc --tail 80 | grep -iE 'error|warn|<your marker>'
docker exec -i mc rcon-cli "<a command that proves the behaviour>"
```

And the negative control principle from `mods/AGENTS.md`: capture a baseline **before** the change, or the result is unfalsifiable.

## Done when

- An agent that patches a shared volume recreates only the consuming service and verifies the rendered file.
- An agent testing its own mod build never runs `./dev up`.
- The skill can answer "why did my change appear to do nothing?" in one table lookup.
