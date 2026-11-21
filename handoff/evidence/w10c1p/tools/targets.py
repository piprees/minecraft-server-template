# targets.py: targets-all.md for Batch 1 from the final dossiers (changed four from runs/fin, the rest W10c1's), with
# W10c1's final as the baseline column.
import json, subprocess, re
CT = '/home/user/minecraft-server-template/mods/custom-dimensions-client-26/scripts/check-targets.py'
T = '/home/user/minecraft-server-template/mods/custom-dimensions-26/fidelity/targets'
old = dict(l.split('=', 1) for l in open('/tmp/claude-0/w10c1/tools/dossiers.txt').read().split())
mine = {s: f'/tmp/claude-0/w10c1p/runs/fin/full/{s}/dossier.json' for s in ('overworld', 'the_abyssal_shrine', 'the_violet_spire', 'the_aviary')}
def run(d, s, st):
    out = subprocess.run(['python3', CT, d, f'{T}/{st}/{s}.json'], capture_output=True, text=True).stdout
    m = re.search(r'(\d+)/(\d+) pass', out)
    return m.group(0).replace(' pass', ''), [l[5:].strip() for l in out.splitlines() if l.startswith('FAIL')]
rows = ['# Targets, Batch 1 (w10c1r)', '', 'Baseline: the definitions W10c1 left. Final: the committed definitions (the_nether, the_end, the_snowbound_isle and the_sunken_temple unchanged, so their final is W10c1\'s survey). w10t from the 1.21.1 descriptions; w10c the placeness targets.', '',
        '| dimension | w10t baseline | w10t final | w10c baseline | w10c final | final misses |', '|---|---|---|---|---|---|']
res = {}
for s in ['the_aviary', 'overworld', 'the_nether', 'the_end', 'the_snowbound_isle', 'the_abyssal_shrine', 'the_violet_spire', 'the_sunken_temple']:
    b1, _ = run(old[s], s, 'w10t'); b2, _ = run(old[s], s, 'w10c')
    d = mine.get(s, old[s])
    f1, m1 = run(d, s, 'w10t'); f2, m2 = run(d, s, 'w10c')
    res[s] = {'w10t': f1, 'w10c': f2, 'missT': m1, 'missC': m2}
    rows.append(f'| {s} | {b1} | {f1} | {b2} | {f2} | ' + '<br>'.join([f'w10t: {m[:150]}' for m in m1] + [f'w10c: {m[:150]}' for m in m2]) + ' |')
open('/tmp/claude-0/w10c1p/targets-all.md', 'w').write('\n'.join(rows) + '\n')
json.dump(res, open('/tmp/claude-0/w10c1p/targets.json', 'w'), indent=1)
for s, r in res.items(): print(s, r['w10t'], r['w10c'], r['missC'])
