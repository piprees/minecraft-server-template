## What

<!-- One-sentence summary of the change. -->

## Why

<!-- What problem does this solve or what does it improve? Link any related issues. -->

## How

<!-- Brief description of the approach. For mod changes, include dependency resolution. -->

## Deploy tier

<!-- Which deploy tier does this trigger? Delete the ones that don't apply. -->

- [ ] **Full** - touches overlay/config/, overlay/mods-extra.txt, overlay/mods-remove.txt, docker-compose.yml, deploy.sh, or initial-setup.sh (see `FULL_PATTERNS` in `.github/workflows/deploy-reusable.yml` for the exact list)
- [ ] **Infra** - touches other overlay/ files, assets, or branding (sidecars restarted, mc untouched)
- [ ] **Pull** - docs, CI, or modpack manifest only (git pull, no compose)
- [ ] **None** - no deployment impact (e.g. issue template changes)

## Checklist

- [ ] `./scripts/test-scripts.sh --quick` passes
- [ ] Tested locally with `./dev up` (if applicable)
- [ ] Conventional commit messages used
- [ ] Documentation updated (if behaviour changed)
