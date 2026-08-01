#!/usr/bin/env python3
# =============================================================================
# gen-suppress-catalogue.py — a ready-to-edit suppress list of every known set
# =============================================================================
#
# Purpose:
#   Renders the extracted structure-set catalogue (extractors/structures.json)
#   into a commented settings.json: every structure set the pack knows about
#   appears as one commented-out line, grouped by mod and theme. Uncomment a
#   line to suppress that set everywhere; the file is a valid settings.json
#   with any subset of lines uncommented.
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
  // Global structure-set suppress catalogue.
  //
  // Every structure set known to this stack appears below as a commented
  // line, grouped by mod and theme. Uncommenting a line removes that set
  // from EVERY dimension's pools and pass-throughs (existing chunks keep
  // what they have — worldgen is creation-time-only).
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

FOOTER = """\
    ]
  }
}
"""


def strip_json_comments(text):
    """Whole-line // comments only — the shared contract in
    DimensionConfigLoader.stripJsonComments (Java) and
    scripts/seed/dimension_profiles.strip_json_comments. Change together."""
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


def render(sets):
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
    lines.append(FOOTER)
    return "".join(lines)


def self_check(text, sets):
    """The emitted text must parse commented AND fully uncommented, and the
    uncommented form must carry exactly the catalogue's ids after the
    sentinel. Any failure here is a generator bug, never a data problem."""
    as_is = json.loads(strip_json_comments(text))
    if as_is["suppress"]["structures"] != [""]:
        raise SystemExit("FAIL self-check: commented form must suppress nothing")
    uncommented = re.sub(r"^([ \t]*)// (, \".*\")$", r"\1\2", text, flags=re.M)
    full = json.loads(strip_json_comments(uncommented))
    ids = [i for i in full["suppress"]["structures"] if i]
    if sorted(ids) != sorted(sets):
        raise SystemExit("FAIL self-check: uncommented form must list every set")


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
    text = render(sets)
    self_check(text, sets)

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
