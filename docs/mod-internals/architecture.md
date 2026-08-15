# Architecture (custom-dimensions)

Component tree for the mod. Rules live in [`mods/AGENTS.md`](../../mods/AGENTS.md); subsystem detail in [`portals.md`](portals.md), [`worldgen-structures.md`](worldgen-structures.md) and [`diagnostics.md`](diagnostics.md).

```
MultiverseServer (entrypoint)
├── WorldLoaderMixin → hooks server start/stop
│   ├── MultiverseConfig.load() → reads repo-owned JSON config (read-only)
│   ├── PortalHelper.loadPortalLinks() → JSON portal state
│   └── DimensionManager.registerDimensions() → unfreezes registry, adds entries
│       (worlds are NOT created at boot — lazy creation on first player entry)
├── END_SERVER_TICK → drains pending world loads/unloads, reconciles orphans
├── ServerWorldMixin → per-tick logic
│   ├── validates portal zones (removes broken ones)
│   ├── teleports players stepping into portals
│   ├── spawns coloured particles on all portals
│   ├── immersive proximity scan → ImmersivePreloader (target world + chunks)
│   ├── ImmersiveProjector.tick → fake-block preview + cross-portal audio
│   └── EntityPassthrough.tick → items/projectiles crossing immersive zones
├── immersive/ → presentation layer, gated on portal.immersive (null = off)
│   ├── ImmersiveSettings (config record; transient on PortalDefinition)
│   ├── ImmersivePreloader → proximity world load + arrival chunk pre-gen
│   ├── ProjectionVolume → pure geometry; mirrors the teleport transform
│   ├── PlayerProjectionState → per-player fake-block slab + delta packets
│   ├── ImmersiveProjector → tick driver, chunk tickets, teardown, audio
│   └── EntityPassthrough → swept-path entity crossing (both directions)
├── PortalIgnitionMixin → portal creation
│   ├── detects item use matching portal config
│   ├── flood-fills to find valid frame
│   └── registers portal zone
├── EntityTickPortalMixin → vanilla portal override
│   └── redirects teleportation for custom portals
├── MobAttributeMixin → per-dimension difficulty (MobEntity.initialize TAIL)
│   └── DifficultyManager: mobMultiplier x depth factor as persistent
│       attribute modifiers (hostile mobs only; 0x = peaceful no-op);
│       player luck via JOIN/world-change events
├── WorldBorderManager → borders.player as the vanilla border per world
│   (SERVER_STARTED, overworld first — vanilla's border syncer trap)
├── DimensionTypeBuilder → "environment" block registers {ns}:{slug}_type
│   (invalid heights fall back to the base type, never a crash)
├── ServerChunkLoadingManagerMixin → per-dimension structure placement
│   └── rebuilds the world's StructurePlacementCalculator with UNREGISTERED
│       placement copies (DimensionStructures) — the global registry is never
│       mutated; custom placement types pass through unchanged
│       ├── NoiseGroupPlan → which groups are active, at what profile,
│       │   radial curve and exclusion (pure; config -> type defaults)
│       ├── NoisePoolBuilder → sorts sets into groups, biome-filters against
│       │   the dimension's biome source, weights by rarity + biome affinity
│       ├── NoiseStructurePlacement → one per active group (thin shell)
│       │   └── NoiseFieldIndex → all the placement maths, no Bootstrap
│       │       └── NoiseProfile → natural/dense/sparse/cluster constants
│       │           └── StructureNoise → own Perlin
│       ├── StructureGroupRegistry → group/rarity per set + type defaults,
│       │   both jar-baked; unknown sets infer to deco + spacing rarity
│       └── FixedStructurePlacement → structures.force positions; their
│           start attempts are performed by ChunkGeneratorForcedStartMixin
│           from the ForcedStartOverride registry (biome predicate
│           bypassed, other mods' start cancels outrun — see T25)
├── MinecraftServerAccessor → server internals access
├── StructurePlacementAccessor / StructurePlacementCalculatorInvoker
│   └── placement field access + the private calculator ctor (the public
│       Stream create() would zero the concentric-ring seed)
└── SimpleRegistryAccessor → registry unfreezing
```
