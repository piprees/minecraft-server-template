# placeregions.py <render dir> <seed-r prefix> <definition.json> [border radius]: places measured on a /customdim render.
# Biome pixels are merged by place (a group's members are one place; an ungrouped regional biome is its own); land is
# every pixel the height render does not paint void-black. Prints, per place, its share of the border's land and its
# land regions; then islands (4-connected land) and the area-weighted mean share of each island's land in its commonest
# place (dominantPlacePercent), beside the same by biome.
import json, sys
from collections import Counter
from PIL import Image
rdir, prefix, defn = sys.argv[1:4]
R = int(sys.argv[4]) if len(sys.argv) > 4 else None
meta = json.load(open(f'{rdir}/{prefix}.json'))
d = json.load(open(defn))
groups = (d.get('biomeLayout', {}).get('places', {}) or {}).get('groups', {})
member = {b: g for g, bs in groups.items() for b in bs}
col = {}
for e in meta['legend']['biomes']:
    c = e['colour'].lstrip('#'); col[tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))] = e['id']
bi = Image.open(f'{rdir}/{prefix}-biome.png').convert('RGB'); hi = Image.open(f'{rdir}/{prefix}-height.png').convert('RGB')
W, H = bi.size; step = meta['step']; rad = meta['radius']
bp, hp = bi.load(), hi.load()
lab = {}
for y in range(H):
    for x in range(W):
        if R is not None and (abs(-rad + x * step) > R or abs(-rad + y * step) > R): continue
        if max(hp[x, y]) < 8: continue
        b = col.get(bp[x, y])
        if b is None: continue
        lab[(x, y)] = (member.get(b, b), b)
n = len(lab)
def comps(key):
    seen, out = set(), []
    for p in lab:
        if p in seen: continue
        k = key(lab[p]); stack = [p]; seen.add(p); cells = []
        while stack:
            q = stack.pop(); cells.append(q)
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                r = (q[0] + dx, q[1] + dy)
                if r in lab and r not in seen and key(lab[r]) == k: seen.add(r); stack.append(r)
        out.append((k, cells))
    return out
share = Counter(v[0] for v in lab.values())
regs = comps(lambda v: v[0])
ha = (step * step) / 10000
print(f'land pixels {n} ({n * ha:.0f} ha), step {step}')
for p, c in share.most_common():
    rs = sorted((len(cells) for k, cells in regs if k == p), reverse=True)
    print(f'  place {p}: {100 * c / n:.1f}% of land, {sum(1 for r in rs if r >= 0.01 * n)} regions of 1%+, largest {100 * rs[0] / n:.1f}%, specks(<1ha) {100 * sum(r for r in rs if r * ha < 1) / n:.1f}%')
big = sorted((len(c) for k, c in regs), reverse=True)
print(f'place land regions: top3 {100 * sum(big[:3]) / n:.1f}%, major {sum(1 for r in big if r >= 0.01 * n)}, specks {100 * sum(r for r in big if r * ha < 1) / n:.1f}%')
isl = comps(lambda v: 1)
for i, name in ((0, 'place'), (1, 'biome')):
    tot = sum(len(c) for _, c in isl)
    dom = sum(Counter(lab[q][i] for q in c).most_common(1)[0][1] for _, c in isl) / tot
    print(f'islands {len(isl)}: dominant{name.title()}Percent {100 * dom:.1f}')
