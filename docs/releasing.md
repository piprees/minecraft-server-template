# Releasing

## How to cut a release

1. Ensure `main` is clean and CI is green.
2. Review the conventional-commit history since the last release to determine the semver bump:
   - `fix:` → patch, `feat:` → minor, `BREAKING CHANGE:` / `!` → major.
3. Run the release workflow (it builds the bundle, creates the release as a
   draft with assets attached, then publishes — the only order compatible
   with immutable releases):
   ```bash
   gh workflow run release.yml -f version=vX.Y.Z
   ```

Do NOT use `gh release create` directly: with immutable releases enabled,
assets can't be attached after publish, so the bundle upload fails.

A failed or deleted release burns its tag forever — immutable releases
reserve the tag name permanently, even after deletion. If a release fails,
fix the cause and cut the NEXT patch version; never retry the same one.

**If a release ships without a bundle:** delete it and re-cut (the only fix for an immutable release):

```bash
gh release delete vX.Y.Z --yes
git push origin :refs/tags/vX.Y.Z
gh workflow run release.yml -f version=vX.Y.Z
```

## Release notes and changelog

Release notes are auto-generated from conventional commits by [git-cliff](https://git-cliff.org) (configured in `cliff.toml`). The release workflow:

1. Generates per-release notes → injected into the GitHub release body
2. Regenerates `CHANGELOG.md` → committed to main

`./dev update` prints the release URL when the version changes, so consumers can see what's new. Commit message quality directly determines changelog quality — see [CONTRIBUTING.md](../CONTRIBUTING.md#commit-conventions) for the format.

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
| `publish.yml` (Publish Container Images) | `release: published`, push to main (Dockerfile/script changes), manual dispatch | GHCR images tagged `X.Y.Z`, `X.Y`, `X`, `latest` |

Pushing to `main` triggers `publish.yml` independently (images tagged `latest` + sha), so consumers on `latest` get image updates between releases. But only `release.yml` produces the bundle tarball that consumers need for `./dev update`.

## Compatibility promise

- **Major** (`v1` → `v2`): breaking changes to `.env` keys, overlay contract, or compose structure. Migration guide provided.
- **Minor** (`v1.1` → `v1.2`): new features, new default mods, config additions. Backwards-compatible.
- **Patch** (`v1.2.0` → `v1.2.1`): bug fixes, mod pin updates. Drop-in safe.

Within a major version:

- No breaking changes to the overlay contract (directory structure, merge semantics).
- No breaking changes to the env contract (`.env` variables, GitHub environment vars/secrets).
- No breaking changes to reusable-workflow inputs or secrets.

A major bump signals that consumers must review migration notes before upgrading.

Consumers pinning `STACK_VERSION=v1` automatically receive minor and patch updates.

## Consumer impact

To upgrade, consumers:

1. Bump `STACK_VERSION` in `.env` (e.g. `v1` stays on latest `v1.x.y`; pin `v1.2.3` for exact control).
2. Run `./dev update` (or `./dev pull`) to fetch the new bundle.
3. Restart local dev or redeploy.
