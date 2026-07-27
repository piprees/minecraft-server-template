# Customisation guide

This guide's content has been split into focused skills for better agent discoverability. Each skill has its own reference files with full details.

## Consumer customisation (branding, mods, packs)

Brand identity, loading screen, title screen panorama, starter kits, player messages, resource packs, adding/removing mods, multi-instance setup, and what to keep private.

**Skill:** [`.claude/skills/consumer-customisation/SKILL.md`](.claude/skills/consumer-customisation/SKILL.md)

Quick reference for overlay paths: [`.claude/skills/consumer-customisation/references/overlay-paths.md`](.claude/skills/consumer-customisation/references/overlay-paths.md)

## Worldgen tuning (terrain, structures, dimensions)

Tectonic terrain dials, structure frequency presets (default/dense/sparse), per-dimension noiseSettings and structureDensity, generator types (checkerboard, superflat), settingsOverrides, biome parameters, biomePatches, and per-dimension structure control.

**Skill:** [`.claude/skills/worldgen-tuning/SKILL.md`](.claude/skills/worldgen-tuning/SKILL.md)

## Portals, exits, and dimension authoring

Portal frame materials and shapes, immersive portals, auras, anchors, single-use portals, exit conditions (void/death/enderPearl/fallFrom), exit shrines, and the full dimension JSON schema.

**Skill:** [`.claude/skills/custom-dimension-authoring/SKILL.md`](.claude/skills/custom-dimension-authoring/SKILL.md)

Source of truth for the dimension-file schema: [`mods/custom-dimensions/README.md`](mods/custom-dimensions/README.md)

## Seed rolling

`./dev seed-roll` evaluates dimension seeds without running the game. Covers candidate banking, scoring, the interactive viewer, and seed groups.

**Skill:** [`.claude/skills/seed-rolling/SKILL.md`](.claude/skills/seed-rolling/SKILL.md)

## Web surface styling

Pack page, mods page, status page, map page, nav bar, design tokens, and the footer version string.

**Skill:** [`.claude/skills/web-surface-branding/SKILL.md`](.claude/skills/web-surface-branding/SKILL.md)
