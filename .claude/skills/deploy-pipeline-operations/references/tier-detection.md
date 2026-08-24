---
title: Tier detection
description: The two-stage algorithm deploy-reusable.yml uses to pick full/infra/pull, FULL_PATTERNS, and worked examples
tags: [tier-detection, deploy-reusable, FULL_PATTERNS, stack-version, deployed-state]
---

# Tier detection

Implemented in the `Determine deploy tier` step of `.github/workflows/deploy-reusable.yml`. It runs on every push-triggered deploy and outputs `tier=full|infra|pull`. Read this file, not a paraphrase of it — the ordering of the checks matters, because each one is an early exit.

## Stage 0 — trigger shortcuts (checked first, no SSH needed)

```bash
if [[ "${{ github.event_name }}" == "workflow_dispatch" || "${{ github.event_name }}" == "release" ]]; then
  tier=full
fi
```

Manual dispatch and GitHub `release` events always deploy full, unconditionally. No file diff, no stack comparison.

## Stage 1 — resolve the symbolic pin, compare against what's actually running

`inputs.stack_version` is usually symbolic (`v5`, `latest`) — it resolves to a different concrete release over time. Comparing symbolic-to-symbolic never detects a new release (the historical bug that made every consumer push land in pull tier). The workflow resolves it exactly like `stack-pull.sh` does: highest semver release tag matching the pin.

```bash
DEPLOYED=$($SSH "cat ~/server/.deployed 2>/dev/null" || echo "")
DEPLOYED_SHA="${DEPLOYED#*consumer_sha=}"   # (extracted via grep, simplified here)

# No state file at all → first deploy ever
[[ -z "$DEPLOYED_SHA" ]] && tier=full

# State file present but no mc container → a previous deploy died after
# writing state, or the server was rebuilt. Trusting the sha here would
# downgrade to pull/infra and leave the server empty while CI reports green.
MC_PRESENT=$($SSH "docker ps -a --format '{{.Names}}'" | grep -cx mc || true)
[[ "$MC_PRESENT" == "0" ]] && tier=full

# Compare the RESOLVED tag against the bundle the server is ACTUALLY running
# (the symlink target, not a recorded string that can go stale or stay symbolic)
RUNNING_STACK=$($SSH 'basename "$(readlink ~/server/.stack/current)"')
[[ "$RUNNING_STACK" != "$RESOLVED" ]] && tier=full   # <- this is what rolls a platform release out
```

This is the check that matters most in practice: **a platform release becomes visible to a consumer the moment ANY consumer push runs after the release publishes**, regardless of what that push touches. The push is just the trigger; the payload is "the server isn't running the release yet".

## Stage 2 — diff consumer files since the last deploy (only reached if stage 1 found no stack change)

```bash
CHANGED=$(git diff --name-only "$DEPLOYED_SHA" HEAD 2>/dev/null || echo "FORCE")
[[ "$CHANGED" == "FORCE" ]] && tier=full   # can't compute the diff — fail safe to full

FULL_PATTERNS="^overlay/config/|^overlay/mods-extra\.txt$|^overlay/mods-remove\.txt$"
echo "$CHANGED" | grep -qE "$FULL_PATTERNS" && tier=full

echo "$CHANGED" | grep -qE "^overlay/" && tier=infra   # any other overlay/ path

tier=pull   # everything else — docs, CI, README, workflows, etc.
```

`FULL_PATTERNS` is exactly three alternatives: the whole `overlay/config/` tree, or the two mod list files at the overlay root. Anything else under `overlay/` (assets, branding) is infra. Anything outside `overlay/` entirely (root `README.md`, `.github/workflows/*`, `docs/`) is pull, **provided stage 1 found no stack change**.

## Worked examples

| Push contents | Stage 1 result | Stage 2 result | Tier | Why |
| --- | --- | --- | --- | --- |
| `README.md` only, no platform release since last deploy | no stack change | no `overlay/` match | **pull** | Nothing the server needs |
| `README.md` only, a platform release published an hour ago | `RUNNING_STACK != RESOLVED` | never reached | **full** | The release, not the README, drives this — stage 1 exits before the diff even runs |
| `overlay/assets/logo.png` | no stack change | matches `^overlay/`, not `FULL_PATTERNS` | **infra** | Branding-only, mc untouched |
| `overlay/mods-extra.txt` (adding a mod) | no stack change | matches `FULL_PATTERNS` | **full** | Mod list changes need a restart + Modrinth re-sync |
| `overlay/config/some-mod/file.toml` | no stack change | matches `^overlay/config/` | **full** | Config that lands in `data/config/` needs the seed re-run + mc restart |
| Manual `gh workflow run deploy.yml` | — (shortcut) | — | **full** | Stage 0 shortcut, no diff computed at all |

The mistake to avoid: reasoning only about "what files changed" and ignoring stage 1. Stage 1 runs first and can force `full` before stage 2's file diff is even computed.
