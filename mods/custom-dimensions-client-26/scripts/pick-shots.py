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
               from the cluster and facing it; structure-<id> when the block is crafted (bricks, cut, waxed,
               chiseled, polished, chains, lanterns), so the frame is labelled as a structure.
Every shot carries 2-3 alternates for the rig's frame-quality gate (README § Frame-quality gate), tried in order when a
frame is rejected: other cells of the same place type or other places of the same cluster first, then other angles
(the camera turned 90, 180 and 270 degrees). The far view's alternates face inward, towards the border centre (0, 0),
then 90 degrees either side. Every camera is checked again by the rig's clear-camera guard before its screenshot.

Usage: pick-shots.py <dossier.json> <out.json> [--features N] [--landmarks N] [--max-per-layer N]
  --max-per-layer keeps each layer's N highest-scoring types (default all).
Gotchas: night vision lights underground and roof shots, and every shot of a dimension without skylight or held at
night (a definition fixedTime of 13000-23000), unless the
dimension is fog-bound: its registered type's visual attributes or skybox name the nether, its visual attributes fix a
fog end distance, or its definition's effects are minecraft:the_nether (that fog turns to a white wall under night
vision, T180); a ceiling dimension's far view
is 12 over the summit, not over the client's heightmap (the roof). y is the feet height the survey measured, so give
the rig the same world.
"""
import json
import math
import re
import sys

CRAFTED = re.compile(r'waxed_|cut_|_bricks?$|chiseled_|polished_|chain|lantern|_planks$|_slab$|_stairs$|trial_|vault|spawner|copper_(bulb|grate|door|trapdoor)')
ALTERNATES = 3


def short(block):
    return block.split(':', 1)[-1]


def pick(d, features=6, landmarks=3, per_layer=None):
    dim = d['dimension']
    slug = dim.split(':', 1)[-1]
    ceiling = bool(d.get('dimensionType', {}).get('ceiling'))
    atmosphere = d.get('atmosphere', {})
    fixed = atmosphere.get('definitionAtmosphere', {}).get('fixedTime')
    dark = not atmosphere.get('hasSkyLight', True) or (fixed is not None and 13000 <= fixed % 24000 <= 23000)
    registered = atmosphere.get('registeredType', {})
    visual = {k: v for k, v in registered.get('attributes', {}).items() if k.startswith('minecraft:visual/')}
    nether_fog = ('nether' in json.dumps([visual, registered.get('skybox')]) or 'minecraft:visual/fog_end_distance' in visual
                  or atmosphere.get('definitionAtmosphere', {}).get('effects') == 'minecraft:the_nether')
    out = []
    vista = d.get('places', {}).get('vista')
    if vista:
        sx, sy, sz = vista['summit']
        out.append({'dimension': dim, 'name': f'{slug}-far', 'x': sx + 0.5, 'y': str(sy + 12) if ceiling else 'surface+40', 'z': sz + 0.5,
                    'nightVision': dark and not nether_fog,
                    'at': vista['lookAt'], 'radius': 24, 'hold': 45, 'why': 'vista: summit of the coarse map, facing its largest drop',
                    'alternates': inward(vista)})
    for layer in ('surface', 'sky', 'underground', 'roof'):
        info = d.get('places', {}).get(layer) or {}
        types = sorted(info.get('types', []), key=lambda t: -(t['sharePercent'] / 100 + t['distinctness']))[:per_layer]
        nv = (dark or layer in ('underground', 'roof')) and not nether_fog
        for t in types:
            coords = t.get('coordinates') or []
            if not coords:
                continue
            c = coords[0]
            s = shot(dim, f"{slug}-{layer}-t{t['type']}", c, nv, f"{layer} type {t['type']}: {t['description']}")
            s['alternates'] = alternates(s, [camera(o) for o in coords[1:]])
            out.append(s)
        for i, lm in enumerate(info.get('landmarks', [])[:landmarks], 1):
            if lm.get('camera'):
                s = shot(dim, f'{slug}-{layer}-lm{i}', lm, nv, f"{layer} landmark: {lm['description']}")
                s['alternates'] = alternates(s, [])
                out.append(s)
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
        nvf = (dark or p['layer'] in ('underground', 'roof')) and not nether_fog
        kind = 'structure' if CRAFTED.search(short(block)) else 'feature'
        s = {'dimension': dim, 'name': f'{slug}-{kind}-{short(block)}', 'x': cam[0], 'y': str(cam[1]), 'z': cam[2],
             'at': p['at'], 'radius': 8, 'hold': 10, 'nightVision': nvf,
             'why': (f"{short(block)}{' (crafted: a structure)' if kind == 'structure' else ''}: {t['clusters']} clusters in "
                     f"{t['patches']} patches, median {t['sizeP50']} blocks, {p['layer']} layer")}
        others = [{'x': q['camera'][0], 'y': str(q['camera'][1]), 'z': q['camera'][2], 'at': q['at']} for q in places[1:]]
        s['alternates'] = alternates(s, others)
        out.append(s)
        n += 1
    return out


def camera(place):
    cam = place['camera']
    return {'x': cam[0], 'y': str(cam[1]), 'z': cam[2], 'yaw': place.get('yaw', 0), 'pitch': place.get('pitch', 5)}


def alternates(s, others):
    """Other places first, then the shot's own camera turned 90, 180 and 270 degrees; ALTERNATES of them."""
    out = list(others[:ALTERNATES])
    if s.get('at'):
        x, z = s['x'], s['z']
        ax, ay, az = s['at']
        dx, dz = ax + 0.5 - x, az + 0.5 - z
        for turn in (180, 90, 270):
            if len(out) >= ALTERNATES:
                break
            r = math.radians(turn)
            out.append({'at': [math.floor(x + dx * math.cos(r) - dz * math.sin(r)), ay, math.floor(z + dx * math.sin(r) + dz * math.cos(r))]})
    else:
        for turn in (180, 90, 270):
            if len(out) >= ALTERNATES:
                break
            out.append({'yaw': (s.get('yaw', 0) + turn) % 360, 'pitch': s.get('pitch', 5)})
    return out


def inward(vista):
    """The far view facing the border centre from the summit, then 90 degrees either side of that."""
    sx, sy, sz = vista['summit']
    ly = vista['lookAt'][1]
    d = math.hypot(sx, sz)
    ux, uz = (-sx / d, -sz / d) if d > 1 else (1.0, 0.0)
    out = []
    for turn in (0, 90, -90):
        r = math.radians(turn)
        vx, vz = ux * math.cos(r) - uz * math.sin(r), ux * math.sin(r) + uz * math.cos(r)
        out.append({'at': [round(sx + vx * 160), ly, round(sz + vz * 160)]})
    return out


def shot(dim, name, place, nv, why):
    cam = place['camera']
    return {'dimension': dim, 'name': name, 'x': cam[0], 'y': str(cam[1]), 'z': cam[2], 'yaw': place.get('yaw', 0),
            'pitch': place.get('pitch', 5), 'radius': 8, 'hold': 10, 'nightVision': nv, 'why': why}


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    features, landmarks, per_layer = 6, 3, None
    args = argv[3:]
    while args:
        if args[0] == '--features':
            features = int(args[1])
        elif args[0] == '--landmarks':
            landmarks = int(args[1])
        elif args[0] == '--max-per-layer':
            per_layer = int(args[1])
        else:
            print('unknown argument', args[0], file=sys.stderr)
            return 2
        args = args[2:]
    with open(argv[1]) as f:
        d = json.load(f)
    out = pick(d, features, landmarks, per_layer)
    with open(argv[2], 'w') as f:
        json.dump(out, f, indent=1)
    print(f"{len(out)} shots -> {argv[2]}")
    for s in out:
        print(f"  {s['name']}: {s['why'][:150]}")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
