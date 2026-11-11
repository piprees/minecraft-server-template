"""Writes the k7 places drafts: base (k1/k2/k3 draft over the head definition, else the head) plus biomeLayout.places."""
import json, os, sys
sys.path.insert(0, '/tmp/claude-0/k7')
import summ

OUT = summ.R + 'fidelity/drafts/k7/'

# slug -> (weights for the placed biomes, or None to keep the base's; places object)
S = {}

# ---- Batch 2: Nether presets
S['the_forged_depths'] = ({'adventure:quartz_flats': 12, 'minecraft:crimson_forest': 8, 'adventure:crimson_pinewood': 55,
                           'minecraft:basalt_deltas': 18, 'minecraft:nether_wastes': 7},
                          {'groups': {'forge': ['adventure:quartz_flats', 'minecraft:crimson_forest'],
                                      'basalt': ['minecraft:basalt_deltas', 'minecraft:nether_wastes']},
                           'arrangement': 'core'})
# order of places = first appearance; core puts the first at the centre: list order decides. Groups first.
S['the_blackstone_keep'] = ({'adventure:blackstone_basin': 55, 'minecraft:basalt_deltas': 12, 'adventure:ash_barrens': 8,
                             'minecraft:soul_sand_valley': 10, 'adventure:crimson_pinewood': 8, 'minecraft:crimson_forest': 7},
                            {'groups': {'ash_and_basalt': ['minecraft:basalt_deltas', 'adventure:ash_barrens'],
                                        'wart_woods': ['adventure:crimson_pinewood', 'minecraft:crimson_forest']},
                             'arrangement': 'core'})
S['the_gilded_pit'] = ({'adventure:blackstone_basin': 20, 'minecraft:nether_wastes': 50, 'minecraft:basalt_deltas': 15,
                        'adventure:magma_land': 5, 'adventure:gravel_desert': 6, 'minecraft:crimson_forest': 4},
                       {'groups': {'basalt': ['minecraft:basalt_deltas', 'adventure:magma_land'],
                                   'ruins': ['adventure:gravel_desert', 'minecraft:crimson_forest']},
                        'at': {'minecraft:nether_wastes': [0, 0]}})
S['the_molten_flats'] = ({'adventure:magma_land': 55, 'adventure:ash_barrens': 20, 'minecraft:basalt_deltas': 15,
                          'adventure:gravel_desert': 7, 'minecraft:nether_wastes': 3},
                         {'groups': {'dunes': ['adventure:gravel_desert', 'minecraft:nether_wastes']},
                          'arrangement': 'core'})
S['the_bloodroot_wastes'] = ({'minecraft:crimson_forest': 55, 'adventure:nether_jungle': 20, 'adventure:upside_down_forest': 12,
                              'adventure:crimson_pinewood': 5, 'minecraft:nether_wastes': 5, 'minecraft:basalt_deltas': 3},
                             {'groups': {'inverted': ['adventure:upside_down_forest', 'adventure:crimson_pinewood'],
                                         'wastes': ['minecraft:nether_wastes', 'minecraft:basalt_deltas']},
                              'arrangement': 'core'})
S['the_obsidian_sanctum'] = ({'adventure:withered_forest': 52, 'minecraft:soul_sand_valley': 28, 'minecraft:nether_wastes': 12,
                              'minecraft:basalt_deltas': 8},
                             {'groups': {'border_wastes': ['minecraft:nether_wastes', 'minecraft:basalt_deltas']},
                              'arrangement': 'core'})
S['the_boneyard'] = ({'minecraft:soul_sand_valley': 55, 'adventure:ash_barrens': 20, 'adventure:gravel_desert': 12,
                      'adventure:quartz_flats': 6, 'adventure:withered_forest': 7},
                     {'groups': {'bone_reefs': ['adventure:gravel_desert', 'adventure:quartz_flats']},
                      'arrangement': 'core'})
S['the_furnace_halls'] = (None, {})
S['the_wailing_narrows'] = ({'minecraft:soul_sand_valley': 52, 'minecraft:basalt_deltas': 36, 'adventure:quartz_flats': 12},
                            {'arrangement': 'core'})
S['the_ember_fields'] = ({'adventure:nether_grasslands': 55, 'adventure:sulphur_springs': 20, 'adventure:quartz_flats': 5,
                          'minecraft:crimson_forest': 10, 'adventure:nether_jungle': 5, 'adventure:crimson_pinewood': 5},
                         {'groups': {'geyser_fields': ['adventure:sulphur_springs', 'adventure:quartz_flats'],
                                     'woods': ['minecraft:crimson_forest', 'adventure:nether_jungle', 'adventure:crimson_pinewood']},
                          'arrangement': 'core'})

# ---- Batch 3: End and floating islands
S['the_fractured_halls'] = (None,
                            {'groups': {'crystal_ridges': ['adventure:painted_mountains'],
                                        'groves': ['adventure:lantern_woods', 'adventure:glowing_grasslands'],
                                        'shadowlands': ['adventure:umbral_wastes', 'minecraft:small_end_islands']},
                             'arrangement': 'bands', 'direction': 90})
S['the_burning_archipelago'] = (None,
                                {'groups': {'crimson': ['minecraft:crimson_forest', 'adventure:withered_forest'],
                                            'warped': ['minecraft:warped_forest'],
                                            'deltas': ['minecraft:basalt_deltas', 'adventure:magma_land'],
                                            'wastes': ['minecraft:nether_wastes', 'adventure:ash_barrens']},
                                 'neighbours': [['crimson', 'warped']]})
S['the_shattered_skies'] = (None,
                            {'groups': {'sakura': ['minecraft:cherry_grove', 'adventure:magnolia_woodland'],
                                        'blooming_valley': ['minecraft:flower_forest', 'minecraft:sunflower_plains'],
                                        'meadows': ['minecraft:meadow', 'minecraft:plains'],
                                        'lavender': ['adventure:lavender_fields'],
                                        'skylands': ['adventure:sky_highlands', 'minecraft:forest', 'adventure:golden_skygrove']}})

# ---- Batch 4: caves
S['the_emberglass_foundry'] = ({'minecraft:basalt_deltas': 14, 'adventure:quartz_flats': 6, 'adventure:blackstone_basin': 52,
                                'adventure:magmatic_caves': 10, 'minecraft:sulfur_caves': 6, 'adventure:crystal_hollows': 12},
                               {'groups': {'columns': ['minecraft:basalt_deltas', 'adventure:quartz_flats'],
                                           'vents': ['adventure:magmatic_caves', 'minecraft:sulfur_caves']}})
S['the_underdark'] = (None,
                      {'groups': {'sculk': ['minecraft:deep_dark', 'minecraft:dripstone_caves'],
                                  'mushroom_halls': ['adventure:bioshroom_caves', 'adventure:fungal_fen', 'adventure:lichen_caves'],
                                  'nether_incursion': ['minecraft:crimson_forest', 'minecraft:warped_forest', 'adventure:withered_forest']}})
S['the_sculked_beyond'] = ({'minecraft:deep_dark': 55, 'adventure:mycotoxic_undergrowth': 9, 'adventure:umbral_wastes': 6,
                            'adventure:withered_forest': 3, 'adventure:magmatic_caves': 8, 'adventure:redstone_abyss': 4,
                            'adventure:bioshroom_caves': 5, 'adventure:lichen_caves': 5, 'minecraft:dripstone_caves': 5},
                           {'groups': {'infested': ['adventure:mycotoxic_undergrowth', 'adventure:umbral_wastes', 'adventure:withered_forest'],
                                       'mantle': ['adventure:magmatic_caves', 'adventure:redstone_abyss'],
                                       'fungal_and_tuff': ['adventure:bioshroom_caves', 'adventure:lichen_caves', 'minecraft:dripstone_caves']},
                            'arrangement': 'core'})
S['the_luminous_caverns'] = ({'minecraft:lush_caves': 25, 'adventure:lichen_caves': 10, 'adventure:crystal_hollows': 18,
                              'adventure:quartz_flats': 6, 'adventure:mineral_strata': 7, 'minecraft:dripstone_caves': 7,
                              'minecraft:sulfur_caves': 8, 'adventure:cinnabar_veins': 6, 'adventure:buried_dunes': 4,
                              'minecraft:crimson_forest': 3},
                             {'groups': {'moss': ['minecraft:lush_caves', 'adventure:lichen_caves'],
                                         'crystal': ['adventure:crystal_hollows', 'adventure:quartz_flats'],
                                         'strata': ['adventure:mineral_strata', 'minecraft:dripstone_caves'],
                                         'warm_caves': ['minecraft:sulfur_caves', 'adventure:cinnabar_veins', 'adventure:buried_dunes',
                                                        'minecraft:crimson_forest']},
                              'arrangement': 'core', 'neighbours': [['moss', 'crystal']]})

# ---- Batch 5: overworld presets, worst half
S['the_lost_outpost'] = ({'minecraft:savanna': 52, 'minecraft:savanna_plateau': 8, 'minecraft:windswept_savanna': 8,
                          'minecraft:wooded_badlands': 4, 'adventure:baobab_savanna': 10, 'adventure:prairie': 8,
                          'minecraft:forest': 5, 'adventure:parched_forest': 5},
                         {'groups': {'slopes': ['minecraft:savanna_plateau', 'minecraft:windswept_savanna', 'minecraft:wooded_badlands'],
                                     'golden_scrub': ['adventure:baobab_savanna', 'adventure:prairie'],
                                     'woods': ['minecraft:forest', 'adventure:parched_forest']},
                          'arrangement': 'core'})
S['the_greywoods'] = ({'adventure:sugi_forest': 52, 'minecraft:old_growth_spruce_taiga': 8, 'minecraft:birch_forest': 8,
                       'minecraft:dark_forest': 6, 'minecraft:swamp': 10, 'adventure:fungal_fen': 4, 'minecraft:pale_garden': 7,
                       'adventure:dying_forest': 5},
                      {'groups': {'fir_and_birch': ['minecraft:old_growth_spruce_taiga', 'minecraft:birch_forest', 'minecraft:dark_forest'],
                                  'swamp': ['minecraft:swamp', 'adventure:fungal_fen'],
                                  'moon_grove': ['minecraft:pale_garden', 'adventure:dying_forest']},
                       'arrangement': 'core'})
S['the_wuthering_wisteria'] = ({'adventure:wisteria_grove': 55, 'minecraft:cherry_grove': 25, 'minecraft:meadow': 10,
                                'minecraft:old_growth_birch_forest': 10},
                               {'groups': {'fringe': ['minecraft:meadow', 'minecraft:old_growth_birch_forest']},
                                'arrangement': 'core'})
S['the_souldrift'] = ({'minecraft:soul_sand_valley': 12, 'adventure:ash_barrens': 8, 'minecraft:nether_wastes': 4, 'adventure:umbral_wastes': 4,
                       'adventure:ashen_woodland': 6, 'minecraft:basalt_deltas': 4, 'adventure:blackstone_basin': 6, 'adventure:magma_land': 4,
                       'minecraft:deep_dark': 10, 'adventure:mycotoxic_undergrowth': 5, 'adventure:redstone_abyss': 4,
                       'minecraft:badlands': 10, 'minecraft:eroded_badlands': 8, 'adventure:gravel_desert': 4,
                       'minecraft:windswept_gravelly_hills': 4, 'adventure:chalk_cliffs': 4, 'minecraft:pale_garden': 3},
                      {'groups': {'soul_scar': ['minecraft:soul_sand_valley', 'adventure:ash_barrens', 'minecraft:nether_wastes', 'adventure:umbral_wastes'],
                                  'ashlands': ['adventure:ashen_woodland', 'minecraft:basalt_deltas', 'adventure:blackstone_basin', 'adventure:magma_land'],
                                  'sculk_breach': ['minecraft:deep_dark', 'adventure:mycotoxic_undergrowth', 'adventure:redstone_abyss'],
                                  'scarred_badlands': ['minecraft:badlands', 'minecraft:eroded_badlands', 'adventure:gravel_desert',
                                                       'minecraft:windswept_gravelly_hills'],
                                  'white_cliffs': ['adventure:chalk_cliffs', 'minecraft:pale_garden']},
                       'arrangement': 'core'})
S['the_dustbowl'] = ({'adventure:dry_steppe': 52, 'adventure:prairie': 15, 'minecraft:savanna': 8, 'minecraft:savanna_plateau': 5,
                      'minecraft:desert': 6, 'adventure:joshua_desert': 4, 'adventure:saguaro_desert': 3, 'minecraft:badlands': 2,
                      'adventure:parched_forest': 5},
                     {'groups': {'xeric_plains': ['adventure:prairie', 'minecraft:savanna', 'minecraft:savanna_plateau'],
                                 'desert': ['minecraft:desert', 'adventure:joshua_desert', 'adventure:saguaro_desert', 'minecraft:badlands'],
                                 'wooded_drylands': ['adventure:parched_forest']},
                      'arrangement': 'core'})
S['the_roothold'] = ({'adventure:redwood_grove': 52, 'minecraft:old_growth_spruce_taiga': 12, 'minecraft:taiga': 5,
                      'minecraft:dark_forest': 10, 'minecraft:forest': 6, 'minecraft:swamp': 8, 'minecraft:windswept_forest': 7},
                     {'groups': {'spruce': ['minecraft:old_growth_spruce_taiga', 'minecraft:taiga'],
                                 'dark_oak': ['minecraft:dark_forest', 'minecraft:forest'],
                                 'willow': ['minecraft:swamp'],
                                 'forested_highlands': ['minecraft:windswept_forest']},
                      'arrangement': 'core'})
S['the_ruined_timberland'] = ({'adventure:maple_woodland': 52, 'adventure:aspen_forest': 12, 'minecraft:birch_forest': 6,
                               'minecraft:forest': 10, 'minecraft:dappled_forest': 8, 'minecraft:dark_forest': 6, 'minecraft:pale_garden': 6},
                              {'groups': {'aspen_and_birch': ['adventure:aspen_forest', 'minecraft:birch_forest'],
                                          'clearings': ['minecraft:forest', 'minecraft:dappled_forest'],
                                          'dark_woods': ['minecraft:dark_forest', 'minecraft:pale_garden']},
                               'arrangement': 'core'})
S['the_whispering_wilds'] = ({'adventure:redwood_grove': 18, 'adventure:sugi_forest': 8,
                              'minecraft:old_growth_spruce_taiga': 10, 'minecraft:old_growth_pine_taiga': 8, 'minecraft:taiga': 4,
                              'minecraft:dark_forest': 10, 'minecraft:pale_garden': 4, 'adventure:alpha_grove': 3,
                              'minecraft:old_growth_birch_forest': 8, 'minecraft:birch_forest': 4, 'adventure:aspen_forest': 4, 'minecraft:meadow': 2,
                              'minecraft:swamp': 3, 'minecraft:mangrove_swamp': 2, 'minecraft:jungle': 2, 'minecraft:sparse_jungle': 2,
                              'adventure:cloud_forest': 3, 'minecraft:mushroom_fields': 2},
                             {'groups': {'redwoods': ['adventure:redwood_grove', 'adventure:sugi_forest'],
                                         'old_taigas': ['minecraft:old_growth_spruce_taiga', 'minecraft:old_growth_pine_taiga', 'minecraft:taiga'],
                                         'dark_woods': ['minecraft:dark_forest', 'minecraft:pale_garden', 'adventure:alpha_grove'],
                                         'birchwoods': ['minecraft:old_growth_birch_forest', 'minecraft:birch_forest', 'adventure:aspen_forest',
                                                        'minecraft:meadow'],
                                         'wet_wilds': ['minecraft:swamp', 'minecraft:mangrove_swamp', 'minecraft:jungle', 'minecraft:sparse_jungle',
                                                       'adventure:cloud_forest', 'minecraft:mushroom_fields']},
                              'arrangement': 'core'})
S['the_rosebluff'] = (None,
                      {'groups': {'wisteria': ['adventure:wisteria_grove'],
                                  'magnolia': ['adventure:magnolia_woodland'],
                                  'sakura_and_lavender': ['minecraft:cherry_grove', 'adventure:lavender_fields', 'minecraft:flower_forest'],
                                  'meadow_fringe': ['minecraft:meadow', 'minecraft:birch_forest']}})

# ---- Batch 6
S['the_lantern_pools'] = ({'adventure:desert_oasis': 52, 'minecraft:plains': 20, 'minecraft:sparse_jungle': 12,
                           'minecraft:mangrove_swamp': 8, 'minecraft:jungle': 8},
                          {'groups': {'lush_valleys': ['minecraft:plains', 'minecraft:sparse_jungle'],
                                      'mangrove_fringe': ['minecraft:mangrove_swamp', 'minecraft:jungle']},
                           'arrangement': 'core'})
S['the_chalk_meadows'] = ({'minecraft:meadow': 50, 'adventure:highland_fells': 8, 'adventure:carnation_fields': 10, 'adventure:chalk_cliffs': 6,
                           'minecraft:plains': 10, 'minecraft:sunflower_plains': 2, 'adventure:alpine_meadow': 6,
                           'minecraft:flower_forest': 4, 'adventure:lavender_fields': 4},
                          {'groups': {'heather_and_chalk': ['adventure:highland_fells', 'adventure:carnation_fields', 'adventure:chalk_cliffs'],
                                      'clover_plains': ['minecraft:plains', 'minecraft:sunflower_plains', 'adventure:alpine_meadow'],
                                      'flowers': ['minecraft:flower_forest', 'adventure:lavender_fields']},
                           'arrangement': 'core'})
S['the_miredeep'] = ({'minecraft:swamp': 52, 'adventure:cypress_wetland': 16, 'minecraft:mangrove_swamp': 12, 'adventure:orchid_marsh': 12,
                      'minecraft:dark_forest': 5, 'minecraft:old_growth_birch_forest': 3},
                     {'groups': {'bayou': ['adventure:cypress_wetland', 'minecraft:mangrove_swamp'],
                                 'dark_fringe': ['minecraft:dark_forest', 'minecraft:old_growth_birch_forest']},
                      'arrangement': 'core'})
TEAL_DEFERRED = ({'minecraft:warped_forest': 20, 'minecraft:badlands': 50, 'minecraft:eroded_badlands': 10,
                             'adventure:upside_down_forest': 6, 'minecraft:crimson_forest': 5, 'adventure:maple_woodland': 4,
                             'adventure:ashen_woodland': 3, 'adventure:blackstone_basin': 2},
                            {'groups': {'eroded_mesas': ['minecraft:eroded_badlands', 'adventure:upside_down_forest'],
                                        'rim': ['minecraft:crimson_forest', 'adventure:maple_woodland', 'adventure:ashen_woodland',
                                                'adventure:blackstone_basin']},
                             'arrangement': 'core'})
S['the_claymarsh'] = ({'adventure:cypress_wetland': 52, 'adventure:orchid_marsh': 20, 'minecraft:swamp': 6, 'minecraft:mangrove_swamp': 6,
                       'minecraft:bamboo_jungle': 4, 'minecraft:dark_forest': 4, 'minecraft:old_growth_birch_forest': 3},
                      {'groups': {'swamps': ['minecraft:swamp', 'minecraft:mangrove_swamp', 'minecraft:bamboo_jungle'],
                                  'dark_fringe': ['minecraft:dark_forest', 'minecraft:old_growth_birch_forest']},
                       'arrangement': 'core'})
S['the_soulfields'] = ({'minecraft:soul_sand_valley': 55, 'adventure:ash_barrens': 15, 'minecraft:nether_wastes': 12,
                        'adventure:nether_jungle': 10, 'adventure:withered_forest': 8},
                       {'groups': {'grave_fields': ['adventure:ash_barrens', 'minecraft:nether_wastes'],
                                   'weeping_valley': ['adventure:nether_jungle', 'adventure:withered_forest']},
                        'arrangement': 'core'})
S['the_dripping_pines'] = ({'minecraft:old_growth_pine_taiga': 52, 'minecraft:old_growth_spruce_taiga': 14, 'minecraft:taiga': 10,
                            'adventure:redwood_grove': 12, 'minecraft:windswept_forest': 6, 'minecraft:snowy_taiga': 6},
                           {'groups': {'spruce_and_fir': ['minecraft:old_growth_spruce_taiga', 'minecraft:taiga'],
                                       'rainforest': ['adventure:redwood_grove'],
                                       'pine_slopes': ['minecraft:windswept_forest', 'minecraft:snowy_taiga']},
                            'arrangement': 'core'})
S['the_ashgrove'] = ({'adventure:ashen_woodland': 52, 'minecraft:savanna': 8, 'adventure:baobab_savanna': 6, 'minecraft:windswept_savanna': 4,
                      'adventure:magma_land': 6, 'minecraft:basalt_deltas': 5, 'adventure:blackstone_basin': 4,
                      'minecraft:badlands': 3, 'minecraft:wooded_badlands': 2, 'minecraft:eroded_badlands': 2,
                      'adventure:joshua_desert': 3, 'adventure:saguaro_desert': 2},
                     {'groups': {'savanna': ['minecraft:savanna', 'adventure:baobab_savanna', 'minecraft:windswept_savanna'],
                                 'volcanic': ['adventure:magma_land', 'minecraft:basalt_deltas', 'adventure:blackstone_basin'],
                                 'badlands_and_desert': ['minecraft:badlands', 'minecraft:wooded_badlands', 'minecraft:eroded_badlands',
                                                         'adventure:joshua_desert', 'adventure:saguaro_desert']},
                      'arrangement': 'core'})

EXPANSE_BIOMES = ['minecraft:plains', 'minecraft:forest', 'minecraft:birch_forest', 'minecraft:taiga', 'minecraft:savanna',
                  'minecraft:desert', 'minecraft:dark_forest', 'minecraft:snowy_plains', 'minecraft:swamp',
                  'minecraft:ocean', 'minecraft:deep_ocean', 'minecraft:cold_ocean', 'minecraft:frozen_ocean',
                  'minecraft:lukewarm_ocean', 'minecraft:warm_ocean', 'minecraft:beach', 'minecraft:snowy_beach',
                  'minecraft:river', 'minecraft:frozen_river']
S['the_endless_expanse'] = ({'minecraft:plains': 18, 'minecraft:forest': 17, 'minecraft:birch_forest': 10, 'minecraft:taiga': 10,
                             'minecraft:savanna': 10, 'minecraft:desert': 9, 'minecraft:dark_forest': 8, 'minecraft:snowy_plains': 8,
                             'minecraft:swamp': 6},
                            {'groups': {'plains': ['minecraft:plains'],
                                        'forest': ['minecraft:forest'],
                                        'birch_forest': ['minecraft:birch_forest'],
                                        'taiga': ['minecraft:taiga', 'minecraft:cold_ocean'],
                                        'savanna': ['minecraft:savanna', 'minecraft:lukewarm_ocean'],
                                        'desert': ['minecraft:desert', 'minecraft:warm_ocean'],
                                        'dark_forest': ['minecraft:dark_forest'],
                                        'snowfields': ['minecraft:snowy_plains', 'minecraft:frozen_ocean', 'minecraft:snowy_beach',
                                                       'minecraft:frozen_river'],
                                        'swamp': ['minecraft:swamp']},
                             'at': {'plains': [0, 0], 'dark_forest': [1650, -1650]},
                             'neighbours': [['taiga', 'snowfields']],
                             'apart': [['snowfields', 'desert'], ['snowfields', 'savanna']]})


CENTRE = {'the_forged_depths': 'adventure:crimson_pinewood', 'the_blackstone_keep': 'adventure:blackstone_basin', 'the_gilded_pit': 'adventure:blackstone_basin',
          'the_molten_flats': 'adventure:magma_land', 'the_bloodroot_wastes': 'minecraft:crimson_forest',
          'the_obsidian_sanctum': 'adventure:withered_forest', 'the_boneyard': 'minecraft:soul_sand_valley',
          'the_wailing_narrows': 'minecraft:soul_sand_valley', 'the_ember_fields': 'adventure:nether_grasslands',
          'the_emberglass_foundry': 'columns', 'the_sculked_beyond': 'minecraft:deep_dark', 'the_luminous_caverns': 'moss',
          'the_lost_outpost': 'minecraft:savanna', 'the_greywoods': 'adventure:sugi_forest', 'the_wuthering_wisteria': 'adventure:wisteria_grove',
          'the_souldrift': 'soul_scar', 'the_dustbowl': 'adventure:dry_steppe', 'the_roothold': 'adventure:redwood_grove',
          'the_ruined_timberland': 'adventure:maple_woodland', 'the_whispering_wilds': 'redwoods', 'the_lantern_pools': 'adventure:desert_oasis',
          'the_chalk_meadows': 'minecraft:meadow', 'the_miredeep': 'minecraft:swamp', 'the_teal_corruption': 'minecraft:warped_forest',
          'the_claymarsh': 'adventure:cypress_wetland', 'the_soulfields': 'minecraft:soul_sand_valley',
          'the_dripping_pines': 'minecraft:old_growth_pine_taiga', 'the_ashgrove': 'adventure:ashen_woodland'}
ROLE_WORDS = ('ocean', 'river', 'beach', 'shore')


def first_place(d, places):
    for b in d.get('biomes') or []:
        bid = b if isinstance(b, str) else b['id']
        if not isinstance(b, str) and b.get('climate') is not None:
            continue
        if any(w in bid for w in ROLE_WORDS) or (d.get('biomeLayout', {}).get('roles') or {}).get(bid) in ('underside', 'basin'):
            continue
        for g, m in (places.get('groups') or {}).items():
            if bid in m:
                return g
        return bid
    return None


REORDER = {'the_fractured_halls': ([], ['adventure:umbral_wastes', 'minecraft:small_end_islands'])}

AT_ALL = {}

def ids(d):
    out = {}
    for b in d.get('biomes') or []:
        if isinstance(b, str):
            out[b] = None
        else:
            out[b['id']] = b.get('climate')
    return out


def build(slug):
    weights, places = S[slug]
    d, src = summ.base(slug)
    if slug == 'the_endless_expanse':
        d['biomes'] = list(EXPANSE_BIOMES)
        d['biomeLayout'] = {'mode': 'regions'}
        src += '+biome list'
    if slug == 'the_lantern_pools':
        for b in d['biomes']:
            if isinstance(b, dict) and b['id'] == 'minecraft:warm_ocean' and b['climate'].get('temperature') == [-2.0, 2.0]:
                del b['climate']['temperature']
                src += ' warm_ocean full-range temperature dropped'
    fr, bk = REORDER.get(slug, ([], []))
    if fr or bk:
        def key(b):
            i = b if isinstance(b, str) else b['id']
            return -1 if i in fr else (1 if i in bk else 0)
        d['biomes'] = sorted(d['biomes'], key=key)
        src += ' reordered'
    listed = ids(d)
    bl = d.setdefault('biomeLayout', {'mode': 'regions'})
    problems = []
    if bl.get('mode') != 'regions':
        problems.append('biomeLayout.mode is ' + str(bl.get('mode')))
    if weights is not None:
        w = dict(bl.get('weights') or {})
        w.update(weights)
        bl['weights'] = w
    seen = {}
    for g, members in (places.get('groups') or {}).items():
        for m in members:
            if m not in listed:
                problems.append('%s in %s is not listed' % (m, g))
            elif listed[m] is not None:
                problems.append('%s in %s is bound (has a climate)' % (m, g))
            if m in seen:
                problems.append('%s in two groups' % m)
            seen[m] = g
    regional = [b for b, c in listed.items() if c is None]
    ungrouped = [b for b in regional if b not in seen]
    bound = [b for b, c in listed.items() if c is not None]
    if weights is not None:
        missing = [b for b in regional if b not in bl['weights']]
        if missing:
            problems.append('regional without weight: %s' % missing)
    places = json.loads(json.dumps(places))
    if slug in AT_ALL:
        places.pop('arrangement', None)
        places['at'] = AT_ALL[slug]
        src += ' at-all'
    if places.get('arrangement') == 'core' and slug not in AT_ALL:
        c = CENTRE[slug]
        fp = first_place(d, places)
        if fp != c:
            del places['arrangement']
            places['at'] = {c: [0, 0]}
            src += ' at-centre(first=%s)' % fp
        else:
            src += ' core'
    bl['places'] = places
    os.makedirs(OUT, exist_ok=True)
    with open(OUT + slug + '.json', 'w') as f:
        json.dump(d, f, indent=2, ensure_ascii=False)
        f.write('\n')
    return src, ungrouped, bound, problems


if __name__ == '__main__':
    only = sys.argv[1:] or list(S)
    for s in only:
        src, ung, bnd, pr = build(s)
        print('%-26s base=%-4s ungrouped=%s bound=%s %s' % (s, src, [x.split(':')[1] for x in ung], [x.split(':')[1] for x in bnd],
                                                         ('PROBLEMS ' + '; '.join(pr)) if pr else ''))
