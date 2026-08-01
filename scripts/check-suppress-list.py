#!/usr/bin/env python3
# =============================================================================
# check-suppress-list.py — does every suppressed structure set actually exist?
# =============================================================================
#
# Purpose:
#   settings.json's `"suppress": {"structures": [...]}` removes structure SET
#   ids from every dimension's noise pools and pass-throughs. An id that
#   matches nothing (typo, mod removed, wrong namespace) suppresses nothing —
#   the server WARNs once at boot and carries on. This checker catches the
#   same mistake offline, before a boot, by cross-referencing every suppress
#   list on disk against the extracted structure-set catalogue
#   (extractors/structures.json).
#
# Context:
#   Reads files only — no Docker, no RCON, no running server. Run by
#   `./dev verify`. The catalogue ships in the stack bundle
#   (config/custom-dimensions/extractors/structures.json), so consumers have
#   it at .stack/current/stack/config/custom-dimensions/.
#
# Usage:
#   ./scripts/check-suppress-list.py --data <consumer>/data \
#       --config <custom-dimensions config dir> \
#       [--overlay <consumer overlay custom-dimensions dir>] [--json]
#
#   Every settings.json findable from those roots is checked independently:
#   the platform file, the config dir's staged overlay/, the consumer repo's
#   overlay source, and the server's staged copies under data/config/. A bad
#   id anywhere is worth flagging — merge order never fixes a typo.
#
# Exit codes:
#   0  every suppressed id resolves to a known structure set (or none listed)
#   1  at least one suppressed id matches no known structure set
#   2  bad invocation / no extractors catalogue found
#
# Gotchas:
#   - Suppress entries are structure SET ids (e.g. `mvs:barn`), not structure
#     short names. The catalogue keys are set ids; for most mods set id ==
#     structure id, which hides the distinction until it bites.
#   - Matching is case-insensitive — the mod lowercases the exclude union.
#   - The boot-time WARN checks the LIVE registry; this checks the extracted
#     catalogue. A freshly added mod not yet re-extracted can false-flag —
#     re-run the extractor before trusting a failure after a mod change.
# =============================================================================
import argparse
import json
import sys
from pathlib import Path


def strip_json_comments(text):
    """JSONC, readers-first: a line whose first non-blank characters are //
    is blanked. The SAME narrow rule (whole-line only, no trailing
    comments, no lenient parsing) lives in
    DimensionConfigLoader.stripJsonComments (Java) and
    scripts/seed/dimension_profiles.strip_json_comments — change together."""
    if "//" not in text:
        return text
    import re
    return re.sub(r"^[ \t]*//.*$", "", text, flags=re.M)


def suppress_ids(settings_file):
    """The suppress.structures list from one settings.json, or None if the
    file is absent/unparseable/has no list. Ids are stripped, not lowercased —
    the report shows what the author wrote."""
    try:
        data = json.loads(strip_json_comments(settings_file.read_text()))
    except (OSError, json.JSONDecodeError):
        return None
    raw = (data.get("suppress") or {}).get("structures")
    if not isinstance(raw, list):
        return None
    return [str(i).strip() for i in raw if str(i).strip()]


def catalogue_sets(extractors_file):
    """Lowercased structure-set ids from extractors/structures.json."""
    data = json.loads(extractors_file.read_text())
    sets = data.get("structure_sets")
    if not isinstance(sets, dict) or not sets:
        raise ValueError(f"{extractors_file} carries no structure_sets map")
    return {k.lower() for k in sets}


def settings_sources(data_dir, config_dir, overlay_dir):
    """Every settings.json worth checking, labelled. Order is platform →
    overlay source → staged server copies."""
    out = []
    if config_dir:
        out.append(("config", Path(config_dir) / "settings.json"))
        out.append(("config overlay", Path(config_dir) / "overlay" / "settings.json"))
    if overlay_dir:
        out.append(("consumer overlay", Path(overlay_dir) / "settings.json"))
    if data_dir:
        staged = Path(data_dir) / "config" / "custom-dimensions"
        out.append(("staged", staged / "settings.json"))
        out.append(("staged overlay", staged / "overlay" / "settings.json"))
    # The same physical file can be reachable twice (platform checkouts pass
    # --data and --config into one tree) — report each file once.
    seen, unique = set(), []
    for label, path in out:
        key = path.resolve()
        if key not in seen:
            seen.add(key)
            unique.append((label, path))
    return unique


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", help="a consumer's data/ directory (staged settings.json copies)")
    ap.add_argument("--config", help="custom-dimensions config directory "
                                     "(platform settings.json + extractors/)")
    ap.add_argument("--overlay", help="consumer overlay custom-dimensions directory")
    ap.add_argument("--extractors", help="explicit path to extractors/structures.json "
                                         "(default: <config>/extractors/structures.json)")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args()

    if not (args.data or args.config):
        ap.error("give --data and/or --config")

    extractors = Path(args.extractors) if args.extractors else (
        Path(args.config) / "extractors" / "structures.json" if args.config else None)
    if extractors is None or not extractors.exists():
        print(f"FAIL no structure-set catalogue at "
              f"{extractors or '<unset — give --config or --extractors>'}")
        return 2
    try:
        known = catalogue_sets(extractors)
    except (json.JSONDecodeError, ValueError) as e:
        print(f"FAIL unreadable catalogue: {e}")
        return 2

    checked, unknown = [], []
    for label, path in settings_sources(args.data, args.config, args.overlay):
        ids = suppress_ids(path)
        if ids is None:
            continue
        bad = [i for i in ids if i.lower() not in known]
        checked.append({"source": label, "file": str(path),
                        "suppressed": ids, "unknown": bad})
        unknown.extend((label, path, i) for i in bad)

    if args.json:
        print(json.dumps({"catalogue": str(extractors), "knownSets": len(known),
                          "sources": checked}, indent=2))
        return 1 if unknown else 0

    if not checked:
        print(f"no suppress.structures list in any settings.json "
              f"({len(known)} sets in catalogue) — nothing to check")
        return 0
    for entry in checked:
        n = len(entry["suppressed"])
        state = "OK" if not entry["unknown"] else f"{len(entry['unknown'])} UNKNOWN"
        print(f"  {entry['source']}: {n} suppressed, {state}  ({entry['file']})")
    for label, path, bad_id in unknown:
        print(f"UNKNOWN {bad_id!r} ({label}) matches no structure set in the "
              f"catalogue — it suppresses nothing")
    if unknown:
        print(f"\n{len(unknown)} unknown id(s) vs {len(known)} catalogued sets. "
              f"Check for typos, or re-run the extractor if mods changed.")
        return 1
    total = sum(len(e["suppressed"]) for e in checked)
    print(f"\nall suppressed ids resolve ({total} across {len(checked)} "
          f"file(s), {len(known)} sets in catalogue)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
