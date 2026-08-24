#!/usr/bin/env python3
"""export-seed-winners.py - Fold a consumer's dimension overlay into the
platform's default dimension configs.

Context: the roller and viewer write into a consumer's overlay
(overlay/config/custom-dimensions/dimensions/<slug>.json). A winning seed only
means anything alongside the config that produced it — the biomes, borders,
type and structures are what make that seed worth having — so this exports the
WHOLE override, not just the seed. The result is that the platform config
alone reproduces the world the consumer was running.

Each overlay file is deep-merged over its platform counterpart using the same
rule the mod applies at boot (DimensionConfigLoader.deepMerge): objects merge
key-by-key, scalars AND arrays are replaced wholesale. The consumer's file then
has nothing the platform lacks, so --clean removes it.

The Picker also drops the winner's own renders beside its JSON as
<slug>_low.png / <slug>_high.png; those are copied across unchanged.

Usage:
  scripts/export-seed-winners.py [--consumer PATH] [--platform PATH]
                                 [--dry-run] [--clean]

  --consumer PATH  consumer repo root (default: $CONSUMER_DIR; no fallback)
  --platform PATH  platform repo root (default: this script's checkout)
  --dry-run        print what would move, change nothing
  --clean          delete each overlay file after folding it in

Template-only (platform development); deliberately NOT in the bundle
MANIFEST - consumers never run this.

Gotchas: the config drives every seed, including the overworld's -
ServerWorldSeedMixin reads each dimension's own file, so exporting a winner
here IS what changes that world's terrain, and the config must reach the
server BEFORE the world is deleted (TROUBLESHOOTING.md#t31). An overlay
file with no "overrides" wrapper is a FULL REPLACEMENT of the platform
default and is folded in as such. A file that merges to nothing is left
alone rather than deleted, because an empty {} overlay disables a dimension
and that is never what an export meant.
"""
import argparse
import copy
import difflib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from consumer_path import consumer_dir  # noqa: E402


def deep_merge(base, over):
    """DimensionConfigLoader.deepMerge: objects merge key-by-key, everything
    else - scalars and arrays alike - is replaced wholesale."""
    result = copy.deepcopy(base)
    for key, value in over.items():
        existing = result.get(key)
        if isinstance(existing, dict) and isinstance(value, dict):
            result[key] = deep_merge(existing, value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def overrides_of(doc):
    """The override body: the "overrides" block, or the whole doc when the
    file is a full replacement."""
    body = doc.get("overrides")
    return body if isinstance(body, dict) else doc


def has_comments(text):
    """Whole-line // comments, which a JSON round-trip would silently drop."""
    return any(line.lstrip().startswith("//") for line in text.splitlines())


def export_thumbnails(overlay_dir, template_dir, dry_run):
    copied = 0
    for src in sorted(overlay_dir.glob("*_low.png")) + sorted(overlay_dir.glob("*_high.png")):
        size = src.stat().st_size
        if dry_run:
            print(f"  {src.name}: would copy ({size} bytes)")
        else:
            (template_dir / src.name).write_bytes(src.read_bytes())
            print(f"  {src.name}: exported ({size} bytes)")
        copied += 1
    return copied


def main():
    parser = argparse.ArgumentParser(
        description="Fold a consumer's dimension overlay into the platform configs.")
    parser.add_argument("--consumer", default=None)
    parser.add_argument("--platform", default=None)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--clean", action="store_true",
                        help="delete each overlay file once it is folded in")
    args = parser.parse_args()

    platform_dir = (Path(args.platform).expanduser() if args.platform
                    else Path(__file__).resolve().parent.parent)
    template_dir = platform_dir / "config" / "custom-dimensions" / "dimensions"
    overlay_dir = (consumer_dir(args.consumer)
                   / "overlay" / "config" / "custom-dimensions" / "dimensions")
    if not overlay_dir.is_dir():
        sys.exit(f"no overlay dimensions directory at {overlay_dir}")

    changed = skipped = missing = keys = 0
    overworld_seed = None
    for overlay_path in sorted(overlay_dir.glob("*.json")):
        try:
            doc = json.loads(overlay_path.read_text())
        except (json.JSONDecodeError, OSError) as e:
            print(f"  WARN {overlay_path.name}: unreadable ({e}) - skipped")
            missing += 1
            continue
        body = overrides_of(doc) if isinstance(doc, dict) else None
        if not body:
            skipped += 1
            continue

        template = template_dir / overlay_path.name
        if not template.is_file():
            print(f"  WARN {overlay_path.name}: no template counterpart - skipped")
            missing += 1
            continue
        original = template.read_text()
        if has_comments(original):
            print(f"  WARN {overlay_path.name}: template carries // comments that a merge "
                  f"would drop - skipped, merge it by hand")
            missing += 1
            continue

        merged = deep_merge(json.loads(original), body)
        updated = json.dumps(merged, indent=2) + "\n"
        if updated == original:
            skipped += 1
            continue

        changed += 1
        keys += len(body)
        if isinstance(body.get("seed"), int) and overlay_path.name == "overworld.json":
            overworld_seed = body["seed"]

        if args.dry_run:
            sys.stdout.writelines(difflib.unified_diff(
                original.splitlines(keepends=True), updated.splitlines(keepends=True),
                fromfile=f"a/{overlay_path.name}", tofile=f"b/{overlay_path.name}"))
        else:
            template.write_text(updated)
            print(f"  {overlay_path.name}: folded in {len(body)} key(s)")
            if args.clean:
                overlay_path.unlink()

    png_copied = export_thumbnails(overlay_dir, template_dir, args.dry_run)

    verb = "would change" if args.dry_run else "changed"
    png_verb = "would copy" if args.dry_run else "copied"
    print(f"\n{verb} {changed} file(s) carrying {keys} override key(s); "
          f"{skipped} unchanged/empty; {missing} skipped; {png_verb} {png_copied} thumbnail(s)")
    if args.clean and not args.dry_run:
        print("overlay files deleted - the platform config alone now reproduces that world")
    if overworld_seed is not None:
        print(f"NOTE: the overworld winner is {overworld_seed}. Its terrain comes from "
              f"overworld.json, which this script just wrote - deploy that config BEFORE "
              f"the wipe. Passing the same seed to ./ops reset-seed only keeps level.dat's "
              f"reported seed consistent; it does not drive generation (T31).")


if __name__ == "__main__":
    main()
