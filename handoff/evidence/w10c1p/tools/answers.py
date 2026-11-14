# answers.py <slug> <facts.json> <shot list.json> <screens dir...> --hand hand.json --out answers.md
# Review answers that cite facts rows and dossier keys. Q3 and Q4 are computed from the facts; Q1, the description
# part of Q2 and Q5 come from the reviewer's hand notes (hand.json: {slug: {q1, q2, q5score, q5why}}), which must
# cite rows themselves.
import json, os, re, sys

slug, facts_p, list_p = sys.argv[1:4]
args = sys.argv[4:]
hand_p = args[args.index('--hand') + 1]; out = args[args.index('--out') + 1]
old_p = args[args.index('--old') + 1] if '--old' in args else None
screens = [a for a in args[:args.index('--hand')]]
F = json.load(open(facts_p)); hand = json.load(open(hand_p)).get(slug, {})
root = '/home/user/minecraft-server-template'
old = json.load(open(old_p or f'{root}/config/custom-dimensions/dimensions/{slug}.json'))
desc = (old.get('description') or '').lower()
m, sc = F['measures'], F['scorecard']
D = json.load(open(open(os.path.join(os.path.dirname(facts_p), 'dossier.path')).read().strip()))
grass = D['surface']['groundBlocksPercent'].get('minecraft:grass_block', 0.0)


def pct(v):
    return 'n/a' if v is None else f'{v}%'


# description word -> (test on measures, statement)
CHECKS = [
    (r'ash|burnt|scorch|charred|barren|baked|desert|arid|wasteland|gravel', lambda: (m['snowIcePercent'] or 0) > 10 or grass > 30,
     lambda: f"snow or ice on {pct(m['snowIcePercent'])} of the surface (M:snow) and grass_block {grass}% of the ground (`surface.groundBlocksPercent`)"),
    (r'snow|frozen|ice|winter|frost|tundra|glacier', lambda: (m['snowIcePercent'] or 0) < 5, lambda: f"snow or ice on {pct(m['snowIcePercent'])} of the surface (M:snow)"),
    (r'lava|volcan|magma|molten|infern', lambda: (m['lavaPercent'] or 0) < 1, lambda: f"lava on {pct(m['lavaPercent'])} of the surface (M:lava)"),
    (r'ocean|sea\b|shallow|lagoon|archipelago|island|flood|swamp|marsh|lake', lambda: (m['waterPercent'] or 0) < 10 and (m['voidPercent'] or 0) < 10, lambda: f"water on {pct(m['waterPercent'])} of the surface (M:water)"),
    (r'jungle|dense|forest|woods|grove|canopy|overgrown|pines', lambda: (m['canopyPercent'] or 0) < 15, lambda: f"tree canopy over {pct(m['canopyPercent'])} of the surface (M:canopy)"),
    (r'mountain|peak|spire|tall|cliff|jagged|highland|crag|pinnacle', lambda: (m['reliefMedian'] or 0) < 30, lambda: f"median relief {m['reliefMedian']} blocks a patch (M:relief)"),
    (r'flat|plain|meadow|rolling|prairie|steppe|field', lambda: (m['reliefMedian'] or 0) > 40, lambda: f"median relief {m['reliefMedian']} blocks a patch (M:relief)"),
    (r'void|floating|sky island|islands in the sky|abyss', lambda: m['layerColumnsPercent'].get('sky', 0) < 5 and (m['voidPercent'] or 0) < 5, lambda: f"sky layer in {m['layerColumnsPercent'].get('sky')}% of columns and void {pct(m['voidPercent'])} (M:layers, M:void)"),
    (r'cave|cavern|underground|subterr|buried|depths', lambda: m['layerColumnsPercent'].get('roof', 0) < 5 and (m['ceiling'] is False), lambda: f"roof in {m['layerColumnsPercent'].get('roof')}% of columns, ceiling {m['ceiling']} (M:layers, M:classes)"),
]
lines = [f'# {slug} — review answers', '',
         f"Evidence: the facts table in `review.md` (O/V/M/S rows, from `{os.path.dirname(facts_p)}` and the W9c final survey), the shots' frame census. "
         'Every statement cites a row or a dossier key.', '']
lines += ['1. **Do the shots show the dominant character?** ' + hand.get('q1', '(not answered)'), '']
lines += ['2. **Unsupported claims.** The shot captions here are the census captions, built from each frame\'s rays, so they carry no claim the census does not. '
          + hand.get('q2', ''), '']
miss = [r for r in F['oldBiomes'] if r['state'] != 'expressed']
if F['oldBiomes']:
    q3 = f"{len(miss)} of {len(F['oldBiomes'])} 1.21.1 biomes are not expressed (S:b {pct(sc['b'])}): " + '; '.join(
        f"{r['row']} {r['old'].split(':')[-1]} -> {(r['v2'] or '-').split(':')[-1]}: {r['state']}" + (f" ({r['flagReason']})" if r['flagReason'] else '') for r in miss) + '.'
    absent = [r for r in miss if r['state'] in ('listed, not found', 'not in v2 definition', 'no v2 id')]
    q3 += f" Absent outright: {', '.join(r['row'] for r in absent) or 'none'}; the rest are placed with a flag."
else:
    q3 = 'The 1.21.1 definition lists no biomes (a base world), so there is no list to compare (S:a, S:b n/a).'
if F['v2Only']:
    q3 += ' Placed v2 biomes no 1.21.1 biome maps to: ' + ', '.join(f"{r['row']} {r['v2'].split(':')[-1]} {r['surfacePercent']}%" for r in F['v2Only'][:6]) + '.'
lines += ['3. **1.21.1 elements absent from the dossier.** ' + q3, '']
con = []
for rx, test, say in CHECKS:
    w = re.search(r'\b(' + rx + r')\w*', desc)
    if w and test():
        con.append(f"the description says \"{w.group(0)}\" but {say()}")
for layer, v in sc['d'].items():
    if not v['present']:
        con.append(f"the 1.21.1 type `{F['intent']['type']}` implies a {layer} layer, absent ({v['percent']}% of columns, S:d)")
flagged, seen = [], set()
for r in F['oldBiomes'] + F['v2Only']:
    if r['flagReason'] and r['surfacePercent'] >= 5 and r['v2'] not in seen:
        seen.add(r['v2']); flagged.append(r)
for r in flagged:
    con.append(f"{r['row']} {r['v2'].split(':')[-1]} ({r['surfacePercent']}% of the surface) is flagged {r['flagReason']}")
lines += ["4. **Measured elements that contradict the intent.** (Literal checks: a description word that names another place still fires.) " + ('; '.join(con) + '.' if con else 'None found by the checks (description words against M rows, S:d, flags on 5%+ biomes).'), '']
lines += [f"5. **Fidelity: {hand.get('q5score', '?')}/5** — " + hand.get('q5why', '(not answered)'), '']
open(out, 'w').write('\n'.join(lines))
print(out)
