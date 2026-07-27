---
title: Portability — macOS bash 3.2 and BSD userland
description: Full bash 3.2 substitution table and BSD-vs-GNU tool differences, each row grounded in a real file in this repo.
tags: [bash, portability, macos, bsd, shellcheck, grep, mapfile, declare]
---

# Portability — macOS bash 3.2 and BSD userland

Every script that isn't purely an Image script (baked into a container FROM a Linux base) must run on **macOS bash 3.2** with **BSD userland** — Apple ships an ancient bash for licensing reasons (bash 3.2 predates the GPLv3 relicense) and never updates it, no matter how new the OS. This is a permanent constraint, not a legacy one. It only applies where a human or `./dev`/`./ops` actually invokes the script on a Mac — code that only ever runs inside a `docker/*/Dockerfile`-built image (Image category) is free to use GNU tools and a modern bash, because the container is Linux regardless of the host.

## The substitution table

| Don't (bash 4+ / GNU) | Do (bash 3.2 / BSD-safe) | Why, and where it's proven in this repo |
| --- | --- | --- |
| `declare -A map` | Parallel arrays, a `case` statement, or a `printf \| grep` lookup | bash 3.2 has no associative arrays. `declare -A` fails immediately with `declare: -A: invalid option`, before `set -euo pipefail` even gets a chance to matter. The constraint is called out at `scripts/lib.sh:18`; nothing in the repo uses it. |
| `${var,,}` / `${var^^}` | `tr '[:upper:]' '[:lower:]'` / `tr '[:lower:]' '[:upper:]'` | Case-conversion parameter expansion is a bash 4.0 addition. `tr` is POSIX and behaves identically on both platforms. |
| `mapfile -t arr < f` / `readarray -t arr < f` | `while IFS= read -r line; do arr+=("$line"); done < f` | `mapfile`/`readarray` are bash 4+ builtins. This exact `while IFS= read -r` pattern is already in use in `scripts/sync-mods.sh` (`fetch_missing`'s URL loop), `scripts/dev-up.sh`, `scripts/harden.sh`, `scripts/stack-pull.sh`, and `scripts/provision-hetzner.sh` — copy one of those rather than reinventing it. |
| `grep -P '...'` | `grep -oE '...'` (POSIX extended regex), or `sed` | BSD grep (macOS default) has **no PCRE support at all** — not a subset, none. The dangerous part isn't a crash: the command runs, matches nothing, and exits non-zero or empty, which reads as "nothing to fix" rather than "wrong tool". This has caused real CI and runtime failures per `AGENTS.md`, and was hit again in the seed-rolling scripts after being documented once already. |
| `od -An -td8` | `od -An -tu8` (unsigned 64-bit), or `python3 -c "import os,struct; print(struct.unpack('<q', os.urandom(8))[0])"` for a **signed** 64-bit value | On macOS, `-td8` is parsed as type `d` (signed decimal) with a field width of `8` **bytes wide as the display column**, not "8-byte signed integers" — so it prints one value per input byte instead of one per 8-byte word. `-tu8` (unsigned) is interpreted correctly on both platforms; there is no portable single-command way to get a _signed_ 64-bit random integer, hence the Python fallback. |
| `cmd \|& other` | `cmd 2>&1 \| other` | `\|&` is bash 4+ shorthand for `2>&1 \|`. Not recognised by bash 3.2 — the parser errors on the token. |
| `date -d '...'` / `date -v ...` | Don't reach for either — difference two `date +%s` epoch values instead | GNU `date -d` and BSD `date -v` take incompatible, non-overlapping syntaxes for relative/parsed dates, and there is no flag combination that works identically on both. The repo never uses either form for cross-platform code: `scripts/doctor.sh` computes a sentinel file's age with pure epoch arithmetic, `AGE=$(( $(date +%s) - $(stat ... ) ))` — `date +%s` (no arguments) prints identically on macOS and Linux. |
| `stat -c %Y file` (GNU) / `stat -f %m file` (BSD) | Only use either inside code guaranteed to run on Linux — an Image script, or a heredoc executed over `ssh` on the (Ubuntu) production host | `scripts/idle-tasks.sh` (baked into the `idle-tasks` image) and the `REMOTE` heredoc inside `scripts/doctor.sh` (which runs via `ssh ... bash -s -- ... << 'REMOTE'` on the production server, never locally) both use GNU `stat -c` safely — they never execute on a Mac. A Bundle script invoked directly by `./dev`/`./ops` cannot assume GNU `stat` is present. |

## Other portable tricks already in the codebase — reuse them, don't reinvent

- **Percent-decoding a filename without a heavier dependency**: `name=$(printf '%b' "${name//%/\\x}")` (`scripts/sync-mods.sh`) — converts `%2B` → `+`, `%20` → space using only `printf`'s `%b` (interpret backslash escapes), which is POSIX and present in bash 3.2.
- **Portable `sed -i`**: don't write `sed -i ''` or `sed -i` directly — both forms are correct on exactly one of macOS/Linux and a syntax error on the other. Use `sed_i` from `lib.sh` (see `references/lib-api.md`), which branches on `uname`.
- **Portable SHA-256**: macOS has `shasum -a 256`, Linux typically has `sha256sum`. Use `sha256` from `lib.sh` rather than hard-coding either binary name.
- **`cksum` for a script's own change-detection checksum**: `examples/consumer/dev`'s `self_sum()` uses `cksum` (POSIX, identical output format on both platforms) instead of `md5`/`md5sum`, which exist on both OSes but under different names and with incompatible output formats.

## What is safe to assume

Bash 3.2 still has: `[[ ]]`, `local`, functions, arrays (indexed, not associative), `${var:-default}`/`${var:?msg}` parameter expansion, `$(...)` command substitution, `set -euo pipefail`, `trap`, here-docs and here-strings (`<<<`). None of these need a substitute.
