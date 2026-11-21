# mk.py <out defs dir> [spec...]: copy the repo's definitions/ to <out>, then apply each spec.
# spec "slug=patch.json" merges the patch's top-level keys (bar "reason") into definitions/<slug>.json in place;
# "new:slug=patch.json" writes the merge as <new>.json (a lazy variant: portals and roll dropped so it cannot clash).
# A patch key whose value is null removes that key.
import json, os, shutil, sys
SRC = '/home/user/minecraft-server-template/mods/custom-dimensions-26/definitions'
out = sys.argv[1]
if os.path.exists(out): shutil.rmtree(out)
shutil.copytree(SRC, out)
for spec in sys.argv[2:]:
    lhs, patch = spec.split('=', 1)
    new, slug = lhs.split(':', 1) if ':' in lhs else (None, lhs)
    d = json.load(open(f'{SRC}/{slug}.json'))
    p = json.load(open(patch)) if patch else {}
    for k, v in p.items():
        if k == 'reason': continue
        if v is None: d.pop(k, None)
        else: d[k] = v
    if new:
        d.pop('portals', None); d.pop('roll', None)
        json.dump(d, open(f'{out}/{new}.json', 'w'), indent=2)
    else:
        json.dump(d, open(f'{out}/{slug}.json', 'w'), indent=2)
    print(spec, '->', new or slug)
