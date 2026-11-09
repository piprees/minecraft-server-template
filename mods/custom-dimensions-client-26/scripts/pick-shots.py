#!/usr/bin/env python3
"""pick-shots.py — a rig shot list for one dimension from its survey dossier.

Context: template-only rig tooling for mods/custom-dimensions-client-26 (stdlib only). Reads the dossier.json that
`/customdim survey <slug>` writes (<server run>/customdim-survey/<slug>/) and writes a list for `scripts/rig.sh biomes`:
  far          from the coarse map's summit (places.vista), 40 over the surface, facing the point across its largest drop;
  <layer>-tN   one shot per place type of each layer (surface, sky, underground, roof), ordered by share plus
               distinctness, at the type's representative camera (1.6 above the ground or a liquid's top on the
               surface and sky layers, a clear cave cell underground, under the roof for the roof layer);
  <layer>-lmN  each landmark (a cell far from every type);
  feature-<id> one per notable cluster type, rarest first (the fewest patches, still in two or more), 6-16 blocks
               from the cluster and facing it.
Every camera is checked again by the rig's clear-camera guard before its screenshot.

Usage: pick-shots.py <dossier.json> <out.json> [--features N] [--landmarks N]
Gotchas: night vision is set for underground shots unless the dimension has a ceiling (a ceiling's fog turns white
under night vision); y is the feet height the survey measured, so give the rig the same world.
"""
import json
import sys


def short(block):
    return block.split(':', 1)[-1]


def pick(d, features=6, landmarks=3):
    dim = d['dimension']
    slug = dim.split(':', 1)[-1]
    ceiling = bool(d.get('dimensionType', {}).get('ceiling'))
    out = []
    vista = d.get('places', {}).get('vista')
    if vista:
        sx, sy, sz = vista['summit']
        out.append({'dimension': dim, 'name': f'{slug}-far', 'x': sx + 0.5, 'y': 'surface+40', 'z': sz + 0.5,
                    'at': vista['lookAt'], 'radius': 24, 'hold': 45, 'why': 'vista: summit of the coarse map, facing its largest drop'})
    for layer in ('surface', 'sky', 'underground', 'roof'):
        info = d.get('places', {}).get(layer) or {}
        types = sorted(info.get('types', []), key=lambda t: -(t['sharePercent'] / 100 + t['distinctness']))
        nv = layer == 'underground' and not ceiling
        for t in types:
            coords = t.get('coordinates') or []
            if not coords:
                continue
            c = coords[0]
            out.append(shot(dim, f"{slug}-{layer}-t{t['type']}", c, nv, f"{layer} type {t['type']}: {t['description']}"))
        for i, lm in enumerate(info.get('landmarks', [])[:landmarks], 1):
            if lm.get('camera'):
                out.append(shot(dim, f'{slug}-{layer}-lm{i}', lm, nv, f"{layer} landmark: {lm['description']}"))
    clusters = d.get('clusters', {}).get('types', {})
    ranked = sorted(clusters.items(), key=lambda kv: (-kv[1]['distinctness'], kv[1]['clusters']))
    n = 0
    for block, t in ranked:
        if n >= features:
            break
        places = t.get('places') or []
        if not places:
            continue
        p = places[0]
        cam = p['camera']
        nvf = p['layer'] == 'underground' and not ceiling
        out.append({'dimension': dim, 'name': f'{slug}-feature-{short(block)}', 'x': cam[0], 'y': str(cam[1]), 'z': cam[2],
                    'at': p['at'], 'radius': 8, 'hold': 10, 'nightVision': nvf,
                    'why': f"{short(block)}: {t['clusters']} clusters in {t['patches']} patches, median {t['sizeP50']} blocks, {p['layer']} layer"})
        n += 1
    return out


def shot(dim, name, place, nv, why):
    cam = place['camera']
    return {'dimension': dim, 'name': name, 'x': cam[0], 'y': str(cam[1]), 'z': cam[2], 'yaw': place.get('yaw', 0),
            'pitch': place.get('pitch', 5), 'radius': 8, 'hold': 10, 'nightVision': nv, 'why': why}


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    features, landmarks = 6, 3
    args = argv[3:]
    while args:
        if args[0] == '--features':
            features = int(args[1])
        elif args[0] == '--landmarks':
            landmarks = int(args[1])
        else:
            print('unknown argument', args[0], file=sys.stderr)
            return 2
        args = args[2:]
    with open(argv[1]) as f:
        d = json.load(f)
    out = pick(d, features, landmarks)
    with open(argv[2], 'w') as f:
        json.dump(out, f, indent=1)
    print(f"{len(out)} shots -> {argv[2]}")
    for s in out:
        print(f"  {s['name']}: {s['why'][:150]}")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
