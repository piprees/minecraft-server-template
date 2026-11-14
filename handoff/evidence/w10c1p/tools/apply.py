# apply.py <slug> <patch.json>: merge a patch's top-level keys (bar "reason"; null removes) into definitions/<slug>.json,
# placing new keys in schema order, and write it in the writer form (fmt.py).
import json, sys, subprocess
ORDER = ['schemaVersion', 'description', 'seed', 'spawn', 'generator', 'terrain', 'biomes', 'biomeLayout', 'liquids', 'materials', 'zones',
         'dimensionType', 'atmosphere', 'rules', 'borders', 'difficulty', 'structures', 'portals', 'exits', 'roll']
slug, patch = sys.argv[1:3]
p = f'/home/user/minecraft-server-template/mods/custom-dimensions-26/definitions/{slug}.json'
d = json.load(open(p))
for k, v in json.load(open(patch)).items():
    if k == 'reason': continue
    if v is None: d.pop(k, None)
    else: d[k] = v
keys = [k for k in ORDER if k in d] + [k for k in d if k not in ORDER]
json.dump({k: d[k] for k in keys}, open(p, 'w'))
subprocess.run(['python3', '/tmp/claude-0/w10c1p/tools/fmt.py', p], check=True)
print(slug, keys)
