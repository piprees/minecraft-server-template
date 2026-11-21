# pick.py <dossiers dir> <slug...>: the capped selection (pick-shots.pick: far view, the top type of every layer,
# more types, one landmark, features; 8 a pick) from the W9h full dossiers, plus the zone shots: crimson_nexus crater and
# obelisk, gritlands crater from above its ring (T199). -> /tmp/claude-0/w10c1p/lists/<slug>-picks.json and all.json
import json, sys, importlib.util
spec = importlib.util.spec_from_file_location('ps', '/home/user/minecraft-server-template/mods/custom-dimensions-client-26/scripts/pick-shots.py')
ps = importlib.util.module_from_spec(spec); spec.loader.exec_module(ps)
D = sys.argv[1]; CAP = 8; W = '/tmp/claude-0/w10c1p/lists'
ZONES = json.load(open('/tmp/claude-0/w10c1p/lists/zones.json')) if __import__('os').path.exists('/tmp/claude-0/w10c1p/lists/zones.json') else {}
allshots = []
for slug in [a for a in sys.argv[2:] if not a.startswith('--')]:
    d = json.load(open(f'{D}/{slug}/dossier.json'))
    allp = ps.pick(d, features=3, landmarks=2)
    tail = lambda s: s['name'][len(slug) + 1:]
    far = [s for s in allp if tail(s) == 'far']
    types = [s for s in allp if tail(s).split('-')[-1].startswith('t') and tail(s).split('-')[0] in ('surface', 'sky', 'underground', 'roof')]
    lms = [s for s in allp if tail(s).split('-')[-1].startswith('lm')]
    feats = [s for s in allp if tail(s).startswith(('feature-', 'structure-'))]
    zones = [{'dimension': 'adventure:' + slug, 'nightVision': False, **({'pair': True} if allp and allp[0].get('pair') else {}), **z} for z in ZONES.get(slug, [])]
    first, rest, seen = [], [], set()
    if '--zones-only' in sys.argv: far, types, lms, feats = [], [], [], []
    for s in types:
        layer = tail(s).split('-')[0]
        (rest if layer in seen else first).append(s); seen.add(layer)
    chosen = far + zones + first
    for s in rest:
        if len(chosen) >= CAP - 1: break
        chosen.append(s)
    if lms and len(chosen) < CAP: chosen.append(lms[0])
    for s in feats + lms[1:]:
        if len(chosen) >= CAP: break
        chosen.append(s)
    json.dump(chosen, open(f'{W}/{slug}-picks.json', 'w'), indent=1)
    allshots += chosen
    print(slug, len(chosen), ' '.join(tail(s) for s in chosen))
json.dump(allshots, open(f'{W}/all.json', 'w'), indent=1)
print('total', len(allshots))
