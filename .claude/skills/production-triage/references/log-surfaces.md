---
title: Log surfaces
description: Every log source on the Adventure Server, what each filters, how to snapshot it, and latest.log's lifecycle
tags: [logs, docker-logs, latest-log, log4j2, snapshot, streaming, game-log, live-logs, live-stats]
---

# Log surfaces

There are three genuinely different things an agent might mean by "check the logs", and they show different information. Picking the wrong one on a crash-looping container is the most common wrong turn in triage.

## 1. `docker logs mc` — the filtered console

Console output is filtered by `config/log4j2-adventure.xml` to keep the signal-to-noise ratio sane for a human tailing it. `scripts/doctor.sh`'s own recent-errors check reads this same stream and additionally filters out a known-harmless allowlist (`No data fixer registered`, `Error loading class`, advancement-parsing noise, `Error upgrading chunk`, `Failed to load chunk`, `Error executing task on Chunk source main thread executor`) so those don't inflate its error count — they're still visible in the raw stream, just not counted as "non-trivial" by doctor.

Snapshot it with `./scripts/game-log.sh` (aliased as `./ops logs` in a consumer repo):

```bash
./scripts/game-log.sh                                # mc console, last 200 lines
./scripts/game-log.sh nav-proxy --tail 50            # any service, not just mc
./scripts/game-log.sh mc --tail 300 --grep ERROR     # filtered console snapshot
```

`--tail N` and `--grep PATTERN` both work; the pattern is applied server-side via `grep -E` before truncating to N lines, so a large `--tail` on the raw stream is fine — only the matched tail comes back over SSH.

## 2. `./scripts/game-log.sh --latest` — the raw, unfiltered file

```bash
./scripts/game-log.sh --latest                       # data/logs/latest.log, unfiltered
./scripts/game-log.sh --latest --grep 'CME|Exception' # unfiltered raw game log
```

This reads `data/logs/latest.log` directly — everything the console filter hides. Lifecycle: reset on every boot (`OnStartupTriggeringPolicy`), rolled into gzips at 10MB or daily (whichever first), deleted after 3 days. It is never unbounded, so there's no need to worry about disk from this file specifically.

**The trap:** on a crash-looping container, `latest.log` looks completely clean — the crash happens during boot, before mixins finish applying, which is _before_ the file logging appender is even wired up. An agent that checks `--latest` on a crash loop and concludes "no errors, must be something else" has looked in the one place guaranteed to be empty. For crash-loop diagnosis, use surface 3 below instead.

## 3. `docker logs mc` during boot — where init errors actually live

Mixin apply failures, missing-dependency crashes, and any other startup-time error appear in the **container's own stdout**, captured by `docker logs`, because they happen before `data/logs/` exists. This is the same command as surface 1 (`docker logs mc` / `./scripts/game-log.sh`) — the distinction is _when_ you're reading it. During a crash loop, always read this over the raw file:

```bash
./scripts/game-log.sh mc --tail 80
# or, if you have shell access already:
docker logs mc --tail 80
```

`RestartCount` above 0 plus `Mixin apply ... failed` in this output means a broken mod jar — see the crash-loop section of `SKILL.md`.

## Streaming tools — snapshot mode only, ever

`scripts/live-logs.sh` and `scripts/live-stats.sh` both default to an interactive/streaming mode meant for a human at a terminal. Both scripts detect a non-interactive context (`CLAUDE_CODE`, `CODEX`, or `CI` set in the environment) and **automatically fall back to a one-shot snapshot** if called with no arguments — but don't rely on that safety net; call them in snapshot form explicitly:

```bash
./scripts/live-logs.sh mc --tail 200                      # snapshot, one container
./scripts/live-logs.sh mc --tail 500 --grep "ERROR|FATAL" # snapshot + filter
./scripts/live-logs.sh mc --since 1h --grep "joined the game"
./scripts/live-logs.sh mc --errors                        # shortcut for --tail 500 --grep "ERROR|WARN"
./scripts/live-logs.sh --once                              # shortcut for: mc --tail 200

./scripts/live-stats.sh --once             # system load, memory, disk, per-container
                                            # stats, and RCON player list — one shot
```

Any of these called **with no flags at all** (bar `--once`) streams forever via `docker logs -f` / `docker stats` and will block the session. Never do that from an agent context.

## Container-specific quick checks

Some containers are more usefully read directly rather than through the generic log surfaces above, because their state isn't really "logs" so much as a status readout:

```bash
./scripts/chunky.sh                       # Chunky pre-gen progress + completion markers
./scripts/map-render.sh status            # unmined-render container state + recent activity
docker logs discord-sync --tail 50        # slash-command sync status, RCON bridge errors
docker logs mc-backup --tail 50           # look for "snapshot ... saved"
docker logs kuma-init --tail 20           # one-shot; non-zero exit = monitors didn't provision
```
