# Custom Dimensions, rebuilt for 26.x

The plan for rebuilding the `custom-dimensions` mod on Minecraft 26.3 and the pack around it: keep every feature players and admins use and every lesson in `TROUBLESHOOTING.md`, rebuild the Minecraft-facing half around what 26.x made data, own all of the worldgen, and keep the mod small enough that a new Minecraft release is a routine job. The result is a fresh world on 26.3, open to unmodded Java clients, the optional pack and Bedrock.

**Status:** F0–F3, M1, M2 and M6 done; M6b (auras) is next. The spikes passed on 26.3 (RB-27, RB-28). [`mods/custom-dimensions-26/`](../../../mods/custom-dimensions-26/README.md) compiles the definitions to a datapack, opens every dimension as a lazy Fantasy level and lights portals on its Polymer block, with 682 tests and an in-game self-test. Its `definitions/` holds the 82 v2 definitions converted from the 1.21.1 set, with [`conversion-report.md`](../../../mods/custom-dimensions-26/definitions/conversion-report.md) listing the 790 ids from mods the register doesn't keep, which W8 replaces. Next: the M, W and P streams (week 4 onwards in [work-plan.md](work-plan.md)).

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
| [schema-v2.md](schema-v2.md) | The v2 definition format, the field map from v1, and the F3 converter |
| [namespaces.csv](namespaces.csv) | Each data namespace in the 1.21.1 pack and the mod that owns it, with the register's decision |
| [pack.md](pack.md) | The inclusion rule, counts by group and decision, and the additions |
| [mod-register.csv](mod-register.csv) | All 235 projects with their decision, reason and replacement |
| [work-plan.md](work-plan.md) | Every work package with size and check, the calendar, the cut order and totals |
| [risks.md](risks.md) | What can go wrong, where it shows, the fallback, and the owner's actions |
