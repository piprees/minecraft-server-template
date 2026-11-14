#!/usr/bin/env python3
"""edits.py - The exact-text edits of the 26.3 cutover, applied by apply.sh.

Each edit names a file, a text that must occur in it, and its replacement.
An edit applies when its old text occurs exactly once (or at least once with
all=True); it counts as applied when the old text is absent and the new text
present; anything else is drift, and the step stops with the file and the
first line of the old text, so a file that changed since this plan was
written is reviewed rather than overwritten.

Usage:
  edits.py <repo> <step> [--dry-run]   apply (or list) one step's edits
  edits.py --steps                     list the steps
  edits.py --sha <repo> <path>         sha256 of the path once every edit applies
Exit 0 when every edit of the step applied or was already applied; 1 on drift.
"""

import sys
from pathlib import Path

E = []  # (step, path, old, new, all)


def edit(step, path, old, new, all=False):
    E.append((step, path, old, new, all))


# --- version: compose, workflow and consumer fallbacks, the version table ----
edit("version", "docker-compose.yml",
     "itzg/minecraft-server:${MC_IMAGE_TAG:-2026.7.0-java21}",
     "itzg/minecraft-server:${MC_IMAGE_TAG:-2026.10.0-java25}")
edit("version", "docker-compose.yml",
     "${MC_VERSION:-1.21.1}", "${MC_VERSION:-26.3}", all=True)
edit("version", "docker-compose.yml",
     "      # The selected version's loader from minecraft-versions.tsv (export_mc_version); the fallback is the 1.21.1 row's.\n",
     "      # The selected version's loader from minecraft-versions.tsv (export_mc_version); the fallback is the 26.3 row's.\n")
edit("version", "docker-compose.yml",
     "${FABRIC_LOADER_VERSION:-0.19.5}", "${FABRIC_LOADER_VERSION:-0.20.1}")
edit("version", "docker-compose.yml",
     "      # Geyser (26.3 list; nothing listens on 1.21.1): NetherNet signaling over\n",
     "      # Geyser: NetherNet signaling over\n")
edit("version", ".github/workflows/deploy-reusable.yml",
     'emit MC_VERSION "${V_MC_VERSION:-1.21.1}"', 'emit MC_VERSION "${V_MC_VERSION:-26.3}"')
edit("version", ".github/workflows/deploy-reusable.yml",
     "|| echo \\${MC_VERSION:-1.21.1})", "|| echo \\${MC_VERSION:-26.3})")
# The scaffold's deploy calls the reusable workflow of the release's major.
edit("version", "examples/consumer/.github/workflows/deploy.yml",
     "deploy-reusable.yml@v5\n", "deploy-reusable.yml@v6\n")
edit("version", "examples/consumer/dev",
     'echo "${MC_VERSION:-1.21.1}")', 'echo "${MC_VERSION:-26.3}")')
edit("version", "scripts/minecraft-versions.tsv",
     "#   image     the itzg/minecraft-server tag; docker-compose.yml's MC_IMAGE_TAG\n"
     "#             fallback carries the 1.21.1 row's tag (test_minecraft_version.py\n"
     "#             holds them equal)\n"
     "#   loader    the server's Fabric loader, exported as FABRIC_LOADER_VERSION;\n"
     "#             docker-compose.yml's fallback carries the 1.21.1 row's loader\n",
     "#   image     the itzg/minecraft-server tag; docker-compose.yml's MC_IMAGE_TAG\n"
     "#             fallback carries the 26.3 row's tag (test_minecraft_version.py\n"
     "#             holds them equal)\n"
     "#   loader    the server's Fabric loader, exported as FABRIC_LOADER_VERSION;\n"
     "#             docker-compose.yml's fallback carries the 26.3 row's loader\n")
edit("version", "scripts/minecraft-versions.tsv",
     "1.21.1     21    2026.7.0-java21    0.19.5  1.21.1,1.21\n", "")

# --- lists: the 26.3 mod list's header once it is the only list --------------
edit("lists", "config/modrinth-mods-26.3.txt",
     "# Re-pin: ./scripts/pin-mod-versions.sh --version 26.3\n",
     "# Re-pin: ./scripts/pin-mod-versions.sh\n")

# --- seed: the defaults-seed image's COPY lines ------------------------------
edit("seed", "docker/defaults-seed/Dockerfile",
     "COPY config/tectonic.json        /defaults/config/tectonic.json\n", "")
edit("seed", "docker/defaults-seed/Dockerfile",
     "COPY config/starterkit.json5     /defaults/config/starterkit.json5\n", "")
edit("seed", "docker/defaults-seed/Dockerfile",
     "# v4 per-dimension configs (custom-dimensions mod): settings.json + dimensions/\n"
     "# extractors/ (authoring-only worldgen extraction data, ~2.6MB) is deliberately\n"
     "# excluded — the mod never reads it, and deploy.sh/dev-up.sh already skip it\n"
     "# when syncing data/config, so it would only ever bloat this image.\n"
     "COPY config/custom-dimensions/settings.json      /defaults/config/custom-dimensions/settings.json\n"
     "COPY config/custom-dimensions/display-names.json /defaults/config/custom-dimensions/display-names.json\n"
     "COPY config/custom-dimensions/dimensions/        /defaults/config/custom-dimensions/dimensions/\n"
     "# Noise-placement data. The MOD reads its own jar-baked copies of both, so\n"
     "# these are not the runtime source — they ship because the seed roller reads\n"
     "# them, and in consumer mode the roller resolves config from\n"
     "# data/config/custom-dimensions/ (roll-all.sh) rather than a repo checkout.\n"
     "COPY config/custom-dimensions/structure-groups.json         /defaults/config/custom-dimensions/structure-groups.json\n"
     "COPY config/custom-dimensions/structure-type-defaults.json  /defaults/config/custom-dimensions/structure-type-defaults.json\n"
     "# Measured climate windows. The mod reads these to map declared cells into the\n"
     "# range a dimension's router actually samples; without the file every dimension\n"
     "# keeps its cells as authored, across the whole climate space.\n"
     "COPY config/custom-dimensions/climate-axes.json  /defaults/config/custom-dimensions/climate-axes.json\n",
     "# custom-dimensions: the v2 definitions with settings.json, and the reviewed\n"
     "# structure bridges the worldgen firewall adopts.\n"
     "COPY config/custom-dimensions/dimensions/ /defaults/config/custom-dimensions/dimensions/\n"
     "COPY config/custom-dimensions/bridges/    /defaults/config/custom-dimensions/bridges/\n")
edit("seed", "docker/defaults-seed/Dockerfile",
     "COPY config/firespreadtweaks/          /defaults/config/firespreadtweaks/\n"
     "COPY config/healingcampfire/           /defaults/config/healingcampfire/\n"
     "COPY config/youritemsaresafe/          /defaults/config/youritemsaresafe/\n"
     "COPY config/doubledoors/               /defaults/config/doubledoors/\n"
     "COPY config/nutritiousmilk/            /defaults/config/nutritiousmilk/\n"
     "COPY config/collective/                /defaults/config/collective/\n",
     "# Every Serilum mod reads config/serilum/<modid>.json through Collective.\n"
     "COPY config/serilum/                   /defaults/config/serilum/\n")
edit("seed", "docker/defaults-seed/Dockerfile",
     "COPY config/lootr/                     /defaults/config/lootr/\n", "")
edit("seed", "docker/defaults-seed/Dockerfile",
     "COPY config/nametagtweaks/             /defaults/config/nametagtweaks/\n"
     "COPY config/betterdays/                /defaults/config/betterdays/\n", "")
edit("seed", "docker/defaults-seed/Dockerfile",
     "COPY config/boring_default_game_rules/ /defaults/config/boring_default_game_rules/\n", "")
edit("seed", "docker/defaults-seed/Dockerfile",
     "COPY config/bountiful/                 /defaults/config/bountiful/\n",
     "COPY config/Geyser-Fabric/             /defaults/config/Geyser-Fabric/\n"
     "COPY config/floodgate/                 /defaults/config/floodgate/\n")
edit("seed", "docker/defaults-seed/Dockerfile",
     "# /defaults/config/<path>. Configs of mods only that version's list carries\n"
     "# (floodgate/, Geyser-Fabric/ on 26.3) live there alone.\n",
     "# /defaults/config/<path>. Configs of mods only that version's list carries\n"
     "# live there alone.\n")

# --- deploy: config sync, game-rule note, bundle exclude --------------------
edit("deploy", "scripts/deploy.sh",
     "#   - Game rules here must match config/boring_default_game_rules/config.json.\n",
     "#   - Game rules live here alone; scripts/game-rules.tsv maps each to the\n"
     "#     version's id (mc_game_rule).\n")
edit("deploy", "scripts/deploy.sh",
     "  local_data_cfg=\"$SERVER_DIR/data/config\"\n"
     "  mkdir -p \"$local_data_cfg\"\n"
     "  cd \"$BUNDLE_CONFIG\"\n"
     "  platform_config_files | while IFS= read -r f; do\n",
     "  local_data_cfg=\"$SERVER_DIR/data/config\"\n"
     "  mkdir -p \"$local_data_cfg\"\n"
     "  # Bridges are the bundle's set exactly: one deleted from the platform\n"
     "  # must stop adopting, and the copy below only adds and overwrites.\n"
     "  rm -rf \"$local_data_cfg/custom-dimensions/bridges\"\n"
     "  cd \"$BUNDLE_CONFIG\"\n"
     "  platform_config_files | while IFS= read -r f; do\n")
edit("deploy", "scripts/deploy.sh",
     "if [[ -f \"$SCRIPT_DIR/patch-mod-data.py\" ]]; then\n"
     "  # A non-zero exit means a carpet jar is present and NOT patched. Loud, but\n"
     "  # not fatal: a half-applied deploy is the worse outcome, and the crash it\n"
     "  # guards needs a Supplementaries piston interaction to fire.\n"
     "  if ! python3 \"$SCRIPT_DIR/patch-mod-data.py\" \"$SERVER_DIR/data/mods\"; then\n"
     "    warn \"carpet is UNPATCHED — expect the Supplementaries piston crash (docs/known-issues/carpet-supplementaries-piston-crash.md)\"\n"
     "  fi\n"
     "fi\n",
     "if [[ -f \"$SCRIPT_DIR/patch-mod-data.py\" ]]; then\n"
     "  python3 \"$SCRIPT_DIR/patch-mod-data.py\" \"$SERVER_DIR/data/mods\" || true\n"
     "fi\n")
edit("deploy", "scripts/dev-up.sh",
     "# Local and production must repair the same jars: without this, Epic\n"
     "# Dungeons' CamelCase loot ids abort feature placement and spawn lootless\n"
     "# chests, and carpet's piston mixin crashes the tick loop next to\n"
     "# Supplementaries.\n"
     "if [[ -f \"$SCRIPT_DIR/patch-mod-data.py\" ]]; then\n"
     "  # A non-zero exit means a carpet jar is present and NOT patched. Loud, but\n"
     "  # not fatal: a half-applied deploy is the worse outcome, and the crash it\n"
     "  # guards needs a Supplementaries piston interaction to fire.\n"
     "  if ! python3 \"$SCRIPT_DIR/patch-mod-data.py\" \"$CONSUMER_DIR/data/mods\"; then\n"
     "    warn \"carpet is UNPATCHED — expect the Supplementaries piston crash (docs/known-issues/carpet-supplementaries-piston-crash.md)\"\n"
     "  fi\n"
     "fi\n",
     "# Local and production must repair the same jars: without this, Epic\n"
     "# Dungeons' CamelCase loot ids abort feature placement and spawn lootless\n"
     "# chests.\n"
     "if [[ -f \"$SCRIPT_DIR/patch-mod-data.py\" ]]; then\n"
     "  python3 \"$SCRIPT_DIR/patch-mod-data.py\" \"$CONSUMER_DIR/data/mods\" || true\n"
     "fi\n")
edit("deploy", "scripts/dev-up.sh",
     "# A checkout linked at a version the 26.x rebuild serves stages its v2 set as\n"
     "# smoke-26.sh does (cutover-removals.md): the mod refuses a dimensions/ file\n"
     "# without \"schemaVersion\", and the base config seeds the 1.21.1 set and its\n"
     "# thumbnails there. A v2 file the consumer added stays.\n"
     "V2_SOURCE=\"\"\n"
     "[[ -f \"$STACK_DIR/PLATFORM\" ]] && V2_SOURCE=\"$(cat \"$STACK_DIR/PLATFORM\")/mods/custom-dimensions-26\"\n"
     "if [[ \"$MC_VERSION\" != 1.* && -n \"$V2_SOURCE\" && -d \"$V2_SOURCE/definitions\" ]]; then\n"
     "  v2_dims=\"$CONSUMER_DIR/data/config/custom-dimensions/dimensions\"\n"
     "  v2_bridges=\"$CONSUMER_DIR/data/config/custom-dimensions/bridges\"\n"
     "  mkdir -p \"$v2_dims\"\n"
     "  cp \"$V2_SOURCE\"/definitions/*.json \"$v2_dims/\"\n"
     "  for f in \"$v2_dims\"/*; do\n"
     "    [[ -f \"$f\" && \"$f\" == *.json ]] && grep -q '\"schemaVersion\"' \"$f\" && continue\n"
     "    rm -f \"$f\"\n"
     "  done\n"
     "  rm -rf \"$v2_bridges\"\n"
     "  mkdir -p \"$v2_bridges\"\n"
     "  cp \"$V2_SOURCE\"/bridges/*.json \"$v2_bridges/\"\n"
     "  echo \"  v2 definitions staged: $(find \"$v2_dims\" -name '*.json' | wc -l | tr -d ' ') files, $(find \"$v2_bridges\" -name '*.json' | wc -l | tr -d ' ') bridges\"\n"
     "fi\n",
     "# Bridges are the bundle's set exactly, as deploy.sh keeps them: the config\n"
     "# sync above is skip-if-exists, and a bridge deleted from the platform must\n"
     "# stop adopting here too.\n"
     "if [[ -d \"$STACK_DIR/config/custom-dimensions/bridges\" ]]; then\n"
     "  rm -rf \"$CONSUMER_DIR/data/config/custom-dimensions/bridges\"\n"
     "  mkdir -p \"$CONSUMER_DIR/data/config/custom-dimensions/bridges\"\n"
     "  cp \"$STACK_DIR\"/config/custom-dimensions/bridges/*.json \"$CONSUMER_DIR/data/config/custom-dimensions/bridges/\"\n"
     "fi\n")
edit("deploy", "scripts/dev-up.sh",
     "# write its own config (as smoke-26.sh).", "# write its own config (as smoke.sh).")
edit("deploy", "scripts/build-stack-bundle.sh",
     "# Excludes 1password.env (secrets), pinned mod lists and the climate grids\n"
     "# (platform-internal: 2.3 MB that only check-band-share.py reads).\n",
     "# Excludes 1password.env (secrets) and pinned mod lists.\n")
edit("deploy", "scripts/build-stack-bundle.sh",
     "  --exclude='modrinth-mods*.pinned.txt' \\\n  --exclude='grids-41' \\\n",
     "  --exclude='modrinth-mods*.pinned.txt' \\\n")

edit("deploy", "scripts/idle-tasks.sh",
     'SETTINGS_FILE="$CD_DIR/settings.json"\n'
     'OVERLAY_SETTINGS_FILE="$CD_DIR/overlay/settings.json"\n',
     'SETTINGS_FILE="$DIM_DIR/settings.json"\n'
     'OVERLAY_SETTINGS_FILE="$OVERLAY_DIM_DIR/settings.json"\n')

edit("deploy", "scripts/dev-up.sh",
     '    "$CONSUMER_DIR/data/config/custom-dimensions/settings.json" 2> /dev/null \\\n',
     '    "$CONSUMER_DIR/data/config/custom-dimensions/dimensions/settings.json" 2> /dev/null \\\n')
edit("deploy", "scripts/dev-up.sh",
     '      overworld | the_nether | the_end | paradise_lost | *_thumb) continue ;;\n',
     '      overworld | the_nether | the_end | settings) continue ;;\n')

# --- mod: the 26.3 mod's paths to the definitions and bridges in config/ -----
DEF = "../../../config/custom-dimensions/dimensions"
BRI = "../../../config/custom-dimensions/bridges"
edit("mod", "mods/custom-dimensions-26/server/build.gradle",
     "file('../definitions')", f"file('{DEF}')", all=True)
edit("mod", "mods/custom-dimensions-26/server/build.gradle",
     "file('../bridges')", f"file('{BRI}')", all=True)
edit("mod", "mods/custom-dimensions-26/compiler/build.gradle",
     "\tdef definitions = file('../definitions').canonicalFile\n"
     "\targs '--definitions', definitions.path,\n"
     "\t\t\t'--mapping', new File(definitions, 'id-mapping.csv').path,\n",
     f"\tdef definitions = file('{DEF}').canonicalFile\n"
     "\targs '--definitions', definitions.path,\n"
     "\t\t\t'--mapping', file('../definitions/id-mapping.csv').canonicalPath,\n")
edit("mod", "mods/custom-dimensions-26/compiler/build.gradle",
     "file('../definitions')", f"file('{DEF}')", all=True)
edit("mod", "mods/custom-dimensions-26/compiler/build.gradle",
     "file('../bridges')", f"file('{BRI}')", all=True)
edit("mod", "mods/custom-dimensions-26/core/src/test/java/com/customdimensions/core/definition/ShippedDefinitionsSchemaTest.java",
     'Path.of("../definitions")', f'Path.of("{DEF}")')
edit("mod", "mods/custom-dimensions-26/core/src/test/java/com/customdimensions/core/firewall/ShippedBridgesTest.java",
     'Path.of("../bridges")', f'Path.of("{BRI}")')
edit("mod", "mods/custom-dimensions-26/server/src/test/java/com/customdimensions/server/structure/StructureSizesFileTest.java",
     'Path.of("../bridges")', f'Path.of("{BRI}")')
edit("mod", "mods/custom-dimensions-26/scripts/vanilla-boot-check.sh",
     '"$here/definitions/settings.json"', '"$here/../../config/custom-dimensions/dimensions/settings.json"')
edit("mod", "mods/custom-dimensions-client-26/scripts/review-packet.py",
     "new_path = os.path.join(ROOT, 'mods', 'custom-dimensions-26', 'definitions', f'{slug}.json')",
     "new_path = os.path.join(ROOT, 'config', 'custom-dimensions', 'dimensions', f'{slug}.json')")
edit("mod", "mods/custom-dimensions-client-26/scripts/review-packet.py",
     "old_path = old_override or os.path.join(ROOT, 'config', 'custom-dimensions', 'dimensions', f'{slug}.json')",
     "old_path = old_override or os.path.join(ROOT, 'mods', 'custom-dimensions-26', 'definitions', 'v1', 'dimensions', f'{slug}.json')")
edit("mod", "mods/custom-dimensions-client-26/scripts/review-packet.py",
     "  the 1.21.1 definition (config/custom-dimensions/dimensions/<slug>.json, read-only: RB-35): description, biome list,\n",
     "  the 1.21.1 definition (mods/custom-dimensions-26/definitions/v1/dimensions/<slug>.json): description, biome list,\n")
edit("mod", "mods/custom-dimensions-client-26/scripts/review-packet.py",
     "  the v2 definition (mods/custom-dimensions-26/definitions/<slug>.json): description, biomes, terrain, zones;\n",
     "  the v2 definition (config/custom-dimensions/dimensions/<slug>.json): description, biomes, terrain, zones;\n")
edit("mod", ".github/workflows/mod26.yml", "name: Mod build (26.3)\n", "name: Mod build\n")
edit("mod", ".github/workflows/mod26.yml",
     "      - 'mods/local-mods-26.3.manifest'\n",
     "      - 'mods/local-mods.manifest'\n"
     "      - 'config/custom-dimensions/**'\n", all=True)
edit("mod", ".github/workflows/mod26.yml",
     "        working-directory: .\n        env:\n          MC_VERSION_OVERRIDE: '26.3'\n        run: |\n",
     "        working-directory: .\n        run: |\n")

# --- mod: core's v1 config tests read the 1.21.1 set where the switch keeps it -
T = "mods/custom-dimensions-26/core/src/test/java/com/customdimensions/core/"
for name in ("VanillaManagedPortalTest", "PortalFrameCollisionTest", "MultiPortalConfigTest", "ConsumesIgniterConfigTest"):
    edit("mod", T + "config/" + name + ".java",
         'Path.of("../../../config/custom-dimensions/dimensions")', 'Path.of("../definitions/v1/dimensions")')
for name in ("ShippedDefinitionsLoadTest", "ShippedDimensionReachabilityTest"):
    edit("mod", T + "config/" + name + ".java",
         'Path.of("..", "..", "..", "config", "custom-dimensions")', 'Path.of("..", "definitions", "v1")')
edit("mod", T + "definition/DifficultyTest.java",
     'Path.of(System.getProperty("user.dir")).getParent().resolve("definitions")',
     'Path.of(System.getProperty("user.dir")).getParent().resolve("../../config/custom-dimensions/dimensions").normalize()')

C = "mods/custom-dimensions-26/compiler/src/test/java/com/customdimensions/compiler/convert/"
edit("mod", C + "ConvertDefinitionsTest.java",
     '/** The converter over the real 1.21.1 definitions in {@code config/custom-dimensions/}. */',
     '/** The converter over the real 1.21.1 definitions in {@code mods/custom-dimensions-26/definitions/v1/}. */')
edit("mod", C + "ConvertDefinitionsTest.java",
     'private static final Path CONFIG = REPO.resolve("config/custom-dimensions");',
     'private static final Path CONFIG = Path.of("../definitions/v1");')
edit("mod", C + "ApplyIdMappingTest.java",
     "definitions.resolve(IdMapping.FILE)", 'Path.of("..", "definitions", IdMapping.FILE)', all=True)
edit("mod", C + "ApplyIdMappingTest.java",
     'definitions.resolveSibling("schema").resolve("definition.schema.json")',
     'Path.of("..", "schema", "definition.schema.json")')
edit("mod", "mods/custom-dimensions-26/compiler/build.gradle",
     "// Rewrites definitions/ (schema v2) and its conversion report from the 1.21.1\n"
     "// definitions in config/custom-dimensions/. The repository root is three levels up.\n",
     "// Converts the 1.21.1 definitions kept in definitions/v1/ to schema v2, with its\n"
     "// conversion report. The repository root is three levels up.\n")
edit("mod", "mods/custom-dimensions-26/compiler/build.gradle",
     "description = 'Converts config/custom-dimensions/ to schema v2 in build/converted-definitions/, beside the hand-edited set (RB-35).'",
     "description = 'Converts definitions/v1/ (the 1.21.1 set) to schema v2 in build/converted-definitions/, beside the hand-edited set (RB-35).'")
edit("mod", "mods/custom-dimensions-26/compiler/build.gradle",
     "args '--config', new File(repoRoot, 'config/custom-dimensions').path,",
     "args '--config', file('../definitions/v1').canonicalPath,")

# --- discord: /mc border and /mc status -------------------------------------
edit("discord", "scripts/discord-sync.py",
     '        f"chunky radius {radius}",\n'
     '        "chunky shape square",\n'
     '        "chunky border add",\n',
     '        f"chunky radius {radius}",\n'
     '        "chunky shape square",\n')
edit("discord", "scripts/discord-sync.py",
     '        f"chunky radius {nether_radius}",\n'
     '        "chunky shape square",\n'
     '        "chunky border add",\n',
     '        f"chunky radius {nether_radius}",\n'
     '        "chunky shape square",\n')
edit("discord", "scripts/discord-sync.py",
     '    rcon_results = await async_rcon_batch(["spark health", "list", "chunky progress"])\n'
     '    health_raw = rcon_results.get("spark health")\n'
     '    list_raw = rcon_results.get("list")\n'
     '    chunky_raw = rcon_results.get("chunky progress")\n'
     '\n'
     '    health = parse_spark_health(health_raw or "")\n'
     '    # spark on 26.3 answers asynchronously (its health report is an upload),\n'
     '    # so RCON gets nothing back; vanilla `tick query` answers in place.\n'
     '    if "tps" not in health:\n'
     '        health = {**parse_tick_query(await async_rcon("tick query") or ""), **health}\n'
     '    tps = health.get("tps", "?")\n'
     '    mspt = health.get("mspt", "?")\n'
     '    memory = health.get("memory", "?")\n'
     '    server_up = health_raw is not None\n',
     '    rcon_results = await async_rcon_batch(["tick query", "list", "chunky progress"])\n'
     '    tick_raw = rcon_results.get("tick query")\n'
     '    list_raw = rcon_results.get("list")\n'
     '    chunky_raw = rcon_results.get("chunky progress")\n'
     '\n'
     '    # spark answers health by uploading a report, so RCON gets nothing back;\n'
     '    # vanilla `tick query` answers in place.\n'
     '    health = parse_tick_query(tick_raw or "")\n'
     '    tps = health.get("tps", "?")\n'
     '    mspt = health.get("mspt", "?")\n'
     '    server_up = tick_raw is not None\n')
edit("discord", "scripts/discord-sync.py",
     'f"  {status_icon} | Uptime: {uptime} | Memory: {memory}",',
     'f"  {status_icon} | Uptime: {uptime}",')
edit("discord", "scripts/admin-parity-check.sh",
     'expect "status: tick query (spark fallback)" "tick query" "Average time per tick"\n'
     'logged "status: spark health" "spark health" "Health Report|spark.lucko.me|TPS from last"\n',
     'expect "status: tick query" "tick query" "Average time per tick"\n')
edit("discord", "scripts/tests/test_discord_sync_status.py",
     '        self.ns = load("parse_spark_health", "parse_tick_query")\n',
     '        self.ns = load("parse_tick_query")\n')
edit("discord", "scripts/tests/test_discord_sync_status.py",
     '    def test_spark_silence_falls_through(self):\n'
     '        self.assertNotIn("tps", self.ns["parse_spark_health"](""))\n'
     '\n'
     '    def test_status_uses_tick_query_only_without_spark_tps(self):\n'
     '        src = SYNC_PY.read_text()\n'
     '        body = src[src.index("async def mc_status"):]\n'
     '        body = body[:body.index("\\n@")]\n'
     '        self.assertIn(\'if "tps" not in health:\', body)\n'
     '        self.assertIn(\'async_rcon("tick query")\', body)\n'
     '        self.assertLess(body.index("parse_spark_health"), body.index(\'async_rcon("tick query")\'))\n',
     '    def test_status_reads_tick_query_not_spark(self):\n'
     '        src = SYNC_PY.read_text()\n'
     '        body = src[src.index("async def mc_status"):]\n'
     '        body = body[:body.index("\\n@")]\n'
     '        self.assertIn(\'"tick query"\', body)\n'
     '        self.assertNotIn("spark health", body)\n')

# --- depreview: join26 folds into smoke --------------------------------------
edit("depreview", ".github/workflows/dep-review.yml",
     "#   smoke   smoke-test.yml against the PR head (mods and defaults-seed)\n"
     "#   join26  smoke-test-26.yml against the PR head when the 26.3 mod list or the\n"
     "#           version table changes: the 26.3 boot, the vanilla client join gate\n"
     "#           and the admin parity check\n",
     "#   smoke   smoke-test.yml against the PR head (mods, defaults-seed, the\n"
     "#           version table): the boot, the vanilla client join gate and the\n"
     "#           admin parity check\n")
edit("depreview", ".github/workflows/dep-review.yml",
     "  join26:\n"
     "    name: 26.3 boot, vanilla join and admin parity\n"
     "    needs: gate\n"
     "    if: needs.gate.outputs.ok == 'true' && needs.gate.outputs.skip != 'true' && needs.gate.outputs.needs_join_26 == 'true'\n"
     "    permissions:\n"
     "      contents: read\n"
     "    uses: ./.github/workflows/smoke-test-26.yml\n"
     "    with:\n"
     "      ref: ${{ needs.gate.outputs.head_sha }}\n"
     "\n", "")
edit("depreview", ".github/workflows/dep-review.yml",
     "    needs: [gate, quick, smoke, join26]\n", "    needs: [gate, quick, smoke]\n")
edit("depreview", ".github/workflows/dep-review.yml",
     "    needs: [gate, quick, smoke, join26, review]\n", "    needs: [gate, quick, smoke, review]\n")
edit("depreview", ".github/workflows/dep-review.yml",
     "          JOIN26: ${{ needs.join26.result }}\n", "")
edit("depreview", ".github/workflows/dep-review.yml",
     "jq -n --arg quick \"$QUICK\" --arg smoke \"$SMOKE\" --arg join26 \"$JOIN26\" --slurpfile checks checks.json \\\n"
     "            '{quick: $quick, smoke: $smoke, join26: $join26, docker_build: $quick, checks: $checks[0]}' > tests.json\n",
     "jq -n --arg quick \"$QUICK\" --arg smoke \"$SMOKE\" --slurpfile checks checks.json \\\n"
     "            '{quick: $quick, smoke: $smoke, docker_build: $quick, checks: $checks[0]}' > tests.json\n")
edit("depreview", "scripts/dep_review/gate.py",
     '        "needs_join_26": False,\n', "")
edit("depreview", "scripts/dep_review/gate.py",
     '        needs_smoke=source in MOD_SOURCES or source == PLATFORM or "docker/defaults-seed/Dockerfile" in names,\n'
     '        needs_join_26=any(n in JOIN_26_PATHS for n in names),\n',
     '        needs_smoke=source in MOD_SOURCES or source == PLATFORM or any(n in SMOKE_PATHS for n in names),\n')
edit("depreview", "scripts/dep_review/gate.py",
     "# A change to the 26.3 mod list or the version table boots the 26.3 stack and\n"
     "# joins it with the vanilla client (smoke-test-26.yml).\n"
     'JOIN_26_PATHS = ("config/modrinth-mods-26.3.txt", "scripts/minecraft-versions.tsv")\n',
     "# Files whose change boots the stack and joins it with the vanilla client\n"
     "# (smoke-test.yml) whatever the PR's source.\n"
     'SMOKE_PATHS = ("docker/defaults-seed/Dockerfile", "config/modrinth-mods.txt", "scripts/minecraft-versions.tsv")\n')
edit("depreview", "scripts/dep_review/gate.py",
     "        f.write(f\"needs_join_26={flag(result.get('needs_join_26'))}\\n\")\n", "")
edit("depreview", "scripts/dep_review/apply.py",
     '    if gate.get("needs_join_26"):\n'
     '        required.append(("join26", "26.3 boot, vanilla join and admin parity"))\n', "")
edit("depreview", "scripts/tests/test_dep_review_gate.py",
     '    def test_the_26_3_join_gate_follows_its_paths(self):\n'
     '        r = self.check(27, "actions")\n'
     '        self.assertFalse(r["needs_join_26"])\n'
     '        self.assertEqual(gate.JOIN_26_PATHS, ("config/modrinth-mods-26.3.txt", "scripts/minecraft-versions.tsv"))\n'
     '        out = Path(tempfile.mkdtemp()) / "out"\n'
     '        gate.write_github_output(out, {**r, "needs_join_26": True})\n'
     '        self.assertIn("needs_join_26=true\\n", out.read_text())\n'
     '        gate.write_github_output(out, r)\n'
     '        self.assertIn("needs_join_26=false\\n", out.read_text())\n',
     '    def test_the_smoke_paths(self):\n'
     '        self.assertEqual(gate.SMOKE_PATHS, ("docker/defaults-seed/Dockerfile", "config/modrinth-mods.txt",\n'
     '                                            "scripts/minecraft-versions.tsv"))\n'
     '        out = Path(tempfile.mkdtemp()) / "out"\n'
     '        gate.write_github_output(out, self.check(27, "actions"))\n'
     '        self.assertIn("needs_smoke=false\\n", out.read_text())\n'
     '        self.assertNotIn("needs_join_26", out.read_text())\n')
edit("depreview", "scripts/tests/test_dep_review_apply.py",
     '    def test_a_failed_26_3_join_gate_is_not_ready(self):\n'
     '        a = change("terralith", flags=["never-automerge"])\n'
     '        plan = self.run_apply([a], verdict([judged(a)]), source="worldgen", needs_join_26=True,\n'
     '                              tests={**GREEN, "join26": "failure"})\n'
     '        self.assertHuman(plan, "26.3 boot, vanilla join and admin parity")\n'
     '\n'
     '    def test_the_26_3_join_gate_is_only_required_when_the_gate_asks(self):\n'
     '        a = change("terralith", flags=["never-automerge"])\n'
     '        plan = self.run_apply([a], verdict([judged(a)]), source="worldgen", tests=GREEN)\n'
     '        self.assertNotIn("26.3 boot", json.dumps(plan))\n'
     '\n', "")

# --- tests: the quick gate's 1.21.1 band and spawn-filter checks -------------
# --- tests: test_minecraft_version.py and the floodgate path once 26.3 is the only row
T = "scripts/tests/test_minecraft_version.py"
edit("tests", T,
     '    assert minecraft_version.info("1.21.1") == {"java": "21", "image": "2026.7.0-java21", "loader": "0.19.5",\n'
     '                                             "accepted": ("1.21.1", "1.21")}\n', "")
edit("tests", T,
     '    assert minecraft_version.local_mods(REPO, "1.21.1") == REPO / "mods" / "local-mods.manifest"\n'
     '    assert minecraft_version.client_mods(REPO, "1.21.1") == REPO / "mods" / "client-mods.manifest"\n'
     '    assert minecraft_version.local_mods(REPO, "26.3") == REPO / "mods" / "local-mods-26.3.manifest"\n'
     '    assert minecraft_version.client_mods(REPO, "26.3") == REPO / "mods" / "client-mods-26.3.manifest"\n'
     '    for v in ("1.21.1", "26.3"):\n',
     '    assert minecraft_version.local_mods(REPO, "26.3") == REPO / "mods" / "local-mods.manifest"\n'
     '    assert minecraft_version.client_mods(REPO, "26.3") == REPO / "mods" / "client-mods.manifest"\n'
     '    for v in ("26.3",):\n')
edit("tests", T,
     '    rows = [r for r in (REPO / "mods" / "local-mods-26.3.manifest").read_text().splitlines()\n',
     '    rows = [r for r in (REPO / "mods" / "local-mods.manifest").read_text().splitlines()\n')
edit("tests", T,
     '    client_rows = [r for r in (REPO / "mods" / "client-mods-26.3.manifest").read_text().splitlines()\n',
     '    client_rows = [r for r in (REPO / "mods" / "client-mods.manifest").read_text().splitlines()\n')
edit("tests", T,
     '    assert minecraft_version.mod_list(REPO, "1.21.1") == REPO / "config/modrinth-mods.txt"\n'
     '    assert minecraft_version.mod_list(REPO, "26.3") == REPO / "config/modrinth-mods-26.3.txt"\n',
     '    assert minecraft_version.mod_list(REPO, "26.3") == REPO / "config/modrinth-mods.txt"\n')
edit("tests", T,
     '    assert minecraft_version.info("1.21.1")["loader"] == "0.19.5"\n'
     '    assert minecraft_version.info("26.3")["loader"] == "0.20.1"\n'
     '    assert minecraft_version.info("1.21.1")["accepted"] == ("1.21.1", "1.21")\n',
     '    assert minecraft_version.info("26.3")["loader"] == "0.20.1"\n')
edit("tests", T,
     '    assert r.stdout.split() == ["26.3", minecraft_version.info("26.3")["image"], "0.20.1"]\n'
     '    f.write_text("1.21.1\\n")\n'
     '    r = bash(\'export_mc_version; bash -c \\\'echo "$FABRIC_LOADER_VERSION"\\\'\', MC_VERSION_FILE=str(f))\n'
     '    assert r.stdout.strip() == "0.19.5"\n',
     '    assert r.stdout.split() == ["26.3", minecraft_version.info("26.3")["image"], "0.20.1"]\n')
edit("tests", T,
     'def test_compose_fallbacks_match_the_1_21_1_row():\n'
     '    compose = (REPO / "docker-compose.yml").read_text()\n'
     '    tags = re.findall(r"itzg/minecraft-server:\\$\\{MC_IMAGE_TAG:-([^}]+)\\}", compose)\n'
     '    assert tags == [minecraft_version.info("1.21.1")["image"]]\n'
     '    assert set(re.findall(r"\\$\\{MC_VERSION:-([^}]+)\\}", compose)) == {"1.21.1"}\n'
     '    assert re.findall(r"\\$\\{FABRIC_LOADER_VERSION:-([^}]+)\\}", compose) == [minecraft_version.info("1.21.1")["loader"]]\n',
     'def test_compose_fallbacks_match_the_26_3_row():\n'
     '    compose = (REPO / "docker-compose.yml").read_text()\n'
     '    tags = re.findall(r"itzg/minecraft-server:\\$\\{MC_IMAGE_TAG:-([^}]+)\\}", compose)\n'
     '    assert tags == [minecraft_version.info("26.3")["image"]]\n'
     '    assert set(re.findall(r"\\$\\{MC_VERSION:-([^}]+)\\}", compose)) == {"26.3"}\n'
     '    assert re.findall(r"\\$\\{FABRIC_LOADER_VERSION:-([^}]+)\\}", compose) == [minecraft_version.info("26.3")["loader"]]\n')
edit("tests", T,
     '    assert masked.count("<v>") == 2 and minecraft_version.info(target)["loader"] not in masked\n'
     '    other = next(r for r in TABLE.read_text().splitlines()\n'
     '                 if r.split() and not r.startswith("#") and r.split()[0] != target)\n'
     '    assert platform_updates.mask_line("scripts/minecraft-versions.tsv", other) is None\n',
     '    assert masked.count("<v>") == 2 and minecraft_version.info(target)["loader"] not in masked\n')
edit("tests", T,
     '    m = json.loads((REPO / "modpack" / "adventure-26.3.mrpack.json").read_text())\n',
     '    m = json.loads((REPO / "modpack" / "adventure.mrpack.json").read_text())\n')
edit("tests", "scripts/tests/test_discord_sync_links.py",
     'FLOODGATE_CONFIG = SCRIPTS.parent / "config" / "versions" / "26.3" / "floodgate" / "config.yml"\n',
     'FLOODGATE_CONFIG = SCRIPTS.parent / "config" / "floodgate" / "config.yml"\n')
edit("discord", "scripts/discord-sync.py",
     '# spaces as "_" (config/versions/26.3/floodgate/config.yml). A Bedrock player is\n',
     '# spaces as "_" (config/floodgate/config.yml). A Bedrock player is\n')

edit("tests", T,
     '    remove = REPO / "modpack" / "versions" / "26.3" / "overrides-remove.txt"\n'
     '    for line in remove.read_text().splitlines():\n'
     '        line = line.split("#")[0].strip()\n'
     '        if line:\n'
     '            assert (REPO / "modpack" / "overrides" / line).exists(), line\n', "")
edit("tests", "scripts/tests/test_stack_version.py",
     '                 / "mods/custom-dimensions/gradle.properties")\n',
     '                 / "mods/custom-dimensions-26/gradle.properties")\n')
edit("mod", "mods/custom-dimensions-26/gradle.properties", "mod_version=0.1.0\n", "mod_version=0.0.0-local\n")

edit("lists", "modpack/adventure-26.3.mrpack.json",
     '    "_parityExempt": {},\n',
     '    "_parityExempt": {\n'
     '      "farmers-delight-refabricated": "farmers-delight-polymer serves its blocks and items to vanilla clients; the vanilla join gate (smoke-test.yml) boots with both and joins unmodded"\n'
     '    },\n')

# --- docs: README, DEPENDENCIES and common-tasks lines that name 1.21.1 --------
edit("docs", "README.md", "modded Minecraft **1.21.1** server", "modded Minecraft **26.3** server")
edit("docs", "README.md", "mc (Fabric 1.21.1, ~150 mods)", "mc (Fabric 26.3, Geyser)")
edit("docs", "README.md",
     "| `smoke-26.sh` | Boot the real 26.3 stack from the checkout with `MC_VERSION_OVERRIDE` (seed image, java25 image, the 26.3 mod list, config overlay and in-house mod) and gate its boot log; `smoke-test-26.yml` runs it, and it runs the same way on a workstation |",
     "| `smoke.sh` | Boot the real stack from the checkout (seed image, the version's itzg image, mod list, config overlay and in-house mod) and gate its boot log; `smoke-test.yml` runs it, and it runs the same way on a workstation |")
edit("docs", "README.md", "`smoke-test-26.yml` runs it after the boot gate", "`smoke-test.yml` runs it after the boot gate")
edit("docs", "README.md", "Minecraft 1.21.1 is a fixed decision.", "Minecraft 26.3 is a fixed decision.")
edit("docs", "README.md",
     "| `reset-seed.sh` | Mac | World reset with a new seed (backs up first, triple-confirmed; wipes the old world's bot/webhook messages from the Discord channels) |\n",
     "| `reset-seed.sh` | Mac | World reset with a new seed (backs up first, triple-confirmed; wipes the old world's bot/webhook messages from the Discord channels) |\n"
     "| `migrate-to-26.sh` | Mac | A 1.21.1 server and its consumer repo to the v6 stack, phase by phase (a dry run unless `--apply`); run by path from the pulled v6 bundle: [`docs/migrate-to-26.md`](docs/migrate-to-26.md) |\n")
edit("docs", "README.md", "Minecraft **1.21.1**, Fabric, Docker, conventional networking, Cloudflare HTTP tunnel only, Incendium-only Nether,",
     "Minecraft **26.3**, Fabric, Docker, conventional networking, Cloudflare HTTP tunnel only, one worldgen owner (custom-dimensions),")
edit("docs", "docs/common-tasks.md",
     "| Change a game rule | `config/boring_default_game_rules/config.json` + `scripts/deploy.sh` | Push (full deploy) |",
     "| Change a game rule | `scripts/deploy.sh` (`restore_game_rules`) + `scripts/game-rules.tsv` | Push (full deploy) |")
edit("docs", "DEPENDENCIES.md", "| Minecraft | `MC_VERSION` (compose default `1.21.1`) | Fixed |", "| Minecraft | `.minecraft-version` (compose default `26.3`) | Fixed |")
edit("docs", "DEPENDENCIES.md",
     "- for a change to `config/modrinth-mods-26.3.txt` or `scripts/minecraft-versions.tsv`, `smoke-test-26.yml` green: the 26.3 stack boots, the unmodded 26.3 client joins and stays 60 s, and the admin parity check passes (the `join26` job);",
     "- for a change to `config/modrinth-mods.txt` or `scripts/minecraft-versions.tsv`, `smoke-test.yml` green: the stack boots, the unmodded 26.3 client joins and stays 60 s, and the admin parity check passes (the `smoke` job);")
edit("docs", "DEPENDENCIES.md", "7. **26.3 list (`config/modrinth-mods-26.3.txt`): vanilla clients join (RB-02).**", "7. **Vanilla clients join (RB-02).**")

# --- webmap: the mod draws map.DOMAIN; the uNmINeD sidecar and its image go ----
edit("webmap", "docker-compose.yml",
     "      - ./data/unmined-web:/unmined-web:ro\n",
     "      - ./data/map-web:/map-web:ro\n")
edit("webmap", "docker-compose.yml",
     "      EXCLUDES: unmined-web,", "      EXCLUDES: map-web,", all=True)
edit("webmap", "docker-compose.yml",
     '      POLL_INTERVAL: "30"\n      PREGEN_BORDER_RADIUS: "${PREGEN_BORDER_RADIUS:-8192}"\n',
     '      POLL_INTERVAL: "30"\n      PREGEN_BORDER_RADIUS: "${PREGEN_BORDER_RADIUS:-8192}"\n'
     '      # The web map export (customdim map export all) after pre-generation; 0 turns it off.\n'
     '      MAP_INTERVAL: "${MAP_INTERVAL:-6h}"\n')
edit("webmap", "config/nginx/nav-proxy.conf.template",
     "# World map — static uNmINeD output (unmined-render sidecar), served\n"
     "# straight from the /unmined-web bind mount. No upstream: the map is\n",
     "# World map — static files the mod's `customdim map export` writes into\n"
     "# data/map-web, served from the /map-web bind mount. No upstream: the map is\n")
edit("webmap", "config/nginx/nav-proxy.conf.template", "    root /unmined-web;\n", "    root /map-web;\n")
edit("webmap", "config/nginx/nav-proxy.conf.template",
     "    # Root shell assets (app.js/app.css/unmined.js) — short cache so shell\n",
     "    # Root shell assets (app.js/app.css) — short cache so shell\n")
edit("webmap", ".github/workflows/publish.yml",
     "modpack-builder, unmined-render]\n", "modpack-builder]\n")
edit("webmap", "docs/web-surfaces.md",
     "| map.DOMAIN | uNmINeD viewer shell in `docker/unmined-render/webshell/`; nav + OG injected by `nav-proxy.conf.template` | `unmined-render` sidecar → `data/unmined-web/` | Infra (force-recreates nav-proxy) |",
     "| map.DOMAIN | Viewer shell in the mod jar (`mods/custom-dimensions-26/server/src/main/resources/customdimensions/map-shell/`), written into `data/map-web/` by each export; nav + OG injected by `nav-proxy.conf.template` | `customdim map export all`, sent by idle-tasks after pre-generation every `MAP_INTERVAL` → `data/map-web/` | Infra (force-recreates nav-proxy) |")

edit("webmap", 'scripts/doctor.sh',
     'check data/unmined-web and data/world first',
     'check data/world and data/map-web first')
edit("webmap", 'scripts/doctor.sh',
     'du -sh data/world data/unmined-web backups',
     'du -sh data/world data/map-web backups')
edit("webmap", 'scripts/doctor.sh',
     'discord-sync idle-tasks unmined-render; do',
     'discord-sync idle-tasks; do')
edit("webmap", 'scripts/infra-deploy.sh',
     '  unmined-render nav-proxy pack-web',
     '  nav-proxy pack-web')
edit("webmap", 'scripts/live-stats.sh',
     'du -sh data/world data/unmined-web',
     'du -sh data/world data/map-web')
edit("webmap", 'scripts/clean-dev-state.sh',
     '      echo "data/unmined-web/maps"\n      echo "data/unmined-web/index.html"\n      echo "data/unmined-web/manifest.json"\n',
     '      echo "data/map-web"\n')
edit("webmap", 'examples/consumer/dev',
     '      "$CONSUMER_DIR/data/unmined-web/maps" \\\n      "$CONSUMER_DIR/data/unmined-web/index.html" \\\n      "$CONSUMER_DIR/data/unmined-web/manifest.json" \\\n',
     '      "$CONSUMER_DIR/data/map-web" \\\n')
edit("webmap", 'examples/consumer/dev',
     'LEFT=$(ls -d "$CONSUMER_DIR/data/world" "$CONSUMER_DIR/data/unmined-web/maps" \\\n      "$CONSUMER_DIR/data/unmined-web/manifest.json" "$CONSUMER_DIR/data/dynamic-data-pack-cache" \\\n',
     'LEFT=$(ls -d "$CONSUMER_DIR/data/world" "$CONSUMER_DIR/data/map-web" \\\n      "$CONSUMER_DIR/data/dynamic-data-pack-cache" \\\n')
edit("webmap", 'examples/consumer/ops',
     '  map render                  Force a map render pass now (uNmINeD)\n  map status|threads <n>      Render progress / thread count\n',
     "  map render [dim] [what]     Export the web map now (customdim map export)\n  map status                  The last export's summary and the map's size\n")
edit("webmap", 'examples/consumer/commands.json',
     '"description": "Map sidecar: status, force re-render, threads"',
     '"description": "Web map: status, export now"')
edit("webmap", 'examples/consumer/README.md',
     '| `map.DOMAIN` (world map) | rendered by the `unmined-render` sidecar | Static output; only the nav bar and viewer shell are ours |',
     "| `map.DOMAIN` (world map) | drawn by the mod's `customdim map export` into `data/map-web/` | Static output; the nav bar is injected, the viewer ships in the mod |")
edit("webmap", 'config/cloudflared/config.yml',
     '  # uNmINeD static world map (via nav-proxy for nav bar injection)\n',
     '  # The static world map the mod exports (via nav-proxy for nav bar injection)\n')
edit("webmap", 'scripts/cloudflare-setup.sh',
     '  # uNmINeD static world map (via nav-proxy for nav bar injection)\n',
     '  # The static world map the mod exports (via nav-proxy for nav bar injection)\n')
edit("webmap", 'README.md',
     '│  unmined-render (static maps)',
     '│  map export (in mc, by idle) ')
edit("webmap", 'README.md',
     '| `unmined-render` | ghcr.io/.../unmined-render | both | Scheduled static uNmINeD map renders into `data/unmined-web/`, served at map.DOMAIN/unmined/ (off until `UNMINED_INTERVAL` is set) |\n',
     '')
edit("webmap", 'README.md',
     '| `map-render.sh` | Mac | Drive the unmined-render sidecar: status, force a render pass |',
     "| `map-render.sh` | Mac | The web map: the last export's summary, or `customdim map export` now over RCON |")
edit("webmap", 'README.md',
     "| `gen-unmined-blocktags.py` | Regenerate the map renderer's modded block tags from the block catalogue (`--check` in CI) |\n",
     '')
edit("webmap", 'CONTRIBUTING.md',
     'registry dump), `blocktags`. `mise tasks` lists them.',
     'registry dump). `mise tasks` lists them.')
edit("webmap", 'DEPENDENCIES.md',
     " `unmined-render` is on a numeric Debian tag (`debian:12.x-slim`) so Dependabot proposes its point releases; it installs `libicu72`, Debian 12's ICU package, so a Debian 13 PR fails its build until a human swaps in Debian 13's package (`libicu76`) on the PR.",
     '')
edit("webmap", '.env.example',
     '# --- Static map renders (uNmINeD — this IS map.DOMAIN) --------------------------\n# The unmined-render sidecar renders every world + visited custom dimension\n# to static webp tile maps in ./data/unmined-web; nav-proxy serves them as\n# map.DOMAIN (per-dimension URLs, long edge-cache with ?v= busting). Renders are incremental and clamped to borders.generation /\n# PREGEN_BORDER_RADIUS. Interval uses sleep(1) syntax; 0 disables.\n# UNMINED_INTERVAL=6h\n# Zoom-out levels in the web viewer (default 6).\n# UNMINED_ZOOMOUT=6\n',
     "# --- The web map (this IS map.DOMAIN) --------------------------------------------\n# idle-tasks sends `customdim map export all` once pre-generation is done, at\n# most this often: every dimension's generated layers (redrawn only when the\n# dimension changed) and the explored layer from saved chunks, into\n# ./data/map-web, which nav-proxy serves. sleep(1) syntax; 0 disables.\n# MAP_INTERVAL=6h\n")
edit("webmap", 'COMMANDS.md',
     "### World map (uNmINeD)\n\nThe map is rendered by the `unmined-render` sidecar into static tiles — no server mod, no RCON commands, no player positions. It renders on `UNMINED_INTERVAL` and only re-renders regions whose files changed. Manage it with Docker, or `./ops map ...` from your Mac:\n\n| Task                            | Command                                                  |\n| ------------------------------- | -------------------------------------------------------- |\n| Render status + recent activity | `docker logs unmined-render --tail 15`                   |\n| Container state                 | `docker inspect unmined-render --format '{{.State.Status}}'` |\n| Force a render pass now         | `./ops map render` (restarts the container, which renders immediately) |\n| Purge one map's tiles           | delete `data/unmined-web/maps/<name>/`, then force a pass |\n",
     "### World map\n\nThe mod draws the map: `customdim map export` writes every dimension's generated layers and the explored layer from saved chunks into `data/map-web/`, which nav-proxy serves. idle-tasks sends `customdim map export all` once pre-generation is done, at most every `MAP_INTERVAL` (6h). A dimension's generated layers are redrawn only when it changed, the explored layer only from region files changed since the last export. No player positions.\n\n| Task | Command |\n| --- | --- |\n| The last export's summary | `./ops map status` (`data/map-web/.export-finished`) |\n| Export now | `./ops map render [dimension\\|all] [generated\\|explored\\|force]`, or `customdim map export all` over RCON |\n| Redraw one dimension's generated layers | `customdim map export <dimension> force` |\n")
edit("webmap", 'COMMANDS.md',
     '# Map render status (the renderer is a sidecar container, no RCON)\ndocker logs unmined-render --tail 15\n\n# Force a render pass now\ndocker restart unmined-render\n',
     '# The last web map export\ncat data/map-web/.export-finished\n\n# Export the web map now\ndocker exec -i mc rcon-cli "customdim map export all"\n')
edit("webmap", 'COMMANDS.md',
     '| `/mc map-refresh` | Restart the map renderer (re-scans all maps) | 5 min cooldown |',
     '| `/mc map-refresh` | Export the web map now (`customdim map export all`) | 5 min cooldown |')
edit("webmap", 'scripts/discord-sync.py',
     'def get_map_render_status() -> list[str]:\n    """Status lines for the unmined-render sidecar container.\n\n    uNmINeD renders on an interval (no RCON interface); the container\'s\n    state and last pass summary from the logs are the observable status.\n    """\n    try:\n        result = subprocess.run(\n            ["docker", "inspect", "unmined-render", "--format", "{{.State.Status}}"],\n            capture_output=True, text=True, timeout=5,\n        )\n        if result.returncode != 0:\n            return ["    (renderer not found)"]\n        state = result.stdout.strip() or "unknown"\n        lines = [f"    renderer: {state}"]\n        logs = subprocess.run(\n            ["docker", "logs", "unmined-render", "--tail", "50"],\n            capture_output=True, text=True, timeout=5,\n        )\n        passes = re.findall(r"pass complete: (\\d+) map\\(s\\) considered", logs.stdout + logs.stderr)\n        if passes:\n            lines.append(f"    last pass: {passes[-1]} map(s)")\n        return lines\n    except Exception:\n        return ["    (unavailable)"]\n',
     'MAP_FINISHED = Path("/data/map-web/.export-finished")\nMAP_STARTED = Path("/data/.map-export-started")\n\n\ndef get_map_render_status() -> list[str]:\n    """Status lines for the web map: the last export\'s one-line summary and when it ended.\n\n    The mod stamps data/map-web/.export-finished at the end of every\n    `customdim map export`; an export started after it is still running.\n    """\n    try:\n        if not MAP_FINISHED.exists():\n            return ["    no export yet"]\n        ended = datetime.fromtimestamp(MAP_FINISHED.stat().st_mtime, tz=timezone.utc)\n        lines = [f"    last export: {MAP_FINISHED.read_text().strip()[:120]} ({ended:%Y-%m-%d %H:%M} UTC)"]\n        if MAP_STARTED.exists() and MAP_STARTED.stat().st_mtime > MAP_FINISHED.stat().st_mtime:\n            lines.append("    an export is running")\n        return lines\n    except Exception:\n        return ["    (unavailable)"]\n')
edit("webmap", 'scripts/discord-sync.py',
     '    # Map render status (unmined-render sidecar - queried via Docker, not RCON)\n',
     '    # Web map status (read from data/map-web, not RCON)\n')
edit("webmap", 'scripts/discord-sync.py',
     '@mc_group.command(name="map-refresh", description="Restart the map renderer (5 min cooldown)")',
     '@mc_group.command(name="map-refresh", description="Export the web map now (5 min cooldown)")')
edit("webmap", 'scripts/discord-sync.py',
     '    # The renderer runs a pass on startup, then sleeps UNMINED_INTERVAL, so a\n    # restart is how you force an immediate pass. Renders are incremental.\n    await interaction.response.send_message("Map renderer restarting - a render pass starts immediately.", ephemeral=True)\n    await asyncio.to_thread(\n        subprocess.run, ["docker", "restart", "unmined-render"],\n        capture_output=True, timeout=60,\n    )\n    await audit(interaction, "restarted the map renderer")\n',
     '    # The export runs in the mod off the server thread; .skip-pause-map keeps\n    # autopause off until the mod stamps .export-finished, and idle-tasks then\n    # clears it. Unchanged dimensions and regions are skipped.\n    await interaction.response.send_message("Web map export started - unchanged worlds are skipped.", ephemeral=True)\n    for marker in (MAP_STARTED, Path("/data/.skip-pause-map"), Path("/data/.skip-pause")):\n        marker.touch()\n    await asyncio.to_thread(rcon_command, "customdim map export all")\n    await audit(interaction, "started a web map export")\n')
edit("webmap", 'scripts/test-scripts.sh',
     '  + BLOCKTAG_ERRORS + OPEN_WATER_ERRORS\n  + CLIENT_PARITY_ERRORS',
     '  + OPEN_WATER_ERRORS\n  + CLIENT_PARITY_ERRORS')

edit("webmap", 'COMMANDS.md',
     'Services: `mc`, `unmined-render`, `nav-proxy`,',
     'Services: `mc`, `nav-proxy`,')
edit("webmap", 'COMMANDS.md',
     '### Map rendering\n\nManage the renderer from your Mac. Passes run automatically on `UNMINED_INTERVAL` and are incremental; `render` forces one immediately.\n\n| Command            | Description                                             |\n| ------------------ | -------------------------------------------------------- |\n| `./ops map render` | Force a render pass now (restarts the container)        |\n| `./ops map status` | Container state + recent render activity                |\n| `./ops map threads <n>`  | Manually set render thread count                           |\n\nMap IDs: `world`, `world_the_nether`, `world_the_end`, `paradise_lost`.\n',
     "### The web map\n\nThe mod exports the map; idle-tasks sends the export after pre-generation, every `MAP_INTERVAL`, and only what changed is redrawn.\n\n| Command | Description |\n| --- | --- |\n| `./ops map status` | The last export's summary, whether one runs, the map's size |\n| `./ops map render [dimension\\|all] [generated\\|explored\\|force]` | `customdim map export` now over RCON (default: all, both layers) |\n\nDimensions are named by slug (`overworld`, `the_nether`, `the_aviary`, ...).\n")
edit("webmap", '.claude/skills/deploy-pipeline-operations/SKILL.md',
     '(`unmined-render nav-proxy pack-web cloudflared mod-checker discord-sync idle-tasks`)',
     '(`nav-proxy pack-web cloudflared mod-checker discord-sync idle-tasks`)')
edit("webmap", '.claude/skills/deploy-pipeline-operations/references/deploy-sequence.md',
     '`unmined-render nav-proxy pack-web cloudflared mod-checker discord-sync idle-tasks` (kuma-init',
     '`nav-proxy pack-web cloudflared mod-checker discord-sync idle-tasks` (kuma-init')
edit("webmap", '.claude/skills/local-stack-testing/SKILL.md',
     "| Map render config or world data | `unmined-render` renders on its `UNMINED_INTERVAL` schedule and skips dimensions whose region files haven't changed, so nothing appears to happen immediately. With `UNMINED_INTERVAL=0` — the default (`render-loop.sh`, the `UNMINED_INTERVAL` default) — it `exec sleep infinity` and never renders at all (`render-loop.sh`, the disabled branch) | Set `UNMINED_INTERVAL` in `.env`, then `./dev restart unmined-render`: `render_all` runs at the top of the loop, before the sleep (`render-loop.sh`, the daemon loop) |\n",
     '| Map export or world data | The map is redrawn only by `customdim map export`: idle-tasks sends it after pre-generation every `MAP_INTERVAL`, and a dimension unchanged since the last export is skipped, so nothing appears to happen immediately | `docker exec -i mc rcon-cli "customdim map export <dimension>"` (`force` redraws the generated layers), then reload map.DOMAIN |\n')
edit("webmap", '.claude/skills/platform-release-management/SKILL.md',
     'building and pushing all 7 images (`discord-sync`, `kuma-init`, `mod-checker`, `idle-tasks`, `defaults-seed`, `modpack-builder`, `unmined-render`)',
     'building and pushing all 6 images (`discord-sync`, `kuma-init`, `mod-checker`, `idle-tasks`, `defaults-seed`, `modpack-builder`)')
edit("webmap", '.claude/skills/web-surface-branding/SKILL.md',
     '| map.DOMAIN | Static uNmINeD render (`unmined-render` sidecar); served directly by `nav-proxy`, no upstream | `unmined-render` on its own render schedule | Infra (nav/CSS changes only — force-recreates `nav-proxy`) |',
     '| map.DOMAIN | Static files the mod exports into `data/map-web/` (viewer shell in the mod jar); served directly by `nav-proxy`, no upstream | `customdim map export`, sent by idle-tasks every `MAP_INTERVAL` | Infra for nav changes (force-recreates `nav-proxy`); a mod release for the viewer |')
edit("webmap", '.claude/skills/web-surface-branding/references/surface-map.md',
     'tags: [surface-map, nav-proxy, pack-web, kuma, unmined-render, deploy-tier]',
     'tags: [surface-map, nav-proxy, pack-web, kuma, map-web, deploy-tier]')
edit("webmap", '.claude/skills/web-surface-branding/references/surface-map.md',
     '- **What actually serves it:** a **static uNmINeD render**, served directly by `nav-proxy`\'s `map` server block straight from the `./data/unmined-web` bind mount (`root /unmined-web;` — no upstream, no proxy_pass). The comment in `nav-proxy.conf.template` is explicit: "the map is always up, even while mc restarts." The `unmined-render` sidecar re-renders on a schedule (`UNMINED_INTERVAL`) and the web shell (`index.html` inside `data/unmined-web`) handles clean per-dimension URLs.\n',
     "- **What actually serves it:** static files the mod's `customdim map export` writes into `./data/map-web`, served directly by `nav-proxy`'s `map` server block from that bind mount (`root /map-web;` — no upstream, no proxy_pass). The comment in `nav-proxy.conf.template` is explicit: the map is always up, even while mc restarts. idle-tasks sends the export every `MAP_INTERVAL`; the viewer shell (`index.html`, `app.js`, `app.css`) ships in the mod jar (`map-shell/`) and each export copies it in, and it handles clean per-dimension URLs.\n")
edit("webmap", '.claude/skills/backup-and-recovery/SKILL.md',
     'unmined-web, mods, libraries, versions, logs, crash-reports,\n',
     'map-web, mods, libraries, versions, logs, crash-reports,\n')
edit("webmap", '.claude/skills/backup-and-recovery/references/world-reset.md',
     'regenerable-data excludes as the restic sidecar (`unmined-web`, `mods`',
     'regenerable-data excludes as the restic sidecar (`map-web`, `mods`')
edit("webmap", '.claude/skills/backup-and-recovery/references/world-reset.md',
     "   - `data/unmined-web/maps/`, `data/unmined-web/index.html`, and `data/unmined-web/manifest.json` — rendered map tiles and the dimension/spawn manifest the web shell reads (all three re-generated on the next render pass; `manifest.json` is a separate file from `index.html` and lists the old world's dimensions/spawns until overwritten)\n",
     '   - `data/map-web/` — the web map, its manifest and its export caches (fingerprints, explored-region cells); the next export draws it afresh for the new world\n')
edit("webmap", '.claude/skills/production-triage/references/known-issues.md',
     'piston, unmined-render, chunk-corruption]',
     'piston, web-map, chunk-corruption]')
edit("webmap", '.claude/skills/production-triage/references/known-issues.md',
     'The map is rendered by the `unmined-render` sidecar (uNmINeD CLI) into static tiles. There is no RCON interface and no live link to mc — it keeps serving while mc restarts or autopauses. Renders are incremental, so a quiet pass usually means nothing changed rather than something failed. Inspect it with:\n',
     "The map is static files the mod's `customdim map export` writes into `data/map-web`; nav-proxy serves them, so it keeps serving while mc restarts or autopauses. idle-tasks sends the export after pre-generation, every `MAP_INTERVAL`, and an unchanged dimension is skipped, so a quick export usually means nothing changed rather than something failed. Inspect it with:\n")
edit("webmap", '.claude/skills/production-triage/references/known-issues.md',
     'docker logs unmined-render --tail 30\n./scripts/map-render.sh status     # container state + recent render activity\n./scripts/map-render.sh render     # force a pass (restarts the sidecar; a\n                                    # restart triggers an immediate render)\n',
     "docker logs mc --tail 200 | grep -i 'web map'   # the export's per-dimension lines\n./scripts/map-render.sh status     # the last export's summary, whether one runs\n./scripts/map-render.sh render     # export now (customdim map export all)\n")
edit("webmap", '.claude/skills/production-triage/references/log-surfaces.md',
     './scripts/map-render.sh status            # unmined-render container state + recent activity',
     './scripts/map-render.sh status            # the last web map export, whether one runs')
edit("webmap", '.claude/skills/local-stack-testing/references/testing-recipes.md',
     "## 6. Map render config or map data\n\n```bash\ndocker restart unmined-render\n# Give it the boot -r pass to detect changed regions, then check tile mtimes:\ndocker exec unmined-render find /web -name '*.webp' -newer /tmp/marker -mmin -2 | head\n```\n\nDon't wait on the `-u` watcher locally — on macOS Docker its file-change events are unreliable and may never fire. The explicit restart is the only reliable local trigger; trust the watcher only on Linux production.\n",
     '## 6. The web map\n\n```bash\ndocker exec -i mc rcon-cli "customdim map export overworld"   # add `force` to redraw the generated layers\n# The export runs off the server thread; it is done when this file is newer than the command:\ncat data/map-web/.export-finished\n```\n\nThe local stack has no idle-tasks, so nothing exports on a schedule; send the command after each change you want to see.\n')

RANGES = [
    ("tests", "scripts/tests/test_minecraft_version.py",
     "# Each 26.3 overlay file: (base file, keys the pinned 26.3 jar no longer reads).\n",
     "def test_platform_updates_moves_the_loader_row_and_compose_together():\n"),
    # (step, path, first line of the block, first line after it)
    ("webmap", 'scripts/test-scripts.sh',
     'echo "  Checking uNmINeD block tags are up to date..."\n',
     'echo "  Checking the open-water guard datapack is up to date..."\n'),
    ("webmap", 'mise.toml',
     '[tasks.blocktags]\n',
     '[tasks.water-guard]\n'),
    ("webmap", "docker-compose.yml",
     "  # ==========================================================================\n"
     "  # unmined-render - Scheduled static map renders (uNmINeD CLI)\n",
     "  # ==========================================================================\n"
     "  # nav-proxy - Reverse proxy that injects the site nav bar\n"),
    ("webmap", ".github/workflows/publish.yml",
     "          - image: unmined-render\n",
     "          - image: kuma-init\n"),
    ("tests", "scripts/test-scripts.sh",
     'echo "  Checking biome bands reach their world\'s climate..."\n',
     'echo "  Checking client/server mod parity..."\n'),
    ("tests", "scripts/test-scripts.sh",
     'echo "  Checking spawn filters name a place..."\n',
     'echo "  Checking Modrinth resolve cache covers every pin..."\n'),
]
edit("tests", "scripts/test-scripts.sh",
     "  + BLOCKTAG_ERRORS + OPEN_WATER_ERRORS + BAND_REACH_ERRORS + BIOME_BANDS_ERRORS\n"
     "  + SPAWN_FILTER_ERRORS + CLIENT_PARITY_ERRORS + COMPOSE_ERRORS + YAML_ERRORS))\n",
     "  + BLOCKTAG_ERRORS + OPEN_WATER_ERRORS\n"
     "  + CLIENT_PARITY_ERRORS + COMPOSE_ERRORS + YAML_ERRORS))\n")


def steps():
    seen = []
    for s, *_ in E + [(r[0],) for r in RANGES]:
        if s not in seen:
            seen.append(s)
    return seen


def apply_range(root, step, path, start, stop, dry):
    p = root / path
    text = p.read_text()
    if start not in text:
        if stop in text:
            print(f"  = {path}: block before {stop.strip()[:50]!r} already removed")
            return True
        print(f"  ! DRIFT {path}: neither {start.strip()[:60]!r} nor {stop.strip()[:60]!r}")
        return False
    a = text.index(start)
    b = text.find(stop, a)
    if b < 0 or text.count(start) != 1:
        print(f"  ! DRIFT {path}: block {start.strip()[:60]!r} is not unique or has no end")
        return False
    print(f"  ~ {path}: remove block {start.strip()[:60]!r} ({text[a:b].count(chr(10))} lines)")
    if not dry:
        p.write_text(text[:a] + text[b:])
    return True


def run(root, step, dry):
    ok = True
    for s, path, old, new, every in E:
        if s != step:
            continue
        p = root / path
        if not p.exists():
            print(f"  ! DRIFT {path}: missing")
            ok = False
            continue
        text = p.read_text()
        n = text.count(old)
        first = old.strip().splitlines()[0][:70] if old.strip() else repr(old)
        if n == 1 or (every and n > 1):
            print(f"  ~ {path}: {first!r}" + (f" (x{n})" if n > 1 else ""))
            if not dry:
                text = text.replace(old, new)
                p.write_text(text)
        elif n == 0 and new and new in text:
            print(f"  = {path}: already applied: {first!r}")
        elif n == 0 and not new:
            print(f"  = {path}: already removed: {first!r}")
        else:
            print(f"  ! DRIFT {path}: {first!r} occurs {n} times")
            ok = False
    for s, path, start, stop in RANGES:
        if s == step:
            ok = apply_range(root, step, path, start, stop, dry) and ok
    return ok


def edited(root, path):
    """The text of `path` once every step's edits apply to it, for a dry run's drift check."""
    import hashlib
    text = (root / path).read_text()
    for step in steps():
        for s, p, old, new, every in E:
            if s == step and p == path and (text.count(old) == 1 or (every and text.count(old) > 1)):
                text = text.replace(old, new)
        for s, p, start, stop in RANGES:
            if s == step and p == path and text.count(start) == 1 and text.find(stop, text.index(start)) >= 0:
                a = text.index(start)
                text = text[:a] + text[text.find(stop, a):]
    return hashlib.sha256(text.encode()).hexdigest()


def main(argv):
    if argv[1:2] == ["--steps"]:
        print("\n".join(steps()))
        return 0
    if argv[1:2] == ["--sha"] and len(argv) == 4:
        print(edited(Path(argv[2]), argv[3]))
        return 0
    if len(argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    root, step, dry = Path(argv[1]), argv[2], "--dry-run" in argv[3:]
    if step not in steps():
        print(f"unknown step {step}", file=sys.stderr)
        return 2
    return 0 if run(root, step, dry) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
