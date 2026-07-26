# Findings — repo problems surfaced while building the skills

Every problem found during the skills work, 2026-07-26. Building a skill means verifying every path and command against the repo rather than trusting the docs, which is why this list exists — none of it was the goal, all of it fell out of the checking.

**Confidence column:**
- **Verified** — I confirmed it myself against the file or a command's output.
- **Agent-reported** — a sub-agent found it and gave specific evidence; plausible and specific, but I have not independently re-checked.

Nothing below has been fixed except the five items in § 0.

---

## 0. Already fixed

| # | File | Change | Why |
| --- | --- | --- | --- |
| 0.1 | `README.md:372` | `restic snapshots --last 5` → `--latest 5` | **Verified.** `--last` does not exist in restic 0.19.1; there is only `--latest n`. The documented disaster-recovery procedure failed at that step. `scripts/doctor.sh:230` already used the correct flag. |
| 0.2 | `docker-compose.yml` (both `mc-backup` and `mc-backup-local`) | Retention fallbacks `7/4/2` → `3/1/1` | **Verified.** Docs said 3/1/1; compose fell back to 7/4/2. See § 1. |
| 0.3 | `docker-compose.yml` (both services) | `BACKUP_INTERVAL` fallback `6h` → `12h` | **Verified.** See § 1. |
| 0.4 | `README.md` § Backups | "every **6h** by default" → "every **12h**" | Follows 0.3. |
| 0.5 | `docker-compose.yml` | Comment "performs daily (by default) backups" → "performs backups on `BACKUP_INTERVAL`" | **Verified.** It was neither daily, 6h, nor 12h — just stale. |

`docker compose config -q` passes on both profiles after these changes.

---

## 1. The backup defaults never matched the documented policy — and elfydd was running the undocumented ones

**Verified. This is the one with a real cost attached.**

`.env.example:119-122` lists the backup settings as commented-out lines:

```
# BACKUP_INTERVAL=12h
# RESTIC_RETENTION_DAILY=3
# RESTIC_RETENTION_WEEKLY=1
# RESTIC_RETENTION_MONTHLY=1
```

Per that file's own conventions block, `# VAR=` means *"optional — uncomment to override the platform default"*. So those four lines documented the intended policy while the actual platform default was whatever `docker-compose.yml` fell back to: **`6h` interval, `7/4/2` retention**.

`~/Projects/elfydd/.env` contains **none** of those four keys. It ran entirely on the compose fallbacks — twice the snapshot rate and roughly double the retention slots that the docs promised, against a 10 GiB cap.

That is a contributing factor to the 50 GiB blow-out, though **not the root cause**. The root cause is the one already documented as `AGENTS.md` trap 14: `restic forget` groups by `(host, paths)`, `mc-backup`'s hostname defaulted to its container id, and every full deploy recreated the sidecar — so each deploy era became its own retention group whose last snapshots were stranded permanently. That was fixed by pinning `hostname:` in compose. The interval/retention correction reduces volume on top of that fix; it does not replace it.

**Worth doing now the defaults changed:** on the next backup run, any server that had been on 7/4/2 will prune down to 3/1/1 in one go. That is a one-way delete of the surplus snapshots, and it happens on the first run rather than gradually.

---

## 2. BlueMap was removed but ~12 places still describe it — including agent-facing incident instructions

**Verified.** Commit `476967f feat!: map.DOMAIN uNmINeD static shell — BlueMap removed (#10)` removed the `bluemap` service and introduced `unmined-render`. The compose file is correct. Almost nothing else was updated.

Current compose services (`docker compose --profile cloud --profile local config --services`):

```
cloudflared, discord-sync, idle-tasks, kuma-init, mc, mc-backup, mc-backup-local,
minio, minio-init, mod-checker, nav-proxy, pack-web, seed, unmined-render, uptime-kuma
```

**There is no `bluemap` container.**

### 2a. Actively misleading — these tell an agent to run commands against a container that does not exist

| Location | Problem |
| --- | --- |
| `AGENTS.md:146-149` (trap 13) | Whole trap. Says "the `bluemap` container renders and serves the map", instructs `docker logs bluemap`, `docker restart bluemap`, describes BlueMap's `render-mask` config format, the ~79-map load window, and the macOS `-u` watcher. All obsolete. **This is trap 13 in the file agents are told to read first, and it fires during a map incident.** |
| `docs/troubleshooting.md:29` | Same: "runs as the `bluemap` sidecar container", `docker logs bluemap --tail 30`, `docker inspect bluemap`, `docker restart bluemap`, "delete `data/bluemap/web/maps/<map>/`". |
| `AGENTS.md:266` (Web surfaces table) | "map.DOMAIN │ BlueMap webapp (upstream); 'map sleeping' fallback page is inline HTML in `config/nginx/nav-proxy.conf`". The map is now a static uNmINeD render off a bind mount, and grep finds **no** "sleeping" fallback page anywhere in `config/nginx/`. |
| `AGENTS.md:174` | "BlueMap does not auto-discover runtime dimensions… until a map config is written and `bluemap reload` is called." The underlying concern may still apply to `unmined-render`, but the mechanism named does not exist. Needs restating, not just deleting. |
| `AGENTS.md:255` | "…and centres the BlueMap webapp on it" — needs checking against what `deploy.sh` actually does now. |
| `config/modrinth-mods.txt:241` | Comment: "bluemap runs as a standalone CLI sidecar container (see docker-compose.yml)" — it doesn't. |

### 2b. Dead code and dead weight

| Location | Problem |
| --- | --- |
| `docker/defaults-seed/Dockerfile:46` | Still `COPY config/bluemap/ /defaults/config/bluemap/`. **79 per-dimension map configs plus `core.conf` are seeded into every consumer's `data/config/bluemap/`** for a service that no longer exists. |
| `config/bluemap/` | The 79 configs themselves, `core.conf`, and a README. Vestigial. |
| `scripts/dev-up.sh:562-570` | A whole block that reads `data/config/bluemap/core.conf` and rewrites `accept-download: false` → `true`, then prints "BlueMap: auto-accepted resource download." Dead. |
| `scripts/doctor.sh:109,115` | Disk triage references `data/bluemap` — `res FAIL "…free space urgently (check data/bluemap first)"` and a `du -sh data/world data/bluemap …` in the largest-dirs line. Points triage at a directory that is no longer produced. |
| `scripts/cloudflare-setup.sh:277,369` | Comments and user-facing output describe `map.DOMAIN` as BlueMap. |

### 2c. Still correct, leave alone

`AGENTS.md:173` (seedtest dirs still get `config/bluemap` — true, *because* of the Dockerfile COPY above), `AGENTS.md:180` (historical incident context), `README.md:359` (the restic `EXCLUDES` list genuinely still contains `bluemap`), `mods/AGENTS.md:285` (architectural history).

**Note the coupling:** fixing 2b's Dockerfile COPY also invalidates `AGENTS.md:173`. Do them together.

---

## 3. Two of four version holds don't protect the server mod list

**Verified.** `scripts/pin-mod-versions.sh` reads holds from `modpack/adventure.mrpack.json` → `_clientMods.holds` (line 209) and has exactly **one** `if slug in holds` check (line 234), inside the client-manifest re-pin loop. The loop that rewrites `config/modrinth-mods.txt` has no holds check at all.

| Held slug | In client pack | In server list | Hold effective? |
| --- | --- | --- | --- |
| `c2me-fabric` | No | **Yes** | **No — enforces nothing** |
| `critters-and-companions` | Yes | Yes | Client entry only |
| `xaeros-world-map` | Yes | No | Yes |
| `xaeros-minimap` | Yes | No | Yes |

`c2me-fabric`'s hold reason is *"0.4.0-alpha.0.21 wedges fresh-world creation"* — the same failure class as the standing known-issues wedge. The weekly `mod-updates.yml --apply` would re-pin it to latest and nothing mechanical would stop it; it survives only because a human reads the PR diff.

`AGENTS.md:242` states "`pin-mod-versions.sh` and the weekly `mod-updates.yml` respect holds" without qualification. True for client mods only.

**Suggested fix:** either have the server-list loop consult the same `holds` map, or move holds to a top-level key that both loops read. The second is cleaner — a hold is a statement about a mod, not about a distribution channel.

---

## 4. `discord-sync.py` is not bind-mounted, and its own docstring says it is

**Verified.** `docker/discord-sync/Dockerfile` does `COPY scripts/discord-sync.py /app/` at image build time. The `discord-sync` service in `docker-compose.yml` mounts only `./data:/data` and `/var/run/docker.sock`. There is no script mount.

`scripts/discord-sync.py:10-12` claims:

> "Runs in the discord-sync container (both compose profiles) with this file bind-mounted read-only - changes need a force-recreate, not a restart (deploy.sh and the CI infra step both do this)."

A force-recreate picks up nothing. Shipping a bot code change actually requires: push to main → image rebuild → cut a release → a consumer push resolves to the new tag → tier detection forces **full** (not infra) → `deploy.sh` step 6 pulls the new `IMAGE_TAG` and step 11 force-recreates the container.

This matters because the stale docstring would send someone force-recreating a container repeatedly wondering why their fix isn't live.

---

## 5. Discord role sync matches by role **name**, not role ID

**Verified.** `scripts/discord-sync.py:74-75`:

```python
PLAYER_ROLE = "Player"
ADMIN_ROLE  = "Admin"
```

Literal, case-sensitive strings checked against `member.roles` names. `DISCORD_ADMIN_ROLE_ID` / `DISCORD_PLAYER_ROLE_ID` from `.env` appear nowhere in the sync path — they are used only to build `<@&id>` mentions in message templates.

**Renaming the "Admin" or "Player" role in the Discord server silently stops whitelist and op sync, with no error anywhere.** The variables named `..._ROLE_ID` actively imply the opposite mechanism.

---

## 6. `deploy.sh`'s "keep in sync" comments are stale, and a manual deploy skips kuma-init

**Verified.** `scripts/deploy.sh:27` and the inline comment above the sidecar list both say the force-recreate list must match `infra-deploy.sh`. They do not match: `infra-deploy.sh:43` includes `kuma-init`, `deploy.sh:666` does not.

That is compensated in three of four paths:

| Path | Refreshes kuma-init |
| --- | --- |
| CI, any non-pull tier | `deploy-reusable.yml:491` "Refresh kuma-init" |
| `./ops update` | `remote-update.sh:81` |
| Infra tier | inline in `infra-deploy.sh:43` |
| **Bare manual `deploy.sh --non-interactive` over SSH** | **Nothing** |

So a hand-run deploy leaves Kuma un-reprovisioned — relevant given the Kuma maintenance-window trap. The comments should say this rather than asserting a symmetry that doesn't hold, or someone will "fix" the asymmetry and break the CI path.

---

## 7. Documentation referring to things that don't exist

| # | Location | Problem | Confidence |
| --- | --- | --- | --- |
| 7.1 | `CONTRIBUTING.md:120` | Tells contributors to add config dirs to "`MC_PATTERNS` in `.github/workflows/deploy.yml`". No such variable exists. The real mechanism is `FULL_PATTERNS` in `deploy-reusable.yml`: `^overlay/config/\|^overlay/mods-extra\.txt$\|^overlay/mods-remove\.txt$` | Agent-reported, consistent with my own reading of `deploy-reusable.yml` |
| 7.2 | `AGENTS.md` § Web surfaces, `docs/customisation.md` | Both say the nav bar exists in **five** copies. `grep -c '<nav' config/nginx/nav-proxy.conf.template` returns **4** | **Verified (count)** |
| 7.3 | `config/nginx/nav-proxy-local.conf` | Not a sixth nav copy — it's a stray **empty directory**, gitignored, referenced by nothing. Classic bind-mount-to-nonexistent-path artefact | Agent-reported |
| 7.4 | `config/uptime-kuma/custom.css` | Dead file. The live mechanism is the `customCSS` string inside `kuma-config.json`, applied by `kuma-provision.py`. `docs/customisation.md` points at the dead file | Agent-reported |
| 7.5 | `examples/consumer/overlay/modpack/README.md` | Its worked example shows `{"_clientMods": {"required": [{"slug":…, "versionId":…}]}}`. `merge-manifest.py` doesn't read that shape at all — it reads `{"add": {"required": ["slug:versionId"]}}`. Copying the shipped example produces a no-op | Agent-reported |
| 7.6 | `examples/consumer/AGENTS.md` | "Add a client mod → Not here — PR to the template repo." Wrong: `overlay/modpack/manifest.json` is a real merge mechanism (`entrypoint.sh` → `merge-manifest.py`) supporting `add.required`, `add.optional`, `remove`. A consumer *can* add or remove a client mod for slugs already in the catalogue | Agent-reported |
| 7.7 | `COMMANDS.md` | Documents 19 `/mc` subcommands; the code has 20. **`/mc border` is missing** (sets the player world border and matching Chunky pre-gen borders, fully audit-logged). `setup-permissions.sh`'s header says to keep COMMANDS.md in step and nothing enforces it — this is that gap already having fired | Agent-reported |
| 7.8 | `examples/consumer/commands.json` | Out of sync with the dispatchers. Missing ops: `shutdown`, `startup`, `reboot` (→ `server-power.sh`). Missing dev: `refresh-config`, `seed-viewer` | Agent-reported |
| 7.9 | `examples/consumer/AGENTS.md` | Documents `./dev sync` as a real command. It is a deprecation shim that prints a warning and `exec`s `./ops sync` | **Verified** |
| 7.10 | `.env.example` | Lists a 6th Cloudflare token permission (`Zone / Cache Purge : Purge`) that neither `setup.sh` nor `docs/credentials.md` mention, and no `/purge_cache` call exists in `cloudflare-setup.sh`. Either the permission is unnecessary or the other two docs are incomplete | Agent-reported |
| 7.11 | `README.md` example `.env` | Shows `STACK_VERSION=v1` and the Upgrading section talks about `v1`, while `.env.example` ships `v2` and `examples/consumer/.github/workflows/deploy.yml` pins the reusable workflow `@v2` | **Verified** |

---

## 8. Smaller things worth knowing

| # | Finding | Confidence |
| --- | --- | --- |
| 8.1 | `config/modrinth-mods.txt` has one malformed optional marker: `attributefix?:XwbErf6s` — the `?` is before the colon instead of at line end, so `pin-mod-versions.sh`'s optional detection doesn't recognise it. Harmless today (resolution goes by immutable version id), but it's a pattern nobody should copy | Agent-reported |
| 8.2 | Running `./scripts/dev-up.sh` bare from a platform checkout — which `CONTRIBUTING.md` explicitly instructs — resolves `CONSUMER_DIR` to `/Users`, because the script's path math assumes `<consumer>/.stack/vX.Y.Z/stack/scripts/` nesting. Its own header says "called by the consumer's `dev` script, not directly". `CONTRIBUTING.md` should either say `docker compose --profile local up -d` or document `CONSUMER_DIR="$(pwd)"` | Agent-reported (agent simulated the path walk) |
| 8.3 | `scripts/service.sh` hard-refuses raw `mc` lifecycle ops with `die "Refusing raw MC lifecycle operation..."`. `AGENTS.md` trap 5 describes this as an unfixed "enforcement gap" — it appears to have been closed. Worth re-reading trap 5 against the current script | Agent-reported |
| 8.4 | `.github/workflows/mod-build.yml` hardcodes `mods/custom-dimensions` and a `customdimensions-*.jar` glob, while `release.yml` iterates `mods/local-mods.manifest`. Adding a second in-house mod would build in release but not in PR CI | Agent-reported |
| 8.5 | `lint.yml`'s bundle-manifest job catches three patterns only: explicit `script_file="X"` mappings in `ops`, `ALLOWED_COMMANDS` entries assumed to map to `scripts/<name>.sh`, and `$SCRIPT_DIR/*.sh` references inside already-manifested scripts. A script wired any other way — called by full path from `deploy.sh`, or run only manually — is not caught, and CI stays green while the omission ships | Agent-reported |
| 8.6 | `destroy-server.sh` is **not** in the bundle `MANIFEST` and not in `ops`'s `ALLOWED_COMMANDS` — platform-repo-only, Hetzner-only. Consumers wanting the same outcome need `./ops teardown --target hetzner`. Separately, `ddns-update.sh` **does** ship in the bundle but has no `./ops` command, so it must be invoked by full path | **Verified** (`grep -c destroy-server scripts/build-stack-bundle.sh` → 0) |
| 8.7 | CI's jar class-count floor is a hardcoded **10**, not "non-zero" — worth knowing if a genuinely tiny mod is ever added | Agent-reported |

---

## 9. Narration — docs describing what the repo *was* rather than what it *is*

A handbook describes the car you bought. It does not describe the trim levels that were cancelled, the previous model's dashboard, or the twenty thousand decisions that never shipped. A lot of this repo's prose does exactly that.

### The rule

Two things look similar and are not:

| | Example | Verdict |
| --- | --- | --- |
| **Present-tense absence** | "There is no RCON interface." "There is no `~/server/scripts/` — `deploy.sh` ships in the bundle." "There is no cross-portal weather relay and there cannot be one." | **Keep.** This stops a reader hunting for something, or attempting something impossible. It describes the thing as it is. |
| **Past-tense change narration** | "X was removed in v2.14.0." "Replaced by Y." "The old Z used to…" "…historically never fired." | **Delete.** The reader was not here before. The change is in the git log and the CHANGELOG, which is what those are for. |

Incident evidence in `AGENTS.md` traps (`2026-07-13: a nav-proxy upstream change "passed" while proxying to the old upstream`) is a third category and stays — it is the proof that a live constraint is real, not a history lesson. The test: **does removing the past-tense clause make the instruction weaker?** If no, cut it.

### Fixed in this pass

| Location | Was |
| --- | --- |
| `README.md` scripts table | A row for `setup-dimensions.sh` reading `_(removed)_ │ Replaced by mod-owned boot-time creation…`. A table of scripts listed a script that does not exist. |
| `examples/consumer/README.md` | "…no sign-marker layers (those needed the old in-process BlueMap mod)." → now just states the map is a static terrain render. |
| `AGENTS.md` § CI discipline | "…(this is what rolls platform releases out; symbolic-vs-symbolic comparison historically never fired)" → the live half kept, the history dropped. |
| `AGENTS.md` trap 2 | "…this replaces the old bind-mount recreate trap." → cut; the trap describes the current mechanism. |
| `config/modrinth-mods.txt` | My own first attempt at this fix said "not by a server mod - no map mod belongs in this list", which is the same error in a new coat. Now one line: what renders the map and where to find it. |

### Still outstanding

| # | Location | Problem |
| --- | --- | --- |
| 9.1 | **`docs/customisation.md` § Map markers** | Worse than narration — **dead instructions**. It opens with a note that the sign-marker mod was removed, then documents adding static markers to `config/bluemap/maps/`. That directory is now deleted and nothing read it before that. The whole section tells a reader to do something with no effect. Either document markers for the current renderer or cut the section. |
| 9.2 | `config/modrinth-mods.txt:51,171,232` | Three commented-out pins carrying removal rationale (`# custom-dimensions:… # disabled: broken on 1.21.1 …`, `# nether-portal-spread:… # retired v3.7.0: replaced by …`, `# datapack:fast-nether-portals:… # replaced by … gamerule`). **Genuine trade-off:** they are tombstones, but two of them plausibly stop someone re-adding a mod that broke things. My suggestion: keep a single short "don't add these and why" block at the bottom of the file rather than three commented pins inline, so the list reads as a list. |
| 9.3 | `mods/AGENTS.md` netherportalspread trap | A full trap for a mod retired in v3.7.0, kept because "the diagnostic technique generalises to any block-converting mod". If the technique is the value, write the technique; don't keep a trap about a mod that isn't shipped. |
| 9.4 | `mods/AGENTS.md` immersive verification recipe | "The old recipe here hand-patched `log4j2-adventure.xml` inside the stack-config volume…" — a paragraph about a previous version of the document. The live instruction (`CUSTOMDIM_LOG_LEVEL` is an env var, not a file patch) survives without it. |
| 9.5 | `docs/known-issues/carpet-supplementaries-piston-crash.md:10` | "— the note itself was never committed and `mods/.ideas/` no longer exists." Narration about a missing file. |
| 9.6 | `scripts/seed/score-seed.sh:10` | "`roll-seeds.sh` no longer calls it." If nothing calls it, the question is whether the script should exist, not whether the comment is accurate. |
| 9.7 | `scripts/seed/roll-seeds.sh:52` | "The old wide `seed-results.csv` is NOT written any more" — describes an output that doesn't exist. |
| 9.8 | `AGENTS.md` trap 15 | Legitimately live *for now* — it carries a real remediation for servers predating v3.10.1. Worth deleting once every server is past it, or it becomes narration by attrition. Flagging so it doesn't quietly outlive its purpose. |
| 9.9 | `scripts/harden.sh:356` | Raises the inotify watcher limit with the comment "BlueMap needs one inotify watcher per map — 78+ maps exhaust the default 128 limit". The current renderer polls on an interval and watches nothing. The sysctl is harmless; the justification is void. |
| 9.10 | `scripts/idle-tasks.sh:162,258` | Two comments explaining that no map trigger is needed because "the bluemap sidecar watches region files". The conclusion still holds; the stated reason is wrong (the current renderer runs on `UNMINED_INTERVAL`). |
| 9.11 | `docker-compose.yml` restic `EXCLUDES` | Still lists `bluemap`. Harmless and defensive, but it is a path that is never created. |

### A note on scale

`grep -rniE 'no longer\|used to \|formerly\|previously\|was removed\|replaced by\|the old '` across the doc surface and script headers returns **68 hits**. Most are legitimate (code comments explaining why a fix is shaped as it is; present-tense statements of absence). The list above is the subset that describes the repo's past to a reader who only needs its present. Worth one deliberate pass rather than fixing opportunistically, because the pattern is self-propagating: I reproduced it myself within an hour of starting.

---

## Suggested order of work

1. **§ 1 follow-through** — done, but watch the first backup run after deploy for the one-off prune.
2. **§ 2a** — the agent-facing BlueMap instructions. Highest value: it fires during an incident, in the file agents read first.
3. **§ 3** — the `c2me-fabric` hold. A latent auto-bump into a known wedge.
4. **§ 4 and § 5** — both are "the docs describe a different mechanism than the code", both cause silent failures.
5. **§ 2b** — dead code and the 79 seeded configs. Cosmetic but it's shipping to every consumer.
6. **§ 6, § 7, § 8** — doc sync, no urgency individually.
