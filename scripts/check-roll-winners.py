#!/usr/bin/env python3
"""check-roll-winners.py - Gate a consumer's rolled seed winners before they
are committed and deployed.

Context: RollPipeline.promoteBest writes a winner through Picker.write, which
adds "spawn" to the overrides block ONLY when the candidate recorded one.
spawnToPromote returns null when the candidate's facts are unreadable, when
column or surfaceHeight is unmeasured, or when the declared column has no real
surface and the banked grid has no cell that does. The platform config it
merges over still carries the OLD seed's top-level spawn, and deepMerge keeps
it - so a seed-only winner silently inherits a spawn point measured against
terrain that no longer exists. On a world that has just been reset, that is a
spawn in rock, in void, or underwater, and there is no re-roll.

This reads the overlay, not the log, and prints the denominator for every
count so a check that never ran cannot report success (TROUBLESHOOTING.md#t63).

Usage:
  scripts/check-roll-winners.py [--consumer PATH] [--platform PATH] [--json]

  --consumer PATH  consumer repo root (default: $CONSUMER_DIR; no fallback)
  --platform PATH  platform repo root (default: this script's checkout)
  --json           machine-readable report on stdout

Exits 1 on any winner that would deploy a stale spawn, any override that names
a dimension with no platform config, and any malformed overlay file. Exits 0
only when every override carries both keys.

Template-only (platform development); deliberately NOT in the bundle MANIFEST.

Gotchas: <slug>_thumb.json sidecars sit in the same directory and are viewer
artefacts, not overrides - they are skipped by name, and the skipped count is
printed so a naming change cannot silently empty the check. A dimension whose
platform config has no top-level spawn (the_canvas) cannot inherit a stale one,
so a seed-only winner there is reported as benign rather than as a failure. The
same holds for a `void` dimension: it has no terrain, so no surface exists to
measure a spawn on and the authored one is the only possible answer - measured
on the 81-dimension roll, all three void dimensions promoted seed-only and all
three were correct to.
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from consumer_path import consumer_dir  # noqa: E402

DIMS = "config/custom-dimensions/dimensions"


def overrides_of(doc):
    """The block Picker writes. A file with no wrapper is a full replacement
    and its top-level keys are the overrides."""
    block = doc.get("overrides")
    return block if isinstance(block, dict) else doc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--consumer", default=None)
    ap.add_argument("--platform", default=None)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    consumer = Path(args.consumer) if args.consumer else Path(consumer_dir())
    platform = Path(args.platform) if args.platform else Path(__file__).resolve().parent.parent

    overlay_dir = consumer / "overlay" / DIMS
    platform_dir = platform / DIMS

    if not overlay_dir.is_dir():
        print(f"FAIL  no overlay dimensions directory at {overlay_dir}")
        return 1

    stale_spawn, no_platform, malformed, benign, ok = [], [], [], [], []
    skipped_thumbs = 0

    for path in sorted(overlay_dir.glob("*.json")):
        if path.name.endswith("_thumb.json"):
            skipped_thumbs += 1
            continue
        slug = path.stem
        try:
            doc = json.loads(path.read_text())
        except (OSError, ValueError) as exc:
            malformed.append((slug, str(exc)))
            continue

        over = overrides_of(doc)
        if not isinstance(over, dict):
            malformed.append((slug, "overrides is not an object"))
            continue

        base_path = platform_dir / f"{slug}.json"
        if not base_path.is_file():
            no_platform.append(slug)
            continue

        if "spawn" in over and over["spawn"] is not None:
            ok.append(slug)
            continue

        # Seed-only winner. It inherits whatever the platform config holds.
        try:
            base = json.loads(base_path.read_text())
        except (OSError, ValueError) as exc:
            malformed.append((slug, f"platform config unreadable: {exc}"))
            continue

        # A void dimension has no terrain, so there is no surface to measure a
        # spawn on and the authored one is the only possible answer. Inheriting
        # it is correct, not a stale-spawn hazard.
        void = str(base.get("type", "")).lower() == "void"
        if base.get("spawn") is None or void:
            benign.append(slug)
        else:
            stale_spawn.append((slug, base["spawn"], over.get("seed")))

    total = len(ok) + len(benign) + len(stale_spawn) + len(no_platform) + len(malformed)
    failures = len(stale_spawn) + len(no_platform) + len(malformed)

    if args.json:
        json.dump(
            {
                "overrides_read": total,
                "thumbnails_skipped": skipped_thumbs,
                "with_spawn": len(ok),
                "seed_only_benign": benign,
                "seed_only_stale_spawn": [
                    {"dimension": s, "inherited_spawn": sp, "seed": sd} for s, sp, sd in stale_spawn
                ],
                "no_platform_config": no_platform,
                "malformed": [{"dimension": s, "error": e} for s, e in malformed],
                "exit": 1 if failures else 0,
            },
            sys.stdout,
            indent=2,
        )
        print()
        return 1 if failures else 0

    print(f"Read {total} override(s) from {overlay_dir}")
    print(f"  thumbnail sidecars skipped   {skipped_thumbs}")
    print(f"  carry seed AND spawn         {len(ok)} of {total}")
    print(f"  seed-only, benign (void or no authored spawn) {len(benign)} of {total}")
    if benign:
        print(f"    {', '.join(benign)}")

    if stale_spawn:
        print(f"\nFAIL  {len(stale_spawn)} of {total} would deploy a spawn measured against the OLD seed:")
        for slug, spawn, seed in stale_spawn:
            print(f"  {slug:<28} seed {seed} inherits spawn {spawn}")
        print("\n  Re-roll each, or write a measured spawn into its override, before deploying.")
    if no_platform:
        print(f"\nFAIL  {len(no_platform)} override(s) name a dimension with no platform config:")
        for slug in no_platform:
            print(f"  {slug}")
    if malformed:
        print(f"\nFAIL  {len(malformed)} unreadable override(s):")
        for slug, err in malformed:
            print(f"  {slug}: {err}")

    if not failures:
        print("\nPASS  every winner carries a seed and a usable spawn")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
