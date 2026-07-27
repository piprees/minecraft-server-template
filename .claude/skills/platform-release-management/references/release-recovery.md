---
title: Release recovery
description: Exact recovery steps for every release failure mode — cancelled image builds, missing bundle, failed smoke test, burnt tags, and the changelog/major-tag push races.
tags: [release, recovery, gh-workflow-run, immutable-release, burnt-tag, publish-yml, concurrency]
---

# Release recovery

Read this when a release has already been dispatched (or published) and something went wrong. Each failure mode below states what state you're actually in — whether the tag is burnt or not is the first thing to establish, because it changes the recovery entirely.

## 1. Cancelled image builds (the publish.yml collision)

**Symptom:** the release published successfully (bundle attached, tag exists), but some or all version-tagged GHCR images are missing. `gh run list --workflow publish.yml` shows a run with conclusion `cancelled` around the time of the release.

**Cause:** `publish.yml` uses `concurrency: { group: publish-${{ github.ref }}, cancel-in-progress: true }`. `release.yml`'s `images` job calls `publish.yml` via `workflow_call` from `main` — same ref as any ordinary push-triggered `publish.yml` run. If something pushes to `main` while the release's `images` job is running, GitHub cancels the release's build mid-flight to start the new one.

**Documented incident:** 2026-07-13 — a docs-only push cancelled the discord-sync 2.14.0 image build partway through a release. The release itself published fine (bundle, tag, GitHub release page all correct); only the version-tagged `discord-sync:2.14.0` image never existed in GHCR. Production pulls images by version tag (`IMAGE_TAG="${STACK_VERSION#v}"`), so any consumer who upgraded to that release got a pull failure for that one service.

**Recovery — the tag and release are NOT burnt by this:**

```bash
gh run rerun <release-run-id> --failed
```

This reruns only the cancelled/failed jobs in the original workflow run — i.e. just `images` (which re-invokes `publish.yml` with the same version). It does **not** re-run `smoke-test` or `bundle`, and does not touch the already-published release or tag. Confirm the `bundle` job ("Build bundle and publish release") shows `success` before relying on this — if `bundle` itself failed for an unrelated reason, this rule doesn't apply (see §3).

**Verify the fix:**

```bash
gh run list --workflow publish.yml --limit 3
docker pull ghcr.io/<owner>/<repo>/discord-sync:2.14.0   # or whichever image was missing
```

**Prevention:** never push to `main` while a release is in flight. Check `gh run list --limit 5` (or `gh run list --workflow release.yml --json status`) before any push, not only before cutting a release yourself — this collision is triggered by _anyone's_ push, release-related or not.

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

## 3. Failed smoke test

**Symptom:** the `smoke-test` job in `release.yml` fails; `bundle` and `images` never start (job dependency: `bundle` needs `smoke-test`).

**State:** nothing was ever tagged or published — `gh release create` lives inside the `bundle` job, which didn't run. **The version is not burnt.**

**Recovery:**

1. Read the failure — `smoke-test.yml` fails loudly on specific assertions (RCON never came up, a dimension didn't load, the portal traversal e2e didn't arrive, the carpet/piston regression fired, missing seed configs). The failing step name tells you which.
2. Fix the actual cause in the platform repo (not the release process).
3. Re-dispatch with the **identical version string** — nothing needs to change to avoid a burnt tag, because nothing was ever published:
   ```bash
   gh workflow run release.yml -f version=vX.Y.Z
   ```

## 4. Burnt tag (any failure after the release actually published)

**Symptom:** you need to fix and retry a release, but the `bundle` job already reached `gh release create`/`gh release edit --draft=false` before failing downstream, or the release is simply live and wrong.

**Rule, no exceptions:** a published immutable release's tag cannot be reused, even after deleting the release. There is no undo.

**Recovery:** fix the cause, then cut the **next patch version** (`vX.Y.(Z+1)`), never the same tag. Treat the broken release as permanent scar tissue — don't spend time trying to make the old tag work.

## 5. CHANGELOG.md commit race

**Symptom:** the release published fine, but `CHANGELOG.md` on `main` doesn't reflect the new version.

**Cause:** the `bundle` job's changelog-commit step is `continue-on-error: true` and retries a rebase-and-push up to 3 times if `main` moved during the release run (documented incident: v3.1.0, 2026-07-22 — a merge landing mid-release made the push non-fast-forward). It is deliberately never allowed to fail the job, so a persistent race can leave the changelog silently stale.

**Recovery:** this doesn't affect the release itself (already published, tag not burnt) — just regenerate and push by hand:

```bash
git fetch --tags --force
# use the same git-cliff version release.yml pins (check the git-cliff-action `version:` input, not the release version)
git-cliff -o CHANGELOG.md
git add CHANGELOG.md && git commit -m "chore: update CHANGELOG.md for vX.Y.Z" && git push origin main
```

## 6. Major tag not advanced

**Symptom:** `git fetch origin '+refs/tags/v3:refs/tags/v3'` (substituting the real major) shows the tag still points at an old commit after a release.

**Cause:** the "Advance major tag" step is also `continue-on-error: true` and can fail silently (e.g. a protected-tag rule blocking a force-push from the workflow's token).

**Recovery:**

```bash
git tag -f vN <release-commit-sha>
git push origin vN --force
```

Then refresh your own local copy the same way consumers are told to (`AGENTS.md`): a stale local major tag otherwise makes every subsequent `git fetch` complain.

## Quick reference: is the tag burnt?

| Where the failure happened | Tag burnt? | What to do |
| --- | --- | --- |
| `smoke-test` job | No | Fix, re-dispatch same version |
| `bundle` job, before `gh release create` runs | No | Fix, re-dispatch same version |
| `bundle` job, at or after `gh release create`/`gh release edit --draft=false` | **Yes** | Cut next patch version |
| `images` job (bundle already succeeded) | No — release already valid | `gh run rerun <run-id> --failed` |
| Manual `gh release create` used instead of the workflow | **Yes** | Cut next patch version, fix the process gap |
