# build.py <slug=dossier.json>...: the w10c1 shot set -> /tmp/claude-0/w10c1p/terrain-shots.json (W10a format), 960 px jpgs
# in shots/, and per slug reviews/<slug>/{review.md,facts.json,shots.json} via review-packet.py, plus answers.md from
# lists/hand.json via answers.py. Shots come from lists/<slug>-picks.json (and lists/<slug>-extra.json), the png found in
# the newest rig run that has it; names in lists/drop.txt are left out. Records of slugs not named are kept from the old file.
import json, os, glob, subprocess, sys
sys.path.insert(0, '/tmp/claude-0/w9c/tools')
from captions import caption
from PIL import Image
W = '/tmp/claude-0/w10c1p'
RP = '/home/user/minecraft-server-template/mods/custom-dimensions-client-26/scripts/review-packet.py'
RUNS = sorted(glob.glob(f'{W}/runs/rig-*/screenshots'), key=os.path.getmtime, reverse=True)
drop = set(open(f'{W}/lists/drop.txt').read().split()) if os.path.exists(f'{W}/lists/drop.txt') else set()
OLD = {'the_aviary': '/home/user/minecraft-server-template/config/custom-dimensions/dimensions/paradise_lost.json'}
LABEL = {False: 'as a player sees it', True: 'with night vision'}
os.makedirs(f'{W}/shots', exist_ok=True)

def record(slug, d, e, n, png, nv):
    fr = png[:-4] + '.frame.json'
    census = json.load(open(fr)) if os.path.exists(fr) else None
    part = n[len(slug) + 1:]
    layer = part.split('-')[0]
    layer = 'vista' if part.startswith('far') else 'feature' if layer in ('feature', 'structure') else layer
    if e.get('layer'): layer = e['layer']
    jpg = f'{W}/shots/{n}.jpg'
    im = Image.open(png).convert('RGB'); im.resize((960, round(960 * im.height / im.width))).save(jpg, quality=85)
    eye = census['eye'] if census else {}
    gate = (census or {}).get('gate', {})
    r = {'kind': part, 'file': png, 'jpg': jpg, 'shows': e.get('shows') or (caption(census, d)[0] if census else None),
         'censusCaption': census['caption'] if census else None, 'layer': layer, 'place': e.get('why', ''),
         'camera': {k: eye.get(k) for k in ('x', 'y', 'z', 'yaw', 'pitch')}, 'lookingAt': e.get('at'),
         'renderer': 'render distance 32, noon, clear, clouds off', 'nightVision': nv,
         'gate': {'passed': gate.get('passed', True), 'reasons': gate.get('reasons', []), 'luma': gate.get('luma'), 'fogPercent': gate.get('fogPercent')}}
    if e.get('pair'): r['frame'] = LABEL[nv]; r['pairOf'] = e['name']
    if e.get('voidFeature'): r['voidFeature'] = True
    return r

old = {r['slug']: r for r in json.load(open(f'{W}/terrain-shots.json'))} if os.path.exists(f'{W}/terrain-shots.json') else {}
for arg in sys.argv[1:]:
    slug, dp = arg.split('=', 1)
    d = json.load(open(dp))
    entries = json.load(open(f'{W}/lists/{slug}-picks.json'))
    if os.path.exists(f'{W}/lists/{slug}-extra.json'): entries += json.load(open(f'{W}/lists/{slug}-extra.json'))
    shots, plist = [], []
    for e in entries:
        n = e['name']
        if n in drop: continue
        png = next((p for p in (f'{s}/{n}.png' for s in RUNS) if os.path.exists(p)), None)
        if not png: continue
        shots.append(record(slug, d, e, n, png, bool(e.get('nightVision')) and not e.get('pair'))); plist.append(e)
        if e.get('pair') and os.path.exists(png[:-4] + '-nv.png') and n + '-nv' not in drop:
            shots.append(record(slug, d, e, n + '-nv', png[:-4] + '-nv.png', True)); plist.append(dict(e, name=n + '-nv', nightVision=True))
    rd = f'{W}/reviews/{slug}'; sd = f'{rd}/screens'; os.makedirs(sd, exist_ok=True)
    open(f'{rd}/dossier.path', 'w').write(dp)
    json.dump(plist, open(f'{rd}/shots.json', 'w'), indent=1)
    for f in glob.glob(f'{sd}/*'): os.remove(f)
    for s in shots:
        for ext in ('.png', '.frame.json'):
            src = s['file'][:-4] + ext
            if os.path.exists(src): os.symlink(src, os.path.join(sd, os.path.basename(src)))
    r = subprocess.run(['python3', RP, slug, dp, f'{rd}/shots.json', sd, f'{rd}/review.md', '--facts', f'{rd}/facts.json'] + (['--old', OLD[slug]] if slug in OLD else []), capture_output=True, text=True)
    if r.returncode: print(slug, 'review-packet failed', r.stderr[-400:])
    hand = f'{W}/lists/hand.json'
    if os.path.exists(hand) and slug in json.load(open(hand)):
        r = subprocess.run(['python3', f'{W}/tools/answers.py', slug, f'{rd}/facts.json', f'{rd}/shots.json', sd, '--hand', hand, '--out', f'{rd}/answers.md'] + (['--old', OLD[slug]] if slug in OLD else []), capture_output=True, text=True)
        if r.returncode: print(slug, 'answers failed', r.stderr[-400:])
    old[slug] = {'slug': slug, 'shots': shots}
    print(slug, len(shots), 'shots')
json.dump(list(old.values()), open(f'{W}/terrain-shots.json', 'w'), indent=1)
