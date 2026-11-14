#!/usr/bin/env python3
"""lane-read.py <lane out dir> — K10's pass bars over lane.sh's self-test report and four dossiers; one PASS/FAIL line each."""
import json
import sys
from pathlib import Path

out = Path(sys.argv[1])
ok = True


def verdict(cond, what):
    global ok
    print(('PASS ' if cond else 'FAIL ') + what)
    ok = ok and bool(cond)


def dossier(slug):
    p = out / 'full' / slug / 'dossier.json'
    return json.loads(p.read_text()) if p.exists() else None


report = out / 'selftest.txt'
lines = report.read_text().splitlines() if report.exists() else []
roofed = [l for l in lines if 'terrain roofed biomes' in l]
verdict(roofed and roofed[0].startswith('PASS'), 'self-test: ' + (roofed[0] if roofed else 'no roofed biomes line'))
fails = [l for l in lines if l.startswith('FAIL')]
verdict(lines and not fails, f'self-test: {sum(l.startswith("PASS") for l in lines)} PASS, {len(fails)} FAIL' + ('; ' + fails[0][:200] if fails else ''))

# Gap 1: the lava sea is the ocean role's; the land biomes stand on the dry floor.
d = dossier('the_buried_age')
if d is None:
    verdict(False, 'the_buried_age: no dossier')
else:
    b = d['biomeExpression']['biomes']
    sea = b.get('minecraft:basalt_deltas', {}).get('placementPercent', {}).get('lava')
    verdict(sea is not None and sea >= 50, f'the_buried_age: basalt deltas (the sea) {sea}% of its columns under lava (bar 50+)')
    for biome, e in b.items():
        if biome == 'minecraft:basalt_deltas' or 'placementPercent' not in e:
            continue
        lava = e['placementPercent']['lava']
        verdict(lava <= 25, f'the_buried_age: {biome} {lava}% under lava (bar 25 or less; 1.21.1-era layout drowned 46-90%), {e["state"]}')

# Gap 2: the far view and the surface cameras stand under the roof.
d = dossier('the_blackstone_keep')
if d is None:
    verdict(False, 'the_blackstone_keep: no dossier')
else:
    v = d['places'].get('vista')
    top = d['dimensionType']['minY'] + 128
    verdict(v is not None and v['summit'][1] + 12 + 1.62 < top - 1 and 'open under the roof' in v['note'],
            f'the_blackstone_keep: vista {v and v["summit"]} far camera y {v and v["summit"][1] + 12} under the roof at {top - 1} ({v and v["note"]})')
    cams = [c['camera'] for t in d['places']['surface']['types'] for c in t.get('coordinates', []) if c.get('camera')]
    cams += [lm['camera'] for lm in d['places']['surface']['landmarks'] if lm.get('camera')]
    over = [c for c in cams if c[1] >= top - 1]
    verdict(cams and not over, f'the_blackstone_keep: {len(cams)} surface cameras, {len(over)} on or over the roof (bar 0)')

# Gap 3: the dimension's fog leads its timelines.
d = dossier('the_obsidian_sanctum')
if d is None:
    verdict(False, 'the_obsidian_sanctum: no dossier')
else:
    tl = d['atmosphere'].get('registeredType', {}).get('timelines')
    first = tl[0] if isinstance(tl, list) and tl else tl
    verdict(first == 'adventure:atmosphere/the_obsidian_sanctum', f'the_obsidian_sanctum: registered timelines {tl} (bar: the atmosphere timeline first)')

# Gap 4: deep dark under the battlefield, not on it.
d = dossier('the_boneyard')
if d is None:
    verdict(False, 'the_boneyard: no dossier')
else:
    b = d['biomeExpression']['biomes']
    total = sum(e.get('columns', 0) for e in b.values())
    surface = 100.0 * b.get('minecraft:deep_dark', {}).get('columns', 0) / max(1, total)
    cave = d['underground'].get('caveBiomesPercent', {}).get('minecraft:deep_dark', 0)
    verdict(surface <= 1, f'the_boneyard: deep dark on {surface:.1f}% of the surface columns (bar 1 or less)')
    verdict(cave >= 5, f'the_boneyard: deep dark {cave}% of the cave biome samples (bar 5+)')
print('ALL PASS' if ok else 'SOME FAIL')
