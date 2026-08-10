---
name: bundle-script-authoring
description: Writes or edits any script shipped by this platform — bundle scripts run by consumers via ./ops/./dev, GHCR image entrypoints, or template-only tooling — and routes each to its correct shipping destiny with a current house header. Covers lib.sh's exported helpers (log/warn/die, load_env, backup, set_env_var/env_quote, rcon), the macOS bash 3.2 portability rules (no declare -A, no ${var,,}, no mapfile, no grep -P), and the two silent shipping traps: a bundle script missing from MANIFEST in build-stack-bundle.sh never reaches consumers, and a new examples/consumer/ file missing from the update) sync list in examples/consumer/dev never reaches an existing consumer. Use when writing a new script under scripts/ or examples/consumer/, adding a script to the stack bundle, editing lib.sh, fixing a ShellCheck warning, or troubleshooting a bogus "unexpected EOF", a "declare: -A: invalid option" crash, or "Script not found:" from ./ops. Consult before hand-writing a header or using a bash 4+ builtin.
---

# Bundle Script Authoring

You are writing or editing a shell (or Python) script in the `minecraft-server-template` platform repo. There are ~77 shell scripts and ~20 Python scripts in `scripts/`, and they obey a house style that contradicts most agents' defaults: macOS bash 3.2 (not bash 4+/5), no PCRE grep, a header comment that is the authoritative reference, and three shipping destinies that only partly overlap with "where the file lives in this repo."

This skill is about **how** to write the script and **where it ends up**. What a specific script should _do_ is out of scope — use the relevant operational skill for that.

## The three destinies — pick one before you write anything

| Category | Lands in | Registered where | Example |
| --- | --- | --- | --- |
| **Bundle** | The stack tarball, run by consumers via `./ops`/`./dev` | `MANIFEST` array in `scripts/build-stack-bundle.sh` | `deploy.sh`, `doctor.sh`, `sync-mods.sh`, `lib.sh` |
| **Image** | Baked into a GHCR image, never run directly by a consumer | A `COPY` line in that image's `docker/<image>/Dockerfile` | `discord-sync.py`, `idle-tasks.sh`, `kuma-provision.py`, `check-updates.sh`, `build-modpack.sh` |
| **Template** | Stays in this repo for platform development only | Nowhere — it just lives here, never packaged | `test-scripts.sh`, `pin-mod-versions.sh`, `build-stack-bundle.sh`, `check-modrinth-compat.sh`, `client-defaults.sh` |

The question that picks one: **who runs this, and from where?** If a consumer's `./ops <cmd>` or `./dev <cmd>` needs to invoke it, or another bundle script sources/execs it, it's Bundle. If it only ever runs inside a container built by `docker/*/Dockerfile`, it's Image. If it's a platform-maintainer-only tool (releasing, re-pinning mods, testing), it's Template — full inventory in [README.md § Scripts](../../../README.md#scripts).

**Get this wrong and it fails silently.** A Bundle script not added to `MANIFEST` builds and runs fine locally (you have the whole repo checked out) but a consumer's `./dev update` never downloads it — `./ops <cmd>` then fails with `Script not found: <path>` pointing at a file that was never in the tarball.

## The house header

Every script carries a header with four parts: what it does, context (where it runs, who calls it), usage with every flag, and gotchas. **Keep it current in the same commit as the behaviour change** — `AGENTS.md` calls this the authoritative reference, not the code, because operators read the header with `head -30`/`cat ... | head -30` before running anything unfamiliar (`examples/consumer/ops` literally does this for `--help`).

Real anchor, verbatim (`scripts/sync-mods.sh`):

```bash
#!/usr/bin/env bash
# sync-mods.sh - Download managed mod/datapack files the seed expects but
# data/ lacks, from the seed-resolved CDN URL lists in the stack-mods volume.
#
# Context: MODS_FILE/DATAPACKS_FILE are empty by default (offline boots —
# itzg's per-URL freshness HEAD checks are skipped entirely), so fetching
# missing files is a host-side job that runs between the seed container and
# mc's start. When every file is already present this makes ZERO network
# requests — the normal case for every boot after the mod list last changed.
#
# Usage:
#   sync-mods.sh <data-dir> [stack-mods-volume]
#
#   data-dir           the consumer/server data directory (jars land in
#                      data/mods, datapacks in data/world/datapacks)
#   stack-mods-volume  optional; resolved from the ${CONTAINER_PREFIX:-}seed
#                      container's /out/mods mount when omitted
#
# Called by dev-up.sh (local), deploy.sh step 10b (production), and
# smoke-test.yml (CI). Idempotent. Exits non-zero if any required file
# cannot be fetched — booting without a worldgen mod corrupts chunks, so a
# missing jar must block the boot loudly.
#
# Gotchas: URL filenames are percent-encoded (%2B -> +, %20 -> space);
# itzg decodes them and so do we, or every boot re-downloads mismatched
# names. Must run on macOS bash 3.2 - no mapfile, no ${var,,}.
set -euo pipefail
```

Every new script needs: shebang, one-line name + purpose, a **Context** paragraph (where it runs, who calls it, why), a **Usage** block listing every flag/positional, and a **Gotchas** paragraph for anything a caller would otherwise learn the hard way. `scripts/doctor.sh`'s header is a longer worked example (exit codes, an interactive vs `DOCTOR_SSH_KEY=... DROPLET_HOST=...` CI-callable form).

## Portability: macOS bash 3.2 + BSD userland

Every script must run on **macOS bash 3.2** — the platform default there is far newer, and that gap is a reflex agents fall into constantly. `#!/usr/bin/env bash` + `set -euo pipefail` always; idempotent (safe to run twice); `backup()` before overwriting; support `--non-interactive` for CI. No `grep -P` anywhere — BSD grep has no PCRE support at all, use `grep -oE` or `sed`.

| Don't                   | Do                                                          |
| ----------------------- | ----------------------------------------------------------- |
| `declare -A map`        | Parallel arrays, a `case`, or a `printf`+`grep` lookup      |
| `${var,,}` / `${var^^}` | `tr '[:upper:]' '[:lower:]'` / `tr '[:lower:]' '[:upper:]'` |
| `mapfile -t arr < f`    | `while IFS= read -r line; do arr+=("$line"); done < f`      |
| `grep -P`               | `grep -oE`, or `sed`                                        |
| `od -An -td8`           | `od -An -tu8`, or a `python3` struct one-liner              |
| `cmd \|& other`         | `cmd 2>&1 \| other`                                         |

This is the short version — **full table with the "why" and a repo citation for each row is in [`references/portability.md`](references/portability.md); read it before you write anything relying on a bash 4+ feature or a GNU-only flag.**

## `lib.sh` — check before writing a helper

Every script sources it the same way:

```bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"
```

It provides `PROJECT_DIR` resolution, colour codes, `log`/`warn`/`die`, `load_env` (sources `.env`), `backup()` (`file.bak.TIMESTAMP`), `set_env_var`/`env_quote` (the only sanctioned way to write a `.env` line), `sed_i`/`sha256` (macOS/Linux-portable), `rcon`/`get_player_count`, and `detect_provider`/`require_provider_cli`. **Check it before rolling your own** — a second `sed -i` portability shim or a second `.env`-writer is a bug waiting to diverge from the original. Full signatures and when to reach for each one: [`references/lib-api.md`](references/lib-api.md).

**Never write a raw `KEY=$value` line into `.env`.** Every value goes through `env_quote`/`set_env_var` — single-quoted, embedded `'` mapped to `’`. An unquoted MOTD containing spaces once executed itself as a command on a production server.

## The two shipping traps — pre-commit checklist

- [ ] New Bundle script → added to the `MANIFEST` array in `scripts/build-stack-bundle.sh`?
- [ ] New `examples/consumer/` file → added to the `update)` case in `examples/consumer/dev` (the executable-entry-points loop, the plain-file-copy loop, or the workflows loop, as appropriate)?

`README.md` and `overlay/` are **deliberately** excluded from the consumer sync — those are consumer-owned content, never overwrite them.

`.github/workflows/lint.yml`'s `bundle-manifest` job only catches part of this: it cross-checks `examples/consumer/ops`'s `script_file="X"` mappings and `ALLOWED_COMMANDS` entries against `MANIFEST`, and checks that any `$SCRIPT_DIR/foo.sh` referenced _from inside_ a manifested bundle script is itself in `MANIFEST`. It only greps for `scripts/*.sh` paths — **a standalone script nobody calls by name yet (or a `.py` file) has no automated check at all**, beyond the narrower `scripts/seed/*.py` import-graph check in `test-scripts.sh` Phase 1 (which only covers the seed roller's own dependency tree). Don't rely on CI to catch a forgotten manifest entry outside those two cases — add it by hand and verify with the validation commands below.

## Safety: no unbounded loops

A single `sleep N` outside a loop, for a known duration, is fine. Any loop that only exits on a condition you cannot guarantee will occur is forbidden — a crashing container never becomes healthy, and `docker logs -f`, `gh run watch`, or a `while true; do ...; done` waiting on a healthcheck will trap the caller (and any CI job) forever. Use `--tail N` snapshots or a `gh run view --json` poll with a hard iteration cap instead.

## Versions: never trust training data

Before pinning any Docker image tag, GitHub Actions `uses:`, CLI tool, or library version, look it up live (`gh release list --repo <owner/repo> --limit 5`, Context7, or the project's releases page). Two traps: `gh release list --limit 1` returns the most _recently published_ release, not the highest semver (backported patches republish old minors) — use `--limit 5` and filter on `isLatest`. And a GitHub release tag doesn't guarantee a matching Docker Hub tag — some projects stop pushing to Docker Hub while still cutting GitHub releases; verify with `docker pull` before pinning.

## User-facing strings

Every player/Discord-facing message lives in `config/messages.json`. Never hard-code one in a script — British English throughout.

## Quality gates

```bash
./scripts/test-scripts.sh --quick    # shellcheck --severity=warning, py_compile, compose config validation
```

Run this before every push. CI's `lint.yml` runs the same plus `yamllint -c .yamllint.yml` and the `bundle-manifest` check, and blocks merges on any failure — it never downgrades a failed static check to a warning. `lefthook install` (once, after cloning) wires the same command into `pre-commit` for any change touching `scripts/**`, `docker/**`, `examples/consumer/dev`, `examples/consumer/ops`, `docker-compose.yml`, `.github/workflows/**`, or `config/cloudflared/config.yml`.

## Traps (read this before you write anything)

1. **`grep -P` on macOS.** Documented in `AGENTS.md`. BSD grep silently produces no match rather than erroring — "no output" reads as "nothing to fix", not as "this tool doesn't exist here". Use `grep -oE` or `sed`.
2. **A Bundle script missing from `MANIFEST` never ships.** The build succeeds, `test-scripts.sh` passes locally (you have the file on disk), and the first symptom is a consumer's `./ops <thing>` failing with `Script not found: <path>` after `./dev update` pulls a release tarball that never contained it.
3. **A consumer scaffold file missing from the `update)` sync list never reaches existing consumers.** New consumers created via `degit`/curl get everything under `examples/consumer/` for free; existing consumers only receive what the explicit copy loops in `examples/consumer/dev`'s `update)` case name. `README.md` and `overlay/` are excluded on purpose — don't "fix" that.
4. **`cp` over a running script corrupts it.** Bash reads a script incrementally as it executes; `cp` rewrites the destination inode in place, and an interpreter mid-execution hits shifted bytes and dies with a bogus `unexpected EOF`/parse error. Use write-to-temp + `mv` — see the executable-entry-points loop in `examples/consumer/dev`'s `update)` case, which does exactly this because that loop replaces the very script bash is executing.
5. **Never write a raw `KEY=$value` into `.env`.** Go through `set_env_var`/`env_quote` — see lib.sh above.
6. **Never trust training data for a version number.** Look it up live; `--limit 1` gets you most-recently-published, not highest; a GitHub tag existing doesn't mean a Docker Hub tag does.
7. **Idempotent means safe to run twice, and back up before overwriting.** Both matter because wizards and CI re-run these scripts constantly — `setup.sh`, `deploy.sh`, and `dev-up.sh` all assume a second run is safe.
8. **User-facing strings belong in `config/messages.json`**, never hard-coded in a script.

## Validation (do not skip this)

```bash
./scripts/test-scripts.sh --quick    # shellcheck + py_compile + compose config — run this first, every time
shellcheck --severity=warning scripts/<new>.sh
bash -n scripts/<new>.sh             # syntax only, fast, no shellcheck warnings
./scripts/test-scripts.sh            # full Ubuntu-container run (harden.sh, lib.sh load) — slower, do before a release
```

Then, by hand (nothing currently automates these two):

- Bundle script: confirm it's a literal line in the `MANIFEST` array in `scripts/build-stack-bundle.sh`.
- New `examples/consumer/` file: confirm it's named in one of the copy loops in the `update)` case of `examples/consumer/dev`.

## References

- [`references/portability.md`](references/portability.md) — the full bash 3.2 / BSD-vs-GNU substitution table, each row with a repo citation.
- [`references/lib-api.md`](references/lib-api.md) — every function `lib.sh` exports, its signature, and when to reach for it instead of rolling your own.
