# Contributing

Bug fixes, setup improvements, and useful features are all welcome.

## Before you start

Read [`README.md`](README.md) (architecture, quickstart), [`AGENTS.md`](AGENTS.md) (constraints and architecture traps), and [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) (every known problem). Check existing issues before opening one. Open an issue first for a large change; small bug fixes and docs can go straight to a PR.

## Which workflow am I in?

Most people reading this are platform contributors — it lives in the platform repo. If you're in a consumer repo, read that repo's README instead.

**Platform contributor** (improving scripts, configs, images, or workflows): platform changes are tested through a consumer repo — copy `examples/consumer/`, or use an existing one — the same way a real consumer uses them.

```bash
# in the consumer repo:
cp .env.example .env
./dev link                # once: point .stack/current at this checkout
./dev up                  # starts everything from the checkout, no release needed
```

`./dev link` builds a farm of symlinks over the checkout, so an edited script or compose file — and every rebuilt in-house mod jar — is live on the next `./dev up`. An edited `config/` file also needs `./dev refresh-config`; content baked into the `defaults-seed` image (`config/nginx/`, `config/modrinth-mods.txt`) a link doesn't reach at all. `./dev unlink` restores the newest pulled release bundle. Full workflow: `.claude/skills/local-stack-testing/SKILL.md` § Linked local development.

**Never run `./scripts/dev-up.sh` directly from a platform checkout** — its path math assumes bundle nesting and resolves incorrectly.

**Consumer contributor** (adding mods or overlays): `cp .env.example .env` then `./dev up`.

The local profile disables online-mode and whitelist, so you can connect at `localhost:25577` without a Microsoft account. `build-stack-bundle.sh` uses GNU tar for reproducible bundles — on macOS `brew install gnu-tar` provides `gtar`; without it locally-built bundles won't byte-match CI's output (harmless, CI does the real build).

## Local development environment

`mise.toml` at the repo root pins the CLI tools CI uses and the Python the
template-only scripts run under:

```bash
mise trust && mise install     # python, shellcheck, yamllint, jq
mise run deps                  # template-only Python packages
cp .env.example .env               # set CONSUMER_DIR to your consumer checkout
```

Python is pinned to 3.13 deliberately: 3.14 has thin wheel coverage. Bumping any dependency: [DEPENDENCIES.md](DEPENDENCIES.md). Java
stays in `mods/mise.toml` (temurin-21), scoped to the Gradle builds.

Tasks: `mise run gate` (the pre-push gate), `scan` (what generated in the
local consumer's world and its water depth), `catalogue` (pull the live
registry dump), `blocktags`. `mise tasks` lists them.

Consumers need none of this — bundle scripts are POSIX/stdlib only and image
scripts get their deps from their own Dockerfile.

## Quality gates

```bash
lefthook install                   # once after cloning — runs the checks pre-commit
./scripts/test-scripts.sh --quick  # the same gate by hand, before opening a PR
```

ShellCheck (severity: warning) on all scripts, Docker entrypoints, and consumer dispatchers; `py_compile` on Python scripts; `docker compose config` on both profiles; yamllint against [`.yamllint.yml`](.yamllint.yml). Every issue fails the gate — no static check is downgraded to a warning. CI runs the same plus bundle-reference and Python dependency checks, and PRs that fail lint won't be merged.

## Conventions

- [Conventional commits](https://www.conventionalcommits.org/), imperative mood: `feat:`, `fix:`, `chore:`, `docs:`, `ci:`, `refactor:`, `style:`, `test:` — e.g. `fix: handle RCON timeout during autopause`.
- British English in all user-facing strings, docs, and commit messages (colour, behaviour, initialise). Second-person imperative for instructions, present tense for descriptions, blunt warnings with no softening ("Never", "Don't", "This will break").
- Shell scripts: `#!/usr/bin/env bash` + `set -euo pipefail`, idempotent, and macOS bash 3.2 compatible (no `declare -A`, no `${var,,}`, no `|&`). Full rules: [AGENTS.md § Conventions](AGENTS.md#conventions).
- Player-facing messages live in `config/messages.json` and Compose values come from `.env`. Never hard-code either.

## Adding or removing mods

The most common change, and the one most likely to break things. Follow [DEPENDENCIES.md § Adding a mod](DEPENDENCIES.md#adding-a-mod) — the dependency checklist is mandatory. A platform mod change reaches servers only in a release: push builds images, then a release is cut per the [versioning contract](DEPENDENCIES.md#versioning-contract).

## Pull requests

- One logical change per PR. Keep it small — discuss large refactors in an issue first.
- Say what changed and why, and name the deploy tier if the change affects deployment (new env vars, services, configs — see the [tier table](README.md#deploy-to-production)).
- Screenshots or logs for UI changes and bug fixes are appreciated.
- Maintained by one person: straightforward changes with green CI usually turn around fast, and the maintainer may squash-merge with a conventional commit message. If CI fails, fix the flagged annotations and push again — CI re-runs automatically.

## Good first contributions

Documentation fixes (typos, clarifications, dead links), adding a mod to `config/modrinth-mods.txt` ([checklist](DEPENDENCIES.md#adding-a-mod)), ShellCheck fixes, issue-template improvements. Look for issues labelled `good first issue` or `help wanted`.

Report problems with the issue templates: **bug report** (something is broken), **config / setup help** (stuck getting the server running), **mod request**. This project follows the [Contributor Covenant 2.1](CODE_OF_CONDUCT.md) — be kind.
