#!/usr/bin/env python3
"""census-list.py — turn accepted shot records into census-only rig shot lists.

Context: template-only rig tooling for mods/custom-dimensions-client-26 (stdlib only). Reads either record format:
  the terrain picks (a list of {slug, shots: [{kind, file, shows, camera: {x, y, z, yaw, pitch}, nightVision}]}, camera y = feet)
  the biome gallery (a list of {biome, shots: [{kind, png, what, dimension, camera: [x, y, z], at, nightVision}]})
and writes a rig list of `check: true` entries at the same cameras, so `scripts/rig.sh biomes` re-censuses them
(<name>.frame.json, no screenshot). Each entry keeps the old caption under `caption` and the image under `image`.

Usage: census-list.py <shots.json> <out.json> [--only slug,slug] [--radius N]
Gotchas: names are <slug>-<kind><n>-census so they never overwrite a shot's png; a far shot keeps radius 24.
"""
import json
import sys


def entries(records, only, radius):
    out = []
    for rec in records:
        slug = rec.get('slug') or rec.get('biome')
        if only and slug not in only:
            continue
        seen = {}
        for shot in rec['shots']:
            kind = shot['kind']
            seen[kind] = seen.get(kind, 0) + 1
            name = f"{slug}-{kind}{seen[kind]}-census"
            e = {'name': name, 'check': True, 'nightVision': bool(shot.get('nightVision')),
                 'radius': 24 if kind in ('far', 'wide') else radius,
                 'caption': shot.get('shows') or shot.get('what') or '', 'image': shot.get('file') or shot.get('png')}
            cam = shot.get('camera')
            if isinstance(cam, dict):
                e.update(dimension=f"adventure:{slug}", x=cam['x'], y=str(cam['y']), z=cam['z'], yaw=cam['yaw'], pitch=cam['pitch'])
            elif isinstance(cam, list):
                e.update(dimension=shot['dimension'], x=cam[0], y=str(cam[1]), z=cam[2])
                if shot.get('at'):
                    e['at'] = shot['at']
                else:
                    e.update(yaw=shot.get('yaw', 0), pitch=shot.get('pitch', 0))
            else:
                continue
            out.append(e)
    return out


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    only, radius = None, 8
    args = argv[3:]
    while args:
        if args[0] == '--only':
            only = set(args[1].split(','))
            args = args[2:]
        elif args[0] == '--radius':
            radius = int(args[1])
            args = args[2:]
        else:
            print('unknown argument', args[0], file=sys.stderr)
            return 2
    with open(argv[1]) as f:
        records = json.load(f)
    out = entries(records, only, radius)
    with open(argv[2], 'w') as f:
        json.dump(out, f, indent=1)
    print(f"{len(out)} census entries -> {argv[2]}")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
