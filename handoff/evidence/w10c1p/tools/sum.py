# sum.py <dossier.json>...: the numbers this pass steers by, one block per dossier.
import json, sys
def g(d, k):
    for p in k.split('.'):
        if not isinstance(d, dict) or p not in d: return None
        d = d[p]
    return d
for f in sys.argv[1:]:
    d = json.load(open(f)); P = d.get('placeness', {})
    print('==', f)
    print(' void', g(d,'surface.voidPercent'), 'water', g(d,'surface.fluidCoverPercent.water'), 'lava', g(d,'surface.fluidCoverPercent.lava'), 'snowIce', g(d,'surface.snowIcePercent'), 'bare', g(d,'surface.bareRockPercent'), 'layers', g(d,'vertical.layerColumnsPercent'))
    print(' relief', g(d,'terrain.reliefMedian'), 'h', {k: g(d,'terrain.surfaceHeight.'+k) for k in ('p5','p50','p95')}, 'cliff', g(d,'terrain.slopes.cliffPercent'), 'trees/ha', g(d,'vegetation.treesPerHectareByLayer'))
    print(' landforms', g(d,'landforms.sharesPercent'))
    print(' biomes', {k.split(':')[1]: v for k, v in sorted((g(d,'surface.biomesPercent') or {}).items(), key=lambda x: -x[1])})
    print(' ground', {k.split(':')[1]: v for k, v in sorted((g(d,'surface.groundBlocksPercent') or {}).items(), key=lambda x: -x[1])[:8]})
    for k in ('biomeRegions', 'landBiomeRegions', 'landformRegions'):
        r = P.get(k) or {}
        print(' ', k, {x: r.get(x) for x in ('largestPercent', 'top3Percent', 'major', 'perKm2', 'specksPercent')})
    print('  islands', P.get('islands', {}).get('count'), P.get('islands', {}).get('sizesHa'), 'dom', P.get('islands', {}).get('dominantBiomePercent'))
    print('  placeBiomes', {k.split(':')[1]: v for k, v in sorted((P.get('biomesPercent') or {}).items(), key=lambda x: -x[1])})
    be = d.get('biomeExpression', {})
    print('  expressed', be.get('expressed'), 'listedNotFound', be.get('listedNotFound'), 'flags', {k.split(':')[1]: v.get('flag') or v.get('flagReason') for k, v in (be.get('biomes') or {}).items() if v.get('flag') or v.get('flagReason')})
