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
| Minecraft game port | TCP (25577) | Public internet | `ONLINE_MODE=TRUE` + `ENFORCE_WHITELIST=TRUE` in production. Mojang session auth prevents unauthenticated joins. Rate-limited via iptables (6 conn/IP/min). fail2ban jails for connection spam and login floods. |
| Simple Voice Chat | UDP (24454) | Public internet | Rate-limited (10 pkt/IP/min). Authenticated players only. |
| SSH | TCP (22) | Public internet | Key-only authentication, no root login, fail2ban (4 failures = 24h ban). |
| Web services (map, status, pack, mods) | HTTPS | Via Cloudflare tunnel | HTTP-only tunnel; no direct port exposure. nginx serves static content behind Cloudflare's WAF. fail2ban jail for exploit scans (wp-login, .env probes). |
| RCON | TCP (25575) | Docker network only | Never exposed to the host network or public internet. Accessed only via `docker exec`. |
| Discord bot | Outbound WebSocket | N/A | Connects outbound to Discord's gateway. Bot token is the primary secret; token compromise allows bot impersonation but not server access. |
| Uptime Kuma | HTTP (3001) | Localhost only | Bound to 127.0.0.1. Accessible only from the host or via Cloudflare tunnel. |

### Hardening applied by `harden.sh`

- UFW default-deny inbound; only SSH, game TCP, and voice UDP allowed
- iptables hashlimit rate limiting (game, voice, SYN flood)
- fail2ban jails for SSH, Minecraft connection spam, login floods, and nginx exploit scans
- Docker `iptables=false` so containers can't bypass UFW
- systemd log pipes for fail2ban (Docker container IDs change on recreate)
- journald capped at 200 MB; swap limited to 2 GB

### What we consider in scope

- Remote code execution via any exposed service
- Authentication bypass (whitelist, online-mode, SSH)
- Secrets exposure (tokens, passwords, API keys in logs, responses, or git history)
- Privilege escalation (container escape, deploy user to root)
- Denial of service that bypasses the existing rate limiting
- Supply chain issues in GitHub Actions workflows

### What we consider out of scope

- Minecraft client exploits or vanilla game bugs (report to Mojang)
- Individual mod vulnerabilities (report to the mod author on Modrinth/GitHub)
- Social engineering attacks against server members
- Attacks requiring physical access to the host machine
- Rate limiting effectiveness under volumetric DDoS (handled by the hosting provider's network-level mitigation)
