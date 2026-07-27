---
name: server-mod-management
description: Add, remove, pin, hold, or troubleshoot server-side Fabric mods and datapacks for the Adventure Server platform (config/modrinth-mods.txt) or a consumer repo (overlay/mods-extra.txt, overlay/mods-remove.txt). Covers the mandatory Modrinth dependency checklist, the never-optional library list (fabric-api, yungs-api, moonlight, lithostitched, fabric-language-kotlin), the two-place rule for config-bearing mods (config/<modname>/ plus a COPY line in docker/defaults-seed/Dockerfile), version holds in modpack/adventure.mrpack.json, and the offline-boot delivery model (seed resolves pins once, sync-mods.sh fetches only what's missing, mc makes zero Modrinth calls at boot). Use when: adding a mod to either mod list, removing a default mod, running ./scripts/pin-mod-versions.sh, resolving "Mixin apply ... failed" or a Fabric "FormattedException" listing missing dependencies, diagnosing a Modrinth "429 Too Many Requests" crash-loop in the seed container, or a mod's config never reaching a consumer server.
---

# Server Mod Management

This is CONTRIBUTING.md's own warning: _"the most common type of change and the one most likely to break things."_ The knowledge is scattered across `AGENTS.md`, `CONTRIBUTING.md`, `README.md`, and four scripts — this skill collects it into one path so you don't ship a mod whose config never reaches consumers, or bump a pin that's meant to be held.

**Out of scope**: client mods, resource/shader packs (client-side only, `modpack/adventure.mrpack.json` `_clientMods`/`_resourcePacks`/`_shaderPacks`), in-house Fabric mods under `mods/` (see `mods/AGENTS.md`), and which deploy tier a change triggers (see the deploy-pipeline skill — this skill only tells you what to edit).

## Which repo am I in? (get this wrong and the mod silently never ships)

|  | Platform repo (this one) | Consumer repo (e.g. `elfydd`) |
| --- | --- | --- |
| Add a mod | `config/modrinth-mods.txt` | `overlay/mods-extra.txt` |
| Remove a default mod | Delete/comment the line in `config/modrinth-mods.txt` | `overlay/mods-remove.txt` (slug per line, must match a slug in the platform defaults) |
| Test locally | `cp .env.example .env && ./scripts/dev-up.sh` | `./dev up` |
| Re-pin | `./scripts/pin-mod-versions.sh --apply` | `./dev pin` (wraps `pin-mod-versions.sh --file overlay/mods-extra.txt`) |
| Ship it | Push to `main` (builds images) **then cut a release** (`gh workflow run release.yml -f version=vX.Y.Z`) — a bare push never reaches a running consumer server | Push to `main` — `overlay/mods-extra.txt`/`mods-remove.txt` match `FULL_PATTERNS` in `deploy-reusable.yml`, so it's a full deploy immediately |

**The trap this table exists to prevent**: pushing a platform mod-list change to `main` builds Docker images but changes nothing on any running server. Consumers pin `STACK_VERSION` to a release tag (`v1`, `v2`, ...); the mod only reaches them once you cut the next release. Contrast this with a consumer repo, where the same kind of edit deploys on the next push.

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

Every `required` dependency must already be in the pack or be added alongside it. **Never mark these libraries optional** — they're relied on across dozens of mods: `fabric-api`, `yungs-api`, `moonlight`, `lithostitched`, `fabric-language-kotlin`.

`AGENTS.md`/`CONTRIBUTING.md` also list `balm` in that never-optional set, but check `config/modrinth-mods.txt` before assuming it's there: it's currently commented out (_"removed: only dep was waystones+netherportalfix (both removed)"_) — there is no live `balm` pin to protect. Only re-add it if a new mod actually needs it.

Verify the resolved version genuinely targets 1.21.1 — Modrinth metadata isn't always honest (`extra_enchantments` claimed 1.21.1 support but shipped 1.21.2 registry keys; it's disabled in the mod list for exactly this reason).

Full worked recipes and the `check-modrinth-compat.sh` / `pin-mod-versions.sh` flag reference: `references/dependency-resolution.md`.

## Step 2: pin the version

```bash
./scripts/pin-mod-versions.sh --apply                        # platform: config/modrinth-mods.txt
./scripts/pin-mod-versions.sh --file overlay/mods-extra.txt   # consumer: in place, review via git diff
```

Pinning writes `slug:versionId` — never `slug:latest`. **Inline comments on a mod line are destroyed on re-pin** (the line is rewritten bare); comment-only lines above a mod survive. Put your reasoning on the line above, never trailing on the mod line.

## The delivery model (the mental model you already have is wrong here)

The instinct is "the server downloads mods at boot." It doesn't, and assuming it does is what causes a Modrinth `429 Too Many Requests` crash-loop the moment a mod list changes:

1. `config/modrinth-mods.txt` (defaults) merges with the consumer's `overlay/mods-remove.txt` and `overlay/mods-extra.txt` inside the `defaults-seed` container (`docker/defaults-seed/seed.sh`).
2. `resolve-mods.py` resolves every `slug:versionId` pin to a direct CDN URL **once** — version IDs are immutable on Modrinth, so the result is cached forever (`.resolve-cache.json`, plus a repo-committed `config/modrinth-resolve-cache.json` baked into the image). A warm cache makes this zero API calls.
3. `scripts/sync-mods.sh` runs host-side, between the seed finishing and `mc` starting, and downloads only the files `data/mods/` is missing.
4. `mc`'s `MODS_FILE`/`DATAPACKS_FILE` are **empty by default** — itzg's image makes no per-URL freshness check and no Modrinth API call at boot, ever.

A failed **required** resolution fails the seed and blocks the boot loudly — that's intentional; booting without a worldgen mod corrupts chunks. A failed **optional** (`?` suffix) resolution just skips that entry and logs a warning.

Full per-environment mechanics (local `./dev up`, production `deploy.sh`, CI): `references/mod-delivery-pipeline.md`.

## The two-place rule for config-bearing mods

If a mod reads its own config file, you must touch **two places** or consumers get the mod's own auto-generated defaults forever, with no error:

1. Config file(s) in `config/<modname>/` (usually a directory; some mods read a bare path — e.g. Tectonic reads `config/tectonic.json` directly, verify against the mod's own docs/jar, don't guess).
2. A matching `COPY` line in `docker/defaults-seed/Dockerfile` (e.g. `COPY config/tectonic.json /defaults/config/tectonic.json`). Without this line the file never reaches the seed image, so it never reaches any consumer.

This is a **silent failure** — nothing crashes, no warning, the mod just runs on its own generated defaults on every consumer server indefinitely.

## Version holds

Holds live in `modpack/adventure.mrpack.json` → `_holds` (keyed by slug, value is the reason). Both of `pin-mod-versions.sh`'s re-pin loops read that one map — the server list and the client manifest alike — and so does the weekly `mod-updates.yml`. It sits at the manifest's top level rather than inside `_clientMods` because a hold is a statement about a mod, not about a distribution channel. Current holds:

| Slug | Held at | Why |
| --- | --- | --- |
| `c2me-fabric` | `0.4.0-alpha.0.19` (`GC7ouKxZ`) | `0.4.0-alpha.0.21` wedges fresh-world creation — the server thread parks forever in `getChunkBlocking` while locating spawn (verified via thread dump, 2026-07-22) |
| `critters-and-companions` | `1.21.1-2.4.1` (`YuM4Jtu5`) | `2.6.x` claims 1.21.1 but needs a newer Architectury than the newest 1.21.1 build provides — `AbstractMethodError` at boot killed the v3.2.0 smoke test (2026-07-22) |

`c2me-fabric` is a _server-only_ mod — it lives in `config/modrinth-mods.txt`, not in `_clientMods.required`/`.optional` — which is exactly why holds are read by both loops: `./scripts/pin-mod-versions.sh --apply`, and the Monday `mod-updates.yml` that runs it unattended, leave it on its held pin and print `c2me-fabric - HELD`.

Never bump a held slug manually; remove the hold only once its stated blocker clears. Read the live table from `modpack/adventure.mrpack.json` rather than trusting this copy — holds come and go.

**Era-pairs**: Xaero's minimap and world map share code and must be bumped together, whether or not either is held.

## Removal is not symmetric with addition

|  | Platform | Consumer |
| --- | --- | --- |
| Remove | Delete/comment the line in `config/modrinth-mods.txt` | Add the slug to `overlay/mods-remove.txt` (one per line; must match a slug present in the platform defaults, or the seed logs a warning and ignores it) |

Either way, **stale jars are pruned automatically**: `deploy.sh` (production) and `dev-up.sh` (local) delete any `data/mods/*.jar` not listed in the seed's `mods-manifest.txt` for that boot. Two exemptions: jars listed in `data/mods/.local-mods-manifest` (the in-house mods just copied from `stack/local-mods/`) and any jar whose name also exists in `local-mods/`. **A hand-added jar in `data/mods/` with neither exemption gets deleted on the next boot** — ship it via `overlay/mods-extra.txt` (goes through the normal resolve pipeline) or `local-mods/` (in-house mods only), never by dropping a file into `data/mods/` directly.

## Traps (read before you touch a mod list)

1. **Hand-added jars in `data/mods/` are pruned.** See "Removal" above. Ship via `overlay/mods-extra.txt` or `local-mods/`.
2. **Modrinth metadata lies.** `extra_enchantments` claimed 1.21.1 and shipped 1.21.2 registry keys — verify the resolved version's actual target, don't trust the tag.
3. **`pin-mod-versions.sh` destroys inline comments on re-pin**; comment-only lines survive. Comment above the mod, never trailing on its line.
4. **Worldgen/dimension mods must be present from chunk zero.** Adding one to an existing world causes visible chunk borders — Terralith, Incendium, and Nullscape all generate custom terrain.
5. **`MODRINTH_PROJECTS` must never come back.** It made itzg re-resolve ~160 versions through the live API on every sync-enabled boot and 429-crash-looped `mc` whenever the mod list changed — this is exactly why the seed/resolve-cache/sync-mods pipeline exists.
6. **A failed required resolution fails the seed and blocks the boot — loudly, on purpose.** Booting without a worldgen mod corrupts chunks; don't try to make this fail softer.
7. **The mod mirror and packwiz index are build output.** `modpack/dist/mods/` and `modpack/dist/packwiz/` are generated and pruned by `build-modpack.sh` — never hand-edit them.
8. **Holds don't protect the server mod list**, only the client manifest re-pin. See "Version holds" above — a held server-only mod (`c2me-fabric`) depends entirely on human review of the weekly PR diff.
9. **macOS BSD tools trip on this workflow in two specific ways**: `grep -P` doesn't exist (use `grep -oE`), and BSD `grep -E` doesn't support `\s` either — `[[:space:]]` or a POSIX character class is what actually works cross-platform. Verified directly while auditing `config/modrinth-mods.txt`: a `\s`-based count silently returned the wrong number with no error.
10. **CONTRIBUTING.md's config-sync section is stale**: it names `MC_PATTERNS` in `.github/workflows/deploy.yml`. The real trigger list is `FULL_PATTERNS` in `.github/workflows/deploy-reusable.yml` (currently `^overlay/config/|^overlay/mods-extra\.txt$|^overlay/mods-remove\.txt$`). Go by the workflow file, not the doc, if they ever disagree again.

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
