# Deployment targets

Deploys to any Ubuntu 24.04 machine. `./ops provision` routes to the provider; `.env` holds everything.

| Target | Approximate cost | Notes |
| --- | --- | --- |
| **Hetzner Cloud** | ~€11/mo (cx33 x86) | Free L3/L4 DDoS protection, EU DCs. ARM (cax21, ~€7.5/mo) is cheaper when available — the provision script offers live alternatives if your pick is sold out |
| **DigitalOcean** | ~$48/mo (4vCPU/8GB) | Multiple DC regions available |
| **Local machine** | electricity | Needs static IP or DDNS (`./ops ddns --install-cron`), router port-forwards for `25577/tcp` + `24454/udp`, ideally a UPS |
| **WSL2** | electricity | Use `networkingMode=mirrored` in `.wslconfig`, or `netsh portproxy` (re-run after WSL restarts) |

Home hosting: the Cloudflare tunnel still covers HTTP services; the game port must be forwarded directly (free tier is HTTP-only). Consider [TCPShield](https://tcpshield.com) (free tier) to hide your home IP — add the `tcpshield` mod so the server sees real player IPs, and note it proxies TCP only (voice stays direct).

## Backup alternatives

`mc-backup` is plain restic — swap `RESTIC_REPOSITORY` in `.env` to change backend: Backblaze B2 (`s3:https://s3.REGION.backblazeb2.com/BUCKET`, 10GB free), local path, `sftp:user@host:/path`, MinIO, Wasabi. Init the new repo (`restic -r <repo> init`), restart `mc-backup`, test with `./ops backup`.
