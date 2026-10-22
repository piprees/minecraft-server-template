# Handoff: the 26.3 rebuild

Temporary working log for the custom-dimensions rebuild. One line per finding,
decision or error, newest last. Delete this file once the rebuild ships; the
lasting record is `docs/design/rebuild-26/` and `TROUBLESHOOTING.md`.

## State

- Branch: `claude/minecraft-dependency-automation-n1w79q` (PR #29). `main` has
  not moved since the branch was cut.
- Blocked on the owner: merging PR #29 and the Dependabot PRs into `main`
  (refused by the session's permission rules), then the private switch in the
  order recorded in the log.
- Rebuild: F0 and F1 done; F2 (carry over the core) in progress, then F3
  (schema v2 and conversion of the 82 definitions).
- Plan of record: `docs/design/rebuild-26/README.md`.

## Log

- 10-22 — Latest stable Minecraft is 26.3 (26.4 is at snapshot 5); the rebuild targets 26.3.
- 10-22 — Open PRs: #29 (this branch, green), Dependabot #27 #30–#34 #38–#40 (green, one-line bumps), #26 mod-updates/auto (56 bumps for 1.21.1 with no CI run; includes worldgen mods, which the versioning contract ships only in a major).
- 10-22 — Merge of #29 into `main` refused by the session's permission classifier ("merge without review"). Work continues on the branch; the owner merges.
- 10-22 — Going private breaks consumer deploys as built: the reusable workflow resolves releases with the caller's `github.token`, and the server pulls the bundle anonymously. elfydd is private, so it can still call the workflow once Actions access is set to "user". Fix: an optional `STACK_READ_TOKEN` secret.
- 10-22 — Going private also moves Actions onto the free plan's 2,000 minutes a month; the smoke-test boots are the biggest consumer.
- 10-22 — Quirk (this container): `python3` is 3.11, but `scripts/score-biome-table.py` uses 3.12 f-string syntax and CI runs 3.13. Run quick checks with 3.13 first on PATH; then 421 tests pass. No Docker daemon here, so the compose-runtime checks can't run.
- 10-22 — Added optional `STACK_READ_TOKEN` (deploy-reusable resolve + server pull over stdin; consumer deploy.yml and update.yml; credentials reference; consumer README). Simulated the pull step with and without a token: both scripts generate and parse.
- 10-22 — Decision: don't switch to private yet. elfydd calls `deploy-reusable.yml@v5`, so the token support reaches it only after (1) PR #29 merges, (2) a v5 release moves the `v5` tag, (3) elfydd gets a `STACK_READ_TOKEN` repo secret and the new `deploy.yml` lines (workflow files are a manual sync). Then set the repo private and Actions access to "user" (`PUT repos/piprees/minecraft-server-template/actions/permissions/access` with `access_level=user`).
- 10-22 — Toolchain for 26.3 (looked up live): Temurin JDK 25.0.5+8, Fabric Loader 0.19.5 (latest stable; 0.20.x not marked stable), Fabric API 0.164.0+26.3, Loom 1.19.3 (`net.fabricmc.fabric-loom`, no remapping), Gradle 9.8.2, Fantasy 0.8.4+26.3, Polymer 0.18.4+26.3. Data pack format 121.0, resource 97.1, world version 5023.
- 10-22 — Quirk (this container): Maven Central answers 429 through the proxy. `~/.gradle/init.d/central-mirror.gradle` swaps in Google's Central mirror; container-only, not committed.
- 10-22 — Finding: 26.3 density functions compile to samplers (`DensityFunction.compileSampler`, `DensitySampler`, `SamplerContext`). A density-function type of ours would implement that API, not the old `compute`. `RandomState.sampleBlockValueUncached(df, x, y, z)` samples any function directly; the climate sampler comes from `createClimateSampler(SamplerContext)`; biomes from `BiomeSource.createUncachedResolver(randomState)`.
- 10-22 — Finding: 26.3 codec keys. `lerp`: alpha, first, second. `gradient`: axis, tiling (clamp_to_edge | repeat | mirrored_repeat), from_coordinate, to_coordinate, from_value, to_value. `distance_to_point`: point, metric (euclidean | euclidean_squared | manhattan | chebyshev). Unary functions (sqrt, square, …) take `input`. The noise router holds chunk_surface_level, continents, depth, erosion, final_density, ridges, temperature, vegetation; aquifers moved to a top-level `aquifers` object.
- 10-22 — F0 zone spike PASSED: a ring zone in pure vanilla data (x and z gradients → sqrt → two clamped ramps → lerp between overworld and amplified final density) changes terrain exactly from 480 to 1,320 blocks (ring 600–1,200, blend 120) and nowhere else. Render: `docs/design/rebuild-26/spike/f0-ring-zone.png`. No custom density-function type is needed for circles and rings.
- 10-22 — Finding: generator-only sampling costs 370 µs per column (height + biome, one thread, this 4-core container), 474 µs with the ring zone (+28%). A 2,000-block radius at 32-block spacing is ~15,600 columns, about 6–7 s a seed per thread.
- 10-22 — Finding: biomes don't follow a terrain zone (they come from climate). Zone biomes need either our biome source or the zone lerping the router's temperature/vegetation functions, which keeps them in data. `chunk_surface_level` and `depth` must be lerped too, or materials and aquifers keep reading vanilla's surface estimate inside the zone.
- 10-22 — Quirk: a render on the server thread longer than 60 s trips the watchdog and kills the server. Rolling and rendering must run off the main thread.
- 10-22 — Next for zones: edge jitter (noise added to the distance) so borders don't read as perfect circles.
- 10-22 — Conflict found: docs/design/immersive-portals.md requires the vanilla-client block slab to keep working; the rebuild plan (RB-03) cuts it. Recorded in rebuild-26/risks.md for the owner; must be settled before M6.
- 10-22 — F0 Fantasy spike PASSED: `getOrOpenPersistentLevel` with the overworld's generator opened a level in 78 ms, generated chunk 0,0 (1.5 s cold), saved it, and on the next boot reopened it from `dimensions/spike/lazy/` with the same terrain (surface y=67 both boots). API on 26.3: `RuntimeLevelConfig` (setDimensionType, setGenerator, setSeed, setDifficulty, setGameRule, clocks), `RuntimeLevelHandle` (asLevel, unload, delete, setTickWhenEmpty).
- 10-22 — Quirk: Fantasy `unload()` is not immediate. After a fresh generation the level was still in `server.levelKeys()` 40 ticks later; after a reopen it was gone. The level manager polls for the unload and never assumes it happened.
- 10-22 — F0 portal spike PASSED (server half): one Polymer block with an `axis` property, implementing vanilla `Portal` and calling `entity.setAsInsidePortal` from `entityInside`, maps to `nether_portal[axis=x|z]` upright and `end_portal` flat. Items dropped into an upright and a flat portal both left with `CHANGED_DIMENSION` and arrived in the Fantasy level at y=63. No mixin involved. Polymer 0.18.4+26.3 needs polymer-core, -common, -networking and -registry-sync-manipulator jars (Loom `include` is not transitive).
- 10-22 — Not yet verified: what an unmodded 26.3 client renders, and that it isn't refused at login. Needs a real client: the e2e rig on the owner's machine, or P3's join gate. The block's tint needs the Polymer resource pack.
- 10-22 — Quirk: 26.x has no always-loaded spawn chunks. An entity at 0,0 after start-up was saved to disk (`UNLOADED_TO_CHUNK`) and never ticked. Anything that must keep ticking needs an explicit ticket (`setChunkForced` or a region ticket).
- 10-22 — `TeleportTransition.PostTeleportTransition` combines with `then(...)`; `PLACE_PORTAL_TICKET` keeps the arrival chunk loaded.
- 10-22 — F1 skeleton: `mods/custom-dimensions-26/` with `core` (zone model and maths), `compiler` (zone mask as 26.3 density-function JSON, vanilla types only), `server` (Fabric mod `customdimensions`, start-up self-test). 11 unit tests pass; the self-test boots 26.3 and vanilla's sampler matches core to 5e-7 over 2,000 points. core and compiler nest in the jar.
- 10-22 — CI: `mod26.yml` (build, tests, self-test boot on 26.3) and `mod26-canary.yml` (weekly build against the newest snapshot; opens an issue on failure). Locally the canary resolved 26.4-snapshot-5 + Fabric API 0.164.1+26.4 and built green.
- 10-22 — Bug fixed: `platform_updates.py` required Yarn and fabric_version pins in every `mods/*/gradle.properties`, so the rebuild's file broke its scan. The rebuild is `OUT_OF_SCOPE` (bumped by hand until the switch); test added; 422 tests pass.
- 10-22 — Closed #26 (superseded; worldgen bumps belong in a major; the new pipeline re-proposes them after #29 merges). Updated #29's title and description to cover everything on the branch.
- 10-22 — CI green on f5896327: "Mod build (26.3)" (build, tests, self-test boot), Lint, Mod build. Smoke Test was still running at the time of writing.
- 10-22 — Production load check: the jar on a plain Fabric 26.3 server (installer 1.1.2, Loader 0.19.5, Fabric API 0.164.0+26.3) loads with core and compiler as nested mods (`com_customdimensions_core`, `com_customdimensions_compiler`), and the self-test passes.
- 10-22 — Owner decisions: previews are companion-only (RB-03 stands; immersive-portals.md's slab rule is scoped to the 1.21.1 mod). Next work: F2 then F3.
- 10-22 — F2 handed to a subagent: carry config, score, SeedFacts/Measured/Json, NoiseFieldIndex, StructureNoise, PortalShape, AuraPolicy and FrameMatcher's accept forms into `core` with Minecraft types replaced; port their tests; add a test loading all 82 shipped configs. Progress lines in `/tmp/claude-0/f2-progress.log`.
