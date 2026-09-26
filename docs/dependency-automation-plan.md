# Dependency automation plan (working record; deleted when DEPENDENCIES.md covers it)

## Your calls

| Topic | Decision |
| --- | --- |
| Template releases | A weekly **release train** (Wednesday 06:00 UTC) computes the version from commits. Patches and minors release automatically; a **major waits for your one-click approval** through a protected `release-major` environment |
| Worldgen | The next-major PR refreshes weekly and **auto-merges once a month** (the first release train of the month) when reviewed and green: at most one major a month |
| Consumer PRs | Patch and minor bumps **auto-merge** once the new release's bundle and images are verified, then dispatch a deploy. Majors wait for a human |
| Consumer pin | A **tracked pin file**, `.stack-version`, at the consumer root. The weekly `update.yml` bumps it; `./dev` and `deploy.yml` read it. The `STACK_VERSION` repository variable and `.env` key retire |

## Consequence of the pin file, and how it's handled

`GITHUB_TOKEN` can't push changes to workflow files, so on a major the `deploy-reusable.yml@vN` line in `deploy.yml` can't be bumped by the update workflow.

- The major PR's body carries it as a checklist step (majors are human-merged anyway).
- `deploy-reusable.yml` compares its own ref's major (`job.workflow_ref`) with `.stack-version` and **fails before touching the server** if they differ.
- Optional: the consumer `dependabot.yml` also proposes the `@vN` bump, making it a one-click merge.

## Defaults I've taken (say if any is wrong)

- **Semver contract:**
  - **major:** worldgen, a mod removed, consumer action needed, a loader or Java change.
  - **minor:** any client-side mod or pack change, a new mod, new features.
  - **patch:** server-only fixes and image patches.
  - **no release:** CI, docs and tests.
- **How it's enforced:** the reviewer sets `release_impact`, which deterministic floors can raise but never lower, and `apply.py` writes the squash commit title and the `BREAKING CHANGE:` footer. A major's consumer notes come from those footers, through git-cliff into the release notes. There is no separate `UPGRADING.md`.
- **Compose images, Fabric loader and hand-pinned tools** get a new weekly `platform-updates.yml`, reviewed by dep-review.
- **Gradle, the root `requirements-dev.txt`, kuma-init's pip and the consumer scaffold's Actions** are added to Dependabot.
- **`DEPENDENCIES.md`** goes at the repo root, following the audited outline. AGENTS.md keeps one-line rules under `## Mods`; `docs/dependency-review.md` keeps only the pipeline internals.
- **Bugs:** `docs/bug-backlog.md`. B1 (the `latest` image tag) and B6 (images built after publish) are fixed as part of the release train, since the train is unsafe without them. The rest wait until the features are done.

## Build order

1. `DEPENDENCIES.md`, moving material out of the other docs; the reviewer's prompt then follows it.
2. The semver contract in dep-review: the `release_impact` verdict, floors, and commit titles and footers.
3. B1 and B6, then the release train with major gating, then the monthly worldgen merge.
4. `platform-updates.yml` and the Dependabot extensions.
5. The consumer side: `.stack-version`, the update workflow bump and auto-merge, the deploy guard, scaffold sync, then migrating elfydd.
