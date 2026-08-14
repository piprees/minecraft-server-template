#!/usr/bin/env python3
# =============================================================================
# gen-suppress-catalogue.py — a ready-to-edit suppress list of every known set
# =============================================================================
#
# Purpose:
#   Renders the extracted catalogues (extractors/structures.json +
#   biomes.json) into a commented settings.json: every structure set and
#   biome the pack knows about appears as one commented-out line, grouped
#   by mod (structures also by theme). Uncomment a line to suppress it
#   everywhere — biome suppression covers every world, base worlds
#   included; the file is a valid settings.json with any subset of lines
#   uncommented.
#
# Context:
#   Reads files only — no Docker, no RCON, no running server. The output is
#   meant to live at overlay/config/custom-dimensions/settings.json in a
#   consumer repo (or be merged into an existing one). Bundle script, so
#   consumers run it against .stack/current/stack/config/custom-dimensions.
#
# Usage:
#   ./scripts/gen-suppress-catalogue.py --config <custom-dimensions dir> \
#       [--output <file>]
#
#   --config   directory holding extractors/structures.json
#   --output   write here instead of stdout (backs up an existing file first)
#
# Gotchas:
#   - The JSONC contract is WHOLE-LINE comments only (see
#     DimensionConfigLoader.stripJsonComments) — never add a trailing comment
#     to an entry line; it would become live text when uncommented.
#   - Entries carry a LEADING comma and follow a blank-string sentinel, so
#     uncommenting any subset always parses strictly (no trailing-comma trap).
#     All readers ignore blank ids, so the sentinel suppresses nothing.
#   - The generator self-checks: the emitted text must parse as-is AND with
#     every entry uncommented, and the uncommented form must list every set.
# =============================================================================
import argparse
import json
import re
import sys
from pathlib import Path

HEADER = """\
{
  // Global suppress catalogue: structure sets, then biomes.
  //
  // Every structure set and biome known to this stack appears below as a
  // commented line, grouped by mod (structure sets also by theme).
  // Uncommenting a structure-set line removes it from EVERY dimension's
  // pools and pass-throughs; uncommenting a biome line removes it from
  // EVERY world's biome source, base worlds included (existing chunks
  // keep what they have — worldgen is creation-time-only per chunk).
  //
  // To use: uncomment the lines you want, then save this file as
  //   overlay/config/custom-dimensions/settings.json
  // Comments must fill a whole line — never add one after an entry.
  // The leading commas are load-bearing: they keep the file valid JSON
  // whichever subset of lines you uncomment. Verify with ./dev verify.
  "suppress": {
    "structures": [
      ""
"""

STRUCTURES_FOOTER = """\
    ],
    "biomes": [
      ""
"""

FOOTER = """\
    ]
  }
}
"""


def strip_json_comments(text):
    """Whole-line // comments only — the shared contract in
    DimensionConfigLoader.stripJsonComments (Java) and
    DimensionConfigLoader.stripJsonComments. Change together."""
    if "//" not in text:
        return text
    return re.sub(r"^[ \t]*//.*$", "", text, flags=re.M)


def load_sets(extractors_file):
    data = json.loads(extractors_file.read_text())
    sets = data.get("structure_sets")
    if not isinstance(sets, dict) or not sets:
        raise SystemExit(f"FAIL {extractors_file} carries no structure_sets map")
    return sets


def mod_label(namespace, entries):
    """`namespace — N sets (source jar)`, jar taken from the first entry."""
    sources = {e.get("source", "?") for _, e in entries}
    src = sorted(sources)[0] + (", …" if len(sources) > 1 else "")
    return f"{namespace} — {len(entries)} set(s)  [{src}]"


def render(sets, biomes):
    by_ns = {}
    for set_id, meta in sets.items():
        ns = set_id.split(":", 1)[0]
        by_ns.setdefault(ns, []).append((set_id, meta))
    lines = [HEADER]
    for ns in sorted(by_ns):
        entries = sorted(by_ns[ns])
        lines.append(f"      // ═══ {mod_label(ns, entries)} ═══\n")
        by_theme = {}
        for set_id, meta in entries:
            by_theme.setdefault(meta.get("theme") or "unthemed", []).append(set_id)
        for theme in sorted(by_theme):
            lines.append(f"      // {theme}:\n")
            for set_id in by_theme[theme]:
                lines.append(f'      // , {json.dumps(set_id)}\n')
    lines.append(STRUCTURES_FOOTER)
    biomes_by_ns = {}
    for biome_id, meta in (biomes or {}).items():
        ns = biome_id.split(":", 1)[0]
        biomes_by_ns.setdefault(ns, []).append((biome_id, meta))
    for ns in sorted(biomes_by_ns):
        entries = sorted(biomes_by_ns[ns])
        lines.append(f"      // ═══ {mod_label(ns, entries)} ═══\n")
        for biome_id, _meta in entries:
            lines.append(f'      // , {json.dumps(biome_id)}\n')
    lines.append(FOOTER)
    return "".join(lines)


def self_check(text, sets, biomes):
    """The emitted text must parse commented AND fully uncommented, and the
    uncommented form must carry exactly the catalogues' ids after the
    sentinels. Any failure here is a generator bug, never a data problem."""
    as_is = json.loads(strip_json_comments(text))
    if as_is["suppress"]["structures"] != [""] or as_is["suppress"]["biomes"] != [""]:
        raise SystemExit("FAIL self-check: commented form must suppress nothing")
    uncommented = re.sub(r"^([ \t]*)// (, \".*\")$", r"\1\2", text, flags=re.M)
    full = json.loads(strip_json_comments(uncommented))
    ids = [i for i in full["suppress"]["structures"] if i]
    if sorted(ids) != sorted(sets):
        raise SystemExit("FAIL self-check: uncommented form must list every set")
    biome_ids = [i for i in full["suppress"]["biomes"] if i]
    if sorted(biome_ids) != sorted(biomes or {}):
        raise SystemExit("FAIL self-check: uncommented form must list every biome")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True,
                    help="custom-dimensions config directory (extractors/)")
    ap.add_argument("--output", help="write here instead of stdout")
    args = ap.parse_args()

    extractors = Path(args.config) / "extractors" / "structures.json"
    if not extractors.exists():
        raise SystemExit(f"FAIL no structure-set catalogue at {extractors}")
    sets = load_sets(extractors)
    biomes_file = Path(args.config) / "extractors" / "biomes.json"
    biomes = {}
    if biomes_file.exists():
        biomes = json.loads(biomes_file.read_text()).get("biomes") or {}
    text = render(sets, biomes)
    self_check(text, sets, biomes)

    if args.output:
        out = Path(args.output)
        if out.exists():
            from datetime import datetime
            bak = out.with_name(out.name + ".bak." + datetime.now().strftime("%Y%m%d%H%M%S"))
            bak.write_text(out.read_text())
            print(f"backed up existing file to {bak}", file=sys.stderr)
        out.write_text(text)
        print(f"{len(sets)} sets catalogued to {out}", file=sys.stderr)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
