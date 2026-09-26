# Dependency review

`dep-review.yml` reviews every dependency-update PR (Dependabot's, and the
weekly mod PR from `mod-updates.yml`) with Claude, then merges it, closes it,
holds mods back, or labels it for a human. It runs on the maintainer's Claude
subscription through `anthropics/claude-code-action`. This file covers the
pipeline; the rules it applies are in [`DEPENDENCIES.md`](../DEPENDENCIES.md).

## Setup

1. Run `claude setup-token` locally. The token lasts about a year.
2. In the repository settings, create an environment named `dep-review`.
   Under **Deployment branches and tags**, allow `main` only. Add the token as
   the environment secret `CLAUDE_CODE_OAUTH_TOKEN`.
3. Nothing else: the relay, the mod-updates dispatch and the labels need no
   further secrets.

To review a PR by hand: **Actions → Dependency Review → Run workflow**, with
the PR number. Tick **force** to re-review when nothing has changed since the
last review.

## Flow

| Job | What it does | Token |
| --- | --- | --- |
| gate | `gate.py`: admits only open, non-fork PRs by `dependabot[bot]` (`dependabot/*` branches, GitHub-signed commits) or `github-actions[bot]` (`mod-updates/auto`, `mod-updates/next-major`) whose diff changes version lines only; the mod PRs' manifest may change pins and holds and nothing else. Skips when the version set matches the last review. | read |
| quick | Unit tests, ShellCheck, changed-JSON validity, and a build of every changed Dockerfile, on the PR head | read, no secrets |
| smoke | `smoke-test.yml` on the PR head, for the mod PR and `defaults-seed` bumps | read, no secrets |
| review | `collect.py` fetches every changelog, flag and dependency into `review/`; Claude judges it with Read, Grep and Glob only and returns a schema-checked verdict | read, OAuth token |
| apply | `apply.py` enforces `.github/dep-review/policy.json` and acts | write |

A merge made with `GITHUB_TOKEN` triggers no push workflows, so `apply.py`
dispatches `publish.yml` after merging. The review comment on the PR carries
the verdicts. On a refresh, only
versions not already judged go to Claude; earlier verdicts are reused.

## Policy

What counts as worldgen, the two mod PRs, what merges automatically, holds
and the versioning contract: [`DEPENDENCIES.md`](../DEPENDENCIES.md#automation).
The numbers are in `.github/dep-review/policy.json`.

## Who can start it

`dep-review.yml` runs on `workflow_dispatch` only, so it always runs main's
definition and only a writer can start it. The gate job fails any run from
another ref, and the environment keeps the token on main.

- Dependabot PRs: `dep-review-relay.yml` dispatches for `pull_request` events
  whose actor and author are both `dependabot[bot]`. Its token can only
  dispatch; fork PRs get a read-only token and cannot.
- The mod PRs: `mod-updates.yml` dispatches after opening or refreshing each.
- Anything else fails the gate, which re-reads the PR from the API.

The reviewer never sees the PR as a checkout: the job's working tree is
`main`, and the PR's diff and changelogs arrive as data in `review/`. It has
no shell, no network and no write access, and reads outside the workspace
are denied. `apply.py` discards any verdict containing credential-like text
and escapes everything it posts.

## Cost

Each run is capped by `--max-budget-usd 20`, `--max-turns 60` and a 45-minute
job timeout, all in `dep-review.yml`. Runs draw on the subscription's usage
limits alongside interactive use. Grouped Dependabot updates
(`.github/dependabot.yml`) and cached verdicts keep the number of reviews
down.

## Known limits

- `GITHUB_TOKEN` may be refused when merging a PR that changes
  `.github/workflows/` (GitHub Actions bumps). The run then comments with
  GitHub's error and the PR stays labelled `dep-review:safe` for a manual
  merge.
- Compose images are outside this flow ([DEPENDENCIES.md § Compose images](../DEPENDENCIES.md#compose-images)).
