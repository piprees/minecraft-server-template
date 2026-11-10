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
     "# write its own config (as smoke-26.sh).\n", "# write its own config (as smoke.sh).\n")
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
     "old_path = os.path.join(ROOT, 'config', 'custom-dimensions', 'dimensions', f'{slug}.json')",
     "old_path = os.path.join(ROOT, 'mods', 'custom-dimensions-26', 'definitions', 'v1', 'dimensions', f'{slug}.json')")
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

RANGES = [
    ("tests", "scripts/tests/test_minecraft_version.py",
     "# Each 26.3 overlay file: (base file, keys the pinned 26.3 jar no longer reads).\n",
     "def test_platform_updates_moves_the_loader_row_and_compose_together():\n"),
    # (step, path, first line of the block, first line after it)
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


def main(argv):
    if argv[1:2] == ["--steps"]:
        print("\n".join(steps()))
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
