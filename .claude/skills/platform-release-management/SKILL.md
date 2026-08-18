---
name: platform-release-management
description: Cuts and verifies platform releases for this Minecraft server template. Covers the `gh workflow run release.yml -f version=vX.Y.Z` dispatch, version selection from conventional commits, pre-flight checks, publish.yml's concurrency groups and what a push to main can and cannot cancel, what a release actually contains, post-release verification, and recovery from a missing image build, missing bundle, failed smoke test, or a burnt tag. Use when: cutting a new vX.Y.Z release, a published release is missing its version-tagged GHCR images, a release has no stack bundle tarball attached, deciding whether a change is a major/minor/patch bump, recovering an image build that was cancelled or failed mid-release, or adding a new bundle script that needs the `MANIFEST` array in `scripts/build-stack-bundle.sh` updated.
---

# Platform release management

Cutting a `vX.Y.Z` release on this repo (platform repo only — not a consumer repo) publishes GHCR images and a stack bundle tarball that every consumer's `./dev update` depends on. Releases are **GitHub immutable releases**: once published, a tag is burnt forever — not just "hard to undo", literally unreusable, even after deleting the release. This skill is for deciding a version, running the release, verifying it actually shipped correctly, and recovering when it didn't. It is **not** for what goes into a release (mod/config changes — see the mod-management and mod-development skills) or for rolling a published release out to a server (see deploy-pipeline-operations).

## Stop — confirm with a human first

`AGENTS.md § Confirm before proceeding` lists cutting a release under actions that require asking a human before executing: **a burnt tag can't be reused; a broken release breaks `./dev update` for every consumer.** Do not dispatch `release.yml` because a task seems to imply it — say what you're about to do and why, and wait for an explicit go-ahead.

## The one command, and the ban

```bash
gh workflow run release.yml -f version=vX.Y.Z
```

**Never `gh release create` directly.** Assets cannot be attached to an immutable release after publish, so a hand-created release ships with no bundle — `release-guard.yml` will catch this after the fact and fail loudly, but the tag is already burnt by then.

## Pre-flight checks

```bash
gh run list --limit 5                                                          # nothing in flight, especially release.yml
./scripts/test-scripts.sh --quick                                              # shellcheck, py_compile, compose validation
gh release list --limit 5 --json tagName,isLatest --jq '.[] | select(.isLatest) | .tagName'  # the real latest, not just the most recent
```

The last command exists because `gh release list --limit 1` returns the most _recently published_ release, not the highest version — a backported patch (e.g. `v5.1.0` published after `v6.0.0`) sorts first by publish time, not by semver.

## Version selection

From conventional commits since the last tag (`cliff.toml` parses the same prefixes):

| Commit prefix | Bump | Promise to consumers |
| --- | --- | --- |
| `fix:` | patch (`v1.2.0` → `v1.2.1`) | Drop-in safe — bug fixes, mod pin updates |
| `feat:` | minor (`v1.1` → `v1.2`) | Backwards-compatible — new features, new default mods, config additions |
| `BREAKING CHANGE:` footer or `!` after type | major (`v1` → `v2`) | Breaking changes to `.env` keys, overlay contract, or compose structure — migration guide provided, but likely breaking to consumers pinned to the old major |

Consumers pinning `STACK_VERSION=v4` (the common case) automatically receive minor and patch updates on their next deploy — they never see a major bump unless they change the pin themselves.

## Pushing to `main` while a release runs

`publish.yml`'s concurrency group is keyed on what is being built, not on the ref that asked for it:

```yaml
group: publish-${{ inputs.version || github.ref }}
cancel-in-progress: ${{ !inputs.version }}
```

A versioned build — the `images` job `release.yml` calls via `workflow_call`, or a `workflow_dispatch` backfill — gets its own group and is never cancelled. An unversioned build (a push to `main` rebuilding `latest`) keeps `cancel-in-progress: true`, so successive pushes still collapse into one rebuild.

The key has to discriminate on the version because **a reusable workflow evaluates `github.ref` from its caller**: a release dispatched on `main` and a push to `main` both resolve to `refs/heads/main`. Grouping on the ref alone put them in one group, and since `images` is `needs: bundle`, the tag was already published and immutable by the time the push cancelled its images — a release whose version-tagged images do not exist, while production pulls by version tag (`IMAGE_TAG="${STACK_VERSION#v}"`).

A push during a release is still worth avoiding: the bundle job commits `CHANGELOG.md` back to `main` with three rebase attempts and `continue-on-error: true`, so a concurrent push can make that silently no-op. Check `gh run list --limit 5` before pushing.

## What actually happens when you run it

`release.yml` ("Release Bundle") is three jobs, each gating the next:

1. **`smoke-test`** — calls `smoke-test.yml`: builds `defaults-seed` from source, boots the local profile with ~150 cached mod JARs, and runs two matrix variants (`default`, `removal` — a representative consumer `mods-remove.txt`). Asserts RCON health, custom-dimensions load with the right noise presets and structure density, a Carpet bot traverses a real portal end-to-end, and carpet's patched piston mixin hasn't regressed. ~43 minutes on GitHub runners for the `default` variant, ~8 for `removal`; they run in parallel, so the job gates the release for about 43. Nearly all of it is the render-check matrix. **If this fails, nothing else runs — no tag, no release, safe to just fix and re-dispatch the same version.**
2. **`bundle`** (needs `smoke-test`) — validates the version string is `vX.Y.Z`; builds every in-house mod from `mods/local-mods.manifest` and verifies the **remapped** jar (never the `-dev` jar — an unremapped jar boots as a production crash loop, and Gradle reports BUILD SUCCESSFUL regardless); runs `scripts/build-stack-bundle.sh`; verifies every declared local mod actually landed in the bundle tarball; generates release notes and `CHANGELOG.md` via `git-cliff-action@v4.8.0`; **creates the release as a draft with the bundle assets attached, then publishes it** (`gh release create --draft` → `gh release edit --draft=false` — the only order that works with immutable releases); best-effort commits `CHANGELOG.md` back to `main` (rebase-and-retry ×3, `continue-on-error: true` — never fails the job over a changelog push race); best-effort force-moves the major tag (e.g. `v3`) to the release commit.
3. **`images`** (needs `bundle`) — calls `publish.yml` via `workflow_call` with the release version, building and pushing all 7 images (`discord-sync`, `kuma-init`, `mod-checker`, `idle-tasks`, `defaults-seed`, `modpack-builder`, `unmined-render`) tagged `X.Y.Z`, `X.Y`, `X`, and `latest`, multi-arch (amd64+arm64).

Note the `version:` input on the two `git-cliff-action@v4.8.0` steps is pinned to `v2.13.1` — that's the **git-cliff CLI version**, unrelated to the `vX.Y.Z` you're releasing. Don't touch it when cutting a release.

**The release is published (tag burnt) at the end of the `bundle` job — before `images` even starts.** A failure in `images` does not roll anything back; see recovery reference.

## What a release contains

- **GHCR images** tagged `X.Y.Z`, `X.Y`, `X`, `latest` for all 7 sidecar images.
- **Stack bundle tarball** (`stack-vX.Y.Z.tar.gz` + `.sha256`): compose files, every bundle-category script, all of `config/` (minus secrets and platform-internal pin files), and the in-house mod jars under `local-mods/`. Full contents and the manifest trap: `references/bundle-contents.md`.

## Post-release verification

A green workflow run is not proof the release is good — verify directly:

```bash
gh release view vX.Y.Z --json assets --jq '.assets[].name'   # expect stack-vX.Y.Z.tar.gz + .sha256
gh run list --workflow publish.yml --limit 3                 # confirm the images job actually ran to completion
git fetch origin '+refs/tags/v4:refs/tags/v4'                 # refresh your local major tag (substitute the real major)
```

Also check `CHANGELOG.md` on `main` actually picked up the new version — the commit step is best-effort and can silently no-op after 3 failed rebase attempts.

## Traps

1. **A published immutable release burns its tag forever.** Deleting the release does not free the tag. If a release is broken, fix the cause and cut the **next patch version** — never retry the same tag.
2. **A release published without a bundle is broken, not recoverable in place.** `release-guard.yml` fires on `release: published`, checks for a `stack-*.tar.gz` asset, and fails loudly with recovery steps if it's missing (this is the guard against someone using `gh release create` directly). Cut the next patch with the complete asset set — do not delete and re-cut. Draft releases are mutable until publication, so this only bites after the fact.
3. **A release published without its version-tagged images is broken for every consumer who upgrades**, however it happened — the GitHub release itself looks fine, and the failure only shows up as an image pull error on a consumer's deploy. Recovery: `gh run rerun <release-run-id> --failed` rebuilds only the failed jobs — the release and tag are unaffected once the `bundle` job ("Build bundle and publish release") has succeeded. Full detail in `references/release-recovery.md`.
4. **Bundle manifest trap.** A new bundle script not added to the `MANIFEST` array in `scripts/build-stack-bundle.sh` is never shipped to consumers. `lint.yml`'s `bundle-manifest` job catches this only for scripts reached via an `ops` case mapping, an `ops` `ALLOWED_COMMANDS` default, or a `$SCRIPT_DIR/*.sh` reference inside another bundle script — a script added some other way can still slip through silently. Details: `references/bundle-contents.md`.
5. **Stale local major tag.** Releases force-move `vN` (e.g. `v3`) to the new release commit; a stale local copy makes every subsequent `git fetch` complain. Refresh with the explicit refspec shown above, not a plain `git fetch`.
6. **A release sitting on GitHub changes nothing on its own.** Consumers only pick it up when _something_ pushes to their repo and tier detection resolves their symbolic `STACK_VERSION` pin against the newly-tagged release.
7. **Never trust training data for version numbers.** Before bumping any `image:` tag, `uses:` action, or pinned tool/library version touched by a release, look it up live (`gh release list --repo <owner/repo> --limit 5`, checked against `isLatest` — not `--limit 1`). Separately confirm the tag is actually pushed to the registry you're pulling from; some projects publish GitHub releases without a matching image push.
8. **`smoke-test` failing does not burn anything.** The version string isn't consumed until the `bundle` job's `gh release create` call, which only runs after `smoke-test` passes — a failed smoke test is safe to fix and re-dispatch with the identical version.

## Validation

```bash
gh release view vX.Y.Z --json assets --jq '.assets[].name'   # bundle tarball + .sha256 present
gh run list --workflow publish.yml --limit 3                 # images job ran and succeeded
git fetch origin '+refs/tags/v4:refs/tags/v4'                 # local major tag matches (substitute real major)
```

## References

- `references/release-recovery.md` — every failure mode (cancelled images, missing bundle, failed smoke test, burnt tag, changelog/major-tag push races) with the exact recovery command.
- `references/bundle-contents.md` — what `build-stack-bundle.sh` packs, the `MANIFEST` array, and exactly what `lint.yml`'s manifest check does and doesn't catch.
