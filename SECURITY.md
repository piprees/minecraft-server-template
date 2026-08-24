# Security policy

## Supported versions

Only the latest release on the `main` branch is supported with security fixes. We don't maintain older versions or branches.

## Reporting a vulnerability

**Please don't open a public issue for security vulnerabilities.**

Use [GitHub's private vulnerability reporting](https://github.com/YOUR_USERNAME/YOUR_REPO/security/advisories/new) to disclose security issues. You'll get an acknowledgement within 72 hours and a detailed response within a week with next steps.

If you can't use GitHub's reporting tools, open a blank issue with the title "Security concern" and ask for a private channel - don't include vulnerability details in the issue body.

## Attack surface

The project exposes several network-facing services. Knowing the attack surface helps prioritise reports:

| Surface | Protocol | Exposure | Notes |
| --- | --- | --- | --- |
| Minecraft game port | TCP (25577) | Public internet | `ONLINE_MODE=TRUE` + `ENFORCE_WHITELIST=TRUE` in production. Mojang session auth prevents unauthenticated joins. Rate-limited via iptables: 6 new conn/IP/min nominal, though `--hashlimit-burst` recharges on htable expiry so the sustained ceiling is roughly double. No fail2ban jail — the server sees the Docker bridge gateway as the source for every player, so a log-based jail cannot identify one. |
| Simple Voice Chat | UDP (24454) | Public internet | Authenticated players only. The 10 pkt/IP/min limiter sits below ufw's `RELATED,ESTABLISHED` accept, so it only rates flow-opening packets — an established voice stream is not rate-limited, by design. Moving that rule earlier would break voice chat. |
| SSH | TCP (22) | Public internet | Key-only authentication, no root login, fail2ban (4 failures = 24h ban). |
| Web services (map, status, pack, mods) | HTTPS | Via Cloudflare tunnel | HTTP-only tunnel; no direct port exposure. nginx serves static content behind Cloudflare's WAF. No fail2ban jail — traffic arrives through an outbound tunnel, so a host ban on 80/443 sees no packets, and `X-Forwarded-For` is appended to by Cloudflare rather than replaced, making its first element attacker-supplied. |
| RCON | TCP (25575) | Docker network only | Never exposed to the host network or public internet. Accessed only via `docker exec`. |
| Discord bot | Outbound WebSocket | N/A | Connects outbound to Discord's gateway. Bot token is the primary secret; token compromise allows bot impersonation but not server access. |
| Uptime Kuma | HTTP (3001) | Localhost only | Bound to 127.0.0.1. Accessible only from the host or via Cloudflare tunnel. |

### Hardening applied by `harden.sh`

- UFW default-deny inbound; only SSH, game TCP, and voice UDP allowed
- iptables hashlimit rate limiting (game, voice, SYN flood), applied to **both**
  `before.rules` and `before6.rules` — `ufw allow` opens a port on both families
- The trusted-source ACCEPT for Docker bridge traffic is scoped to the bridge
  interfaces. Unscoped it admits any packet from the internet that merely claims
  a `172.16.0.0/12` source, ahead of every limiter; Ubuntu ships `rp_filter=2`
  (loose) and setting it to strict does **not** help, because the kernel takes
  `max(all, interface)` and loose outranks strict
- **fail2ban runs the SSH jail only.** The Minecraft and nginx jails ship
  disabled: neither can see a real client address, and their unanchored filters
  ban whatever address a chat line or a request header happens to name
- `usedns = no`, so a crafted header cannot make the host resolve an
  attacker-chosen name
- Public-IP lookup output is validated before it reaches a root shell or
  `jail.local` — unvalidated it is a remote code execution path
- Docker `iptables=false` so containers can't bypass UFW. Note the consequence:
  with no DNAT, published ports are relayed by `docker-proxy`, so containers see
  the bridge gateway rather than the real client address
- Docker is restarted only when `daemon.json` actually changes
- journald capped at 200 MB; swap limited to 2 GB

### What we consider in scope

- Remote code execution via any exposed service
- Authentication bypass (whitelist, online-mode, SSH)
- Secrets exposure (tokens, passwords, API keys in logs, responses, or git history)
- Privilege escalation (container escape, or any path to root that does not go
  through the `deploy` user). **The `deploy` user reaching root is by design** —
  it holds `NOPASSWD:ALL` and docker-group membership because every deploy
  depends on it. Do not remove that grant; it is not a vulnerability.
- Denial of service that bypasses the existing rate limiting
- Supply chain issues in GitHub Actions workflows

### What we consider out of scope

- Minecraft client exploits or vanilla game bugs (report to Mojang)
- Individual mod vulnerabilities (report to the mod author on Modrinth/GitHub)
- Social engineering attacks against server members
- Attacks requiring physical access to the host machine
- Rate limiting effectiveness under volumetric DDoS (handled by the hosting provider's network-level mitigation)
