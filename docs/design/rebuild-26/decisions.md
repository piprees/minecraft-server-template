# Decisions

Each decision is binding on the rebuild until replaced by a new entry. Ids are never reused.

**RB-01 — Minecraft 26.3 is the floor, built for every 26.x from one codebase; the next forced bump is 27.x.**
Why: 26.3 is the latest stable release (checked 22 October 2026; 26.4 is at snapshot 5), and its worldgen is almost entirely data: the material-rule registry, `distance_to_point`, `lerp`, and the `aquifers` and `ore_veins` objects.
Rejected: targeting 26.4 — it is a snapshot; the version table absorbs it if it is out by launch ([risks.md](risks.md)).

**RB-02 — 100% server-side: no client mod is ever necessary to join or play.**
Why: an unmodded 26.3 Java client and Bedrock (through Geyser and Floodgate) must see the full game, so friends join with nothing to install. Client additions are allowed only when they are purely effects, shaders or UI tweaks (shaders, Distant Horizons rendering, minimaps, sound).
Rejected: content mods that need their client (furniture, backpacks, creatures and the rest); each is replaced server-side, rebuilt by us, or cut ([pack.md](pack.md)).

**RB-03 — The companion client mod is optional and effect-only; it is the only path for rendered previews.**
Why: it renders the far side of a portal live; nothing depends on it, and vanilla clients get a coloured portal, particles and a title instead ([portals.md](portals.md)).
Rejected: the fake-block projection painted into each player's world (the `immersive` package, 4,840 lines), chosen by the owner over keeping it as the vanilla-client fallback. The slab rule in [immersive-portals.md](../immersive-portals.md) governs the 1.21.1 mod only.

**RB-04 — Same repository, additive until one cutover.**
Why: the new mod lives in `mods/custom-dimensions-26/` beside the 1.21.1 mod and stays out of the release bundle until the switch; `.minecraft-version` selects the Minecraft version, mod list and image.
Rejected: a fresh repository or a fork — either would recreate the CI, release pipeline, dep-review, bundle model and troubleshooting record, and break consumer wiring (`deploy-reusable.yml@v5`, `.stack-version`).

**RB-05 — The switch is one release, `v6.0.0`.**
Why: one release moves the version to 26.3, puts the new mod into the bundle, deletes the 1.21.1 mod (with its jar-baked Terralith and Tectonic data) and removes the 1.21.1 mod list. Consumers move from `@v5` to `@v6`; its breaking-change note says: new world, Minecraft 26.3, Bedrock needs its UDP port.
A `release/v5` maintenance branch is cut from the last v5 tag only if a 1.21.1 fix is needed after the switch.

**RB-06 — The repository goes private.**
Why: consumer CI reads releases with an optional `STACK_READ_TOKEN` secret (fine-grained, Contents: Read on this repository). Order: merge to main; cut a v5 release so the `v5` tag carries the token support; give each consumer the secret and the new `deploy.yml` lines; then switch visibility and set Actions access to "user".
Constraints: consumer repositories must be private to call a private repository's workflows; private repositories on the free plan get 2,000 Actions minutes a month.

**RB-07 — We own all of the worldgen.**
Why: every dimension runs on vanilla's noise generator, which is fast and maintained by Mojang, with settings our mod writes; a renamed field becomes a version-table entry instead of a broken dependency.
Rejected: keeping Terralith, Tectonic, Incendium, Nullscape and the other worldgen mods as terrain owners; each register row names the part of our kit that takes its place.

**RB-08 — Firewall and bridges.**
Why: any mod may be installed, but not another worldgen owner; at start-up the mod refuses to start, listing what it found, if a foreign dimension, noise setting, biome source, worldgen mixin or biome library appears without a bridge.
Structure mods that build with vanilla blocks stay, through one bridge file each; our structure control decides where their structures go, and structure sets without a bridge are switched off ([worldgen.md](worldgen.md)).

**RB-09 — No loader overrides and no trials.**
Why: a mod is in when its author ships a 26.3 build and runs as released; dep-review adds a waiting mod when its build appears.
Rejected: forcing 1.21.1 or 26.2 builds onto 26.3.

**RB-10 — Fantasy for runtime levels, bundled inside our jar (LGPL).**
Why: Fantasy's persistent levels, `setSeed` and unload replace `CreateWorldsMixin`, `WorldLoaderMixin`, the registry accessor, the per-level seed mixin, and the chunk-ticket and unload mixins.
Fallback: if its lifecycle does not fit lazy activation (F0), we keep our own level manager using the same registry technique.

**RB-11 — One Polymer portal block implementing vanilla `Portal`, with a state per plane.**
Why: vanilla clients and Geyser see a Nether portal, an End portal or an End gateway; ignition uses Fabric's use-block event. That removes the portal-tick, destination and ignition mixins and makes horizontal portals ordinary rather than a special case.

**RB-12 — Twelve mixins or fewer, each with a game test; a new one needs a written reason.**
Why: the weekly canary then shows which mixin a snapshot broke, one failing test per mixin. The 1.21.1 mod has 38.

**RB-13 — Clean-room rule for Stardust Labs works.**
Nobody, human or agent, reads Terralith, Incendium, Nullscape, Structory or any other Stardust Labs files.
Why: their licence forbids copying parts into another mod and forbids using their work with AI systems. We describe what we want in our own words and build it from vanilla's functions.

**RB-14 — Terralith is dropped; the Terralith-derived files leave with the 1.21.1 mod.**
Why: its licence (RB-13) and no 26.3 build. The files go when the 1.21.1 mod is deleted in `v6.0.0` (P10); nothing changes in production before then.

**RB-15 — The old roller is replaced.**
Why: its facts engine mirrored worldgen and would need re-deriving for 26.3; `customdim render`, `roll` and `pick` sample the real generator instead, and scoring carries over ([roller.md](roller.md)).
Rejected: porting the facts engine, the web viewer and tryouts (with `roll`, 12,342 lines).

**RB-16 — Worldgen is shaped, not searched for.**
Why: zones put different worldgen in rings, areas and distances from spawn, built into the noise; rolling only picks the best seed for the rest.
Fallback: if the F0 spike shows the blend cannot look natural in data alone, zones use one density-function type of ours for every shape.

**RB-17 — The fourth base world is The Aviary (`adventure:the_aviary`).**
Why: the Nether's opposite, a bright world of floating islands and open sky, built from our kit.
Rejected: Paradise Lost — no 26.x build, and it needs its client.

**RB-18 — The compiler writes an ordinary datapack to disk.**
Why: JSON on disk is diffable, testable against golden files and readable when something looks wrong; a per-release version table maps field names ([architecture.md](architecture.md)).

**RB-19 — Base worlds load at start; the other 78 open lazily, one first-time generation at a time.**
Why: concurrent first-time generation wedges the main thread ([K6](../../../TROUBLESHOOTING.md#k6)).

**RB-20 — Worldgen fields are fingerprinted; a change on a live world is refused unless the dimension is being reset, and seeds are rolled only before first creation.**
Why: all worldgen is creation-time ([D2](../../../TROUBLESHOOTING.md#d2)).

**RB-21 — Bedrock through Geyser and Floodgate.**
Why: Bedrock players join with nothing to install; custom biomes show as their nearest vanilla biome.
Fallback: if Geyser's 26.3 release lags, launch for Java and Bedrock joins when Geyser does.

**RB-22 — Our own content comes after launch, through Polymer, in shippable slices (C1–C3).**
Why: biomes at launch use vanilla blocks; blocks, items and creatures follow, each slice started only if the last was worth it.

**RB-23 — The mod id stays `customdimensions` and is never published.**
Why: the Modrinth slug `custom-dimensions` belongs to someone else; delivery is through the release bundle.

**RB-24 — Toolchain: Java 25 (Temurin 25.0.5), Loom 1.19.3, Gradle 9.8.2, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3; one target until a second exists.**
Why: the latest stable versions on 22 October 2026 (Loader 0.20.x is not yet marked stable). Stonecutter adds nothing while 26.3 is the only target; it joins when 26.4 is released.

**RB-25 — Client mods in the optional pack are presentation only.**
Allowed: graphics (shaders, Distant Horizons, animations, entity textures, particles, the companion's portal previews), audio, UI and HUD (maps, tooltips, recipe views) and voice chat. Never allowed: anything the server relies on, or anything that changes rules, items, blocks or mobs. Textures for our Polymer content arrive in the server resource pack, which is not a mod.
Why: the server-side philosophy (RB-02) holds when every client mod is removable without changing the game.

**RB-26 — Polymer is a launch dependency.**
Why: the portal block (RB-11) is a Polymer block. Dependencies at launch are Fabric API, Fantasy and Polymer.

**RB-27 — Circular and ring zones compile to vanilla density functions; no density-function type of ours.**
Why: the F0 spike built a ring from `gradient` (x and z), `square`, `sqrt`, two clamped ramps and `lerp`, and it changed terrain exactly within the ring and its blend ([spike/f0-ring-zone.png](spike/f0-ring-zone.png)). A zone costs about 28% more per sampled column. Biomes don't follow a terrain zone, so a zone also lerps the router's climate functions or uses our biome source; it lerps `chunk_surface_level` and `depth` so materials and aquifers agree with the new terrain.
Rejected: a custom density-function type for every shape, kept only for shapes data can't express (polygons).

**RB-28 — Rendering and rolling run off the server thread.**
Why: a 160,000-column render took 59–76 s and a server-thread task over 60 s trips the watchdog, which kills the server. Sampling costs 370 µs per column without zones on one thread here.

**RB-29 — Every entity crosses our portals; only a player opens a lazy level.**
Why: the 26.3 portal block uses vanilla's `Portal` rules, so mobs, items and projectiles cross as they do through a Nether portal. A lazy destination opens only for a player; anything else waits until the level is already open, so a mob farm or hopper line beside a portal never keeps a level loaded.
Rejected: players only (the 1.21.1 zone traversal), which needs a mixin and breaks the vanilla expectation that items and mobs follow players through.

**RB-30 — A win is a vanilla advancement the mod grants.**
Each entry in a definition's `wins` names a trigger (`enter`, `reach` a point within a radius, `kill` an entity or tag a number of times, `obtain` an item or tag, `survive` a number of ticks, or earn a vanilla `advancement`) and how it reads (`title`, `description`, `icon`, `frame`). The compiler writes it as `adventure:wins/<slug>/<id>` with an `impossible` criterion under one `adventure:wins/root` tab; the mod grants it when the trigger fires, then sends the `message` key from `config/messages.json` and, when `exit` is set, sends the player on by the `ExitTarget` grammar.
Why: a vanilla or Bedrock client shows the toast and the advancement tab with nothing installed, and the advancement is the once-per-player record, so wins need no store of their own.
Rejected: a scoreboard or a file of our own, which the client cannot show.

**RB-31 — A dimension can keep or drop what a player carries when they die there.**
`deaths.inventory` and `deaths.experience` are `keep` or `drop`; absent, the `keepInventory` game rule decides. Kept items and levels are taken before the drop and handed back on respawn, through Fabric's death and respawn events.
Why: the game rule is server-wide, and a dimension built as a trial or a gallery needs its own rule.
