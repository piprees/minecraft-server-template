# Skill brief: `server-provisioning`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

Zero-to-running-server is an eight-script chain (`setup` → `preflight` → `provision` → `harden` → `prepare` → `cloudflare` → `initial-setup` → first deploy), each idempotent, each with its own preconditions, and each with a failure mode that is confusing if you enter the chain in the wrong place. `docs/setup-guide.md` (390 lines) walks it for a human doing it once. An agent is usually asked to do *part* of it — re-run hardening, fix DNS, re-issue a deploy key — and needs to know what that step assumes and what it will restart.

The one thing most likely to burn an agent's session is Cloudflare. There are **three different Cloudflare credential systems**, the stack uses two, they are created on different pages, and the R2 token page shows three values of which only two are correct. `docs/credentials.md` explains this well; nothing routes an agent to it before it has already pasted the wrong value.

## Scope

**In:** provisioning and configuring a server from nothing, and re-running any single step safely. Provider routing, hardening, deploy keys, Cloudflare DNS/tunnel/R2/Worker, the GitHub environment wiring, first boot, and teardown.

**Out:** the `.env` model and secret lifecycle → brief 07 (this skill covers *obtaining* credentials; brief 07 covers *managing* them). Ongoing deploys → brief 02.

## Source material

| File | What to mine |
| --- | --- |
| `scripts/setup.sh` (header, the 18-step workflow block) | The canonical order. Every step names the script it delegates to |
| `docs/setup-guide.md` | The human walkthrough — accounts needed, Discord app setup with intents, the GitHub environment, verification |
| `docs/credentials.md` | **The credential table and the three-Cloudflare-systems section.** The single highest-value reference here |
| `scripts/preflight-check.sh` (header) | Target-aware validation; `--target local|hetzner|digitalocean` |
| `scripts/provision.sh` + `-hetzner` + `-droplet` (headers) | Provider routing via `CLOUDFLARE_PROVIDER`/`CLOUD_PROVIDER`; idempotent detection of an existing server; the ARM/x86 note |
| `scripts/harden.sh` (header) | The seven things it sets up; `--remote` auto-re-exec; **it restarts Docker** |
| `scripts/prepare-droplet.sh` (header) | The six bridging steps: deploy key → GitHub, key to droplet, dir skeleton, stack-pull, `.env`, GH vars |
| `scripts/cloudflare-setup.sh` (header) | The seven ordered resources; the grey-cloud A record + SRV; `--local-host` |
| `scripts/initial-setup.sh` (header) | First-boot-only work deploy.sh assumes exists; `--offline` |
| `scripts/github-env-sync.sh` (header) | The only place GitHub is wired; the silent-skip gate `if: vars.DROPLET_HOST != ''` |
| `scripts/server-power.sh`, `scripts/destroy-server.sh`, `scripts/teardown.sh` (headers) | Power management; clean-slate deletion; the reverse-of-setup with double confirms |
| `scripts/ddns-update.sh` (header) | Local/home hosting only; `--install-cron` |
| `docs/deployment.md` | Targets, costs, WSL2 and home-hosting caveats, TCPShield |
| `docs/security.md` | What hardening actually applies |
| `AGENTS.md:45` | Fixed decision: **Cloudflare tunnels HTTP only.** The game port uses a plain DNS A record |

## Required structure

```
server-provisioning/
├── SKILL.md
└── references/
    ├── credential-sourcing.md   # every credential, where to create it, format, and the traps (Cloudflare especially)
    └── step-chain.md            # each script: preconditions, what it does, what it restarts, how to re-run safely
```

### SKILL.md must contain

1. **The chain as a table with preconditions**, not a prose walkthrough:
   | Step | Command | Assumes | Restarts / destroys |
   | --- | --- | --- | --- |
   | Wizard | `./ops setup` | nothing | — (delegates) |
   | Validate | `./ops preflight` | `.env` populated | — |
   | Create server | `./ops provision` | provider token | — (idempotent; reports existing IP) |
   | Lock down | `./ops harden` | root SSH to a fresh Ubuntu 24.04 | **Docker** — never near a CI deploy |
   | Bridge | `./ops prepare` | hardened server, `gh` authenticated | — |
   | DNS/tunnel/R2 | `./ops cloudflare` | Cloudflare token + zone | restart cloudflared after |
   | First boot | `initial-setup.sh` (on server) | `.stack/current` exists | — (then hands off to deploy.sh) |
2. **The Cloudflare credential section, early and blunt.** Three systems; the stack uses the API Token (Bearer, for DNS/tunnel/R2-bucket/Worker) and the R2 S3 keypair (for restic). The Global API Key is **not used**. The R2 page shows three values — only Access Key ID and Secret go in `.env`; pasting the Token value into `CLOUDFLARE_API_TOKEN` yields `Invalid API Token` on every DNS and tunnel call. Include the exact permission matrix and the verify curl.
3. **The networking fixed decision**, because it is the thing an agent will "helpfully" undo: the game port is a **grey-cloud (DNS-only) A record**, never tunnelled — the free tier is HTTP-only and fails silently. The SRV record hides the port. The tunnel carries map/status/mods/pack only.
4. **Idempotency as a property to rely on**, with the caveat that idempotent ≠ side-effect-free — `harden.sh` restarts Docker every time.
5. **The GitHub wiring gate**: without `./ops github-env-sync`, `deploy.yml` is **silently skipped** (`if: vars.DROPLET_HOST != ''`). Silent skips are the worst failure mode; give it a heading.
6. **Teardown and destroy**, distinguished: `destroy-server.sh` deletes the server + firewall only (dev clean-slate); `teardown.sh` is the full reverse of setup with double confirms and requires human authorisation.

### Traps to capture

1. **Never tunnel the game port.** Free tier is HTTP-only; it fails silently.
2. **`harden.sh` restarts Docker** — provision time only, never during or near a CI deploy.
3. **Verify a new SSH session before closing the root one.** `harden.sh` switches SSH to key-only, no root login. Getting this wrong locks you out of a fresh server.
4. **`CLOUDFLARE_TUNNEL_ID` is generated by `./ops cloudflare`**, never hand-created.
5. **`CLOUDFLARE_ZONE_ID` is auto-resolved** by setup when the token is valid — a hand-pasted wrong one masks a token problem.
6. **`R2_ACCOUNT_ID` is the same value as `CLOUDFLARE_ACCOUNT_ID`.** People look for a separate one.
7. **`RESTIC_PASSWORD` is invented by you and unrecoverable.** Store it before proceeding.
8. **Restart cloudflared after `./ops cloudflare`** or config changes don't load.
9. **Home hosting needs port forwards for `25577/tcp` and `24454/udp`** and either a static IP or `./ops ddns --install-cron`. WSL2 needs `networkingMode=mirrored` or `netsh portproxy` re-applied after every WSL restart.
10. **`gh release list --limit 1` lies** and **GitHub release tags don't always exist on Docker Hub** — relevant when pinning provider images or CLI versions during setup.

### Validation section

```bash
./ops preflight                     # per-item pass/fail before anything is created
curl -s "https://api.cloudflare.com/client/v4/user/tokens/verify" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN"     # → "status": "active"
./ops github-env-sync --check
./ops doctor                        # after first deploy
dig mc.example.com                  # → server IP, grey cloud
```

## Done when

- An agent asked to "fix the Cloudflare setup" identifies which of the three credential systems is involved before touching anything.
- An agent asked to re-run one step knows what that step assumes and whether it will restart Docker.
- The skill never duplicates `docs/credentials.md`'s table wholesale — it links, and reproduces only the two trap paragraphs.
