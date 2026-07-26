---
title: Credential Sourcing
description: Every credential the provisioning chain needs, where to create it, its format, and the traps — in the order ./ops setup asks for them
tags: [cloudflare, hetzner, digitalocean, discord, r2, restic, api-token, credentials]
---

# Credential Sourcing

This is the "how do I obtain X" companion to `docs/credentials.md` (the canonical table — check there for rotation guidance and anything not covered here). Order follows `./ops setup`'s phases, since that's the order an agent running the wizard will hit them.

**Never print or reproduce a real credential value.** Placeholders only, even when quoting a `.env` line back to a user.

## 1. Cloud provider token (pick one)

### Hetzner Cloud — `HCLOUD_TOKEN`

Hetzner Console → your project → **Security → API Tokens → Generate API Token** → Read & Write.

- Format: 64 alphanumeric characters, shown once.
- 1Password field name if stored: `HETZNER_API_TOKEN` (the wizard reads either `HCLOUD_TOKEN` or `HETZNER_API_TOKEN`).
- Default server type `cx33` (4 vCPU / 8GB / x86, ~€11/mo). ARM (`cax` family) is cheaper when in stock; `provision-hetzner.sh` offers a live interactive picker (real-time pricing from the Hetzner API) if your chosen type is sold out in the chosen location — ARM sells out frequently in popular regions.
- Default location `fsn1` (Falkenstein); alternatives `nbg1`, `hel1`.

### DigitalOcean — `DO_API_TOKEN`

Cloud console → **API → Tokens/Keys → Generate New Token** → Read + Write scope.

- Shown once. No fixed format check in preflight beyond non-empty/non-placeholder.
- Default droplet size `s-4vcpu-8gb` (~$48/mo), region `lon1`.

## 2. Cloudflare — three systems, only two used

Read `docs/credentials.md`'s "three credentials people mix up" section before creating anything here. Summary of what to actually create:

### `CLOUDFLARE_API_TOKEN` (the only "token" that goes in `.env`)

**My Profile → API Tokens → Create Token → Create Custom Token**, with exactly:

| Scope | Permission | Access |
| --- | --- | --- |
| Account | Cloudflare Tunnel | Edit |
| Account | Workers R2 Storage | Edit |
| Account | Workers Scripts | Edit |
| Zone | DNS | Edit |
| Zone | Zone | Read |

Zone Resources: *Include → Specific zone → your `DOMAIN`*. Format: 40+ chars, newer tokens start `cfut_`.

Note: `.env.example`'s comment additionally lists `Zone/Cache Purge:Purge` as a permission to add. It isn't exercised by any script read for this skill (no `/purge_cache` API call found in `cloudflare-setup.sh` or elsewhere) — treat the five permissions above as the verified minimum; add Cache Purge only if a future script needs it.

Verify before blaming anything else (preflight and `./ops setup` both do this live):

```bash
curl -s "https://api.cloudflare.com/client/v4/user/tokens/verify" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN"
# -> "result": {"status": "active"}
```

`CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_ZONE_ID` (both 32 hex chars) auto-resolve once the token verifies and `DOMAIN` is set — via a `GET /zones?name=$DOMAIN` call in both `setup.sh` and `preflight-check.sh`. Only hand-enter them if that lookup comes back empty (token's Zone Resources don't include the domain, or the domain isn't on this Cloudflare account).

`CLOUDFLARE_TUNNEL_ID` — never hand-created. `./ops cloudflare` creates the tunnel via the API and writes the ID back to `.env` itself.

### R2 S3 keypair — `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY`

**Dashboard → R2 → Manage API Tokens → Create Account API token** → **Object Read & Write**, scoped to your bucket name (default `mc-backups`).

The result page shows **three** values:

| Value shown | Goes in `.env` as | Format |
| --- | --- | --- |
| Token value | nothing — not used anywhere in this stack | — |
| Access Key ID | `R2_ACCESS_KEY_ID` | 32 hex chars |
| Secret Access Key | `R2_SECRET_ACCESS_KEY` | 64 hex chars, shown once |

`setup.sh` validates both against these exact regexes on entry and re-prompts if they don't match — a fast way to catch "I copied the wrong box."

`R2_ACCOUNT_ID` is **not a separate value** — it's the same 32-hex-char `CLOUDFLARE_ACCOUNT_ID`. `setup.sh` sets it automatically from `CLOUDFLARE_ACCOUNT_ID` once that's resolved.

### Global API Key — do not use

Legacy `X-Auth-Email` + `X-Auth-Key` scheme. Nothing in this stack reads it. If a tutorial or a person asks for "your Cloudflare API key and email," they mean this scheme — it doesn't apply here and grants far more access than needed.

## 3. Discord bot

Full click-by-click steps (intents, OAuth scopes, invite URL): `docs/setup-guide.md` §4 — not duplicated here. Credentials collected:

| Variable | Format | Where |
| --- | --- | --- |
| `DISCORD_BOT_TOKEN` | ~70 chars, two `.` separators | Bot tab → Reset Token |
| `DISCORD_CHANNEL_ID`, `DISCORD_GUILD_ID`, role IDs | 18-19 digit snowflake | Right-click with Developer Mode on → Copy ID |
| `DISCORD_WEBHOOK_URL` | `https://discord.com/api/webhooks/…` | Channel → Integrations → Webhooks |

Required intents: **MESSAGE CONTENT** and **SERVER MEMBERS** (Bot tab → Privileged Gateway Intents). Missing either one is a common cause of the bot connecting but not responding to messages or role events.

## 4. Backup encryption — `RESTIC_PASSWORD`

You invent this one (or let `setup.sh` auto-generate it: 32 chars from `/dev/urandom`, base64, alphanumeric-filtered). **Unrecoverable if lost** — every existing backup becomes permanently unreadable. `setup.sh` mirrors it to 1Password immediately if connected; confirm it landed there before moving on, since this is the one credential with no "just create a new one" recovery path.

## 5. RCON / Kuma passwords

`RCON_PASSWORD` and `KUMA_PASSWORD` auto-generate on first run of `setup.sh` (or `./dev up` locally) if left blank — 24-char alphanumeric from `/dev/urandom`. No dashboard involved.

## Format-validation regexes (from `preflight-check.sh`, for self-checking without running preflight)

| Variable | Pattern |
| --- | --- |
| `R2_ACCESS_KEY_ID` | `^[0-9a-fx]{32}$` |
| `R2_SECRET_ACCESS_KEY` | `^[0-9a-fx]{64}$` |
| Any credential var | fails if empty, contains `xxxx`, contains `REPLACE`, or starts `change-me` |

## Cross-reference

Rotating a credential already in use, or the four-places-to-update rule (`.env.example`, 1Password, GitHub environment, reusable workflow) — see the `env-and-secrets` skill, not this one.
