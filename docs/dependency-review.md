# Dependency review

`dep-review.yml` reviews every dependency-update PR with Claude, from six
sources: `mods` and `worldgen` (the two weekly PRs from `mod-updates.yml`),
`actions`, `docker` and `pip` (Dependabot's), and `platform` (the weekly PR
from `platform-updates.yml`). It then merges the PR, closes it, holds mods
back, or labels it for a human, and retitles it by release impact. It runs on
the maintainer's Claude subscription through `anthropics/claude-code-action`. This file covers the
pipeline; the rules it applies are in [`DEPENDENCIES.md`](../DEPENDENCIES.md).

## Setup

1. Run `claude setup-token` locally. The token lasts about a year.
2. In the repository settings, create an environment named `dep-review`.
   Under **Deployment branches and tags**, allow `main` only. Add the token as
   the environment secret `CLAUDE_CODE_OAUTH_TOKEN`.
3. Create an environment named `release-major` and add at least one
   **required reviewer**. `release-train.yml` waits there for approval before
   dispatching a major, and refuses to release one when the environment has
   no required reviewer.
4. Nothing else: the relay, the mod-updates and platform-updates dispatches
   and the labels need no further secrets.

To review a PR by hand: **Actions → Dependency Review → Run workflow**, with
the PR number. Tick **force** to re-review when nothing has changed since the
last review.

## Flow

| Job | What it does | Token |
| --- | --- | --- |
| gate | `gate.py`: admits only open, non-fork PRs by `dependabot[bot]` (`dependabot/*` branches, GitHub-signed commits) or `github-actions[bot]` (`mod-updates/auto`, `mod-updates/next-major`, `platform-updates/auto`) whose diff changes version lines only; the mod PRs' manifest may change pins and holds and nothing else, and the platform PR only the lines `scripts/platform_updates.py` names. Skips when the version set matches the last review. | read |
| quick | Unit tests, ShellCheck, changed-JSON validity, and a build of every changed Dockerfile, on the PR head | read, no secrets |
| smoke | `smoke-test.yml` on the PR head, for the mod PRs, the platform PR and `defaults-seed` bumps | read, no secrets |
| review | `collect.py` fetches every changelog, flag and dependency into `review/`; Claude judges it with Read, Grep and Glob only and returns a schema-checked verdict | read, OAuth token |
| apply | `apply.py` enforces `.github/dep-review/policy.json`, sets the release impact and commit title, and acts | write |

A merge made with `GITHUB_TOKEN` triggers no push workflows, so `apply.py`
dispatches `publish.yml` after merging. The review comment on the PR carries
the verdicts. On a refresh, only
versions not already judged go to Claude; earlier verdicts are reused.

## Policy

What counts as worldgen, the mod and platform PRs, what merges automatically,
holds, release floors and the versioning contract:
[`DEPENDENCIES.md`](../DEPENDENCIES.md#automation).
The numbers are in `.github/dep-review/policy.json`.

## Who can start it

`dep-review.yml` runs on `workflow_dispatch` only, so it always runs main's
definition and only a writer can start it. The gate job fails any run from
another ref, and the environment keeps the token on main.

- Dependabot PRs: `dep-review-relay.yml` dispatches for `pull_request` events
  whose actor and author are both `dependabot[bot]`. Its token can only
  dispatch; fork PRs get a read-only token and cannot.
- The mod PRs and the platform PR: `mod-updates.yml` and
  `platform-updates.yml` dispatch after opening or refreshing each.
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
  merge. The platform PR never touches a root workflow: git-cliff's version
  lives in `.github/git-cliff-version` for this reason.
