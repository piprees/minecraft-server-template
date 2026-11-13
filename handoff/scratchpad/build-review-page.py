#!/usr/bin/env python3
"""Build an owner's review page for a batch of dimensions.

usage: build-review-page.py <package-dir> <out-dir> <collection> <title> <lede-html-file>
Reads <package-dir>/terrain-shots.json, page.json, renders/, and the repo's definitions.
Writes <out-dir>/index.html and img/, and files.json (the publish list).
"""
import json, os, re, shutil, sys
pkg, out, coll, title, lede = sys.argv[1:6]
S = os.path.dirname(os.path.abspath(__file__))
DEFS = '/home/user/minecraft-server-template/mods/custom-dimensions-26/definitions'
t = open(S + '/terrain-picks/index.html').read()
shots = {p['slug']: p for p in json.load(open(pkg + '/terrain-shots.json'))}
page = {p['slug']: p for p in json.load(open(pkg + '/page.json'))}
os.makedirs(out + '/img', exist_ok=True)

def kind(s):
    k = s['kind']; nv = k.endswith('-nv'); k = k[:-3] if nv else k
    m = re.match(r'(surface|underground|sky|roof)-t(\d+)$', k)
    if m: lab = f"{m.group(1)} · place type {m.group(2)}"
    elif re.match(r'(surface|underground|sky|roof)-lm(\d+)$', k): lab = k.split('-')[0] + ' · landmark'
    elif k == 'far': lab = 'far view'
    elif k.startswith('feature-'): lab = 'feature · ' + k[8:].replace('_', ' ')
    elif k.startswith('structure-'): lab = 'structure · ' + k[10:].replace('_', ' ')
    else: lab = k.replace('-', ' ')
    if s.get('pairOf') or nv or s.get('frame'):
        lab += ' · ' + (s.get('frame') or ('with night vision' if s.get('nightVision') else 'as a player sees it'))
    return lab

B, files, problems = [], [], []
for slug in page:
    p = page[slug]; d = json.load(open(f'{DEFS}/{slug}.json'))
    name = ' '.join(w.capitalize() for w in slug.split('_'))
    ig = []
    for s in shots.get(slug, {}).get('shots', []):
        if not os.path.exists(s['jpg']): problems.append(f"{slug}: missing {s['jpg']}"); continue
        fn = 'img/' + os.path.basename(s['jpg']); shutil.copy(s['jpg'], out + '/' + fn); files.append(fn)
        shows = s['shows']; g = s.get('gate') or {}
        if g and not g.get('passed', True):
            shows += ' The frame gate rejects this one (' + ', '.join(r.split(':')[0] for r in g.get('reasons', [])) + '); its twin passes.'
        ig.append({'kind': kind(s), 'file': fn, 'shows': shows})
    if not ig: problems.append(f'{slug}: no shots')
    maps = {}
    for k in ('height', 'biome'):
        src = f'{pkg}/renders/{slug}-{k}.jpg'
        if os.path.exists(src):
            fn = f'img/{slug}-{k}.jpg'; shutil.copy(src, out + '/' + fn); files.append(fn); maps[k] = fn
        else: problems.append(f'{slug}: no {k} map')
    rows = [['1.21.1 description', d.get('description', '')], ['Generator', p['terrain']], ['Changed', p['changed']]]
    tg = p.get('targets') or {}
    if tg: rows.append(['Targets met', ' · '.join(f"{k} {v}" for k, v in tg.items())])
    for m in p.get('misses') or []: rows.append(['Miss', m])
    B.append({'id': slug, 'name': name, 'tag': p['terrain'], 'rows': rows, 'ingame': ig, 'files': maps,
              'fidelity': {'score': p['fidelity'], 'why': p['why']}, 'exception': p.get('exception') or '',
              'eye': p.get('ownersEye') or []})

m = re.search(r'const BIOMES = (\[.*?\]);\n', t, re.S)
t = t[:m.start(1)] + json.dumps(B, ensure_ascii=False) + t[m.end(1):]
def sub(old, new):
    global t
    assert old in t, old[:60]; t = t.replace(old, new)
sub('el("code", { text: `${b.terrain} · ${b.setting}` })', 'el("code", { text: b.tag })')
sub('for (const [k, v] of [["1.21.1 intent", b.intent], ["Terrain", `${b.terrain}, ${b.setting}`], ["Tuned", b.params], ["Why", b.reason]])', 'for (const [k, v] of b.rows)')
sub('if (b.rework) art.append(el("p", { class: "caveat" }, el("b", { text: "Reworked from your note: " }), document.createTextNode("“" + b.rework + "”")));', '')
sub('document.createTextNode(b.fidelity.why)));',
    'document.createTextNode(b.fidelity.why)));\n    if (b.exception) art.append(el("p", { class: "caveat" }, el("b", { text: "Exception: " }), document.createTextNode(b.exception)));\n    if (b.eye.length) { const ul = el("ul", { class: "eye" }); b.eye.forEach(q => ul.append(el("li", { text: q }))); art.append(el("p", { class: "caveat" }, el("b", { text: "For your eye:" })), ul); }')
sub('for (const k of ["height", "biome"]) {', 'for (const k of ["height", "biome"].filter(k => b.files[k])) {')
t = t.replace('db.collection("terrains")', f'db.collection("{coll}")')
h0 = t.index('<header>'); h1 = t.index('<div class="tally"')
n = len(B)
t = t[:h0] + f'<header>\n    <span class="eyebrow">custom-dimensions 26.3 · dimension review</span>\n    <h1>{title}</h1>\n' + open(lede).read() + '\n    ' + t[h1:]
t = re.sub(r'<span class="chip" id="n-open">\d+ open</span>', f'<span class="chip" id="n-open">{n} open</span>', t)
t = re.sub(r'<title>.*?</title>', f'<title>{title}</title>', t, flags=re.S)
open(out + '/index.html', 'w').write(t)
json.dump(sorted(set(files)), open(out + '/files.json', 'w'))
print(len(B), 'dimensions,', len(set(files)), 'files'); print('\n'.join(problems) or 'no problems')
