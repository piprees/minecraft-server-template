#!/usr/bin/env python3
"""review-packet.py — one dimension's review packet: intent, measurement, a facts table and shots side by side.

Context: template-only rig tooling for mods/custom-dimensions-client-26 (stdlib only). Writes review.md from
  the 1.21.1 definition (config/custom-dimensions/dimensions/<slug>.json, read-only: RB-35): description, biome list,
    type, noiseSettings, settingsOverrides, environment;
  the v2 definition (mods/custom-dimensions-26/definitions/<slug>.json): description, biomes, terrain, zones;
  definitions/id-mapping.csv, which maps each 1.21.1 biome to its v2 id;
  an optional owner-notes file;
  the survey dossier (dossier.md's summary, its biome expression, surface, terrain and layer numbers);
  each shot of the rig list that has a png or a frame census in the screenshots folder: its census caption and image.
The facts table is computed, not judged: one row per 1.21.1 biome (O<n>: its v2 id, placed share, state, flags and
the reason it is flagged), one per placed v2 biome no 1.21.1 biome maps to (V<n>), the dossier's measures next to
the 1.21.1 settings (M:<key>) and the scorecard (S:a-S:d). It ends with the rule and the fixed review questions:
every factual statement in an answer cites a facts row id or a dossier key. The reviewer answers; the script assembles.

Usage: review-packet.py <slug> <dossier.json> <shot list.json> <screenshots dir> <out review.md> [--notes FILE] [--facts FILE]
  --facts also writes the facts table and scorecard as JSON.
Gotchas: run from anywhere; the repository root is found from this file's location. A shot whose png is missing is
listed as not taken, with its census if one exists. A 1.21.1 vanilla id with no mapping row maps to itself. A base
world (overworld, the_nether, the_end) has no 1.21.1 biome list, so scorecard a and b are n/a there; a is also n/a
where no column has ground. The survey files the lowest mass as the surface, so for the sky layer a type implies,
islands over void (void 20%+ of columns, ground on 5%+) count as present.
"""
import csv
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
QUESTIONS = [
    "Taken together, do the shots show the world's dominant character as the dossier measures it?",
    "Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.",
    "Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.",
    "Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?",
    "Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.",
]
RULE = ("Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key "
        "(for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is "
        "absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.")
# Layers each 1.21.1 type implies; a base world without a type takes its own name.
TYPE_LAYERS = {
    'multi_biome': ('surface', 'underground'), 'large_biomes': ('surface', 'underground'),
    'amplified': ('surface', 'underground'), 'checkerboard': ('surface', 'underground'), 'superflat': ('surface',),
    'nether': ('surface', 'roof'), 'the_nether': ('surface', 'roof'), 'cave': ('underground', 'roof'),
    'sky_islands': ('sky',), 'nether_islands': ('sky',), 'end': ('sky',), 'the_end': ('sky',),
    'paradise_lost:paradise_lost': ('sky',), 'overworld': ('surface', 'underground'), 'void': (),
}
LAYER_PRESENT_PERCENT = 5.0
ISLAND_VOID_PERCENT = 20.0


def load(path):
    with open(path) as f:
        return json.load(f)


def biome_ids(biomes):
    out = []
    for b in biomes or []:
        out.append(b if isinstance(b, str) else b.get('id', '?'))
    return out


def load_mapping(path):
    """1.21.1 biome id -> (v2 id or None, decision, reason) from id-mapping.csv."""
    out = {}
    if os.path.exists(path):
        with open(path, newline='') as f:
            for r in csv.DictReader(f):
                if r.get('kind') == 'biome':
                    out[r['id']] = (r.get('replacement') or None, r.get('decision', ''), r.get('reason', ''))
    return out


def facts(slug, old, new, dossier, mapping):
    """The facts table and scorecard: computed from the definitions, the mapping and the dossier only."""
    surface = dossier.get('surface', {})
    shares = surface.get('biomesPercent', {})
    expr = dossier.get('biomeExpression', {}).get('biomes', {})
    v2_listed = set(biome_ids(new.get('biomes')))
    not_found = set(dossier.get('biomeExpression', {}).get('listedNotFound', []))
    old_ids = biome_ids(old.get('biomes'))
    rows, traced = [], set()
    for i, oid in enumerate(old_ids, 1):
        if oid in mapping:
            v2, decision, why = mapping[oid]
        else:
            v2, decision, why = (oid, 'same id', '') if oid.startswith('minecraft:') else (None, 'no mapping row', '')
        e = expr.get(v2, {}) if v2 else {}
        share = shares.get(v2, 0.0) if v2 else 0.0
        if not v2:
            state, reason = 'no v2 id', decision
        elif e:
            state = e.get('state', 'placed')
            reason = ', '.join(e.get('flags', []))
        elif v2 in not_found:
            state, reason = 'listed, not found', 'in the v2 definition, in no sampled column'
        elif v2 in v2_listed:
            state, reason = 'listed, not found', 'in the v2 definition, not in the dossier'
        else:
            state, reason = 'not in v2 definition', 'mapped id missing from the v2 biome list'
        if v2:
            traced.add(v2)
        rows.append({'row': f'O{i}', 'old': oid, 'v2': v2, 'mapping': decision, 'mappingReason': why,
                     'surfacePercent': share, 'columns': e.get('columns', 0), 'state': state, 'flagReason': reason})
    extra = []
    for v2 in sorted(expr, key=lambda k: -shares.get(k, 0.0)):
        if v2 not in traced:
            e = expr[v2]
            extra.append({'row': f'V{len(extra) + 1}', 'v2': v2, 'surfacePercent': shares.get(v2, 0.0),
                          'columns': e.get('columns', 0), 'state': e.get('state', 'placed'), 'flagReason': ', '.join(e.get('flags', []))})
    land = sum(shares.values())
    traceable = round(100.0 * sum(shares.get(v, 0.0) for v in traced) / land, 1) if land else None
    terrain = dossier.get('terrain', {})
    fluid = surface.get('fluidCoverPercent', {})
    layers = dossier.get('vertical', {}).get('layerColumnsPercent', {})
    measures = {
        'topBiomes': [[k, v] for k, v in list(shares.items())[:3]],
        'snowIcePercent': surface.get('snowIcePercent'), 'waterPercent': fluid.get('water'), 'lavaPercent': fluid.get('lava'),
        'voidPercent': surface.get('voidPercent'), 'canopyPercent': surface.get('canopyPercent'),
        'reliefMedian': terrain.get('reliefMedian'), 'surfaceHeight': terrain.get('surfaceHeight'),
        'layerColumnsPercent': layers, 'columnClassPercent': dossier.get('vertical', {}).get('columnClassPercent'),
        'ceiling': dossier.get('dimensionType', {}).get('ceiling'), 'hasSkyLight': dossier.get('atmosphere', {}).get('hasSkyLight'),
    }
    old_type = old.get('type') or slug
    intent = {'type': old_type, 'noiseSettings': old.get('noiseSettings'),
              'settingsOverrides': old.get('settingsOverrides'), 'environment': old.get('environment')}
    implied = {}
    for layer in TYPE_LAYERS.get(old_type, ('surface',)):
        share = layers.get(layer, 0.0)
        present = share >= LAYER_PRESENT_PERCENT
        if layer == 'sky' and not present:
            # The survey files the lowest mass as surface, so islands over void count as the sky layer.
            share = round(min(layers.get('surface', 0.0), surface.get('voidPercent') or 0.0), 1)
            present = (surface.get('voidPercent') or 0.0) >= ISLAND_VOID_PERCENT and layers.get('surface', 0.0) >= LAYER_PRESENT_PERCENT
        implied[layer] = {'percent': share, 'present': present}
    expressed = sum(1 for r in rows if r['state'] == 'expressed')
    scorecard = {
        'a': traceable if old_ids and land else None,
        'b': round(100.0 * expressed / len(rows), 1) if rows else None,
        'c': sum(1 for e in expr.values() if e.get('state') == 'placed, not expressed'),
        'd': implied,
    }
    return {'slug': slug, 'oldBiomes': rows, 'v2Only': extra, 'traceableSurfacePercent': scorecard['a'],
            'measures': measures, 'intent': intent, 'scorecard': scorecard}


def facts_section(f):
    def pct(v):
        return 'n/a' if v is None else f'{v}%'
    b = ['## Facts', '', RULE, '', '### 1.21.1 biomes (O rows)', '']
    if f['oldBiomes']:
        b += ['| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |', '|---|---|---|---|---|---|---|']
        for r in f['oldBiomes']:
            b.append(f"| {r['row']} | {r['old']} | {r['v2'] or '-'} | {r['surfacePercent']} | {r['columns']} | {r['state']} | {r['flagReason'] or '-'} |")
    else:
        b.append('The 1.21.1 definition lists no biomes.')
    b += ['', '### Placed v2 biomes no 1.21.1 biome maps to (V rows)', '']
    if f['v2Only']:
        b += ['| row | v2 biome | surface % | columns | state | flag reason |', '|---|---|---|---|---|---|']
        for r in f['v2Only']:
            b.append(f"| {r['row']} | {r['v2']} | {r['surfacePercent']} | {r['columns']} | {r['state']} | {r['flagReason'] or '-'} |")
    else:
        b.append('None.')
    m, i = f['measures'], f['intent']
    b += ['', '### Measures (M rows) beside the 1.21.1 settings', '', '| key | dossier | 1.21.1 |', '|---|---|---|',
          f"| M:topBiomes | {', '.join(f'{k} {v}%' for k, v in m['topBiomes']) or '-'} | type `{i['type']}` |",
          f"| M:snow | {pct(m['snowIcePercent'])} (`surface.snowIcePercent`) | noiseSettings `{i['noiseSettings']}` |",
          f"| M:water | {pct(m['waterPercent'])} (`surface.fluidCoverPercent.water`) | settingsOverrides `{json.dumps(i['settingsOverrides'])}` |",
          f"| M:lava | {pct(m['lavaPercent'])} (`surface.fluidCoverPercent.lava`) | |",
          f"| M:void | {pct(m['voidPercent'])} (`surface.voidPercent`) | |",
          f"| M:canopy | {pct(m['canopyPercent'])} (`surface.canopyPercent`) | |",
          f"| M:relief | median {m['reliefMedian']} blocks a patch; height {json.dumps(m['surfaceHeight'])} (`terrain`) | |",
          f"| M:layers | {json.dumps(m['layerColumnsPercent'])} (`vertical.layerColumnsPercent`) | environment `{json.dumps(i['environment'])}` |",
          f"| M:classes | {json.dumps(m['columnClassPercent'])} (`vertical.columnClassPercent`); ceiling {m['ceiling']}, skylight {m['hasSkyLight']} | |",
          '', '### Scorecard (S rows)', '',
          f"- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): {pct(f['scorecard']['a'])}",
          f"- S:b — 1.21.1 biomes expressed: {pct(f['scorecard']['b'])}",
          f"- S:c — v2 biomes placed but not expressed: {f['scorecard']['c']}",
          '- S:d — layers the 1.21.1 type implies: ' + (', '.join(
              f"{k} {'present' if v['present'] else 'absent'} ({v['percent']}% of columns)" for k, v in f['scorecard']['d'].items()) or 'none'),
          '']
    return b


def summary_section(dossier_md):
    lines, keep = [], False
    for line in dossier_md.splitlines():
        if line.startswith('## '):
            keep = line.strip() in ('## Summary', '## Layers', '## Places', '## Biome expression')
        if keep:
            lines.append(line)
    return '\n'.join(lines)


def main(argv):
    if len(argv) < 6:
        print(__doc__)
        return 2
    slug, dossier_path, list_path, shots_dir, out = argv[1:6]
    notes = facts_out = None
    rest = argv[6:]
    while rest:
        if rest[0] == '--notes' and len(rest) > 1:
            notes = rest[1]
        elif rest[0] == '--facts' and len(rest) > 1:
            facts_out = rest[1]
        else:
            print('unknown argument', rest[0], file=sys.stderr)
            return 2
        rest = rest[2:]
    old_path = os.path.join(ROOT, 'config', 'custom-dimensions', 'dimensions', f'{slug}.json')
    new_path = os.path.join(ROOT, 'mods', 'custom-dimensions-26', 'definitions', f'{slug}.json')
    old = load(old_path) if os.path.exists(old_path) else {}
    new = load(new_path) if os.path.exists(new_path) else {}
    dossier = load(dossier_path)
    md_path = os.path.join(os.path.dirname(dossier_path), 'dossier.md')
    dossier_md = open(md_path).read() if os.path.exists(md_path) else ''
    shots = load(list_path)
    table = facts(slug, old, new, dossier, load_mapping(os.path.join(ROOT, 'mods', 'custom-dimensions-26', 'definitions', 'id-mapping.csv')))
    if facts_out:
        with open(facts_out, 'w') as f:
            json.dump(table, f, indent=1)
    b = [f'# Review packet: {slug}', '']
    b += ['## 1.21.1 intent', '', f"Source: `{os.path.relpath(old_path, ROOT)}`", '', f"> {old.get('description', '(no description)')}", '',
          'Biomes: ' + (', '.join(biome_ids(old.get('biomes'))) or 'none listed'), '']
    b += ['## v2 definition', '', f"Source: `{os.path.relpath(new_path, ROOT)}`", '', f"> {new.get('description', '(no description)')}", '',
          'Biomes: ' + (', '.join(biome_ids(new.get('biomes'))) or 'none listed'), '',
          'Terrain: `' + json.dumps(new.get('terrain', {})) + '`; generator `' + json.dumps(new.get('generator', {})) + '`', '',
          'Zones: ' + (', '.join(z.get('id', z.get('name', '?')) if isinstance(z, dict) else str(z) for z in new.get('zones', [])) or 'none'), '']
    if notes and os.path.exists(notes):
        b += ['## Owner notes', '', open(notes).read().strip(), '']
    b += ['## Survey', '', f"Dossier: `{dossier_path}`; layer maps in `{os.path.join(os.path.dirname(dossier_path), 'layers')}`", '']
    missing = dossier.get('biomeExpression', {}).get('listedNotFound', [])
    b += [summary_section(dossier_md), '']
    if missing:
        b += ['v2 biomes not found in any sampled column: ' + ', '.join(missing), '']
    b += facts_section(table)
    b += ['## Shots', '']
    for s in shots:
        name = s['name']
        png = os.path.join(shots_dir, f'{name}.png')
        frame = os.path.join(shots_dir, f'{name}.frame.json')
        census = load(frame) if os.path.exists(frame) else None
        b.append(f"### {name}")
        b.append('')
        if s.get('why'):
            b.append(f"Picked for: {s['why']}")
        if s.get('caption'):
            b.append(f"Old caption: {s['caption']}")
        b.append('Census: ' + (census['caption'] if census else 'none written'))
        b.append('Image: ' + (f'`{png}`' if os.path.exists(png) else (f"`{s['image']}` (earlier shot)" if s.get('image') else 'not taken')))
        b.append('')
    b += ['## Review questions', '', RULE, '']
    for i, q in enumerate(QUESTIONS, 1):
        b.append(f'{i}. {q}')
    b.append('')
    with open(out, 'w') as f:
        f.write('\n'.join(b))
    print(f'review packet -> {out} ({len(shots)} shots)')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
