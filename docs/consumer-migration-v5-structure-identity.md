# Consumer migration: exact structure identity (v5.1.0)

DRAFT — steps for rolling a consumer repo with an existing seed bank onto
v5.1.0's exact structure identity (`structureSelection: pick-v1`). elfydd has
completed this migration; this is the checklist for any other consumer (the
production repo included). Nothing here touches the game world — the
migration re-derives roller measurements only. No production action is
implied; deploys follow the normal CI path.

## What changes and why

v5.1.0 makes every noise site's occupant an exact, mirrored fact. The
generation payload of every noise-managed dimension gains
`structureSelection: "pick-v1"`, so every banked candidate's generation
fingerprint re-keys: `./dev seed-status` reports every noise dimension
**DRIFTED** until the bank is migrated. This is correct, not a fault — the
structure layer of every banked measurement must be recomputed once under
the new identity model. Terrain and biome generation are unchanged; worlds
need no wipe and no re-roll is forced.

## Prerequisites

- Consumer repo pinned to `v5` (or `v5.1.0`+) and `./dev update` run, so
  `.stack/current` resolves to the v5.1.0 bundle.
- A platform checkout beside the consumer repo — two migration tools are
  template-only and not shipped in the bundle:
  `scripts/seed/migrate-structure-identity.sh` and
  `scripts/seed/restamp-structure-identity.py`.
- Local stack booted once on the new bundle (`./dev up`) so the mod jar is
  the released one.

## Checklist

1. **Dump fresh structure pools** (live server, new jar):
   `docker exec -i mc rcon-cli "customdim dump-structure-pools"`.
2. **Migrate the bank** — from the platform checkout:
   `scripts/seed/migrate-structure-identity.sh` against the consumer's
   `.seedtest`. It backs up `candidates/` to `candidates.bak.<timestamp>/`
   first, then runs a rescore whose schema-version cache miss recomputes the
   whole structure layer (schemaVersion-2 sidecars + `byStructure`,
   poolHash-stamped). Hours at the default worker count; Ctrl+C loses
   nothing (checkpointed); re-runs resume.
3. **Verify the migration** —
   `python3 scripts/seed/verify-structure-identity.py --seedtest <consumer>/.seedtest --backup <consumer>/.seedtest/candidates.bak.<timestamp>`
   must exit 0: every candidate whose backup had a populated group now has a
   sidecar + `byStructure` (or `{}` with the pool confirming empty).
4. **Restamp fingerprints** —
   `python3 scripts/seed/restamp-structure-identity.py --seedtest <consumer>/.seedtest --config <consumer>/.stack/current/stack/config/custom-dimensions`
   (with `SEED_OVERLAY_DIR` pointing at the consumer overlay). Restamps only
   candidates whose recomputed census carries the current noise fingerprint,
   pool hash and sidecar. Run `--dry-run` first and read the per-dim counts.
5. **Confirm freshness** — `./dev seed-status`: expect 0 DRIFTED / 0 STALE.
   A dimension still DRIFTED after restamping had a real generation-config
   change since measurement — that one genuinely needs a re-roll, not a
   stamp.
6. **Winners** — scores move under exact identity, so ranking changes are
   expected. Re-roll or re-pick winners on the owner's timing; nothing
   forces it.

## Expectations to state up front

- The migration is deliberate and manual, once per consumer.
- The bank backup stays on disk; deleting it is the owner's call after the
  verify gate passes.
- Tag-based wants resolve from real tag data; anything not exactly
  measurable is banked as such and shown as "not exactly measurable" — a
  score that changed to reflect that is the system being honest, not a
  regression.
