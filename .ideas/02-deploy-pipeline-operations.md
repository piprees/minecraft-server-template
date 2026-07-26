# Skill brief: `deploy-pipeline-operations`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

Deploying is the highest-consequence routine action in the repo and the rules for doing it safely are almost entirely negative — *don't* stream, *don't* poll, *don't* push while a run is in flight, *don't* trust `--limit 1`, *don't* trust the state file. Negative rules are exactly what an agent violates when it has to infer them.

The knowledge sits in four places that no single reader visits together: `AGENTS.md § CI discipline` (the push ritual), `README.md § Deploy to production` (the tier table), `.github/workflows/deploy-reusable.yml` (553 lines, the actual implementation and the only place tier detection is truthfully described), and `scripts/deploy.sh` (the 12-step server-side sequence, in its header).

Real incidents this skill prevents, all in `AGENTS.md`: trap 9 (players dropped mid-session by a plain `compose up -d`), trap 11 (green CI, server running nothing), the 2026-07-12 timeout that stranded a server mid-restart, and the `Broken pipe` collision from concurrent deploys.

## Scope

**In:** triggering, understanding, monitoring and recovering a deploy. Tier detection, the three tiers, the `deploy.sh` sequence, the pre-push and post-push checklists, run-id resolution, failure recovery, manual dispatch, and the `dc` safe wrapper.

**Out:** cutting a platform release → brief 06. Diagnosing a server that is unwell for reasons other than a deploy → brief 03. What a change is *made of* → briefs 01/02/14.

## Source material

| File | What to mine |
| --- | --- |
| `AGENTS.md:76-95` § CI discipline | Two-stage tier detection, before/after push checklists, the pre-pull `deploy.sh` note |
| `AGENTS.md` traps 2, 9, 11 | Seed must re-run; `--no-recreate` is load-bearing; `.deployed` can lie |
| `AGENTS.md:339-340` safety rules 10 & 11 | No unbounded wait loops; don't repeatedly poll CI |
| `README.md` § Deploy to production | The tier table — full / infra / pull, and *why* most full deploys come from the resolved-tag comparison rather than file diffs |
| `.github/workflows/deploy-reusable.yml` | Ground truth. `FULL_PATTERNS`, `readlink .stack/current`, the state-file guards, the timeout rationale comment, the secrets list, `concurrency: deploy-<environment>` with `cancel-in-progress: false` |
| `examples/consumer/.github/workflows/deploy.yml` | The caller side |
| `scripts/deploy.sh` (header + step numbering) | The 12-step sequence; steps 8, 8b, 8c, 10b are individually load-bearing |
| `scripts/infra-deploy.sh` (header) | The infra tier; the sidecar list that must match `deploy.sh` |
| `scripts/dc.sh` (header) | The safe compose wrapper installed on the server |
| `scripts/remote-update.sh`, `scripts/stack-pull.sh` (headers) | `./ops update`; bundle resolution, retry, cache fallback |
| `examples/consumer/AGENTS.md:79-83` | The consumer-facing summary — keep the skill consistent with it |

## Required structure

```
deploy-pipeline-operations/
├── SKILL.md
└── references/
    ├── tier-detection.md      # the two-stage algorithm, FULL_PATTERNS, worked examples
    ├── deploy-sequence.md     # deploy.sh step by step, what each step guarantees, what breaks if skipped
    └── ci-monitoring.md       # run-id-by-sha, gh JSON polling, log snapshots, failure recovery
```

### SKILL.md must contain

1. **The tier table**, and immediately after it the sentence that makes it usable: *most* full deploys are driven by the resolved-tag comparison, not by consumer file diffs — so a docs-only push after a platform release lands is what rolls that release out. Agents consistently predict "pull tier" for a docs push and are wrong.
2. **A pre-push checklist** as three numbered commands, not prose: `gh run list --limit 3` (wait if in progress), check players if full tier, batch the commit.
3. **A post-push checklist** with the run-id-by-sha recipe verbatim:
   ```bash
   gh run list --workflow deploy.yml --commit <sha> --json databaseId,status
   ```
   and the explicit reason `--limit 1` is wrong (a fresh push races run creation).
4. **The polling rule, stated as a hard limit.** Check status once. If in progress, hand the user the Actions URL and stop. `gh run watch` is forbidden — it streams and blocks. Full deploys take 3–15 minutes, longer when the mod list changed.
5. **The `deploy.sh` sequence at a glance**, with the four steps an agent needs to reason about by number: 8 (config sync, before mc starts), 8b (`local-mods` jars copied while mc is stopped), 8c (c2me DFC patch), 10b (`sync-mods.sh`).
6. **Manual deploy and manual dispatch**, both exact:
   ```bash
   ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST 'cd ~/server && .stack/current/stack/scripts/deploy.sh --non-interactive'
   ```
   plus the fact that there is no `~/server/scripts/` — `deploy.sh` ships in the bundle — and that manual workflow dispatch always deploys full.
7. **Failure recovery**, because a failed deploy is not inert: it can leave containers stopped, configs half-applied, or the whitelist cleared. Give the verification command and the whitelist-restore path.

### Traps to capture

1. **`--no-recreate` is load-bearing on the infra tier.** A plain `docker compose up -d` after a full deploy sees mc as config-drifted (the temporary Modrinth override file was deleted) and recreates it with no countdown — players dropped mid-session, 2026-07-01.
2. **`.deployed` can lie.** If it records a sha whose deploy never completed, every following push downgrades to pull tier and CI goes green while the server runs nothing. Guards exist; recovery is delete the file or dispatch manually.
3. **The seed container must be recreated on a full deploy** or config/mod changes don't take effect. Nginx configs are still bind-mounted, so nav-proxy and pack-web additionally need force-recreate.
4. **Changes to `deploy.sh` itself take effect on the *next* deploy** — an in-flight deploy executes the pre-pull copy.
5. **Concurrent deploys race.** Symptom: `client_loop: send disconnect: Broken pipe`. Wait for CI, verify health, re-run.
6. **Never `docker restart mc` on production** — it skips countdown, kick, save and whitelist. Use `deploy.sh` or Discord `/mc restart`. `./ops start|stop|restart mc` is prohibited (`service.sh` still permits it — an enforcement gap, say so).
7. **Never run `harden.sh` during or near a deploy** — it restarts Docker.

### Validation section

Post-deploy verification that checks *outcomes*, not script output (`mods/AGENTS.md:293` — script counters count commands sent, not commands that succeeded):

```bash
gh run view <id> --json status,conclusion
ssh ... 'docker logs mc --tail 50'
./ops rcon "list"
./ops doctor
```

Note that under deploy load RCON can time out and return empty — treat an empty response as a failure to re-check, never as success.

## Done when

- An agent handed "deploy this change" runs the pre-push checks, pushes once, resolves the run by sha, checks status once, and stops — with the Actions URL handed back.
- The skill makes an agent predict the correct tier for: a docs-only push after a release (full), an `overlay/assets/` change (infra), a `README.md` change with no stack change (pull).
