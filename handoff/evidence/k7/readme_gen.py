import re, json
LOG = '/tmp/claude-0/k7/compile-final.log'
OUT = '/home/user/minecraft-server-template/.claude/worktrees/agent-aba3eecf95945dad2/mods/custom-dimensions-26/fidelity/drafts/k7/README.md'

field = {}
cur = None
names = {}
for l in open(LOG):
    if l.startswith('== '):
        cur = l[3:].strip()
    m = re.search(r'as (\d+) place\(s\) on the (\w+) axis \((.*?)\), arrangement (\w+)', l)
    if m:
        names[cur] = [x.rsplit(' ', 1)[0].split(':')[-1] for x in m.group(3).split(', ')]
    if 'FIELD' in l:
        sh = [float(x) for x in re.search(r'shares% ([\d. ]+);', l).group(1).split()]
        field[cur] = sh

LARGEST = '`landBiomeRegions.largestPercent` ≥ 50 (one biome is the place), `specksPercent` ≤ 5'
MEET = '`landBiomeRegions.top3Percent` ≥ 60, `major` ≤ 8, `specksPercent` ≤ 5'
ISL = 'on islands `landBiomeRegions` counts islands: judge by each group\'s `biomesPercent` and `islands.dominantBiomePercent` towards 100'

# slug -> (kind, arrangement and the phrase, should move, server check)  ; None arrangement => no draft (kind carries the reason)
R = {
 # Batch 2
 'the_forged_depths': ('one place with a fringe', '`core`, the deep at the centre: "a small brutal deep" over k1\'s central pit layout', LARGEST,
     'magma_land stays bound to depth 0.2+ in every place ("hotter downward"); the forge place is a ring, not spawn (kit gap 1): check `at.spawn` and the crimson_forge site'),
 'the_buried_age': ('deliberately broken', None, None, 'Card: "deliberately drowned; one lava sea is the place". The lava sea is the place; biomes are what the sea leaves. No draft.'),
 'the_blackstone_keep': ('one place with a fringe', '`core`, blackstone at the centre: "bastions and blackstone walls from spawn"', LARGEST,
     'blackstone_basin\'s ground and the bastion near spawn; the wart woods\' trees stay under the roof (W9g had 38/ha in the sky layer)'),
 'the_gilded_pit': ('one place with a fringe', '`at` nether_wastes [0, 0], the rest automatic: "the pit: gravel and wastes" round k1\'s central pit; the gilded ledge (blackstone_basin) is a place on the pit\'s side, not the landing (kit gap 1)',
     LARGEST + '; `at.spawn.biomeEdgeBlocks` ≥ 64 (spawn deep in the wastes)', 'which place the landing ledge stands in; gilded blackstone on the surface'),
 'the_molten_flats': ('one place with a fringe', '`core`, magma at the centre: "flat magma and ash ground at lava level"', LARGEST + '; ash_barrens placed (W9g 0.1%)',
     'the flats are terrain (G1): places only fix the biomes'),
 'the_bloodroot_wastes': ('one place with a fringe', '`core`, crimson at the centre: "crimson fungus at spawn"', LARGEST + '; nether_wastes no longer the largest',
     'crimson nylium ground (netherrack was 53.4%)'),
 'the_obsidian_sanctum': ('one place with a fringe', '`core`, withered forest at the centre: "a sanctum and infernal altar at spawn", "soul ground and withered trees"; the wastes place is on the ring that reaches the border ("forbidden castles near the border")', LARGEST,
     'obsidian on the surface is materials, not places'),
 'the_boneyard': ('one place with a fringe', '`core`, soul sand at the centre: "soul sand and ash under a bone-pale haze"', LARGEST,
     'deep_dark stays bound at depth 0.15+ ("sculk below") in every place; bone on the surface is materials'),
 'the_furnace_halls': ('meeting', 'automatic, `places: {}`, each classic biome one place at the k1 weights: "classic Nether: wastes, basalt, soul sand, crimson"', MEET,
     'structures: "a mega-fortress anchors the far end" is `near_border`, not a place'),
 'the_wailing_narrows': ('one place with a fringe', '`at` soul_sand_valley [0, 0]: "soul sand at the frame"', LARGEST + ' (soul sand 45.2 → 52)',
     'the chasms are terrain (G1)'),
 'the_ember_fields': ('one place with a fringe', '`core`, the meadows at the centre: "nylium meadows, a piglin village and a crimson well" at spawn', LARGEST + '; magma_land and gravel_desert (not meadow) are not listed in k1',
     'sulfur_caves stays bound to depth 0.2+ under every place'),
 # Batch 3
 'the_blighted_maw': ('one place', None, None, 'Card: "a cluster round spawn, emptiness beyond"; no largest target. If the islands read as patchwork, `sectors` gives each biome its share of a radial cluster (kit-gaps 3). No draft.'),
 'the_catalyst_maw': ('deliberately broken', None, None, 'Card: "deliberately chaotic". No draft.'),
 'the_fractured_halls': ('meeting', '`bands` west to east (`direction` 90) in list order (ridges, groves, shadowlands), umbral_wastes and small_end_islands moved to the end of the biome list so the groves band lies between: "moonlight groves have taken the sheltered ground between the ridges"',
     MEET + '; ' + ISL, 'the ridges are terrain (G3, G4): a band is a share of the sky, so check each group\'s land share against its weight'),
 'the_crumbling_reaches': ('deliberately broken', None, None, 'Card: "deliberately broken" (wreckage). No draft.'),
 'the_end_citadel': ('one place', None, None, 'No biome list (the End preset\'s own, on erosion); places need one. Island-aware layout is G3. No draft.'),
 'the_pillared_void': ('one place', None, None, 'Card: "deliberately empty"; two biomes over spires, where land regions are the pillars. No draft.'),
 'the_burning_archipelago': ('meeting', 'automatic, `neighbours` crimson–warped: "the piglins and endermen fought across these straits"', MEET + '; ' + ISL,
     'coverage 0.04: each place\'s land share follows how many islands its cell holds; Nether ground on End bones is G5'),
 'the_pale_reach': ('one place', None, None, 'Card: "one desolate place"; on floating islands the largest region is an island, so places do not reach `largestPercent`. No draft.'),
 'the_starwell': ('one place', None, None, 'Card: one small place (border 256), a few islands. No draft.'),
 'the_shattered_skies': ('meeting', 'automatic, five garden places in the description\'s order, so consecutive ones share a border where they can: "sakura, blooming valley, meadow, lavender"', MEET + '; ' + ISL,
     'hanging_gardens stays the underside of every place; the void share ("slowly winning") is terrain'),
 'the_glacial_drift': ('one place', None, None, 'Card: "one frozen place"; island regions, as the_pale_reach. No draft.'),
 # Batch 4
 'the_emberglass_foundry': ('one place with a fringe', 'automatic: "small, self-contained" (border 256); columns at spawn would need a small heart (kit gap 1)', LARGEST + '; basalt_deltas and magmatic_caves placed (both unplaced at weight 1)',
     'water on the floor (26.6%) is aquifers, not places'),
 'the_underdark': ('meeting', 'automatic, three places: "a meeting of three: sculk, mushroom light, Nether fungus"; sculk and mushroom near equal: "in equal measure"', '`landBiomeRegions.top3Percent` ≥ 60, `specksPercent` ≤ 5',
     'the zone at 900, -1500 keeps deep_dark for the forced ancient city whichever place it falls in'),
 'the_weeping_vault': ('one place', None, None, 'Card: one small dense place, but its dominant ground is a material (blackstone, crying obsidian), not a listed biome; choose the biome first. No draft.'),
 'the_sculked_beyond': ('one place with a fringe', '`at` deep_dark [0, 0]: "a sculk dungeon and camp at spawn", "sculk over everything"', LARGEST + '; deep_dark placed (unplaced at weight 1)',
     '"an ancient city near the border" wants deep_dark there: the centre place reaches the border on some seeds only'),
 'the_luminous_caverns': ('meeting', '`at` moss [0, 0], `neighbours` moss–crystal: "a lush dungeon in moss and glow" at spawn, "lush moss and quartz light"', MEET + '; lush_caves leads instead of sulfur_caves',
     'the frosted caves of 1.21.1 have no listed biome; water on the floor is aquifers'),
 'the_fungal_lanterns': ('meeting, vertical', None, None, 'Card: "crimson (orange) below, warped (teal) above": a meeting by height, which places (x and z only) cannot make; bind warped_forest and upside_down_forest to the ceiling with `climate.depth` (kit-gaps 2). No draft.'),
 'the_slatemouth': ('one place', None, None, 'Void source: no region layout. No draft.'),
 'the_icebound_rift': ('one place', None, None, 'Void source; its biomes are "felt, not seen". No draft.'),
 'the_red_monument': ('one place', None, None, 'Void source, a composed centre and ring of structures. No draft.'),
 'the_tiled_expanse': ('one place', None, None, 'Checkerboard source: no region layout. No draft.'),
 'the_canvas': ('one place', None, None, 'Flat source. No draft.'),
 # Batch 5
 'the_tidepools': ('one place', None, None, 'Its one land biome is windswept_gravelly_hills; the rest are ocean and shore roles, which places do not cut. No draft.'),
 'the_lost_outpost': ('one place with a fringe', '`at` savanna [0, 0]: "a pillager outpost and campsite near spawn on arid savanna"; deciduous woods a fringe place ("Rarely: deciduous forest")', LARGEST + '; wooded_badlands 60.7 → 4',
     'the warm ocean (42.2%) is the land share (k3 terrain), not places'),
 'the_greywoods': ('one place with a fringe', '`at` sugi_forest [0, 0]: "a graveyard in cedar and fir" (sugi stands in for cedar)', LARGEST,
     'dying_forest placed; the manor near the border falls in whichever place reaches it'),
 'the_wuthering_wisteria': ('one place with a fringe', '`at` wisteria_grove [0, 0]: "the torii gate opening on wisteria and blossom"', LARGEST + '; old_growth_birch_forest 52.9 → 10',
     'the stream under the canopy is the river role, not a place'),
 'the_souldrift': ('meeting', '`at` soul_scar [0, 0]: "a graveyard on soul sand and ash" at spawn, the scar inside "scarred badlands"', MEET + '; soul_sand_valley placed',
     'the zone at 540, -420 still lays soul sand over any place; deep_dark is a land place (sculk at the surface) here, and crystal_hollows and cinnabar_veins stay bound below'),
 'the_dustbowl': ('one place with a fringe', '`core`, dry_steppe at the centre: "dry steppe at spawn"', LARGEST + ' (dry_steppe weight 1 → 52)',
     'the farmstead forced at -87, -312 lies 324 blocks out, and should fall in the steppe place (52% of the border, ~417 blocks to its edge as a disc): check it'),
 'the_roothold': ('one place with a fringe', '`at` redwood_grove [0, 0]: "old-growth giants and roots (redwood ...) closing overhead"', LARGEST + '; swamp 25.9 → 8',
     'the roots underground are cave biomes, still bound'),
 'the_ruined_timberland': ('one place with a fringe', '`at` maple_woodland [0, 0]: "a forest ruin and pumpkin ruins in autumn colour"', LARGEST + '; forest 45.9 → 10',
     'the ocean biome on dry land (60.2%) is the k3 terrain\'s (sea at 44), not places\''),
 'the_whispering_wilds': ('meeting', '`at` redwoods [0, 0]: "an abandoned camp under redwoods"', MEET + ' (18 biomes in five places); redwood_grove and sugi_forest placed',
     'redwood_grove weight 1.8 → 18, the wet six (swamps, jungles, cloud forest, mushroom fields) 70.9 → 14: check the trees per hectare'),
 'the_rosebluff': ('meeting', 'automatic at the head weights ("wisteria and magnolia lead: right")', MEET,
     'chalk_cliffs stays bound (white bluffs are its climate box and terrain); "villages near the border"'),
 # Batch 6
 'the_lantern_pools': ('one place with a fringe', '`at` desert_oasis [0, 0]: "a turquoise oasis pool ringed with grass and palms"', LARGEST + '; desert_oasis placed (weight 2.3, unplaced)',
     'warm_ocean\'s full-range temperature box is dropped (a box on the place axis is refused; [-2, 2] bound nothing): it stays bound on continentalness'),
 'the_chalk_meadows': ('one place with a fringe', '`at` meadow [0, 0]: "a village in a hollow among heather and carnations", "rolling downs"', LARGEST + '; heather (highland_fells) and carnation placed together',
     'chalk on slopes: chalk_cliffs is a land place member here, so it shows in one place only'),
 'the_miredeep': ('one place with a fringe', '`at` swamp [0, 0]: "swamps, bayou, orchid swamp, marsh, mangrove"', LARGEST + '; dark_forest placed',
     'the ocean and beach (a third of the map) are the land share, not places'),
 'the_teal_corruption': ('one place, composed round the centre', None, None, 'No draft: a 20% teal heart inside 50% red rock cannot be held at the centre (kit gap 1, measured: badlands took the centre under `core` and under `at` for every place). The 420-block zone keeps the teal centre.'),
 'the_claymarsh': ('one place with a fringe', '`at` cypress_wetland [0, 0]: "reed beds and a mangrove hut in still water over clay"', LARGEST,
     'river, muddy_river and beach stay roles; clay ground is materials'),
 'the_soulfields': ('one place with a fringe', '`core`, soul sand at the centre: "an empty soul-sand plain"; the grave fields round it ("the graves begin some way out")', LARGEST,
     'the graves thickening outward are structures by distance'),
 'the_dripping_pines': ('one place with a fringe', '`at` old_growth_pine_taiga [0, 0]: "a foraging outpost under dripping pines"', LARGEST + '; snowy_taiga 36.5 → 6',
     'moss on the ground is materials'),
 'the_ashgrove': ('one place with a fringe', '`at` ashen_woodland [0, 0]: "ashen woodland and savanna at spawn"', LARGEST + '; magma_land and blackstone_basin a 15% volcanic place, not the ground',
     'grass on an "ash-grey" savanna is materials'),
 'the_amplified_reaches': ('noise (frontier)', None, None, 'Card: "a frontier of vertical land; vanilla biomes as broad regions"; no biome list, target `top3Percent` ≥ 25 at vanilla scale. No draft.'),
 'the_endless_expanse': ('meeting', 'nine one-biome places (a group per land biome, with its own ocean, beach or river where vanilla has one), `at` plains [0, 0] ("a village and farmstead near spawn in one huge biome") and dark_forest [1650, -1650] ("a mansion at the far edge"), `neighbours` taiga–snowfields, `apart` snowfields–desert and snowfields–savanna',
     '`landBiomeRegions.largestPercent` ≥ 15, `major` ≤ 10, `specksPercent` ≤ 3 ("each seen to the horizon")',
     'the draft adds a biome list (the head has none and lays the large_biomes preset\'s own); peaks take their place\'s biome'),
}

BATCHES = {}
for l in open('/tmp/claude-0/k7/slugs.txt'):
    b, s = l.split()
    BATCHES.setdefault(b, []).append(s)

TITLES = {'2': 'Batch 2 — Nether presets', '3': 'Batch 3 — End presets and floating islands', '4': 'Batch 4 — caves and void or special generators',
          '5': 'Batch 5 — overworld presets, worst half', '6': 'Batch 6 — overworld presets, better half and the two vanilla presets'}

defs = '/home/user/minecraft-server-template/.claude/worktrees/agent-aba3eecf95945dad2/mods/custom-dimensions-26/fidelity/drafts/k7/'


def places_text(s):
    import os
    d = json.load(open(defs + s + '.json'))
    groups = d['biomeLayout']['places'].get('groups') or {}
    out = []
    for n, share in zip(names[s], field[s]):
        if n in groups and not (len(groups[n]) == 1 and groups[n][0].split(':')[1] == n):
            mem = ', '.join(m.split(':')[1] for m in groups[n])
            out.append('%s (%s) %.0f' % (n, mem, share))
        else:
            out.append('%s %.0f' % (n.split(':')[-1], share))
    return '; '.join(out)


lines = ['# K7 drafts: places for Batches 2–6', '',
         'Each draft is its base with `biomeLayout.places` added: the k1, k2 or k3 draft where one exists (its top-level keys over `definitions/<slug>.json`), else the definition. Apply one by copying it over `definitions/<slug>.json`; it replaces the earlier draft for that slug. Where a draft also re-draws `biomeLayout.weights`, the row says so: a place\'s share is its weight, so the weights come from the description\'s "Mostly" and "Rarely". Three drafts touch `biomes`: the_fractured_halls (order), the_lantern_pools (one full-range box dropped), the_endless_expanse (a list added).',
         '',
         'Kinds. **Meeting**: a few named places. **One place with a fringe**: one biome holds 50–55% of the border as one region, which the one-place target (`landBiomeRegions.largestPercent` ≥ 50) asks for and noise regions do not give (W9g baselines on the intent cards: largest 6–29%); the rest of the list makes two to four fringe places. **One place**, **deliberately broken**, **noise**: no draft, for the reason in the row.',
         '',
         'Figures. Shares are the compiled place field sampled on a 128-cell grid over the smaller of the player and generation borders, with the unit tests\' stand-in warp noise at seed 20261022 (`/tmp/claude-0/k7/dbg/`, K7Drafts): the server\'s noise moves the edges, not the shares. In every draft each place is one region of 1% or more and specks are 0.00%; every `neighbours` and `apart` pair held, and every `at` site lies in its own place. The full set (83 definitions, 34 drafts in place) compiles in 3.7 s. A group\'s biomes mix inside its place on humidity, and count as separate biome regions in the survey. Survey each with `/customdim survey <slug> 1 1`: read `placeness.landBiomeRegions` and `biomesPercent`, and on sky worlds `islands.dominantBiomePercent`.',
         '',
         'Gaps the descriptions show and the layout cannot express: `/tmp/claude-0/k7/kit-gaps.md` (a small heart held at the centre; places by height).',
         '']
for b in sorted(BATCHES):
    lines += ['## ' + TITLES[b], '', '| Dimension | Kind | Places (share %) | Arrangement, from the description | Should move | Check in a server |', '| --- | --- | --- | --- | --- | --- |']
    for s in BATCHES[b]:
        kind, arr, move, check = R[s]
        if arr is None:
            lines.append('| %s | %s | — | — | — | %s |' % (s, kind, check))
        else:
            lines.append('| %s | %s | %s | %s | %s | %s |' % (s, kind, places_text(s), arr, move, check))
    lines.append('')
open(OUT, 'w').write('\n'.join(lines))
missing = [s for b in BATCHES for s in BATCHES[b] if s not in R]
print('rows', sum(len(v) for v in BATCHES.values()), 'missing', missing)
