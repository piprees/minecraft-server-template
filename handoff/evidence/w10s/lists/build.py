# build.py: targets tables, review packs (shots copied in) and page.json from W10r's entries, the RB-45 readings and the shots.
import json, os, re, shutil, subprocess
W = '/tmp/claude-0/w10s'; CT = '/home/user/minecraft-server-template/mods/custom-dimensions-client-26/scripts/check-targets.py'
T = '/home/user/minecraft-server-template/mods/custom-dimensions-26/fidelity/targets'
exec(open(W + '/data/rb45.py').read())
D = {s: f'/tmp/claude-0/w10r/runs/r3/full/{s}/dossier.json' for s in R}
D['the_abyssal_shrine'] = W + '/runs/b1/full/the_abyssal_shrine_b/dossier.json'
SHOTS = W + '/shots'
w10r = {e['slug']: e for e in json.load(open('/home/user/minecraft-server-template/handoff/evidence/w10r/page.json'))}
extra = json.load(open(W + '/data/page-extra.json'))
page, alltargets = [], []
def check(s, k):
    j = f'{W}/data/t-{s}-{k}.json'
    subprocess.run(['python3', CT, D[s], f'{T}/{k}/{s}.json', '--json', j], capture_output=True)
    return json.load(open(j))
for s in R:
    pack = f'{W}/review-pack/{s}'; os.makedirs(pack + '/shots', exist_ok=True)
    rows = [f'# {s}: targets', '', 'Measured on the survey of the current definition (dossier keys). "from" is the phrase of the description or the note each number comes from.', '',
            '| set | key | test | measured | passes | from |', '|---|---|---|---|---|---|']
    res = {}
    for k in ('w10t', 'w10c'):
        r = check(s, k); res[k] = r
        for x in r['results']:
            rows.append(f"| {'description' if k == 'w10t' else 'placeness'} | `{x['key']}` | {x['op']} {x['value']} | {x['measured']} | {'yes' if x['pass'] else 'no'} | {x.get('from', '').replace('|', '/')} |")
    open(pack + '/targets.md', 'w').write('\n'.join(rows) + '\n')
    # shots: the structure and zone frames (both frames of a pair) plus W10r's terrain shots of the dimension
    for f in sorted(os.listdir(SHOTS)):
        if f.startswith(s + '-') and f.endswith('.jpg'): shutil.copy(f'{SHOTS}/{f}', f'{pack}/shots/{f}')
    for f in sorted(os.listdir('/tmp/claude-0/w10r/shots')):
        if f.startswith(s + '-') and 'structure' not in f: shutil.copy(f'/tmp/claude-0/w10r/shots/{f}', f'{pack}/shots/terrain-{f}')
    e = dict(w10r[s]); e.pop('fidelity', None)
    e.update(extra.get(s, {}))
    e['targets'] = {'description': f"{res['w10t']['pass']}/{res['w10t']['pass'] + res['w10t']['fail']}", 'placeness': f"{res['w10c']['pass']}/{res['w10c']['pass'] + res['w10c']['fail']}"}
    e['rb45'] = [{'structure': sid, 'status': v[0], 'shot': (v[1] + '.jpg') if v[1] else None, 'onItsGround': v[2], 'site': v[3], 'materialsAndAge': v[4], 'scale': v[5],
                  'approach': {'yes': v[6], 'line': v[7]}} for sid, v in R[s].items()]
    e['shotsAdded'] = sorted(f for f in os.listdir(pack + '/shots') if not f.startswith('terrain-'))
    page.append(e)
    alltargets.append((s, e['targets'], [f"{x['key']} {x['op']} {x['value']}: {x['measured']}" for k in res for x in res[k]['results'] if not x['pass']]))
json.dump(page, open(W + '/page.json', 'w'), indent=1, ensure_ascii=False)
open(W + '/targets-all.md', 'w').write('| dimension | description | placeness | failing |\n|---|---|---|---|\n' + ''.join(f"| {s} | {t['description']} | {t['placeness']} | {'<br>'.join(m)} |\n" for s, t, m in alltargets))
for s, t, m in alltargets: print(s, t, m)
