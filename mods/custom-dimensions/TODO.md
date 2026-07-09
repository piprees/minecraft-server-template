Custom Dimensions Mod — Code Review

I read every source file, the build config, mixin config, fabric.mod.json, and all tests. I verified key class/method/field names against the Yarn 1.21.1+build.3 mappings JAR. Here are the findings, prioritised.

---

CRITICAL (won't compile)

C1. DustParticleEffect constructor takes (Vector3f, float), not (int, float)

- Files: PortalHelper.java:201, PortalHelper.java:217, PortalHelper.java:224
- The code passes new DustParticleEffect(color, 2.0f) where color is an int. The Yarn mappings confirm the constructor is <init>(Lorg/joml/Vector3f;F)V. This won't compile.
- Fix: convert the int to a Vector3f: new Vector3f(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f)

C2. ServerWorld constructor missing WorldGenerationProgressListener parameter

- File: DimensionManager.java:207-219
- The constructor in 1.21.1 takes 12 parameters: (MinecraftServer, Executor, LevelStorage.Session, ServerWorldProperties, RegistryKey, DimensionOptions, WorldGenerationProgressListener, boolean, long, List, boolean, RandomSequencesState). The code passes 11 args — it has false where WorldGenerationProgressListener should be (position 7), shifting all subsequent arguments.
- Fix: insert a progress listener as parameter 7. The vanilla server uses WorldGenerationProgressListener.EMPTY or a no-op implementation. Then the remaining params (false, worldSeed, List.of(), false, overworld.getRandomSequences()) align correctly with (debugWorld, seed, spawners, shouldTickTime, randomSequences).

C3. Undefined variable overworldGen

- File: DimensionManager.java:159
- In the single_biome case fallback path: yield new DimensionOptions(overworldOpts.dimensionTypeEntry(), overworldGen); — overworldGen is never declared.
- Fix: replace with overworldOpts.chunkGenerator().

C4. Entity.teleport called with wrong arity (8 args instead of 7)

- Files: EntityTickPortalMixin.java:89, ServerWorldMixin.java:135, ServerWorldMixin.java:145
- In 1.21.1, Entity.teleport is (ServerWorld, double, double, double, Set, float, float)boolean — 7 params. The code passes an extra true at the end (likely leftover from 1.21.0 which had a resetCamera boolean). No overload matches 8 parameters.
- Fix: remove the trailing true from all three call sites.

---

HIGH (runtime failures)

H1. NPE from context.getPlayer() in PortalIgnitionMixin

- File: PortalIgnitionMixin.java:113 and PortalIgnitionMixin.java:159
- ItemUsageContext.getPlayer() can return null (dispensers, command blocks). The code calls .isCreative() on the result without a null check.
- Fix: add if (context.getPlayer() != null && !context.getPlayer().isCreative()) before decrementing.

H2. Dimension unloading doesn't close world resources

- File: DimensionManager.java:294-306
- After world.save(null, false, false), the code only does worlds.remove(key). This leaks the chunk manager, entity storage, block tick schedulers, and file handles. A proper unload must also call world.getChunkManager().close() and ideally world.getEntityManager().close().
- Without this, repeated create/unload cycles will exhaust file descriptors and cause OOM.

H3. Portal zone iteration is unsafe in ServerWorldMixin.onTick

- File: ServerWorldMixin.java:47-58
- PortalHelper.getSourceZones(worldKey) returns the backing ArrayList directly (not a copy). The code calls PortalHelper.removeZone(zone) which modifies this list, then resets the iterator with zoneIter = zones.iterator(). But removeZone calls zones.remove(zone) on the same list, invalidating any active iterator. On certain liststates this can throw ConcurrentModificationException.
- Fix: either iterate over a copy (new ArrayList<>(zones)) or collect removals into a separate list and remove after iteration.

---

MEDIUM (bugs under specific conditions)

M1. Custom seed doesn't affect chunk generation

- File: DimensionManager.java:205
- worldSeed is passed to the ServerWorld constructor, but the ChunkGenerator inside DimensionOptions was already constructed with the overworld's seed. The ServerWorld seed parameter is used for random sequences and weather, not terrain generation. To actually change the world seed for terrain, you'd need to create a newChunkGenerator with the custom seed.
- Impact: the seed field in DimensionDefinition has no effect on terrain. Players will see identical terrain regardless of the seed they specify.

M2. FlatChunkGeneratorConfig constructor may receive wrong list type

- File: DimensionManager.java:134-139
- The superflat case passes List<FlatChunkGeneratorLayer> as the third argument to the 3-arg constructor. Yarn names this parameter "features" (List<PlacedFeature>).Due to type erasure this compiles, but if the constructor stores it as features rather than layers, the flat world won't generate the bedrock/dirt/grass layers. Use FlatChunkGeneratorConfig.with(layers, Optional.empty(), biome) instead.

M3. Portal command getOrCreateDimension passes identifier instead of name

- File: PortalCommand.java:85
- DimensionManager.getInstance().getOrCreateDimension(target) where target is a full identifier string like "minecraft:the_nether". But getOrCreateDimension looks up by the name field of DimensionDefinition (e.g., "my_world"), not by full identifier. The call will always return null for custom dimensions.

M4. Config is saved every tick unnecessarily

- File: ServerWorldMixin.java:31-34, ServerWorldMixin.java:41-43
- onSave marks the config dirty on every world save. Then onTick checks isDirty() and saves the config. Since vanilla calls ServerWorld.save() during autosaves, the config JSON is rewritten to disk every autosave cycle plus on the next tick after any world save. This creates unnecessary I/O.
- Fix: only mark dirty when config data actually changes (which is already done by the add/remove methods).

M5. Horizontal portals won't display correctly

- File: PortalHelper.java:241
- NetherPortalBlock.AXIS only supports X and Z values. The code handles axis == Y by falling back to visualAxis = X, so horizontal portals display as vertical X-axis portals. The visual won't match player expectation of a flat floor portal.

M6. MobSpawnMixin conflict risk with Lithium

- File: MobSpawnMixin.java:18
- Lithium replaces SpawnHelper.spawnEntitiesInChunk with optimized implementations. The @Inject(at = @At("HEAD"), cancellable = true) may silently stop working ifLithium overwrites the method, meaning peaceful dimensions could still spawn hostiles.

---

LOW (style/improvements)

L1. Fabric Loom 1.9-SNAPSHOT is unstable

- File: build.gradle:2
- Pin to a stable release like 1.8.12 or 1.7.4. SNAPSHOT versions can break builds without warning.

L2. Missing fabric-api in depends

- File: fabric.mod.json:19-23
- The mod uses DedicatedServerModInitializer from Fabric API but doesn't declare fabric or fabric-api as a dependency. Should add "fabric-api": "\*" to depends.

L3. amplified and large_biomes types have no switch case

- File: DimensionManager.java:126-174
- These are listed in VALID_TYPES (DimensionCommand.java:22) but fall through to default in createDimensionOptions, silently creating a plain overworld copy. Either add explicit cases or remove them from the valid types set.

L4. Exception swallowed silently in ServerWorldMixin teleport

- File: ServerWorldMixin.java:147
- catch (Exception ignored) {} hides all portal teleportation errors. At minimum, log the exception.

L5. Command registration timing

- File: WorldLoaderMixin.java:27-29
- Commands are registered in afterCreateWorlds rather than via CommandRegistrationCallback. This works but is fragile across Fabric API versions.

L6. ReadWriteLock overhead is unnecessary

- File: MultiverseConfig.java:25
- All callers run on the server thread. The lock adds overhead without providing real thread safety (the IO pool in StorageHelper doesn't access the config).

---

Mixin Safety Summary

- All 8 mixin classes are listed in customdimensions.mixins.json — correct
- defaultRequire: 1 is set — correct (fail-fast)
- Accessor interfaces (MinecraftServerAccessor, SimpleRegistryAccessor, NoiseChunkGeneratorAccessor) are properly defined
- Field names verified against Yarn 1.21.1+build.3: worlds, workerExecutor, session, saveProperties, frozen, settings — all correct
- Method targets verified: tickPortalTeleportation()V, createWorlds(WorldGenerationProgressListener)V, shutdown()V, save(ProgressListener,Z,Z)V, tick(BooleanSupplier)V, useOnBlock(ItemUsageContext)ActionResult, spawnEntitiesInChunk(SpawnGroup,ServerWorld,WorldChunk,Checker,Runner)V — all correct

Mod Compatibility Notes

- Lithium: MEDIUM risk — may overwrite SpawnHelper.spawnEntitiesInChunk, breaking MobSpawnMixin
- C2ME/ServerCore: LOW risk — ServerWorldMixin.onTick runs heavy portal logic every tick in every dimension, but the @Inject at HEAD is compatible
- Immersive Portals: HIGH conflict — both mods cancel Entity.tickPortalTeleportation
- Incendium/Terralith/Nullscape: LOW risk — these are worldgen datapacks, not mixins; they won't conflict with the dimension registration code

Build Configuration

- Fabric Loom 1.9-SNAPSHOT: unstable, should pin to stable
- Fabric Loader 0.16.14: valid
- Fabric API 0.115.0+1.21.1: valid
- Yarn 1.21.1+build.3: valid
- Java 21: correct for 1.21.1
- JUnit 5.11.4 config: correct
- RefMap will be generated correctly by Loom
- The build will NOT succeed due to the 4 critical compilation errors above

---

Fix these right away:

C1. DustParticleEffect constructor takes (Vector3f, float), not (int, float) C2. ServerWorld constructor missing WorldGenerationProgressListener parameter C3. Undefined variable overworldGen C4. Entity.teleport called with wrong arity (8 args instead of 7) H1. NPE from context.getPlayer() in PortalIgnitionMixin H2. Dimension unloading doesn't close world resources H3. Portal zone iteration is unsafe in ServerWorldMixin.onTick M1. Custom seed doesn't affect chunk generation M2. FlatChunkGeneratorConfig constructor may receive wrong list type M3. Portal command getOrCreateDimension passes identifier instead of name M4. Config is saved every tick unnecessarily M6. MobSpawnMixin conflict risk with Lithium L1. Fabric Loom 1.9-SNAPSHOT is unstable L2. Missing fabric-api in depends L3. amplified and large_biomes types have no switch case L4. Exception swallowed silently in ServerWorldMixin teleport L5. Command registration timing L6. ReadWriteLock overhead is unnecessary

For this one, I know the end portal is a horizontal portal; we may need to implement horizontal portals with that instead of the nether portal:

M5. Horizontal portals won't display correctly

---

Once you are done, run the tests and verify they pass, fix any issues, and compile the mod jar.
