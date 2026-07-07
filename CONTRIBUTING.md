# Contributing

This is a Minecraft server template built as infrastructure-as-code. Bug fixes, setup improvements, and useful features are all welcome.

## Before you start

1. **Read the docs.** [`README.md`](README.md) has the architecture and quickstart; [`AGENTS.md`](AGENTS.md) has the constraints and architecture traps that apply to every change.
2. **Check existing issues** for related discussion before opening a new one.
3. **For large changes**, open an issue first to discuss the approach. Small bug fixes and documentation improvements can go straight to a PR.

## Local development

```bash
# Clone and start the local dev server
git clone https://github.com/YOUR_USERNAME/YOUR_REPO.git
cd YOUR_REPO
cp .env.example .env
./scripts/dev-up.sh
```

The local profile disables online-mode and whitelist, so you can connect at `localhost:25577` without a Microsoft account. See [README.md](README.md#contributing) for more.

## Quality gates

Run this before opening a PR:

```bash
./scripts/test-scripts.sh --quick
```

This checks:

- **ShellCheck** (severity: warning) on all shell scripts
- **py_compile** on all Python scripts
- **docker compose config** validation for both profiles

CI runs the same checks plus yamllint. PRs that fail lint won't be merged.

## Commit conventions

Use [conventional commits](https://www.conventionalcommits.org/) with imperative mood:

```
feat: add Terralith biome presets to seed scorer
fix: handle RCON timeout during autopause
chore: update mod pinned versions
docs: clarify Discord bot setup steps
ci: align checkout action to v7
```

Common prefixes: `feat:`, `fix:`, `chore:`, `docs:`, `ci:`, `refactor:`, `style:`, `test:`.

## Style guide

- British English in all user-facing strings, docs, and commit messages (colour, behaviour, initialise).
- Shell scripts use `#!/usr/bin/env bash` + `set -euo pipefail`. Must run on macOS bash 3.2 (no `declare -A`, no `${var,,}`, no `|&`). Idempotent - safe to run twice.
- Player-facing messages all live in `config/messages.json`. Never hard-code user-facing strings in scripts.
- Docker Compose values come from `.env`/`server.env`. No hard-coded values in `docker-compose.yml`.

## Adding or removing mods

This is the most common type of change and the one most likely to break things. Follow this checklist:

### Dependency checklist (mandatory)

Before adding any mod, resolve its dependencies:

```bash
# 1. List dependencies for 1.21.1 Fabric
curl -s "https://api.modrinth.com/v2/project/{slug}/version?game_versions=%5B%221.21.1%22%5D&loaders=%5B%22fabric%22%5D" \
  | python3 -c "import sys,json; [print(f'  {d[\"project_id\"]} ({d[\"dependency_type\"]})') for v in json.load(sys.stdin)[:1] for d in v.get('dependencies',[])]"

# 2. Resolve each project_id to a slug
curl -s "https://api.modrinth.com/v2/project/{project_id}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['slug'], d['title'])"
```

### What to edit

| What | Edit | Then |
| --- | --- | --- |
| Server mod | `config/modrinth-mods.txt` (format `slug:versionId`, `?` suffix = optional) | Push to `main` (triggers full deploy) |
| Client mod | `modpack/adventure.mrpack.json` (`_clientMods.required` / `.optional`) | Push (CI rebuilds `.mrpack`) |
| Datapack | `config/modrinth-mods.txt` with `datapack:` prefix, or drop into `config/datapacks/` | Push (full deploy) |

### Rules

- Every required dependency must already be in the pack or be added alongside.
- Library dependencies (`fabric-api`, `yungs-api`, `moonlight`, `balm`, `lithostitched`, `fabric-language-kotlin`) go in required, never optional.
- Verify the resolved version actually targets 1.21.1 - Modrinth metadata isn't always accurate.
- After adding, pin versions: `./scripts/pin-mod-versions.sh --apply`.
- All worldgen/dimension mods must be present from chunk zero - adding them to an existing world causes visible chunk borders.

## Pull request expectations

- One logical change per PR. If your change touches mods _and_ scripts, that's fine as long as they're related.
- Include a brief description of what changed and why.
- If your change affects deployment (new env vars, new services, config changes), note which deploy tier it triggers (full/infra/pull - see [README.md](README.md#deploy-to-production)).
- Screenshots or logs for UI changes or bug fixes are appreciated.
- Keep PRs small. Large refactors should be discussed in an issue first.

## Config sync

If you add a mod with server-side configuration, you need to touch two places:

1. Config files in `config/<modname>/`
2. The directory added to `MC_PATTERNS` in `.github/workflows/deploy.yml` so changes trigger a full deploy

Config seeding is handled automatically by `deploy.sh` step 8 — it copies all files from the bundle's `config/` into `data/config/` (skip-if-exists for defaults, force-overwrite for consumer overlay). This runs before mc starts so mods don't create their own defaults first.

## Reporting issues

Use the issue templates:

- **Bug report** - something is broken
- **Config / setup help** - you're stuck getting the server running
- **Mod request** - suggest a mod to add to the pack

## Code of Conduct

This project follows the [Contributor Covenant 2.1](CODE_OF_CONDUCT.md). Be kind.
