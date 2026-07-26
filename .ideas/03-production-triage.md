# Skill brief: `production-triage`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

This is the "something is wrong" skill, and it is the one where an agent's default instincts are most dangerous. The reflexes a model brings — tail the logs, retry the login, restart the container, poll until healthy — are each individually forbidden here, for reasons that have all already caused an incident:

- `docker logs -f` and `gh run watch` block forever (`AGENTS.md:339`)
- retrying a Kuma login risks fail2ban locking the account, and the password is *never* the problem (`AGENTS.md:130-131`)
- `docker restart mc` skips the countdown, kick, save and whitelist dance (`AGENTS.md:337`)
- a `while true; sleep; done` wait on a crashing container never exits (`AGENTS.md:339`)
- RCON silence is normally autopause, not an outage (`AGENTS.md:66`)

Meanwhile the actual triage checklist exists — as the 30-line header comment of `scripts/doctor.sh` — and nothing routes an agent to it.

## Scope

**In:** diagnosing a live server (production or local). Where each log surface lives and what it hides, autopause semantics, crash-loop triage, the container inventory, `./ops doctor`, performance profiling, the standing known-issues watch list, and the monitoring stack (Uptime Kuma, including its human-gated auth).

**Out:** deploy failures specifically → brief 02 (cross-reference). Backups and restore → brief 08. Fixing a mod → brief 01.

## Source material

| File | What to mine |
| --- | --- |
| `scripts/doctor.sh` (header, lines 1-30) | **The checklist.** Every item, in order, with its PASS/WARN/FAIL thresholds and exit-code semantics |
| `AGENTS.md` § Production access, § Safety rules 10-11 | Snapshot-never-stream; the four `./ops` entry points; no unbounded loops; don't poll CI |
| `AGENTS.md` traps 1, 3, 5, 6, 7, 8, 13, 14 | Discord registry, mcrcon threading, whitelist lockout, Kuma config-driven, **Kuma auth stop-rule**, Chunky markers, BlueMap sidecar, restic hostname |
| `AGENTS.md § Known issues` | Epic Dungeons → c2me main-thread wedge (two triggers, the spark false-positive caveat, recovery); c2me `TheChunkSystem` CME |
| `docs/troubleshooting.md` | Startup / connect / backup / voice / BlueMap / Kuma / performance tables |
| `docs/known-issues/carpet-supplementaries-piston-crash.md` | The one fully written-up known issue — mirror its shape |
| `scripts/game-log.sh` (header) | **The log-surface distinction**: `docker logs` is filtered by `log4j2-adventure.xml`; `--latest` reads the unfiltered file appender. `latest.log` lifecycle |
| `scripts/live-logs.sh`, `scripts/live-stats.sh` (headers) | Snapshot modes; the explicit "humans only" streaming modes |
| `scripts/chunky.sh`, `scripts/map-render.sh`, `scripts/service.sh` (headers) | Pre-gen status, map sidecar, sidecar lifecycle (mc excluded) |
| `scripts/kuma-token.sh` (header) | `--browser` / `--paste` / `--remote`; why the python wrapper cannot log in to Kuma 2.x |
| `scripts/idle-tasks.sh` (header) | Autopause interaction: `/data/.skip-pause`, JVM process-state check *before* RCON |
| `examples/consumer/AGENTS.md:63-71` | Crash-loop triage: boot failures die before `data/logs/` exists |
| `README.md` § Architecture | The container inventory — what should be running |

## Required structure

```
production-triage/
├── SKILL.md
└── references/
    ├── log-surfaces.md      # every log source, what it filters, how to snapshot it
    ├── known-issues.md      # the standing watch list with symptoms, diagnosis, recovery
    └── uptime-kuma.md       # config-driven monitors, the 2FA stop-rule, maintenance-window recovery
```

### SKILL.md must contain

1. **`./ops doctor` first, always.** Lead with it. It is one SSH round-trip, it runs the whole checklist server-side, and it exits 1 on any FAIL. Everything else in the skill is for when doctor points somewhere.
2. **A forbidden-actions box in the first 40 lines.** Streaming, unbounded loops, `gh run watch`, `docker restart mc`, retrying Kuma logins, poking the game port on an interval. Each with its one-line reason.
3. **The autopause model**, before any diagnosis: the JVM freezes after 10 minutes empty; `docker ps` still shows healthy; RCON stops responding. *Silence + healthy container = paused, not down.* Nothing may poke the game port on an interval — that defeats autopause and killed several Kuma monitor designs.
4. **A symptom → first command table.** This is the skill's spine. Rows for: can't connect, server won't start, crash loop, slash commands missing, map down, backups look wrong, TPS bad, players locked out, RCON times out but container healthy.
5. **Log surfaces, explicitly.** Three different things called "the logs":
   - `docker logs mc` — console, **filtered** by `config/log4j2-adventure.xml`
   - `./ops logs --latest` — `data/logs/latest.log`, **unfiltered**, shows what the console hides
   - `docker logs mc` during boot — where mixin/init errors live, *because they die before `data/logs/` exists*
   An agent that checks `latest.log` on a crash-looping container sees a clean file and concludes wrongly.
6. **Crash-loop triage** as a distinct branch, with `docker inspect mc --format 'RestartCount={{.RestartCount}}'` and the rule: RestartCount above 0 is a crash you have not explained yet.

### Traps to capture

1. **Kuma auth is human-gated. Do NOT debug it by retrying logins.** The admin account has 2FA, so password-only logins *always* fail `authIncorrectCreds` — the password is not wrong, it is missing a TOTP. Repeated attempts risk Kuma's rate limit and the host's fail2ban; a locked account means rebuilding Kuma. Recovery is `./ops reauth-kuma` (interactive) then `./ops github-env-sync`, because an empty `KUMA_API_KEY` GitHub secret is what wipes a hand-installed token on the next full deploy. A stuck maintenance window can be cleared without auth via the `sqlite3` DELETE.
2. **`spark`'s `Timed out waiting for world statistics` is not proof of the wedge.** It also fires through legitimately heavy boots (mass dimension creation runs 10+ min). Confirm with `Error upgrading chunk` / `DungeonZombie` counts and whether the log has stopped advancing.
3. **BlueMap is a sidecar since v2.14.0.** There is no `bluemap` RCON command. Diagnose with `docker logs bluemap`; allow ~5 minutes after restart to load ~79 maps before concluding failure; on macOS Docker the bind-mount watcher may never fire — validate with `docker restart bluemap`.
4. **Kuma monitors deleted in the UI reappear** — `config/uptime-kuma/kuma-config.json` is authoritative and kuma-init re-syncs every deploy.
5. **Restic snapshot counts that look wrong** are usually the hostname retention trap (trap 14) — cross-reference brief 08 rather than duplicating.
6. **Chunky re-generating done chunks** = deleted `data/.chunky-*-complete` markers.
7. **Missing slash commands** = the dcintegration mod's `[commands] enabled` flipped true in the *live* TOML. The repo's `config/dcintegration/config.toml` is an intent doc, not the live schema. Cross-reference brief 10.
8. **`mcrcon` arms SIGALRM** — `signal only works in main thread` in discord-sync logs means new RCON code bypassed `ThreadSafeRcon`.

### Validation section

The skill should end with a "prove it recovered" block: `./ops doctor` exits 0, `./ops rcon "list"` answers (or is legitimately paused), `docker inspect mc` RestartCount stable, and the specific check for whatever was broken.

## Done when

- An agent handed "the server seems down" runs `./ops doctor` first, correctly identifies autopause as not-an-outage, and never streams a log or retries a Kuma login.
- The known-issues reference is a living list an agent can extend, matching the shape of `docs/known-issues/carpet-supplementaries-piston-crash.md`.
