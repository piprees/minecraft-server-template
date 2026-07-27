# .env file anatomy

## `.env.example` conventions

The header of `.env.example` states its own rules — read them there first; this is a summary with the mechanism behind each one.

| Marker | Meaning | Example |
| --- | --- | --- |
| `VAR=` (blank, uncommented) | Required — no default anywhere. Preflight/deploy will complain if it's still blank. | `BRAND_NAME=` |
| `# VAR=` (commented) | Optional override of a platform default baked into `docker-compose.yml` (`${VAR:-default}`). Leave commented to accept the default. | `# MEMORY=5G` |
| `# e.g. VAR='…'` | Shows the shape of a real value without being one — never a live credential. | `# e.g. DISCORD_WEBHOOK_URL='https://discord.com/api/webhooks/…'` |

The file is split into two banners: **LOCAL DEVELOPMENT** (fill these in, ignore the rest for `./dev up`) and **PRODUCTION** (only needed when deploying to a cloud server). A local-only contributor never needs to touch anything below the production banner.

`.env.example` is the base layer `op-env.sh` emits first (`grep -v '^\s*#' .env.example`, stripped of comments and blank lines) before it layers 1Password secrets on top — so **any change to `.env.example` shows up in every future `./ops op-env` restore automatically**, without touching `op-env.sh` itself.

## The quoting rule, mechanically

Everything goes through `scripts/lib.sh`:

```bash
strip_surrounding_quotes() {
  # removes one matching pair of leading/trailing ' or " —
  # so a pasted 'My Token' or "My Token" isn't double-wrapped
}

env_quote() {
  local v
  v=$(strip_surrounding_quotes "${1-}")
  printf "'%s'" "${v//\'/’}"   # embedded ' -> typographic ’
}

set_env_var() {
  # FILE KEY VALUE — updates in place (sed) or appends, always via env_quote
}
```

Every writer in the repo — `setup.sh`, `op-env.sh` (its own inline equivalent, see below), and `deploy-reusable.yml`'s "Generate server .env" step (`emit()`, a separate inline re-implementation, since the GitHub Actions runner doesn't source `lib.sh`) — produces the exact same output shape: `KEY='value with an embedded ’ instead of a straight quote'`. Never write a `.env` line any other way, including quick one-off fixes with `sed` or `>>` — an unquoted `MOTD` with spaces once ran as a shell command on a production server.

`op-env.sh` reimplements the quoting inline rather than sourcing `lib.sh` (it's a standalone script, not run through the `ops`/`dev` dispatchers that set up `PROJECT_DIR`):

```bash
echo "${var_name}='${value//\'/’}'"
```

Same rule, same output — just don't assume every writer literally calls the shared function; some replicate it because of how they're invoked.

## Compose fallbacks

Every `${VAR}` reference in `docker-compose.yml` carries an inline default: `${MEMORY:-5G}`, `${SERVER_PORT:-25577}`, `${VIEW_DISTANCE:-10}`, `${SIMULATION_DISTANCE:-8}`, `${IMAGE_REGISTRY:-ghcr.io/piprees/minecraft-server-template}`, `${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}`, `${ONLINE_MODE:-TRUE}`, `${MOTD:-A modded adventure server}`, and so on for every variable that has a sensible platform default. Docker Compose reads `.env` automatically for interpolation — no extra config needed.

Consequence: a lean consumer `.env` containing only the required, filled-in lines is a **complete** config. Don't add a commented-out default line to `.env` "just to be explicit" — it adds a second place that can drift from the real default in `docker-compose.yml` and nobody will notice until they disagree.

## Precedence: which `.env` a script actually reads

Every bundle script derives its project root the same way (`lib.sh`, and independently duplicated at the top of standalone scripts like `op-env.sh`/`op-sync-env.sh`/`github-env-sync.sh`):

```bash
PROJECT_DIR="${CONSUMER_DIR:-${PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}}"
```

Precedence, highest first:

1. **`CONSUMER_DIR`** — set by the `ops`/`dev` dispatchers before they exec into a bundle script. This is what lets one bundle of scripts operate on a _consumer_ repo's `.env` even though the scripts themselves live in `.stack/current/stack/scripts/`.
2. **Pre-set `PROJECT_DIR`** — used when a container entrypoint or another script has already exported it.
3. **Derived** — `..` relative to the script's own location (`scripts/` → repo root). This is the fallback when a script is run standalone, e.g. directly from a platform checkout.

If a script seems to be reading the wrong `.env` (or none at all), check which of these three set the value in that invocation — it's rarely a bug in the script itself.

## The 1Password `op://` reference format

`config/1password.env` is not a `.env` file itself — it's a template of `op://` reference URIs, one per line, in `VAR=op://vault/item/field` shape. Two things make it non-obvious:

**Item name substitution.** The literal item name in the file is always `Minecraft Server` — `op-env.sh` and `op-sync-env.sh` both rewrite that substring to the per-brand item name (`Minecraft Server - <BRAND_SLUG>`, or `$OP_ITEM_NAME` if set) _at read/write time_. Don't "fix" the file to hardcode a real brand name — that breaks every other consumer repo built from this template.

**Per-environment sections.** A handful of fields are nested under `local`/`prod` sections **within the same 1Password item**, not separate items:

```bash
RCON_PASSWORD=op://Dev/Minecraft Server/${MC_ENV:-local}/RCON_PASSWORD
KUMA_PASSWORD=op://Dev/Minecraft Server/${MC_ENV:-local}/KUMA_PASSWORD
ONLINE_MODE=op://Dev/Minecraft Server/${MC_ENV:-local}/ONLINE_MODE
```

`op-env.sh` exports `MC_ENV` (from its own `local`/`prod` argument) before resolving these, so `./scripts/op-env.sh prod` and `./scripts/op-env.sh local` pull different RCON/Kuma passwords and a different `ONLINE_MODE` from the _same_ vault item. `op-sync-env.sh` writes these with an explicit `local.` prefix (`sync_field "local.RCON_PASSWORD" ...`) — there's no equivalent `prod.` sync from this script; production values in that section are set once at provisioning time and not routinely overwritten by a local sync.

**Field name mismatches happen.** `KUMA_API_KEY` in `.env` resolves to the 1Password field `KUMA_UPTIME_CHECKS_API_KEY` — the var name and the vault field name are not always identical. When a value fails to resolve, check the actual `op://` line in `config/1password.env` rather than assuming the field is named after the environment variable.

**Non-secret config lives here too.** `BRAND_NAME`, `BRAND_SLUG`, `SEED`, `SPAWN_X/Y/Z` are synced to 1Password even though they aren't secrets — this makes a full server identity recoverable from 1Password alone, without needing the GitHub environment at all.
