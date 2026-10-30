---
name: server-mod-management
description: Add, remove, pin, hold, or troubleshoot server-side Fabric mods and datapacks for the Adventure Server platform (config/modrinth-mods.txt) or a consumer repo (overlay/mods-extra.txt, overlay/mods-remove.txt). Covers the Modrinth dependency-checklist recipes, the two-place rule for config-bearing mods (config/<modname>/ plus a COPY line in docker/defaults-seed/Dockerfile), version holds in modpack/adventure.mrpack.json, and the offline-boot delivery model (seed resolves pins once, sync-mods.sh fetches only what's missing, mc makes zero Modrinth calls at boot). Use when: adding a mod to either mod list, removing a default mod, running ./scripts/pin-mod-versions.sh, resolving "Mixin apply ... failed" or a Fabric "FormattedException" listing missing dependencies, diagnosing a Modrinth "429 Too Many Requests" crash-loop in the seed container, or a mod's config never reaching a consumer server.
---

# Server Mod Management

The commands for changing a mod list. The rules, their reasons and the versioning contract are in [`DEPENDENCIES.md`](../../../DEPENDENCIES.md); read [§ Adding a mod](../../../DEPENDENCIES.md#adding-a-mod) before adding anything.

**Out of scope**: client mods, resource/shader packs (client-side only, `modpack/adventure.mrpack.json` `_clientMods`/`_resourcePacks`/`_shaderPacks`), in-house Fabric mods under `mods/` (see `mods/AGENTS.md`), and which deploy tier a change triggers (see the deploy-pipeline skill — this skill only tells you what to edit).

## Which repo am I in? (get this wrong and the mod silently never ships)

|  | Platform repo (this one) | Consumer repo (e.g. `elfydd`) |
| --- | --- | --- |
| Add a mod | `config/modrinth-mods.txt` | `overlay/mods-extra.txt` |
| Remove a default mod | Delete/comment the line in `config/modrinth-mods.txt` | `overlay/mods-remove.txt` (slug per line, must match a slug in the platform defaults) |
| Test locally | `cp .env.example .env && ./scripts/dev-up.sh` | `./dev up` |
| Re-pin | `./scripts/pin-mod-versions.sh --apply` | `./dev pin` (wraps `pin-mod-versions.sh --file overlay/mods-extra.txt`) |
| Ship it | Push to `main` (builds images) **then cut a release** (`gh workflow run release.yml -f version=vX.Y.Z`) — a bare push never reaches a running consumer server | Push to `main` — `overlay/mods-extra.txt`/`mods-remove.txt` match `FULL_PATTERNS` in `deploy-reusable.yml`, so it's a full deploy immediately |

**The trap this table exists to prevent**: pushing a platform mod-list change to `main` builds Docker images but changes nothing on any running server. Consumers pin `STACK_VERSION` to a release tag (`v4`, `v5`, ...); the mod only reaches them once you cut the next release, versioned per [DEPENDENCIES.md § Versioning contract](../../../DEPENDENCIES.md#versioning-contract). Contrast this with a consumer repo, where the same kind of edit deploys on the next push.

## Step 1: the dependency checklist (mandatory, every time)

Before adding any mod, resolve what it depends on for 1.21.1 Fabric:

```bash
# 1. List the mod's dependencies
curl -s "https://api.modrinth.com/v2/project/{slug}/version?game_versions=%5B%221.21.1%22%5D&loaders=%5B%22fabric%22%5D" \
  | python3 -c "import sys,json; [print(f'  {d[\"project_id\"]} ({d[\"dependency_type\"]})') for v in json.load(sys.stdin)[:1] for d in v.get('dependencies',[])]"

# 2. Resolve each project_id to a slug
curl -s "https://api.modrinth.com/v2/project/{project_id}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['slug'], d['title'])"
```

Every `required` dependency must already be in the pack or be added alongside it. Libraries are never optional; the list is [DEPENDENCIES.md § Libraries](../../../DEPENDENCIES.md#libraries).

Verify the resolved version genuinely targets 1.21.1: Modrinth's `game_versions` metadata can be wrong, so check the version detail and the jar.

Full worked recipes and the `check-modrinth-compat.sh` / `pin-mod-versions.sh` flag reference: `references/dependency-resolution.md`.

## Step 2: pin the version

```bash
./scripts/pin-mod-versions.sh --apply                        # platform: config/modrinth-mods.txt
./scripts/pin-mod-versions.sh --file overlay/mods-extra.txt   # consumer: in place, review via git diff
```

Pinning writes `slug:versionId` — never `slug:latest`. **Inline comments on a mod line are destroyed on re-pin** (the line is rewritten bare); comment-only lines above a mod survive. Put your reasoning on the line above, never trailing on the mod line.

## The delivery model (the mental model you already have is wrong here)

The server never downloads mods from the Modrinth API at boot ([T4](../../../TROUBLESHOOTING.md#t4)):

1. `config/modrinth-mods.txt` (defaults) merges with the consumer's `overlay/mods-remove.txt` and `overlay/mods-extra.txt` inside the `defaults-seed` container (`docker/defaults-seed/seed.sh`).
2. `resolve-mods.py` resolves every `slug:versionId` pin to a direct CDN URL **once** — version IDs are immutable on Modrinth, so the result is cached forever (`.resolve-cache.json`, plus a repo-committed `config/modrinth-resolve-cache.json` baked into the image). A warm cache makes this zero API calls.
3. `scripts/sync-mods.sh` runs host-side, between the seed finishing and `mc` starting, and downloads only the files `data/mods/` is missing.
4. `mc`'s `MODS_FILE`/`DATAPACKS_FILE` are **empty by default** — itzg's image makes no per-URL freshness check and no Modrinth API call at boot, ever.

A failed **required** resolution fails the seed and blocks the boot loudly — that's intentional; booting without a worldgen mod corrupts chunks. A failed **optional** (`?` suffix) resolution just skips that entry and logs a warning.

Full per-environment mechanics (local `./dev up`, production `deploy.sh`, CI): `references/mod-delivery-pipeline.md`.

## Config-bearing mods

A mod that reads its own config needs the file in `config/<modname>/` **and** a `COPY` line in `docker/defaults-seed/Dockerfile`, or every consumer silently runs the mod's own defaults: [AGENTS.md § Config sync](../../../AGENTS.md#config-sync). Flat-path exceptions: `references/mod-list-formats.md`.

## Version holds

Rules: [DEPENDENCIES.md § Holds](../../../DEPENDENCIES.md#holds). Holds live in `modpack/adventure.mrpack.json` → `_holds` (slug → reason). Both of `pin-mod-versions.sh`'s re-pin loops read that map, so a held slug keeps its pin in `config/modrinth-mods.txt` and in the client manifest alike, and the run logs it as `HELD`. Read the live object for the current holds.

## Removal is not symmetric with addition

|  | Platform | Consumer |
| --- | --- | --- |
| Remove | Delete/comment the line in `config/modrinth-mods.txt` | Add the slug to `overlay/mods-remove.txt` (one per line; must match a slug present in the platform defaults, or the seed logs a warning and ignores it) |

Either way, **stale jars are pruned automatically**: `deploy.sh` (production) and `dev-up.sh` (local) delete any `data/mods/*.jar` not listed in the seed's `mods-manifest.txt` for that boot. Two exemptions: jars listed in `data/mods/.local-mods-manifest` (the in-house mods just copied from `stack/local-mods/`) and any jar whose name also exists in `local-mods/`. **A hand-added jar in `data/mods/` with neither exemption gets deleted on the next boot** — ship it via `overlay/mods-extra.txt` (goes through the normal resolve pipeline) or `local-mods/` (in-house mods only), never by dropping a file into `data/mods/` directly. To get an unreleased in-house build into `local-mods/` locally, `./dev link` the consumer to a platform checkout; the link symlinks the checkout's built jars into that slot (`.claude/skills/local-stack-testing/SKILL.md` § Linked local development).

## Traps (read before you touch a mod list)

1. **Hand-added jars in `data/mods/` are pruned.** See "Removal" above. Ship via `overlay/mods-extra.txt` or `local-mods/`.
2. **Modrinth metadata can be wrong.** A mod can claim 1.21.1 and ship another version's registry keys — verify the resolved version's actual target, don't trust the tag.
3. **`pin-mod-versions.sh` destroys inline comments on re-pin**; comment-only lines survive. Comment above the mod, never trailing on its line.
4. **Worldgen/dimension mods must be present from chunk zero** ([DEPENDENCIES.md § Worldgen, structure and dimension mods](../../../DEPENDENCIES.md#worldgen-structure-and-dimension-mods)).
5. **`MODRINTH_PROJECTS` must never come back** ([T4](../../../TROUBLESHOOTING.md#t4)).
6. **A failed required resolution fails the seed and blocks the boot — loudly, on purpose.** Booting without a worldgen mod corrupts chunks; don't try to make this fail softer.
7. **The mod mirror and packwiz index are build output** ([T16](../../../TROUBLESHOOTING.md#t16)).
8. **Holds cover both lists.** A held slug stays on its pin in `config/modrinth-mods.txt` and the client manifest; only a hand edit moves it, and that edit is forbidden.
9. **macOS BSD tools:** no `grep -P` ([P3](../../../TROUBLESHOOTING.md#p3)), and BSD `grep -E` has no `\s` — use `[[:space:]]`. A `\s`-based count returns the wrong number with no error.

## Validation

**Loud failures** (you'll see these immediately): seed container exits non-zero and blocks the boot; `mc` crash-loop with `Mixin apply ... failed` (usually a broken/incompatible jar); Fabric `FormattedException` listing missing dependencies at startup.

**Silent failures** (nothing crashes, nothing warns): a config-bearing mod added without the `Dockerfile` `COPY` line (consumers get the mod's own defaults forever); a mod added to `overlay/mods-extra.txt` whose dependency is present on your local test rig but missing from the platform's actual shipped list.

```bash
./scripts/check-modrinth-compat.sh --version 1.21.1 --loader fabric   # every mod vs Modrinth, no boot required
./scripts/test-scripts.sh --quick                                     # shellcheck, py_compile, compose config, yamllint
./dev up && docker logs mc --tail 80 | grep -iE 'mixin apply|error|missing'
```

## References

- `references/dependency-resolution.md` — full Modrinth API recipes worked end to end, `pin-mod-versions.sh`/`check-modrinth-compat.sh` flag reference
- `references/mod-delivery-pipeline.md` — pin → seed resolve → sync-mods → `data/mods/`, per environment (local/production/CI), with the exact scripts and exemptions involved
- `references/mod-list-formats.md` — `modrinth-mods.txt`, `mods-extra.txt`, `mods-remove.txt` formats, the `?`/`datapack:` markers, and real counts from the shipped list
