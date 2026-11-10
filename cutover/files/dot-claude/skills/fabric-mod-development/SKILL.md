---
name: fabric-mod-development
description: Builds, verifies and ships the in-house Fabric mods under mods/ on Minecraft 26.3 — mods/custom-dimensions-26 (the server mod) and mods/custom-dimensions-client-26 (the optional companion). Covers the mise-pinned Java 25 Gradle build, Loom's non-remapping plugin (no refmap), the in-game self-test and its sections, the survey and the client rig, the artefact gate that catches an empty jar, the linked local loop, and the release path into the stack bundle. Use when changing mod code, adding a mixin, verifying a build before a server runs it, running the self-test, or diagnosing a crash-looping server after a mod change. Not for authoring definitions (custom-dimension-authoring) or cutting a release (platform-release-management).
---

# Fabric mod development

Rules for mod code (modules, mixins, threading): [`mods/AGENTS.md`](../../../mods/AGENTS.md). This skill is the procedure.

| Project | Manifest | README |
| --- | --- | --- |
| `mods/custom-dimensions-26/` | `mods/local-mods.manifest` | [README](../../../mods/custom-dimensions-26/README.md) |
| `mods/custom-dimensions-client-26/` | `mods/client-mods.manifest` | [README](../../../mods/custom-dimensions-client-26/README.md) |

## 1. Build

```bash
cd mods/custom-dimensions-26
mise exec -- ./gradlew build        # core, compiler, server; unit tests included
```

Java 25 from the project's `mise.toml` ([P4](../../../TROUBLESHOOTING.md#p4)). The jar is `server/build/libs/customdimensions-<version>.jar` with `core` and `compiler` nested in `META-INF/jars/`.

## 2. Gate the artefact

Gradle reports `BUILD SUCCESSFUL` for an empty jar. The same gate the release runs:

```bash
./scripts/local-mods.sh build server "$PWD/dist/local-mods" --skip-tests   # from the repo root
./scripts/local-mods.sh verify server "$PWD/dist/local-mods"
```

`verify` checks compiled classes and the environment; a manifest row whose refmap column is `-` needs no refmap, which is every 26.x mod.

## 3. Self-test on a real 26.3 server

```bash
./gradlew :server:runServer -PselfTest=/tmp/st.txt                        # every section; a line starting FAIL fails
./gradlew :server:runServer -PselfTest=/tmp/st.txt -PselfTestOnly=zones   # one section
```

Sections: `exits`, `commands`, `materials`, `biomes`, `render`, `firewall`, `mixins`, `roll`, `rollperf`, `structures`, `terrain`, `zones`. The persistence check is two boots: `-PselfTestPersist=write`, then `-PselfTestPersist=verify`. Fixtures live in `server/src/selftest/` and never reach the release jar. `-Pdefinitions=<dir>` and `-Pbridges=<dir>` point `runServer` at other folders; by default it reads `config/custom-dimensions/dimensions/` and `bridges/`. `-PmeasureStructures=<file>` measures every structure and stops.

## 4. Survey and rig

On a running dev server, `/customdim survey <slug>` writes a dossier; `mods/custom-dimensions-client-26/scripts/rig.sh` shoots a real 26.3 client under Xvfb (portal variants, or `biomes <shot list>`). The loop: the `custom-dimension-authoring` skill § The loop.

## 5. The whole stack

`./dev link` a consumer to this checkout once, then `./dev up`: it builds and installs the manifest's jars into `data/mods/` and recreates mc. `./dev restart mc` installs nothing. For the release's own boot gate, `scripts/smoke.sh prepare && scripts/smoke.sh up && scripts/smoke.sh check` (Docker; the `local-stack-testing` skill).

## 6. Ship

`release.yml` builds each manifest row through `local-mods.sh` (passing the release as `-Pmod_version`), stages `dist/local-mods/<jar>`, and `build-stack-bundle.sh` packs it as `stack/local-mods/`. `deploy.sh` step 8b and `dev-up.sh` copy it into `data/mods/` while mc is stopped. Never hand-place a jar in a consumer's `data/mods/`.

## Failure signatures

| Symptom | Cause |
| --- | --- |
| Boot hangs after mod init | An accessor mixin missing from `customdimensions.mixins.json` ([T175](../../../TROUBLESHOOTING.md#t175)) |
| `custom-dimensions refuses to start: N findings of another worldgen owner` | The firewall found a mod shaping terrain, biomes or surfaces, or an unbridged structure namespace ([T142](../../../TROUBLESHOOTING.md#t142)) |
| Server dies with a watchdog after a long command | Work on the server thread over 60 s ([T120](../../../TROUBLESHOOTING.md#t120)) |
| A new field or fix has no effect on the client | The rebuilt client jar never reached the running client ([T112](../../../TROUBLESHOOTING.md#t112)) |
| A setting changed in `config/` reads as the old value locally | `data/config/` is skip-if-exists: `./dev refresh-config` ([T78](../../../TROUBLESHOOTING.md#t78)) |
