---
title: CI monitoring and failure recovery
description: Resolving a deploy run by commit sha, gh JSON polling patterns, log snapshot commands, and what to do when a deploy fails
tags: [gh-run, polling, run-id, failure-recovery, rcon-timeout, whitelist]
---

# CI monitoring and failure recovery

## Resolving the run — by commit sha, never `--limit 1`

```bash
gh run list --workflow deploy.yml --commit <sha> --json databaseId,status
```

A push and the workflow-run-creation webhook race each other. `gh run list --limit 1` right after a push can return the **previous** run — the one from your last change, already finished — and you'll read its (unrelated) status as if it were the current push's. Filtering by `--commit <sha>` is the only reliable way to get the run this specific push triggered. If the query comes back empty, the webhook hasn't registered the run yet — retry after a few seconds rather than assuming the push didn't trigger anything.

## Polling — a hard limit, not a suggestion

```bash
gh run view <id> --json status,conclusion
```

Check exactly once. Read the result:

- `status: completed`, `conclusion: success` → done, verify with the commands below if the change was full/infra tier.
- `status: completed`, `conclusion: failure` → go straight to failure recovery, below.
- `status: in_progress` → hand the user the Actions URL (`https://github.com/<owner>/<repo>/actions/runs/<id>`) and **stop polling**. Full deploys legitimately run 3–15 minutes, longer when the mod list changed (Modrinth re-sync of ~150 jars). Re-running `gh run view` every 30–60 seconds in a loop wastes context and tells you nothing you didn't already know — the deploy will finish when it finishes.

`gh run watch` is forbidden outright: it streams to the terminal and blocks until the run ends, with no way to interrupt it cleanly from an agent session — the exact same failure mode as `docker logs -f`.

## Log snapshots (never streams)

```bash
./ops logs mc --tail 80
./ops logs mc --tail 200   # if the 80-line snapshot doesn't show the boot start
```

The reusable workflow's own "Verify server health" step does exactly this on failure (dumps `--tail 80`, then `--tail 200` if the RCON check fails) — mirror it rather than tailing with `-f`.

## Failure recovery decision tree

1. **Confirm it actually failed** — don't act on a guess:
   ```bash
   gh run view <id> --json status,conclusion
   ```
2. **Check what's actually running on the server**:
   ```bash
   ./ops ssh 'docker ps -a --format "{{.Names}}\t{{.Status}}"'
   ```
   Look specifically for `mc` — is it running, restarting, or exited? `deploy.sh` stops mc in section 5 and doesn't restart it until section 11; a death between those two points leaves the server down with the whitelist cleared.
3. **Read the boot log**:
   ```bash
   ./ops logs mc --tail 80
   ```
4. **Check whether RCON actually responds** — and treat a timeout or empty response as "unknown", not "healthy":
   ```bash
   ./ops rcon "list"
   ```
   Under deploy load (mid-boot, dimension activation running) RCON can time out and come back empty. That is not evidence the server is fine — it's evidence you haven't confirmed anything. Re-check after a short wait rather than reporting success.
5. **Run the full triage**:
   ```bash
   ./ops doctor
   ```
6. **If the whitelist looks cleared and the deploy died before section 15 restored it**: the itzg image re-applies `WHITELIST` from `.env` on the server's **next** boot. The fix is to get `deploy.sh` to run again successfully (fix whatever killed it, then re-push or re-dispatch), not to manually RCON the whitelist back — a manual fix will be wiped by the next real deploy anyway since `.env`/whitelist state is regenerated from GitHub secrets on every full deploy.
7. **Never "fix" a stuck deploy with `docker restart mc`.** It skips the countdown, kick, save-flush, and whitelist dance entirely — exactly the disruption `deploy.sh` exists to avoid. Use `deploy.sh --non-interactive` (manual SSH invocation, see the main SKILL.md) or Discord `/mc restart`.
8. **If a second push/dispatch collided with the still-running failed one**, symptom is `client_loop: send disconnect: Broken pipe` in the CI logs — wait for the in-progress run to actually finish (success or failure) before touching the server again.

## Concurrency note

`deploy-reusable.yml` sets `concurrency: { group: deploy-${{ inputs.environment }}, cancel-in-progress: false }` — a second dispatch while one is running **queues** rather than cancelling the first. This means a rapid double-push doesn't lose the first deploy, but it does mean two full deploys can run back-to-back with no gap, which is exactly the collision scenario for the `Broken pipe` symptom if anyone touches the server manually in between.
