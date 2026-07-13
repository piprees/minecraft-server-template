# Mod-owned dimension lifecycle

The mod should own the full dimension + portal lifecycle at boot, reading
a rich config that lives in the repo — not relying on a deploy-time
shell-script pipeline to create dimensions via RCON.

## Current state (the problem)

```
dimensions.txt (repo, human-editable, limited schema)
  → setup-dimensions.sh (deploy-time, reads dimensions.txt, fires RCON commands)
    → /dimension create + /portal link (RCON, writes to multiverse_config.json)
      → multiverse_config.json (server-side only, runtime state, not in repo)
        → mod reads at boot
```

- `dimensions.txt` is the intended source of truth but has a limited
  schema (no colour, particle, sounds, cooldown).
- `setup-dimensions.sh` is a middleman that translates it into RCON
  commands. It runs on every deploy but skips existing dimensions, so
  config changes (e.g. a new colour) never flow through after first
  creation.
- `multiverse_config.json` has the rich schema (`PortalDefinition` with
  colour, particle, sounds, scale, cooldown) but it's server-side
  runtime state, not in the repo — so it's not version-controlled,
  can't be reviewed in PRs, and drifts from intent.
- Portal properties like colour and particle are already supported by
  the mod (v1.0.6) but there's no config path to set them per-dimension
  without manually editing `multiverse_config.json` on the server.

## Target state

```
config/dimensions.json (repo, rich schema, version-controlled)
  → mod reads at boot
    → creates missing ServerWorlds
    → portal definitions in memory (PortalDefinition)
    → players build frames, ignite, mod matches and applies all properties
```

### What the config should carry per dimension

```json
{
  "dimensions": [
    {
      "name": "the_blossom_gardens",
      "type": "multi_biome",
      "scale": 1,
      "seed": "server",
      "biomes": ["minecraft:cherry_grove", "minecraft:meadow"],
      "peaceful": true,
      "portal": {
        "frameBlock": "minecraft:cherry_planks",
        "igniterItem": "minecraft:cherry_sapling",
        "color": "FFB7C5",
        "particleType": "minecraft:cherry_leaves",
        "lightLevel": 11,
        "cooldown": 40,
        "igniteSound": "block.portal.trigger",
        "enterSound": "block.portal.travel",
        "exitSound": "block.portal.travel"
      }
    }
  ],
  "defaults": {
    "color": "8844FF",
    "lightLevel": 11,
    "cooldown": 40,
    "igniteSound": "block.portal.trigger",
    "enterSound": "block.portal.travel",
    "exitSound": "block.portal.travel"
  },
  "idleUnloadMinutes": 5
}
```

### What changes

| Component | Change |
|---|---|
| **Config format** | Replace `dimensions.txt` (pipe-delimited) with `dimensions.json` (or extend `multiverse_config.json` to be repo-sourced). JSON is already what the mod reads — just make it the input too. |
| **Mod boot** | `DimensionManager.onServerStart()` reads the config, registers dimensions that don't have a `ServerWorld` yet (currently only done via RCON `/dimension create`). The `registerDimensions()` + `getOrCreateDimension()` path already exists — it just needs to be driven by the config file instead of RCON. |
| **Portal definitions** | Loaded from the same config at boot. Currently `PortalDefinition` objects are created by the `/portal link` command and stored in `multiverse_config.json`. Instead: the mod builds them from the config's `portal` section per dimension. |
| **`setup-dimensions.sh`** | Removed entirely. The mod does everything it did. |
| **`deploy.sh`** | Stops calling `setup-dimensions.sh`. The config file is seeded by the defaults-seed container (same as every other mod config). |
| **`multiverse_config.json`** | Repo-owned, deploy-seeded, read-only at runtime. The mod never writes to it. |
| **`/dimension create` + `/portal link` + delete commands** | Removed. All dimensions and portals come from the config. Orphaned `adventure:` worlds are cleaned up at boot. |

### Phase 0: branded namespace

The dimension namespace is hardcoded as `adventure` in
`DimensionDefinition.NAMESPACE`. Consumer servers should use their
brand slug (e.g. `elfydd:the_blossom_gardens` instead of
`adventure:the_blossom_gardens`).

Changes:
- Make `NAMESPACE` configurable — read from `multiverse_config.json`
  (new `"namespace"` field, default `"adventure"` for backwards compat)
  or from `.env` via `BRAND_SLUG`.
- One-time production migration:
  - Rename `data/world/dimensions/adventure/` →
    `data/world/dimensions/<brand>/`
  - Update `level.dat` dimension registry entries (NBT edit)
  - Find-replace `adventure:` → `<brand>:` in `portal_links.json`,
    `multiverse_config.json`, BlueMap map confs
    (`config/bluemap/maps/*.conf` `dimension:` field),
    `configurable-difficulty.json5`, and `config/dimensions.txt`
- Guard: the namespace is used by `MobSpawnMixin`,
  `PeacefulDimensionSpawnMixin`, `unfreezeBlueMapOnFirstVisit` (deleted
  in v1.0.5), and all dimension creation paths — all reference
  `DimensionDefinition.NAMESPACE`, so changing the constant is
  sufficient for the mod.

Do this BEFORE the lifecycle refactor so the config file ships with the
branded namespace from the start.

### Migration

1. Generate `dimensions.json` from the current `dimensions.txt` + live
   `multiverse_config.json` (one-off script, keeps all existing portal
   colours/particles).
2. Mod reads `dimensions.json` at boot, creates missing worlds, loads
   portal definitions.
3. Remove `setup-dimensions.sh` from the bundle and `deploy.sh`.
4. `dimensions.txt` can remain as a deprecated alias or be removed.

### Config ownership and mutation

`config/multiverse_config.json` (repo) is the **sole** source of truth.
The mod reads it at boot and **never writes to it**. No runtime overlay
file, no merge, no drift.

- **Boot-time reconciliation**: the `adventure:` namespace is exclusively
  ours. At boot, any `adventure:` world not in the config is orphaned
  — unload and clean it up (same idle-unload path). Any config entry
  without a `ServerWorld` gets created. Config is always authoritative.
- **`/dimension create`, `/portal link`, `/dimension delete`,
  `/portal delete`** — all removed. Dimensions and portals come
  exclusively from the repo config. To add a dimension: edit the
  config, commit, deploy. To remove: delete from config, deploy; the
  boot reconciliation cleans up the orphaned world.
- **`save()` removed** — the dirty-flag mechanism is deleted. The mod
  never writes `multiverse_config.json`. It's repo-owned,
  deploy-seeded, immutable at runtime.
- **`portal_links.json`** (return-trip portal-block state) is unaffected
  — that's genuinely runtime state tied to placed blocks, not config.

### Considerations

- The config file needs to be seeded into `data/config/` by the
  defaults-seed container, same path as every other mod config. Consumer
  overlay can override individual dimensions.
- Per-dimension seeds (`ServerWorldSeedMixin`) already read from
  `DimensionDefinition` — this is already wired.
- The `pendingWorldLoads` queue (END_SERVER_TICK) must be used for
  boot-time creation too — same reason as the command path (sync
  creation during server start risks the same deadlock).
- Existing `portal_links.json` (return-trip targets) is unaffected —
  that's runtime portal-block state, not config.

### Phase 2: Theme every dimension's portal

Once the config format supports it, use the biome/group/theme of each
dimension to assign appropriate portal visuals. A subagent prompt
exists in the scratchpad (`portal-theming-prompt.md`) with:

- Curated particle type list (cherry_leaves, soul_fire_flame, ash, etc.)
- Colour palette mapped to biome themes
- Rules: use custom particles only where they genuinely fit, default to
  coloured dust for most portals

The subagent edits `dimensions.json` directly — one file, all 74+
dimensions, version-controlled, reviewable in a PR.

### Scope

**Phase 1** (lifecycle refactor): medium — mod config loading, boot-time
dimension creation, portal definition sourcing. The portal system itself
(ignition, traversal, particles, sounds) is untouched. One focused
session.

**Phase 2** (theming): small — purely config edits once the format
supports it. Subagent can do it in one pass.
