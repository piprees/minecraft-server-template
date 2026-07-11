# Troubleshooting

**Server won't start:**

| Symptom | Fix |
| --- | --- |
| Mod incompatibility | A mod has no 1.21.1 build. Check `./scripts/check-updates.sh`, mark it `?` or remove it |
| Missing dependency | Check the crash log for the mod ID, add the library to the mod list |
| Out of memory | Raise `MEMORY` in `.env`; look for `OutOfMemoryError` in logs |
| Port conflict | `lsof -i :25577` |
| Mod download fails | Verify the slug + pinned ID on Modrinth; use `./dev up --offline` if Modrinth is down |
| Modrinth `429 Too Many Requests` in the seed container | Only possible on a cold resolve cache (first ever boot) — the resolver paces requests and honours `Retry-After`, so it converges in one run. Boots never call the Modrinth API: mods download by direct URL only when missing from `data/mods/` |

**Can't connect:**

| Check           | Command                                                                                 |
| --------------- | --------------------------------------------------------------------------------------- |
| Server running? | `docker ps` — mc should be `(healthy)`                                                  |
| RCON responds?  | `docker exec -i mc rcon-cli "list"` (no response can just mean autopaused)              |
| Whitelisted?    | `docker exec -i mc rcon-cli "whitelist list"` — or did they `/register` + get the role? |
| Firewall open?  | `sudo ufw status` — `25577/tcp` allowed                                                 |
| DNS resolves?   | `dig mc.example.com` → server IP                                                        |
| Correct port?   | `mc.example.com:25577` (the SRV record usually makes the bare hostname work)            |

**Backup fails:** verify R2 credentials in `.env`; `restic snapshots` ("repository does not exist" → `restic init`); `df -h`; `docker logs mc-backup --tail 50`.

**Voice chat:** UDP `24454` must be open in UFW **and** mapped in Docker. Icon error = mod version mismatch, re-import the `.mrpack`. Walls not muffling = Sound Physics is client-side, ships in the pack.

**BlueMap:** force render `bluemap update`; nuclear option `bluemap purge world` then update; check `https://map.example.com` routes through the tunnel.

**Uptime Kuma:** `config/uptime-kuma/kuma-config.json` is the source of truth — `kuma-init` re-syncs on every deploy and recreates anything deleted only via the UI. Monitors are deliberately minimal (container health + HTTP checks): every game-port probe that was ever added woke the server from autopause, so don't add them back.

**Discord:** see [README → Discord integration](../README.md#discord-integration).

**Performance (lag, high MSPT):**

| Action       | Command                                                                               |
| ------------ | ------------------------------------------------------------------------------------- |
| Health check | `docker exec -i mc rcon-cli "spark health"` — TPS 20, MSPT < 50ms                     |
| Profile      | `spark profiler start` … 30-60s … `spark profiler stop`                               |
| Pre-generate | idle-tasks runs Chunky automatically when empty; progress via `/mc status` in Discord |
| Reduce load  | Lower `VIEW_DISTANCE`/`SIMULATION_DISTANCE` in `.env`                                 |
