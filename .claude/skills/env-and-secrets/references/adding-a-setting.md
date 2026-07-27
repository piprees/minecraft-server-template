# Adding a setting — the four places, worked end to end

Two worked examples: a runtime **secret** (illustrative name — treat `EXAMPLE_API_TOKEN` as a stand-in for whatever credential you're actually adding) and an existing **plain variable** used as a template for adding a new one. Follow the same shape for a real addition; substitute your own name and files throughout.

## Worked example: adding a secret the server needs at boot

Say a new sidecar needs `EXAMPLE_API_TOKEN` at runtime.

### 1. `.env.example`

Add it under the relevant section, following the file's own conventions (documented in its own header):

```bash
# --- Example service ------------------------------------------------------
# Example Service dashboard > Settings > API tokens > Create.
# e.g. EXAMPLE_API_TOKEN='ex_live_...'
EXAMPLE_API_TOKEN=
```

- `VAR=` (blank) means required, no default.
- `# VAR=` (commented) means optional — uncomment to override a platform default that already lives in `docker-compose.yml`.
- The `# e.g.` line shows the shape of a real value without being one.

### 2. 1Password

Add the reference to `config/1password.env`, in the matching section comment block:

```bash
EXAMPLE_API_TOKEN=op://Dev/Minecraft Server/EXAMPLE_API_TOKEN
```

Then add a `sync_field` call to `scripts/op-sync-env.sh` so your local value actually reaches the vault:

```bash
sync_field "EXAMPLE_API_TOKEN" "${EXAMPLE_API_TOKEN:-}"
```

Fill in the real value in your local `.env`, then run:

```bash
./ops op-sync-env
```

Confirm: `op item get "Minecraft Server - $BRAND_SLUG" --vault Dev` shows the field.

### 3. GitHub `production` environment

Add `EXAMPLE_API_TOKEN` to the right array in `scripts/github-env-sync.sh` — `REQUIRED_SECRETS` if the deploy should refuse to proceed without it, `OPTIONAL_SECRETS` otherwise:

```bash
REQUIRED_SECRETS=(
  RCON_PASSWORD
  # ...
  EXAMPLE_API_TOKEN
)
```

Then push it:

```bash
./ops github-env-sync
```

This creates/updates `gh secret set EXAMPLE_API_TOKEN --env production` from whatever is in your local `.env`. An empty local value is skipped for optional secrets (existing GitHub value untouched) but **fails the sync** for required ones.

### 4. `deploy-reusable.yml` — only because the server needs it at boot

Three edits in the same file:

**a. Declare the input** (top of the file, `on.workflow_call.secrets`):

```yaml
secrets:
  EXAMPLE_API_TOKEN:
    required: false
```

**b. Map it into the "Generate server .env" step's `env:` block:**

```yaml
S_EXAMPLE_API_TOKEN: ${{ secrets.EXAMPLE_API_TOKEN }}
```

**c. Emit it in that step's script**, alongside the other `emit` calls:

```bash
emit EXAMPLE_API_TOKEN "$S_EXAMPLE_API_TOKEN"
```

Skipping (b) and (c) is the single most common way this goes wrong — the `secrets:` declaration alone makes GitHub _pass_ the value into the job, but nothing then writes it into the file that ends up on the server.

**If it's `required: true` in the workflow**, also add it to the `MISSING` validation block in the same step so a blank value fails the deploy loudly instead of shipping an empty line.

## Worked example: adding a plain variable (no server runtime need)

Not everything needs step 4. `OP_ITEM_NAME` or `OP_VAULT`-style overrides, or a variable only a _script_ reads locally (never the running server), stop after the GitHub environment step — there's nothing for the reusable workflow to carry.

Use an existing entry as the template — `BACKUP_INTERVAL` in `OPTIONAL_VARS` (`scripts/github-env-sync.sh`) is a real one that already completes all the steps a "plain variable, server needs it" setting requires:

1. `.env.example`: `# BACKUP_INTERVAL=` (commented — has a compose default).
2. Not secret, so no 1Password entry needed unless you want it recoverable from a full 1Password restore (non-secret config like `BRAND_NAME`/`SEED`/`SPAWN_X` are synced this way — see `op-sync-env.sh`'s "Non-secret config" section).
3. Already in `OPTIONAL_VARS` in `github-env-sync.sh` — `./ops github-env-sync` pushes it as `gh variable set BACKUP_INTERVAL --env production`.
4. Already mapped in `deploy-reusable.yml`'s `env:` block (`V_BACKUP_INTERVAL: ${{ vars.BACKUP_INTERVAL }}`) and emitted (`emit BACKUP_INTERVAL "${V_BACKUP_INTERVAL:-12h}"`) — note the fallback lives in the `emit` call itself here, mirroring the compose default, so an unset GitHub variable degrades to the same default rather than an empty string.

That last point is worth copying for any new variable that has a sensible default: emit `"${V_FOO:-default}"` rather than `"$V_FOO"` so a value nobody has bothered to set doesn't null out the compose fallback on the server.

## Removing a setting

Reverse order: drop the `emit`/`env:`/`secrets:` entries in `deploy-reusable.yml` first (so a stale value stops reaching the server), then the array entry in `github-env-sync.sh`, then the `config/1password.env` reference (and consider whether to delete the field from the live 1Password item — `op-sync-env.sh` doesn't clean up on its own), then the `.env.example` line last so anyone mid-upgrade still has a working reference until the rest has shipped.
