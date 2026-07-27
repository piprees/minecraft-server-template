---
name: deploy-pipeline-operations
description: |
  Triggers, monitors, and recovers deploys of the Adventure Server platform (piprees/minecraft-server-template) through .github/workflows/deploy-reusable.yml. Covers the two-stage tier detection that picks full/infra/pull (STACK_VERSION resolved against `readlink .stack/current`, then FULL_PATTERNS diffing consumer files against the last deployed commit), the pre-push and post-push checklists, resolving a CI run by commit sha, the deploy.sh 17-step server-side sequence, manual dispatch, and recovering a deploy that dies mid-run.

  Use when: told to "deploy this", push to main and check whether CI is deploying, decide which tier a change will trigger, resolve a `gh run` after pushing, recover from a failed or stuck deploy, or force a full deploy via manual dispatch or `./ops update`. Also use when troubleshooting "client_loop: send disconnect: Broken pipe", "Server failed to respond to RCON after deploy", or "No platform release matches STACK_VERSION=" during a deploy.
---

# Deploy pipeline operations

Deploying is the highest-consequence routine action in this repo. The rules for doing it safely are almost entirely negative — don't stream, don't poll repeatedly, don't push while a run is in flight, don't trust `--limit 1`, don't trust the `.deployed` state file. This skill exists so you don't have to infer them from four different files: `AGENTS.md` § CI discipline, `README.md` § Deploy to production, `.github/workflows/deploy-reusable.yml` (the actual implementation), and `scripts/deploy.sh`'s header.

**Out of scope**: cutting a platform release (separate skill), diagnosing a server that's unwell for reasons unrelated to a deploy (separate skill), what a change is _made of_ (mod/config skills). This skill is about the mechanics of getting a change from a push to a healthy running server.

## The three tiers

| Tier | Trigger | What happens |
| --- | --- | --- |
| **Full** | A new platform release matching `STACK_VERSION` (resolved tag ≠ the bundle the server actually runs), `overlay/config/`, `overlay/mods-extra.txt`, `overlay/mods-remove.txt` changed, manual dispatch, or a `release` event | `.env` regenerated → overlay rsynced → stack bundle pulled to the resolved tag → `deploy.sh`: countdown → kick → whitelist cleared → save → stop mc → pull images → re-seed → config sync → mc restart → RCON health wait → dimensions/gamerules/permissions → whitelist restored |
| **Infra** | Other `overlay/` changes (assets, branding) with no stack-version change | `infra-deploy.sh`: re-run seed → `compose up --no-recreate` (mc untouched) → force-recreate sidecars |
| **Pull** | Docs, CI, anything else, with no stack-version change | Nothing touches the server |

**Read this twice**: most full deploys are NOT triggered by consumer file diffs — they're triggered by the resolved-tag comparison. A docs-only push made after a platform release has landed is a **full** deploy, because it's the push that rolls that release out. Predicting "pull tier" for a docs push right after a release is the single most common wrong answer here. See `references/tier-detection.md` for the full two-stage algorithm and worked examples.

## Pre-push checklist

Run these three, in order, before pushing anything that could trigger a deploy:

```bash
gh run list --limit 3                                   # 1. a run already in progress? WAIT — concurrent deploys race
ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST \
  'docker exec -i mc rcon-cli "list"'                    # 2. only if this change is full-tier: check who's online
git add -A && git commit -m "..."                        # 3. batch related changes into ONE commit — every push is a deploy
```

## Post-push checklist

```bash
gh run list --workflow deploy.yml --commit <sha> --json databaseId,status
```

**Never `gh run list --limit 1`** — a fresh push races the run-creation webhook, and `--limit 1` grabs the _previous_ run, not the one you just triggered. Resolve by commit sha and retry (empty result) if the run hasn't registered yet.

Then:

```bash
gh run view <id> --json status,conclusion
```

**Check status once.** If it's `in_progress`, hand the user the Actions URL (`https://github.com/<owner>/<repo>/actions/runs/<id>`) and stop. Do not loop. `gh run watch` is forbidden — it streams and blocks forever, same rule as `docker logs -f`. Full deploys legitimately take 3–15 minutes (longer when the mod list changed — Modrinth re-sync of ~150 jars). One background check with a generous timeout is fine; five manual polls in a row wastes context and achieves nothing (safety rule 11 in `AGENTS.md`).

## The deploy.sh sequence, at a glance

Full detail (all 17 numbered sections) is in `references/deploy-sequence.md`. The four an agent needs to reason about by number:

- **Step 8** — seeds default + overlay configs into `data/config/`, **before mc starts**. Mods that auto-generate config on first boot would otherwise create defaults that block the bundle's version.
- **Step 8b** — copies `local-mods/*.jar` (in-house mods) into `data/mods/` while mc is **stopped**. Doing this after the health wait meant a jar that broke the boot could never be replaced by the deploy that shipped it.
- **Step 8c** — re-patches `c2me.toml`'s `useDensityFunctionCompiler = false` and silences DistantHorizons GC warnings, while mc is stopped. c2me strips this key from its own config on every boot, so it must be re-applied every deploy or every custom dimension generates as a clone of the main world.
- **Step 10b** — `sync-mods.sh` fetches any managed jar/datapack missing from `data/`. `MODS_FILE` is empty by default so itzg makes zero network requests at boot; this is the only place mod downloads happen, and only when the mod list actually changed.

**Changes to `deploy.sh` itself take effect on the _next_ deploy**, not the one that merges them — an in-flight deploy already executed the pre-pull copy of the script.

## Manual deploy and manual dispatch

Manual dispatch of the caller workflow (consumer repo) **always** deploys full — it skips tier detection entirely:

```bash
gh workflow run deploy.yml   # run from the consumer repo; workflow_dispatch has no inputs
```

Direct manual deploy on the server, bypassing CI (there is no `~/server/scripts/` — `deploy.sh` ships inside the bundle):

```bash
ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST \
  'cd ~/server && .stack/current/stack/scripts/deploy.sh --non-interactive'
```

`./ops update` (→ `scripts/remote-update.sh`) is the scripted equivalent: pulls the stack bundle, pulls images, rebuilds the modpack, then runs `deploy.sh --non-interactive` and refreshes `kuma-init` — all over SSH, outside CI.

## Failure recovery

A failed deploy is not inert. `deploy.sh` clears the whitelist near the start and restores it near the end (steps 2 and 15) — a mid-run death can leave containers stopped, configs half-applied, or players locked out with the whitelist empty.

```bash
gh run view <id> --json status,conclusion               # confirm it actually failed
ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST 'docker ps -a --format "{{.Names}}\t{{.Status}}"'
ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST 'docker logs mc --tail 80'
./ops rcon "list"                                        # empty/timeout under load ≠ success — re-check, don't assume healthy
./ops doctor                                             # full triage: drift, disk, containers, backups
```

If the whitelist was cleared and the deploy died before step 15 restored it, the itzg image re-applies `WHITELIST` from `.env` on the **next** boot — re-running `deploy.sh` (re-dispatch, or push again once the cause is fixed) restores it. Never `docker restart mc` to "fix" a stuck deploy — that skips the countdown/kick/save dance entirely; use `deploy.sh` or Discord `/mc restart`.

## Traps

1. **`--no-recreate` on the infra tier is load-bearing.** A full deploy creates mc _with_ a temporary Modrinth override, then deletes the override file. A plain `docker compose up -d` afterwards would see mc as config-drifted and recreate it — no countdown, players dropped mid-session (2026-07-01). `infra-deploy.sh` always carries `--no-recreate` on its first `up -d`; only `deploy.sh`, after the countdown, may recreate mc. `scripts/dc.sh` (the safe compose wrapper installed on the server) blocks a bare `up -d` while mc is running for the same reason — but `deploy.sh`/`infra-deploy.sh` call `docker compose` directly and bypass it.
2. **`.deployed` can lie.** If `~/server/.deployed` records a `consumer_sha` whose deploy never actually finished, every following push diffs against that stale sha and downgrades to pull tier — CI goes green while the server runs nothing. Guards exist (state written only on `success()`, a state file with no `mc` container forces full, the stack-version comparison never trusts the state file), but if a server ever has `.deployed` with no containers, delete the file or dispatch manually.
3. **The seed container must be recreated on a full deploy** (step 7, `--force-recreate --no-deps seed`) or config/mod changes won't take effect — the `defaults-seed` image lays platform defaults + overlay into shared volumes at container start, not continuously. `nav-proxy`/`pack-web` are still bind-mounted, so they additionally need force-recreate to pick up config changes (they're in both `deploy.sh`'s and `infra-deploy.sh`'s sidecar lists).
4. **`deploy.sh`'s sidecar force-recreate list and `infra-deploy.sh`'s are not identical — and the comments claiming they are, are stale.** Both `scripts/deploy.sh:27` ("The force-recreate sidecar list below must match infra-deploy.sh") and the inline comment above the list itself say to keep them in sync. They are not in sync: `infra-deploy.sh` force-recreates `kuma-init` inline, `deploy.sh` does not. That is compensated, not broken — `kuma-init` is refreshed by a separate caller in each path:

   | Path | Refreshes kuma-init |
   | --- | --- |
   | CI (any non-pull tier) | `deploy-reusable.yml` "Refresh kuma-init" step, after `deploy.sh` returns |
   | `./ops update` | `remote-update.sh`, after `deploy.sh` returns |
   | Infra tier | `infra-deploy.sh`'s own list |
   | **A bare manual `deploy.sh --non-interactive` over SSH** | **Nothing. kuma-init is not refreshed.** |

   So a hand-run deploy leaves Kuma un-reprovisioned. If Kuma monitors or the maintenance window look stale after a manual deploy, that is why — refresh it yourself:

   ```bash
   ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST \
     'cd ~/server && docker compose --project-directory . -f .stack/current/stack/docker-compose.yml \
      --profile cloud up -d --force-recreate --no-deps kuma-init'
   ```

   Keep the rest of the sidecar set in sync across both lists (`unmined-render nav-proxy pack-web cloudflared mod-checker discord-sync idle-tasks`). Don't "fix" the kuma-init asymmetry without also removing the two workflow/script steps that compensate for it — and if you touch this at all, fix the stale comments.

5. **Concurrent deploys race.** Symptom: `client_loop: send disconnect: Broken pipe`. A second deploy started (or a manual SSH session collided) while one was already restarting Docker. Wait for the in-progress run, verify health, then re-run.
6. **Never run `harden.sh` during or near a deploy** — it restarts Docker and will pull the rug out from under an in-flight deploy.
7. **Never `docker restart mc` on production.** It skips the countdown, kick, save-flush and whitelist dance that `deploy.sh` does deliberately. Use `deploy.sh` or Discord `/mc restart`. `./ops start|stop|restart mc` is meant to be prohibited too, but `service.sh` still technically permits raw `mc` operations — treat it as off-limits regardless, this is an acknowledged enforcement gap.
8. **A run's own script counters aren't proof of success.** `rcon_best_effort`/`rcon()` in `deploy.sh` log a warning on failure but don't abort the deploy — a step can "complete" having silently failed every RCON call inside it. Verify outcomes (world border, dimension count, whitelist contents), not just that the step printed.

## Validation

```bash
gh run view <id> --json status,conclusion
ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST 'docker logs mc --tail 50'
./ops rcon "list"
./ops doctor
```

Under deploy load, RCON can time out and return empty — treat an empty response as "needs re-checking", never as a healthy result. `deploy.sh`'s own health gate (`server_alive`, up to 600s) is the loud version of this check; a silent one is a `docker ps` that shows `mc` running while it's still mid-boot with dimensions not yet activated.

## References

- `references/tier-detection.md` — the full two-stage algorithm, `FULL_PATTERNS`, worked examples for docs/overlay/release pushes
- `references/deploy-sequence.md` — `deploy.sh`'s 17 numbered sections, what each guarantees, what breaks if skipped
- `references/ci-monitoring.md` — run-id-by-sha in full, `gh` JSON polling patterns, log snapshot commands, failure-mode reference
