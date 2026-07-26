---
title: lib.sh API reference
description: Every function and variable scripts/lib.sh exports, its signature, and when to reach for it instead of writing a local equivalent.
tags: [lib.sh, bash, env, rcon, backup, sed, sha256, provider]
---

# `lib.sh` API reference

`scripts/lib.sh` is the shared utility library sourced by (almost) every Bundle script. **Check here before writing a helper** — a second `.env` writer, a second portable-`sed`, or a second RCON wrapper is a bug waiting to diverge from this one.

## Sourcing idiom (verbatim, use exactly this)

```bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"
```

`shellcheck` will flag the `source` line — add `# shellcheck disable=SC1091` above it (lib.sh's path isn't resolvable at lint time from every caller's location); this is the existing convention throughout the codebase.

## `PROJECT_DIR`

Resolved once, at source time, with this precedence: `CONSUMER_DIR` (set by the `ops`/`dev` dispatchers when running against a consumer checkout) → a pre-set `PROJECT_DIR` (container entrypoints export this themselves) → derived as `lib.sh`'s own parent directory. Read it, don't reassign it after sourcing.

## Colours

`RED`, `GREEN`, `YELLOW`, `BLUE`, `BOLD`, `RESET` — ANSI codes when stdout is a TTY (`[[ -t 1 ]]`), empty strings otherwise so piped/CI output never carries raw escape codes.

## Logging

- `log MSG` — `${GREEN}==>${RESET} MSG` to stdout. Use for normal progress lines.
- `warn MSG` — `${YELLOW}WARNING:${RESET} MSG` to **stderr**. Non-fatal.
- `die MSG` — `${RED}ERROR:${RESET} MSG` to stderr, then `exit 1`. Use for anything that should stop the script immediately with a clear reason.

## `show_banner CMD [DETAIL]`

Prints the brand's deploy banner (from `overlay/config/deploy-banner.txt`, falling back to `config/deploy-banner.txt`) followed by a `BRAND_NAME — CMD | DETAIL` line, then sets `BANNER_SHOWN=1`. Call it at the top of any user-facing script: `show_banner "deploy" "Full production deploy"`. It's a no-op if `BANNER_SHOWN` is already `1` — the `ops`/`dev` dispatchers export that before `exec`-ing into a bundle script, so the banner never prints twice.

## `load_env`

Sources `$PROJECT_DIR/.env` if it exists, via `set -a; source ...; set +a` (exports every variable it defines). Silently does nothing if `.env` is absent — callers that require it should check separately (e.g. `: "${DROPLET_HOST:?Set DROPLET_HOST in .env}"` after calling `load_env`, as `doctor.sh` does).

## `.env` writing — the mandatory path

**Never write a raw `KEY=$value` line to `.env`.** An unquoted MOTD containing spaces once executed itself as a command on a production server. Always go through:

- `env_quote VALUE` → returns `'VALUE'`, single-quoted, with any embedded `'` mapped to the typographic `’` (so it round-trips through both bash `source` and Docker Compose's env-file reader without breaking the quoting). Internally strips one layer of surrounding quotes first (`strip_surrounding_quotes`) so a user-pasted `'already quoted'` value doesn't get double-wrapped.
- `set_env_var FILE KEY VALUE` — updates `KEY=...` in place (via `sed_i`) if the line already exists in `FILE`, otherwise appends `KEY=<quoted-value>`. This is the only sanctioned way to write or update a `.env` entry from a script.

## `sed_i ARGS...`

Portable `sed -i`: `sed -i '' "$@"` on Darwin, `sed -i "$@"` elsewhere. Use this instead of either form directly — the two are mutually incompatible (macOS requires the empty-string backup-suffix argument; GNU sed treats it as the pattern).

## `sha256 ARGS...`

Portable SHA-256: `sha256sum "$@"` if available, else `shasum -a 256 "$@"` (macOS default). Use this instead of hard-coding either binary name.

## `backup FILE`

`cp -p FILE FILE.bak.<YYYYMMDD-HHMMSS>` if `FILE` exists, and prints `  backed up FILE`. Call before any in-place overwrite of a config or state file a human might want to recover — this is what makes "idempotent, safe to run twice" also mean "safe to run wrong once".

## `rcon ARGS...`

`docker exec "${CONTAINER_PREFIX:-}mc" rcon-cli "$@" 2>/dev/null || true` — never throws, always returns (empty output on failure, e.g. autopaused or mc not up). `CONTAINER_PREFIX` defaults to empty (single-instance); set it to `"${COMPOSE_PROJECT_NAME}-"` for multi-instance setups to avoid container-name collisions.

## `get_player_count`

Runs `rcon list`, parses the "There are N" count out of the response with `grep -oE`, and prints `-1` if RCON returned nothing (autopaused, booting, or down) or the pattern didn't match. Use this instead of parsing `rcon list` output yourself — the `-1` sentinel is the established "unknown/unavailable" convention other scripts check for.

## `detect_provider`

Prints `hetzner` if `HCLOUD_TOKEN` or `HETZNER_API_TOKEN` is set, `digitalocean` if `DO_API_TOKEN` is set, else `local`. Pure env-var sniffing, no network calls.

## `require_provider_cli [PROVIDER]`

Defaults `PROVIDER` to `detect_provider`'s output. For `digitalocean`, requires `doctl` on `PATH` (exits 1 with per-OS install hints if missing). For `hetzner`, requires `hcloud`. For `local`, no-op. Call this before any provider-specific operation that would otherwise fail with an opaque "command not found" deep in a provisioning script.
