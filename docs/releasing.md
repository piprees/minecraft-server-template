# Releasing

## How to cut a release

1. Ensure `main` is clean and CI is green.
2. Review the conventional-commit history since the last release to determine the semver bump:
   - `fix:` → patch, `feat:` → minor, `BREAKING CHANGE:` / `!` → major.
3. Create the release:
   ```bash
   gh release create vX.Y.Z --generate-notes --target main
   ```

## What a release produces

A published release triggers two workflows:

| Workflow | Output |
| --- | --- |
| **Publish Container Images** (`publish.yml`) | 6 GHCR images (`discord-sync`, `kuma-init`, `mod-checker`, `idle-tasks`, `defaults-seed`, `modpack-builder`) tagged `X.Y.Z`, `X.Y`, `X`, and `latest` |
| **Release Bundle** (`release.yml`) | `stack-vX.Y.Z.tar.gz` + `.sha256` attached to the release, plus an advancing `vX` major tag for workflow `@vX` references |

## Compatibility promise

Within a major version:

- No breaking changes to the overlay contract (directory structure, merge semantics).
- No breaking changes to the env contract (`.env` variables, GitHub environment vars/secrets).
- No breaking changes to reusable-workflow inputs or secrets.

A major bump signals that consumers must review migration notes before upgrading.

## Consumer impact

To upgrade, consumers:

1. Bump `STACK_VERSION` in `.env` (e.g. `v1` stays on latest `v1.x.y`; pin `v1.2.3` for exact control).
2. Run `./dev update` or `./stack-pull.sh` to fetch the new bundle.
3. Restart local dev or redeploy.
