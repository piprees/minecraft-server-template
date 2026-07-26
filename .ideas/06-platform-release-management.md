# Skill brief: `platform-release-management`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

Releases here are **irreversible in a way that is not obvious**. GitHub immutable releases burn the tag permanently — a failed release cannot be retried at the same version, even after deleting the release. An agent's default instinct (`gh release create`) produces a broken release with no bundle, which breaks `./dev update` for every consumer.

There is a second, subtler failure mode with a documented incident: pushing to `main` while `release.yml` is in flight **cancels the release's image builds**, because `publish.yml` uses concurrency group `publish-${{ github.ref }}` with `cancel-in-progress: true` and a push shares that group. On 2026-07-13 a docs push cancelled the discord-sync 2.14.0 image, leaving a published release whose version-tagged images did not exist — and production pulls version tags.

`AGENTS.md § Cutting a release` states the constraints; `docs/releasing.md` states the procedure. Neither states the *recovery*, and neither is reachable from "cut a release".

## Scope

**In:** deciding the version, running the release, what the release contains, verifying it, recovering from a failed or incomplete release, the changelog pipeline, and consumer impact.

**Out:** what goes *into* the release (mods, configs, mod jars) → briefs 01/05. Rolling the release out to a server → brief 02.

## Source material

| File | What to mine |
| --- | --- |
| `AGENTS.md § Cutting a release` (lines 97-107) | The four key constraints and the `gh run rerun --failed` recovery |
| `docs/releasing.md` | The procedure, the two pipelines, tag protection, compatibility promise, consumer impact |
| `.github/workflows/release.yml` (178 lines) | The real sequence: smoke-test gate → bundle → draft with assets → publish; the mod build + jar verification |
| `.github/workflows/publish.yml` (112 lines) | The concurrency group that causes the collision; the tag matrix (`X.Y.Z`, `X.Y`, `X`, `latest`) |
| `.github/workflows/release-guard.yml` | What it guards — read it and say so |
| `.github/workflows/smoke-test.yml` | The gate: boots ~150 mods, Carpet bot traversal, config assertions. 5–10 min on runners |
| `scripts/build-stack-bundle.sh` | The `MANIFEST` array — the bundle manifest trap |
| `cliff.toml` | git-cliff config driving release notes + `CHANGELOG.md` |
| `AGENTS.md:210` | After any release, refresh the major tag: `git fetch origin '+refs/tags/v3:refs/tags/v3'` |
| `README.md § What a release contains`, § Compatibility promise | Images tagged, bundle contents incl. `local-mods/` |
| `CONTRIBUTING.md § Commit conventions` | Commit quality → changelog quality |

## Required structure

```
platform-release-management/
├── SKILL.md
└── references/
    ├── release-recovery.md    # every failure mode and its exact recovery, with the burnt-tag rule
    └── bundle-contents.md     # what build-stack-bundle.sh packs, the MANIFEST array, lint's manifest check
```

### SKILL.md must contain

1. **The one command, and the ban, in the first ten lines:**
   ```bash
   gh workflow run release.yml -f version=vX.Y.Z
   ```
   > **Never `gh release create`.** With immutable releases, assets cannot be attached after publish, so the bundle upload fails and the release ships broken.
2. **A confirm-before-proceeding marker.** `AGENTS.md:321` lists cutting a release under actions that require asking a human first. The skill must say so and mean it.
3. **Version selection** from conventional commits: `fix:` → patch, `feat:` → minor, `BREAKING CHANGE:`/`!` → major, mapped onto the compatibility promise (what a major/minor/patch *promises consumers*, not just what it numbers).
4. **Pre-flight checks**, as commands:
   ```bash
   gh run list --limit 5            # nothing in flight
   ./scripts/test-scripts.sh --quick
   gh release list --limit 5 --json tagName,isLatest --jq '.[] | select(.isLatest) | .tagName'
   ```
   The last one carries its own trap — `--limit 1` returns the most recently *published* release, not the highest version, because backported patches publish out of order.
5. **The push freeze**, stated as a rule with its mechanism: no pushes to `main` while `release.yml` is running, because `publish.yml`'s `cancel-in-progress` concurrency group is shared and a push cancels the release's in-flight image builds.
6. **What a release contains** — GHCR images tagged `X.Y.Z`/`X.Y`/`X`/`latest`, and the stack bundle tarball (compose files, host-side scripts, default configs, in-house mod jars in `local-mods/`).
7. **Post-release verification**, because a green workflow is not proof: assets attached to the release, version-tagged images actually present in GHCR, `CHANGELOG.md` committed, and the local major tag refreshed.

### Traps to capture

1. **A published immutable release burns its tag forever.** Deleting the release does not free it. Fix the cause and cut the **next patch version** — never retry the same tag.
2. **A release published without a bundle is broken.** Do not delete and re-cut. Cut the next patch with the complete asset set. Draft releases are mutable until publication — validate every asset on the draft.
3. **The publish.yml cancellation collision** (2026-07-13). Recovery: `gh run rerun <release-run-id> --failed` rebuilds only the cancelled jobs; the release and tag are unaffected if the "Build bundle and publish release" job succeeded.
4. **Bundle manifest trap.** A new bundle script that is not added to the `MANIFEST` array in `scripts/build-stack-bundle.sh` is never shipped. `lint.yml` checks that every `.sh` referenced by `ops` or imported by another bundle script is in the manifest — but only for those two cases.
5. **Stale major tag.** Releases force-move `v3`; a stale local copy makes `git fetch` complain forever. Refresh with the explicit refspec.
6. **Consumers do not get the release until something pushes.** A platform release sitting on GitHub changes nothing until a consumer push triggers tier detection and the resolved-tag comparison fires.
7. **Never rely on training data for version numbers** when bumping an image tag or action — and verify Docker Hub availability separately, since some projects publish GitHub releases without pushing images.

### Validation section

```bash
gh release view vX.Y.Z --json assets --jq '.assets[].name'   # bundle tarball + .sha256
gh run list --workflow publish.yml --limit 3
git fetch origin '+refs/tags/v3:refs/tags/v3'
```

## Done when

- An agent asked to cut a release pauses for human confirmation, checks nothing is in flight, uses `gh workflow run`, and knows that a failure means the *next* patch version.
- The recovery reference covers: cancelled image builds, missing bundle, failed smoke test, and a burnt tag — each with the exact command.
