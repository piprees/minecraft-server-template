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

The frame gate rejects a frame with more than BORDER_MAX percent of its rays reaching the world border before any block
(FrameGate.BORDER_MAX, FrameCensus's ray grid), and a ray that hits nothing (sky, or void under a sky world) counts as
travelling the whole render distance. So a far view from a summit near the border of a small sky world cannot pass.
With the border's half-size known (--border, else the dossier's borders.player or census radius) each far candidate is scored by
border_share(): the share of its rays that would reach the border if every one of them hit nothing, which is the gate's
own rule at its strictest, so a candidate under BORDER_MAX passes the border test whatever it sees. When the summit's
camera scores over it, the far view and its alternates are the candidates that pass: cameras on the border ring
(RING_INSET inside it, RING_RISE over the summit's height) facing the centre, tilted RING_PITCH down so the far side
of the border sits near the top of the frame (more where the border rule needs it), the one opposite the summit first;
then a camera over the centre (OVERHEAD_RISE over the summit) looking OVERHEAD_PITCH or more down. The summit's own view
follows them while alternates remain. A ring point near a corner of the border sees the two edges beside it and fails.

Usage: pick-shots.py <dossier.json> <out.json> [--features N] [--landmarks N] [--max-per-layer N] [--border N]
                     [--render-distance N]
  --max-per-layer keeps each layer's N highest-scoring types (default all).
  --border gives the world border's half-size in blocks round (0, 0) (default the dossier's borders.player, else its census radius).
  --render-distance is the rig's, in chunks (default 32, rig.sh --render-distance 32).
Gotchas: night vision lights underground and roof shots. A dimension that is dark (no skylight, or held at night by a
definition fixedTime of 13000-23000) or fog-bound (its registered type's visual attributes or skybox name the nether,
its visual attributes fix a fog end distance, or its definition's effects are minecraft:the_nether; T180, T189) gets a
frame pair on every shot instead (`pair: true`, `nightVision: false`): the rig takes the frame a player sees and one
with night vision from the same camera, and keeps both when either passes the gate. A dimension with a void hole of a
hectare or more (landforms.voidHoles) marks its far view and every shot looking down more than VOID_PITCH degrees
`voidFeature: true`, which lets the gate take a mostly void frame that still hits blocks. A ceiling dimension's far
view is 12 over the summit, not over the client's heightmap (the roof). y is the feet height the survey measured, so
give the rig the same world.
"""
import json
import math
import re
import sys

CRAFTED = re.compile(r'waxed_|cut_|_bricks?$|chiseled_|polished_|chain|lantern|_planks$|_slab$|_stairs$|trial_|vault|spawner|copper_(bulb|grate|door|trapdoor)')
ALTERNATES = 3
VOID_PITCH = 20
BORDER_MAX = 2
COLUMNS, ROWS = 64, 36
FOV, ASPECT = 70.0, 1280 / 720
EYE = 1.62
RING_INSET, RING_RISE, OVERHEAD_RISE = 16, 40, 60
RING_PITCH, OVERHEAD_PITCH = 25, 30
RING_BEARINGS = 8
CENSUS_RADIUS = re.compile(r'over the (\d+)-block border')


def short(block):
    return block.split(':', 1)[-1]


def pick(d, features=6, landmarks=3, per_layer=None, border=None, render_distance=32):
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
    paired = dark or nether_fog
    holes = (d.get('landforms', {}).get('voidHoles') or {}).get('countOver1Ha', 0) > 0
    out = []
    vista = d.get('places', {}).get('vista')
    if vista:
        sx, sy, sz = vista['summit']
        far = {'dimension': dim, 'name': f'{slug}-far', 'x': sx + 0.5, 'y': str(sy + 12) if ceiling else 'surface+40', 'z': sz + 0.5,
               'nightVision': False,
               'at': vista['lookAt'], 'radius': 24, 'hold': 45, 'why': 'vista: summit of the coarse map, facing its largest drop',
               'alternates': inward(vista)}
        half = border if border is not None else border_of(d)
        if half and not ceiling:
            far = within_border(far, vista, half, render_distance * 16.0, top(d))
        out.append(far)
    for layer in ('surface', 'sky', 'underground', 'roof'):
        info = d.get('places', {}).get(layer) or {}
        types = sorted(info.get('types', []), key=lambda t: -(t['sharePercent'] / 100 + t['distinctness']))[:per_layer]
        nv = layer in ('underground', 'roof') and not paired
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
        nvf = p['layer'] in ('underground', 'roof') and not paired
        kind = 'structure' if CRAFTED.search(short(block)) else 'feature'
        s = {'dimension': dim, 'name': f'{slug}-{kind}-{short(block)}', 'x': cam[0], 'y': str(cam[1]), 'z': cam[2],
             'at': p['at'], 'radius': 8, 'hold': 10, 'nightVision': nvf,
             'why': (f"{short(block)}{' (crafted: a structure)' if kind == 'structure' else ''}: {t['clusters']} clusters in "
                     f"{t['patches']} patches, median {t['sizeP50']} blocks, {p['layer']} layer")}
        others = [{'x': q['camera'][0], 'y': str(q['camera'][1]), 'z': q['camera'][2], 'at': q['at']} for q in places[1:]]
        s['alternates'] = alternates(s, others)
        out.append(s)
        n += 1
    for s in out:
        if paired:
            s['pair'] = True
            s['nightVision'] = False
        if holes and (s['name'].endswith('-far') or looks_down(s) > VOID_PITCH):
            s['voidFeature'] = True
    return out


def border_of(d):
    """The player border's half-size: the dossier's borders.player, else the radius its structure census note names; None."""
    player = (d.get('borders') or {}).get('player')
    if player:
        return int(player)
    note = (d.get('structures', {}).get('census') or {}).get('note', '')
    m = CENSUS_RADIUS.search(note)
    return int(m.group(1)) if m else None


def top(d):
    """The dimension's top block height, or None when the dossier does not give it."""
    t = d.get('dimensionType', {})
    return t['minY'] + t['height'] if 'minY' in t and 'height' in t else None


def direction(pitch, yaw):
    """Minecraft's Vec3.directionFromRotation: yaw 0 faces +z, 90 faces -x; pitch above 0 looks down."""
    f, g = math.cos(-math.radians(yaw) - math.pi), math.sin(-math.radians(yaw) - math.pi)
    h, i = -math.cos(-math.radians(pitch)), math.sin(-math.radians(pitch))
    return g * h, i, f * h


def border_distance(x, z, dx, dz, half):
    """FrameGate.borderDistance for a square border of half-size `half` centred on (0, 0)."""
    if abs(x) > half or abs(z) > half:
        return 0
    t = math.inf
    if dx > 1e-9:
        t = min(t, (half - x) / dx)
    if dx < -1e-9:
        t = min(t, (-half - x) / dx)
    if dz > 1e-9:
        t = min(t, (half - z) / dz)
    if dz < -1e-9:
        t = min(t, (-half - z) / dz)
    return t


def border_share(x, eye_y, z, yaw, pitch, half, reach):
    """Percent of FrameCensus's rays from the eye at (x, eye_y, z) that reach the border within `reach` blocks when none hits a block."""
    fx, fy, fz = direction(pitch, yaw)
    rx, ry, rz = direction(0, yaw + 90)
    ux, uy, uz = direction(pitch - 90, yaw)
    ty = math.tan(math.radians(FOV / 2))
    tx = ty * ASPECT
    hit = 0
    for r in range(ROWS):
        v = (1 - (r + 0.5) / ROWS * 2) * ty
        for c in range(COLUMNS):
            u = ((c + 0.5) / COLUMNS * 2 - 1) * tx
            dx, dy, dz = fx + rx * u + ux * v, fy + ry * u + uy * v, fz + rz * u + uz * v
            n = math.sqrt(dx * dx + dy * dy + dz * dz)
            dx, dz = dx / n, dz / n
            horizontal = math.hypot(dx, dz)
            if horizontal > 1e-6 and border_distance(x, z, dx / horizontal, dz / horizontal, half) / horizontal < reach:
                hit += 1
    return hit * 100.0 / (ROWS * COLUMNS)


def yaw_to(x, z, tx, tz):
    """The yaw that faces (tx, tz) from (x, z)."""
    return math.degrees(math.atan2(-(tx - x), tz - z)) % 360


def facing(s):
    """A shot's yaw and pitch: its own, or those that face its `at` block from its eye."""
    if not s.get('at'):
        return s.get('yaw', 0), s.get('pitch', 0)
    ax, ay, az = s['at']
    return yaw_to(s['x'], s['z'], ax + 0.5, az + 0.5), looks_down(s)


def least_pitch(x, eye_y, z, yaw, half, reach, start):
    """The smallest pitch from `start` up, in steps of 5, whose frame passes the border rule at its strictest; None if none does."""
    p = start
    while p <= 90:
        share = border_share(x, eye_y, z, yaw, p, half, reach)
        if share <= BORDER_MAX:
            return p, share
        p += 5
    return None


def on_ring(bearing, half):
    """The point RING_INSET inside the square border along `bearing` (degrees, 0 east, 90 south) from its centre."""
    b = math.radians(bearing)
    c, s = math.cos(b), math.sin(b)
    d = (half - RING_INSET) / max(abs(c), abs(s))
    return c * d, s * d


def within_border(far, vista, half, reach, top_y):
    """`far`, or when its summit camera fails the border rule at its strictest, candidates that pass it, with `far` after them."""
    sx, sy, sz = vista['summit']
    yaw, pitch = facing(far)
    eye = sy + 40 + EYE
    if border_share(far['x'], eye, far['z'], yaw, pitch, half, reach) <= BORDER_MAX:
        return far
    cap = (top_y - 2) if top_y is not None else math.inf
    y = min(sy + RING_RISE, cap)
    away = math.degrees(math.atan2(-sz, -sx)) if math.hypot(sx, sz) > 1 else 0
    candidates = []
    for k in range(RING_BEARINGS):
        bearing = away + 360.0 * ((k + 1) // 2) / RING_BEARINGS * (1 if k % 2 else -1)
        x, z = on_ring(bearing, half)
        yw = yaw_to(x, z, 0, 0)
        found = least_pitch(x, y + EYE, z, yw, half, reach, RING_PITCH)
        if found:
            candidates.append({'x': round(x, 1), 'y': str(int(y)), 'z': round(z, 1), 'yaw': round(yw, 1), 'pitch': found[0],
                               'why': f'far: the border ring at bearing {bearing % 360:.0f}, facing the centre; '
                                      f'{found[1]:.1f}% of rays could reach the border'})
    oy = min(sy + OVERHEAD_RISE, cap)
    oyaw = yaw_to(0, 0, sx, sz)
    found = least_pitch(0.5, oy + EYE, 0.5, oyaw, half, reach, OVERHEAD_PITCH)
    if found:
        candidates.append({'x': 0.5, 'y': str(int(oy)), 'z': 0.5, 'yaw': round(oyaw, 1), 'pitch': found[0],
                           'why': f'far: over the centre looking down towards the summit; {found[1]:.1f}% of rays could reach the border'})
    if not candidates:
        far['why'] += (f'; no camera inside the {half}-block border passes the border rule at a render distance of {reach / 16:.0f}: '
                       f'a lower --render-distance (and the rig at it) leaves rays short of the border')
        return far
    first = candidates[0]
    out = {k: v for k, v in far.items() if k not in ('at', 'alternates')}
    out.update(first)
    summit = {'x': far['x'], 'y': far['y'], 'z': far['z'], 'at': far['at'], 'why': far['why']}
    out['alternates'] = (candidates[1:] + [summit])[:ALTERNATES]
    return out


def looks_down(s):
    """Degrees below the horizon the shot's camera looks: its pitch, or the angle down to its `at` block."""
    if not s.get('at'):
        return s.get('pitch', 0)
    try:
        y = float(s['y'])
    except ValueError:
        return 0
    ax, ay, az = s['at']
    return math.degrees(math.atan2(y + 1.6 - ay - 0.5, math.hypot(ax + 0.5 - s['x'], az + 0.5 - s['z'])))


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
    features, landmarks, per_layer, border, distance = 6, 3, None, None, 32
    args = argv[3:]
    while args:
        if args[0] == '--features':
            features = int(args[1])
        elif args[0] == '--landmarks':
            landmarks = int(args[1])
        elif args[0] == '--max-per-layer':
            per_layer = int(args[1])
        elif args[0] == '--border':
            border = int(args[1])
        elif args[0] == '--render-distance':
            distance = int(args[1])
        else:
            print('unknown argument', args[0], file=sys.stderr)
            return 2
        args = args[2:]
    with open(argv[1]) as f:
        d = json.load(f)
    out = pick(d, features, landmarks, per_layer, border, distance)
    with open(argv[2], 'w') as f:
        json.dump(out, f, indent=1)
    print(f"{len(out)} shots -> {argv[2]}")
    for s in out:
        print(f"  {s['name']}: {s['why'][:150]}")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
