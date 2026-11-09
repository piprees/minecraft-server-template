#!/usr/bin/env python3
"""check-targets.py — check a survey dossier against measurable targets.

Context: template-only rig tooling for mods/custom-dimensions-client-26 (stdlib only). A targets file turns a
dimension's intent into numbers the survey measures (`/customdim survey <slug>` writes dossier.json):
  {"slug": "the_x", "targets": [{"key": "surface.snowIcePercent", "op": ">=", "value": 70, "from": "why"}, ...]}
`key` is a dossier key path, dot-separated (`terrain.surfaceHeight.p95`, `surface.biomesPercent.minecraft:plains`,
`clusters.types.minecraft:magma_block.clusters`); several paths joined by ` + ` are summed. `op` is one of
>=, <=, >, <, == or `between` (value [low, high], inclusive). `from` is the sentence of intent the number comes from.
Prints one PASS or FAIL line per target with the measured value; exit 0 when every target passes, 1 when any fails.

Usage: check-targets.py <dossier.json> <targets.json> [--json OUT]
  --json writes {slug, pass, fail, results: [{key, op, value, measured, pass, note}]}.
Gotchas: an unknown key path is an error (exit 2), never a pass or a fail. The one exception is a missing registry id
(it has a colon) in a map keyed by ids (shares, counts, cluster or species records): the dossier lists only the largest
entries, so a missing one measures 0 and the line says "not listed". A whole missing section (`clusters.types.minecraft:x` with no `clusters` key) is still an error.
"""
import json
import sys

OPS = {'>=': lambda m, v: m >= v, '<=': lambda m, v: m <= v, '>': lambda m, v: m > v, '<': lambda m, v: m < v,
       '==': lambda m, v: m == v, 'between': lambda m, v: v[0] <= m <= v[1]}


class TargetError(Exception):
    pass


def numeric_map(node):
    return isinstance(node, dict) and len(node) > 0 and all(
        isinstance(x, (int, float)) and not isinstance(x, bool) for x in node.values())


def resolve(dossier, path):
    """The number at a dot path, and a note; a missing entry in a numeric map is 0, anything else unknown raises."""
    node = dossier
    parts = path.split('.')
    for i, part in enumerate(parts):
        if isinstance(node, dict) and part in node:
            node = node[part]
            continue
        listed_id = ':' in part and i > 0 and isinstance(node, dict)
        if listed_id and i == len(parts) - 1 and (numeric_map(node) or node == {}):
            return 0.0, 'not listed'
        if listed_id and i == len(parts) - 2 and all(isinstance(x, dict) for x in node.values()):
            sibling = next(iter(node.values()), None)
            if sibling is None or parts[-1] in sibling:
                return 0.0, 'not listed'
        raise TargetError('unknown key %s (stops at %s)' % (path, '.'.join(parts[:i + 1])))
    if isinstance(node, bool) or not isinstance(node, (int, float)):
        raise TargetError('key %s is not a number (%s)' % (path, type(node).__name__))
    return float(node), ''


def measure(dossier, key):
    total, notes = 0.0, []
    for path in [p.strip() for p in key.split(' + ')]:
        value, note = resolve(dossier, path)
        total += value
        if note:
            notes.append('%s %s' % (path.rsplit('.', 1)[-1], note))
    return total, '; '.join(notes)


def check(dossier, targets):
    results = []
    for t in targets:
        op = t.get('op')
        if op not in OPS:
            raise TargetError('unknown op %r for %s' % (op, t.get('key')))
        value = t['value']
        if op == 'between' and not (isinstance(value, list) and len(value) == 2):
            raise TargetError('between needs [low, high] for %s' % t['key'])
        measured, note = measure(dossier, t['key'])
        results.append({'key': t['key'], 'op': op, 'value': value, 'measured': round(measured, 2),
                        'pass': bool(OPS[op](measured, value)), 'note': note, 'from': t.get('from', '')})
    return results


def fmt(r):
    value = '%s-%s' % tuple(r['value']) if r['op'] == 'between' else r['value']
    note = ' (%s)' % r['note'] if r['note'] else ''
    return '%s %s %s %s: %s%s' % ('PASS' if r['pass'] else 'FAIL', r['key'], r['op'], value, r['measured'], note)


def main(argv):
    if len(argv) < 3:
        print(__doc__.split('Usage: ')[1].split('\n')[0], file=sys.stderr)
        return 2
    with open(argv[1]) as f:
        dossier = json.load(f)
    with open(argv[2]) as f:
        spec = json.load(f)
    try:
        results = check(dossier, spec['targets'])
    except TargetError as e:
        print('ERROR %s: %s' % (spec.get('slug', argv[2]), e), file=sys.stderr)
        return 2
    passed = sum(1 for r in results if r['pass'])
    for r in results:
        print(fmt(r))
    print('%s: %d/%d pass' % (spec.get('slug', '?'), passed, len(results)))
    if '--json' in argv:
        with open(argv[argv.index('--json') + 1], 'w') as f:
            json.dump({'slug': spec.get('slug'), 'pass': passed, 'fail': len(results) - passed, 'results': results},
                      f, indent=1)
    return 0 if passed == len(results) else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv))
