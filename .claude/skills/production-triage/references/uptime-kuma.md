---
title: Uptime Kuma
description: Config-driven monitors, the 2FA authentication stop-rule, token recovery, and clearing a stuck maintenance window without auth
tags: [uptime-kuma, kuma-token, authIncorrectCreds, KUMA_API_KEY, maintenance-window, 2FA, TOTP]
---

# Uptime Kuma

## Monitors are config, not UI state

`config/uptime-kuma/kuma-config.json` is the authoritative source; the
`kuma-init` container runs `kuma-provision.py` against it on **every deploy**
and re-creates anything deleted only through the Kuma UI. If a monitor
reappears after you delete it, that's working as designed — edit the config
file and let the next deploy apply it, don't fight the UI.

Shipped monitors are deliberately minimal — a container healthcheck plus HTTP
checks, nothing that touches the game port:

- **Container** (group "Minecraft") — Docker healthcheck on `mc`.
- **Map**, **Mods**, **Pack**, **Status** (group "Site") — HTTP checks against
  `map.${DOMAIN}`, `mods.${DOMAIN}`, `pack.${DOMAIN}`, `status.${DOMAIN}`.

Every game-port probe that was ever added woke the server from autopause —
don't propose adding one back.

## The 2FA stop-rule — read this before touching Kuma auth at all

The admin account has 2FA enabled. **A password-only login always fails
`authIncorrectCreds`, regardless of whether the password is right.** The
session token (a socket.io JWT) is the only headless credential, and minting
one requires a human entering a TOTP code. If you see `authIncorrectCreds` in
`kuma-init` or `kuma-provision` logs:

1. **Stop calling the login.** Retrying risks Kuma's own rate limit and the
   host's fail2ban — a locked-out account means rebuilding Kuma from scratch.
2. The token has been lost or expired. The usual cause: a full CI deploy
   regenerated the server's `.env` from GitHub secrets while the
   `KUMA_API_KEY` secret was empty (this happened 2026-07-11 — the consumer
   `.env` had no `KUMA_API_KEY` line at all, so `github-env-sync.sh` had
   nothing to push, and the token that had been hand-installed on the server
   was wiped on the next deploy).
3. Recovery is human-only:

   ```bash
   ./ops reauth-kuma          # opens a real Chromium window (Playwright);
                              # log in with password + TOTP yourself.
                              # The session JWT is lifted from localStorage
                              # automatically once you're logged in.
   ./ops github-env-sync      # MANDATORY — otherwise the next full CI
                              # deploy regenerates .env and wipes the token again
   ```

`reauth-kuma` is `kuma-token.sh --browser` under the hood — the recommended
mode. Two other modes exist for edge cases:

- `kuma-token.sh --paste` — you log in yourself in any browser, copy the JWT
  from DevTools → Application → Local Storage, and paste it in.
- `kuma-token.sh --remote` — legacy socket-based login. **Only works against
  Uptime Kuma 1.x**; on 2.x it times out. The python wrapper
  (`uptime-kuma-api` 1.2.1) cannot complete a password+TOTP login against
  Kuma 2.x's socket.io auth at all — that's *why* the browser mode exists.

`KUMA_API_KEY` is a **socket.io session token**, not the Prometheus API key —
don't confuse the two when checking `.env`.

## Clearing a stuck maintenance window — no auth needed

`deploy.sh` opens a Kuma maintenance window (title `Deploy in progress`, via
`kuma-maintenance.py start`) around every full deploy so monitors don't spam
Discord with up/down flaps mid-restart, and closes it again on completion
(`kuma-maintenance.py stop`, called with `|| true` — a Kuma outage must never
fail a deploy). If a deploy dies mid-way and leaves the window open, you don't
need Kuma credentials to clear it — go straight to the database:

```bash
docker exec uptime-kuma sqlite3 /app/data/kuma.db \
  "DELETE FROM maintenance WHERE title='Deploy in progress';"
docker restart uptime-kuma
```

## `KUMA_API_KEY` is not a login you can automate around

Do not write new code that attempts a password+TOTP login programmatically —
`kuma-provision.py` and `kuma-maintenance.py` both already accept
`KUMA_API_KEY` (session token) as the primary auth path and fall back to
username/password only when it's absent. If both are failing, the fix is
minting a fresh token per the recovery steps above, not making the
username/password fallback work harder.
