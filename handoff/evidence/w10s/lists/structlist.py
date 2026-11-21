# structlist.py <fast sidecar json> <slug> [<slug> <sidecar> ...]: one structure shot per structure that starts in the
# dimension (the start nearest its spawn inside the player border), from the fast render's fast.structures.starts boxes.
# Camera: on the approach side (between spawn and the structure), d = half the box's diagonal + 14 (22..70) out,
# y "open+8" (over the floor of the open space at the box's base, never on a roof); alternates round the compass,
# higher (open+16) and nearer. -> /tmp/claude-0/w10s/lists/<slug>-struct.json; prints wanted/forced ids without a start.
import json, math, sys
DEFS = '/home/user/minecraft-server-template/mods/custom-dimensions-26/definitions'
W10R = '/tmp/claude-0/w10r/lists'
OUT = '/tmp/claude-0/w10s/lists'
MAX_PER = int(__import__('os').environ.get('MAX_PER', 1))


def entries(slug, sidecar):
    d = json.load(open(f'{DEFS}/{slug}.json'))
    side = json.load(open(sidecar))
    starts = side['fast']['structures'].get('starts', [])
    picks = json.load(open(f'{W10R}/{slug}-picks.json'))
    dim = picks[0]['dimension']
    pair = bool(picks[0].get('pair'))
    sx, sz = (d.get('spawn') or [0, 0, 0])[0], (d.get('spawn') or [0, 0, 0])[2]
    border = (d.get('borders') or {}).get('player') or 4096
    st = d.get('structures') or {}
    named = set((st.get('wants') or {}).keys()) | {f['structure'] for f in st.get('force') or []} | {i for i in st.get('list') or []}
    best = {}
    for s in starts:
        b = s['box']
        cx, cz = (b[0] + b[3] + 1) / 2, (b[2] + b[5] + 1) / 2
        if max(abs(cx), abs(cz)) > border - 8:
            continue
        dist = math.hypot(cx - sx, cz - sz)
        best.setdefault(s['structure'], []).append((dist, b, cx, cz))
    out = []
    for sid in sorted(best):
        for dist, b, cx, cz in sorted(best[sid])[:MAX_PER]:
            half = math.hypot(b[3] - b[0] + 1, b[5] - b[2] + 1) / 2
            dd = min(70, max(22, half + 14))
            base = math.atan2(sx - cx, sz - cz) if dist > 1 else 0.0
            tries = []
            for k, (rot, n, f) in enumerate([(0, 8, 1), (45, 8, 1), (-45, 8, 1), (90, 8, 1), (-90, 8, 1), (135, 8, 1), (-135, 8, 1),
                                             (180, 8, 1), (0, 16, 1), (90, 16, 1), (180, 16, 1), (-90, 16, 1), (0, 6, 0.7), (180, 6, 0.7)]):
                a = base + math.radians(rot)
                tries.append({'x': round(cx + math.sin(a) * dd * f, 1), 'y': f'open+{n}', 'z': round(cz + math.cos(a) * dd * f, 1),
                              'why': f'bearing {rot:+d} from the approach, {dd * f:.0f} out, {n} over the floor'})
            short = sid.split(':', 1)[1].replace('/', '_')
            e = {'dimension': dim, 'name': f'{slug}-structure-{short}', **tries[0], 'subject': b,
                 'radius': min(10, max(6, math.ceil((dd + half) / 16) + 2)), 'hold': 4, 'nightVision': False, 'pair': pair,
                 'layer': 'structure', 'structure': sid, 'fromSpawn': round(dist), 'alternates': tries[1:]}
            e['why'] = f'start of {sid}, {round(dist)} blocks from spawn; ' + tries[0]['why']
            out.append(e)
    missing = sorted(i for i in named if ':' in i and i not in best and not i.endswith(('_complexes', '_structures', '_monuments', '_cities',
                                                                                      '_lighthouses', '_fossils')))
    return out, missing


if __name__ == '__main__':
    a = sys.argv[1:]
    allout = []
    for i in range(0, len(a), 2):
        slug, sidecar = a[i], a[i + 1]
        out, missing = entries(slug, sidecar)
        json.dump(out, open(f'{OUT}/{slug}-struct.json', 'w'), indent=1)
        allout += out
        print(slug, len(out), 'shots;', 'no start:', missing)
    json.dump(allout, open(f'{OUT}/struct-all.json', 'w'), indent=1)
