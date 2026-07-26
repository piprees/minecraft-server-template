# Spike — Docs-to-Skills Consolidation

> **Date:** 2026-07-27 | **Status:** planned, ready to execute
> **Prompted by:** audit of `docs/` vs `.claude/skills/` — scattered
> documentation that agents load in full when they only need one domain,
> duplicated material across docs and skills, and orphaned READMEs in
> config subdirectories that no skill references.

## Goal prompt

```
/goal

Read `docs/spikes/SPIKE-DOCS-TO-SKILLS.md` in full, then `AGENTS.md`
and `README.md`. Execute the phases in order. Each phase is independent
— commit after each one. Follow the verify steps; do not skip them.
For skill creation, match the structure and frontmatter style of
existing skills (e.g. `server-provisioning/SKILL.md`,
`web-surface-branding/SKILL.md`). Reference files go under
`references/` within the skill directory.
```

## Decision

Consolidate scattered documentation into the skills system: create two
new skills, fold five docs into existing skills as reference files,
retire five docs, absorb two orphaned READMEs, and tidy spike files.

## Task list

### Phase A: Delete superseded/stale docs (quick wins)

- [ ] **A1.** Delete `docs/seed-rolling.md` — fully superseded by the
  `seed-rolling` skill + `scripts/seed/README.md`.

  **Verify:** `grep -rn 'docs/seed-rolling' .` — update or remove any
  references found (expect README.md's docs table).

- [ ] **A2.** Delete `docs/dimension-profiles-v3.md` — proposal-era
  table; the live config files in `config/custom-dimensions/dimensions/`
  are the source of truth.

  **Verify:** `grep -rn 'dimension-profiles-v3' .` — update or remove
  references.

- [ ] **A3.** Delete `docs/migration-v3.md` — no other consumers exist
  yet; the migration is historical.

  **Verify:** `grep -rn 'migration-v3' .` — update or remove references.

- [ ] **A4.** Delete `docs/security.md` — 13 lines, strict subset of
  `server-provisioning` skill + root `SECURITY.md`.

  **Verify:** confirm `server-provisioning/SKILL.md` or its references
  already cover UFW, fail2ban, SSH hardening, rate limiting, Docker
  iptables, online-mode/whitelist enforcement. If anything is missing,
  add it to the skill's references before deleting.

- [ ] **A5.** Delete `docs/deployment.md` — 17 lines, provider costs
  and backup alternatives. Fold any content not already in
  `server-provisioning` into that skill's references before deleting.

  **Verify:** `server-provisioning/references/step-chain.md` or
  `credential-sourcing.md` mentions Hetzner/DO/local routing and backup
  alternatives. If not, add a brief section.

- [ ] **A6.** Update `docs/README.md` — remove rows for all deleted
  files. The index must match what exists.

  **Verify:** every link in `docs/README.md` resolves to a real file.

### Phase B: Fold docs into existing skills as references

- [ ] **B1.** Move `docs/setup-guide.md` →
  `server-provisioning/references/setup-walkthrough.md`.
  Update cross-references in `server-provisioning/SKILL.md` and
  anywhere else that linked to `docs/setup-guide.md`.

  **Verify:** `grep -rn 'docs/setup-guide' .` — all references updated.
  `grep -rn 'setup-walkthrough' .claude/skills/server-provisioning/` —
  the SKILL.md mentions the new path.

- [ ] **B2.** Move `docs/credentials.md` →
  `server-provisioning/references/credentials.md`.
  Update cross-references. The skill already duplicates the Cloudflare
  three-credentials section — after the move, the SKILL.md should
  reference rather than duplicate.

  **Verify:** `grep -rn 'docs/credentials' .` — all updated.

- [ ] **B3.** Move `docs/releasing.md` →
  `platform-release-management/references/releasing-procedure.md`.
  Update cross-references.

  **Verify:** `grep -rn 'docs/releasing' .` — all updated.

- [ ] **B4.** Move `config/datapack-presets/README.md` content into the
  new `worldgen-tuning` skill (Phase C) as a reference file. Leave a
  one-line stub README pointing at the skill: "See
  `.claude/skills/worldgen-tuning/references/structure-presets.md`."

  **Verify:** the original README location has the stub; the content
  lives in the skill.

- [ ] **B5.** Move `scripts/seed/README.md` content into
  `seed-rolling/references/pipeline-architecture.md`. Leave a one-line
  stub README pointing at the skill.

  **Verify:** same pattern as B4.

- [ ] **B6.** Update `docs/README.md` — remove rows for moved files,
  add a note that setup/credentials/releasing/security/deployment
  content now lives in the skills system.

### Phase C: Create `worldgen-tuning` skill

- [ ] **C1.** Create `.claude/skills/worldgen-tuning/SKILL.md` with
  frontmatter matching existing skills. Description: covers Tectonic
  terrain dials, structure frequency presets, per-dimension
  noiseSettings/structureDensity, generator types (checkerboard,
  superflat), settingsOverrides, biome parameters, biomePatches,
  structure spacing/mode/force. Trigger: changing terrain shape,
  adjusting structure density, tuning Tectonic, adding biome patches,
  overriding structure placement.

  Source material (extract, don't copy wholesale — write skill-style
  prose with traps and verification commands):
  - `docs/customisation.md` §§ Worldgen: terrain shape, structure
    frequency presets, per-dimension profiles, portals (portal material
    goes to custom-dimension-authoring instead — see C3)
  - `config/datapack-presets/README.md` (becomes a reference)
  - `AGENTS.md` § Mods (the worldgen-relevant parts)

- [ ] **C2.** Create reference files:
  - `worldgen-tuning/references/tectonic-dials.md` — the full dial
    table from customisation.md § Worldgen: terrain shape
  - `worldgen-tuning/references/structure-presets.md` — from
    `config/datapack-presets/README.md`
  - `worldgen-tuning/references/generator-types.md` — checkerboard,
    superflat, settingsOverrides, biome parameters, biomePatches,
    structure spacing/mode/force from customisation.md

- [ ] **C3.** Move the portal/exit/shrine content from
  `docs/customisation.md` (§§ Portals, Dimension links and exit
  conditions, Exit shrines) into
  `custom-dimension-authoring/references/` as a new reference file
  (e.g. `portals-and-exits.md`). Update the skill's SKILL.md to
  reference it.

  **Verify:** `custom-dimension-authoring/SKILL.md` mentions the new
  reference. The content is not duplicated in two places.

### Phase D: Create `consumer-customisation` skill

- [ ] **D1.** Create `.claude/skills/consumer-customisation/SKILL.md`.
  Description: consumer-facing customisation via the overlay contract —
  branding, assets, loading screen, title screen panorama, starter kit,
  player messages, resource packs, removing default mods, multi-instance
  setup. Trigger: rebranding, changing loading screen, adding resource
  packs, editing starter kits, overriding configs, removing default
  mods.

  Source material:
  - `docs/customisation.md` §§ Brand identity, Loading screen, Title
    screen panorama, Map markers, Starter kit, Player-facing messages,
    Multi-instance, Mod list, Removing default mods, Resource packs,
    Minecraft version, What to keep private
  - `examples/consumer/README.md` § Customising (the consumer-facing
    version — reconcile differences, don't duplicate)

- [ ] **D2.** Create reference files:
  - `consumer-customisation/references/overlay-paths.md` — the
    complete overlay path reference (which file overrides what)
  - `consumer-customisation/references/panorama-capture.md` — the
    step-by-step cubemap capture guide from customisation.md
  - `consumer-customisation/references/resource-packs.md` — the
    resource pack system (slug forms, filename pinning, options.txt)

### Phase E: Slim `docs/customisation.md`

- [ ] **E1.** After C and D are complete, replace `docs/customisation.md`
  with a short hub page pointing at the three skills that now own the
  content: `consumer-customisation`, `worldgen-tuning`, and
  `custom-dimension-authoring` (for portals). Keep enough context for a
  human reader to know which skill covers what, but no duplicated
  procedural content.

  **Verify:** `docs/customisation.md` is under 50 lines. Every section
  that used to be here has a clear pointer to its new home. No orphaned
  cross-references from other files.

- [ ] **E2.** Update `examples/consumer/README.md` to reference the
  `consumer-customisation` skill for detailed procedures (panorama
  capture, resource pack details, splash screen config reference) rather
  than duplicating them inline. Keep the consumer README self-contained
  for quick-start but link to the skill for depth.

### Phase F: Tidy spike files

- [ ] **F1.** Move `scripts/seed/spike/*.md` (14 files) to
  `docs/spikes/seed/`. These are historical R&D reports, not active
  tooling docs.

  **Verify:** `scripts/seed/README.md` (now a stub) or the
  `seed-rolling` skill's pipeline-architecture reference still
  cross-references them at their new path.

- [ ] **F2.** Update `scripts/seed/README.md` stub (from B5) to point
  at `docs/spikes/seed/` for the research notes table.

### Phase G: Final verification

- [ ] **G1.** Run `./scripts/test-scripts.sh --quick` — no regressions.

- [ ] **G2.** `grep -rn 'docs/' .claude/skills/` — every docs/ reference
  from skills should resolve. No dangling links.

- [ ] **G3.** `grep -rn '\.claude/skills/' docs/` — any docs that
  reference skills should use correct paths.

- [ ] **G4.** Verify `docs/README.md` index matches the actual contents
  of `docs/`.

- [ ] **G5.** Verify every skill's SKILL.md `description` field in
  frontmatter matches the skill's listing description (the system
  reminder shows these — they must match or the skill won't trigger
  correctly).

## Files touched

| Action | File |
| --- | --- |
| Delete | `docs/seed-rolling.md` |
| Delete | `docs/dimension-profiles-v3.md` |
| Delete | `docs/migration-v3.md` |
| Delete | `docs/security.md` |
| Delete | `docs/deployment.md` |
| Move | `docs/setup-guide.md` → `server-provisioning/references/` |
| Move | `docs/credentials.md` → `server-provisioning/references/` |
| Move | `docs/releasing.md` → `platform-release-management/references/` |
| Move | `config/datapack-presets/README.md` content → `worldgen-tuning/references/` |
| Move | `scripts/seed/README.md` content → `seed-rolling/references/` |
| Move | `scripts/seed/spike/*.md` → `docs/spikes/seed/` |
| Create | `.claude/skills/worldgen-tuning/` (SKILL.md + 3 references) |
| Create | `.claude/skills/consumer-customisation/` (SKILL.md + 3 references) |
| Edit | `docs/customisation.md` (slim to hub page) |
| Edit | `docs/README.md` (update index) |
| Edit | `examples/consumer/README.md` (link to skills for depth) |
| Edit | `server-provisioning/SKILL.md` (reference new paths) |
| Edit | `platform-release-management/SKILL.md` (reference new path) |
| Edit | `custom-dimension-authoring/SKILL.md` (add portals reference) |
| Edit | `seed-rolling/SKILL.md` (add pipeline-architecture reference) |
| Stub | `config/datapack-presets/README.md` (pointer to skill) |
| Stub | `scripts/seed/README.md` (pointer to skill) |

## Not in scope

- **New skills for `ci-workflow-management` and `modpack-management`** —
  identified as gaps but not blocking. Revisit after this consolidation
  lands and we see what agents still struggle with.
- **Root-level docs** (`COMMANDS.md`, `CONTRIBUTING.md`, `DESIGN.md`,
  `PRODUCT.md`, `TROUBLESHOOTING.md`) — these stay at root. They're
  top-level entry points or GitHub conventions.
- **`mods/custom-dimensions/immersive/*.md`** — the active phase docs
  stay; the archive is already tidy.
- **Editing AGENTS.md** — this spike moves content OUT of docs into
  skills; AGENTS.md's cross-references may need updating but its
  structure doesn't change.

## Risks

| Risk | Mitigation |
| --- | --- |
| Broken cross-references after moves | G2/G3 grep verification; commit after each phase |
| Skill frontmatter description drift | G5 verification against system reminder listings |
| Consumer README becomes too terse | E2 keeps quick-start self-contained; links for depth |
| `docs/customisation.md` hub page goes stale | It contains pointers only, not content — nothing to drift |
| Skills become too large to load efficiently | Each skill has a focused SKILL.md (<150 lines) with detail in references/ — only loaded when the skill activates |
