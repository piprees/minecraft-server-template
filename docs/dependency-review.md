# Dependency review

`dep-review.yml` reviews every dependency-update PR (Dependabot's, and the
weekly mod PR from `mod-updates.yml`) with Claude, then merges it, closes it,
holds mods back, or labels it for a human. It runs on the maintainer's Claude
subscription through `anthropics/claude-code-action`.

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

The review comment on the PR carries the verdicts. On a refresh, only
versions not already judged go to Claude; earlier verdicts are reused.

## Two mod PRs

`mod-updates.yml` re-pins every mod each Monday, then `partition.py` splits the
result:

| PR | Branch | Holds | Merged by |
| --- | --- | --- | --- |
| Regular updates | `mod-updates/auto` | everything else | dep-review, when the policy allows |
| Next major | `mod-updates/next-major` | worldgen updates, the regenerated presets and the structure census | a human cutting the next major release |

A mod update is worldgen when the mod sits in a `never_automerge_sections`
section of `config/modrinth-mods.txt` (terrain, BetterX, caves, dimensions,
structures, boss dungeons), is named in `worldgen_slugs`, or its jar's
`data/*/worldgen`, `structure(s)`, `tags/worldgen` or biome-modifier files
differ between the old and new version. A mod added or removed, or a jar that
cannot be compared, counts as worldgen.

Consumers pinned to a major (`STACK_VERSION=v5`) take every release in it, and
worldgen cannot be undone on generated chunks, so worldgen ships only in a
major. The next-major PR's commit is `feat(mods)!:` with a `BREAKING CHANGE:`
footer, and `scripts/check-release-version.sh` makes `release.yml` refuse any
release after it that keeps the major. dep-review reviews every refresh, holds
mods back on it like on the regular PR, never merges it, and labels it
`dep-review:ready-for-major` when everything but its worldgen status passes.
Merging the regular PR re-runs `mod-updates.yml`, so the next-major PR stays
mergeable.

To release it: merge the next-major PR, then
`gh workflow run release.yml -f version=vN.0.0`.

## What merges automatically

All of these, checked by `apply.py` whatever the reviewer says:

- every update accepted, none above `automerge_max_risk` (`low`)
- quick checks, smoke test and Docker builds green, and every other check on
  the PR head completed green
- no blocking flag: pre-release, major version, wrong Minecraft version or
  loader, a missing required dependency, fewer than `min_age_days` (3) since
  release, or a worldgen mod (`never_automerge_sections`, `worldgen_slugs`)
- no change under `never_automerge_paths` (regenerated worldgen presets and
  the structure census)

A merge dispatches `publish.yml`, because a merge made with `GITHUB_TOKEN`
triggers no push workflows. Releases stay manual, so nothing reaches a
server until a release is cut.

## Holds

On either mod PR, the reviewer holds a mod at its current version when it is a
pre-release, targets the wrong game version or loader, misses a dependency, or
looks risky, and releases an existing hold whose blocker has cleared.
`apply.py` edits `_holds` on the PR branch and dispatches `mod-updates.yml`,
which re-pins from main with both branches' holds carried over
(`carry_holds.py`) and asks for the next review.

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
- Images in `docker-compose.yml` are written as `${MIRROR_REGISTRY:-…}/image:tag`,
  which Dependabot cannot parse, so they are not updated by this flow.
