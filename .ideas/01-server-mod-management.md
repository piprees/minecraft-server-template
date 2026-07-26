# Skill brief: `server-mod-management`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

`CONTRIBUTING.md:75` calls it directly: *"This is the most common type of change and the one most likely to break things."* The knowledge needed to do it correctly is currently spread across six files:

- the dependency checklist — `AGENTS.md:230-240` **and** `CONTRIBUTING.md:77-105` (two copies, subtly different)
- version holds — `AGENTS.md:242`
- the two-place config rule — `README.md:246-255` (§ Config sync), *not* in either mods section
- the offline boot / seed-resolution model — `AGENTS.md:120-122` (trap 4) and `AGENTS.md:296-302`
- consumer vs platform paths — `examples/consumer/AGENTS.md:110-112`
- the pruning rule that deletes hand-added jars — `AGENTS.md:122`

An agent that reads only the `## Mods` section of `AGENTS.md` will add a mod, forget the `COPY` line in `docker/defaults-seed/Dockerfile`, and ship a config that never reaches consumers. That has a name in this repo and no single home.

## Scope

**In:** server-side mods and datapacks. Adding, removing, pinning, re-pinning, holding, resolving dependencies, the weekly update PR, where the change lands per repo type, and how the jar actually arrives on disk at boot.

**Out:** client mods, resource packs, shader packs → `client-pack-authoring` (brief 02). In-house Fabric mods → `fabric-mod-development` (brief 05). Which deploy tier the change triggers → `deploy-pipeline-operations` (brief 03), cross-reference only.

## Source material — read all of these while building

| File | What to mine |
| --- | --- |
| `AGENTS.md` §§ Mods, Config sync, traps 4 & 15 | Dependency checklist, holds, pinning, sync model, the `mods-manifest.txt` prune |
| `CONTRIBUTING.md:73-105` | The second copy of the checklist — reconcile the two, do not ship both |
| `README.md` § Add or remove mods, § Config sync | The edit → then table; the `defaults-seed` `COPY` requirement |
| `config/modrinth-mods.txt` | The real format. Count the `?` optional entries and `datapack:` entries and state the counts |
| `scripts/pin-mod-versions.sh` (header, lines 1-25) | `--apply`, `--version`, `--file`, the inline-comment destruction rule |
| `scripts/check-updates.sh` (header) | Three run modes, the dedupe ledger, `--fast` |
| `scripts/sync-mods.sh` (header) | Host-side fetch between seed and mc; percent-decoding; exit-non-zero-on-missing |
| `docker/defaults-seed/resolve-mods.py` | How pins become CDN URLs; what a failed required resolution does |
| `docker/defaults-seed/seed.sh` | Overlay merge order: `mods-remove.txt` then `mods-extra.txt` against the defaults list |
| `docker/defaults-seed/Dockerfile` | The `COPY config/<modname>/` lines — the second place a config mod must be registered |
| `.github/workflows/mod-updates.yml` + `scripts/build-mod-update-report.py` | The Monday PR, the `# FIXME: no 1.21.x build` marker |
| `modpack/adventure.mrpack.json` → `_clientMods.holds` | Holds live in the *client* manifest but govern `pin-mod-versions.sh` for both lists |
| `docs/troubleshooting.md:48-57` | Startup failure table |

## Required structure

```
server-mod-management/
├── SKILL.md
└── references/
    ├── dependency-resolution.md   # the Modrinth API recipes, worked end to end
    ├── mod-delivery-pipeline.md   # pin → seed resolve → sync-mods → data/mods, per environment
    └── mod-list-formats.md        # modrinth-mods.txt, mods-extra.txt, mods-remove.txt, datapack:, ?, holds
```

### SKILL.md must contain

1. **A "which repo am I in" fork at the very top.** Platform (`config/modrinth-mods.txt`, push, cut a release) vs consumer (`overlay/mods-extra.txt`, `./dev up` or push). Getting this wrong is the most common failure and it is invisible until deploy.
2. **The dependency checklist as the mandatory first step**, with both `curl` recipes runnable as written. Reconcile the `AGENTS.md` and `CONTRIBUTING.md` copies into one. State the never-optional library list verbatim: `fabric-api`, `yungs-api`, `moonlight`, `balm`, `lithostitched`, `fabric-language-kotlin`.
3. **A delivery-model section** — because the mental model an agent brings ("the server downloads mods at boot") is *wrong here* and the wrongness causes the 429 crash-loop. Boots make zero network requests; `MODS_FILE`/`DATAPACKS_FILE` are empty by default; the seed resolves pins to immutable CDN URLs once; `sync-mods.sh` fetches only what `data/mods/` lacks.
4. **The two-place rule for config-bearing mods**, stated as a checklist item, not a footnote: config files in `config/<modname>/` **and** a `COPY` line in `docker/defaults-seed/Dockerfile`. Note the flat-path exception (Tectonic reads `config/tectonic.json`) and that you verify against the jar, not from memory.
5. **Version holds.** Where they live, why `xaero-worldmap` is pinned at 1.41.2, the era-pair rule (minimap + world map together), and that `pin-mod-versions.sh` and `mod-updates.yml` both respect them.
6. **Removal**, which is not symmetric with addition: `overlay/mods-remove.txt` for consumers, deleting the line for platform, and the fact that stale jars are pruned against `mods-manifest.txt` while `local-mods/` jars are exempt.

### Traps to capture (all real, all documented)

1. Hand-added jars in `data/mods/` are pruned by `deploy.sh`/`dev-up.sh`. Ship via `overlay/mods-extra.txt` or `local-mods/`.
2. Modrinth metadata lies — `extra_enchantments` claimed 1.21.1 and shipped 1.21.2 registry keys. Verify the resolved version's actual target.
3. `pin-mod-versions.sh` rewrites mod lines as bare `slug:versionId`. **Inline comments are destroyed**; comment-only lines survive. Comment *above* the mod.
4. Adding a worldgen or dimension mod to an existing world causes visible chunk borders. They must be present from chunk zero.
5. `MODRINTH_PROJECTS` is gone and must not come back — it re-resolved ~160 versions through the API on every sync boot and 429-crash-looped mc.
6. A failed *required* resolution fails the seed and blocks the boot loudly. That is intended — booting without a worldgen mod corrupts chunks.
7. The mod mirror (`modpack/dist/mods/`) and packwiz index are build output. Never hand-edit. (Cross-ref brief 02.)

### Validation section

Loud failures: seed container exits non-zero; mc crash-loop with `Mixin apply … failed`; Fabric `FormattedException` listing missing deps.

Silent failures: a config-bearing mod added without the `Dockerfile` `COPY` (consumers get the mod's own defaults, forever); a mod added to `mods-extra.txt` whose dependency is already present *locally* but not in the platform list.

Give runnable checks:

```bash
./scripts/check-modrinth-compat.sh --version 1.21.1 --loader fabric
./scripts/test-scripts.sh --quick
./dev up && docker logs mc --tail 80 | grep -iE 'mixin apply|error|missing'
```

## Anti-scope reminder for the builder

Do not write a tutorial on Modrinth. Do not explain what a Fabric mod is. Every line should be something an agent would get *wrong* without it.

## Done when

- An agent handed "add `mod-x` to the server" produces: dependency resolution output, the correct file edited for the repo it is in, a pinned `slug:versionId`, a `COPY` line if the mod has config, and a local boot check — without reading `AGENTS.md`.
- The skill states the never-optional library list, the two-place rule, and the offline boot model in its first 100 lines.
