# Releasing

## How to cut a release

1. Ensure `main` is clean and CI is green.
2. Review the conventional-commit history since the last release to determine the semver bump:
   - `fix:` → patch, `feat:` → minor, `BREAKING CHANGE:` / `!` → major.
3. Run the release workflow (it builds the bundle, creates the release as a draft with assets attached, then publishes — the only order compatible with immutable releases):
   ```bash
   gh workflow run release.yml -f version=vX.Y.Z
   ```

Do NOT use `gh release create` directly: with immutable releases enabled, assets can't be attached after publish, so the bundle upload fails.

A published immutable release burns its tag forever — GitHub does not permit the tag to be reused, even if the release is deleted. If a release fails, fix the cause and cut the **NEXT patch version**; never retry the same tag.

**If a published release ships without a bundle:** treat it as broken and cut the next patch version with the complete asset set. Do not delete and re-cut the same tag. Draft releases are mutable until publication, so verify every asset on the draft before publishing it.

## Release notes and changelog

Release notes are auto-generated from conventional commits by [git-cliff](https://git-cliff.org) (configured in `cliff.toml`). The release workflow:

1. Generates per-release notes → injected into the GitHub release body
2. Regenerates `CHANGELOG.md` → committed to main

`./dev update` prints the release URL when the version changes, so consumers can see what's new. Commit message quality directly determines changelog quality — see [CONTRIBUTING.md](../../../../CONTRIBUTING.md#conventions) for the format.

## Tag protection

Enable tag protection rules in GitHub (Settings → Rules → Rulesets) to prevent direct `v*` tag pushes. Only GitHub Actions should create release tags:

1. Create a ruleset targeting tags matching `v*`
2. Set bypass: GitHub Actions only
3. Restrict creation to "through a merge queue or GitHub Actions only"

This prevents accidental `git push origin v2.7.0` which would create a broken release without a bundle.

## Two pipelines, one chain

| Workflow | Triggers | Produces |
| --- | --- | --- |
| `release.yml` (Release Bundle) | Manual dispatch only | Stack bundle tarball → draft release → publish |
| `publish.yml` (Publish Container Images) | `release: published`, **any** push to main (no path filter), manual dispatch | GHCR images tagged `X.Y.Z`, `X.Y`, `X`, `latest` |

Pushing to `main` triggers `publish.yml` independently (images tagged `latest` + sha). A deploy pulls the images of the concrete release its pin resolves to, and only `release.yml` produces the bundle tarball that consumers need for `./dev update`.

## Compatibility promise

What each bump may contain, the within-major guarantees and how consumers take a release: [DEPENDENCIES.md § Versioning contract](../../../../DEPENDENCIES.md#versioning-contract) and [§ For consumers](../../../../DEPENDENCIES.md#for-consumers).
