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
| Anything touching per-dimension seeds or worldgen | c2me strips `useDensityFunctionCompiler` from `data/config/c2me.toml` after reading it on **every** boot | Nothing — the mod's preLaunch entrypoint re-supplies the key every boot, so bare restarts stay patched ([TROUBLESHOOTING.md#d6](../../../TROUBLESHOOTING.md#d6)); verify via **log grep**, never the config file |
| An nginx config (`config/nginx/*.conf.template`) | Bind-mounted into the `seed` volume, but nginx renders the template once at container start and caches it — editing the source doesn't touch the running container | `./dev restart nav-proxy` or `./dev restart pack-web` |
| Map render config or world data | `unmined-render` only renders on its `UNMINED_INTERVAL` schedule, and skips dimensions whose region files haven't changed — so nothing appears to happen immediately | `docker restart unmined-render` (a restart renders straight away) |
| A host-side bundle script (`.stack/<version>/stack/scripts/**`) | A symbolic `STACK_VERSION` (`v5`, `latest`) re-resolves on every `./dev`/`./ops` call, so publishing any release repoints `.stack/current` at a directory your patch never touched — silently, mid-session ([TROUBLESHOOTING.md#t30](../../../TROUBLESHOOTING.md#t30)) | Release it and `./dev update`. If you must patch to probe, re-check `readlink .stack/current` and grep the patch in the file that will execute, immediately before every run |

Full worked recipes for every row: `references/testing-recipes.md`. The `stack-config`/`stack-mods` volume model — what's in them, who seeds them, who reads them, and how they differ from `data/config` — is fully explained in `references/volume-model.md`. Read that before hand-patching anything; the two "config" areas look similar and are not the same mechanism.

## Verify from artefacts, not from RCON output

Before the table below sends you to a `docker exec … rcon-cli` one-liner: **the mod's diagnostic commands write versioned JSON to `data/config/custom-dimensions/`, and checkers in `scripts/` assert over those files with no server running.**

```bash
./dev verify        # every checker; safe while the server is up, paused, or down
```

RCON concatenates feedback lines with no separator, truncates at a few KB, and cannot tell a timeout from a success — so parsing its output is how a broken world produces a green run ([T17](../../../TROUBLESHOOTING.md#t17)). Use RCON to _trigger_ a dump and to run short commands; read the answer from the file.

**Check the mc boot log for a drift WARN first** (`docker logs mc | grep "worldgen config changed"`) whenever you are about to assert anything about worldgen. Worldgen is creation-time-only, so a world created before your config change still generates the OLD world and every other assertion is measuring history — this is the single most likely reason a local test disagrees with the config in front of you.

## The verification principle — read this twice

**Verify the rendered state, never the patch.** Checking that your edit is still sitting in the file you edited proves nothing about what the container is actually serving:

```bash
docker exec nav-proxy grep <expected> /etc/nginx/conf.d/default.conf
```

That's the _rendered_ nginx config (nginx's `docker-entrypoint.sh` envsubsts `/config-vol/nginx/nav-proxy.conf.template` into it once, at container start). Grepping the source template in the volume instead only tells you your patch survived — it says nothing about whether nav-proxy has picked it up (2026-07-13: a nav-proxy upstream change "passed" a check on the source file while the running container proxied to the old upstream).

The same principle applies everywhere in the table above: grep `docker logs` for the line that proves the code path ran, not the config file for the value you set.

## `./dev link` — a consumer on the platform checkout (bundle-free local dev)

To test unreleased bundle content (configs, scripts, compose, in-house mod
jars) in a consumer repo without cutting a release:

```bash
./dev link                       # default checkout: ../minecraft-server-template
./dev link ~/somewhere/else      # explicit checkout path
./dev unlink                     # restore the newest pulled release bundle
```

`link` builds a symlink farm at `.stack/dev/stack` reproducing the bundle
layout (config/scripts/examples/compose/.env.example → the checkout;
`local-mods/` gets the checkout's BUILT jars — `-dev`/`-sources` jars and
jars without a refmap are skipped with a warning), writes `VERSION=dev`, and
points `.stack/current` at it.

- `./dev` then reads the platform checkout's configs and scripts directly —
  an edit in the repo is live on the next `./dev up`.
- `local-mods/` is populated by **copying** the checkout's built jars at the
  moment `link` runs — it is a snapshot, not a symlink. So `./dev up` installs
  whatever was built when you last linked, and every rebuild after that makes
  it staler. `link` inverts trap #2 below only for the build that was on disk
  at link time; for an edit-build-test loop it re-arms it with an older jar.
  Re-run `./dev link` after every build, or copy your jar straight into
  `data/mods/` under the same filename (`ls data/mods | grep <modid>` must
  return one line).
- `readlink .stack/current` answering `dev` is the tell that a consumer is on
  a checkout, not a release — `./dev up` prints a loud LINKED banner while
  active. Run `./dev unlink` before trusting any "works on the shipped
  bundle" claim, and never leave a shared consumer (elfydd) pointed at `dev`
  when you finish.
- Local only, and guarded: `deploy.sh` refuses to run when `.stack/current`
  resolves to `dev`.

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

## The c2me patch — automatic

The customdimensions jar's preLaunch entrypoint (`C2meConfigPatch`) forces
`useDensityFunctionCompiler = false` into `data/config/c2me.toml` on every
boot, so a bare `docker restart mc` stays patched — no manual snippet, no
`./dev up` requirement. c2me reads its config at mixin-bootstrap time
(before any entrypoint) and strips the key after honouring it, so each
boot's write is consumed by the NEXT boot's read; `deploy.sh`/`dev-up.sh`
still pre-patch as a second layer, which covers the one gap (the first
boot in a fresh environment). Full mechanics:
[TROUBLESHOOTING.md#d6](../../../TROUBLESHOOTING.md#d6).

**Verify via log grep, never the config file**: the key's _absence_ from
`c2me.toml` after boot is expected and proves nothing either way.

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
4. **The c2me DFC patch is automatic, but only the log grep proves it.** The mod's preLaunch entrypoint re-supplies the key every boot ([TROUBLESHOOTING.md#d6](../../../TROUBLESHOOTING.md#d6)); the key's absence from `c2me.toml` afterwards is expected. A boot whose log lacks the `Removing config entry` line ran unpatched — the only case is a fresh environment's very first boot, which the scripts' pre-patch covers on every scripted path.
5. **`docker restart mc` is fine locally, forbidden on production.** Local dev has no countdown, kick, save, or whitelist dance to protect — that's fine, nobody's playing. Don't let the habit leak to production: server restarts there go through `deploy.sh` or Discord `/mc restart` only.
6. **On macOS Docker, bind-mount file-change events are unreliable.** Any container watching a bind-mounted path may never see a change locally. Validate pickup with an explicit `docker restart <service>` rather than waiting on a watcher.
7. **The local consumer server (`~/Projects/elfydd`) is shared.** Check nobody is mid-test before restarting its containers — a `docker restart` there affects a real, currently-in-use dev world, not a disposable one.
8. **`data/logs/latest.log` is reset on every boot** (itzg's `OnStartupTriggeringPolicy`). A crash-loop's real error lives in `docker logs mc`, not in a stale `latest.log` from a previous boot.
9. **Copying `data/config/` wholesale into a test dir brings per-dimension DistantHorizons state**, causing boot warnings about per-level configs that don't match the copied worlds. Delete `config/DistantHorizons` from the copy first.
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
