# zonelist.py: the_end's outer-island zone shots. For each zone, the land pixel of its own biome (r2048 step 8 render: biome
# colour from the legend, land where the height image is not empty) with the most such land round it within 3 pixels,
# nearest the zone centre on ties; camera 40 blocks out towards the centre of the world, "open+14" over the island's
# height, facing the spot; alternates round it. -> lists/the_end-zones.json
import json, math
from PIL import Image
R = '/tmp/claude-0/w10r/runs/r3/renders/the_end/-1727347169663540530-r2048'
side = json.load(open(R + '.json'))
bio = Image.open(R + '-biome.png').convert('RGB'); hei = Image.open(R + '-height.png').convert('RGB')
W, H = bio.size; step = side['step']; r = side['radius']; cx0, cz0 = side['centre']
col = {tuple(int(b['colour'][i:i + 2], 16) for i in (1, 3, 5)): b['id'] for b in side['legend']['biomes']}
d = json.load(open('/home/user/minecraft-server-template/mods/custom-dimensions-26/definitions/the_end.json'))
B = bio.load(); Hh = hei.load()
def land(px, pz): return Hh[px, pz] != (0, 0, 0)
out = []
for i, z in enumerate(d['zones']):
    want = z['biomes'][0]; zx, zz = z['centre']
    best = None
    for px in range(W):
        for pz in range(H):
            x, zc = cx0 - r + px * step, cz0 - r + pz * step
            if math.hypot(x - zx, zc - zz) > z['radius'] or col.get(B[px, pz]) != want or not land(px, pz): continue
            n = sum(1 for a in range(-3, 4) for b in range(-3, 4) if 0 <= px + a < W and 0 <= pz + b < H and land(px + a, pz + b) and col.get(B[px + a, pz + b]) == want)
            key = (n, -math.hypot(x - zx, zc - zz))
            if best is None or key > best[0]: best = (key, x, zc)
    if best is None:
        print('zone', i, want, 'no land of its biome'); continue
    (n, _), x, zc = best
    a0 = math.atan2(-x, -zc)
    tries = [{'x': round(x + math.sin(a0 + math.radians(t)) * 40, 1), 'y': 'open+14', 'z': round(zc + math.cos(a0 + math.radians(t)) * 40, 1)} for t in (0, 90, -90, 180, 45, -45)]
    short = want.split(':')[1]
    out.append({'dimension': 'minecraft:the_end', 'name': f'the_end-zone{i + 1}-{short}', **tries[0], 'at': [x + 4, 60, zc + 4], 'radius': 8, 'hold': 6,
                'nightVision': False, 'layer': 'zone', 'zoneBiome': want, 'landPixels': n,
                'why': f'zone {i + 1} ({want}, centre {zx} {zz}): its densest land at {x} {zc} ({n} of 49 pixels round it)', 'alternates': tries[1:]})
    print(i + 1, want, x, zc, n)
json.dump(out, open('/tmp/claude-0/w10s/lists/the_end-zones.json', 'w'), indent=1)
