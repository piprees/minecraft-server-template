---
name: production-triage
description: |
  Diagnoses a live server (production or local) when something seems wrong:
  can't connect, a container crash-looping, RCON silent or timing out, or the
  map, backups, Discord commands or performance looking off. Leads with the
  one-shot `./ops doctor` checklist, then autopause semantics (RCON silence
  with a healthy container is normal), the three distinct "log" surfaces,
  `RestartCount` crash-loop triage, the Uptime Kuma 2FA stop-rule, and the
  known-issues watch list.

  Use when: the server seems down, a restart count is climbing, RCON times
  out but the container looks healthy, Kuma auth fails with
  "authIncorrectCreds", or you hit "Error upgrading chunk" or spark's "Timed
  out waiting for world statistics". Never stream logs, retry a Kuma login,
  `docker restart mc`, or loop-wait for a container.
---

# Production Triage

You are diagnosing a running Adventure Server (Fabric 1.21.1, `itzg/minecraft-server`

- sidecars) that a human has reported as "wrong" in some way. Your job is to find the actual cause with the fewest, safest commands — not to restart things and hope, and not to stream anything waiting for a signal that may never come.

**In scope:** live diagnosis of production or local, autopause vs outage, crash-loop triage, log surfaces, the container inventory, performance profiling, the known-issues watch list, and Uptime Kuma. **Out of scope:** deploy failures specifically (that's a deploy-pipeline concern, not this skill), restore-from-backup procedure, and mod dependency work.

## Forbidden — do not do these, ever

| Action | Why |
| --- | --- |
| `docker logs -f` / `./scripts/live-logs.sh` with no flags / any streaming mode | Blocks forever. Snapshot only: `--tail N`, `--since`, `--grep`. |
| A `while true; sleep; done` wait loop over SSH for a container/log line | A crashing container never becomes healthy — you'll be stuck with no way out. |
| `gh run watch`, or polling `gh run view` more than once or twice | Streams/blocks, or wastes context for nothing. One check, then hand the user the Actions URL. |
| `docker restart mc` (or `./scripts/service.sh restart mc`, which refuses this itself) | Skips the countdown, kick, save-all, and whitelist-restore dance `deploy.sh` does. Use a full deploy or Discord `/mc restart`. |
| Retrying a Kuma login after `authIncorrectCreds` | The admin account has 2FA — password-only logins **always** fail this, regardless of password. Retries risk Kuma's rate limit and the host's fail2ban; a locked account means rebuilding Kuma. See [references/uptime-kuma.md](references/uptime-kuma.md). |
| Polling the game port (25577) on an interval, or any RCON poll loop | Wakes the server from autopause. This killed several earlier Kuma monitor designs — don't reinvent one. |

## Start here, always: `./scripts/doctor.sh`

One SSH round-trip, runs the whole checklist server-side, PASS/WARN/FAIL per item, exits `1` on any FAIL. In a consumer repo the same thing is `./ops doctor`. Everything else in this skill is for when doctor points somewhere specific.

```bash
./scripts/doctor.sh                # uses DROPLET_HOST from .env
./scripts/doctor.sh --threads      # + JVM thread dump: is the main thread
                                    #   progressing or truly stuck? (SIGQUIT,
                                    #   non-invasive — the JVM dumps and carries on)
```

It checks, in order: deployed commit vs `origin/main` (drift), stashed pre-deploy hotfixes, disk usage + biggest `data/` consumers, every expected container (running/healthy/restart count), a stale `.deployed` state file, `ONLINE_MODE`/`ENFORCE_WHITELIST`, RCON player list + spark TPS (silence here is reported as autopause `INFO`, not a failure), the java process state (SIGSTOPped vs live — the same signal that drives autopause), last restic snapshot age, the Discord slash-command registry, the modpack mirror, and `kuma-init`'s exit code. Read its full checklist and thresholds in the header of `scripts/doctor.sh` — that header is the authoritative reference, not this summary.

## Autopause — read this before diagnosing anything

The JVM SIGSTOPs itself after the server has been empty for 10 minutes. **`docker ps` still shows `mc` as healthy while paused** — the healthcheck doesn't touch RCON — and RCON simply stops responding. **Silence + healthy container = paused, not down.** Nothing may poll the game port or RCON on an interval to check for this; that wakes the server (a 4-minute wake/pause churn loop hit production on 2026-07-10 from exactly this mistake in `idle-tasks.sh`).

To tell paused from busy-and-not-responding without touching RCON:

```bash
./scripts/rcon.sh "list"                                   # try it once — silence is not proof either way
ssh ... 'docker exec mc ps -ax -o stat,comm | grep java'    # state starts with T = SIGSTOPped (paused, normal)
                                                              # state starts with S/R = live (busy or wedged — see Crash-loop below)
```

`doctor.sh` already does both of these for you and reports them as `INFO`.

## Symptom → first command

| Symptom | First command | Then |
| --- | --- | --- |
| "Is anything wrong?" / general check-in | `./scripts/doctor.sh` | Follow whatever FAILs |
| Can't connect | `./scripts/doctor.sh` (checks healthcheck, whitelist, RCON in one pass) | `dig mc.DOMAIN`, `sudo ufw status` on the host if doctor passes but players still can't join |
| RCON times out, container shows healthy | `docker exec mc ps -ax -o stat,comm \| grep java` | `T*` = paused, normal. `S*`/`R*` = busy or wedged — see Crash-loop / known issues |
| Server won't start / crash loop | `docker inspect mc --format 'RestartCount={{.RestartCount}}'` | See Crash-loop triage below — check `docker logs mc`, **not** `data/logs/latest.log` |
| Slash commands (`/mc`, `/register`) missing | `docker logs discord-sync --tail 50 \| grep -i "slash"` | Check `[commands] enabled` in the _live_ `data/config/Discord-Integration.toml` — see [references/known-issues.md](references/known-issues.md) |
| Map (map.DOMAIN) down | `docker logs unmined-render --tail 30` | `./scripts/map-render.sh status`, then `./scripts/map-render.sh render` to force a pass |
| Backups look wrong (count/size) | `docker logs mc-backup --tail 50` | Cross-check the restic hostname retention trap — see [references/known-issues.md](references/known-issues.md) |
| TPS bad / lag reports | `docker exec -i mc rcon-cli "spark health"` | `spark profiler start` … 30-60s … `spark profiler stop` |
| Players locked out ("not whitelisted" despite the role) | `docker exec -i mc rcon-cli "whitelist list"` | A dead deploy can leave the whitelist cleared — restore via RCON or re-run deploy |
| Kuma shows a failure / `authIncorrectCreds` in logs | **Do not retry the login.** Read [references/uptime-kuma.md](references/uptime-kuma.md) first | `./ops reauth-kuma` (interactive, needs a human with the TOTP) |

## Log surfaces — there are three different "the logs"

Full detail, filters, and lifecycle in [references/log-surfaces.md](references/log-surfaces.md). The short version:

1. **`docker logs mc`** (or `./scripts/game-log.sh`) — the console, **filtered** by `config/log4j2-adventure.xml` to cut cosmetic noise.
2. **`./scripts/game-log.sh --latest`** — the raw `data/logs/latest.log` file, **unfiltered**. Shows every error the console filter hides. Resets on every boot, rolls at 10MB/daily, deleted after 3 days.
3. **`docker logs mc` during boot** — mixin/init errors live _here_, because they happen before `data/logs/` exists at all. A crash-looping container shows a perfectly clean `latest.log` — checking that file on a crash loop and concluding "no errors" is the single most common wrong turn in this skill. Check the container's own boot log instead.

Never use `docker logs -f` or `./scripts/live-logs.sh`/`./scripts/live-stats.sh` with no flags — those stream forever. Snapshot forms: `--tail N`, `--since 1h`, `--grep PATTERN`, `--errors`, or `--once`.

## Crash-loop triage

A `RestartCount` above 0 is a crash you have not explained yet — never assume it's transient.

```bash
docker inspect mc --format 'RestartCount={{.RestartCount}} Health={{.State.Health.Status}}'
docker logs mc --tail 80                 # init + mixin errors live here, not in data/logs/
ls data/crash-reports/ 2>/dev/null       # tick-loop crashes land here (local checkout only)
```

`Mixin apply ... failed` means a broken mod jar — fixed by pulling the current bundle/redeploying, not by removing mods. If the boot itself hangs (RCON never comes up, `docker ps` never reports healthy, and the log has stopped advancing), check the known-issues watch list before assuming a generic hang — see [references/known-issues.md](references/known-issues.md), especially the c2me/Epic Dungeons main-thread wedge.

## Container inventory (cloud profile)

`doctor.sh` checks all of these by name — if you're checking by hand, this is the list: `mc`, `mc-backup`, `uptime-kuma`, `nav-proxy`, `cloudflared`, `pack-web`, `mod-checker`, `discord-sync`, `idle-tasks`. `unmined-render` and `kuma-init` also run but aren't in doctor's per-container loop (`kuma-init` is one-shot; `unmined-render`'s health is judged by its own logs — see the symptom table above).

## Traps

1. **Kuma auth is human-gated — do NOT debug it by retrying logins.** `authIncorrectCreds` in kuma-init/kuma-provision logs means the admin account's 2FA is doing its job, not that the password is wrong. See [references/uptime-kuma.md](references/uptime-kuma.md) for the full recovery path (`./ops reauth-kuma`, then `./ops github-env-sync` or the next CI deploy wipes the token again).

2. **`Timed out waiting for world statistics` alone is not proof of the c2me wedge.** It also fires through legitimately heavy boots (mass dimension creation can run 10+ minutes). Confirm with `Error upgrading chunk` / `DungeonZombie` counts in the log, and whether the log has stopped advancing at all. Full detail: [references/known-issues.md](references/known-issues.md).

3. **The map has no RCON interface and no live link to mc.** It is rendered by the `unmined-render` sidecar (`scripts/map-render.sh`) into static tiles that nav-proxy serves off a bind mount, so it stays up while mc restarts or autopauses. Renders are incremental — a near-silent pass usually means nothing changed. Diagnose with `docker logs unmined-render`; force a pass with `./scripts/map-render.sh render` (this restarts the sidecar — a restart triggers an immediate render pass).

4. **Kuma monitors deleted in the UI reappear.** `config/uptime-kuma/kuma-config.json` is authoritative; `kuma-init` re-syncs it on every deploy.

5. **Restic snapshot counts that look wrong** are usually the hostname retention trap ([`TROUBLESHOOTING.md#t14`](../../../TROUBLESHOOTING.md#t14)) — pinned `hostname` in `docker-compose.yml`'s `mc-backup` service fixed it once; if it regresses, that's where to look. Restore procedure itself is out of scope here.

6. **Chunky re-generating chunks that were already done** = someone deleted `data/.chunky-*-complete` markers. Check with `./scripts/chunky.sh` before assuming a bug — it reports active tasks, all four completion markers, and the `.skip-pause` autopause-bypass state in one shot.

7. **Missing slash commands (`/mc`, `/register`)** = the `dcintegration` mod's `[commands] enabled` flipped `true` in the _live_ `data/config/Discord-Integration.toml`. The repo's `config/dcintegration/config.toml` is an intent doc, not the live schema — check the container's file, not the repo's.

8. **`signal only works in main thread` in discord-sync logs** means new RCON code bypassed `ThreadSafeRcon` — `mcrcon` arms `SIGALRM`, which raises under `asyncio.to_thread`.

9. **World chunk corruption** (`Corrupted` / read errors on a region file): the server must be stopped first — writing region files while `mc` holds them corrupts them further. `./scripts/wipe-chunk.sh --block X Z --dry-run` shows what would be deleted before you commit to it; it backs up the region file it replaces and prints the exact undo command.

## Validation — prove it actually recovered

Don't report something fixed on a hunch. Before closing out:

```bash
./scripts/doctor.sh                                          # exits 0, all FAILs cleared
./scripts/rcon.sh "list"                                     # answers, or is legitimately autopaused (empty server)
docker inspect mc --format 'RestartCount={{.RestartCount}}'  # stable — not still climbing
```

Plus whatever was actually broken: `docker logs discord-sync --tail 20 | grep -i synced` for slash commands, `./scripts/map-render.sh status` for the map, `docker logs mc-backup --tail 20` for backups. If `doctor.sh` still shows a WARN you can't explain, say so — don't paper over it.

## References

- [references/log-surfaces.md](references/log-surfaces.md) — every log source in detail: what each filters, how to snapshot it, `latest.log`'s lifecycle, and doctor's own error-noise filter.
- [references/known-issues.md](references/known-issues.md) — the standing watch list: the c2me/Epic Dungeons wedge, the c2me `TheChunkSystem` CME, the patched-away Carpet×Supplementaries piston crash.
- [references/uptime-kuma.md](references/uptime-kuma.md) — config-driven monitors, the 2FA stop-rule in full, token recovery, and clearing a stuck maintenance window without auth.
