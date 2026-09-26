# Dependencies

How this pack's dependencies are chosen, updated and released. Humans read it, and `dep-review.yml`'s reviewer follows it ([`.github/dep-review/PROMPT.md`](.github/dep-review/PROMPT.md)). Numbers live in [`.github/dep-review/policy.json`](.github/dep-review/policy.json); problems live in [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) and are linked by id, never restated. Commands, API recipes and flag references are in the [`server-mod-management`](.claude/skills/server-mod-management/SKILL.md) skill.

1. [What counts as a dependency](#what-counts-as-a-dependency)
2. [Ground rules](#ground-rules)
3. [Automation](#automation)
4. [Versioning contract](#versioning-contract)
5. [Looking up versions](#looking-up-versions)
6. [Ecosystem playbooks](#ecosystem-playbooks)
7. [Situation playbooks](#situation-playbooks)
8. [Mods with standing constraints](#mods-with-standing-constraints)
9. [Testing a dependency change locally](#testing-a-dependency-change-locally)
10. [For consumers](#for-consumers)
11. [Where the rest lives](#where-the-rest-lives)

## What counts as a dependency

Anything with a version this repo did not write.

| Dependency | Pinned in | Updated by |
| --- | --- | --- |
| Server mods | `config/modrinth-mods.txt` (`slug:versionId`) | `mod-updates.yml`, weekly |
| Modrinth datapacks | `config/modrinth-mods.txt` (`datapack:` lines) | By hand (the re-pin passes them through) |
| Client mods, resource packs, shader packs | `modpack/adventure.mrpack.json` (`_clientMods`, `_resourcePacks`, `_shaderPacks`) | `mod-updates.yml`, weekly |
| Holds on any of the above | `modpack/adventure.mrpack.json` (`_holds`) | Humans and the dep-review reviewer |
| Sidecar base images | `docker/*/Dockerfile` (`FROM`) | Dependabot `docker` |
| Compose images | `docker-compose.yml` (`image:`) and `.github/workflows/mirror-images.yml` | By hand |
| GitHub Actions | `.github/workflows/*.yml` (`uses:`) | Dependabot `github-actions` |
| Consumer scaffold workflows | `examples/consumer/.github/workflows/` | By hand |
| Discord bot Python packages | `scripts/requirements-discord-sync.txt` (`==`) | Dependabot `pip` |
| Template-only Python packages | `requirements-dev.txt`; kuma-init's inline `pip install` | By hand |
| Minecraft | `MC_VERSION` (compose default `1.21.1`) | Fixed |
| Fabric loader | `modpack/adventure.mrpack.json` (`dependencies.fabric-loader`); `mods/*/gradle.properties` (`loader_version`); the server's is not pinned (compose sets only `TYPE: FABRIC`) | By hand |
| In-house mod toolchain | `mods/*/gradle.properties`, `mods/*/build.gradle`, the Gradle wrapper, `mods/mise.toml` (Java 21) | By hand |
| Dev tools | `mise.toml` (Python 3.13; shellcheck, yamllint, jq float on `latest`) | By hand |
| Other hand-pinned tools | Tailwind CLI in `mods/custom-dimensions/build-viewer-css.sh`; packwiz bootstrap in `scripts/build-modpack.sh` | By hand |

## Ground rules

- **Minecraft 1.21.1 on Fabric, nothing else.** Most mods only target it ([AGENTS.md § Fixed decisions](AGENTS.md#fixed-decisions-template-defaults)). No updater may leave 1.21.1; a Fabric build for another version is never a valid pin.
- **Worldgen is present from chunk zero.** Worldgen config is creation-time only ([D2](TROUBLESHOOTING.md#d2)), so a worldgen, structure or dimension mod joins a new world or not at all, and its updates ship only in a major.
- **The dependency checklist is mandatory** before adding any mod: [Adding a mod](#adding-a-mod).
- **Libraries are required, never optional.** A `?` on a library lets the boot skip it and crash every mod that needs it: [Libraries](#libraries).
- **Never take a version number from training data.** Look it up live: [Looking up versions](#looking-up-versions).
- **Never guess a config key or command syntax.** Fetch the mod's current docs (`npx ctx7@latest docs`, Modrinth, the mod's wiki) before editing a config or using a command.
- **Never bump a held slug by hand**: [Holds](#holds).
- **Back up before a version change reaches production** ([AGENTS.md safety rule 3](AGENTS.md#safety-rules)).

## Automation

### The weekly mod re-pin

`mod-updates.yml` runs every Monday at 06:00 UTC (or on `gh workflow run mod-updates.yml`). It re-pins every server mod, client mod, resource pack and shader pack to its newest 1.21.1 Fabric build with `scripts/pin-mod-versions.sh`, skipping held slugs, and `scripts/dep_review/partition.py` splits the result into two PRs:

| PR | Branch | Commit | Holds | Merged by |
| --- | --- | --- | --- | --- |
| Regular updates | `mod-updates/auto` | `chore: update N mod version(s)` | everything that is not worldgen | dep-review, when the policy allows |
| Next major | `mod-updates/next-major` | `feat(mods)!:` with a `BREAKING CHANGE:` footer | worldgen updates, the regenerated presets (`gen-structure-presets.py`, `gen-terrain-presets.py`) and the structure census (`gen-structure-groups.py`) | the month's first release train, or a human cutting a major |

A mod update is **worldgen** when the mod sits under a `policy.json` `never_automerge_sections` header in `config/modrinth-mods.txt`, is named in `worldgen_slugs`, or its jar's `data/*/worldgen`, `structure(s)`, `tags/worldgen` or biome-modifier files differ between the two versions. A mod added or removed, or a jar that cannot be compared, counts as worldgen.

**The `# === section ===` headers in `config/modrinth-mods.txt` are load-bearing.** `never_automerge_sections` matches them by exact text, so renaming a header, or filing a worldgen mod under a non-worldgen one, moves its updates onto the regular PR.

Merging the regular PR re-runs `mod-updates.yml`, so the next-major PR stays mergeable. Never merge the next-major PR except when cutting a major.

### Dependabot

`.github/dependabot.yml` opens grouped PRs every Monday, with a cooldown before a new version is proposed (longer for a major):

| Ecosystem | Directory | Group | Commit prefix |
| --- | --- | --- | --- |
| `github-actions` | `/` (root workflows only) | `actions-minor`: minor and patch together; majors separately | `ci` |
| `docker` | `/docker/*` | `base-images`: one PR per base image across every Dockerfile | `chore` |
| `pip` | `/scripts` | `python-minor`: minor and patch together | `chore` |

Not covered, so updated by hand: compose images, the consumer scaffold's workflows, `requirements-dev.txt`, kuma-init's inline pip install, the Gradle toolchain, the Fabric loader and `mise.toml`.

### dep-review and what merges automatically

`dep-review.yml` reviews every Dependabot PR and both mod PRs, then merges, closes, holds mods back, or labels the PR for a human. Setup, jobs, security and cost: [`docs/dependency-review.md`](docs/dependency-review.md).

`apply.py` merges a PR only when all of these hold, whatever the reviewer says, with every threshold read from `policy.json`:

- every update accepted, none riskier than `automerge_max_risk`;
- the quick checks, smoke test and Docker builds green, and every other check on the PR head completed green;
- no `blocking_flags` flag: pre-release, major version, wrong Minecraft version or loader, a missing required dependency, released fewer than `min_age_days` ago, or worldgen;
- no change under `never_automerge_paths`.

The reviewer can only make a PR less mergeable. On a mod PR one blocked mod blocks the whole PR, so the reviewer holds each blocked mod back instead. The next-major PR is never merged by dep-review; it is labelled `dep-review:ready-for-major` when everything but its worldgen status passes.

### Holds

A hold keeps a mod, client mod or pack at its current pin. Holds live in `modpack/adventure.mrpack.json` → `_holds`, slug → reason, and cover the server list and the client manifest alike: both re-pin loops in `pin-mod-versions.sh` skip a held slug and log it as `HELD`.

- **Who writes them:** a human, or the reviewer through `apply.py`, which edits `_holds` on the mod PR's branch and re-dispatches `mod-updates.yml`; `carry_holds.py` carries both branches' holds across the re-pin.
- **The reason names its release condition**, for example "pre-release; release when a release build for 1.21.1 ships".
- **Never bump a held slug by hand.** Remove the hold only when its stated condition has cleared: [Releasing a hold](#releasing-a-hold).
- **Holds are state.** Read the live `_holds`; no doc lists them.

### What reaches servers

A merge to `main` makes `publish.yml` rebuild the images tagged `latest`; deploys pull only released versions. Nothing reaches a consumer until a release: `release-train.yml` cuts one every Wednesday from what landed (a major waits for approval in the `release-major` environment), or a human dispatches `release.yml` ([AGENTS.md § Cutting a release](AGENTS.md#cutting-a-release-platform-repo-only)). The consumer's next push then resolves its pin to the new release and runs a full deploy ([README § Deploy to production](README.md#deploy-to-production)).

## Versioning contract

Every platform release is `vX.Y.Z`. Choose the bump from what changed since the last release, taking the highest class any change reaches:

| Bump | When |
| --- | --- |
| **Major** | A worldgen change: anything that makes new chunks generate differently, which is everything `partition.py` classes as worldgen. A mod removed: its blocks and items vanish from existing worlds. Anything a consumer must act on: a `.env` key, the overlay contract, a compose service or structure, reusable-workflow inputs or secrets. A Fabric loader or Java change. |
| **Minor** | Any client-side mod or pack change (players must update their pack). A new mod. A new feature. A dependency major that needs no consumer action. |
| **Patch** | Server-only bug-fix updates. Image patch bumps. Security fixes. |
| **No release** | CI, docs, tests and dev tooling. |

Minecraft stays 1.21.1. Changing it is outside this contract: it overturns a fixed decision and needs the user's agreement first ([Changing the Minecraft version](#changing-the-minecraft-version)).

Within a major, consumers keep the overlay contract (directory structure and merge semantics), the env contract (`.env` variables, GitHub environment vars and secrets) and the reusable workflow's inputs and secrets.

**Enforcement.** The commit type carries the class: `!` or a `BREAKING CHANGE:` footer is major, `feat:` minor, any other releasable type patch, and `ci`, `docs`, `test`, `style` and `chore` release nothing (`cliff.toml` `[bump]`). Land every change with the type its class needs; a breaking change that is not worldgen carries `!` or the footer itself. `scripts/check-release-version.sh`, the first job of `release.yml`, refuses a release that keeps the major over a breaking commit; the merged next-major PR is one.

**Cutting a major.** The month's first release train merges a next-major PR labelled `dep-review:ready-for-major`; by hand, merge it and dispatch `vN.0.0`. Re-enable the smoke test's render-check before a worldgen release ([AGENTS.md safety rule 12](AGENTS.md#safety-rules)). What a consumer must do goes in the breaking commit's `BREAKING CHANGE:` footer; git-cliff lists it under "Breaking changes" in the release notes.

## Looking up versions

Never take a version from training data. For every `image:` tag, `uses:` ref, Modrinth pin and pinned tool version:

- **GitHub releases:** `gh release list --repo <owner/repo> --limit 5 --json tagName,isLatest`. `--limit 1` returns the most recently _published_ release, not the highest version; a backported patch can publish after a newer minor.
- **Docker images:** `docker pull <image>:<tag>` before pinning. A GitHub release does not guarantee a registry tag; some projects stop pushing images while still cutting releases.
- **Modrinth:** the version API, filtered to `1.21.1` and `fabric` (recipe in the [`server-mod-management`](.claude/skills/server-mod-management/references/dependency-resolution.md) skill). `game_versions` metadata can be wrong: check the jar's own target.
- **Fabric:** `https://meta.fabricmc.net/v2/versions/loader` and `https://meta.fabricmc.net/v2/versions/yarn/1.21.1`.
- **Library docs and config keys:** Context7 (`npx ctx7@latest docs`), or the project's own docs or wiki.

## Ecosystem playbooks

### Adding a mod

1. **Resolve its dependencies** with the Modrinth recipe in the [`server-mod-management`](.claude/skills/server-mod-management/SKILL.md) skill. No 1.21.1 Fabric build means the mod cannot be added.
2. **Verify the jar's real target.** Modrinth metadata is sometimes wrong.
3. **Add every required dependency** in the same commit unless it is already in the pack. Libraries go in required, never optional ([Libraries](#libraries)).
4. **Worldgen, structure or dimension mod?** Chunk-zero only, filed under a worldgen section header, shipped in a major ([Worldgen, structure and dimension mods](#worldgen-structure-and-dimension-mods)). Incendium is the only Nether overhaul.
5. **Pin:** `./scripts/pin-mod-versions.sh --apply`. Put the reasoning (pairings, stripped mixins, why it is safe) in a comment block **above** the line, never trailing on it: a re-pin rewrites the line. That block is the `list_comment` the reviewer reads.
6. **Client parity:** a mod the client also needs goes into `_clientMods` at the same versionId, or every player is kicked at join ([T80](TROUBLESHOOTING.md#t80)). `scripts/check-client-parity.py` gates lint.
7. **Config:** the two-place rule in [AGENTS.md § Config sync](AGENTS.md#config-sync). Fetch the mod's docs for every key.
8. **Test locally** ([Testing a dependency change locally](#testing-a-dependency-change-locally)), then release per the [versioning contract](#versioning-contract): a new mod is a minor, a worldgen mod a major.

Mods reach the server through the seed, never through the Modrinth API at boot ([T4](TROUBLESHOOTING.md#t4)).

### Worldgen, structure and dimension mods

- Updates come only through the next-major PR. A hand bump outside `mod-updates.yml` regenerates the jar-baked data itself: `gen-terrain-presets.py` (Tectonic, Terralith or the MC version), `gen-structure-groups.py` (any structure-mod pin), `gen-structure-presets.py`; `--check` gates staleness ([mods/AGENTS.md](mods/AGENTS.md)).
- `custom-dimensions` builds every generator from these mods' registry entries ([AGENTS.md § Dimensions](AGENTS.md#dimensions)), so a bump changes every dimension that reads them.
- Mods ship lenient JSON ([T35](TROUBLESHOOTING.md#t35)), and a jar scan sees biomes inside optional built-in datapacks ([T66](TROUBLESHOOTING.md#t66)): confirm against the registry dump, not the jar.
- Re-enable the render-check before releasing ([AGENTS.md safety rule 12](AGENTS.md#safety-rules)).

### Libraries

A library is any mod another mod requires. Most sit in the `# === dependency libraries ===` section of `config/modrinth-mods.txt`: `almanac`, `architectury-api`, `athena-ctm`, `bookshelf-lib`, `cloth-config`, `collective`, `cristel-lib`, `deimos`, `deltaboxlib`, `forge-config-api-port`, `frozenlib`, `geckolib`, `kambrik`, `moonlight`, `otterlib`, `playeranimator`, `prickle`, `puzzles-lib`, `resourceful-config`, `resourceful-lib`, `terrablender`, `villagerapi`, `yacl`. Five more sit beside the mods that need them: `fabric-api` and `fabric-language-kotlin` (core), `lithostitched` (terrain), `yungs-api` (structures) and `trinkets` (accessories).

- Required, never `?`.
- Judge a library bump by its dependants: find every mod in the pack that requires it. If a newer build breaks one, hold the library and name the dependant in the reason.
- `terrablender` injects biome regions ([T34](TROUBLESHOOTING.md#t34)).

### Client mods, resource packs and shader packs

- `_clientMods.required` / `.optional` in `modpack/adventure.mrpack.json`; `stableOnly` makes the re-pin take the newest release instead of the newest build for a slug whose pre-releases have misbehaved.
- `modpack/overrides/configureddefaults/options.txt` names resource packs by filename, and the build fails when a bump renames one: update the filename. The shader's filename in `configureddefaults/config/iris.properties` has no such check; update it with the pin.
- Shipped client defaults must not clobber player settings ([T10](TROUBLESHOOTING.md#t10)); `modpack/dist/` is build output ([T16](TROUBLESHOOTING.md#t16)).
- Any client-side change is at least a minor.

### Datapacks

Modrinth datapacks are `datapack:slug:versionId` lines in `config/modrinth-mods.txt`; the re-pin passes them through, so bump them by hand and look the version up first. In-repo datapacks live in `config/datapacks/` (a `never_automerge_paths` entry), at `pack_format` 48 for 1.21.1. A structure datapack is worldgen.

### Sidecar base images

Dependabot proposes one PR per base image; dep-review's quick job builds every changed Dockerfile, and the smoke test runs for `defaults-seed`. A base-OS move (Alpine, Python or Debian release) needs a look at that Dockerfile's `apk`, `pip` and `apt` pins: `unmined-render` installs `libicu72`, which only bookworm ships. `debian:bookworm-slim` is a codename tag Dependabot never bumps. A base-image bump is a patch, or a minor for a major version.

### Compose images

Dependabot cannot parse `${MIRROR_REGISTRY:-…}/image:tag`, so these move by hand, in order:

1. Look the tag up and `docker pull` it.
2. Change it in `.github/workflows/mirror-images.yml`'s source list and in the fallback list in `scripts/cache-assets.sh`.
3. Dispatch `gh workflow run mirror-images.yml` (otherwise it runs Sundays at 04:00 UTC) and confirm the GHCR mirror tag exists.
4. Change every `image:` line in `docker-compose.yml`: `nginx` and `itzg/mc-backup` each appear twice.

`itzg/minecraft-server` owns the mc container's lifecycle: read its release notes for env-var, autopause and Fabric-install changes, and keep the `-java21` suffix. Class the bump by the [versioning contract](#versioning-contract): anything consumers must act on is a major.

### GitHub Actions

Dependabot groups minor and patch bumps; a major arrives as its own PR. `GITHUB_TOKEN` may be refused the merge of a PR that changes `.github/workflows/` ([known limits](docs/dependency-review.md#known-limits)), leaving it for a manual merge. The consumer scaffold's workflows are bumped by hand and reach consumers only through `./dev update` ([AGENTS.md § Scripts](AGENTS.md#scripts)). No release unless the scaffold changed.

### Python

`scripts/requirements-discord-sync.txt` is pinned with `==` and bumped by Dependabot; `discord.py` constrains `aiohttp`, so they move together. A bump rebuilds the `discord-sync` image: a patch, or a minor for a major version. `requirements-dev.txt` is template-only (`mise run deps`). Dev tooling runs on Python 3.13 ([CONTRIBUTING § Local development environment](CONTRIBUTING.md#local-development-environment)).

### Minecraft, Fabric and the in-house mod toolchain

- Everything follows 1.21.1: every Modrinth pin, yarn `1.21.1+build.N`, fabric-api `+1.21.1` builds and datapack `pack_format` 48.
- A Fabric loader change is a major. Three pins exist: the client pack's `dependencies.fabric-loader`, the in-house mods' `loader_version`, and the server's, which the itzg image chooses.
- `mods/custom-dimensions` and `mods/custom-dimensions-client` share `gradle.properties` (`yarn_mappings`, `loader_version`, `fabric_version`), Loom in `build.gradle`, and the Gradle wrapper; bump them as a pair. Java 21 comes from `mods/mise.toml`, and builds need `mise exec` ([P4](TROUBLESHOOTING.md#p4)). Build contract: [mods/AGENTS.md](mods/AGENTS.md).

#### Changing the Minecraft version

A fixed decision: only with the user's agreement, and only as a major. Every server and client mod must support the target first; one that can't move blocks the change until it is dropped or replaced by a maintained fork.

1. Back up: `./ops backup`.
2. `./scripts/check-modrinth-compat.sh --version <target>`.
3. Set `MC_VERSION`, then `./scripts/pin-mod-versions.sh --version <target> --apply`.
4. Test locally on a copy of the world: Terralith, Incendium and Nullscape leave chunk borders where old and new chunks meet.
5. Release, deploy, then `./ops map render`.

## Situation playbooks

**Only a pre-release targets 1.21.1.** The `prerelease` flag blocks auto-merge. Hold it with the reason "pre-release; release when a release build for 1.21.1 ships". On a worldgen mod a pre-release is baked into every chunk it generates.

**A required dependency is missing from the pack** (`missing-deps`). Add it in the same commit if it passes [the checklist](#adding-a-mod); otherwise hold the mod and name the dependency.

**The mod dropped 1.21.1.** `pin-mod-versions.sh` falls back through older 1.21.x builds, then leaves the pin with `# FIXME: no 1.21.x build - <slug>`, listed under "Needs attention" in the weekly PR. The current pin is final: keep it for good, or replace the mod ([Removing a default mod](#removing-a-default-mod)).

**The project left Modrinth.** `check-modrinth-compat.sh` reports it not found. The committed `config/modrinth-resolve-cache.json` keeps the pin resolving without the API only while the CDN serves the file: plan a replacement.

**A config key is renamed or removed.** Compare the mod's current docs with `config/<mod>/` and update the platform file. `overlay/config/` replaces whole files, so if a consumer override would break, that is consumer action: a major. Keys forced at deploy by `scripts/force-toml-key.py` ([D6](TROUBLESHOOTING.md#d6)) need their call site updated too.

**The changelog announces a breaking change** (world or save format, registry ids, removed blocks or items). Hold it and route it to a human. Vanished registry ids delete content from existing worlds: a major at least.

**A newer build breaks a dependant.** Hold the library; the reason names the dependant and the build that would clear it.

**Mods that move together.** Xaero's minimap and world map share code (the live `_holds` entry is this pair). The BetterX family moves as one. So do `discord.py` and `aiohttp`, and the two in-house mod projects.

#### Releasing a hold

Show that the stated condition has cleared, for example with the Modrinth version API, then delete the key from `_holds` (by hand, or the reviewer's `holds_remove` with its evidence). The next re-pin bumps the mod.

#### Removing a default mod

Removing a mod deletes its blocks and items from existing worlds: a major.

1. Remove its dependants first: find every mod that requires it.
2. Remove it from `_clientMods` too if the client carries it ([T80](TROUBLESHOOTING.md#t80)).
3. A worldgen mod's removal changes new terrain only; existing chunks keep their shape. The structure datapacks strip removed mods' overrides through `ownership.json`, and the smoke test's `removal` variant guards the boot.

Consumers remove defaults through `overlay/mods-remove.txt` ([consumer scaffold README](examples/consumer/README.md)).

## Mods with standing constraints

Before bumping, removing or pairing one of these, read the linked entry. The comment block above each pin in `config/modrinth-mods.txt` is the per-pin record.

| Mods | Constraint | See |
| --- | --- | --- |
| `betterend`, `betternether`, `bclib`, `worldweaver` | Chunk-zero only; one family, moved together | [AGENTS.md § Mods](AGENTS.md#mods), [T75](TROUBLESHOOTING.md#t75) |
| `betterend` with client `sodium` | The client pack removes Sodium's `breaks: betterend` in `modpack/overrides/config/fabric_loader_dependencies.json`; re-check on either bump | [T82](TROUBLESHOOTING.md#t82) |
| `natures-spirit`, `terrablender` | Region injection repaints managed dimensions | [T34](TROUBLESHOOTING.md#t34) |
| `yungs-better-caves` | Aquifer sampler cast; unclamped carving floor | [T79](TROUBLESHOOTING.md#t79), [T84](TROUBLESHOOTING.md#t84) |
| `c2me-fabric` | A `worldgen_slugs` entry; self-patching config; chunk-generation wedges; removal is the only lever for its CME | [D6](TROUBLESHOOTING.md#d6), [K1](TROUBLESHOOTING.md#k1), [K2](TROUBLESHOOTING.md#k2) |
| `carpet` with `supplementaries` | Carpet's piston mixin is stripped; re-run the reproduction on a carpet bump | [carpet-supplementaries-piston-crash.md](docs/known-issues/carpet-supplementaries-piston-crash.md) |
| `xaeros-minimap`, `xaeros-world-map` | Era pair: move together | live `_holds` |
| `tectonic`, `terralith`, `incendium`, `nullscape` | `custom-dimensions` reads their generator settings; a bump regenerates the terrain presets | [AGENTS.md § Dimensions](AGENTS.md#dimensions) |

## Testing a dependency change locally

- `./dev link` a consumer to this checkout once. The seed **image** carries the mod list, so a linked stack runs the last seed image's mods, not the repo's ([T77](TROUBLESHOOTING.md#t77)): pull or build the `defaults-seed` image with the change, and verify by diffing resolved filenames against `data/mods`.
- A config change needs `./dev refresh-config` ([T78](TROUBLESHOOTING.md#t78)); the seed re-runs on every deploy ([T2](TROUBLESHOOTING.md#t2)).
- `./scripts/check-modrinth-compat.sh --version 1.21.1 --loader fabric` and `python3 scripts/check-client-parity.py` need no boot.
- Snapshot the boot log (`docker logs mc --tail 200 > /tmp/mc.log`), then search the file for `Mixin apply`, `FormattedException` and missing dependencies.
- On macOS there is no `grep -P` ([P3](TROUBLESHOOTING.md#p3)).

## For consumers

- **The pin.** CI deploys the release named by the `STACK_VERSION` **repository variable**; local `./dev` reads `STACK_VERSION` in `.env`. Keep them equal. `v5` floats on the newest `v5.x.y`, `v5.6.1` holds exactly, and unset tracks the newest release across majors, worldgen majors included: pin a major. The scaffold's `deploy.yml` calls `deploy-reusable.yml@v5`.
- **When a release arrives.** The next push to the consumer repo, even a docs-only one, resolves the pin and runs a full deploy ([README § Deploy to production](README.md#deploy-to-production)). Before moving to a new major, read its release notes' "Breaking changes".
- **Updating locally.** `./dev update` pulls the bundle and images, `./dev rollback` lists or restores a version, and `./ops sync` pushes the local `.env` to GitHub and deploys. `./ops update` and `./dev update` refresh different machines ([T46](TROUBLESHOOTING.md#t46)); a `dev` behind the scaffold misreads new flags ([T83](TROUBLESHOOTING.md#t83)); a hand-patched `.stack/<version>/` is discarded when a newer release resolves ([T30](TROUBLESHOOTING.md#t30)).
- **Your own mods.** `overlay/mods-extra.txt` follows [the same checklist](#adding-a-mod); `./dev pin` re-pins it, and the weekly `update.yml` PR does the same and notes a new stack release. Client mods go in `overlay/modpack/manifest.json`. Removing a default: `overlay/mods-remove.txt`, keeping the client pack in sync ([consumer scaffold README](examples/consumer/README.md)).

## Where the rest lives

| Need | Go to |
| --- | --- |
| Modrinth recipes, pin and compat flags, delivery pipeline, list formats | [`server-mod-management`](.claude/skills/server-mod-management/SKILL.md) skill |
| dep-review setup, jobs, security, cost, known limits | [`docs/dependency-review.md`](docs/dependency-review.md) |
| Policy numbers and worldgen sections | [`.github/dep-review/policy.json`](.github/dep-review/policy.json) |
| Cutting and recovering a release | [`platform-release-management`](.claude/skills/platform-release-management/SKILL.md) skill |
| Deploy tiers and rolling a release out | [`deploy-pipeline-operations`](.claude/skills/deploy-pipeline-operations/SKILL.md) skill |
| Overlay mechanics in a consumer | [`consumer-customisation`](.claude/skills/consumer-customisation/SKILL.md), [`consumer-repo-operations`](.claude/skills/consumer-repo-operations/SKILL.md) skills |
| Network calls and image mirrors | [`docs/network-dependencies.md`](docs/network-dependencies.md) |
| Every known problem | [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) |
