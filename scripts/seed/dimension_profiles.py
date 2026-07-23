#!/usr/bin/env python3
"""dimension_profiles.py — per-dimension seed-roll scoring profiles.

Derives a measurement plan and a scoring profile for every dimension in
the v4 config directory (config/custom-dimensions/) or the deprecated
monolithic config/multiverse_config.json — load_config() accepts either.
This is the single source of truth for WHAT gets measured (locate
battery, biome probes, terrain grid) and HOW candidates are judged
(placement bands, terrain targets, weights).

Philosophy (from the worldgen brief):
  - Dimensions represent their namesake but are never single-biome.
  - Hard dims (dense + hostile + small playable radius) must be WORTH IT:
    hostile structures close, brutal terrain, places to hide/explore/fight.
  - Easy/peaceful dims are relaxing but not boring: scenery, variety,
    gentle structures.
  - Nether rule: the smaller the playable world (higher portal scale),
    the harder it should be; larger worlds are easier and more varied.
  - Voids are dead worlds: no terrain, but the biome layout is still
    there (mob spawning, sounds, fog) — variety of listed biomes matters.
  - Playable radius: the per-dimension borders.player when set (the mod
    applies it verbatim as the vanilla border radius — config authors
    write it pre-scaled), else PLAYER_BORDER_RADIUS / portal scale.
    Structure quality is judged by placement bands RELATIVE to it.

Used by score-dimensions.py (plan / score / finalise). No CLI here.
"""

# Vanilla world border radius the placement bands are relative to.
DEFAULT_BORDER_RADIUS = 8192

# Families share a locate battery (structure id -> band).
# "cave" rides the overworld family: minecraft:caves samples the overworld
# climate router, so biome measurement + locate batteries behave overworld-ish.
# "checkerboard" rides it too: overworld noise settings under a deterministic
# biome grid (the grid is seed-independent; terrain/structures still roll).
OVERWORLD_FAMILY = {"overworld", "multi_biome", "amplified", "large_biomes", "sky_islands", "cave",
                    "checkerboard"}
NETHER_FAMILY = {"nether", "nether_islands"}
END_FAMILY = {"end"}

# Placement bands as fractions of the playable radius — every band fits
# INSIDE the world. A structure the dimension shouldn't contain is a shun,
# not a far-away band (a 1024-radius wasteland has no "3000 blocks out").
BANDS = {
    "near_spawn": (0.00, 0.30),
    "spread": (0.15, 0.65),
    "near_border": (0.45, 1.00),
}

# structureDensity shifts placement expectations of WANTS.
DENSITY_SHIFT = {
    "dense": {"near_border": "spread", "spread": "near_spawn"},
    "sparse": {"near_spawn": "spread", "spread": "near_border"},
}

# Wants that make no sense in a peaceful dimension (dropped there; the
# peaceful generation overlay strips dungeons anyway).
HOSTILE_STRUCTURES = {
    "ancient_city", "trial_chambers", "fortress", "bastion", "sanctum",
    "coliseum", "mansion", "monument", "pillager_outpost", "bandit_towers",
    "bandit_village", "illager_fort", "keep_kayra", "infested_temple",
}

# Endgame / boss-tier structures that should NOT appear near spawn in
# normal dimensions — structures whose presence near spawn means the
# adventure is over before it starts. Hard/compressed/dense dimensions
# opt out via seedRoll.allowEndgameNearSpawn or mood=hard.
# NOT included: fortress, bastion, end_city (mid-game infrastructure).
# Rebuilt from scripts/data/structure-sets-extracted.csv (377 sets audited).
ENDGAME_STRUCTURES = {
    # vanilla climax
    "ancient_city", "trial_chambers", "mansion", "monument",
    # dungeons arise — mega-dungeons and hostile fortifications
    "coliseum", "keep_kayra", "infested_temple",
    "bandit_towers", "bandit_village", "illager_fort",
    # dungeons arise — flying ships and boss encounters
    "heavenly_rider", "heavenly_conqueror", "heavenly_challenger",
    "typhon", "shiraz_palace", "plague_asylum", "mechanical_nest",
    "kisegi_sanctuary", "thornborn_towers",
    "undead_pirate_ship", "illager_corsair", "illager_galley",
    "ceryneian_hind", "scorched_mines", "foundry",
    # incendium nether endgame
    "sanctum", "forbidden_castle", "nether_reactor",
    # moogs nether/reimagined boss structures
    "mns_nether_tower", "nether_temple",
    # end climax (MES)
    "phantom_citadel", "enderkeep", "end_gate_fortress",
    # end mega ships (MES)
    "mega_ship_crashed", "mega_ship_deepslate",
    # epic dungeons (large tiers only)
    "ice_dungeon_l", "sand_dungeon_l",
    # boss-tier towers and temples
    "ancient_crypt", "ancient_temple", "relic_temple", "wizard_tower",
    # ocean endgame
    "ocean_fortress",
    # sky islands endgame
    "sky_arena", "sky_castle_ruin", "sky_castle_tower",
    # dungeons and taverns mega-dungeons (sp >= 100)
    "creeping_crypt", "undead_crypt", "illager_hideout",
    "shrine_tower", "trident_trial", "lone_citadel", "stray_fort",
    "illager_manor",
    # philip's ruins mega crypt
    "antiquus_crypta",
    # friends & foes boss encounter
    "iceologer_citadel",
}

ENDGAME_SAFE_MOODS = {"hard", "adventurous"}

# Short name -> locate id. Every id verified against the worldgen registries
# extracted from the shipped jars/datapacks (Structory, Philip's Ruins,
# Explorify, Dungeons Plus, Epic Dungeons, Adventure Dungeons, YUNG's,
# Dungeons Arise, MNS, MES, MSS, Incendium, Nullscape).
STRUCTS = {
    # settlements + civilisation
    "village": "#minecraft:village", "tavern": "nova_structures:tavern_birch",
    "farmstead": "explorify:farmstead", "watchtower": "explorify:watchtower/plains",
    "guide_post_warm": "explorify:guide_post_warm", "guide_post_cold": "explorify:guide_post_cold",
    "campsite": "explorify:campsite", "dark_settlement": "explorify:dark_forest_settlement",
    "outcast_grassy": "structory:outcast_villager_grassy", "outcast_desert": "structory:outcast_villager_desert",
    "pillager_outpost": "minecraft:pillager_outpost", "pillager_lookout": "structory_towers:pillager_lookout",
    "nomad_outpost": "structory_towers:nomad_outpost", "taiga_outpost": "structory_towers:taiga_outpost",
    "foraging_outpost": "structory_towers:foraging_outpost", "mirage_outpost": "structory_towers:mirage_outpost",
    "quarter_outpost": "structory_towers:quarter_outpost", "engineer_tower": "structory_towers:engineer_tower",
    "firetower": "structory:firetower", "lighthouse": "structory_towers:lighthouse",
    # ruins + the-world-went-wrong
    "ruined_portal": "minecraft:ruined_portal", "mineshaft": "minecraft:mineshaft",
    "ruin_grassy": "structory:ruin_grassy", "swamp_ruin": "structory:swamp_ruin",
    "jungle_ruin": "structory:jungle_ruin", "forest_ruin": "structory:dense_forest_ruin",
    "northern_ruin": "structory:northern_ruin", "taiga_ruin": "structory:taiga_ruin_surface",
    "taiga_ruin_deep": "structory:taiga_ruin_underground",
    "abandoned_camp": "structory:abandoned_camp", "abandoned_chapel": "structory:abandoned_chapel",
    "graveyard": "structory:graveyard", "old_manor": "structory:old_manor",
    "field_ruins": "philipsruins:field_stone_ruins", "badlands_ruins": "philipsruins:badlands_structures",
    "ancient_ruins": "philipsruins:ancient_ruins", "ancient_crypt": "philipsruins:ancient_crypt",
    "pumpkin_ruins": "philipsruins:pumpkin_ruins",
    "mausoleum": "explorify:mausoleum", "ruins": "explorify:ruins",
    "ruins_desert": "adventuredungeons:ruins_desert", "ruins_snow": "adventuredungeons:ruins_snow",
    "ruins_standard": "adventuredungeons:ruins_standard",
    "desert_shrine": "explorify:desert_shrine", "badlands_pyramid": "explorify:badlands_pyramid",
    "black_spiral": "explorify:black_spiral", "mangrove_hut": "explorify:mangrove_hut",
    "supply_cache_desert": "explorify:supply_cache/desert",
    # dungeons
    "trial_chambers": "minecraft:trial_chambers", "ancient_city": "minecraft:ancient_city",
    "skeleton_dungeon": "betterdungeons:skeleton_dungeon", "spider_dungeon": "betterdungeons:spider_dungeon",
    "zombie_dungeon": "betterdungeons:zombie_dungeon",
    "cold_dungeon": "dungeons_plus:cold_dungeon", "frozen_dungeon": "dungeons_plus:frozen_dungeon",
    "lush_dungeon": "dungeons_plus:lush_dungeon", "muddy_dungeon": "dungeons_plus:muddy_dungeon",
    "webbed_dungeon": "dungeons_plus:webbed_dungeon", "infested_dungeon": "dungeons_plus:infested_dungeon",
    "mouldy_dungeon": "dungeons_plus:mouldy_dungeon", "dusty_tomb": "dungeons_plus:dusty_tomb",
    "scorched_tomb": "dungeons_plus:scorched_tomb", "deepwater_dungeon": "dungeons_plus:deepwater_dungeon",
    "ice_dungeon_l": "epic:large_ice_dungeon", "ice_dungeon_m": "epic:medium_ice_dungeon",
    "sand_dungeon_l": "epic:large_sand_dungeon", "sculk_dungeon": "philipsruins:sculk_dungeon",
    "bone_dungeon": "philipsruins:bone_dungeon", "underground_camp": "adventuredungeons:underground_camp",
    "coldlair": "adventuredungeons:coldlair", "murkydungeon": "adventuredungeons:murkydungeon",
    # epic
    "coliseum": "dungeons_arise:coliseum", "keep_kayra": "dungeons_arise:keep_kayra",
    "infested_temple": "dungeons_arise:infested_temple", "abandoned_temple": "dungeons_arise:abandoned_temple",
    "bandit_towers": "dungeons_arise:bandit_towers", "bandit_village": "dungeons_arise:bandit_village",
    "illager_fort": "dungeons_arise:illager_fort", "illager_campsite": "dungeons_arise:illager_campsite",
    "jungle_tree_house": "dungeons_arise:jungle_tree_house", "giant_mushroom": "dungeons_arise:giant_mushroom",
    "wizard_tower": "structory_towers:wizard_tower", "ancient_temple": "structory_towers:ancient_temple",
    "relic_temple": "structory_towers:sacred_relic_temple",
    "mansion": "minecraft:mansion", "monument": "minecraft:monument",
    "heavenly_rider": "dungeons_arise:heavenly_rider",
    "heavenly_conqueror": "dungeons_arise:heavenly_conqueror",
    "heavenly_challenger": "dungeons_arise:heavenly_challenger",
    "typhon": "dungeons_arise:typhon", "shiraz_palace": "dungeons_arise:shiraz_palace",
    "plague_asylum": "dungeons_arise:plague_asylum",
    "mechanical_nest": "dungeons_arise:mechanical_nest",
    "kisegi_sanctuary": "dungeons_arise:kisegi_sanctuary",
    "thornborn_towers": "dungeons_arise:thornborn_towers",
    "undead_pirate_ship": "dungeons_arise:undead_pirate_ship",
    "illager_corsair": "dungeons_arise:illager_corsair",
    "illager_galley": "dungeons_arise:illager_galley",
    "ceryneian_hind": "dungeons_arise:ceryneian_hind",
    "scorched_mines": "dungeons_arise:scorched_mines",
    "mining_complex": "dungeons_arise:mining_complex",
    "foundry": "dungeons_arise:foundry",
    # dungeons and taverns boss/mega structures
    "creeping_crypt": "nova_structures:creeping_crypt",
    "undead_crypt": "nova_structures:undead_crypt",
    "illager_hideout": "nova_structures:illager_hideout",
    "shrine_tower": "nova_structures:shrine_tower",
    "trident_trial": "nova_structures:trident_trial_monument",
    "lone_citadel": "nova_structures:lone_citadel",
    "stray_fort": "nova_structures:stray_fort",
    "illager_manor": "nova_structures:illager_manor",
    # friends & foes
    "iceologer_citadel": "friendsandfoes:citadel",
    # moogs nether/reimagined boss structures
    "mns_nether_tower": "mns:nether_tower",
    "nether_temple": "mtr:nether_temple",
    # philip's ruins mega crypt
    "antiquus_crypta": "philipsruins:antiquus_crypta",
    # sky islands castle tower
    "sky_castle_tower": "mss:castle_tower",
    # ocean / frozen vanilla
    "shipwreck": "minecraft:shipwreck", "buried_treasure": "minecraft:buried_treasure",
    "igloo": "minecraft:igloo", "desert_pyramid": "minecraft:desert_pyramid",
    "ocean_ruins": "philipsruins:ocean_ruins", "ocean_fortress": "philipsruins:ocean_fortress",
    "ocean_pillar": "structory_towers:ocean_pillar",
    # nether
    "fortress": "betterfortresses:fortress", "bastion": "minecraft:bastion_remnant",
    "sanctum": "incendium:sanctum", "forbidden_castle": "incendium:forbidden_castle",
    "piglin_village": "incendium:piglin_village", "nether_reactor": "incendium:nether_reactor",
    "ruined_lab": "incendium:ruined_lab", "infernal_altar": "incendium:infernal_altar",
    "nether_tower": "incendium:abandoned_tower", "pipeline": "incendium:pipeline",
    "giant_skull": "mns:giant_skull", "nether_graveyard": "mns:grave_yard",
    "crimson_forge": "mns:crimson_forge", "copper_tower": "mns:copper_tower",
    "blackstone_pillars": "mns:large_blackstone_pillars", "blackstone_walls": "mns:large_blackstone_walls",
    "nether_bridge": "mns:bridge_1", "crimson_well": "mns:crimson_lava_well",
    "crimson_fungus": "mns:medium_crimson_fungus", "nether_brick_hall": "mns:large_nether_brick",
    "warped_greatsword": "structory_towers:warped_greatsword",
    "warped_outpost": "structory_towers:nether/warped_outpost",
    "strange_outpost": "structory_towers:nether/strange_outpost",
    "nether_dungeon": "betterdungeons:small_nether_dungeon",
    "lost_soul_dungeon": "philipsruins:lost_soul_dungeon", "nether_lava_ruins": "philipsruins:nether_lava_ruins",
    "start_nether_ruin": "philipsruins:start_nether_ruin",
    # end
    "end_city": "minecraft:end_city", "phantom_citadel": "mes:phantom_citadel",
    "enderkeep": "mes:enderkeep_courtyard", "enderwatch_tower": "mes:enderwatch_tower",
    "ender_spire": "mes:ender_spire", "monolith": "mes:monolith",
    "ruined_pillar": "mes:ruined_pillar", "mystical_archway": "mes:mystical_archway",
    "manuscript_shrine": "mes:manuscript_shrine", "mythic_garden": "mes:mythic_garden",
    "astral_hideaway": "mes:astral_hideaway", "endscraps": "mes:endscraps",
    "mega_ship_crashed": "mes:mega_ship_crashed", "mega_ship_deepslate": "mes:mega_ship_crashed_deepslate",
    "dragon_skeleton": "nullscape:dragon_skeleton", "end_tower": "structory_towers:end/end_tower",
    "end_ruins": "philipsruins:end_ruins", "end_gate_fortress": "philipsruins:end_gate_fortress",
    # in-house (custom-dimensions jar datapack)
    "exit_shrine": "adventure:exit_shrine",
    # sky islands (MSS places on the floating islands)
    "sky_castle_ruin": "mss:castle_ruin", "sky_arena": "mss:arena",
    "sky_house": "mss:small_oak_house", "sky_volcano": "mss:volcano",
    # paradise lost (the skylands family)
    "para_remains": "paradise_lost:remains", "aurel_tower": "paradise_lost:aurel_tower",
    "para_vault": "paradise_lost:vault", "para_palace": "paradise_lost:palace",
    "birdcage": "paradise_lost:birdcage",
}

# Per-clone-type column search ranges (min_y/height from the dimension_type;
# paradise islands float well above the overworld ceiling).
CLONE_HEIGHT_RANGES = {
    "paradise_lost:paradise_lost": (-60, 440),
}

# What each mood is optimising for — shown in the viewer so a human can
# judge the judge.
MOOD_BLURBS = {
    "hard": "A hard world: hostile structures close enough to matter, brutal "
            "terrain with places to hide and fight. Going here must be worth it.",
    "adventurous": "Structure-led exploration: plenty to find at sane "
                   "distances, terrain interesting but traversable.",
    "dramatic": "Terrain is the star — high relief, craggy grain. Structures "
                "are seasoning, not the meal.",
    "scenic": "Stunning and iconic at spawn; gentle exploration, low threat.",
    "pastoral": "Rolling, liveable, buildable. Settlements near, dungeons far.",
    "serene": "Relaxing but not boring: soft terrain, gentle structures, "
              "no hostile pressure.",
    "desolate": "Empty and evocative — the namesake mood carries it; sparse "
                "everything, wide horizons.",
    "standard": "A believable, balanced world — variety, fair structure "
                "spread, vanilla-plus feel.",
}

# Mood -> default clearSpawnRadius (blocks). Structures inside this zone
# penalise the score — the player should have breathing room at spawn,
# not land on top of a dungeon entrance. Hard/adventurous dims want
# structures in your face; serene/pastoral want space to build.
MOOD_CLEAR_SPAWN = {
    "hard": 0,
    "adventurous": 0,
    "dramatic": 48,
    "scenic": 64,
    "pastoral": 80,
    "serene": 80,
    "desolate": 48,
    "standard": 48,
}

# Mood -> component weights (namesake, variety, terrain, structures).
# Normalised at use; hostile-structure emphasis rides inside structures.
MOOD_WEIGHTS = {
    "hard": {"namesake": 15, "variety": 15, "terrain": 25, "structures": 45},
    "adventurous": {"namesake": 15, "variety": 15, "terrain": 20, "structures": 50},
    "dramatic": {"namesake": 20, "variety": 15, "terrain": 40, "structures": 25},
    "scenic": {"namesake": 30, "variety": 20, "terrain": 35, "structures": 15},
    "pastoral": {"namesake": 25, "variety": 20, "terrain": 30, "structures": 25},
    "serene": {"namesake": 30, "variety": 20, "terrain": 30, "structures": 20},
    "desolate": {"namesake": 30, "variety": 10, "terrain": 40, "structures": 20},
    "standard": {"namesake": 20, "variety": 20, "terrain": 30, "structures": 30},
}


# Base-world filenames in config/custom-dimensions/dimensions/ override
# existing worlds instead of creating new ones.
BASE_WORLD_IDS = {
    "overworld": "minecraft:overworld",
    "the_nether": "minecraft:the_nether",
    "the_end": "minecraft:the_end",
    "paradise_lost": "paradise_lost:paradise_lost",
}


def load_dimension_configs(config_dir):
    """Scan {config_dir}/dimensions/*.json -> {slug: raw config dict}.
    The slug comes from the filename (never the JSON), matching the mod's
    loader. Unparseable files are skipped with a warning, not fatal."""
    import json
    import sys
    from pathlib import Path
    configs = {}
    dims_dir = Path(config_dir) / "dimensions"
    if not dims_dir.is_dir():
        return configs
    for f in sorted(dims_dir.glob("*.json")):
        try:
            data = json.loads(f.read_text())
        except json.JSONDecodeError as e:
            print(f"warning: skipping unparseable {f}: {e}", file=sys.stderr)
            continue
        if isinstance(data, dict):
            configs[f.stem.lower()] = data
    return configs


def load_config(config_path):
    """A monolith-shaped config dict from either format: a directory
    (config/custom-dimensions/) is synthesised into the legacy in-memory
    shape (namespace/dimensions/portals/worlds/worldSeed) so every
    downstream consumer keeps working; a file is read as-is."""
    import json
    from pathlib import Path
    p = Path(config_path)
    if p.is_dir():
        return monolith_from_dir(p)
    return json.loads(p.read_text())


def _deep_merge(base, over):
    """Recursive dict merge, `over` wins — mirrors the mod's deepMerge."""
    out = dict(base)
    for key, value in over.items():
        if isinstance(out.get(key), dict) and isinstance(value, dict):
            out[key] = _deep_merge(out[key], value)
        else:
            out[key] = value
    return out


def resolve_overlay(platform, overlay, namespace):
    """Consumer overlay resolution, mirroring the mod's DimensionConfigLoader:
    empty {} -> skip; top-level "overrides" -> deep-merge over the platform
    default; anything else -> full replace; overlay-only slugs are
    consumer-added (namespaced by BRAND_SLUG when set). -> {slug: (dict, ns)}"""
    import os
    consumer_ns = os.environ.get("BRAND_SLUG") or namespace
    resolved = {}
    for slug, data in platform.items():
        over = overlay.get(slug)
        if over is None:
            resolved[slug] = (data, namespace)
        elif not over:
            continue  # empty {} — dimension disabled by the consumer
        elif isinstance(over.get("overrides"), dict):
            resolved[slug] = (_deep_merge(data, over["overrides"]), namespace)
        else:
            resolved[slug] = (over, namespace)
    for slug, over in overlay.items():
        if slug in platform or not over:
            continue
        body = over["overrides"] if isinstance(over.get("overrides"), dict) else over
        resolved[slug] = (body, consumer_ns)
    return resolved


def monolith_from_dir(config_dir):
    """Synthesise the legacy monolithic shape from the per-file directory.
    A staged consumer overlay at {config_dir}/overlay/dimensions (the layout
    deploy.sh/dev-up.sh produce inside data/config/custom-dimensions) is
    resolved exactly like the mod does at boot."""
    import json
    from pathlib import Path
    p = Path(config_dir)
    settings = {}
    settings_file = p / "settings.json"
    if settings_file.exists():
        settings = json.loads(settings_file.read_text())
    ns = settings.get("namespace", "adventure")
    files = load_dimension_configs(p)
    overlay_files = load_dimension_configs(p / "overlay")
    namespaces = {}
    if overlay_files:
        resolved = resolve_overlay(files, overlay_files, ns)
        files = {slug: data for slug, (data, _ns) in resolved.items()}
        namespaces = {slug: dim_ns for slug, (_data, dim_ns) in resolved.items()}

    dimensions, worlds, portals = [], [], []
    world_seed = None
    for slug, f in files.items():
        if slug in BASE_WORLD_IDS:
            w = {"name": slug, "dimensionId": BASE_WORLD_IDS[slug]}
            seed = f.get("seed")
            # "env" sentinel stays env-driven — only numeric seeds carry over.
            if isinstance(seed, (int, float)) and not isinstance(seed, bool):
                if slug == "overworld":
                    world_seed = int(seed)
                else:
                    w["seed"] = int(seed)
            for key in ("spawn", "scale", "seedRoll", "difficulty", "borders"):
                if key in f:
                    w[key] = f[key]
            worlds.append(w)
            continue

        d = {"name": slug,
             "dimensionId": f.get("dimensionId") or f"{namespaces.get(slug, ns)}:{slug}"}
        for key in ("type", "seed", "spawn", "noiseSettings", "structureDensity",
                    "seedRoll", "difficulty", "borders", "structures",
                    "checkerboardScale", "layers", "flatBiome",
                    "settingsOverrides", "biomePatches", "exitShrines"):
            if key in f:
                d[key] = f[key]
        biomes = f.get("biomes")
        if biomes:
            ids, bparams = biome_ids_and_params(biomes)
            d["biome"] = ",".join(ids)
            if bparams:
                d["biomeParameters"] = bparams
        elif f.get("biome"):
            d["biome"] = f["biome"]
        dif = f.get("difficulty") or {}
        hostile = dif.get("hostileSpawning", f.get("hostileSpawning"))
        if hostile is not None:
            d["hostileSpawning"] = hostile
        dimensions.append(d)

        portal = f.get("portal")
        if portal and portal.get("frameBlock"):
            entry = {"id": slug, "targetDimension": d["dimensionId"]}
            for key in ("frameBlock", "igniterItem", "color", "lightLevel",
                        "scale", "cooldown", "particleType"):
                if key in portal:
                    entry[key] = portal[key]
            sounds = portal.get("sounds") or {}
            entry["igniteSound"] = sounds.get("ignite", portal.get("igniteSound", "block.portal.trigger"))
            entry["enterSound"] = sounds.get("enter", portal.get("enterSound", "block.portal.travel"))
            entry["exitSound"] = sounds.get("exit", portal.get("exitSound", "block.portal.travel"))
            portals.append(entry)

    out = {
        "namespace": ns,
        "idleUnloadMinutes": settings.get("idleUnloadMinutes", 5),
        "dimensions": dimensions,
        "portals": portals,
        "worlds": worlds,
    }
    frames = settings.get("frames", {})
    for src, dst in (("overworld", "frameOverworld"), ("nether", "frameNether"), ("end", "frameEnd")):
        if frames.get(src):
            out[dst] = frames[src]
    if world_seed is not None:
        out["worldSeed"] = world_seed
    return out


def biome_ids_and_params(entries):
    """Split a v4 "biomes" array into (ordered id list, {id: parameters}).
    Entries are plain id strings or {"id": ..., "parameters": {...}} objects
    (Tier 3) — mirrors DimensionConfig.getBiomes/getBiomeParameters."""
    ids, params = [], {}
    for e in entries or []:
        if isinstance(e, str):
            if e.strip():
                ids.append(e.strip())
        elif isinstance(e, dict):
            bid = (e.get("id") or "").strip()
            if not bid:
                continue
            ids.append(bid)
            if isinstance(e.get("parameters"), dict):
                params[bid] = e["parameters"]
    return ids, params


def family_of(dim_type):
    if dim_type in OVERWORLD_FAMILY:
        return "overworld"
    if dim_type in NETHER_FAMILY:
        return "nether"
    if dim_type in END_FAMILY:
        return "end"
    if dim_type and ":" in dim_type:
        # Generic clone types (ns:path): family guess from the id.
        if "nether" in dim_type:
            return "nether"
        if "end" in dim_type:
            return "end"
        return "overworld"
    return None  # void / superflat


def world_family(dimension_id):
    """Family for a 'worlds' entry (vanilla + static mod dimensions)."""
    if dimension_id == "minecraft:the_nether":
        return "nether"
    if dimension_id == "minecraft:the_end":
        return "end"
    if dimension_id == "paradise_lost:paradise_lost":
        return "paradise_lost"
    return "overworld"


def load_difficulty(config_path):
    """Per-dimension mob difficulty multipliers from
    config/configurable-difficulty/configurable-difficulty.json5 (sibling of
    the multiverse config file OR the custom-dimensions directory — both
    live under config/). Tolerant of // comments; {} when absent. v4
    per-dimension files carry difficulty.mobMultiplier themselves, which
    wins in build_profile; this is the legacy fallback."""
    import json
    import re
    from pathlib import Path
    p = Path(config_path).parent / "configurable-difficulty" / "configurable-difficulty.json5"
    if not p.exists():
        return {}
    text = re.sub(r"^\s*//.*$", "", p.read_text(), flags=re.M)
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return {}
    return data.get("dimensionMultipliers", {})


def mood_from_difficulty(mult):
    if mult <= 0.0:
        return "serene"
    if mult <= 0.9:
        return "scenic"
    if mult <= 1.2:
        return "standard"
    if mult <= 1.7:
        return "adventurous"
    return "hard"


def rollable(dim):
    """seedRoll.skip is the explicit opt-out: {"seedRoll": {"skip": true}}
    excludes a dimension from measurement and scoring entirely (mirrors the
    mod's DimensionConfig.SeedRoll.skip — the mod itself ignores seedRoll).
    Superflat is not rollable (flat generator — nothing varies with the
    seed, custom layers/flatBiome included). Voids roll ONLY with a biome
    list: the mod's adventure:void noise generator (custom-dimensions >=
    1.2.0) gives them a real, seeded biome layout, so the seed genuinely
    changes what spawns/sounds/looms in the fog. A void without biomes has
    nothing to measure. Checkerboard rolls: the biome GRID is seed-
    independent, but terrain shape and structure placement still vary."""
    if (dim.get("seedRoll") or {}).get("skip"):
        return False
    t = dim.get("type")
    if t == "superflat":
        return False
    if t == "void":
        return bool(dim.get("biome") or dim.get("biomes"))
    return True


def portal_scales(config):
    return {p["targetDimension"].split(":", 1)[1]: float(p.get("scale", 1.0))
            for p in config.get("portals", [])}


def shifted_band(band, density):
    return DENSITY_SHIFT.get(density or "", {}).get(band, band)


def grid_pitch(radius):
    """Sample pitch so the 3x3 grid spans ~half the playable radius."""
    return max(64, min(512, int(radius / 4)))


def nether_difficulty(scale):
    """Nether rule: smaller playable world (bigger scale) = harder."""
    if scale >= 12:
        return "hard"
    if scale >= 8:
        return "adventurous"
    return "standard"


# Terrain targets by noiseSettings flavour (matched on the id's path so
# consumer preset namespaces work too): relief = max-min of grid heights,
# grain = mean |dh| between adjacent points, water = wet grid fraction.
TERRAIN_TARGETS = {
    "compressed": {"relief": (40, 160), "grain": (6, 26), "water": (0.0, 0.30)},
    "wide": {"relief": (10, 60), "grain": (0, 6), "water": (0.05, 0.45)},
    None: {"relief": (18, 90), "grain": (2, 14), "water": (0.0, 0.45)},
}


def terrain_targets_for(noise):
    key = (noise or "").rsplit(":", 1)[-1] or None
    return dict(TERRAIN_TARGETS.get(key, TERRAIN_TARGETS[None]))


# Generic wants for dimensions with NO seedRoll block (e.g. a consumer's
# own dimensions before they curate one). Deliberately modest.
DEFAULT_WANTS = {
    "overworld": {"village": "near_spawn", "mineshaft": "spread",
                  "trial_chambers": "spread", "ancient_city": "near_border"},
    "nether": {"fortress": "spread", "bastion": "spread"},
    "end": {"end_city": "spread"},
}


def resolve_struct(name):
    """wants/shuns entries are STRUCTS short names or raw '<ns>:<path>' ids."""
    return STRUCTS.get(name, name if ":" in name else None)


def want_range(value, radius, density):
    """A want's placement window in BLOCKS (v4 Phase 6).

    Explicit range objects ({"min": N, "max": M}) are absolute block
    distances, used as-is (structureDensity shifting only applies to the
    band-name shorthand — explicit is explicit). Legacy band-name strings
    ("near_spawn"/"spread"/"near_border") convert via the BANDS fractions
    of the playable radius, density-shifted as before. None = invalid."""
    if isinstance(value, dict):
        try:
            lo = float(value.get("min", 0))
            hi = float(value.get("max", radius))
        except (TypeError, ValueError):
            return None
        return (lo, hi) if hi > lo >= 0 else None
    if isinstance(value, str) and value in BANDS:
        lo_f, hi_f = BANDS[shifted_band(value, density)]
        return (lo_f * radius, hi_f * radius)
    return None


def shun_threshold(value, radius):
    """A shun's minimum-distance threshold in BLOCKS. {"minDistance": N}
    means "must be at LEAST N away"; 0 (or the legacy bare-name form)
    means "must not exist anywhere inside the playable radius"."""
    if isinstance(value, dict):
        try:
            dist = float(value.get("minDistance", 0))
        except (TypeError, ValueError):
            return radius
        return dist if dist > 0 else radius
    return radius


def build_profile(dim, config, difficulty=None):
    """Full per-dimension profile from the dimension's config entry — the
    'seedRoll' block (mood/spawnFilter/water/wants/shuns/description) is the
    single source of truth; sensible generics cover entries without one.
    Accepts legacy monolith entries AND raw v4 per-file dicts ("biomes"
    array, "portal" block, "difficulty" block). Handles both runtime
    dimensions and base-world entries (no "type" key = base world)."""
    name = dim["name"]
    is_world = "type" not in dim  # base-world entries have no type
    dim_type = dim.get("type", "world")
    sr_early = dim.get("seedRoll") or {}
    # Config dictates everything; type-string heuristics are only fallbacks.
    fam = sr_early.get("family")
    if fam not in ("overworld", "nether", "end", "paradise_lost"):
        fam = world_family(dim.get("dimensionId", "")) if is_world else family_of(dim_type)
    portal = dim.get("portal") or {}
    if is_world:
        scale = float(dim.get("scale", 1.0))
    else:
        scale = float(portal.get("scale") or portal_scales(config).get(name, 1.0))
    # borders.player IS the playable radius (WorldBorderManager applies it
    # unscaled); the 8192/scale heuristic only covers configs without one.
    border_player = (dim.get("borders") or {}).get("player")
    if isinstance(border_player, (int, float)) and not isinstance(border_player, bool) \
            and border_player > 0:
        radius = float(border_player)
    else:
        radius = DEFAULT_BORDER_RADIUS / scale
    density = dim.get("structureDensity")
    dim_difficulty = dim.get("difficulty") or {}
    peaceful = dim_difficulty.get("hostileSpawning", dim.get("hostileSpawning")) is False
    noise = dim.get("noiseSettings")
    sr = dim.get("seedRoll") or {}
    # Biome list: raw v4 dicts may carry object entries with parameters
    # (Tier 3); monolith synthesis collapses them into "biome" (id string)
    # + "biomeParameters". Both paths land in the same two variables.
    raw_biomes = dim.get("biomes")
    if raw_biomes:
        config_biomes, biome_parameters = biome_ids_and_params(raw_biomes)
    else:
        config_biomes = [b.strip() for b in (dim.get("biome") or "").split(",") if b.strip()]
        biome_parameters = dim.get("biomeParameters") or {}

    # Mob difficulty: the v4 per-dimension difficulty block wins; the
    # legacy configurable-difficulty.json5 dict is the fallback. Tiebreaker
    # for mood when the config doesn't set one; always shown in the viewer.
    dim_id = dim.get("dimensionId", "")
    mob_difficulty = dim_difficulty.get("mobMultiplier", (difficulty or {}).get(dim_id))

    mood = sr.get("mood", "standard")
    if mood not in MOOD_WEIGHTS:
        mood = "standard"
    if "mood" not in sr:
        if mob_difficulty is not None:
            mood = mood_from_difficulty(mob_difficulty)
        elif fam == "nether":
            mood = nether_difficulty(scale)
        if peaceful:
            mood = "serene"
        elif density == "dense" and mood in ("standard", "adventurous"):
            mood = "adventurous"

    # Spawn identity: the seedRoll spawnFilter (candidates whose spawn misses
    # it are rejected); probes also cover the config list so a listed-but-
    # off-filter spawn still identifies itself in the data.
    namesake = list(sr.get("spawnFilter") or config_biomes[:4])
    spawn_probes = list(namesake)
    for b in config_biomes:
        if b not in spawn_probes:
            spawn_probes.append(b)

    # Biome variety battery: locate biome for listed biomes (voids,
    # multi_biome, islands); otherwise the spawn filter. Locate biome is
    # ~1s per call, so long lists are sampled evenly down to 8.
    variety_biomes = config_biomes if config_biomes else namesake[:4]
    if len(variety_biomes) > 8:
        step = len(variety_biomes) / 8.0
        variety_biomes = [variety_biomes[int(i * step)] for i in range(8)]

    # Structure battery (v4 Phase 6): the top-level "structures" block wins
    # (wants/shuns as explicit block-distance ranges); the legacy seedRoll
    # wants/shuns (band names / bare lists) remain fully supported. Wants
    # are range-scored (peaceful drops hostile ones); shuns cost the point
    # when the structure sits closer than its minimum distance.
    struct_block = dim.get("structures") or {}
    if struct_block.get("wants") is not None:
        wants = struct_block["wants"]
    elif "wants" in sr or "shuns" in sr:
        wants = sr.get("wants")
    else:
        wants = DEFAULT_WANTS.get(fam or "", {})
    if struct_block.get("shuns") is not None:
        shuns = struct_block["shuns"]
    else:
        shuns = sr.get("shuns", [])
    battery = []
    for sname, value in (wants or {}).items():
        sid = resolve_struct(sname)
        rng = want_range(value, radius, density)
        if sid is None or rng is None:
            continue
        if peaceful and sname in HOSTILE_STRUCTURES:
            continue
        battery.append((sname, sid, rng, "want"))
    shun_items = shuns.items() if isinstance(shuns, dict) else [(s, None) for s in (shuns or [])]
    for sname, value in shun_items:
        sid = resolve_struct(sname)
        if sid is not None:
            battery.append((sname, sid, shun_threshold(value, radius), "shun"))

    terrain = terrain_targets_for(noise)
    # Mood modulation: hard/dramatic want more violence, serene less.
    if mood in ("hard", "dramatic"):
        lo, hi = terrain["relief"]
        terrain["relief"] = (lo * 1.25, hi * 1.4)
        lo, hi = terrain["grain"]
        terrain["grain"] = (max(lo, 3), hi * 1.3)
    elif mood in ("serene", "pastoral"):
        lo, hi = terrain["relief"]
        terrain["relief"] = (lo * 0.7, hi * 0.8)
    # Water preference overrides.
    wpref = sr.get("water")
    if wpref == "sea":
        terrain["water"] = (0.5, 1.0)
    elif wpref == "high":
        terrain["water"] = (0.25, 0.8)
    elif wpref == "none":
        terrain["water"] = (0.0, 0.10)

    terrain_kind = sr.get("terrain")  # config override: solid | islands | void
    if terrain_kind in ("void", "islands", "solid"):
        is_void = terrain_kind == "void"
        is_islands = terrain_kind == "islands"
    else:
        is_void = dim_type == "void"
        is_islands = dim_type in ("sky_islands", "nether_islands") \
            or "paradise_lost" in (dim_type or "") \
            or dim.get("dimensionId") == "paradise_lost:paradise_lost"

    weights = dict(MOOD_WEIGHTS[mood])
    if is_void:
        # No terrain in a void — variety and namesake carry the score.
        weights = {"namesake": 30, "variety": 55, "terrain": 15, "structures": 0}
    elif mob_difficulty is not None and mob_difficulty >= 2.0:
        # Very dangerous worlds must be WORTH IT: structures matter more.
        weights["structures"] += 10
        weights["namesake"] = max(5, weights["namesake"] - 5)
        weights["variety"] = max(5, weights["variety"] - 5)

    # Clear-spawn radius: structures inside this zone penalise the score.
    # Config wins; mood default is the fallback.
    csr = struct_block.get("clearSpawnRadius")
    if isinstance(csr, (int, float)) and not isinstance(csr, bool):
        clear_spawn_radius = max(0, int(csr))
    else:
        clear_spawn_radius = MOOD_CLEAR_SPAWN.get(mood, 48)

    # Endgame near-spawn safety: hard/dense dims want endgame close;
    # everything else penalises candidates with endgame inside a protected
    # zone. The structures.endgame block overrides the heuristics.
    # Endgame structures are merged INTO the battery as shuns with
    # minDistance=safe_r — one pass, one set of locates, scoring handles it.
    endgame_cfg = struct_block.get("endgame") or {}
    if endgame_cfg.get("allow") is not None:
        allow_endgame = bool(endgame_cfg["allow"])
    else:
        allow_endgame = sr.get("allowEndgameNearSpawn", False) \
            or mood in ENDGAME_SAFE_MOODS \
            or density == "dense"
    if allow_endgame:
        endgame_safe_radius = 0
    elif endgame_cfg.get("safeRadius") is not None:
        endgame_safe_radius = int(endgame_cfg["safeRadius"])
    else:
        endgame_safe_radius = max(256, int(0.15 * radius))
    # Endgame structures are NOT in the battery — each locate blocks the
    # server thread for up to 120s, and 50+ entries would take hours per
    # seed. Endgame proximity requires the mod's async locate command
    # (future work). For now, only config wants+shuns are measured.

    return {
        "name": name,
        "blurb": sr.get("description")
        or MOOD_BLURBS[mood] + (" (Void: no terrain generates, but the biome layout"
                                " is real — variety and namesake carry the score.)"
                                if is_void else ""),
        "type": dim_type,
        "family": fam,
        "is_world": is_world,
        "dimension_id": dim.get("dimensionId", ""),
        "mob_difficulty": mob_difficulty,
        "scale": scale,
        "radius": radius,
        "density": density,
        "peaceful": peaceful,
        "noise": noise,
        "mood": mood,
        "spawn_probes": spawn_probes,
        "namesake": sorted(namesake),
        "variety_biomes": variety_biomes,
        "battery": battery,
        "terrain": terrain,
        "weights": weights,
        "is_void": is_void,
        "is_islands": is_islands,
        "height_range": (tuple(sr["heightRange"]) if isinstance(sr.get("heightRange"), list)
                         and len(sr["heightRange"]) == 2
                         else CLONE_HEIGHT_RANGES.get(dim_type)),
        "clear_spawn_radius": clear_spawn_radius,
        "endgame_safe_radius": endgame_safe_radius,
        # Checkerboard grid scale (vanilla codec 0-62, default 2); the fast
        # roller's CheckerboardBiomeSampler mirrors the mod's grid formula.
        "checkerboard_scale": dim.get("checkerboardScale"),
        # Tier 3 parity: explicit multi-noise intervals ({id: parameters}),
        # ChunkGeneratorSettings swaps (seaLevel/defaultFluid feed the fluid
        # check in seed_worker), and per-set placement overrides (tier-1
        # structure maths applies them before nearest_structure).
        "biome_parameters": biome_parameters,
        "settings_overrides": dim.get("settingsOverrides") or {},
        "spacing_overrides": struct_block.get("spacing") or {},
        # Precision placement: fixed circular patches over the layout —
        # the fast roller wraps its sampler in PatchedBiomeSampler.
        "biome_patches": dim.get("biomePatches") or [],
        # Exit shrines: the adventure:exit_shrines set ships at frequency
        # 0.001 and DimensionStructures raises it to 1.0 for opted-in dims —
        # tier-1 structure maths mirrors the raise (fast_roller).
        "exit_shrines": bool((dim.get("exitShrines") or {}).get("enabled")),
        # Wants may deliberately sit beyond the border (pocket-dim scenery
        # visible via Distant Horizons) — the locate cap must reach them.
        "locate_cap": int(max([radius] + [spec[1] for _n, _sid, spec, kind in battery
                                          if kind == "want"]) + 1000),
        "grid_pitch": grid_pitch(radius),
        "create_args": {
            "type": dim_type,
            "noiseSettings": noise,
            "structureDensity": density,
            "biome": ",".join(config_biomes) if config_biomes else None,
        },
    }
