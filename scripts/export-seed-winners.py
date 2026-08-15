#!/usr/bin/env python3
"""export-seed-winners.py - Copy rolled winner seeds, spawns and thumbnails
from a consumer's overlay into the platform's default dimension configs.

Context: after a final roll and winner picking on the consumer (elfydd),
each chosen `seed` and `spawn` lives in an overlay "overrides" file
(overlay/config/custom-dimensions/dimensions/<slug>.json, written by the
roller / viewer). The platform defaults ship in
config/custom-dimensions/dimensions/<slug>.json. This tool surgically
replaces ONLY the top-level "seed" number and "spawn" array in each
template file — plain text substitution, no JSON round-trip, so JSONC
comments and formatting survive untouched.

The same `web/Picker` write also drops the winner's own low/high renders
beside its JSON, as <slug>_low.png / <slug>_high.png — this tool copies
those across unchanged (binary, no substitution to do) so the map sidebar
has a thumbnail for the exported dimension without the production server
ever rendering one itself.

Usage:
  scripts/export-seed-winners.py [--consumer PATH] [--dry-run]

  --consumer PATH  consumer repo root (default: ~/Projects/elfydd)
  --platform PATH  platform repo root (default: this script's checkout)
  --dry-run        print per-file unified diffs, change nothing

Template-only (platform development); deliberately NOT in the bundle
MANIFEST — consumers never run this.

Gotchas: the config drives every seed, including the overworld's —
ServerWorldSeedMixin reads each base world's own file (overworld.json,
the_nether.json, the_end.json, paradise_lost.json), so exporting a winner
here IS what changes that world's terrain. `.env SEED` seeds level.dat only,
and reaches terrain solely when a config's seed field is the literal string
"env" (see TROUBLESHOOTING.md#t31). Passing the overworld winner to
reset-seed keeps level.dat's reported seed consistent with the terrain; it is
not the lever that generates it. What actually matters is that these configs
reach the server BEFORE the world is deleted. Winner spawns of [0, 64, 0] are
the "not chosen" placeholder and are never exported.
"""
import argparse
import difflib
import json
import re
import sys
from pathlib import Path

PLACEHOLDER_SPAWN = [0, 64, 0]
SEED_RE = re.compile(r'(?m)^(\s*"seed"\s*:\s*)(null|-?\d+)')
SPAWN_RE = re.compile(r'(?s)(^[ \t]*)("spawn"\s*:\s*)\[[^\]]*\]', re.M)


def winner_fields(overlay_path):
    """(seed, spawn) from an overlay file — overrides-form or full-replacement."""
    try:
        data = json.loads(overlay_path.read_text())
    except (json.JSONDecodeError, OSError) as e:
        print(f"  WARN {overlay_path.name}: unreadable ({e}) — skipped")
        return None, None
    if not isinstance(data, dict):
        return None, None
    overrides = data.get("overrides")
    source = overrides if isinstance(overrides, dict) else data
    seed = source.get("seed")
    spawn = source.get("spawn")
    if not isinstance(seed, int):
        seed = None
    if not (isinstance(spawn, list) and len(spawn) == 3
            and all(isinstance(v, int) for v in spawn)) or spawn == PLACEHOLDER_SPAWN:
        spawn = None
    return seed, spawn


def replace_seed(text, seed, name):
    match = SEED_RE.search(text)
    if not match:
        # A template without the key gains it as the first member — every
        # dimension ships a rolled seed in its final form.
        print(f"  {name}: template had no \"seed\" line — inserted")
        return insert_after_open_brace(text, f'  "seed": {seed},\n')
    return text[:match.start()] + match.group(1) + str(seed) + text[match.end():]


def replace_spawn(text, spawn, name):
    match = SPAWN_RE.search(text)
    single = "[" + ", ".join(str(v) for v in spawn) + "]"
    if not match:
        seed_match = SEED_RE.search(text)
        line = f'  "spawn": {single},\n'
        if seed_match:
            end = text.find("\n", seed_match.end()) + 1
            print(f"  {name}: template had no \"spawn\" array — inserted")
            return text[:end] + line + text[end:]
        print(f"  {name}: template had no \"spawn\" array — inserted")
        return insert_after_open_brace(text, line)
    indent, prefix = match.group(1), match.group(2)
    if "\n" in match.group(0):
        inner = ",\n".join(f"{indent}  {v}" for v in spawn)
        replacement = f"{indent}{prefix}[\n{inner}\n{indent}]"
    else:
        replacement = indent + prefix + single
    return text[:match.start()] + replacement + text[match.end():]


def parses_as_jsonc(text):
    """Valid JSON after stripping whole-line // comments (the platform's
    JSONC contract — trailing comments are deliberately unsupported)."""
    stripped = "\n".join(
        "" if line.lstrip().startswith("//") else line
        for line in text.splitlines())
    try:
        json.loads(stripped)
        return True
    except json.JSONDecodeError:
        return False


def insert_after_open_brace(text, line):
    brace = text.find("{")
    newline = text.find("\n", brace)
    if brace < 0 or newline < 0:
        return text
    return text[:newline + 1] + line + text[newline + 1:]


def export_thumbnails(overlay_dir, template_dir, dry_run):
    """Copies every <slug>_low.png / <slug>_high.png Picker wrote beside a
    winner config into the platform template directory under the same name.
    Independent of the JSON substitution above — a re-pick at an unchanged
    seed can still swap in a different render, and there is nothing to
    diff for a binary file, so this always copies what it finds."""
    copied = 0
    sources = sorted(overlay_dir.glob("*_low.png")) + sorted(overlay_dir.glob("*_high.png"))
    for src in sources:
        dest = template_dir / src.name
        size = src.stat().st_size
        if dry_run:
            print(f"  {src.name}: would copy ({size} bytes)")
        else:
            dest.write_bytes(src.read_bytes())
            print(f"  {src.name}: exported ({size} bytes)")
        copied += 1
    return copied


def main():
    parser = argparse.ArgumentParser(
        description="Export rolled winner seeds/spawns from a consumer overlay "
                    "into the platform dimension configs.")
    parser.add_argument("--consumer", default=str(Path.home() / "Projects" / "elfydd"))
    parser.add_argument("--platform", default=None)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    platform_dir = (Path(args.platform).expanduser() if args.platform
                    else Path(__file__).resolve().parent.parent)
    template_dir = platform_dir / "config" / "custom-dimensions" / "dimensions"
    overlay_dir = (Path(args.consumer).expanduser()
                   / "overlay" / "config" / "custom-dimensions" / "dimensions")
    if not overlay_dir.is_dir():
        sys.exit(f"no overlay dimensions directory at {overlay_dir}")

    changed = skipped = missing = 0
    overworld_seed = None
    for overlay_path in sorted(overlay_dir.glob("*.json")):
        seed, spawn = winner_fields(overlay_path)
        if seed is None and spawn is None:
            skipped += 1
            continue
        template = template_dir / overlay_path.name
        if not template.is_file():
            print(f"  WARN {overlay_path.name}: no template counterpart — skipped")
            missing += 1
            continue
        original = template.read_text()
        updated = original
        if seed is not None:
            updated = replace_seed(updated, seed, overlay_path.name)
        if spawn is not None:
            updated = replace_spawn(updated, spawn, overlay_path.name)
        if updated == original:
            skipped += 1
            continue
        if not parses_as_jsonc(updated):
            print(f"  WARN {overlay_path.name}: substitution produced invalid JSON — "
                  f"not written; inspect the template by hand")
            missing += 1
            continue
        changed += 1
        if overlay_path.name == "overworld.json" and seed is not None:
            overworld_seed = seed
        if args.dry_run:
            diff = difflib.unified_diff(
                original.splitlines(keepends=True), updated.splitlines(keepends=True),
                fromfile=f"a/{overlay_path.name}", tofile=f"b/{overlay_path.name}")
            sys.stdout.writelines(diff)
        else:
            template.write_text(updated)
            print(f"  {overlay_path.name}: exported")

    png_copied = export_thumbnails(overlay_dir, template_dir, args.dry_run)

    verb = "would change" if args.dry_run else "changed"
    png_verb = "would copy" if args.dry_run else "copied"
    print(f"\n{verb} {changed} file(s); {skipped} unchanged/no-winner; "
          f"{missing} missing template(s); {png_verb} {png_copied} thumbnail(s)")
    if overworld_seed is not None:
        print(f"NOTE: the overworld winner is {overworld_seed}. Its terrain comes from "
              f"overworld.json, which this script just wrote — deploy that config BEFORE "
              f"the wipe. Passing the same seed to ./ops reset-seed only keeps level.dat's "
              f"reported seed consistent; it does not drive generation (T31).")


if __name__ == "__main__":
    main()
