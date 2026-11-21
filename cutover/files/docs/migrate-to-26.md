# Migrating a 1.21.1 server to the v6 stack (Minecraft 26.3)

`scripts/migrate-to-26.sh` takes a running 1.21.1 server (a v5 bundle in `~/server`) and its consumer repo to the v6 stack in phases. It runs on the operator's machine from the consumer repo and reaches the server through `./ops ssh` and `./ops doctor`. The 1.21.1 world never boots on 26.3: it is archived three ways (a restic snapshot, a cold tar on the host, a copy off the host whose sha256 the operator types back) and removed with `mc` stopped, before the push that runs the first v6 deploy.

## Getting the script onto a v5 consumer

The script ships in the v6 bundle; a v5 consumer's `./ops` has no entry for it. Unpack the v6 bundle beside the v5 one without repointing `.stack/current` (the v5 `./ops` keeps driving the v5 server until the `consumer` phase):

```bash
gh release download v6.0.0 -R piprees/minecraft-server-template -p 'stack-v6.0.0.tar.gz*' -D .stack/v6.0.0
(cd .stack/v6.0.0 && shasum -a 256 -c stack-v6.0.0.tar.gz.sha256 && tar xzf stack-v6.0.0.tar.gz)
M=.stack/v6.0.0/stack/scripts/migrate-to-26.sh
bash $M plan
```

This is the layout `stack-pull.sh` makes, so the `consumer` phase's `./dev update` finds it cached.

## Running it

Every phase is a dry run until `--apply`. A confirm step asks for its own name to be typed, or takes `--yes-<step>`; there is no blanket yes. State is kept in `.migrate-26.state` at the consumer root (git-ignored through `.git/info/exclude`), but each phase checks its preconditions on the server at the moment it runs, so a re-run resumes. Exit 3 means "not yet": a bounded wait ran out or an owner step is due; run the same phase again.

| Phase | Steps | Class | What it does |
| --- | --- | --- | --- |
| `preflight` | A1 | safe | The server is on a 1.21.1 v5 bundle; no workflow run in progress; `./ops doctor` (FAIL lines stop it, `--yes-doctor` goes past); nobody online (RCON silence reads as autopause); free disk for the cold tar; the local `.stack/current` is not v6 |
| `pin` | A2 | confirm (`pin`) | `.stack-version` = the server's `readlink .stack/current`, committed and pushed (a pull-tier deploy of the same release); the `STACK_VERSION` repository variable deleted |
| owner | A3 | owner (`release`) | The v6.0.0 release |
| `archive` | B4 | safe | A backup through the sidecar; its `snapshot <id> saved` line found in bounded log snapshots; `restic ls <id>` lists region files |
| | B5 | safe, then `map` | A render pass (`unmined-render` restart); on the next run, once `./ops map status` is idle, `data/unmined-web` tarred |
| | B6 | confirm (`stop-mc`) | `mc` stopped and left stopped; `data/world data/config` tarred to `backups/world-1.21.1-<date>.tar.gz`; its sha256 recorded and printed with the `scp` line for the off-host copy |
| `host` | C7 | confirm (`firewall`) | `ufw allow` the Bedrock port over TCP and UDP; harden.sh's `mc-bedrock`/`mc-bedrock6` lines added before `# END RATE-LIMIT` in `before.rules`/`before6.rules` (each backed up first); `ufw reload` |
| owner | C7 | owner (`cloud-firewall`) | The Hetzner cloud firewall rules |
| `clean` | C8 | confirm (`clean`) | Refuses unless, checked on the server now: `mc` is stopped, the cold tar exists and matches its sha256, `--offhost-sha` equals it, the restic snapshot is in the repository, the bundle is still v5. Then removes exactly the step 8 list (`bash $M plan` prints it); `--tidy` adds the tidy-up paths |
| owner | D9 | owner (`secrets`) | `FLOODGATE_KEY_B64`, and `STACK_READ_TOKEN` while the template is private |
| `consumer` | D10 | confirm (`consumer`) | `.stack-version` = v6.0.0 first (a v6 `dev` reads the file before the exported variable), then `STACK_VERSION=v6.0.0 ./dev update --scaffold-only`; `deploy.yml` checked for `@v6` and both secrets; the 1.21.1 seed-viewer files (`*_thumb.json`, `*_low.png`, `*_high.png`) removed from the overlay, anything else there listed and kept; one commit; the push that runs the full v6 deploy, its run resolved by commit |
| `verify` | E11 | safe | The server's bundle is v6; `./ops doctor`; `customdim list` (82 dimensions: 4 base, 78 lazy); `customdim audit`; the last 200 lines of mc's log for `ERROR` |
| | E12 | reports | `execute in <level> run seed` for each base world against its definition's `"seed"` (the world seed for `"env"` or none); a mismatch stops the phase and prints `./ops reset-seed --same-seed`, which the script never runs |
| | E13 | safe | `spawn_phantoms` and `log_admin_commands` against `mc_game_rules`; one lazy level opened and its `spawn_phantoms` read against its definition's own value, else the server-wide one; a mismatch stops the phase |
| pointer | E14 | not scripted | § Pre-generation below |
| `first-backup` | E15 | safe | The first restic snapshot of the 26.3 world |
| owner | E16 | owner (`launch`) | The launch joins |

An owner step is printed when it is due; the phase after it stops until `--owner-done <step> --apply`. `bash $M status` prints what is done and what is next.

```bash
bash $M preflight --apply
bash $M pin --apply                         # type "pin", or --yes-pin
bash $M archive --apply --owner-done release
bash $M archive --apply                     # again once the render is idle: type "map", then "stop-mc"
bash $M host --apply
bash $M clean --apply --owner-done cloud-firewall --offhost-sha <sha256 of the off-host copy>
bash $M consumer --apply --owner-done secrets
bash $M verify --apply                      # after the deploy run finishes
bash $M first-backup --apply
bash $M status --apply --owner-done launch
```

### On a linked local stack

`--local <consumer-dir>` runs the same phases against a local stack (compose `local` profile): commands run in the consumer directory instead of over ssh, `host` and the cloud firewall are skipped with a line saying so, nothing is pushed and no `gh` call is made, and the backup sidecar is `mc-backup-local`. In `consumer`, `--link <switched checkout>` runs `./dev link` instead of `./dev update`, then `./dev refresh-config` and `./dev up` start `mc` on an empty world.

## Pre-generation

Not scripted: it runs for hours, one dimension at a time, and stops on a disk projection only a person can weigh. After `verify` and before players are invited: stop `idle-tasks`; pre-generate the base worlds first, one at a time (K6), writing each marker after it finishes; measure the per-1,000-chunk time and disk on the first dimension (`./ops chunky`, `./ops stats --once`, `du -sh` of its `region/` before and after a 1,000-chunk slice) and stop if the projected total exceeds the free disk `./ops doctor` reports less `BACKUP_SIZE_CAP_GIB`. Each definition's `borders.generation` (else `settings.json`'s `defaults.borders`) bounds Chunky; `borders.player` bounds Distant Horizons' `/dh pregen`.

## What it leaves alone

- `data/mods/`: the v6 deploy prunes every jar the 26.3 manifest does not list.
- Player data outside the world (`whitelist.json`, `ops.json`, `usercache.json`, the ban lists), `backups/`, logs, and every config not on the step 8 list.
- The server's `.env`: CI writes it on every full deploy. The script never prints a `.env` value.
- A rollback after the v6 deploy: once the 26.3 world exists, `.stack-version` back to v5 boots a 1.21.1 server on it. A rollback is the cold tar restored with `mc` stopped, on v5.
