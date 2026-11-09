#!/usr/bin/env python3
"""review-packet.py — one dimension's review packet: intent, measurement and shots side by side.

Context: template-only rig tooling for mods/custom-dimensions-client-26 (stdlib only). Writes review.md from
  the 1.21.1 definition (config/custom-dimensions/dimensions/<slug>.json, read-only: RB-35): description, biome list;
  the v2 definition (mods/custom-dimensions-26/definitions/<slug>.json): description, biomes, terrain, zones;
  an optional owner-notes file;
  the survey dossier (dossier.md's summary and the biome-expression list);
  each shot of the rig list that has a png or a frame census in the screenshots folder: its census caption and image.
It ends with the fixed review questions. The reviewer answers them; the script only assembles.

Usage: review-packet.py <slug> <dossier.json> <shot list.json> <screenshots dir> <out review.md> [--notes FILE]
Gotchas: run from anywhere; the repository root is found from this file's location. A shot whose png is missing is
listed as not taken, with its census if one exists.
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
QUESTIONS = [
    "Taken together, do the shots show the world's dominant character as the dossier measures it?",
    "Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.",
    "Which elements of the 1.21.1 description or biome list are absent from the dossier?",
    "Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?",
    "Fidelity to the 1.21.1 intent, 0-5, with one line why.",
]


def load(path):
    with open(path) as f:
        return json.load(f)


def biome_ids(biomes):
    out = []
    for b in biomes or []:
        out.append(b if isinstance(b, str) else b.get('id', '?'))
    return out


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
    notes = None
    if len(argv) > 7 and argv[6] == '--notes':
        notes = argv[7]
    old_path = os.path.join(ROOT, 'config', 'custom-dimensions', 'dimensions', f'{slug}.json')
    new_path = os.path.join(ROOT, 'mods', 'custom-dimensions-26', 'definitions', f'{slug}.json')
    old = load(old_path) if os.path.exists(old_path) else {}
    new = load(new_path) if os.path.exists(new_path) else {}
    dossier = load(dossier_path)
    md_path = os.path.join(os.path.dirname(dossier_path), 'dossier.md')
    dossier_md = open(md_path).read() if os.path.exists(md_path) else ''
    shots = load(list_path)
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
    b += ['## Review questions', '']
    for i, q in enumerate(QUESTIONS, 1):
        b.append(f'{i}. {q}')
    b.append('')
    with open(out, 'w') as f:
        f.write('\n'.join(b))
    print(f'review packet -> {out} ({len(shots)} shots)')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
