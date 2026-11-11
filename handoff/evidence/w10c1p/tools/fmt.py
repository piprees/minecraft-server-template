# fmt.py <file.json>...: rewrite in the definitions' house style (2-space indent, arrays of scalars on one line).
import json, sys, re
def dump(o, ind=0):
    sp = '  ' * ind
    if isinstance(o, dict):
        if not o: return '{}'
        return '{\n' + ',\n'.join(f'{sp}  {json.dumps(k, ensure_ascii=False)}: {dump(v, ind + 1)}' for k, v in o.items()) + f'\n{sp}}}'
    if isinstance(o, list):
        if all(not isinstance(x, (dict, list, str)) for x in o): return '[' + ', '.join(json.dumps(x, ensure_ascii=False) for x in o) + ']'
        return '[\n' + ',\n'.join(f'{sp}  {dump(v, ind + 1)}' for v in o) + f'\n{sp}]'
    return json.dumps(o, ensure_ascii=False)
for f in sys.argv[1:]:
    s = dump(json.load(open(f))) + "\n"; open(f, "w").write(s)
