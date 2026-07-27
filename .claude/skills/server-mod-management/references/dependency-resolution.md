---
title: Dependency Resolution
description: Modrinth API recipes for resolving a new mod's dependencies, worked end to end, plus the pin-mod-versions.sh and check-modrinth-compat.sh flag reference
tags: [modrinth, dependencies, pin-mod-versions, check-modrinth-compat, fabric]
---

# Dependency Resolution

## The checklist, worked end to end

Say you're adding a mod with slug `some-new-mod`.

**1. List its dependencies for 1.21.1 Fabric:**

```bash
curl -s "https://api.modrinth.com/v2/project/some-new-mod/version?game_versions=%5B%221.21.1%22%5D&loaders=%5B%22fabric%22%5D" \
  | python3 -c "import sys,json; [print(f'  {d[\"project_id\"]} ({d[\"dependency_type\"]})') for v in json.load(sys.stdin)[:1] for d in v.get('dependencies',[])]"
```

This hits the newest version matching both filters and prints each dependency's Modrinth `project_id` (not a human-readable slug yet) alongside its `dependency_type` (`required`, `optional`, `incompatible`, or `embedded`). If the query returns nothing, there is no 1.21.1 Fabric build at all — stop here, the mod can't be added yet.

**2. Resolve each `project_id` to a slug and title:**

```bash
curl -s "https://api.modrinth.com/v2/project/{project_id}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['slug'], d['title'])"
```

Run this once per `project_id` printed in step 1. Cross-check every `required` result against `config/modrinth-mods.txt` (platform) or the merged view (`config/modrinth-mods.txt` + `overlay/mods-extra.txt`, consumer) — anything missing needs adding alongside the new mod, in the same PR/commit.

**3. Never mark these optional**, no matter what step 1 reports for them: `fabric-api`, `yungs-api`, `moonlight`, `lithostitched`, `fabric-language-kotlin`. They're transitively required by dozens of shipped mods even when a specific dependency scan doesn't flag them for the mod you're adding.

**4. Verify the resolved version's actual target**, don't trust the `game_versions` tag on the project page. Modrinth metadata has been wrong before — `extra_enchantments` claimed 1.21.1 support but every version actually shipped 1.21.1's incompatible sibling (1.21.2 registry keys), and it's commented out of `config/modrinth-mods.txt` for exactly that reason. If a mod behaves oddly after adding, re-fetch its version detail directly:

```bash
curl -s "https://api.modrinth.com/v2/project/{slug}/version/{versionId}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['game_versions'], d['loaders'])"
```

**5. Pin it:**

```bash
./scripts/pin-mod-versions.sh --apply                        # platform
./scripts/pin-mod-versions.sh --file overlay/mods-extra.txt   # consumer, or: ./dev pin
```

## `pin-mod-versions.sh` flag reference

Source: `scripts/pin-mod-versions.sh` header + argument parsing.

| Flag | Effect |
| --- | --- |
| (none) | Uses `MC_VERSION` from `.env` (default `1.21.1`); writes `config/modrinth-mods.pinned.txt` for review, does not touch the real file |
| `--version <ver>` | Override the target MC version (e.g. `--version 1.22`) |
| `--apply` | Also overwrite `config/modrinth-mods.txt` with the resolved output |
| `--file <path>` | Re-pin an arbitrary list **in place** — this is how consumer overlays (`overlay/mods-extra.txt`) get re-pinned; review the result with `git diff` |

Fallback chain: it tries the exact target version first, then walks backwards through `1.21.x` down to `1.21` looking for a build. A mod with no match anywhere in that chain is left as-is with a `# FIXME: no 1.21.x build - <slug>` comment inserted above it — `build-mod-update-report.py`'s `FIXME_RE` (`#\s*FIXME:\s*no [\d.]+x? build - (\S+)`) picks these up and surfaces them in the weekly PR body under "Needs attention".

The same invocation also re-pins `modpack/adventure.mrpack.json` (`_clientMods.required`/`.optional`, plus `_resourcePacks`/`_shaderPacks`) using the same fallback chain. Slugs listed in the manifest's top-level `_holds` are skipped by this loop and by the server-list loop above it — one map, both lists.

**Inline comments on a mod line do not survive a re-pin** — every mod line is rewritten as bare `slug:versionId[?]`. Comment-only lines (starting with `#`) are preserved untouched. Put context on the line above the mod, not trailing on it — see the `carpet:f2mvlGrg` entry in `config/modrinth-mods.txt` for the pattern (a multi-line comment block above the pin, nothing after the pin itself).

## `check-modrinth-compat.sh` flag reference

Source: `scripts/check-modrinth-compat.sh`.

```bash
./scripts/check-modrinth-compat.sh                          # uses MC_VERSION from .env.example, loader fabric
./scripts/check-modrinth-compat.sh --version 1.21.1 --loader fabric
./scripts/check-modrinth-compat.sh -v 1.22 -l fabric
```

Read-only — makes no changes to any mod list. For every line in `config/modrinth-mods.txt` it checks, in order: does the project exist at all (404 → not found) → is there an exact match for the target version+loader (✓) → is there any build for that loader on a different MC version (~ nearby, lists the versions) → is there any build at all under a different loader (✗, lists the loaders that do exist). `datapack:` entries are listed but skipped (not checked via the mod-version API). Summarises pass/nearby/not-found/no-compatible-version counts at the end.

## Never-optional libraries: current status in the shipped list

All confirmed present in `config/modrinth-mods.txt` as of this writing: `fabric-api:aUrTRV7H`, `fabric-language-kotlin:bdhiINYC`, `moonlight:OtIOgMN8`, `lithostitched:JWtSqSeY`, `yungs-api:9aZPNrZC`. `balm` is **not** currently pinned (commented out — its only two dependents, `waystones` and `netherportalfix`, were both removed). Don't be surprised it's absent; re-add it only if a future mod genuinely needs it.
