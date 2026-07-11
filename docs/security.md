# Security

`./ops harden` applies (idempotent, run once at provision — **never during a CI deploy**, it restarts Docker):

- **UFW**: default deny inbound; only SSH, game TCP, voice UDP
- **SSH**: key-only, no root, fail2ban (4 failures → 24h)
- **Rate limiting** (iptables hashlimit in UFW before.rules): game 6 conn/IP/min, voice 10 pkt/IP/min, SYN 30/IP/min; Docker bridge traffic exempt
- **fail2ban jails**: MC connection spam (10 in 2m → 1h), login flood (20 in 5m → 6h), nginx exploit scans (`wp-login.php`, `/.env`, admin panels → 1 week on first hit, banned on the real `X-Forwarded-For` IP); Cloudflare ranges, Docker nets, localhost whitelisted
- **Docker**: `iptables=false` so containers can't bypass UFW (NAT/forwarding handled in before.rules); log rotation
- **systemd log pipes** feed container logs to `/var/log/*` for fail2ban (Docker container IDs change on recreate, so fail2ban can't read them directly)
- **journald** capped at 200MB; 2G swap, swappiness 10
- **`ONLINE_MODE=TRUE` + `ENFORCE_WHITELIST=TRUE`** — non-negotiable in production
