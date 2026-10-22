# Custom Dimensions, rebuilt for 26.x

The plan for rebuilding the `custom-dimensions` mod on Minecraft 26.3 and the pack around it: keep every feature players and admins use and every lesson in `TROUBLESHOOTING.md`, rebuild the Minecraft-facing half around what 26.x made data, own all of the worldgen, and keep the mod small enough that a new Minecraft release is a routine job. The result is a fresh world on 26.3, open to unmodded Java clients, the optional pack and Bedrock.

**Status:** planning complete; F0 spike in progress. The zone spike passed ([spike/f0-ring-zone.png](spike/f0-ring-zone.png), findings in [decisions.md](decisions.md) RB-27 and RB-28).

## Principles

- **100% server-side.** No client mod is ever necessary to join or play. An unmodded 26.3 Java client and Bedrock (through Geyser and Floodgate) see the full game. Client additions are optional presentation only ([RB-25](decisions.md)); the companion mod renders live portal previews and nothing depends on it.
- **One worldgen owner.** Every dimension, the four base worlds included, runs on vanilla's noise generator with settings our mod writes. A firewall refuses to start if another mod shapes terrain, biomes or surfaces; structure mods join through bridges.
- **Small surface.** About 32,000 lines and 12 or fewer mixins, each with a game test. Dependencies: Fabric API, Fantasy and Polymer.
- **Ready for 27.x.** One codebase builds for every 26.x; a weekly canary builds against each new snapshot; renamed worldgen fields are entries in the compiler's version table.

## Documents

| Document | What it covers |
| --- | --- |
| [requirements.md](requirements.md) | The acceptance list: every requirement, how it is met server-side, what Bedrock gets |
| [decisions.md](decisions.md) | Every decision with its reason and the alternatives rejected |
| [architecture.md](architecture.md) | The four parts, package fates, mixins, dependencies, the compiler and levels |
| [worldgen.md](worldgen.md) | The layer table, zones, the lessons it is built around, clean-room rule, firewall and bridges |
| [roller.md](roller.md) | `customdim render`, `roll` and `pick`: the sampler, scoring and the agreement gate |
| [portals.md](portals.md) | Frames, orientations, shapes, the portal block, travel, auras and previews |
| [pack.md](pack.md) | The inclusion rule, counts by group and decision, and the additions |
| [mod-register.csv](mod-register.csv) | All 235 projects with their decision, reason and replacement |
| [work-plan.md](work-plan.md) | Every work package with size and check, the calendar, the cut order and totals |
| [risks.md](risks.md) | What can go wrong, where it shows, the fallback, and the owner's actions |
