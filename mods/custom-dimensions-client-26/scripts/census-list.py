#!/usr/bin/env python3
"""census-list.py — turn accepted shot records into census-only rig shot lists.

Context: template-only rig tooling for mods/custom-dimensions-client-26 (stdlib only). Reads any record format:
  the terrain picks (a list of {slug, shots: [{kind, file, shows, camera: {x, y, z, yaw, pitch}, nightVision}]}, camera y = feet)
  the biome gallery (a list of {biome, dimension?, shots: [{kind, png, what|shows, dimension?, camera: [x, y, z],
    at|lookingAt|yaw+pitch, nightVision}]}; a shot without a dimension takes its record's, else selftest_biome_<biome>)
  the gallery's first batch ({root, biomes: [{biome, showcase, shots: [{kind, png, shows, camera: {x, y, z, yaw, pitch}}]}]})
and writes a rig list of `check: true` entries at the same cameras, so `scripts/rig.sh biomes` re-censuses them
(<name>.frame.json, no screenshot). Each entry keeps the old caption under `caption` and the image under `image`.

Usage: census-list.py <shots.json> <out.json> [--only slug,slug] [--radius N]
Gotchas: names are <slug>-<kind><n>-census so they never overwrite a shot's png; a far shot keeps radius 24.
"""
import json
import sys


def entries(records, only, radius):
    out = []
    if isinstance(records, dict):
        records = [dict(r, biome=r['biome'].split(':')[-1], dimension=r.get('showcase')) for r in records.get('biomes', [])]
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
            dim = shot.get('dimension') or rec.get('dimension')
            if isinstance(cam, dict):
                e.update(dimension=dim or f"adventure:{slug}", x=cam['x'], y=str(cam['y']), z=cam['z'])
                face = cam
            elif isinstance(cam, list):
                e.update(dimension=dim or f"adventure:selftest_biome_{slug}", x=cam[0], y=str(cam[1]), z=cam[2])
                face = {}
            else:
                continue
            if shot.get('at') or shot.get('lookingAt'):
                e['at'] = shot.get('at') or shot.get('lookingAt')
            else:
                e.update(yaw=face.get('yaw', shot.get('yaw', 0)), pitch=face.get('pitch', shot.get('pitch', 0)))
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
