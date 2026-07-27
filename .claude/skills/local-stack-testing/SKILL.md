---
name: local-stack-testing
description: |
  Diagnoses and prevents the local test that passes for the wrong reason.
  Covers the two independent config-seeding paths (stack-config/stack-mods
  named volumes from the defaults-seed image, versus the data/config bind
  mount seeded skip-if-exists by dev-up.sh), why a plain `compose up` reverts
  a hand-patched volume, why `./dev up` overwrites a locally-built mod jar
  with the bundle's released copy, and the c2me density-function-compiler key
  stripped from c2me.toml on every boot.

  Use when: testing unreleased defaults-seed content locally; testing a
  locally-built mod jar without cutting a release; choosing between `./dev
  up`, `./dev restart <service>` and a raw `docker restart`; or working out
  why a local pass didn't reflect the change under test. Consult before
  trusting a `./dev up` boot as proof a change is live, and when debugging
  "Removing config entry
  .vanillaWorldGenOptimizations.useDensityFunctionCompiler".
---

# Local Stack Testing

You are testing an unreleased change against the local Docker stack (this platform repo, or a consumer repo like `elfydd`) and need the test to actually exercise the change — not silently fall back to whatever was already there. Every trap here has the same shape: **the boot succeeded, the assertion passed, and the change under test never ran.**

## MANDATORY: the decision table

Read this before touching the stack. Find the row that matches what you changed, and run the command in that row — not `./dev up` by reflex.

| You changed | The trap | Correct local test |
| --- | --- | --- |
| An in-house mod jar (`mods/custom-dimensions/`) | `./dev up` copies `stack/local-mods/*.jar` from the bundle into `data/mods/` on **every** run, silently overwriting your fresh build with the last released version | `cp build/libs/<mod>-<version>.jar <consumer>/data/mods/<mod>.jar`, then `docker stop mc && docker start mc` (or `docker restart mc`) — never `./dev up` |
| Content baked into the `defaults-seed` image (`config/modrinth-mods.txt`, `config/nginx/*.template`, anything under `docker/defaults-seed/`) | The image is rebuilt only by CI on push. The `stack-config`/`stack-mods` **named volumes** are seeded once by the `seed` container; any `compose up` that reruns `seed` (i.e. without `--no-deps`) silently restores image defaults over your hand-patch | Patch the volume directly, then recreate **only the consuming service** — `./dev restart nav-proxy` / `./dev restart pack-web` (runs `--force-recreate --no-deps`, does not touch `seed`) — and verify the **rendered** file, never the patch |
| A config under `config/<modname>/` that `dev-up.sh` seeds into `data/config/` (a **bind mount**, not the volume above) | Seeding is `if [[ ! -f "$dest" ]]` — skip-if-exists. An existing `data/config/` file (e.g. from a previous version) keeps its old content forever; your new fields never land | `./dev refresh-config` (backs up `data/config` to `data/config.bak.<timestamp>`, then copies **every** platform default unconditionally and reapplies the overlay) — or delete the one target file yourself before `./dev up` |
| Anything touching per-dimension seeds or worldgen | c2me strips `useDensityFunctionCompiler` from `data/config/c2me.toml` after reading it on **every** boot; a bare restart after the first one boots unpatched and every custom dimension silently clones the main world | Re-run the c2me snippet (below) before every `stop`/`start` cycle, or use `./dev up` (which re-applies it); verify via **log grep**, never the config file |
| An nginx config (`config/nginx/*.conf.template`) | Bind-mounted into the `seed` volume, but nginx renders the template once at container start and caches it — editing the source doesn't touch the running container | `./dev restart nav-proxy` or `./dev restart pack-web` |
| Map render config or world data | `unmined-render` only renders on its `UNMINED_INTERVAL` schedule, and skips dimensions whose region files haven't changed — so nothing appears to happen immediately | `docker restart unmined-render` (a restart renders straight away) |

Full worked recipes for every row: `references/testing-recipes.md`. The `stack-config`/`stack-mods` volume model — what's in them, who seeds them, who reads them, and how they differ from `data/config` — is fully explained in `references/volume-model.md`. Read that before hand-patching anything; the two "config" areas look similar and are not the same mechanism.

## Verify from artefacts, not from RCON output

Before the table below sends you to a `docker exec … rcon-cli` one-liner: **the mod's diagnostic commands write versioned JSON to `data/config/custom-dimensions/`, and checkers in `scripts/` assert over those files with no server running.**

```bash
./dev verify        # every checker; safe while the server is up, paused, or down
```

RCON concatenates feedback lines with no separator, truncates at a few KB, and cannot tell a timeout from a success — so parsing its output is how a broken world produces a green run ([T17](../../../TROUBLESHOOTING.md#t17)). Use RCON to _trigger_ a dump and to run short commands; read the answer from the file.

**Run `scripts/check-dimension-drift.py` first** whenever you are about to assert anything about worldgen. Worldgen is creation-time-only, so a world created before your config change still generates the OLD world and every other assertion is measuring history — this is the single most likely reason a local test disagrees with the config in front of you.

## The verification principle — read this twice

**Verify the rendered state, never the patch.** Checking that your edit is still sitting in the file you edited proves nothing about what the container is actually serving:

```bash
docker exec nav-proxy grep <expected> /etc/nginx/conf.d/default.conf
```

That's the _rendered_ nginx config (nginx's `docker-entrypoint.sh` envsubsts `/config-vol/nginx/nav-proxy.conf.template` into it once, at container start). Grepping the source template in the volume instead only tells you your patch survived — it says nothing about whether nav-proxy has picked it up (2026-07-13: a nav-proxy upstream change "passed" a check on the source file while the running container proxied to the old upstream).

The same principle applies everywhere in the table above: grep `docker logs` for the line that proves the code path ran, not the config file for the value you set.

## The two local entry points

| Entry point | Use in | Notes |
| --- | --- | --- |
| `./dev up` / `./dev down` / `./dev restart <service>` | Consumer repos (`elfydd`-shaped checkouts) | Thin wrapper (`examples/consumer/dev`) that resolves `CONSUMER_DIR` to the consumer repo root and delegates to the bundle's `dev-up.sh` / `service.sh`. This is the only entry point most contributors need. |
| `docker compose --profile local up -d` | Platform checkout (this repo) | The command CONTRIBUTING.md and README.md both document as the direct alternative. **Prefer this over running `scripts/dev-up.sh` bare.** |

**Do not run `./scripts/dev-up.sh` directly without exporting `CONSUMER_DIR` first.** Its own header says "Called by the consumer's dev script, not directly," and its `CONSUMER_DIR` auto-resolution walks up two directories assuming it's nested at `<consumer>/.stack/vX.Y.Z/stack/scripts/dev-up.sh`. Invoked bare from this repo's plain `scripts/` directory, that walk lands **two levels above the repo** — verified: from `<repo>/scripts` it resolves to the repo's grandparent directory, then tries to `mkdir -p <grandparent>/data/mods` and source `<grandparent>/.env`. If you must call it directly (e.g. to test `dev-up.sh` itself), export `CONSUMER_DIR="$(pwd)"` first, from the repo root.

`./dev restart <service>` refuses `mc` by design (`service.sh`: "Refusing raw MC lifecycle operation. Use deploy.sh or Discord /mc restart.") — this is a production safety rail that also applies to `./dev restart` locally. For `mc` itself, use `docker stop mc && docker start mc` or `docker restart mc` directly against the Docker CLI.

## What the local profile changes vs production

`docker-compose.local.yml` overrides, and what they mean for what you can and can't validate locally:

- `ONLINE_MODE=FALSE`, whitelist fully disabled (`ENABLE_WHITELIST`, `ENFORCE_WHITELIST`, `OVERRIDE_WHITELIST` all `FALSE`) — you cannot test the whitelist door-lock behaviour, Mojang auth, or the Discord role-to-whitelist sync locally.
- `ENABLE_AUTOPAUSE=FALSE` — autopause behaviour (RCON going silent when empty) is untestable locally; don't mistake local RCON silence for the production autopause symptom.
- MinIO (`localhost:9001`, `minioadmin`/`minioadmin123`) stands in for Cloudflare R2 — the backup _mechanism_ is identical (restic), but nothing exercises real R2 credentials, the tunnel, or DNS.
- `uptime-kuma` and `pack-web` are bound to `localhost` only — the Cloudflare tunnel path is entirely untested locally.
- `OPS` still grants operator via RCON post-boot (offline mode can't resolve usernames to UUIDs through Mojang, so the `OPS` env var itself silently no-ops — `dev-up.sh` works around this with a post-boot RCON loop).

A green local boot proves the mod/config/worldgen path works. It proves nothing about auth, the whitelist dance, autopause, or anything behind the tunnel.

## The c2me recipe

Needed before nearly every `stop`/`start` cycle once you're touching dimensions, seeds, or worldgen. This is the idempotent snippet from `dev-up.sh` (search `useDensityFunctionCompiler`) — reproduce it exactly, don't approximate:

```python
import sys, os, re
p = sys.argv[1]
section = "[vanillaWorldGenOptimizations]"
key = "useDensityFunctionCompiler"
if os.path.exists(p):
    s = open(p).read()
    if key in s:
        s2 = re.sub(r'%s\s*=\s*\S+' % key, '%s = false' % key, s)
    elif section in s:
        s2 = s.replace(section, section + "\n\t%s = false" % key)
    else:
        s2 = s + "\n%s\n\t%s = false\n" % (section, key)
    if s2 != s:
        open(p, "w").write(s2)
        print("  c2me: useDensityFunctionCompiler forced off (per-dimension seeds)")
else:
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write("%s\n\t%s = false\n" % (section, key))
```

Run against `<consumer>/data/config/c2me.toml`. `./dev up` applies this automatically every time; a bare `docker restart mc` does not — re-run the snippet (or use `./dev up`) before trusting seed/worldgen results after a manual restart.

**Verify via log grep, never the config file**: c2me reads the key first, then strips it when it rewrites its own config on boot — so its _absence_ from `c2me.toml` after boot is expected and proves nothing either way.

```bash
docker exec mc sh -c 'grep -F "Removing config entry .vanillaWorldGenOptimizations.useDensityFunctionCompiler because it is not used" /data/logs/latest.log'
```

Finding that line confirms the patch was read before c2me wiped it. Its absence means the boot ran unpatched — every custom dimension will silently clone the main world's terrain.

## Reset to clean

- `./dev up` **deliberately** restores the bundle's shipped in-house mod jars and re-seeds any `data/config/` file that's missing — this is by design, not a bug, and is exactly what you want once you're done testing a local build and want back to the released baseline.
- `./dev down` stops and removes the stack's containers (not volumes or `data/`).
- Worldgen changes (`type`, `noiseSettings`, `biomes`, `seed` in a dimension config) are creation-time-only and survive everything short of wiping `data/world` — see the traps below and `mods/AGENTS.md` for the full level.dat scrub procedure if you need to fully remove a runtime dimension.

## Traps

The five/six traps in the decision table above are the core of this skill, restated here as full symptom → cause → fix entries. The rest extend it.

1. **Hand-patched shared volumes are reverted by every seed run.** Symptom: a local test of unreleased `defaults-seed` content passes, but the change was never live — the pass came through the old config. Cause: `stack-config`/`stack-mods` are named volumes seeded once by the `seed` container from the image; any `compose up` that reruns `seed` silently restores image defaults over a hand-patch. Fix: patch the volume, then `./dev restart <consuming-service> --no-deps`-equivalent (i.e. `./dev restart nav-proxy`), never a plain `compose up` (2026-07-13).
2. **`./dev up` destroys a local mod build.** Symptom: a whole feature appears broken across several boot cycles. Cause: `dev-up.sh` copies `stack/local-mods/*.jar` into `data/mods/` on every run, overwriting your fresh jar with the last release. Fix: `cp` your jar in, then `docker restart mc` — never `./dev up` (2026-07-25).
3. **`dev-up.sh` skip-if-exists blocks config upgrades.** Symptom: a consumer upgrading keeps an old config file missing new fields, with no warning. Cause: `if [[ ! -f "$dest" ]]` only seeds a file that doesn't already exist. Fix: `./dev refresh-config`, or delete the specific file before `./dev up`.
4. **The c2me re-patch does not survive repeated restarts.** Symptom: three consecutive fixture cycles ran unpatched mid-session despite the trap being documented. Cause: c2me strips the key on every boot after reading it, so patching once only covers the first restart. Fix: re-run the snippet (or `./dev up`) before every `stop`/`start`, verify by log grep (2026-07-23).
5. **`docker restart mc` is fine locally, forbidden on production.** Local dev has no countdown, kick, save, or whitelist dance to protect — that's fine, nobody's playing. Don't let the habit leak to production: server restarts there go through `deploy.sh` or Discord `/mc restart` only.
6. **On macOS Docker, bind-mount file-change events are unreliable.** Any container watching a bind-mounted path may never see a change locally. Validate pickup with an explicit `docker restart <service>` rather than waiting on a watcher.
7. **The local consumer server (`~/Projects/elfydd`) is shared.** Check nobody is mid-test before restarting its containers — a `docker restart` there affects a real, currently-in-use dev world, not a disposable one.
8. **`data/logs/latest.log` is reset on every boot** (itzg's `OnStartupTriggeringPolicy`). A crash-loop's real error lives in `docker logs mc`, not in a stale `latest.log` from a previous boot.
9. **Copying `data/config/` wholesale into a test/seedtest dir brings per-dimension DistantHorizons state**, causing boot warnings about per-level configs that don't match the copied worlds. Delete `config/DistantHorizons` from the copy first.
10. **`mise exec` is required for mod builds.** `mods/mise.toml` pins Java 21, but a global Java (e.g. 25) wins on `PATH` and Gradle fails with a misleading task-creation error, not a clear wrong-Java message. Use `mise exec -- ./gradlew build`.
11. **Running `scripts/dev-up.sh` bare from a platform checkout resolves `CONSUMER_DIR` outside the repo.** The script's path-walk assumes `<consumer>/.stack/vX.Y.Z/stack/scripts/dev-up.sh` nesting; from this repo's plain `scripts/` directory it resolves two levels above the repo root instead (verified: `cd scripts && CONSUMER_DIR` auto-resolves to the repo's grandparent). Use `docker compose --profile local up -d` directly, or export `CONSUMER_DIR="$(pwd)"` first.

## Validation — do not skip this

Generic "did my change actually take effect" ritual, in order:

```bash
# 1. Container health and restart count (a RestartCount above 0 is a crash
#    you haven't explained yet, not evidence of self-healing)
docker inspect <service> --format '{{.State.Health.Status}} {{.RestartCount}}'

# 2. Read the RENDERED artefact, never the source you edited
docker exec <service> <read the rendered config/file>

# 3. Snapshot logs for the marker line that proves the code path ran
docker logs mc --tail 80 | grep -iE 'error|warn|<your marker>'

# 4. Prove the behaviour via RCON, not just "it booted"
docker exec -i mc rcon-cli "<a command that proves the behaviour>"
```

**Negative control principle** (from `mods/AGENTS.md`): capture a baseline **before** the change — what the log/RCON output looked like on the old code — or a "pass" is unfalsifiable. This is exactly how the c2me seed mixin, the shared-igniter portal bug, and the piston/carpet crash were all proven: a before/after pair, never a single "it worked" observation.

## References

- `references/volume-model.md` — what's baked into the `defaults-seed` image, what lands in the `stack-config`/`stack-mods` named volumes vs the `data/config` bind mount, who seeds each, who reads each, and why they revert differently.
- `references/testing-recipes.md` — full command sequences for every row in the decision table above.
