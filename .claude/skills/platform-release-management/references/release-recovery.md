---
title: Release recovery
description: Exact recovery steps for every release failure mode — failed image builds on a draft, missing bundle, failed semver or smoke test, burnt tags, and the changelog/major-tag push races.
tags: [release, recovery, gh-workflow-run, immutable-release, burnt-tag, publish-yml, concurrency]
---

# Release recovery

Read this when a release has already been dispatched (or published) and something went wrong. Each failure mode below states what state you're actually in — whether the tag is burnt or not is the first thing to establish, because it changes the recovery entirely.

## 1. Image build failed or was cancelled

**Symptom:** the `images` job in `release.yml` failed or was cancelled; the release sits as a **draft** with its bundle attached, and `publish` never ran.

**State:** a draft is unpublished and mutable, and the releases API consumers resolve against never lists it, so no consumer can pick it up. **The version is not burnt.**

**Concurrency:** `publish.yml` groups on `publish-${{ inputs.version || github.ref }}` with `cancel-in-progress: ${{ !inputs.version }}`, so a versioned build has a group of its own and a push to `main` cannot cancel it. A cancellation here means something else cancelled it: a manual cancel, or a second dispatch of the same version.

**Recovery:**

```bash
gh run rerun <release-run-id> --failed
```

This reruns only the failed or cancelled jobs, `images` and then `publish`, which publishes the draft once the images exist. It does not re-run `semver`, `smoke-test` or `bundle`.

**Verify:**

```bash
gh release view vX.Y.Z --json isDraft
docker pull ghcr.io/<owner>/<repo>/discord-sync:X.Y.Z   # or whichever image failed
```

## 2. Release published without a bundle

**Symptom:** `release-guard.yml` fails on `release: published` with `Release <TAG> is missing the stack bundle tarball!`. Consumer `./dev update` 404s trying to fetch it.

**Cause:** almost always `gh release create` was run directly instead of dispatching `release.yml`. Immutable releases don't allow attaching assets after publish, so a hand-created release has no tarball, no `.sha256`, nothing.

**Recovery — the tag IS burnt, do not try to fix it in place:**

1. Do not delete and re-cut the same tag — GitHub will not let you reuse it even after deletion, and trying wastes a cycle discovering that.
2. Fix whatever caused the manual `gh release create` (usually: someone bypassed the documented command under time pressure).
3. Cut the next patch version properly:
   ```bash
   gh workflow run release.yml -f version=vX.Y.(Z+1)
   ```
4. Confirm tag protection is actually enabled (`Settings → Rules → Rulesets`, ruleset on `v*`, bypass restricted to GitHub Actions) so this can't recur from a direct `git push origin vX.Y.Z`.

## 3. Failed before the draft exists

**Symptom:** `semver`, `smoke-test`, or `bundle` before its "Create the draft release" step failed.

**State:** nothing was tagged or drafted. **The version is not burnt.**

**Recovery:**

1. Read the failure. `semver` names the breaking commits that need a major (`scripts/check-release-version.sh`); `smoke-test.yml` fails on specific assertions (RCON never came up, a dimension didn't load, the portal traversal e2e didn't arrive, the carpet/piston regression fired, missing seed configs), and the failing step name tells you which.
2. Fix the cause in the platform repo; for `semver`, release the major it asks for.
3. Re-dispatch with the **identical version string** (or the major `semver` named):
   ```bash
   gh workflow run release.yml -f version=vX.Y.Z
   ```

A failure after the draft exists but before `publish` leaves a draft: rerun the failed jobs as in §1, or delete the draft (`gh release delete vX.Y.Z`; a draft has no tag yet) and re-dispatch the same version.

## 4. Burnt tag (any failure after the release published)

**Symptom:** the `publish` job flipped the draft to published and something is wrong with the release, or a step after that failed.

**Rule, no exceptions:** a published immutable release's tag cannot be reused, even after deleting the release. There is no undo.

**Recovery:** fix the cause, then cut the **next patch version** (`vX.Y.(Z+1)`), never the same tag.

## 5. CHANGELOG.md commit race

**Symptom:** the release published, but `CHANGELOG.md` on `main` doesn't reflect the new version.

**Cause:** the `publish` job's changelog commit is `continue-on-error: true` and retries a rebase-and-push up to three times when `main` moves during the run. It never fails the job, so a persistent race leaves the changelog stale.

**Recovery:** the release is unaffected; regenerate and push by hand:

```bash
git fetch --tags --force
# use the git-cliff version release.yml pins (its git-cliff-action `version:` input)
git-cliff -o CHANGELOG.md
git add CHANGELOG.md && git commit -m "chore: update CHANGELOG.md for vX.Y.Z" && git push origin main
```

## 6. Major tag not advanced

**Symptom:** `git fetch origin '+refs/tags/v4:refs/tags/v4'` (substituting the real major) shows the tag still points at an old commit after a release.

**Cause:** the `publish` job's "Advance major tag" step is also `continue-on-error: true` and can fail silently (e.g. a protected-tag rule blocking a force-push from the workflow's token).

**Recovery:**

```bash
git tag -f vN <release-commit-sha>
git push origin vN --force
```

Then refresh your own local copy the same way consumers are told to (`AGENTS.md`): a stale local major tag otherwise makes every subsequent `git fetch` complain.

## Quick reference: is the tag burnt?

| Where the failure happened | Tag burnt? | What to do |
| --- | --- | --- |
| `semver`, `smoke-test`, or `bundle` before the draft | No | Fix, re-dispatch the same version |
| After the draft, before `publish` (`images` included) | No, it's a draft | `gh run rerun <run-id> --failed`, or delete the draft and re-dispatch |
| `publish` flipped the draft, or anything later | **Yes** | Cut the next patch version |
| Manual `gh release create` instead of the workflow | **Yes** | Cut the next patch version, close the process gap |
