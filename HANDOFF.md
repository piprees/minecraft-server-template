# Handoff: the 26.3 rebuild

Temporary working log for the custom-dimensions rebuild. One line per finding,
decision or error, newest last. Delete this file once the rebuild ships; the
lasting record is `docs/design/rebuild-26/` and `TROUBLESHOOTING.md`.

## State

- Branch: `claude/minecraft-dependency-automation-n1w79q` (PR #29). `main` has
  not moved since the branch was cut.
- Blocked on the owner: merging PR #29 and the Dependabot PRs into `main`, and
  switching the repository to private. Both were refused by the session's
  permission rules; see the log.
- Plan of record: `docs/design/rebuild-26/README.md`.

## Log

- 10-22 — Latest stable Minecraft is 26.3 (26.4 is at snapshot 5); the rebuild targets 26.3.
- 10-22 — Open PRs: #29 (this branch, green), Dependabot #27 #30–#34 #38–#40 (green, one-line bumps), #26 mod-updates/auto (56 bumps for 1.21.1 with no CI run; includes worldgen mods, which the versioning contract ships only in a major).
- 10-22 — Merge of #29 into `main` refused by the session's permission classifier ("merge without review"). Work continues on the branch; the owner merges.
- 10-22 — Going private breaks consumer deploys as built: the reusable workflow resolves releases with the caller's `github.token`, and the server pulls the bundle anonymously. elfydd is private, so it can still call the workflow once Actions access is set to "user". Fix: an optional `STACK_READ_TOKEN` secret.
- 10-22 — Going private also moves Actions onto the free plan's 2,000 minutes a month; the smoke-test boots are the biggest consumer.
- 10-22 — Quirk (this container): `python3` is 3.11, but `scripts/score-biome-table.py` uses 3.12 f-string syntax and CI runs 3.13. Run quick checks with 3.13 first on PATH; then 421 tests pass. No Docker daemon here, so the compose-runtime checks can't run.
- 10-22 — Added optional `STACK_READ_TOKEN` (deploy-reusable resolve + server pull over stdin; consumer deploy.yml and update.yml; credentials reference; consumer README). Simulated the pull step with and without a token: both scripts generate and parse.
