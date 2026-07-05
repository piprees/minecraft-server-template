## What

<!-- One-sentence summary of the change. -->

## Why

<!-- What problem does this solve or what does it improve? Link any related issues. -->

## How

<!-- Brief description of the approach. For mod changes, include dependency resolution. -->

## Deploy tier

<!-- Which deploy tier does this trigger? Delete the ones that don't apply. -->

- [ ] **Full** - touches docker-compose.yml, server.env, modrinth-mods.txt, synced mod configs, deploy.sh, or initial-setup.sh
- [ ] **Infra** - touches other config/ or scripts/ files (sidecars restarted, mc untouched)
- [ ] **Pull** - docs, CI, or modpack manifest only (git pull, no compose)
- [ ] **None** - no deployment impact (e.g. issue template changes)

## Checklist

- [ ] `./scripts/test-scripts.sh --quick` passes
- [ ] Tested locally with `./dev up` (if applicable)
- [ ] Conventional commit messages used
- [ ] Documentation updated (if behaviour changed)
